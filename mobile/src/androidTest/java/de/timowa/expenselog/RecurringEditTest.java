package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
 * Editing a repeating series after it was created: ending it, splitting it, changing its values
 * or schedule from one record on, and moving its end -- without a stored start date.
 *
 * <p>{@code RepeatingTable} has no start column, and adding one would bump the schema version and
 * make every new backup unimportable on an older build. {@link RepeatingSeries#findOrigin}
 * recovers a start from the remaining records instead, and these tests pin where that is exact,
 * where it is refused, and the one documented case where it is wrong.
 */
@RunWith(AndroidJUnit4.class)
public class RecurringEditTest {

    private static final int DAY = 0, WEEK = 1, MONTH = 2, YEAR = 3;

    private Context context;
    private DBAdapter adapter;
    private Locale savedLocale;
    private TimeZone savedZone;

    @Before
    public void setUp() {
        savedLocale = Locale.getDefault();
        savedZone = TimeZone.getDefault();
        Locale.setDefault(Locale.GERMANY);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        adapter = new DBAdapter(context);
        adapter.open();
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        Locale.setDefault(savedLocale);
        TimeZone.setDefault(savedZone);
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

    private static LogItem values(double amount, long time) {
        LogItem item = new LogItem();
        item.setAmount(amount);
        item.setTimeStamp(time);
        item.setAccountId(1);
        item.setCategory(3);
        item.setNotes("salary");
        item.setImageUri("");
        item.setExpenseIncome(1);
        return item;
    }

    /** A series as the app saves one: the rest of the series, then the first record. */
    private int series(long first, long end, int frequency, int period, double amount) {
        int id = adapter.createRepeatingSeries(first, end, amount, frequency, period, 1, 1, 3, "salary", "");
        assertNotEquals(-1, id);
        assertTrue(adapter.newLog(first, 1, 1, amount, 3, "salary", "", id));
        return id;
    }

    private List<String> dates(int repeatingId) {
        List<String> dates = new ArrayList<>();
        for (long t : adapter.getRepeatingSeries(repeatingId).times)
            dates.add(date(t));
        return dates;
    }

    private List<Double> amounts(int repeatingId) {
        List<Double> amounts = new ArrayList<>();
        try (Cursor c = adapter.getLogs("repeatingId = " + repeatingId, DBAdapter.KEY_LOG_TIME)) {
            while (c.moveToNext())
                amounts.add(c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT));
        }
        return amounts;
    }

    private long count(String table) {
        try (Cursor c = adapter.getDB().rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            c.moveToFirst();
            return c.getLong(0);
        }
    }

    private int logIdAt(int repeatingId, long time) {
        RepeatingSeries s = adapter.getRepeatingSeries(repeatingId);
        return s.logIds[s.positionOf(time) - 1];
    }

    // --- the recovered origin ---

    @Test
    public void origin_monthlyOnThe31st_withItsFirstRecordsGone_isStillThe31st() {
        int id = series(at(2026, 1, 31), at(2026, 7, 1), 1, MONTH, 10);
        adapter.deleteLog(logIdAt(id, at(2026, 1, 31)));
        adapter.deleteLog(logIdAt(id, at(2026, 2, 28)));

        assertTrue(adapter.setRepeatingEnd(id, at(2026, 10, 1)) >= 0);
        assertEquals(List.of("2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30",
                "2026-07-31", "2026-08-31", "2026-09-30"), dates(id));
    }

    @Test
    public void origin_monthlyOnThe31st_startingInFebruary_isStillThe31st() {
        // The earliest remaining record is the clamped one; the 31st comes from a later record.
        int id = series(at(2026, 1, 31), at(2026, 5, 1), 1, MONTH, 10);
        adapter.deleteLog(logIdAt(id, at(2026, 1, 31)));
        assertEquals("2026-02-28", dates(id).get(0));

        adapter.setRepeatingEnd(id, at(2026, 6, 1));
        assertEquals(List.of("2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31"), dates(id));
    }

    /**
     * The documented limit: when no remaining record shows the real day, the latest day that does
     * is used, and an extension into a longer month lands early. Pinned so a change to it is a
     * decision, not an accident.
     */
    @Test
    public void origin_monthlyOnThe31st_withEveryLongMonthGone_landsOnThe30th() {
        int id = series(at(2026, 1, 31), at(2026, 5, 1), 1, MONTH, 10);
        adapter.deleteLog(logIdAt(id, at(2026, 1, 31)));
        adapter.deleteLog(logIdAt(id, at(2026, 3, 31)));
        assertEquals(List.of("2026-02-28", "2026-04-30"), dates(id));

        adapter.setRepeatingEnd(id, at(2026, 6, 1));
        assertEquals(List.of("2026-02-28", "2026-04-30", "2026-05-30"), dates(id));
    }

    @Test
    public void origin_yearlyFromLeapDay_withTheLeapYearGone_returnsToLeapDay() {
        int id = series(at(2024, 2, 29), at(2028, 3, 1), 1, YEAR, 10);
        adapter.deleteLog(logIdAt(id, at(2024, 2, 29)));
        adapter.setRepeatingEnd(id, at(2032, 3, 1));
        assertEquals(List.of("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29",
                "2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"), dates(id));
    }

    @Test
    public void origin_weeklyAcrossNewYear_extendsSevenDaysOnward() {
        int id = series(at(2025, 12, 1), at(2025, 12, 20), 1, WEEK, 10);
        adapter.deleteLog(logIdAt(id, at(2025, 12, 1)));
        adapter.setRepeatingEnd(id, at(2026, 1, 13));
        assertEquals(List.of("2025-12-08", "2025-12-15", "2025-12-22", "2025-12-29",
                "2026-01-05", "2026-01-12"), dates(id));
    }

    @Test
    public void origin_dailyAcrossDaylightSaving_isFound() {
        long first = at(2026, 3, 27, 0, 30);
        int id = series(first, at(2026, 4, 2), 1, DAY, 10);
        RepeatingSeries.Origin origin = adapter.getRepeatingSeries(id).findOrigin();
        assertNotNull(origin);
        assertEquals(first, origin.time);
        assertEquals(0, origin.firstK);
    }

    @Test
    public void origin_everyThreeMonths_countsInSteps() {
        int id = series(at(2025, 11, 30), at(2026, 12, 1), 3, MONTH, 10);
        RepeatingSeries.Origin origin = adapter.getRepeatingSeries(id).findOrigin();
        assertNotNull(origin);
        assertEquals(0, origin.firstK);
        assertEquals(4, origin.lastK);
    }

    @Test
    public void origin_ofASeriesOffItsSchedule_isNull_andExtendingItWritesNothing() {
        int id = series(at(2026, 1, 1), at(2026, 6, 2), 1, MONTH, 10);
        // one record a day off, as the old stepping bug or a time-zone change would leave it
        int moved = logIdAt(id, at(2026, 3, 1));
        adapter.updateLog(moved, at(2026, 3, 2), 1, 1, 10, 3, "salary", "", id);
        assertNull(adapter.getRepeatingSeries(id).findOrigin());

        long before = count("mainLogs");
        long endBefore = adapter.getRepeatingSeries(id).endTime;
        assertEquals(DBAdapter.SERIES_NO_SCHEDULE, adapter.setRepeatingEnd(id, at(2026, 12, 1)));
        assertEquals(before, count("mainLogs"));
        assertEquals(endBefore, adapter.getRepeatingSeries(id).endTime);

        // shortening needs no schedule
        assertEquals(2, adapter.setRepeatingEnd(id, at(2026, 4, 15)));
        assertEquals(List.of("2026-01-01", "2026-02-01", "2026-03-02", "2026-04-01"), dates(id));
    }

    @Test
    public void origin_twoRecordsOnOneOccurrence_isNull() {
        int id = series(at(2026, 1, 1), at(2026, 3, 2), 1, MONTH, 10);
        adapter.newLog(at(2026, 2, 1), 1, 1, 10, 3, "salary", "", id);
        assertNull(adapter.getRepeatingSeries(id).findOrigin());
    }

    @Test
    public void extend_leavesRecordsDeletedBeforeTheOldEndDeleted() {
        int id = series(at(2026, 1, 1), at(2026, 6, 2), 1, MONTH, 10);
        adapter.deleteLog(logIdAt(id, at(2026, 6, 1)));
        adapter.deleteLog(logIdAt(id, at(2026, 5, 1)));
        adapter.setRepeatingEnd(id, at(2026, 8, 2));
        assertEquals(List.of("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01",
                "2026-07-01", "2026-08-01"), dates(id));
    }

    @Test
    public void extend_copiesTheLatestRecordsValues() {
        int id = series(at(2026, 1, 1), at(2026, 2, 2), 1, MONTH, 10);
        adapter.updateLog(logIdAt(id, at(2026, 2, 1)), at(2026, 2, 1), 0, 2, 55.5, 7, "rent", "", id);
        adapter.setRepeatingEnd(id, at(2026, 3, 2));
        try (Cursor c = adapter.getLog(String.valueOf(logIdAt(id, at(2026, 3, 1))))) {
            assertTrue(c.moveToFirst());
            assertEquals(55.5, c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT), 0);
            assertEquals(7, c.getInt(DBAdapter.COLUMN_LOG_CATEGORY));
            assertEquals(2, c.getInt(DBAdapter.COLUMN_LOG_ACCOUNT));
            assertEquals(0, c.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME));
            assertEquals("rent", c.getString(DBAdapter.COLUMN_LOG_NOTES));
        }
    }

    @Test
    public void extend_pastTheCeiling_writesNothing() {
        int id = series(at(2026, 1, 1), at(2026, 1, 5), 1, DAY, 10);
        long before = count("mainLogs");
        assertEquals(DBAdapter.SERIES_TOO_LONG, adapter.setRepeatingEnd(id, at(2090, 1, 1)));
        assertEquals(before, count("mainLogs"));
    }

    // --- deleting from a record on ---

    @Test
    public void deleteFrom_aMiddleRecord_endsTheSeriesTheDayBefore() {
        int id = series(at(2026, 1, 1), at(2026, 6, 2), 1, MONTH, 10);
        assertEquals(3, adapter.deleteRepeatingFrom(id, at(2026, 4, 1)));
        RepeatingSeries s = adapter.getRepeatingSeries(id);
        assertEquals(List.of("2026-01-01", "2026-02-01", "2026-03-01"), dates(id));
        assertEquals("2026-03-31", date(s.endTime));
        assertTrue(s.endTime < at(2026, 4, 1));
    }

    @Test
    public void deleteFrom_theFirstRecord_removesTheSeries() {
        int id = series(at(2026, 1, 1), at(2026, 6, 2), 1, MONTH, 10);
        assertEquals(6, adapter.deleteRepeatingFrom(id, at(2026, 1, 1)));
        assertNull(adapter.getRepeatingSeries(id));
        assertEquals(0, count("mainLogs"));
    }

    @Test
    public void deleteFrom_leavesOtherSeriesAlone() {
        int a = series(at(2026, 1, 1), at(2026, 6, 2), 1, MONTH, 10);
        int b = series(at(2026, 1, 1), at(2026, 6, 2), 1, MONTH, 20);
        adapter.deleteRepeatingFrom(a, at(2026, 2, 1));
        assertEquals(1, adapter.getRepeatingSeries(a).size());
        assertEquals(6, adapter.getRepeatingSeries(b).size());
    }

    // --- new values from a record on ---

    /** The feature request: a raise from April must not rewrite January to March. */
    @Test
    public void updateFrom_aRaiseInApril_splitsTheSeriesAndKeepsThePastAmounts() {
        int id = series(at(2026, 1, 1), at(2026, 12, 2), 1, MONTH, 3000);
        RepeatingSeries before = adapter.getRepeatingSeries(id);

        int raised = adapter.updateRepeatingFrom(id, at(2026, 4, 1), values(3200, at(2026, 4, 1)), before.endTime);
        assertTrue(raised > 0);
        assertNotEquals(id, raised);

        assertEquals(List.of("2026-01-01", "2026-02-01", "2026-03-01"), dates(id));
        assertEquals(List.of(3000.0, 3000.0, 3000.0), amounts(id));
        assertEquals(9, adapter.getRepeatingSeries(raised).size());
        assertEquals("2026-04-01", dates(raised).get(0));
        assertEquals("2026-12-01", dates(raised).get(8));
        for (double amount : amounts(raised))
            assertEquals(3200, amount, 0);
        assertEquals(12, count("mainLogs"));

        RepeatingSeries after = adapter.getRepeatingSeries(raised);
        assertEquals(before.endTime, after.endTime);
        assertEquals(MONTH, after.period);
        assertEquals(1, after.frequency);
        assertEquals(3200, after.amount, 0);
        assertEquals("2026-03-31", date(adapter.getRepeatingSeries(id).endTime));
    }

    @Test
    public void updateFrom_theFirstRecord_changesTheSeriesInPlace() {
        int id = series(at(2026, 1, 1), at(2026, 4, 2), 1, MONTH, 10);
        long end = adapter.getRepeatingSeries(id).endTime;
        assertEquals(id, adapter.updateRepeatingFrom(id, at(2026, 1, 1), values(12, at(2026, 1, 1)), end));
        assertEquals(List.of(12.0, 12.0, 12.0, 12.0), amounts(id));
        assertEquals(1, count("RepeatingTable"));
        assertEquals(12, adapter.getRepeatingSeries(id).amount, 0);
    }

    @Test
    public void updateAll_withALaterEnd_changesEveryRecordAndExtends() {
        int id = series(at(2026, 1, 1), at(2026, 3, 2), 1, MONTH, 10);
        assertEquals(id, adapter.updateRepeatingFrom(id, Long.MIN_VALUE, values(15, at(2026, 2, 1)), at(2026, 5, 2)));
        assertEquals(List.of("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01", "2026-05-01"), dates(id));
        assertEquals(List.of(15.0, 15.0, 15.0, 15.0, 15.0), amounts(id));
    }

    @Test
    public void updateFrom_withAnEarlierEnd_splitsAndShortensTheNewPart() {
        int id = series(at(2026, 1, 1), at(2026, 12, 2), 1, MONTH, 10);
        int b = adapter.updateRepeatingFrom(id, at(2026, 6, 1), values(20, at(2026, 6, 1)), at(2026, 8, 2));
        assertEquals(List.of("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01", "2026-05-01"), dates(id));
        assertEquals(List.of("2026-06-01", "2026-07-01", "2026-08-01"), dates(b));
    }

    @Test
    public void updateFrom_aSeriesOffItsSchedule_extendingIt_writesNothing() {
        int id = series(at(2026, 1, 1), at(2026, 4, 2), 1, MONTH, 10);
        // off schedule inside the part that moves to the new series, which is what gets extended
        adapter.updateLog(logIdAt(id, at(2026, 3, 1)), at(2026, 3, 3), 1, 1, 10, 3, "salary", "", id);
        long before = count("RepeatingTable");
        assertEquals(DBAdapter.SERIES_NO_SCHEDULE,
                adapter.updateRepeatingFrom(id, at(2026, 2, 1), values(99, at(2026, 2, 1)), at(2026, 9, 2)));
        assertEquals(before, count("RepeatingTable"));
        assertEquals(List.of(10.0, 10.0, 10.0, 10.0), amounts(id));
    }

    // --- a new schedule from a record on ---

    @Test
    public void restartFrom_aMiddleRecord_withANewInterval() {
        int id = series(at(2026, 1, 15), at(2026, 12, 31), 1, MONTH, 10);
        int b = adapter.restartRepeatingFrom(id, at(2026, 4, 15), values(10, at(2026, 4, 15)), 2, MONTH, at(2026, 12, 31));
        assertTrue(b > 0);
        assertNotEquals(id, b);
        assertEquals(List.of("2026-01-15", "2026-02-15", "2026-03-15"), dates(id));
        assertEquals(List.of("2026-04-15", "2026-06-15", "2026-08-15", "2026-10-15", "2026-12-15"), dates(b));
        RepeatingSeries s = adapter.getRepeatingSeries(b);
        assertEquals(2, s.frequency);
        assertEquals(MONTH, s.period);
    }

    /** Payday moves from the 1st to the 25th, from June on. */
    @Test
    public void restartFrom_aMovedDate_movesEveryFollowingRecord() {
        int id = series(at(2026, 1, 1), at(2026, 9, 2), 1, MONTH, 10);
        int b = adapter.restartRepeatingFrom(id, at(2026, 6, 1), values(10, at(2026, 5, 25)), 1, MONTH, at(2026, 9, 2));
        assertEquals(List.of("2026-01-01", "2026-02-01", "2026-03-01", "2026-04-01", "2026-05-01"), dates(id));
        assertEquals(List.of("2026-05-25", "2026-06-25", "2026-07-25", "2026-08-25"), dates(b));
    }

    @Test
    public void restartFrom_theFirstRecord_keepsTheSeriesId() {
        int id = series(at(2026, 1, 1), at(2026, 3, 2), 1, MONTH, 10);
        assertEquals(id, adapter.restartRepeatingFrom(id, at(2026, 1, 1), values(10, at(2026, 1, 3)), 1, WEEK, at(2026, 1, 25)));
        assertEquals(List.of("2026-01-03", "2026-01-10", "2026-01-17", "2026-01-24"), dates(id));
        assertEquals(1, count("RepeatingTable"));
        assertEquals(WEEK, adapter.getRepeatingSeries(id).period);
    }

    @Test
    public void restartFrom_pastTheCeiling_writesNothing() {
        int id = series(at(2026, 1, 1), at(2026, 3, 2), 1, MONTH, 10);
        long logs = count("mainLogs");
        long entries = count("RepeatingTable");
        assertEquals(DBAdapter.SERIES_TOO_LONG,
                adapter.restartRepeatingFrom(id, at(2026, 2, 1), values(10, at(2026, 2, 1)), 1, DAY, at(2090, 1, 1)));
        assertEquals(logs, count("mainLogs"));
        assertEquals(entries, count("RepeatingTable"));
        assertEquals(List.of("2026-01-01", "2026-02-01", "2026-03-01"), dates(id));
    }

    @Test
    public void restartFrom_aZeroInterval_isRefused() {
        int id = series(at(2026, 1, 1), at(2026, 3, 2), 1, MONTH, 10);
        assertEquals(-1, adapter.restartRepeatingFrom(id, at(2026, 2, 1), values(10, at(2026, 2, 1)), 0, MONTH, at(2026, 9, 1)));
        assertEquals(3, adapter.getRepeatingSeries(id).size());
    }

    // --- reading ---

    @Test
    public void allSeries_leavesOutASeriesWithNoRecords() {
        int a = series(at(2026, 1, 1), at(2026, 2, 2), 1, MONTH, 10);
        int b = series(at(2026, 1, 1), at(2026, 2, 2), 1, MONTH, 10);
        adapter.deleteLog(logIdAt(b, at(2026, 1, 1)));
        adapter.deleteLog(logIdAt(b, at(2026, 2, 1)));
        List<RepeatingSeries> all = adapter.getAllRepeatingSeries();
        assertEquals(1, all.size());
        assertEquals(a, all.get(0).id);
        assertEquals(at(2026, 2, 1), all.get(0).latest.getTimeStamp());
    }

    @Test
    public void series_countsAndPositions() {
        int id = series(at(2026, 1, 1), at(2026, 6, 2), 1, MONTH, 10);
        RepeatingSeries s = adapter.getRepeatingSeries(id);
        assertEquals(6, s.size());
        assertEquals(3, s.positionOf(at(2026, 3, 1)));
        assertEquals(0, s.positionOf(at(2026, 3, 2)));
        assertEquals(4, s.countFrom(at(2026, 3, 1)));
        assertEquals(at(2026, 4, 1), s.nextAfter(at(2026, 3, 1)));
        assertEquals(-1, s.nextAfter(at(2026, 6, 1)));
    }

    @Test
    public void aSeriesEdit_marksTheDatabaseChanged() {
        int id = series(at(2026, 1, 1), at(2026, 6, 2), 1, MONTH, 10);
        long generation = DBAdapter.changeGeneration();
        adapter.deleteRepeatingFrom(id, at(2026, 4, 1));
        assertTrue(DBAdapter.changeGeneration() > generation);
    }
}
