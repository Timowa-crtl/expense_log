package de.timowa.expenselog.reminders;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import de.timowa.expenselog.IsolatedDatabaseContext;
import de.timowa.expenselog.R;

/**
 * The ask-once flag behind finding 5 of {@code docs/history/REVIEW_FINDINGS.md}.
 *
 * <p>{@code MainActivity.onCreate} asks for {@code POST_NOTIFICATIONS} on every launch. Once the
 * user has denied twice, Android stops showing a dialog and delivers an immediate denial to the
 * callback, so the request — and the message explaining that reminders will not appear — fired again
 * on every launch and every rotation. The fix is to remember that the launch-time request has been
 * made.
 *
 * <p>What is tested here is the remembering, not the dialog. Driving the real permission prompt
 * would need UiAutomator and a device whose permission state the test controls; the branch's gate
 * for this step is a device check (deny twice, relaunch, rotate, expect no toast). But the stored
 * flag is worth pinning on its own: a typo in the preference key reads as "never asked" forever,
 * which restores the bug silently and would pass every other test in the suite.
 *
 * <p>Runs against {@link IsolatedDatabaseContext} so it writes a {@code test_}-prefixed preferences
 * file rather than the user's real one.
 */
@RunWith(AndroidJUnit4.class)
public class NotificationPermissionTest {

    private Context context;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        clearFlag();
    }

    private void clearFlag() {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .remove(context.getString(R.string.pref_key_notification_permission_asked))
                .apply();
    }

    /** Before anything happens, the launch-time request has not been made. */
    @Test
    public void freshInstall_hasNotBeenAsked() {
        assertFalse("a fresh install must be willing to ask once",
                NotificationPermission.hasBeenAsked(context));
    }

    /** And once it has, it stays remembered — this is what stops the every-launch repeat. */
    @Test
    public void afterMarking_staysAsked() {
        NotificationPermission.markAsked(context);
        assertTrue("the ask must be remembered across the next launch",
                NotificationPermission.hasBeenAsked(context));

        // Reading it again must not clear it: the flag is the whole mechanism.
        assertTrue("and remembered again after that",
                NotificationPermission.hasBeenAsked(context));
    }

    /**
     * The key has to resolve to a real string resource. Reading through {@code getString} rather
     * than a literal is the project's convention for every preference key, and it is what makes a
     * typo a build failure instead of a permanently-false flag.
     */
    @Test
    public void theFlagIsStoredUnderTheDeclaredKey() {
        NotificationPermission.markAsked(context);

        String key = context.getString(R.string.pref_key_notification_permission_asked);
        assertTrue("the flag must be stored under " + key,
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                        .getBoolean(key, false));
    }
}
