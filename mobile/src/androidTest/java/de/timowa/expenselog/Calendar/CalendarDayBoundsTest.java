package de.timowa.expenselog.Calendar;

import static org.junit.Assert.assertEquals;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.TimeZone;

import de.timowa.expenselog.DBAdapter;
import de.timowa.expenselog.IsolatedDatabaseContext;

/**
 * A calendar day holds every record saved during it, including in its first and last second.
 *
 * <p>The day's bounds used to clear hours, minutes and seconds but not the millisecond, so which of
 * a day's edge records it showed depended on the millisecond the wall clock was on when the month
 * was drawn. Found on the emulator against a real database: 1 May 2022 showed 4 records instead of
 * 5, missing one saved at 00:00:00.216. docs/history/RELIABILITY_PLAN.md, Step 2.
 *
 * <p>Repeats the load across the clock's milliseconds, because the old behaviour was only wrong for
 * some of them.
 */
@RunWith(AndroidJUnit4.class)
public class CalendarDayBoundsTest {

    private Context context;
    private DBAdapter adapter;
    private TimeZone savedZone;

    @Before
    public void setUp() {
        savedZone = TimeZone.getDefault();
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
        TimeZone.setDefault(savedZone);
    }

    private static long at(int hour, int minute, int second, int millis) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2022, Calendar.MAY, 1, hour, minute, second);
        c.set(Calendar.MILLISECOND, millis);
        return c.getTimeInMillis();
    }

    private void insert(SQLiteDatabase db, long time) {
        ContentValues v = new ContentValues();
        v.put(DBAdapter.KEY_LOG_TIME, time);
        v.put("amount", 1.0);
        v.put("categoryId", 1);
        v.put("notes", "");
        v.put("expenseIncome", 0);
        v.put("account", 1);
        v.put("repeatingId", -1);
        db.insert("mainLogs", null, v);
    }

    @Test
    public void aDayHoldsTheRecordsInItsFirstAndLastMillisecondsWhateverTheClockSays()
            throws InterruptedException {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                context.getDatabasePath(DBAdapter.DATABASE_NAME).getPath(), null,
                SQLiteDatabase.OPEN_READWRITE)) {
            insert(db, at(0, 0, 0, 0));
            insert(db, at(0, 0, 0, 216));
            insert(db, at(12, 0, 0, 0));
            insert(db, at(23, 59, 59, 900));
            insert(db, at(23, 59, 59, 999));
            // Neighbours that must stay out.
            insert(db, at(0, 0, 0, 0) - 1);
            insert(db, at(23, 59, 59, 999) + 1);
        }

        for (int i = 0; i < 60; i++) {
            CalendarDay day = new CalendarDay(context, 1, 2022, Calendar.MAY, "");
            day.noAsync(adapter);
            assertEquals("iteration " + i + " at clock millisecond "
                    + (System.currentTimeMillis() % 1000), 5, day.getNumOfEvents());
            Thread.sleep(17);
        }
    }
}
