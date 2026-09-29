package de.timowa.expenselog.reminders;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.preference.DialogPreference;
import androidx.preference.PreferenceManager;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;

import de.timowa.expenselog.AppLocale;
import de.timowa.expenselog.R;

/**
 * The reminder time, stored as a millisecond timestamp and shown in the app's time format.
 *
 * <p>An {@code androidx.preference.DialogPreference}: it holds the value and the summary, while
 * {@link TimePreferenceDialogFragment} owns the picker. The legacy {@code
 * android.preference.DialogPreference} built its own dialog view; AndroidX splits the two, which
 * is why the picker moved out. The stored key, type and format are unchanged, so an existing
 * reminder time reads back exactly as before.
 */
public class TimePreference extends DialogPreference {

    private final Calendar calendar = new GregorianCalendar();

    public TimePreference(Context context) {
        this(context, null);
    }

    public TimePreference(Context context, AttributeSet attrs) {
        this(context, attrs, androidx.preference.R.attr.dialogPreferenceStyle);
    }

    public TimePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setPositiveButtonText(R.string.ok);
        setNegativeButtonText(R.string.cancel);
    }

    /** The hour the picker should open on. */
    int getHour() {
        return calendar.get(Calendar.HOUR_OF_DAY);
    }

    /** The minute the picker should open on. */
    int getMinute() {
        return calendar.get(Calendar.MINUTE);
    }

    /** True if the picker should be 24-hour, per the app's time format setting. */
    boolean is24Hour() {
        return uses24HourFormat(getContext());
    }

    /**
     * Store the time the picker returned, if the change listener accepts it.
     *
     * <p>The listener is what updates the alarm: {@code RemindersPreferenceFragment} calls
     * {@code ReminderManager.updateReminder} from it. Persisting without asking it first would
     * move the time and leave the alarm on the old one.
     */
    void setTime(int hour, int minute) {
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        if (callChangeListener(calendar.getTimeInMillis())) {
            persistLong(calendar.getTimeInMillis());
            setSummary(getSummary());
            notifyChanged();
        }
    }

    @Override
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return a.getString(index);
    }

    @Override
    protected void onSetInitialValue(Object defaultValue) {
        if (defaultValue == null) {
            calendar.setTimeInMillis(getPersistedLong(System.currentTimeMillis()));
        } else {
            calendar.setTimeInMillis(Long.parseLong((String) defaultValue));
        }
        setSummary(getSummary());
    }

    @Override
    public CharSequence getSummary() {
        return timeFormat(getContext()).format(calendar.getTime());
    }

    /**
     * The app's time format: the user's setting if they have one, otherwise the device's.
     *
     * <p>{@link AppLocale#TEXT} rather than the default locale, like every other time the app
     * writes a time out.
     */
    public static DateFormat timeFormat(Context context) {
        return uses24HourFormat(context)
                ? new SimpleDateFormat("k:mm", AppLocale.TEXT)
                : new SimpleDateFormat("h:mm a", AppLocale.TEXT);
    }

    private static boolean uses24HourFormat(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String key = context.getString(R.string.pref_key_time_format);
        if (prefs.contains(key)) {
            return "1".equals(prefs.getString(key, "0"));
        }
        String systemPref = android.provider.Settings.System.getString(
                context.getContentResolver(), android.provider.Settings.System.TIME_12_24);
        return "24".equals(systemPref);
    }
}
