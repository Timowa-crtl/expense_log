package de.timowa.expenselog.reminders;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Calendar;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.R;

/**
 * manages the reminders for notifications
 */
public class ReminderManager {

    // Exact-alarm policy, checked at Step 5 when targetSdk went to 35: nothing to do here.
    //
    // Every alarm this class schedules is INEXACT -- setRepeating below, and set() in
    // snoozeReminder. The SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM permissions introduced at API 31
    // gate setExact, setExactAndAllowWhileIdle and setAlarmClock, none of which the app calls, so
    // it needs neither permission and must not gate on canScheduleExactAlarms.
    //
    // The consequence, which is the intended trade-off rather than a defect: the system batches
    // these and Doze can defer them, so a reminder set for 10:00 may arrive somewhat later. That is
    // correct for a "log your expenses" nudge and has been true since API 19 -- it is a function of
    // API level, not of targetSdk, so the bump changes nothing. Do not "fix" this by reaching for an
    // exact alarm: it would mean asking the user for a special-access permission to no real benefit.

    public static final String ACTION_KEY = "action";
    public static final int ACTION_UPDATE_REMINDER = 0;
    public static final int ACTION_NOTIFY = 1;
    public static final int ACTION_SNOOZE = 2;
    public static final int ACTION_DISMISS = 3;
    public static final int ACTION_SETTINGS = 4;
    public static final int ACTION_NEW_LOG = 5;

    public static final String BROADCAST_ID = "notificationId";
    public static final int FIRST_REMINDER_ID = 1;
    public static final int SECOND_REMINDER_ID = 2;
    public static final int THIRD_REMINDER_ID = 3;

    public static void updateReminder(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        // if no time exists (first launch) set a time so that the reminder happens
        // daily at the same time the app first first launched
        if (!prefs.contains(ctx.getResources().getString(R.string.pref_key_reminder_time))) {
            Calendar firstReminderCalendar = Calendar.getInstance();
            prefs.edit().putLong(ctx.getResources().getString(R.string.pref_key_reminder_time)
                    , firstReminderCalendar.getTimeInMillis()).apply();
        }
        boolean reminderEnabled = prefs.getBoolean(ctx.getResources().getString(R.string.pref_key_reminder_enable),
                ctx.getResources().getBoolean(R.bool.default_reminder_enable));
        if (BuildConfig.DEBUG) Log.i("RTEST", "reminderEnabled: " + reminderEnabled);
        if (reminderEnabled) {
            setupReminder(ctx);
        } else {
            disableReminder(ctx);
        }

    }

    public static void disableReminder(Context ctx) {
        if (BuildConfig.DEBUG) Log.i("RTEST", "reminder disabled");

        AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        Intent myIntent = new Intent(ctx, ReminderReceiver.class);

        // cancel first reminder
        PendingIntent pendingIntent = PendingIntent.getBroadcast(ctx, FIRST_REMINDER_ID, myIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.cancel(pendingIntent);

    }

    public static void setupReminder(Context ctx) {
        // disable all reminders for a clean slate

        disableReminder(ctx);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        int reminderFrequencySetting =
                Integer.parseInt(prefs.getString(ctx.getResources().getString(R.string.pref_key_reminder_frequency), "0"));
        long reminderTime = prefs.getLong(ctx.getResources().getString(R.string.pref_key_reminder_time), 0);
        Calendar tempCalendar = Calendar.getInstance();
        Calendar firstReminderCalendar = Calendar.getInstance();
        Calendar currentTimeCalendar = Calendar.getInstance();

        if (reminderTime != 0) {
            // if reminder time is set
            tempCalendar.setTimeInMillis(reminderTime);
            firstReminderCalendar.set(Calendar.MINUTE, tempCalendar.get(Calendar.MINUTE));
            firstReminderCalendar.set(Calendar.HOUR_OF_DAY, tempCalendar.get(Calendar.HOUR_OF_DAY));
        }
        if (BuildConfig.DEBUG)
            Log.i("RTEST", "reminderFrequency: " + reminderFrequencySetting);

        long reminderFrequency = 1000 * 60 * 60 * 24; // set default to one day

        switch (reminderFrequencySetting) {
            case 0: // daily
                // If the configured time has already gone by today, the first reminder is tomorrow.
                // Anything still ahead of now fires today, however close.
                //
                // This guard is load-bearing, and not for the reason it looks like:
                // firstReminderCalendar is *today's* date at the configured hour and minute, so a
                // time earlier in the day is in the past -- and setRepeating() on a past
                // RTC_WAKEUP time fires immediately. Without this, setting 08:00 at 17:00 would
                // deliver a reminder the moment you saved it, and again every day after.
                //
                // The window used to be now + 5 minutes, which also swallowed reminders set a
                // minute or two ahead and pushed them to tomorrow with nothing said. That made
                // testing the reminder path slow, and was mildly surprising in ordinary use. It is
                // now the narrower question the code actually needs answered.
                if (firstReminderCalendar.getTimeInMillis() < currentTimeCalendar.getTimeInMillis())
                    firstReminderCalendar.add(Calendar.DAY_OF_YEAR, 1);
                break;
            case 1: // every other day
                reminderFrequency = reminderFrequency * 2;
                firstReminderCalendar.add(Calendar.DAY_OF_YEAR, 2);
                break;
            case 2: // weekly
                reminderFrequency = reminderFrequency * 7;
                firstReminderCalendar.add(Calendar.DAY_OF_YEAR, 7);
                break;
        }

        // Setup notifications and reminder
        setupReminderNotification(ctx, firstReminderCalendar, reminderFrequency, FIRST_REMINDER_ID);
        if (BuildConfig.DEBUG)
            Log.i("RTEST", "first rem time: " + firstReminderCalendar.getTime());
    }

    @NonNull
    private static Calendar getReminderTime(SharedPreferences prefs, Calendar currentTimeCalendar, String reminderSettingKey) {
        Calendar tempCalendar = Calendar.getInstance();
        // Setup notifications and reminder
        Calendar reminderTimeCalendar = Calendar.getInstance();
        long tempReminderTime = prefs.getLong(reminderSettingKey, 0);
        tempCalendar.setTimeInMillis(tempReminderTime);
        reminderTimeCalendar.set(Calendar.MINUTE, tempCalendar.get(Calendar.MINUTE));
        reminderTimeCalendar.set(Calendar.HOUR_OF_DAY, tempCalendar.get(Calendar.HOUR_OF_DAY));
        // add one day if first reminder is before current time
        if (reminderTimeCalendar.getTimeInMillis() < currentTimeCalendar.getTimeInMillis())
            reminderTimeCalendar.add(Calendar.DAY_OF_YEAR, 1);
        return reminderTimeCalendar;
    }

    private static void setupReminderNotification(Context ctx, Calendar reminderTime, long reminderFrequency, int reminderID) {
        if (BuildConfig.DEBUG)
            Log.i("RTEST", "Setup ring time: " + reminderTime.getTime() + " id: " + reminderID);
        Intent myIntent = new Intent(ctx, ReminderReceiver.class);
        myIntent.putExtra(ACTION_KEY, ACTION_NOTIFY);
        myIntent.putExtra(BROADCAST_ID, reminderID);
        AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(ctx, reminderID, myIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, reminderTime.getTimeInMillis(), reminderFrequency, pendingIntent);
    }

    static void snoozeReminder(Context ctx) {

        // Notifications and reminder
        Intent myIntent = new Intent(ctx, ReminderReceiver.class);
        myIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        myIntent.putExtra(ACTION_KEY, ACTION_NOTIFY);
        AlarmManager alarmManager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        // set snooze broadcast id to 4, an unused request code, to avoid interfering with exists reminders
        PendingIntent pendingIntent = PendingIntent.getBroadcast(ctx, 4, myIntent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // create a reminder 60m from snooze time
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, 60);
        alarmManager.set(AlarmManager.RTC, calendar.getTimeInMillis(), pendingIntent);

    }
}
