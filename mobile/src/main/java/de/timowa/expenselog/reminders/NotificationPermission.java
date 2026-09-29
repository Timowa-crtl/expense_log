package de.timowa.expenselog.reminders;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.preference.PreferenceManager;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.MainActivity;
import de.timowa.expenselog.R;

/**
 * The runtime {@code POST_NOTIFICATIONS} grant that reminders depend on from API 33 (Android 13).
 *
 * <p><b>Why this class exists at all.</b> Without the grant, {@code NotificationManager.notify} is a
 * silent no-op: no exception, no log line, no notification. A user who had working reminders before
 * the targetSdk bump would simply stop getting them and have nothing to look at. This is the single
 * most likely way Step 5 breaks the app quietly, so the request is centralised rather than inlined
 * at each call site.
 *
 * <p>This is also the only runtime permission the app asks for. {@code PermissionHelper} was deleted
 * in Step 4 because it requested a permission and never implemented
 * {@code onRequestPermissionsResult} — it asked and never listened. The lesson is baked in here:
 * {@link #onRequestPermissionsResult} exists, callers are expected to route to it, and a denial
 * produces a message rather than silence.
 *
 * <p>The classic {@code ActivityCompat.requestPermissions} API is used deliberately in preference to
 * {@code registerForActivityResult}, which would need an androidx.activity upgrade that
 * {@code docs/history/REVIVAL_PLAN.md} defers.
 */
public final class NotificationPermission {

    /** Request code for {@link ActivityCompat#requestPermissions}. */
    public static final int REQUEST_CODE = 5150;

    private NotificationPermission() {
    }

    /**
     * Whether the app may post notifications.
     *
     * <p>Returns {@code true} unconditionally below API 33, where the permission does not exist and
     * posting is always allowed. Callers can therefore treat this as "can reminders work?" without
     * version-guarding themselves.
     */
    public static boolean isGranted(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks for the permission at launch, if reminders are switched on, it is not held yet, and it
     * has not already been asked for on this install.
     *
     * <p>Deliberately does nothing when reminders are off: a user who never wanted reminders should
     * not be asked to allow notifications for them.
     *
     * <p><b>And it asks at most once.</b> {@code MainActivity.onCreate} calls this on every launch
     * and every configuration change. Once the user has denied twice, Android stops showing a
     * dialog and delivers an immediate denial to the callback instead — so the request, and the
     * message explaining what was lost, fired again on every launch and every rotation. Finding 5
     * of {@code docs/history/REVIEW_FINDINGS.md}. The user changing the reminder setting is a fresh question and
     * clears the flag; see {@link #request}.
     */
    public static void requestIfRemindersEnabled(Activity activity) {
        if (isGranted(activity) || !remindersEnabled(activity) || alreadyAsked(activity)) {
            return;
        }
        request(activity);
    }

    /**
     * Asks for the permission now, regardless of the reminder setting, and regardless of whether it
     * has been asked for before. No-op below API 33.
     *
     * <p>This is the deliberate-user-action form — the moment they switch reminders on — so it
     * resets the ask-once flag rather than respecting it. Turning the switch on is the user asking
     * a question that deserves an answer, even if they refused the same permission last month.
     *
     * <p>Whether a dialog actually appears is Android's decision, not this method's: after two
     * denials the system auto-denies and {@link #onRequestPermissionsResult} is called straight
     * away, which is what turns the message into something the user sees exactly when they have
     * just asked for a feature that cannot work.
     */
    public static void request(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(activity)) {
            return;
        }
        markAsked(activity);
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE);
    }

    /**
     * Whether the launch-time request has already been made on this install.
     *
     * <p>{@code shouldShowRequestPermissionRationale} is the sharper signal for "the user has seen
     * this and said no once", but it cannot carry the whole answer: it is false both before the
     * first ask and after a permanent denial, which are the two cases that have to be told apart.
     * So it is used for what it does know, and a stored flag covers the rest.
     */
    private static boolean alreadyAsked(Activity activity) {
        if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                Manifest.permission.POST_NOTIFICATIONS)) {
            return true;
        }
        return hasBeenAsked(activity);
    }

    /**
     * The stored half of {@link #alreadyAsked}, split out so a test can reach it without driving a
     * permission dialog. A typo in the key would read as "never asked" forever, which is the bug
     * this step exists to fix, silently restored.
     */
    static boolean hasBeenAsked(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(context.getString(R.string.pref_key_notification_permission_asked), false);
    }

    static void markAsked(Context context) {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(context.getString(R.string.pref_key_notification_permission_asked), true)
                .apply();
    }

    /**
     * Handles the result of {@link #request}. Returns true if this was our request, so the caller
     * can tell whether it still needs to pass the result on.
     *
     * <p>On denial the user is told what they have lost, through {@link #warnIfUngranted} — which
     * is the single place that decides whether to warn. Saying nothing here would recreate exactly
     * the failure this class exists to prevent: reminders enabled in settings, and nothing ever
     * arriving.
     */
    public static boolean onRequestPermissionsResult(Activity activity, int requestCode,
                                                     int[] grantResults) {
        if (requestCode != REQUEST_CODE) {
            return false;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            warnIfUngranted(activity);
        }
        return true;
    }

    private static boolean remindersEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(context.getString(R.string.pref_key_reminder_enable),
                context.getResources().getBoolean(R.bool.default_reminder_enable));
    }

    /**
     * The one place that decides whether to tell the user reminders cannot fire.
     *
     * <p>Warns only when the permission is missing <em>and</em> reminders are switched on: a user
     * with reminders off has lost nothing and should hear nothing.
     *
     * <p>Called from {@link #onRequestPermissionsResult}, on both paths that reach it — a dialog
     * the user declined, and the immediate auto-denial Android delivers once they have declined
     * twice. It previously had no callers at all while its javadoc described two, and the toast it
     * was meant to own was inlined in the callback instead. Finding 8 of {@code docs/history/REVIEW_FINDINGS.md}.
     */
    public static void warnIfUngranted(Context context) {
        if (isGranted(context) || !remindersEnabled(context)) {
            return;
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.i(MainActivity.REMINDER_TAG, "reminders enabled without POST_NOTIFICATIONS");
        }
        Toast.makeText(context, R.string.reminders_need_notification_permission,
                Toast.LENGTH_LONG).show();
    }
}
