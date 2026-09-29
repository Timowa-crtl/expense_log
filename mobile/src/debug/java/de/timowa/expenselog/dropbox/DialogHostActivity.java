package de.timowa.expenselog.dropbox;

import android.app.Activity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.widget.Toast;

/**
 * An empty activity that exists only to give a dialog somewhere to attach.
 *
 * <p>It lives in {@code src/debug/}, so it is in the debug APK and never in a release one. It
 * cannot live in {@code src/androidTest/}: an activity declared there belongs to the test package
 * and runs in the test process, which instrumentation targeting the app refuses to launch.
 *
 * <p>The app's own activities would give a dialog a window too, but every one of them builds the
 * Dagger graph and opens the real database on the way up — see {@code IsolatedDatabaseContext} for
 * why a test that touches the live database is a data-loss bug rather than a slow test.
 *
 * <p><b>It also doubles as a preview.</b> {@link SyncConflictDialog} only appears when local and
 * remote have both changed since the last sync, which takes two devices and some patience to
 * arrange — and the bug it was rebuilt for was purely about how the thing looks on a small screen.
 * Launched with the extra below it just shows the dialog against sample timestamps, so it can be
 * looked at on any phone:
 *
 * <pre>
 * adb shell am start -n de.timowa.expenselog/.dropbox.DialogHostActivity --ez show_conflict true
 * </pre>
 */
public class DialogHostActivity extends Activity {

    /** Launch with this to preview the sync-conflict dialog; see the class comment. */
    public static final String EXTRA_SHOW_CONFLICT = "show_conflict";

    /** 2026-09-03, 42 minutes apart, remote newer — the pair from the device report. */
    private static final long SAMPLE_LOCAL_MILLIS = 1_788_457_080_000L;
    private static final long SAMPLE_REMOTE_MILLIS = 1_788_459_600_000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!getIntent().getBooleanExtra(EXTRA_SHOW_CONFLICT, false)) return;

        SyncConflictDialog.OnChoice reportChoice = new SyncConflictDialog.OnChoice() {
            @Override
            public void onKeepLocal() {
                report("keep local");
            }

            @Override
            public void onKeepRemote() {
                report("keep remote");
            }
        };

        androidx.appcompat.app.AlertDialog dialog = SyncConflictDialog.create(
                this, SAMPLE_LOCAL_MILLIS, SAMPLE_REMOTE_MILLIS, reportChoice);
        // A preview resolves nothing, so leaving the activity up would be a dead end.
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                finish();
            }
        });
        dialog.show();
    }

    private void report(String choice) {
        Toast.makeText(this, "Preview: " + choice + " (nothing synced)", Toast.LENGTH_SHORT).show();
    }
}
