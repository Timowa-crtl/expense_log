package de.timowa.expenselog.Calendar;

import static org.junit.Assert.assertEquals;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

import de.timowa.expenselog.DBAdapter;
import de.timowa.expenselog.IsolatedDatabaseContext;

/**
 * The calendar's one-query month load gives every day exactly the records and the total the old
 * per-day and per-cell queries did. docs/history/RELIABILITY_PLAN.md, Step 8 (F11).
 */
@RunWith(AndroidJUnit4.class)
public class CalendarTotalsTest {

    private static final String[] FILTERS = {"", "expenseIncome = 0 AND ", "expenseIncome = 1 AND ",
            "categoryId = 2 AND "};

    private Context context;
    private SharedPreferences prefs;
    private TimeZone savedZone;

    @Before
    public void setUp() {
        savedZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        prefs = context.getSharedPreferences("calendar_totals_test", Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        context.deleteSharedPreferences("calendar_totals_test");
        TimeZone.setDefault(savedZone);
    }

    private static long at(int day, int hour, int minute, int second, int millis) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.MARCH, day, hour, minute, second);
        c.set(Calendar.MILLISECOND, millis);
        return c.getTimeInMillis();
    }

    private void seed() {
        new DBAdapter(context); // creates the schema
        Random random = new Random(314);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                context.getDatabasePath(DBAdapter.DATABASE_NAME).getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            // Every day of March 2026 (with the DST change on the 29th), the edges of each day,
            // and the neighbouring months' edges.
            for (int day = 1; day <= 31; day++) {
                int n = random.nextInt(4);
                for (int i = 0; i < n; i++)
                    insert(db, at(day, random.nextInt(24), random.nextInt(60), 0, 0), random);
                if (day % 5 == 0) {
                    insert(db, at(day, 0, 0, 0, 0), random);
                    insert(db, at(day, 23, 59, 59, 999), random);
                }
            }
            insert(db, at(1, 0, 0, 0, 0) - 1, random);
            insert(db, at(31, 23, 59, 59, 999) + 1, random);
        }
    }

    private static void insert(SQLiteDatabase db, long time, Random random) {
        ContentValues v = new ContentValues();
        v.put(DBAdapter.KEY_LOG_TIME, time);
        v.put("amount", random.nextInt(100000) / 100.0);
        v.put("categoryId", 1 + random.nextInt(3));
        v.put("notes", "");
        v.put("expenseIncome", random.nextInt(2));
        v.put("account", 1);
        v.put("repeatingId", -1);
        db.insert("mainLogs", null, v);
    }

    @Test
    public void everyDaysRecordsAndTotal_matchTheQueriesTheyReplaced() {
        seed();
        DBAdapter adapter = new DBAdapter(context);
        Calendar march = Calendar.getInstance();
        march.clear();
        march.set(2026, Calendar.MARCH, 1);

        for (String weekStart : new String[]{"0", "1", "2"}) {
            prefs.edit().putString(context.getString(de.timowa.expenselog.R.string.pref_key_first_day_of_week), weekStart).commit();
            for (String filter : FILTERS) {
                List<CalendarDay> days = CalendarAdapter.loadMonth(context, march, filter, prefs);
                int seen = 0;
                for (CalendarDay day : days) {
                    if (day.getDay() == 0)
                        continue;
                    seen++;
                    long start = CalendarDay.dayStart(2026, Calendar.MARCH, day.getDay());
                    long end = CalendarDay.dayEnd(2026, Calendar.MARCH, day.getDay());
                    String where = "filter '" + filter + "' day " + day.getDay();
                    try (Cursor c = adapter.getLogsInRange(start, end, DBAdapter.KEY_LOG_TIME, filter)) {
                        assertEquals(where, c.getCount(), day.getNumOfEvents());
                    }
                    assertEquals(where, adapter.getTotalForRange(start, end, filter), day.getTotal(), 1e-6);
                }
                assertEquals(31, seen);
            }
        }
    }
}
