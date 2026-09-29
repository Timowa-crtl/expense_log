package de.timowa.expenselog;

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
 * {@link PeriodBoundsTest}'s checks, for every "today" across four years instead of only the day
 * the suite happens to run.
 *
 * <p>That difference is the bug this exists for. {@code getPeriodEnd} used to set the month and
 * then ask for the month's last day while today's day number was still in the calendar, so on the
 * 29th-31st the pages overlapped: with today 30 Jan, February ran 1 Feb - 3 Mar.
 * {@code PeriodBoundsTest.neighbouringPeriodsTile} could only catch that when run on one of those
 * days. Pinning "now" makes it deterministic. docs/history/RELIABILITY_PLAN.md, Step 2.
 *
 * <p>Failures are collected rather than thrown at the first one, so a red run says how widespread
 * the problem is. Writes only a test-named preferences file, deleted afterwards; opens no database.
 */
@RunWith(AndroidJUnit4.class)
public class PinnedPeriodBoundsTest {

    private static final String PREFS = "pinned_period_bounds_test";
    private static final int[] PAGED_MODES = {
            LogTabsFragment.KEY_RECORDS_MODE_YEAR,
            LogTabsFragment.KEY_RECORDS_MODE_MONTH,
            LogTabsFragment.KEY_RECORDS_MODE_WEEK,
            LogTabsFragment.KEY_RECORDS_MODE_DAY,
    };
    /** Every stored value of pref_first_day_of_week_values: 0 is Saturday, 1..6 Sunday..Friday. */
    private static final String[] WEEK_STARTS = {"0", "1", "2", "3", "4", "5", "6"};
    private static final int MAX_REPORTED = 15;

    private Context context;
    private SharedPreferences prefs;
    private TimeZone savedZone;
    private final List<String> failures = new ArrayList<>();
    private int failureCount;
    private final java.util.Map<String, Integer> failuresByKind = new java.util.TreeMap<>();
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
        prefs.edit().clear().commit();
        context.deleteSharedPreferences(PREFS);
        TimeZone.setDefault(savedZone);
    }

    private void check(boolean ok, String kind, String what) {
        checks++;
        if (ok)
            return;
        failureCount++;
        failuresByKind.merge(kind, 1, Integer::sum);
        if (failures.size() < MAX_REPORTED)
            failures.add(what);
    }

    private void assertNoFailures() {
        assertTrue(failureCount + " of " + checks + " checks failed, by kind " + failuresByKind
                + ", first ones:\n"
                + String.join("\n", failures), failureCount == 0);
    }

    private long start(int mode, int offset, Calendar now) {
        return LogTabsFragment.getPeriodStart(mode, offset, context, prefs, now).getTimeInMillis();
    }

    private long end(int mode, int offset, Calendar now) {
        return LogTabsFragment.getPeriodEnd(mode, offset, context, prefs, now).getTimeInMillis();
    }

    private static String fmt(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%tF %<tT.%<tL", c);
    }

    private static String modeName(int mode) {
        switch (mode) {
            case LogTabsFragment.KEY_RECORDS_MODE_YEAR: return "YEAR";
            case LogTabsFragment.KEY_RECORDS_MODE_MONTH: return "MONTH";
            case LogTabsFragment.KEY_RECORDS_MODE_WEEK: return "WEEK";
            default: return "DAY";
        }
    }

    /** Every day 2026-2028 (three New Years, a leap day, six DST changes) at the first millisecond, midday, and the last millisecond. */
    private interface NowVisitor {
        void visit(Calendar now, String weekStart);
    }

    private void forEveryNow(NowVisitor visitor) {
        int[][] times = {{0, 0, 0, 0}, {12, 34, 56, 789}, {23, 59, 59, 999}};
        for (String weekStart : WEEK_STARTS) {
            prefs.edit().putString(context.getString(R.string.pref_key_first_day_of_week), weekStart).commit();
            Calendar day = Calendar.getInstance();
            day.clear();
            day.set(2026, Calendar.JANUARY, 1);
            while (day.get(Calendar.YEAR) < 2029) {
                for (int[] t : times) {
                    Calendar now = (Calendar) day.clone();
                    now.set(Calendar.HOUR_OF_DAY, t[0]);
                    now.set(Calendar.MINUTE, t[1]);
                    now.set(Calendar.SECOND, t[2]);
                    now.set(Calendar.MILLISECOND, t[3]);
                    now.getTimeInMillis();
                    visitor.visit(now, weekStart);
                }
                day.add(Calendar.DATE, 1);
            }
        }
    }

    @Test
    public void neighbouringPeriodsTile_whateverDayItIs() {
        forEveryNow((now, weekStart) -> {
            for (int mode : PAGED_MODES) {
                if (mode != LogTabsFragment.KEY_RECORDS_MODE_WEEK && !weekStart.equals("2"))
                    continue; // only weeks depend on the setting
                for (int offset = -2; offset <= 2; offset++) {
                    long end = end(mode, offset, now);
                    long next = start(mode, offset + 1, now);
                    check(end + 1 == next, modeName(mode) + "/weekStart " + weekStart, "now " + fmt(now.getTimeInMillis()) + " weekStart "
                            + weekStart + " " + modeName(mode) + " offset " + offset + ": ends "
                            + fmt(end) + " but next starts " + fmt(next));
                }
            }
        });
        assertNoFailures();
    }

    @Test
    public void currentPageContainsToday_whateverDayItIs() {
        forEveryNow((now, weekStart) -> {
            long t = now.getTimeInMillis();
            for (int mode : PAGED_MODES) {
                if (mode != LogTabsFragment.KEY_RECORDS_MODE_WEEK && !weekStart.equals("2"))
                    continue;
                long start = start(mode, 0, now);
                long end = end(mode, 0, now);
                check(start <= t && t <= end, modeName(mode) + "/weekStart " + weekStart, "now " + fmt(t) + " weekStart " + weekStart + " "
                        + modeName(mode) + ": page is " + fmt(start) + " .. " + fmt(end));
            }
        });
        assertNoFailures();
    }

    @Test
    public void periodsStartOnTheirBoundary_whateverDayItIs() {
        forEveryNow((now, weekStart) -> {
            int expectedWeekday = weekStart.equals("0") ? Calendar.SATURDAY : Integer.parseInt(weekStart);
            for (int mode : PAGED_MODES) {
                if (mode != LogTabsFragment.KEY_RECORDS_MODE_WEEK && !weekStart.equals("2"))
                    continue;
                for (int offset = -2; offset <= 2; offset++) {
                    Calendar s = Calendar.getInstance();
                    s.setTimeInMillis(start(mode, offset, now));
                    boolean midnight = s.get(Calendar.HOUR_OF_DAY) == 0 && s.get(Calendar.MINUTE) == 0
                            && s.get(Calendar.SECOND) == 0 && s.get(Calendar.MILLISECOND) == 0;
                    boolean boundary;
                    switch (mode) {
                        case LogTabsFragment.KEY_RECORDS_MODE_YEAR:
                            boundary = s.get(Calendar.DAY_OF_YEAR) == 1;
                            break;
                        case LogTabsFragment.KEY_RECORDS_MODE_MONTH:
                            boundary = s.get(Calendar.DAY_OF_MONTH) == 1;
                            break;
                        case LogTabsFragment.KEY_RECORDS_MODE_WEEK:
                            boundary = s.get(Calendar.DAY_OF_WEEK) == expectedWeekday;
                            break;
                        default:
                            boundary = true;
                    }
                    check(midnight && boundary, modeName(mode) + "/weekStart " + weekStart, "now " + fmt(now.getTimeInMillis()) + " weekStart "
                            + weekStart + " " + modeName(mode) + " offset " + offset + ": starts "
                            + fmt(s.getTimeInMillis()));
                }
            }
        });
        assertNoFailures();
    }
}
