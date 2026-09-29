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

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Moving a series' end from the record editor: what an earlier end deletes is handed back for
 * an Undo that puts the records and the old end back -- re-creating the series if the end
 * emptied it -- and {@link DBAdapter#occurrencesAddedBy}, which the save dialog shows, counts
 * exactly what a later end adds.
 */
@RunWith(AndroidJUnit4.class)
public class SeriesEndUndoTest {

    private static final int MONTH = 2;

    private Context context;
    private DBAdapter adapter;
    private TimeZone savedZone;
    /** Real rows: a restore skips a record whose category or account does not exist. */
    private int category;
    private int account;

    @Before
    public void setUp() {
        savedZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        adapter = new DBAdapter(context);
        adapter.open();
        category = adapter.newTag("Rent", 0, "fa-home");
        account = (int) adapter.newAccount("Personal");
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        TimeZone.setDefault(savedZone);
    }

    private static long at(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 10, 0, 0);
        return c.getTimeInMillis();
    }

    /** Monthly on the 15th, Jan to Dec 2026: 12 records. */
    private int monthlySeries() {
        long first = at(2026, 1, 15);
        int id = adapter.createRepeatingSeries(first, at(2026, 12, 31), 100, 1, MONTH, 1, account, category, "rent", "");
        assertNotEquals(-1, id);
        assertTrue(adapter.newLog(first, 1, account, 100, category, "rent", "", id));
        assertEquals(12, adapter.getRepeatingSeries(id).size());
        return id;
    }

    private int recordsOf(int id) {
        try (Cursor c = adapter.getLogs("repeatingId = " + id, DBAdapter.KEY_LOG_TIME)) {
            return c.getCount();
        }
    }

    @Test
    public void anEarlierEnd_handsBackWhatItDeleted_andUndoRestoresRecordsAndEnd() {
        int id = monthlySeries();
        long oldEnd = adapter.getRepeatingSeries(id).endTime;
        DBAdapter.DeletedLogs[] removed = new DBAdapter.DeletedLogs[1];

        assertTrue(adapter.setRepeatingEnd(id, at(2026, 7, 1), d -> removed[0] = d) >= 0);
        assertNotNull(removed[0]);
        assertEquals(6, removed[0].size());
        assertEquals(6, adapter.getRepeatingSeries(id).size());

        assertEquals(6, adapter.restoreLogs(removed[0]));
        RepeatingSeries back = adapter.getRepeatingSeries(id);
        assertEquals(12, back.size());
        assertEquals(oldEnd, back.endTime);
        assertEquals(12, recordsOf(id));
    }

    @Test
    public void anEndBeforeEveryRecord_dropsTheSeries_andUndoRecreatesIt() {
        int id = monthlySeries();
        DBAdapter.DeletedLogs[] removed = new DBAdapter.DeletedLogs[1];

        assertTrue(adapter.setRepeatingEnd(id, at(2026, 1, 1), d -> removed[0] = d) >= 0);
        assertEquals(12, removed[0].size());
        assertNull(adapter.getRepeatingSeries(id));

        assertEquals(12, adapter.restoreLogs(removed[0]));
        assertEquals(12, adapter.getRepeatingSeries(id).size());
        assertEquals(12, recordsOf(id));
    }

    @Test
    public void aLaterEnd_deletesNothing_andAddsWhatTheDialogSaid() {
        int id = monthlySeries();
        long newEnd = at(2027, 6, 30);
        int promised = DBAdapter.occurrencesAddedBy(adapter.getRepeatingSeries(id), newEnd);
        assertEquals(6, promised);
        DBAdapter.DeletedLogs[] removed = new DBAdapter.DeletedLogs[1];

        assertTrue(adapter.setRepeatingEnd(id, newEnd, d -> removed[0] = d) >= 0);
        assertNull(removed[0]);
        assertEquals(12 + promised, adapter.getRepeatingSeries(id).size());
    }

    @Test
    public void newValuesWithAnEarlierEnd_undoBringsBackTheRecordsButKeepsTheValues() {
        int id = monthlySeries();
        LogItem raised = new LogItem();
        raised.setAmount(120);
        raised.setTimeStamp(at(2026, 1, 15));
        raised.setAccountId(account);
        raised.setCategory(category);
        raised.setNotes("rent");
        raised.setImageUri("");
        raised.setExpenseIncome(1);
        DBAdapter.DeletedLogs[] removed = new DBAdapter.DeletedLogs[1];

        assertEquals(id, adapter.updateRepeatingFrom(id, Long.MIN_VALUE, raised, at(2026, 10, 1),
                d -> removed[0] = d));
        assertEquals(3, removed[0].size());

        assertEquals(3, adapter.restoreLogs(removed[0]));
        assertEquals(12, adapter.getRepeatingSeries(id).size());
        try (Cursor c = adapter.getLogs("repeatingId = " + id, DBAdapter.KEY_LOG_TIME)) {
            while (c.moveToNext())
                assertEquals(120, c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT), 0);
        }
    }

    @Test
    public void undoAfterTheSeriesChangedAgain_bringsTheRecordsBackDetached() {
        int id = monthlySeries();
        DBAdapter.DeletedLogs[] removed = new DBAdapter.DeletedLogs[1];
        assertTrue(adapter.setRepeatingEnd(id, at(2026, 7, 1), d -> removed[0] = d) >= 0);
        assertTrue(adapter.setRepeatingEnd(id, at(2026, 5, 1)) >= 0);

        assertEquals(6, adapter.restoreLogs(removed[0]));
        assertEquals(4, adapter.getRepeatingSeries(id).size());
        try (Cursor c = adapter.getLogs("repeatingId = -1", DBAdapter.KEY_LOG_TIME)) {
            assertEquals(6, c.getCount());
        }
    }

    @Test
    public void anEndTooFarOut_isTooLongInTheDialogAndInTheSave() {
        long first = at(2026, 1, 1);
        int id = adapter.createRepeatingSeries(first, at(2026, 1, 11), 5, 1, 0, 1, account, category, "daily", "");
        assertTrue(adapter.newLog(first, 1, account, 5, category, "daily", "", id));
        // 60 years of days is more than the ceiling, however the count is taken
        long newEnd = at(2086, 1, 1);
        assertTrue(DBAdapter.occurrencesAddedBy(adapter.getRepeatingSeries(id), newEnd)
                > DBAdapter.MAX_REPEATING_OCCURRENCES);
        assertEquals(DBAdapter.SERIES_TOO_LONG, adapter.setRepeatingEnd(id, newEnd));
        assertEquals(10, adapter.getRepeatingSeries(id).size());
    }

    @Test
    public void withoutAnUndo_anEarlierEndStillDeletes() {
        int id = monthlySeries();
        assertTrue(adapter.setRepeatingEnd(id, at(2026, 7, 1)) >= 0);
        assertEquals(6, adapter.getRepeatingSeries(id).size());
    }

    @Test
    public void aSeriesOffItsSchedule_cannotBeCounted_andIsRefusedByTheSaveToo() {
        int id = monthlySeries();
        // one record a day off, as the old stepping bug would leave it
        RepeatingSeries series = adapter.getRepeatingSeries(id);
        adapter.updateLog(series.logIds[2], series.times[2] + 24 * 3600_000L, 1, account, 100, category,
                "rent", "", id);
        series = adapter.getRepeatingSeries(id);
        assertNull(series.findOrigin());
        // -1: the editor refuses before its dialog, as the save below would after it
        assertEquals(-1, DBAdapter.occurrencesAddedBy(series, at(2027, 6, 30)));
        assertEquals(DBAdapter.SERIES_NO_SCHEDULE, adapter.setRepeatingEnd(id, at(2027, 6, 30)));
    }

    @Test
    public void theCount_matchesWhatALongExtensionReallyAdds() {
        long first = at(2026, 1, 1);
        int id = adapter.createRepeatingSeries(first, at(2026, 1, 11), 5, 1, 0, 1, account, category, "daily", "");
        assertTrue(adapter.newLog(first, 1, account, 5, category, "daily", "", id));
        // two days deleted before the old end stay deleted: counted from the old end on
        RepeatingSeries series = adapter.getRepeatingSeries(id);
        adapter.deleteLog(series.logIds[9]);
        adapter.deleteLog(series.logIds[8]);
        series = adapter.getRepeatingSeries(id);
        long newEnd = at(2027, 3, 1);
        int promised = DBAdapter.occurrencesAddedBy(series, newEnd);
        int before = series.size();
        assertTrue(adapter.setRepeatingEnd(id, newEnd) >= 0);
        assertEquals(before + promised, adapter.getRepeatingSeries(id).size());
    }

    @Test
    public void thePartFromAnEntryOn_isCountedAsTheSplitWillBe() {
        int id = monthlySeries();
        RepeatingSeries series = adapter.getRepeatingSeries(id);
        RepeatingSeries part = series.from(series.times[4]);
        assertEquals(8, part.size());
        assertEquals(series.endTime, part.endTime);
        long newEnd = at(2027, 6, 30);
        assertEquals(DBAdapter.occurrencesAddedBy(series, newEnd), DBAdapter.occurrencesAddedBy(part, newEnd));
    }
}
