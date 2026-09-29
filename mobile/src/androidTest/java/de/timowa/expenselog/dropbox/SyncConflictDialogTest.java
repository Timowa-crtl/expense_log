package de.timowa.expenselog.dropbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Rect;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import de.timowa.expenselog.R;

/**
 * The sync-conflict dialog, rebuilt on 2026-09-04 after device testing on a Unihertz Jelly Star.
 *
 * <p><b>Why this exists.</b> The old dialog put the two choices in a {@code setItems} list and the
 * sentence explaining them in a custom view that renders below the list. Everything was on screen
 * and nothing crashed — it just looked like a status message with a single Cancel button, so the
 * user could not confirm. A bug that consists of a control not looking like a control is invisible
 * to a build, to lint, and to any test that only asks "did it throw".
 *
 * <p>So these assert the two things that would bring it back: that all three controls exist and are
 * actually shown and enabled, and that each one still routes to the side it names. The
 * local/remote mapping used to be a list index ({@code which == 0} meant local); it is now two
 * separate buttons, and getting that backwards would send a user's data the wrong way — past a
 * confirmation dialog that would name the wrong consequence with total confidence.
 *
 * <p>They run at whatever size the test device is, so they cannot sweep font scales — but they do
 * assert that each button is <i>wholly</i> on screen rather than merely {@code isShown()}, because
 * a clipped button reports itself as shown. That distinction is not academic: the first draft of
 * this rewrite was clipped at a 1.3x font scale on a 320dp screen and these tests passed anyway.
 * The measured limits, and the reason the message is kept short, are in {@link SyncConflictDialog}.
 */
@RunWith(AndroidJUnit4.class)
public class SyncConflictDialogTest {

    /** 2026-09-03, 42 minutes apart with the remote newer — the shape of the device report. */
    private static final long LOCAL_MILLIS = 1_788_457_080_000L;
    private static final long REMOTE_MILLIS = 1_788_459_600_000L;

    @Test
    public void allThreeChoicesAreVisibleAndEnabled() {
        withDialog(LOCAL_MILLIS, REMOTE_MILLIS, new OnDialog() {
            @Override
            public void run(Activity activity, AlertDialog dialog) {
                assertButtonUsable(dialog, DialogInterface.BUTTON_POSITIVE,
                        activity.getString(R.string.sync_conflict_keep_local));
                assertButtonUsable(dialog, DialogInterface.BUTTON_NEGATIVE,
                        activity.getString(R.string.sync_conflict_keep_remote));
                assertButtonUsable(dialog, DialogInterface.BUTTON_NEUTRAL,
                        activity.getString(R.string.cancel));
            }
        });
    }

    @Test
    public void keepLocalButtonReportsLocal() {
        final List<String> choices = new ArrayList<>();
        withDialog(LOCAL_MILLIS, REMOTE_MILLIS, choices, new OnDialog() {
            @Override
            public void run(Activity activity, AlertDialog dialog) {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            }
        });
        assertEquals("Keep local must resolve to the local side",
                Collections.singletonList("local"), choices);
    }

    @Test
    public void keepRemoteButtonReportsRemote() {
        final List<String> choices = new ArrayList<>();
        withDialog(LOCAL_MILLIS, REMOTE_MILLIS, choices, new OnDialog() {
            @Override
            public void run(Activity activity, AlertDialog dialog) {
                dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            }
        });
        assertEquals("Keep remote must resolve to the remote side",
                Collections.singletonList("remote"), choices);
    }

    @Test
    public void cancelChoosesNeitherSide() {
        final List<String> choices = new ArrayList<>();
        withDialog(LOCAL_MILLIS, REMOTE_MILLIS, choices, new OnDialog() {
            @Override
            public void run(Activity activity, AlertDialog dialog) {
                dialog.getButton(DialogInterface.BUTTON_NEUTRAL).performClick();
            }
        });
        assertTrue("Cancel must not resolve the conflict either way", choices.isEmpty());
    }

    /** Both timestamps have to be readable, and the more recent one has to be marked as such. */
    @Test
    public void messageShowsBothTimestampsAndMarksTheNewerOne() {
        withDialog(LOCAL_MILLIS, REMOTE_MILLIS, new OnDialog() {
            @Override
            public void run(Activity activity, AlertDialog dialog) {
                TextView messageView = dialog.findViewById(android.R.id.message);
                assertNotNull("dialog has no message view", messageView);
                String message = messageView.getText().toString();

                assertTrue("message must explain the conflict: " + message,
                        message.contains(activity.getString(R.string.sync_conflict_msg)));
                assertTrue("message must name both sides: " + message,
                        message.contains(activity.getString(R.string.local_last_modified))
                                && message.contains(activity.getString(R.string.remote_last_modified)));

                String newer = activity.getString(R.string.sync_conflict_newer);
                int localAt = message.indexOf(activity.getString(R.string.local_last_modified));
                int remoteAt = message.indexOf(activity.getString(R.string.remote_last_modified));
                int newerAt = message.indexOf(newer);
                assertTrue("the newer marker is missing: " + message, newerAt > -1);
                assertTrue("remote is newer here, so the marker belongs to it: " + message,
                        newerAt > remoteAt && remoteAt > localAt);
                assertFalse("only one side may be marked newer: " + message,
                        message.indexOf(newer, newerAt + newer.length()) > -1);
            }
        });
    }

    /**
     * Only one conflict dialog at a time. The Dropbox test pass on 2026-09-15 found two stacked
     * after a record was saved during a sync download: the stood-down download and the save's
     * refused upload each re-checked and each opened one.
     */
    @Test
    public void aSecondConflictWhileOneIsShowing_doesNotOpenAnotherDialog() {
        try (ActivityScenario<DialogHostActivity> scenario =
                     ActivityScenario.launch(DialogHostActivity.class)) {
            final List<Boolean> shown = new ArrayList<>();
            final SyncConflictDialog.OnChoice ignore = new SyncConflictDialog.OnChoice() {
                @Override
                public void onKeepLocal() {
                }

                @Override
                public void onKeepRemote() {
                }
            };
            scenario.onActivity(activity -> {
                shown.add(SyncConflictDialog.show(activity, LOCAL_MILLIS, REMOTE_MILLIS, ignore));
                shown.add(SyncConflictDialog.show(activity, LOCAL_MILLIS, REMOTE_MILLIS, ignore));
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                AlertDialog first = SyncConflictDialog.showing();
                assertNotNull("the first dialog should be on screen", first);
                first.dismiss();
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                shown.add(SyncConflictDialog.show(activity, LOCAL_MILLIS, REMOTE_MILLIS, ignore));
                SyncConflictDialog.showing().dismiss();
            });
            assertEquals("first shows, second is suppressed, a later one shows again",
                    List.of(true, false, true), shown);
        }
    }

    /**
     * <b>{@code isShown()} is not enough, and finding that out cost a round trip.</b> It reports
     * visibility and attachment, and stays true for a button drawn past the bottom of the window —
     * which is exactly what the first draft of this dialog did on a 320dp screen. So this measures
     * the button's visible rectangle against its own height and demands the whole thing.
     */
    private static void assertButtonUsable(AlertDialog dialog, int whichButton, String label) {
        android.widget.Button button = dialog.getButton(whichButton);
        assertNotNull(label + " button is missing", button);
        assertEquals(label, button.getText().toString());
        assertTrue(label + " button is not shown", button.isShown());
        assertTrue(label + " button is not enabled", button.isEnabled());

        Rect visible = new Rect();
        boolean anyVisible = button.getGlobalVisibleRect(visible);
        assertTrue(label + " button is off screen entirely", anyVisible);
        assertEquals(label + " button is clipped: " + visible.height() + " of "
                + button.getHeight() + "px on screen", button.getHeight(), visible.height());
    }

    private interface OnDialog {
        void run(Activity activity, AlertDialog dialog);
    }

    private void withDialog(long localMillis, long remoteMillis, OnDialog body) {
        withDialog(localMillis, remoteMillis, new ArrayList<String>(), body);
    }

    /** Shows the dialog on a bare host activity, runs {@code body} against it, then tears it down. */
    private void withDialog(final long localMillis, final long remoteMillis,
                            final List<String> choices, final OnDialog body) {
        try (ActivityScenario<DialogHostActivity> scenario =
                     ActivityScenario.launch(DialogHostActivity.class)) {
            final AtomicReference<AlertDialog> held = new AtomicReference<>();
            scenario.onActivity(new ActivityScenario.ActivityAction<DialogHostActivity>() {
                @Override
                public void perform(DialogHostActivity activity) {
                    AlertDialog dialog = SyncConflictDialog.create(activity, localMillis, remoteMillis,
                            new SyncConflictDialog.OnChoice() {
                                @Override
                                public void onKeepLocal() {
                                    choices.add("local");
                                }

                                @Override
                                public void onKeepRemote() {
                                    choices.add("remote");
                                }
                            });
                    dialog.show();
                    held.set(dialog);
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(new ActivityScenario.ActivityAction<DialogHostActivity>() {
                @Override
                public void perform(DialogHostActivity activity) {
                    body.run(activity, held.get());
                }
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            scenario.onActivity(new ActivityScenario.ActivityAction<DialogHostActivity>() {
                @Override
                public void perform(DialogHostActivity activity) {
                    AlertDialog dialog = held.get();
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                }
            });
        }
    }
}
