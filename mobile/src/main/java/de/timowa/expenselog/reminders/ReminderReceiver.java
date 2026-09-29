package de.timowa.expenselog.reminders;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.MainActivity;
import de.timowa.expenselog.R;

/**
 * Service that shows a reminder notification
 */

public class ReminderReceiver extends BroadcastReceiver {

    private static final int NOTIFICATION_ID = 222;
    private String CHANNEL_ID = "expenseLogChannel";

    // Distinct request codes for the three PendingIntents this notification carries. PendingIntent
    // identity ignores extras -- it is (requestCode, component, action, data, type, categories) --
    // so the content intent and the Settings action, which now both target MainActivity with no
    // action set and differ only in their ACTION_KEY extra, would collapse into a single
    // PendingIntent if they shared a request code. The Settings button would then open a new
    // record, or the reverse, depending on which was created last.
    private static final int REQUEST_CONTENT = 10;
    private static final int REQUEST_SETTINGS = 11;
    private static final int REQUEST_SNOOZE = 12;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (BuildConfig.DEBUG) Log.i("RTEST", "reminder intent received");

        // The manifest filter admits one broadcast, BOOT_COMPLETED, and it carries none of the
        // app's extras: re-arm the alarm and read nothing else. Every other intent that reaches
        // this non-exported receiver is one of the app's own PendingIntents (the alarm, snooze),
        // which name what to do in an extra. Checking the action makes the boot case explicit
        // instead of "whatever arrives without extras".
        int passedAction = ReminderManager.ACTION_UPDATE_REMINDER;
        int broadcastID = 999;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try {
                passedAction = intent.getExtras().getInt(ReminderManager.ACTION_KEY, ReminderManager.ACTION_UPDATE_REMINDER);
                if (BuildConfig.DEBUG) Log.i("RTEST", "reminder action: " + passedAction);

                broadcastID = intent.getExtras().getInt(ReminderManager.BROADCAST_ID, 333);
                if (BuildConfig.DEBUG) Log.i("RTEST", "passed reminder id: " + broadcastID);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        switch (passedAction) {
            case ReminderManager.ACTION_NOTIFY:
                showReminderNotification(context);
                break;
            case ReminderManager.ACTION_SETTINGS:
                // Reachable only from a stale PendingIntent -- a notification posted before the
                // Step 5 update, still sitting in the shade. New ones send Settings straight to
                // MainActivity; see showReminderNotification.
                //
                // It deliberately does NOT start an activity. At targetSdk 31+ the system drops a
                // startActivity made from a notification-triggered broadcast, so the call could
                // never work; keeping it would only leave a startActivity in this receiver, which
                // makes lint flag every broadcast PendingIntent into it -- including snooze, which
                // is otherwise perfectly legal -- as a NotificationTrampoline. Dismissing is all
                // this can honestly do, and the case expires with the next reminder.
                dismissNotification(context);
                break;
            case ReminderManager.ACTION_SNOOZE:

                dismissNotification(context);

                Toast.makeText(context, "Reminder Snoozed", Toast.LENGTH_SHORT).show();

                // set a new one to be 60m from now
                ReminderManager.snoozeReminder(context);
                break;
            case ReminderManager.ACTION_UPDATE_REMINDER:
                // if no intent specified, start update manager
                if (BuildConfig.DEBUG) Log.i("RTEST", "reminder manager started");
                ReminderManager.updateReminder(context);
                break;
        }
    }

    /**
     * Clears the reminder notification.
     *
     * <p>Public and static because the Settings action is no longer handled here: it opens
     * MainActivity directly, and a notification action button -- unlike the content intent -- is not
     * covered by {@code setAutoCancel}, so the receiving activity has to clear it.
     */
    public static void dismissNotification(Context context) {
        // dismiss the notification
        NotificationManager notificationManagerTemp = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManagerTemp.cancel(NOTIFICATION_ID);

        // The status bar used to be collapsed here with ACTION_CLOSE_SYSTEM_DIALOGS. That broadcast
        // has been restricted since Android 12 -- BROADCAST_CLOSE_SYSTEM_DIALOGS is
        // signature|privileged, so this app can never hold it -- and the shade already collapses on
        // its own when a notification action is tapped. Removed rather than permission-guarded.
    }

    public void showReminderNotification(Context context) {
        if (BuildConfig.DEBUG) Log.i("RTEST", "reminder notification created");

        createNotificationChannel(context);

        try {
            Intent snoozeIntent = new Intent(context, ReminderReceiver.class);
            snoozeIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            snoozeIntent.putExtra(ReminderManager.ACTION_KEY, ReminderManager.ACTION_SNOOZE);
            snoozeIntent.setAction("snooze");

            PendingIntent piSnooze = PendingIntent.getBroadcast(context, REQUEST_SNOOZE, snoozeIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Straight to the activity, not through this receiver.
            //
            // This action used to be a getBroadcast into ReminderReceiver, which then called
            // startActivity. That is a notification trampoline, and apps targeting API 31+ are
            // blocked from it outright: the broadcast is delivered, the startActivity is dropped,
            // and the button does nothing at all. It fails silently, which is why lint's
            // NotificationTrampoline check was suppressed rather than hit during Phase 1.
            //
            // MainActivity already knows how to handle ACTION_SETTINGS -- checkForNotificationFlags
            // routes it to Navigator.openSettings -- so the activity PendingIntent needs no new
            // receiving code, only its own request code (see REQUEST_SETTINGS above).
            Intent settingsIntent = new Intent(context, MainActivity.class);
            settingsIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            settingsIntent.putExtra(ReminderManager.ACTION_KEY, ReminderManager.ACTION_SETTINGS);
            PendingIntent piSettings = PendingIntent.getActivity(context, REQUEST_SETTINGS, settingsIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationManager mNotifyMgr = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationCompat.Builder mBuilder =
                    new NotificationCompat.Builder(context, CHANNEL_ID)
                            .setSmallIcon(R.drawable.ic_notification_logo)
                            .setContentTitle(context.getString(R.string.app_name))
                            .setContentText(context.getString(R.string.notification_reminder_message))
                            .setAutoCancel(true)
                            .setDefaults(Notification.DEFAULT_ALL) // requires VIBRATE permission
                                /*
                                * Sets the big view "big text" style and supplies the
                                * text (the user's reminder message) that will be displayed
                                * in the detail area of the expanded notification.
                                * These calls are ignored by the support library for
                                * pre-4.1 devices.
                                */
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(context.getString(R.string.notification_reminder_message)))
                            .addAction(R.drawable.ic_notification_settings,
                                    context.getResources().getString(R.string.action_settings), piSettings)
                            .addAction(R.drawable.ic_notification_snooze,
                                    context.getResources().getString(R.string.snooze), piSnooze);

            // todo on click send user to main, update manager, and send user to new log
            Intent myIntent = new Intent(context, MainActivity.class);
            myIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            myIntent.putExtra(ReminderManager.ACTION_KEY, ReminderManager.ACTION_NEW_LOG);
            PendingIntent resultPendingIntent = PendingIntent.getActivity(context,
                    REQUEST_CONTENT,
                    myIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            mBuilder.setContentIntent(resultPendingIntent);
            // Builds the notification and issues it.
            mNotifyMgr.notify(NOTIFICATION_ID, mBuilder.build());
        } catch (Resources.NotFoundException e) {
            e.printStackTrace();
        }
    }

    private void createNotificationChannel(Context context) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = context.getString(R.string.reminders);
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
