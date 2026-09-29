package de.timowa.expenselog.dropbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.dropbox.core.v2.files.UploadError;
import com.dropbox.core.v2.files.UploadErrorException;
import com.dropbox.core.v2.files.UploadWriteFailed;
import com.dropbox.core.v2.files.WriteConflictError;
import com.dropbox.core.v2.files.WriteError;
import com.dropbox.core.v2.files.WriteMode;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.LongSupplier;

import de.timowa.expenselog.DBAdapter;

/**
 * The rules Dropbox sync follows, without a network. docs/history/RELIABILITY_PLAN.md, Step 7 (F4, F23, F25).
 */
@RunWith(AndroidJUnit4.class)
public class DropboxSyncRulesTest {

    private static final String CHANGED = DBAdapter.KEY_DATABASE_CHANGE;
    private static final LongSupplier SOME_RECORDS = () -> 12;
    private static final LongSupplier NO_RECORDS = () -> 0;
    private static final LongSupplier NOT_ASKED = () -> {
        throw new AssertionError("the record count is only needed for a local change");
    };

    // --- writeModeFor -------------------------------------------------------------------------

    @Test
    public void anUploadAfterASync_isConditionalOnTheLastKnownVersion() {
        String rev = "0165b751a566ba100000003815d0f83"; // a real version, from the test account
        assertEquals(WriteMode.update(rev), DropboxSyncRules.writeModeFor(rev, false));
    }

    @Test
    public void aStoredVersionDropboxWouldReject_neverOverwrites() {
        assertEquals(WriteMode.ADD, DropboxSyncRules.writeModeFor("rev-7", false));
        assertEquals(WriteMode.ADD, DropboxSyncRules.writeModeFor(DBAdapter.KEY_DATABASE_CHANGE, false));
    }

    @Test
    public void anUploadByADeviceThatNeverSawARemote_mayOnlyCreateTheFile() {
        assertEquals(WriteMode.ADD, DropboxSyncRules.writeModeFor(null, false));
        assertEquals(WriteMode.ADD, DropboxSyncRules.writeModeFor("", false));
        assertEquals(WriteMode.ADD, DropboxSyncRules.writeModeFor(DropboxSyncRules.NO_LAST_KNOWN_REV, false));
    }

    @Test
    public void onlyAnUploadTheUserChose_overwrites() {
        assertEquals(WriteMode.OVERWRITE, DropboxSyncRules.writeModeFor("0165b751a566ba100000003815d0f83", true));
        assertEquals(WriteMode.OVERWRITE, DropboxSyncRules.writeModeFor(null, true));
    }

    @Test
    public void aWriteConflict_isRecognised_andOtherUploadErrorsAreNot() {
        UploadErrorException conflict = new UploadErrorException("2/files/upload", "req", null,
                UploadError.path(new UploadWriteFailed(WriteError.conflict(WriteConflictError.FILE), "s")));
        UploadErrorException noSpace = new UploadErrorException("2/files/upload", "req", null,
                UploadError.path(new UploadWriteFailed(WriteError.INSUFFICIENT_SPACE, "s")));
        UploadErrorException tooLarge = new UploadErrorException("2/files/upload", "req", null,
                UploadError.PAYLOAD_TOO_LARGE);
        assertTrue(DropboxSyncRules.isConflict(conflict));
        assertFalse(DropboxSyncRules.isConflict(noSpace));
        assertFalse(DropboxSyncRules.isConflict(tooLarge));
    }

    // --- decide: a characterisation of DropboxSyncCheck as it was before the extraction ----------

    @Test
    public void noRemoteFile_uploads() {
        assertEquals(DropboxSyncRules.Action.UPLOAD, DropboxSyncRules.decide("", "x", null, NOT_ASKED));
        assertEquals(DropboxSyncRules.Action.UPLOAD, DropboxSyncRules.decide(CHANGED, "x", null, NOT_ASKED));
    }

    @Test
    public void aDeviceThatNeverSynced_downloads() {
        assertEquals(DropboxSyncRules.Action.DOWNLOAD, DropboxSyncRules.decide("", "gibberish", "r1", NOT_ASKED));
    }

    @Test
    public void aLocalChangeWithNoRecords_downloads() {
        assertEquals(DropboxSyncRules.Action.DOWNLOAD, DropboxSyncRules.decide(CHANGED, "r0", "r1", NO_RECORDS));
    }

    @Test
    public void aLocalChangeOnAnUnchangedRemote_uploads() {
        assertEquals(DropboxSyncRules.Action.UPLOAD, DropboxSyncRules.decide(CHANGED, "r1", "r1", SOME_RECORDS));
    }

    @Test
    public void aLocalChangeOnAChangedRemote_isAConflict() {
        assertEquals(DropboxSyncRules.Action.CONFLICT, DropboxSyncRules.decide(CHANGED, "r0", "r1", SOME_RECORDS));
        assertEquals(DropboxSyncRules.Action.CONFLICT,
                DropboxSyncRules.decide(CHANGED, DropboxSyncRules.NO_LAST_KNOWN_REV, "r1", SOME_RECORDS));
    }

    @Test
    public void theSameVersionOnBothSides_doesNothing() {
        assertEquals(DropboxSyncRules.Action.NOTHING, DropboxSyncRules.decide("r1", "r1", "r1", NOT_ASKED));
    }

    @Test
    public void anUnchangedLocalBehindTheRemote_downloads() {
        assertEquals(DropboxSyncRules.Action.DOWNLOAD, DropboxSyncRules.decide("r0", "r0", "r1", NOT_ASKED));
    }

    // --- mayOverwriteLocal / mayRecordUploadAsCurrent -------------------------------------------

    @Test
    public void aDownloadTheCheckChose_standsDownIfARecordWasSavedMeanwhile() {
        assertTrue(DropboxSyncRules.mayOverwriteLocal("r0", "r0"));
        assertTrue(DropboxSyncRules.mayOverwriteLocal("", ""));
        assertFalse(DropboxSyncRules.mayOverwriteLocal("r0", CHANGED));
        assertFalse(DropboxSyncRules.mayOverwriteLocal("", CHANGED));
    }

    @Test
    public void aDownloadTheUserChose_alwaysProceeds() {
        assertTrue(DropboxSyncRules.mayOverwriteLocal(null, CHANGED));
    }

    @Test
    public void anUploadRecordsItsVersion_onlyIfNothingChangedWhileItRan() {
        assertTrue(DropboxSyncRules.mayRecordUploadAsCurrent(CHANGED, CHANGED));
        assertFalse(DropboxSyncRules.mayRecordUploadAsCurrent("r1", CHANGED));
        assertFalse(DropboxSyncRules.mayRecordUploadAsCurrent(null, CHANGED));
    }

    @Test
    public void anUploadDoesNotRecordItsVersion_ifAChangeWasCountedWhileTheMarkerAlreadySaidChanged() {
        assertTrue(DropboxSyncRules.mayRecordUploadAsCurrent(CHANGED, CHANGED, 4, 4));
        assertFalse(DropboxSyncRules.mayRecordUploadAsCurrent(CHANGED, CHANGED, 4, 5));
        assertFalse(DropboxSyncRules.mayRecordUploadAsCurrent("r1", CHANGED, 4, 4));
    }

    @Test
    public void aDownloadOverAnEmptyChangedDatabase_standsDownOnceARecordExists() {
        assertTrue(DropboxSyncRules.mayOverwriteLocal(CHANGED, CHANGED, () -> 0));
        assertFalse(DropboxSyncRules.mayOverwriteLocal(CHANGED, CHANGED, () -> 1));
        assertTrue(DropboxSyncRules.mayOverwriteLocal("r0", "r0", () -> 7));
        assertTrue(DropboxSyncRules.mayOverwriteLocal(null, CHANGED, () -> 7));
    }
}
