package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Deleting several records from the records list, and Undo putting them back exactly: same ids,
 * same columns, repeating entries still in their series. Also the selection's total.
 */
@RunWith(AndroidJUnit4.class)
public class RecordDeleteUndoTest {

    private static final long JAN_2026 = 1_767_225_600_000L; // 2026-01-01 00:00 UTC
    private static final long DAY_MS = 86_400_000L;
    private static final int DAY = 0;

    private Context context;
    private DBAdapter adapter;
    private int category;
    private int account;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        adapter = new DBAdapter(context);
        adapter.open();
        category = adapter.newTag("Groceries", 0, "fa-shopping-cart");
        account = (int) adapter.newAccount("Personal");
    }

    @After
    public void tearDown() {
        adapter.close();
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    /** Every record, every column, in id order -- what a restore must reproduce. */
    private List<List<Object>> allRows() {
        List<List<Object>> rows = new ArrayList<>();
        try (Cursor c = adapter.getLogs(null, DBAdapter.KEY_ROW_ID)) {
            while (c.moveToNext()) {
                List<Object> row = new ArrayList<>();
                for (int i = 0; i < c.getColumnCount(); i++) {
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL:
                            row.add(null);
                            break;
                        case Cursor.FIELD_TYPE_INTEGER:
                            row.add(c.getLong(i));
                            break;
                        case Cursor.FIELD_TYPE_FLOAT:
                            row.add(c.getDouble(i));
                            break;
                        default:
                            row.add(c.getString(i));
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<Integer> ids() {
        List<Integer> ids = new ArrayList<>();
        for (List<Object> row : allRows())
            ids.add(((Long) row.get(DBAdapter.COLUMN_LOG_ID)).intValue());
        return ids;
    }

    private int seriesOfFive() {
        int series = adapter.createRepeatingSeries(JAN_2026, JAN_2026 + 5 * DAY_MS, 9.99, 1, DAY,
                0, account, category, "coffee", "");
        assertNotEquals(-1, series);
        // The series is written after its first record, which the caller saves itself.
        assertTrue(adapter.newLog(JAN_2026, 0, account, 9.99, category, "coffee", "", series));
        return series;
    }

    private void addPlainRecords() {
        // An amount whose text form rounds, a null note and an old image path among them.
        assertTrue(adapter.newLog(JAN_2026 + 1, 0, account, 0.1 + 0.2, category, "bread", null, -1));
        assertTrue(adapter.newLog(JAN_2026 + 2, 1, account, 2500.0, category, null, "/old/picture.jpg", -1));
        assertTrue(adapter.newLog(JAN_2026 + 3, 0, account, 12.345678901234567, category, "", "", -1));
    }

    @Test
    public void deleteLogs_removesExactlyTheSelectedRecords_andLeavesTheirSeries() {
        int series = seriesOfFive();
        addPlainRecords();
        List<Integer> before = ids();
        assertEquals(8, before.size());

        List<Integer> chosen = Arrays.asList(before.get(1), before.get(5), before.get(7));
        DBAdapter.DeletedLogs deleted = adapter.deleteLogs(chosen);

        assertEquals(3, deleted.size());
        List<Integer> expected = new ArrayList<>(before);
        expected.removeAll(chosen);
        assertEquals(expected, ids());
        try (Cursor entry = adapter.getRepeatingEntry(series)) {
            assertEquals("the series itself is not deleted", 1, entry.getCount());
        }
    }

    @Test
    public void restoreLogs_putsBackEveryColumn_underTheSameIds() {
        seriesOfFive();
        addPlainRecords();
        List<List<Object>> before = allRows();
        List<Integer> all = ids();

        DBAdapter.DeletedLogs deleted = adapter.deleteLogs(Arrays.asList(all.get(0), all.get(2), all.get(5),
                all.get(6), all.get(7)));
        assertEquals(3, allRows().size());

        assertEquals(5, adapter.restoreLogs(deleted));
        assertEquals(before, allRows());
    }

    @Test
    public void restoreLogs_afterNewRecords_keepsBoth() {
        addPlainRecords();
        List<Integer> all = ids();
        DBAdapter.DeletedLogs deleted = adapter.deleteLogs(Collections.singletonList(all.get(2)));

        // A record saved while Undo is still on offer must not take the deleted one's id.
        assertTrue(adapter.newLog(JAN_2026 + 9, 0, account, 1.0, category, "new", "", -1));
        assertEquals(1, adapter.restoreLogs(deleted));

        List<Integer> after = ids();
        assertEquals(4, after.size());
        assertTrue(after.contains(all.get(2)));
    }

    @Test
    public void restoreLogs_skipsRecordsWhoseCategoryOrAccountIsGone_andDetachesFromAGoneSeries() {
        int series = seriesOfFive();
        int otherCategory = adapter.newTag("Fuel", 0, "fa-car");
        int otherAccount = (int) adapter.newAccount("Joint");
        assertTrue(adapter.newLog(JAN_2026 + 1, 0, account, 5.0, otherCategory, "fuel", "", -1));
        assertTrue(adapter.newLog(JAN_2026 + 2, 0, otherAccount, 6.0, category, "joint", "", -1));

        List<Integer> all = ids();
        DBAdapter.DeletedLogs deleted = adapter.deleteLogs(all);
        assertEquals(7, deleted.size());

        adapter.deleteTag(otherCategory);
        adapter.deleteAccount(otherAccount);
        adapter.deleteRepeatingLogs(series);

        assertEquals("the two orphans stay deleted", 5, adapter.restoreLogs(deleted));
        try (Cursor c = adapter.getLogs(null, DBAdapter.KEY_ROW_ID)) {
            assertEquals(5, c.getCount());
            while (c.moveToNext())
                assertEquals("restored as plain records", -1, c.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
        }
    }

    @Test
    public void restoreLogs_refusesAfterTheDatabaseFileWasReplaced() {
        addPlainRecords();
        DBAdapter.DeletedLogs deleted = adapter.deleteLogs(ids());
        assertEquals(0, ids().size());

        // What an import or a Dropbox download goes through: the records belong to another file.
        DBAdapter.closeSharedConnection(context);
        long generation = DBAdapter.changeGeneration();

        assertEquals(-1, adapter.restoreLogs(deleted));
        assertEquals(0, ids().size());
        assertEquals("a refused restore is not a change", generation, DBAdapter.changeGeneration());
    }

    @Test
    public void deleteAndRestore_countAsChanges() {
        addPlainRecords();
        long before = DBAdapter.changeGeneration();
        DBAdapter.DeletedLogs deleted = adapter.deleteLogs(ids());
        long afterDelete = DBAdapter.changeGeneration();
        assertTrue(afterDelete > before);
        adapter.restoreLogs(deleted);
        assertTrue(DBAdapter.changeGeneration() > afterDelete);
    }

    @Test
    public void deleteLogs_withNothingSelected_deletesNothing() {
        addPlainRecords();
        assertEquals(0, adapter.deleteLogs(Collections.emptyList()).size());
        assertEquals(3, ids().size());
    }

    private static LogItem record(int id, double amount, int expenseIncome) {
        LogItem item = new LogItem();
        item.setId(id);
        item.setAmount(amount);
        item.setExpenseIncome(expenseIncome);
        item.setRepeatingId(-1);
        return item;
    }

    private static LogItem repeating(int id, double amount, int series) {
        LogItem item = record(id, amount, 0);
        item.setRepeatingId(series);
        return item;
    }

    /** The series' entry, every column. */
    private List<Object> seriesEntry(int series) {
        try (Cursor c = adapter.getRepeatingEntry(series)) {
            if (!c.moveToFirst())
                return null;
            List<Object> row = new ArrayList<>();
            for (int i = 0; i < c.getColumnCount(); i++)
                row.add(c.getType(i) == Cursor.FIELD_TYPE_FLOAT ? (Object) c.getDouble(i) : c.getString(i));
            return row;
        }
    }

    @Test
    public void undo_thisAndFollowing_restoresTheRecordsAndTheSeriesEnd() {
        int series = seriesOfFive();
        addPlainRecords();
        List<List<Object>> before = allRows();
        List<Object> entryBefore = seriesEntry(series);

        DBAdapter.DeletedLogs deleted = adapter.deleteSeriesRecords(series, JAN_2026 + 2 * DAY_MS);
        assertEquals("the 3rd, 4th and 5th entries", 3, deleted.size());
        assertNotEquals("the series was cut", entryBefore, seriesEntry(series));
        assertEquals(2, adapter.getRepeatingSeries(series).size());

        assertEquals(3, adapter.restoreLogs(deleted));
        assertEquals(before, allRows());
        assertEquals(entryBefore, seriesEntry(series));
        assertEquals(5, adapter.getRepeatingSeries(series).size());
    }

    @Test
    public void undo_thisAndFollowingFromTheFirst_bringsBackTheDroppedSeries() {
        int series = seriesOfFive();
        List<List<Object>> before = allRows();
        List<Object> entryBefore = seriesEntry(series);

        DBAdapter.DeletedLogs deleted = adapter.deleteSeriesRecords(series, JAN_2026);
        assertEquals(5, deleted.size());
        assertEquals("nothing left, so the series is dropped", null, seriesEntry(series));

        assertEquals(5, adapter.restoreLogs(deleted));
        assertEquals(before, allRows());
        assertEquals(entryBefore, seriesEntry(series));
    }

    @Test
    public void undo_wholeSeries_restoresTheRecordsAndTheSeries() {
        int series = seriesOfFive();
        addPlainRecords();
        List<List<Object>> before = allRows();
        List<Object> entryBefore = seriesEntry(series);

        DBAdapter.DeletedLogs deleted = adapter.deleteSeriesRecords(series, Long.MIN_VALUE);
        assertEquals(5, deleted.size());
        assertEquals(null, seriesEntry(series));
        assertEquals("plain records untouched", 3, ids().size());

        assertEquals(5, adapter.restoreLogs(deleted));
        assertEquals(before, allRows());
        assertEquals(entryBefore, seriesEntry(series));
    }

    @Test
    public void undo_ofACut_afterTheRestOfTheSeriesWasDeleted_bringsTheRecordsBackPlain() {
        int series = seriesOfFive();
        DBAdapter.DeletedLogs cut = adapter.deleteSeriesRecords(series, JAN_2026 + 3 * DAY_MS);
        assertEquals(2, cut.size());
        adapter.deleteRepeatingLogs(series);

        assertEquals(2, adapter.restoreLogs(cut));
        assertEquals("the series stays deleted", null, seriesEntry(series));
        try (Cursor c = adapter.getLogs(null, DBAdapter.KEY_ROW_ID)) {
            assertEquals(2, c.getCount());
            while (c.moveToNext())
                assertEquals(-1, c.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
        }
    }

    @Test
    public void undo_ofACut_afterTheSeriesEndChanged_keepsTheChange_andBringsTheRecordsBackPlain() {
        int series = seriesOfFive();
        DBAdapter.DeletedLogs cut = adapter.deleteSeriesRecords(series, JAN_2026 + 3 * DAY_MS);
        assertEquals(2, cut.size());
        // Someone moves the end of what is left before tapping Undo.
        long newEnd = JAN_2026 + 2 * DAY_MS + DAY_MS / 2;
        assertTrue(adapter.setRepeatingEnd(series, newEnd) >= 0);
        List<Object> changed = seriesEntry(series);

        assertEquals(2, adapter.restoreLogs(cut));
        assertEquals("the new end stands", changed, seriesEntry(series));
        assertEquals("the kept entries are still the series", 3, adapter.getRepeatingSeries(series).size());
        int plain = 0;
        try (Cursor c = adapter.getLogs(null, DBAdapter.KEY_ROW_ID)) {
            while (c.moveToNext())
                if (c.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID) == -1)
                    plain++;
        }
        assertEquals("the restored entries come back outside the series", 2, plain);
    }

    @Test
    public void undo_ofOneEntry_afterTheSeriesAmountChanged_bringsItBackPlain() {
        int series = seriesOfFive();
        List<Integer> all = ids();
        DBAdapter.DeletedLogs one = adapter.deleteLogs(Collections.singletonList(all.get(4)));

        LogItem raise = new LogItem();
        raise.setAmount(12.0);
        raise.setCategory(category);
        raise.setAccountId(account);
        raise.setNotes("coffee");
        raise.setImageUri("");
        raise.setExpenseIncome(0);
        RepeatingSeries before = adapter.getRepeatingSeries(series);
        assertEquals(series, adapter.updateRepeatingFrom(series, Long.MIN_VALUE, raise, before.endTime));

        assertEquals(1, adapter.restoreLogs(one));
        try (Cursor c = adapter.getLogs(DBAdapter.KEY_ROW_ID + " = " + all.get(4), null)) {
            assertTrue(c.moveToFirst());
            assertEquals(-1, c.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
            assertEquals("its own values, as deleted", 9.99, c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT), 1e-9);
        }
        assertEquals(4, adapter.getRepeatingSeries(series).size());
    }

    @Test
    public void undo_ofOneEntry_withTheSeriesUnchanged_putsItBackInTheSeries() {
        int series = seriesOfFive();
        List<Integer> all = ids();
        DBAdapter.DeletedLogs one = adapter.deleteLogs(Collections.singletonList(all.get(2)));
        // deleting another entry of the same series does not change the series itself
        adapter.deleteLogs(Collections.singletonList(all.get(3)));

        assertEquals(1, adapter.restoreLogs(one));
        assertEquals(4, adapter.getRepeatingSeries(series).size());
    }

    @Test
    public void deleteSeriesRecords_cutOfAMissingSeries_writesNothing() {
        addPlainRecords();
        long generation = DBAdapter.changeGeneration();
        assertEquals(null, adapter.deleteSeriesRecords(999, JAN_2026));
        assertEquals(-1, adapter.deleteRepeatingFrom(999, JAN_2026));
        assertEquals(3, ids().size());
        assertEquals(generation, DBAdapter.changeGeneration());
    }

    @Test
    public void selection_selectAllLeavesRepeatingEntriesOut() {
        List<LogItem> records = Arrays.asList(record(1, 1, 0), repeating(2, 5, 7), record(3, 2, 1),
                repeating(4, 5, 7));
        RecordSelection selection = new RecordSelection();
        assertEquals(2, selection.selectAllPlain(records));
        assertEquals(Arrays.asList(1, 3), selection.ids());
        assertFalse(selection.holdsRepeating(records));

        RecordSelection solo = new RecordSelection();
        solo.toggle(4);
        assertTrue(solo.holdsRepeating(records));
        assertEquals(4, solo.selected(records).get(0).getId());
        assertFalse(solo.holdsRepeating(null));
    }

    @Test
    public void selection_totalIsIncomeMinusExpenses_ofTheSelectedRecordsOnly() {
        List<LogItem> records = Arrays.asList(record(1, 10.0, 0), record(2, 2.5, 0),
                record(3, 100.0, 1), record(4, 7.0, 0));
        RecordSelection selection = new RecordSelection();
        assertFalse(selection.isActive());

        selection.toggle(1);
        selection.toggle(2);
        assertTrue(selection.isActive());
        assertEquals(-12.5, selection.total(records), 1e-9);

        selection.toggle(3);
        assertEquals(87.5, selection.total(records), 1e-9);

        selection.toggle(1);
        assertEquals(2, selection.size());
        assertEquals(97.5, selection.total(records), 1e-9);

        selection.toggle(4);
        assertEquals(3, selection.size());
        assertEquals(90.5, selection.total(records), 1e-9);
    }

    @Test
    public void selection_keepsOnlyRecordsStillInTheList() {
        RecordSelection selection = new RecordSelection();
        selection.addAll(new int[]{1, 2, 3});
        selection.retainAll(Arrays.asList(record(2, 1, 0), record(5, 1, 0)));
        assertEquals(Collections.singletonList(2), selection.ids());
        assertEquals(1, selection.toArray().length);

        selection.toggle(2);
        assertFalse(selection.isActive());
    }
}
