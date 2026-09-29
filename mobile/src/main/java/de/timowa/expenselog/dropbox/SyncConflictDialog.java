package de.timowa.expenselog.dropbox;

import de.timowa.expenselog.AppLocale;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;

import de.timowa.expenselog.R;

/**
 * The "Syncing Conflict" dialog: local and remote both changed since the last sync, and the user
 * has to say which one wins.
 *
 * <p><b>Why this is its own class, and why it does not use a list.</b> It used to be built inline
 * in {@link DropboxSyncCheck} out of {@code setItems} plus {@code setView}: the two timestamps
 * were <i>list rows</i>, and tapping one was how you chose. Device testing on a small screen
 * (Unihertz Jelly Star, 2026-09-04) found that nobody can tell. The rows carry no button styling,
 * and the sentence explaining that they are a choice renders <i>below</i> them, because
 * {@code AlertDialog} always puts {@code customPanel} under {@code contentPanel}. What the user
 * sees is two lines of status text, a question, and one tappable-looking control: Cancel.
 *
 * <p>The reason the original went that way is real: {@code AlertDialog} cannot show a message and
 * a list at the same time — {@code AlertController.setupContent} installs the list only in the
 * {@code else} branch of {@code if (mMessage != null)} — so explanatory text alongside a list
 * needs a custom view, which lands in the wrong place. Making the choices ordinary dialog buttons
 * sidesteps that: the message can then say everything, and the buttons look like buttons.
 *
 * <p><b>The button bar is not guaranteed room, so the message is kept short.</b> This was measured
 * rather than assumed, on an emulator squeezed to the Jelly Star's geometry (480x854 at 240dpi, so
 * 320dp wide). {@code AlertDialogLayout} scrolls the message when space runs out, but once the
 * three buttons are too wide for one row they stack, and a stacked button bar that outgrows the
 * window is <i>clipped</i> rather than scrolled: the first draft of this dialog lost half of
 * "Keep remote" and all of "Cancel" at a 1.3x font scale, which is the same failure the rewrite
 * was meant to end. Trimming the message to four short lines fixed that with room to spare.
 *
 * <p>Verified 2026-09-04 at 320dp: fine at a 1.0x and 1.3x font scale, and still clipped at 2.0x
 * (Android's accessibility maximum), where the message alone fills the window. Ending that for
 * good means moving the two choices into a {@code ScrollView} of our own so nothing but a
 * single-row Cancel is ever pinned — worth doing if anyone runs this app at a large font scale,
 * and not done yet. <b>Keep the message short; every line of it comes out of the button bar.</b>
 *
 * <p>Choosing here is not destructive on its own. Both choices route back through
 * {@code DropBoxHelper.KEY_DROPBOX_SYNC_CONFLICT_RESOLVE}, which asks for a second confirmation
 * naming the consequence before anything is uploaded or downloaded.
 */
public final class SyncConflictDialog {

    /** Which side the user picked, or nothing at all if they dismissed the dialog. */
    public interface OnChoice {
        void onKeepLocal();

        void onKeepRemote();
    }

    private SyncConflictDialog() {
    }

    /**
     * @param localModifiedMillis  when the local database was last written
     * @param remoteModifiedMillis the remote file's {@code serverModified} time
     */
    public static boolean show(Context context, long localModifiedMillis, long remoteModifiedMillis,
                               final OnChoice listener) {
        AlertDialog current = showing();
        if (current != null) {
            // Already asking. Two sync checks can reach the same conflict moments apart -- the
            // download that stood down for a record saved during it, and that save's own refused
            // upload -- and each used to open a dialog. Answering "keep local" on one and "keep
            // remote" on the other would then upload and download over each other.
            return false;
        }
        AlertDialog dialog = create(context, localModifiedMillis, remoteModifiedMillis, listener);
        dialog.show();
        sShowing = new WeakReference<>(dialog);
        return true;
    }

    /** The conflict dialog currently on screen, if any. Main thread only, like the dialog itself. */
    static AlertDialog showing() {
        AlertDialog dialog = sShowing == null ? null : sShowing.get();
        return dialog != null && dialog.isShowing() ? dialog : null;
    }

    private static WeakReference<AlertDialog> sShowing;

    /** Builds the dialog without showing it, so a test can assert on its buttons. */
    static AlertDialog create(Context context, long localModifiedMillis, long remoteModifiedMillis,
                              final OnChoice listener) {
        boolean remoteIsNewer = remoteModifiedMillis > localModifiedMillis;
        String newer = context.getString(R.string.sync_conflict_newer);

        // One line per side, and no more words than necessary. Every extra line of message is
        // height the button bar does not get on a small screen -- see the class comment.
        String message = context.getString(R.string.sync_conflict_msg)
                + "\n\n"
                + context.getString(R.string.local_last_modified) + ": "
                + formatTimestamp(localModifiedMillis) + (remoteIsNewer ? "" : " " + newer)
                + "\n"
                + context.getString(R.string.remote_last_modified) + ": "
                + formatTimestamp(remoteModifiedMillis) + (remoteIsNewer ? " " + newer : "");

        return new AlertDialog.Builder(context)
                .setTitle(R.string.syncing_conflict)
                .setMessage(message)
                .setPositiveButton(R.string.sync_conflict_keep_local,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                listener.onKeepLocal();
                            }
                        })
                .setNegativeButton(R.string.sync_conflict_keep_remote,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                listener.onKeepRemote();
                            }
                        })
                .setNeutralButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                })
                .create();
    }

    /** Unchanged from what the dialog showed before, so the two timestamps still read the same. */
    private static String formatTimestamp(long millis) {
        Date date = new Date(millis);
        return new SimpleDateFormat("MMM dd, yyyy", AppLocale.TEXT).format(date)
                + " " + new SimpleDateFormat("h:mm a", AppLocale.TEXT).format(date);
    }
}
