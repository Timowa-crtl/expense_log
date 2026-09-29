package de.timowa.expenselog;

import android.annotation.SuppressLint;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.res.Configuration;
import androidx.preference.PreferenceManager;
import android.view.ContextThemeWrapper;

import java.util.Locale;

/**
 * The app is English throughout, whatever the phone's language: its strings exist only in
 * English, and every date and time it writes out -- month and weekday names, the date and time
 * pickers -- uses {@link #TEXT} rather than the device locale, so a German phone does not show
 * "Sept." or "Donnerstag" inside English sentences.
 *
 * <p><b>Numbers are not part of this.</b> Amounts keep the device's format ("4.214,00 €" on a
 * German phone), and so does the first day of the week, which is a region setting.
 */
public final class AppLocale {

    public static final Locale TEXT = Locale.US;

    private AppLocale() {
    }

    /**
     * A date picker in English, starting its weeks on the app's First day of week.
     *
     * <p>English resources alone are not enough: the picker's header ("Thu, Sep 17") takes its
     * locale from {@link Locale#getDefault()} while it is being built, so the default is English
     * for that moment only. Its first day of week would then be English too (Sunday), which is a
     * region setting, not a language one -- so it is set from the app's setting instead.
     */
    public static DatePickerDialog datePicker(Context base, DatePickerDialog.OnDateSetListener listener,
                                              int year, int month, int day) {
        Locale saved = Locale.getDefault();
        DatePickerDialog dialog;
        Locale.setDefault(TEXT);
        try {
            dialog = new DatePickerDialog(forDialog(base), listener, year, month, day);
        } finally {
            Locale.setDefault(saved);
        }
        dialog.getDatePicker().setFirstDayOfWeek(LogTabsFragment.firstDayOfWeek(base,
                PreferenceManager.getDefaultSharedPreferences(base)));
        return dialog;
    }

    /** A time picker in English (AM / PM); like {@link #datePicker}, built with an English default. */
    public static TimePickerDialog timePicker(Context base, TimePickerDialog.OnTimeSetListener listener,
                                              int hour, int minute, boolean is24Hour) {
        Locale saved = Locale.getDefault();
        Locale.setDefault(TEXT);
        try {
            return new TimePickerDialog(forDialog(base), listener, hour, minute, is24Hour);
        } finally {
            Locale.setDefault(saved);
        }
    }

    /**
     * {@code base} with English resources, for a platform dialog that names months or days.
     *
     * <p>Lint's AppBundleLocaleChanges warns that Play may not have delivered the language
     * resources this switches to. English is the app's only language and lives in the base
     * resources, which every install has, so there is nothing to download.
     */
    @SuppressLint("AppBundleLocaleChanges")
    public static Context forDialog(Context base) {
        // The theme by id, not base.getTheme(): a dialog layers its own overlay on this theme,
        // and on a shared Theme object that left the time picker unable to resolve its colours.
        ContextThemeWrapper english = new ContextThemeWrapper(base, R.style.AppTheme_NoActionBar);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(TEXT);
        english.applyOverrideConfiguration(config);
        return english;
    }
}
