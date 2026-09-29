package de.timowa.expenselog.dropbox;

import com.dropbox.core.v2.files.UploadErrorException;
import com.dropbox.core.v2.files.WriteMode;

import java.util.function.LongSupplier;

import de.timowa.expenselog.DBAdapter;

/**
 * The decisions Dropbox sync makes, kept free of network and Android calls so they can be tested.
 *
 * <p>Sync is whole-file handover, and stays that way: the database on Dropbox is replaced
 * wholesale, by whichever device writes it. What these rules add is that a device only replaces
 * the remote file when the remote is still the version it last saw, and only replaces its own
 * database when nothing changed locally since it decided to. docs/history/RELIABILITY_PLAN.md, Step 7.
 *
 * <p>Two preferences drive everything. {@code pref_key_current_dropbox_db_version} -- the
 * <em>marker</em> -- holds the Dropbox version this database was last in step with, or
 * {@link DBAdapter#KEY_DATABASE_CHANGE} once anything changed locally. {@code last_known_dropbox_rev}
 * holds the last remote version this device saw, whatever happened locally since.
 */
public final class DropboxSyncRules {

    /** What {@code DropboxSyncCheck} has always read when no last-known version was stored. */
    static final String NO_LAST_KNOWN_REV = "gibberish";

    /** What a sync check concludes. */
    enum Action {
        /** Local is ahead of a remote nobody else has changed: upload it. */
        UPLOAD,
        /** The remote is ahead of an unchanged local database: download it. */
        DOWNLOAD,
        /** Both are the same version. */
        NOTHING,
        /** Both changed since the last sync: ask the user. */
        CONFLICT
    }

    private DropboxSyncRules() {
    }

    /**
     * The sync check's decision, exactly as {@code DropboxSyncCheck} has always made it.
     *
     * @param marker           the stored marker; empty if this device never synced
     * @param lastKnownRev     the last remote version this device saw
     * @param remoteRev        the remote file's version, or null if there is no remote file
     * @param localRecordCount asked only when the marker says the database changed locally
     */
    static Action decide(String marker, String lastKnownRev, String remoteRev,
                         LongSupplier localRecordCount) {
        if (remoteRev == null)
            return Action.UPLOAD;
        if (marker == null || marker.isEmpty())
            return Action.DOWNLOAD;
        if (DBAdapter.KEY_DATABASE_CHANGE.equals(marker)) {
            if (localRecordCount.getAsLong() == 0)
                return Action.DOWNLOAD;
            return remoteRev.equals(lastKnownRev) ? Action.UPLOAD : Action.CONFLICT;
        }
        return marker.equals(remoteRev) ? Action.NOTHING : Action.DOWNLOAD;
    }

    /**
     * How to write the main database file to Dropbox.
     *
     * <p>Every upload used to be {@code OVERWRITE}, including the one every save triggers without a
     * sync check: a device whose view of Dropbox was stale replaced another device's upload and
     * then recorded the result as in sync, so no conflict was ever shown. An upload now only
     * succeeds if the remote is still at {@code lastKnownRev} ({@code update}), or still absent if
     * this device never saw one ({@code ADD}); otherwise Dropbox refuses it as a conflict.
     *
     * @param forced true only where the user explicitly chose to replace the remote: "keep local"
     *               in the conflict dialog, and the upload that follows a restore
     */
    static WriteMode writeModeFor(String lastKnownRev, boolean forced) {
        if (forced)
            return WriteMode.OVERWRITE;
        if (lastKnownRev == null || lastKnownRev.isEmpty() || NO_LAST_KNOWN_REV.equals(lastKnownRev))
            return WriteMode.ADD;
        try {
            return WriteMode.update(lastKnownRev);
        } catch (IllegalArgumentException e) {
            // The SDK validates the version's format. A stored value that is not one cannot be a
            // version this device saw; ADD refuses to replace anything, so it ends in the conflict
            // dialog rather than in an overwrite or a crash.
            return WriteMode.ADD;
        }
    }

    /** Whether an upload was refused because the remote is not the version it was conditional on. */
    static boolean isConflict(UploadErrorException e) {
        return e.errorValue != null && e.errorValue.isPath()
                && e.errorValue.getPathValue().getReason().isConflict();
    }

    /**
     * Whether a download the sync check decided on may still replace the local database.
     *
     * <p>The check reads the marker, talks to Dropbox, and only then starts the download; a record
     * saved in between changed the marker but not the decision, and the download overwrote it.
     *
     * @param markerSeenByCheck the marker when the check decided, or null for a download the user
     *                          chose explicitly (keep remote, restore), which always proceeds
     */
    static boolean mayOverwriteLocal(String markerSeenByCheck, String markerNow) {
        return markerSeenByCheck == null || markerSeenByCheck.equals(markerNow);
    }

    /**
     * As {@link #mayOverwriteLocal(String, String)}, and also when the check saw the "changed"
     * marker: a save cannot change that marker any further, so the only sign of one is the record
     * count the check's decision rested on ({@link #decide} downloads over a changed database only
     * when it holds no records).
     */
    static boolean mayOverwriteLocal(String markerSeenByCheck, String markerNow,
                                     LongSupplier localRecordCount) {
        if (!mayOverwriteLocal(markerSeenByCheck, markerNow))
            return false;
        return !DBAdapter.KEY_DATABASE_CHANGE.equals(markerSeenByCheck)
                || localRecordCount.getAsLong() == 0;
    }

    /**
     * Whether an upload may record its new version as the marker.
     *
     * <p>Only if nothing changed locally while it ran. A change during the upload -- a delete, an
     * edited category -- set the marker to "changed" after the snapshot was taken; overwriting that
     * with the uploaded version declared the change synced when it never reached Dropbox.
     */
    static boolean mayRecordUploadAsCurrent(String markerBeforeSnapshot, String markerNow) {
        return markerBeforeSnapshot != null && markerBeforeSnapshot.equals(markerNow);
    }

    /**
     * As {@link #mayRecordUploadAsCurrent(String, String)}, and also only if no change was counted
     * while the upload ran. The marker alone cannot see a change made while it already reads
     * "changed" -- which it always does for the upload a save starts -- so a delete during that
     * upload was declared synced. See {@code DBAdapter.changeGeneration}.
     */
    static boolean mayRecordUploadAsCurrent(String markerBeforeSnapshot, String markerNow,
                                            long generationBefore, long generationNow) {
        return generationBefore == generationNow
                && mayRecordUploadAsCurrent(markerBeforeSnapshot, markerNow);
    }
}
