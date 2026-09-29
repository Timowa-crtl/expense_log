package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.StatusBarNotification;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import de.timowa.expenselog.reminders.NotificationPermission;
import de.timowa.expenselog.reminders.ReminderReceiver;

/**
 * The reminder notification, which Step 5 rebuilt when targetSdk went from 29 to 35.
 *
 * <p><b>Why this exists.</b> Both failure modes Step 5 had to fix are silent. A missing
 * {@code POST_NOTIFICATIONS} grant makes {@code notify} do nothing at all — no exception, no log.
 * A notification trampoline is worse: the notification posts and looks right, the button is
 * tappable, and the system simply drops the {@code startActivity} behind it. Neither shows up in a
 * build, in lint alone, or in a casual glance at the phone, and the second one had been shipping
 * broken behind a suppressed lint check.
 *
 * <p>{@link #settingsAction_launchesAnActivityDirectly} is the one that matters most. It asserts on
 * the shape of the {@code PendingIntent} rather than on behaviour, because the behaviour it guards
 * against is *nothing happening* — the assertion has to be made against the notification the app
 * posted, not against a screen that never opened.
 *
 * <p>These run on the emulator like the rest of the suite; see {@code DBAdapterSchemaTest} for why
 * the instrumentation source set exists at all.
 */
@RunWith(AndroidJUnit4.class)
public class ReminderNotificationTest {

    /** Mirrors {@code ReminderReceiver.NOTIFICATION_ID}, which is private to that class. */
    private static final int NOTIFICATION_ID = 222;

    /**
     * Grants POST_NOTIFICATIONS for the test run. Without it every assertion below would fail for
     * the uninteresting reason, and the interesting one — that the notification is well-formed —
     * would never be reached.
     */
    @Rule
    public GrantPermissionRule permission = grantPostNotifications();

    private Context context;
    private NotificationManager manager;

    private static GrantPermissionRule grantPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        // Below 33 the permission does not exist and granting it fails; nothing to do.
        return GrantPermissionRule.grant();
    }

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(NOTIFICATION_ID);
    }

    @After
    public void tearDown() {
        manager.cancel(NOTIFICATION_ID);
    }

    @Test
    public void permissionHelper_reportsTheGrantItWasGiven() {
        assertTrue("POST_NOTIFICATIONS was granted for this run, so isGranted must agree",
                NotificationPermission.isGranted(context));
    }

    @Test
    public void reminder_postsANotificationWithBothActions() {
        Notification posted = postAndAwaitReminder();
        assertNotNull("the reminder notification was not posted", posted);
        assertNotNull("the reminder posted with no action buttons", posted.actions);
        assertEquals("expected the Settings and Snooze actions", 2, posted.actions.length);
    }

    /**
     * The trampoline guard.
     *
     * <p>Before Step 5 the Settings action was a {@code getBroadcast} into {@code ReminderReceiver},
     * which then called {@code startActivity}. Apps targeting API 31+ are blocked from that, so the
     * button did nothing whatsoever. If someone routes it back through the receiver, this fails.
     */
    @Test
    public void settingsAction_launchesAnActivityDirectly() {
        assumeTrue("PendingIntent.isActivity needs API 31",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);

        Notification posted = postAndAwaitReminder();
        assertNotNull("the reminder notification was not posted", posted);

        Notification.Action settings = posted.actions[0];
        assertTrue("the Settings action must start an activity directly, not via a receiver;"
                        + " a broadcast here is a notification trampoline and is dropped at"
                        + " targetSdk 31+",
                settings.actionIntent.isActivity());
    }

    /**
     * Snooze legitimately stays a broadcast — it reschedules an alarm and shows a toast, and never
     * starts an activity. Pinned so that a future "fix everything to getActivity" sweep does not
     * quietly turn a working background action into a launched screen.
     */
    @Test
    public void snoozeAction_staysABroadcast() {
        assumeTrue("PendingIntent.isBroadcast needs API 31",
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);

        Notification posted = postAndAwaitReminder();
        assertNotNull("the reminder notification was not posted", posted);

        Notification.Action snooze = posted.actions[1];
        assertTrue("Snooze does background work and must stay a broadcast",
                snooze.actionIntent.isBroadcast());
    }

    /**
     * The content intent and the Settings action both target MainActivity and differ only in an
     * extra. PendingIntent identity ignores extras, so equal request codes would collapse them into
     * one and the Settings button would open whatever the other one pointed at.
     */
    @Test
    public void settingsActionAndContentIntent_areDistinctPendingIntents() {
        Notification posted = postAndAwaitReminder();
        assertNotNull("the reminder notification was not posted", posted);
        assertNotNull("the reminder has no content intent", posted.contentIntent);

        assertTrue("the Settings action collapsed into the content intent; give them different"
                        + " request codes",
                !posted.actions[0].actionIntent.equals(posted.contentIntent));
    }

    /**
     * Posts the reminder and waits for it to appear, re-posting once if it does not.
     *
     * <p><b>Why this is not a plain post-then-read.</b> On the first run after installing the app
     * under a new application id, two of these tests failed with "the reminder notification was not
     * posted" while three others in the same class passed; re-running the class alone, and the full
     * suite again, was green 29/29 on the same emulator. The post is dropped, not delayed — a
     * notification that was never accepted will not turn up however long you wait — so waiting
     * alone would not fix it, and the retry is the part that matters. What must not happen is this
     * being papered over: a notification the app genuinely fails to build still never appears, and
     * the assertion in the caller still fails.
     */
    private Notification postAndAwaitReminder() {
        for (int attempt = 0; attempt < 2; attempt++) {
            new ReminderReceiver().showReminderNotification(context);

            long deadline = System.currentTimeMillis() + 2000;
            do {
                Notification posted = findPostedReminder();
                if (posted != null) {
                    return posted;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            } while (System.currentTimeMillis() < deadline);
        }
        return null;
    }

    private Notification findPostedReminder() {
        for (StatusBarNotification sbn : manager.getActiveNotifications()) {
            if (sbn.getId() == NOTIFICATION_ID) {
                return sbn.getNotification();
            }
        }
        return null;
    }
}
