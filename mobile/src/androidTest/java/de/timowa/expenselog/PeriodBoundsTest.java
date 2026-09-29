package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;

/**
 * Pins the records periods from {@link LogTabsFragment#getPeriodStart} and
 * {@link LogTabsFragment#getPeriodEnd} to whole milliseconds, and checks that they tile.
 *
 * <p>Both used to clear the hour, minute and second but not the millisecond, so a period started at
 * 00:00:00 plus whatever millisecond the clock was on. A record saved at 00:00:00.216 on 1 May was
 * in May's list, total and export when that value was below 216 and missing otherwise; found by
 * exporting May 2022 from a real database and getting 40 records once and 39 six times after.
 *
 * <p>Reads a preferences file that is never written, so the week start is the shipped default and
 * no real preference is touched. No database is opened.
 */
@RunWith(AndroidJUnit4.class)
public class PeriodBoundsTest {

    private static final int[] PAGED_MODES = {
            LogTabsFragment.KEY_RECORDS_MODE_YEAR,
            LogTabsFragment.KEY_RECORDS_MODE_MONTH,
            LogTabsFragment.KEY_RECORDS_MODE_WEEK,
            LogTabsFragment.KEY_RECORDS_MODE_DAY,
    };

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences("period_bounds_test_unwritten", Context.MODE_PRIVATE);
    }

    private long start(int mode, int offset) {
        return LogTabsFragment.getPeriodStart(mode, offset, context, prefs).getTimeInMillis();
    }

    private long end(int mode, int offset) {
        return LogTabsFragment.getPeriodEnd(mode, offset, context, prefs).getTimeInMillis();
    }

    private static int millisecondOf(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return calendar.get(Calendar.MILLISECOND);
    }

    /** The bug itself: a start at .000 includes a record in the period's first second. */
    @Test
    public void everyPeriodStartsOnMillisecondZero() {
        for (int mode : PAGED_MODES)
            for (int offset = -3; offset <= 3; offset++)
                assertEquals("mode " + mode + " offset " + offset, 0, millisecondOf(start(mode, offset)));
    }

    /** The same for the last second: an end at .999 includes a record saved at 23:59:59.900. */
    @Test
    public void everyPeriodEndsOnMillisecond999() {
        for (int mode : PAGED_MODES)
            for (int offset = -3; offset <= 3; offset++)
                assertEquals("mode " + mode + " offset " + offset, 999, millisecondOf(end(mode, offset)));
    }

    /**
     * No gap and no overlap between neighbouring pages, so every record lands on exactly one.
     * Wide enough to cross both daylight-saving changes and a year boundary in every mode.
     */
    @Test
    public void neighbouringPeriodsTile() {
        for (int mode : PAGED_MODES)
            for (int offset = -60; offset < 60; offset++)
                assertEquals("mode " + mode + " offset " + offset,
                        end(mode, offset) + 1, start(mode, offset + 1));
    }
}
