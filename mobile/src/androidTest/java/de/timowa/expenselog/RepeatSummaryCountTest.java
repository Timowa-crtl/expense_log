package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The entry count under the New Record screen's repeat controls must be what saving writes.
 *
 * <p>{@link NewLogFragment#countOccurrences} counts the edited record plus every occurrence
 * {@link DBAdapter#createRepeatingSeries} would insert, so each case here creates the series and
 * compares. Past the cap, the count must come out higher than the cap allows, because that is
 * what makes the screen show the "too many" message instead of a number.
 */
@RunWith(AndroidJUnit4.class)
public class RepeatSummaryCountTest {

    private static final int DAY = RepeatingSeries.PERIOD_DAY, WEEK = RepeatingSeries.PERIOD_WEEK,
            MONTH = RepeatingSeries.PERIOD_MONTH, YEAR = RepeatingSeries.PERIOD_YEAR;

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
        adapter.close();
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

    /** The last millisecond of the day, as the screen's end-date picker sets it. */
    private static long endOf(int y, int m, int d) {
        return at(y, m, d, 23, 59) + 59_999;
    }

    private long storedFor(int repeatingId) {
        try (Cursor c = adapter.getDB().rawQuery("SELECT COUNT(*) FROM mainLogs WHERE repeatingId = ?",
                new String[]{String.valueOf(repeatingId)})) {
            c.moveToFirst();
            return c.getLong(0);
        }
    }

    private void assertCountMatchesSave(long start, long end, int frequency, int period) {
        int id = adapter.createRepeatingSeries(start, end, 10, frequency, period, 0, 1, 1, "", null);
        assertNotEquals(-1, id);
        // The screen's count includes the record being saved; the series writes the rest.
        assertEquals(frequency + "/" + period, storedFor(id) + 1,
                NewLogFragment.countOccurrences(start, end, frequency, period));
    }

    @Test
    public void count_isTheRecordPlusWhatTheSeriesWrites() {
        long start = at(2026, 9, 16, 16, 2);
        assertCountMatchesSave(start, endOf(2027, 9, 16), 1, MONTH);  // the 16th itself is included
        assertCountMatchesSave(start, endOf(2027, 9, 16), 3, WEEK);
        assertCountMatchesSave(start, endOf(2027, 9, 16), 1, DAY);
        assertCountMatchesSave(start, endOf(2031, 9, 15), 1, YEAR);   // a day short of the 5th
        assertCountMatchesSave(at(2026, 1, 31, 10, 0), endOf(2026, 12, 31), 1, MONTH);
    }

    @Test
    public void count_endOnTheStartDay_isTheRecordAlone() {
        long start = at(2026, 9, 16, 16, 2);
        assertEquals(1, NewLogFragment.countOccurrences(start, endOf(2026, 9, 16), 1, DAY));
        assertEquals(13, NewLogFragment.countOccurrences(start, endOf(2027, 9, 16), 1, MONTH));
    }

    @Test
    public void count_pastTheCap_isOneMoreThanSavingAllows() {
        long start = at(2026, 1, 1, 10, 0);
        long end = endOf(2100, 1, 1);
        int count = NewLogFragment.countOccurrences(start, end, 1, DAY);
        assertEquals(DBAdapter.MAX_REPEATING_OCCURRENCES + 2, count);
        assertEquals("and saving it is refused", -1,
                adapter.createRepeatingSeries(start, end, 10, 1, DAY, 0, 1, 1, "", null));
    }
}
