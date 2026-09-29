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

/**
 * Writes a row through {@code DBAdapter}'s own API and reads every field back by the positional
 * constant the app uses, for each of the four tables.
 *
 * <p>{@link DBAdapterSchemaTest} proves the constants point at the right column names.
 * This proves the values survive the trip — that what the app writes under a field is what it
 * reads back out of it. Between them they are the safety net the Step 3 migration needs: a
 * migration that shifts a column breaks the schema test, and one that corrupts a value breaks
 * this one.
 *
 * <p>Deliberately uses distinguishable values per field. Reusing {@code 1} everywhere would let a
 * transposition of two adjacent integer columns pass unnoticed, which is the exact failure this
 * is built to catch.
 */
@RunWith(AndroidJUnit4.class)
public class DBAdapterRoundTripTest {

    private Context context;
    private DBAdapter adapter;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        adapter = new DBAdapter(context);
        adapter.open();
    }

    @After
    public void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    @Test
    public void newLog_roundTripsEveryField() {
        long time = 1_600_000_000_000L;
        int expenseIncome = 1;
        int accountId = 7;
        double amount = 1234.56;
        int categoryId = 42;
        String notes = "round trip, with a comma and \"quotes\"";
        String imageUri = "content://example/image/9";
        int repeatingId = 13;

        assertTrue("insert should report success",
                adapter.newLog(time, expenseIncome, accountId, amount, categoryId, notes,
                        imageUri, repeatingId));

        Cursor c = adapter.getAllLogs();
        try {
            assertEquals("exactly one row expected", 1, c.getCount());
            assertTrue(c.moveToFirst());

            assertEquals(time, c.getLong(DBAdapter.COLUMN_LOG_TIME));
            assertEquals(amount, c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT), 0.0001);
            assertEquals(categoryId, c.getInt(DBAdapter.COLUMN_LOG_CATEGORY));
            assertEquals(notes, c.getString(DBAdapter.COLUMN_LOG_NOTES));
            assertEquals(imageUri, c.getString(DBAdapter.COLUMN_LOG_IMAGE));
            assertEquals(expenseIncome, c.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME));
            assertEquals(accountId, c.getInt(DBAdapter.COLUMN_LOG_ACCOUNT));
            assertEquals(repeatingId, c.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
        } finally {
            c.close();
        }
    }

    /**
     * The four integer columns of a log are the transposition risk: category, expense/income,
     * account, and repeating id are all plain ints, so swapping two of them is invisible to
     * SQLite. Distinct values make a swap fail loudly.
     */
    @Test
    public void newLog_doesNotTransposeAdjacentIntegerColumns() {
        adapter.newLog(1L, 0, 200, 1.0, 100, "n", null, 300);

        Cursor c = adapter.getAllLogs();
        try {
            assertTrue(c.moveToFirst());
            assertEquals("categoryId", 100, c.getInt(DBAdapter.COLUMN_LOG_CATEGORY));
            assertEquals("expenseIncome", 0, c.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME));
            assertEquals("account", 200, c.getInt(DBAdapter.COLUMN_LOG_ACCOUNT));
            assertEquals("repeatingId", 300, c.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
        } finally {
            c.close();
        }
    }

    @Test
    public void newTag_roundTripsEveryField() {
        int tagId = adapter.newTag("Groceries", 0, "fa-shopping-cart");
        assertNotEquals("insert should return a real row id", -1, tagId);

        Cursor c = adapter.getTag(tagId);
        try {
            assertTrue(c.moveToFirst());
            assertEquals(tagId, c.getInt(DBAdapter.COLUMN_TAG_ID));
            assertEquals("Groceries", c.getString(DBAdapter.COLUMN_TAG_LABEL));
            assertEquals("fa-shopping-cart", c.getString(DBAdapter.COLUMN_TAG_ICON));
            assertEquals(0, c.getInt(DBAdapter.COLUMN_TAG_TYPE));
        } finally {
            c.close();
        }

        assertEquals("Groceries", adapter.getCategoryLabel(tagId));
    }

    /**
     * Two tags may share a label as long as their types differ — this is why a category can appear
     * twice in a CSV summary export, which groups by tag id rather than by label.
     */
    @Test
    public void newTag_allowsSameLabelWithDifferentType() {
        int expense = adapter.newTag("Aktien", 0, "icon");
        int income = adapter.newTag("Aktien", 1, "icon");

        assertNotEquals("the two tags must be distinct rows", expense, income);
        assertTrue(adapter.doesTagExist("Aktien", 0));
        assertTrue(adapter.doesTagExist("Aktien", 1));
    }

    @Test
    public void newAccount_roundTripsEveryField() {
        long accountId = adapter.newAccount("Joint Account");
        assertNotEquals(-1, accountId);

        Cursor c = adapter.getAccount((int) accountId);
        try {
            assertTrue(c.moveToFirst());
            assertEquals(accountId, c.getLong(DBAdapter.COLUMN_ACCOUNT_ID));
            assertEquals("Joint Account", c.getString(DBAdapter.COLUMN_ACCOUNT_LABEL));
        } finally {
            c.close();
        }

        assertEquals("Joint Account", adapter.getAccountLabel((int) accountId));
    }

    @Test
    public void newRepeatingRecord_roundTripsEveryField() {
        double amount = 99.95;
        int frequency = 3;
        int period = 2;
        long endTime = 1_700_000_000_000L;

        int repeatingId = adapter.newRepeatingRecord(amount, frequency, period, endTime);
        assertNotEquals(-1, repeatingId);

        Cursor c = adapter.getRepeatingEntry(repeatingId);
        try {
            assertTrue(c.moveToFirst());
            assertEquals(repeatingId, c.getInt(DBAdapter.COLUMN_REPEATING_ID));
            assertEquals(amount, c.getDouble(DBAdapter.COLUMN_REPEATING_AMOUNT), 0.0001);
            assertEquals("period and frequency must not swap",
                    period, c.getInt(DBAdapter.COLUMN_REPEATING_PERIOD));
            assertEquals("period and frequency must not swap",
                    frequency, c.getInt(DBAdapter.COLUMN_REPEATING_PERIOD_FREQUENCY));
            assertEquals(endTime, c.getLong(DBAdapter.COLUMN_REPEATING_END_TIME));
        } finally {
            c.close();
        }
    }

    @Test
    public void deleteLog_removesTheRow() {
        adapter.newLog(1L, 0, 1, 5.0, 1, "doomed", null, 0);

        Cursor before = adapter.getAllLogs();
        int logId;
        try {
            assertTrue(before.moveToFirst());
            logId = before.getInt(DBAdapter.COLUMN_LOG_ID);
        } finally {
            before.close();
        }

        assertTrue(adapter.deleteLog(logId));
        assertEquals(0, adapter.getTotalNumberOfLogs());
    }

    /**
     * Locks the sign convention, which is not obvious from the schema and is easy to get backwards.
     *
     * <p>{@code amount} is stored as a <b>positive magnitude</b>; the {@code expenseIncome} flag
     * carries the sign (0 = expense, 1 = income). Nothing in the column definition enforces this —
     * {@code amount} is a plain {@code real} and would happily hold a negative number — so the
     * convention lives only in the callers. {@code getTotalForRange} then returns
     * {@code income - expenses}, and {@code SpreadsheetHelper.appendSummary} multiplies the expense
     * sum by -1 for display. Writing a negative amount against an expense flag would double-negate
     * and silently corrupt every total that touches the row.
     */
    @Test
    public void getTotalForRange_appliesSignFromTheExpenseIncomeFlag() {
        adapter.newLog(1_000L, 0, 1, 10.0, 1, "expense, in range", null, 0);
        adapter.newLog(1_500L, 0, 1, 5.0, 1, "expense, in range", null, 0);
        adapter.newLog(2_000L, 1, 1, 30.0, 1, "income, in range", null, 0);
        adapter.newLog(9_000L, 0, 1, 99.0, 1, "expense, out of range", null, 0);

        // income - expenses, over the range only: 30 - (10 + 5)
        assertEquals(15.0, adapter.getTotalForRange(1_000L, 2_000L, null), 0.0001);
    }

    /** Expenses alone net negative, and the out-of-range row stays out. */
    @Test
    public void getTotalForRange_excludesRowsOutsideTheRange() {
        adapter.newLog(1_000L, 0, 1, 10.0, 1, "in range", null, 0);
        adapter.newLog(9_000L, 0, 1, 99.0, 1, "out of range", null, 0);

        assertEquals(-10.0, adapter.getTotalForRange(1_000L, 2_000L, null), 0.0001);
    }
}
