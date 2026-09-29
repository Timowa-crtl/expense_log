package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * {@link LogTabsFragment#pageOffsetFor}: after a save, the records screen opens on the page whose
 * period holds the saved record, so the page it picks must contain that time by
 * {@code getPeriodStart}/{@code getPeriodEnd}'s own reckoning -- including a record on the first
 * or last millisecond of a period, and across New Year, a leap day and DST. Also
 * {@link ViewedPeriod#focusTime}, which with it decides the page a records view opens on when
 * another view, or another period length, was showing a different period.
 *
 * <p>Writes only a test-named preferences file, deleted afterwards; opens no database.
 */
@RunWith(AndroidJUnit4.class)
public class SavedRecordPageTest {

    private static final String PREFS = "saved_record_page_test";
    private static final int[] PAGED_MODES = {
            LogTabsFragment.KEY_RECORDS_MODE_YEAR,
            LogTabsFragment.KEY_RECORDS_MODE_MONTH,
            LogTabsFragment.KEY_RECORDS_MODE_WEEK,
            LogTabsFragment.KEY_RECORDS_MODE_DAY,
    };
    /** Half the pager's 1000 pages: the furthest a page can be from today's. */
    private static final int HALF_WINDOW = 500;
    private static final int MAX_REPORTED = 15;

    private Context context;
    private SharedPreferences prefs;
    private TimeZone savedZone;
    private final List<String> failures = new ArrayList<>();
    private int failureCount;
    private long checks;

    @Before
    public void setUp() {
        savedZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() {
        ViewedPeriod.clear();
        prefs.edit().clear().commit();
        context.deleteSharedPreferences(PREFS);
        TimeZone.setDefault(savedZone);
    }

    private static Calendar at(int year, int month, int day, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, hour, minute);
        return c;
    }

    private static String fmt(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%tF %<tT.%<tL", c);
    }

    private int offsetFor(int mode, long time, Calendar now) {
        return LogTabsFragment.pageOffsetFor(mode, time, context, prefs, now);
    }

    private void checkContains(int mode, long time, Calendar now) {
        checks++;
        int offset = offsetFor(mode, time, now);
        long start = LogTabsFragment.getPeriodStart(mode, offset, context, prefs, now).getTimeInMillis();
        long end = LogTabsFragment.getPeriodEnd(mode, offset, context, prefs, now).getTimeInMillis();
        if (start <= time && time <= end)
            return;
        failureCount++;
        if (failures.size() < MAX_REPORTED)
            failures.add("mode " + mode + " now " + fmt(now.getTimeInMillis()) + " record " + fmt(time)
                    + ": offset " + offset + " is " + fmt(start) + " .. " + fmt(end));
    }

    @Test
    public void pageHoldsTheRecord_forEveryDayWithinTheWindow() {
        Calendar[] nows = {
                at(2026, Calendar.SEPTEMBER, 27, 12, 0),
                at(2027, Calendar.JANUARY, 31, 0, 0),
                at(2028, Calendar.FEBRUARY, 29, 23, 59),
        };
        String[] weekStarts = {"0", "1", "2"};
        for (String weekStart : weekStarts) {
            prefs.edit().putString(context.getString(R.string.pref_key_first_day_of_week), weekStart).commit();
            for (Calendar now : nows) {
                for (int mode : PAGED_MODES) {
                    if (mode != LogTabsFragment.KEY_RECORDS_MODE_WEEK && !weekStart.equals("2"))
                        continue; // only weeks depend on the setting
                    // Every day near today, then every 17th day out to well back and well
                    // ahead (a prime step, so it walks through weekdays and days of the month),
                    // each at a period's first and last millisecond and at midday. Day mode only
                    // reaches 500 days either way. Every day of the range took 12 minutes.
                    int span = mode == LogTabsFragment.KEY_RECORDS_MODE_DAY ? HALF_WINDOW - 1 : 1500;
                    for (int i = -span; i <= span; i += Math.abs(i) < 45 ? 1 : 17) {
                        Calendar day = (Calendar) now.clone();
                        day.set(Calendar.HOUR_OF_DAY, 0);
                        day.set(Calendar.MINUTE, 0);
                        day.add(Calendar.DATE, i);
                        long midnight = day.getTimeInMillis();
                        checkContains(mode, midnight, now);
                        checkContains(mode, midnight + 12 * 3600_000L, now);
                        day.add(Calendar.DATE, 1);
                        checkContains(mode, day.getTimeInMillis() - 1, now);
                    }
                }
            }
        }
        assertTrue(failureCount + " of " + checks + " checks failed, first ones:\n"
                + String.join("\n", failures), failureCount == 0);
    }

    @Test
    public void todayIsTheMiddlePage() {
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        for (int mode : PAGED_MODES)
            assertEquals(0, offsetFor(mode, now.getTimeInMillis(), now));
        assertEquals(0, offsetFor(LogTabsFragment.KEY_RECORDS_MODE_ALL, 0L, now));
    }

    @Test
    public void lastMonthIsOnePageBack() {
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        long lastMonth = at(2026, Calendar.AUGUST, 31, 23, 59).getTimeInMillis();
        assertEquals(-1, offsetFor(LogTabsFragment.KEY_RECORDS_MODE_MONTH, lastMonth, now));
        long nextMonth = at(2026, Calendar.OCTOBER, 1, 0, 0).getTimeInMillis();
        assertEquals(1, offsetFor(LogTabsFragment.KEY_RECORDS_MODE_MONTH, nextMonth, now));
    }

    @Test
    public void aRecordBeyondTheWindowClampsToItsEdge() {
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        long longAgo = at(2020, Calendar.JANUARY, 1, 12, 0).getTimeInMillis();
        long farAhead = at(2032, Calendar.JANUARY, 1, 12, 0).getTimeInMillis();
        assertEquals(-HALF_WINDOW, offsetFor(LogTabsFragment.KEY_RECORDS_MODE_DAY, longAgo, now));
        assertEquals(HALF_WINDOW - 1, offsetFor(LogTabsFragment.KEY_RECORDS_MODE_DAY, farAhead, now));
    }

    private long periodStart(int mode, int offset, Calendar now) {
        return LogTabsFragment.getPeriodStart(mode, offset, context, prefs, now).getTimeInMillis();
    }

    private long periodEnd(int mode, int offset, Calendar now) {
        return LogTabsFragment.getPeriodEnd(mode, offset, context, prefs, now).getTimeInMillis();
    }

    /** The user pages, by hand, to {@code offset} in {@code mode}. */
    private void pagedTo(int mode, int offset, Calendar now) {
        ViewedPeriod.paged(periodStart(mode, offset, now), periodEnd(mode, offset, now), now.getTimeInMillis());
    }

    /** A view in {@code mode} opens -- a view switch or a new period length -- as the app does it. */
    private int open(int mode, Calendar now) {
        long t = now.getTimeInMillis();
        long target = ViewedPeriod.focusTime(t);
        int offset = offsetFor(mode, target, now);
        ViewedPeriod.shown(periodStart(mode, offset, now), periodEnd(mode, offset, now), target, t);
        return offset;
    }

    /** The page a view in {@code toMode} opens on after the user paged to {@code fromOffset} in {@code fromMode}. */
    private int switchTo(int toMode, int fromMode, int fromOffset, Calendar now) {
        ViewedPeriod.clear();
        pagedTo(fromMode, fromOffset, now);
        return open(toMode, now);
    }

    @Test
    public void samePeriodLength_keepsThePage() {
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        for (int mode : PAGED_MODES)
            for (int offset = -30; offset <= 30; offset++)
                assertEquals(offset, switchTo(mode, mode, offset, now));
    }

    @Test
    public void pastMonthIntoWeeksOrDays_opensOnItsFirstDay() {
        prefs.edit().putString(context.getString(R.string.pref_key_first_day_of_week), "2").commit();
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        // July 2026: 1 Jul is a Wednesday, so its week starts Monday 29 Jun
        int week = switchTo(LogTabsFragment.KEY_RECORDS_MODE_WEEK, LogTabsFragment.KEY_RECORDS_MODE_MONTH, -2, now);
        assertEquals(at(2026, Calendar.JUNE, 29, 0, 0).getTimeInMillis(),
                periodStart(LogTabsFragment.KEY_RECORDS_MODE_WEEK, week, now));
        int day = switchTo(LogTabsFragment.KEY_RECORDS_MODE_DAY, LogTabsFragment.KEY_RECORDS_MODE_MONTH, -2, now);
        assertEquals(at(2026, Calendar.JULY, 1, 0, 0).getTimeInMillis(),
                periodStart(LogTabsFragment.KEY_RECORDS_MODE_DAY, day, now));
    }

    @Test
    public void currentMonthIntoWeeksOrDays_opensOnToday() {
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        assertEquals(0, switchTo(LogTabsFragment.KEY_RECORDS_MODE_WEEK, LogTabsFragment.KEY_RECORDS_MODE_MONTH, 0, now));
        assertEquals(0, switchTo(LogTabsFragment.KEY_RECORDS_MODE_DAY, LogTabsFragment.KEY_RECORDS_MODE_YEAR, 0, now));
    }

    @Test
    public void aWeekIntoMonths_opensOnTheMonthItStartsIn() {
        prefs.edit().putString(context.getString(R.string.pref_key_first_day_of_week), "2").commit();
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        // the week of Monday 29 Jun 2026 runs into July; it opens June
        int week = offsetFor(LogTabsFragment.KEY_RECORDS_MODE_WEEK, at(2026, Calendar.JULY, 2, 12, 0).getTimeInMillis(), now);
        assertEquals(-3, switchTo(LogTabsFragment.KEY_RECORDS_MODE_MONTH, LogTabsFragment.KEY_RECORDS_MODE_WEEK, week, now));
        // and "All" into anything is today's period
        assertEquals(0, switchTo(LogTabsFragment.KEY_RECORDS_MODE_MONTH, LogTabsFragment.KEY_RECORDS_MODE_ALL, 0, now));
    }

    @Test
    public void narrowingAndWideningAgain_comesBackToTheSameMonth() {
        prefs.edit().putString(context.getString(R.string.pref_key_first_day_of_week), "2").commit();
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        int month = LogTabsFragment.KEY_RECORDS_MODE_MONTH;
        int week = LogTabsFragment.KEY_RECORDS_MODE_WEEK;
        int day = LogTabsFragment.KEY_RECORDS_MODE_DAY;
        // July -> the week of 29 Jun -> 1 Jul -> July, not June
        int w = switchTo(week, month, -2, now);
        int d = open(day, now);
        assertEquals(at(2026, Calendar.JULY, 1, 0, 0).getTimeInMillis(), periodStart(day, d, now));
        assertEquals(-2, open(month, now));
        // paging by hand moves it: the week of 29 Jun paged to on its own widens to June
        assertEquals(-3, switchTo(month, week, w, now));
    }

    @Test
    public void theCurrentMonth_keepsFollowingTodayIntoTheNext() {
        int month = LogTabsFragment.KEY_RECORDS_MODE_MONTH;
        Calendar sep30 = at(2026, Calendar.SEPTEMBER, 30, 23, 0);
        Calendar oct1 = at(2026, Calendar.OCTOBER, 1, 9, 0);
        ViewedPeriod.clear();
        pagedTo(month, 0, sep30);
        // on the 1st, "this month" is October, not a September that has become last month
        assertEquals(0, open(month, oct1));
        // a past month stays put
        pagedTo(month, -2, oct1);
        assertEquals(-2, open(month, oct1));
    }

    @Test
    public void widenedThroughAPeriodHoldingToday_comesBackToTheSameMonth() {
        int month = LogTabsFragment.KEY_RECORDS_MODE_MONTH;
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        // July -> Year 2026 (which holds today) -> Month: July, not September
        assertEquals(0, switchTo(LogTabsFragment.KEY_RECORDS_MODE_YEAR, month, -2, now));
        assertEquals(-2, open(month, now));
        // and through All
        assertEquals(0, open(LogTabsFragment.KEY_RECORDS_MODE_ALL, now));
        assertEquals(-2, open(month, now));
        // the current month through Year stays the current month
        assertEquals(0, switchTo(LogTabsFragment.KEY_RECORDS_MODE_YEAR, month, 0, now));
        assertEquals(0, open(month, now));
    }

    @Test
    public void aPageClampedByTheWindow_doesNotReplaceWhereTheUserWas() {
        int month = LogTabsFragment.KEY_RECORDS_MODE_MONTH;
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        // January 2025 is 20 months back, but more than 500 days: Day mode clamps to its edge
        assertEquals(-HALF_WINDOW, switchTo(LogTabsFragment.KEY_RECORDS_MODE_DAY, month, -20, now));
        assertEquals(-20, open(month, now));
    }

    @Test
    public void focusOnATime_opensItsPeriodInEveryLength() {
        Calendar now = at(2026, Calendar.SEPTEMBER, 27, 12, 0);
        long june15 = at(2026, Calendar.JUNE, 15, 23, 59).getTimeInMillis();
        ViewedPeriod.clear();
        ViewedPeriod.focusOn(june15);
        assertEquals(-3, open(LogTabsFragment.KEY_RECORDS_MODE_MONTH, now));
        int day = open(LogTabsFragment.KEY_RECORDS_MODE_DAY, now);
        assertEquals(at(2026, Calendar.JUNE, 15, 0, 0).getTimeInMillis(),
                periodStart(LogTabsFragment.KEY_RECORDS_MODE_DAY, day, now));
    }
}
