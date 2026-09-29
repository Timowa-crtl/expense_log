package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

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
 * Pins how a repeating series lays out its occurrences.
 *
 * <p>Runs on the device rather than the JVM on purpose: the bugs it guards against lived in
 * {@code java.util.Calendar}'s field arithmetic, so the test has to use Android's own.
 *
 * <p>The old stepping ({@code set(field, get(field) + n)} on the previous occurrence) failed two
 * ways. Weekly series crossing New Year jumped back to January of the same year and never ended --
 * so there is deliberately no test here that runs the old code on that case, because it does not
 * terminate. Monthly series from the 29th-31st drifted onto the 1st-3rd and stayed there; the
 * monthly and leap-day cases below failed against it. docs/history/RELIABILITY_PLAN.md, Step 1.
 */
@RunWith(AndroidJUnit4.class)
public class RepeatingScheduleTest {

    private static final int DAY = 0, WEEK = 1, MONTH = 2, YEAR = 3;

    private static final Locale[] LOCALES = {Locale.US, Locale.GERMANY, Locale.UK};
    private static final String[] ZONES = {"Europe/Berlin", "America/New_York"};

    private Context context;
    private DBAdapter adapter;
    private Locale savedLocale;
    private TimeZone savedZone;

    @Before
    public void setUp() {
        savedLocale = Locale.getDefault();
        savedZone = TimeZone.getDefault();
        use(Locale.GERMANY, "Europe/Berlin");
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        adapter = new DBAdapter(context);
        adapter.open();
    }

    @After
    public void tearDown() {
        adapter.close();
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        Locale.setDefault(savedLocale);
        TimeZone.setDefault(savedZone);
    }

    private static void use(Locale locale, String zone) {
        Locale.setDefault(locale);
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
    }

    private static long at(int y, int m, int d, int hour, int minute) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, hour, minute, 0);
        return c.getTimeInMillis();
    }

    private static long at(int y, int m, int d) {
        return at(y, m, d, 10, 0);
    }

    private static String date(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%tF", c);
    }

    private static List<String> occurrences(long original, int count, int frequency, int period) {
        List<String> dates = new ArrayList<>();
        for (int k = 1; k <= count; k++)
            dates.add(date(DBAdapter.repeatingOccurrence(original, k, frequency, period)));
        return dates;
    }

    private List<String> storedDates() {
        List<String> dates = new ArrayList<>();
        try (Cursor c = adapter.getAllLogs()) {
            while (c.moveToNext())
                dates.add(date(c.getLong(DBAdapter.COLUMN_LOG_TIME)));
        }
        return dates;
    }

    private int series(long original, long end, int frequency, int period) {
        return adapter.createRepeatingSeries(original, end, 10, frequency, period, 0, 1, 1, "", null);
    }

    private long count(String table) {
        try (Cursor c = adapter.getDB().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            c.moveToFirst();
            return c.getLong(0);
        }
    }

    @Test
    public void weekly_acrossNewYear_isAlwaysSevenDaysOnward() {
        for (Locale locale : LOCALES) {
            for (String zone : ZONES) {
                use(locale, zone);
                // Built after use(), so each start is 10:00 local in the zone under test.
                long[] starts = {at(2025, 12, 1), at(2026, 12, 15), at(2029, 12, 1), at(2030, 12, 29)};
                for (long start : starts) {
                    // Checked with java.time, independently of the Calendar arithmetic under test.
                    java.time.ZoneId zoneId = java.time.ZoneId.of(zone);
                    java.time.ZonedDateTime first =
                            java.time.Instant.ofEpochMilli(start).atZone(zoneId);
                    for (int k = 1; k <= 12; k++) {
                        java.time.ZonedDateTime actual = java.time.Instant
                                .ofEpochMilli(DBAdapter.repeatingOccurrence(start, k, 1, WEEK))
                                .atZone(zoneId);
                        String where = locale + " " + zone + " from " + first.toLocalDate() + " k=" + k;
                        assertEquals(where, first.toLocalDate().plusDays(7L * k), actual.toLocalDate());
                        assertEquals(where, 10, actual.getHour());
                    }
                }
            }
        }
    }

    @Test
    public void monthly_fromThe31st_landsOnEachMonthsLastDayAndReturnsToThe31st() {
        for (Locale locale : LOCALES) {
            for (String zone : ZONES) {
                use(locale, zone);
                assertEquals(locale + " " + zone,
                        List.of("2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30"),
                        occurrences(at(2026, 1, 31), 5, 1, MONTH));
                assertEquals(locale + " " + zone, List.of("2026-02-28", "2026-03-30"),
                        occurrences(at(2026, 1, 30), 2, 1, MONTH));
            }
        }
    }

    @Test
    public void yearly_fromLeapDay_returnsToLeapDay() {
        assertEquals(List.of("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"),
                occurrences(at(2024, 2, 29), 4, 1, YEAR));
    }

    @Test
    public void everyTwoWeeks_andEveryThreeMonths() {
        assertEquals(List.of("2025-12-15", "2025-12-29", "2026-01-12"),
                occurrences(at(2025, 12, 1), 3, 2, WEEK));
        assertEquals(List.of("2026-02-28", "2026-05-30", "2026-08-30", "2026-11-30"),
                occurrences(at(2025, 11, 30), 4, 3, MONTH));
        assertEquals(List.of("2027-01-01", "2027-01-04"), occurrences(at(2026, 12, 29), 2, 3, DAY));
    }

    @Test
    public void daily_keepsItsWallClockTimeAcrossDaylightSaving() {
        use(Locale.GERMANY, "Europe/Berlin");
        long start = at(2026, 3, 25, 9, 30);
        for (int k = 1; k <= 240; k++) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(DBAdapter.repeatingOccurrence(start, k, 1, DAY));
            assertEquals("k=" + k, 9, c.get(Calendar.HOUR_OF_DAY));
            assertEquals("k=" + k, 30, c.get(Calendar.MINUTE));
        }
    }

    @Test
    public void series_monthlyFromThe31st_isStoredOnTheRightDates() {
        int id = series(at(2026, 1, 31), at(2026, 7, 1), 1, MONTH);
        assertNotEquals(-1, id);
        assertEquals(List.of("2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30"),
                storedDates());
    }

    @Test
    public void series_yearlyFromLeapDay_isStoredOnTheRightDates() {
        assertNotEquals(-1, series(at(2024, 2, 29), at(2028, 3, 1), 1, YEAR));
        assertEquals(List.of("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"), storedDates());
    }

    @Test
    public void series_weeklyAcrossNewYear_endsWhereItShould() {
        for (Locale locale : LOCALES) {
            use(locale, "Europe/Berlin");
            adapter.getDB().delete("mainLogs", null, null);
            int id = series(at(2025, 12, 1), at(2026, 2, 1), 1, WEEK);
            assertNotEquals(-1, id);
            assertEquals(locale.toString(), List.of("2025-12-08", "2025-12-15", "2025-12-22",
                    "2025-12-29", "2026-01-05", "2026-01-12", "2026-01-19", "2026-01-26"),
                    storedDates());
        }
    }

    @Test
    public void series_rowsShareOneRepeatingId() {
        int id = series(at(2026, 1, 1), at(2026, 1, 11), 1, DAY);
        try (Cursor c = adapter.getAllLogs()) {
            assertEquals(9, c.getCount());
            while (c.moveToNext())
                assertEquals(id, c.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
        }
        try (Cursor c = adapter.getRepeatingEntry(id)) {
            assertTrue(c.moveToFirst());
        }
    }

    /**
     * A frequency too large for the calendar never produces a record in the past. The day count
     * used to be int arithmetic, so a weekly frequency of 400 000 000 wrapped negative; and a
     * yearly one past about 292 million overflows the calendar's milliseconds even in long.
     */
    @Test
    public void aFrequencyTooLargeForTheCalendar_isPastAnyEnd_neverInThePast() {
        long start = at(2026, 1, 1);
        long end = at(2027, 1, 1);
        for (int period : new int[]{DAY, WEEK, MONTH, YEAR}) {
            for (int frequency : new int[]{400_000_000, 1_000_000_000, Integer.MAX_VALUE}) {
                for (int k : new int[]{1, 2, 20_001}) {
                    long t = DBAdapter.repeatingOccurrence(start, k, frequency, period);
                    assertTrue("period " + period + " frequency " + frequency + " k " + k
                            + " went back to " + t, t > start);
                }
                adapter.getDB().delete("mainLogs", null, null);
                series(start, end, frequency, period);
                assertEquals("period " + period + " frequency " + frequency, 0, count("mainLogs"));
            }
        }
    }

    @Test
    public void series_overTheCeiling_writesNothingAtAll() {
        long before = count("RepeatingTable");
        assertEquals(-1, series(at(2026, 1, 1), at(2086, 1, 1), 1, DAY));
        assertEquals(0, count("mainLogs"));
        assertEquals(before, count("RepeatingTable"));
    }

    @Test
    public void series_withAFrequencyThatNeverAdvances_writesNothingAtAll() {
        assertEquals(-1, series(at(2026, 1, 1), at(2027, 1, 1), 0, MONTH));
        assertEquals(0, count("mainLogs"));
        assertEquals(0, count("RepeatingTable"));
    }
}
