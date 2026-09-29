package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.res.Resources;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.Locale;

/**
 * The app writes dates in English whatever the phone's language: the series block's schedule
 * line names English weekdays and months on a German phone, and the picker dialogs get English
 * resources. See {@link AppLocale}.
 */
@RunWith(AndroidJUnit4.class)
public class EnglishTextTest {

    private Locale savedLocale;
    private Resources res;

    @Before
    public void setUp() {
        savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
        res = ApplicationProvider.getApplicationContext().getResources();
    }

    @After
    public void tearDown() {
        Locale.setDefault(savedLocale);
    }

    private static long at(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day, 16, 12);
        return c.getTimeInMillis();
    }

    @Test
    public void schedule_isEnglishOnAGermanPhone() {
        long thursday = at(2026, 9, 17);
        assertEquals("Every 3 weeks on Thursday",
                NewLogFragment.describeSchedule(res, thursday, 3, RepeatingSeries.PERIOD_WEEK));
        assertEquals("Yearly on Sep 17",
                NewLogFragment.describeSchedule(res, thursday, 1, RepeatingSeries.PERIOD_YEAR));
        assertEquals("Monthly on day 17",
                NewLogFragment.describeSchedule(res, thursday, 1, RepeatingSeries.PERIOD_MONTH));
        assertEquals("Every 3 days",
                NewLogFragment.describeSchedule(res, thursday, 3, RepeatingSeries.PERIOD_DAY));
    }

    @Test
    public void lateMonthDay_saysItFallsBackToTheLastDay() {
        assertEquals("Monthly on day 31 (or last day)",
                NewLogFragment.describeSchedule(res, at(2026, 10, 31), 1, RepeatingSeries.PERIOD_MONTH));
    }

    @Test
    public void pickerDialogs_getEnglishResources() {
        Context app = ApplicationProvider.getApplicationContext();
        Context english = AppLocale.forDialog(new androidx.appcompat.view.ContextThemeWrapper(app, R.style.AppTheme));
        assertEquals(Locale.US, english.getResources().getConfiguration().getLocales().get(0));
    }
}
