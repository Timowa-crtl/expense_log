package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * Locks the contract between {@code DBAdapter}'s positional {@code COLUMN_*} constants and the
 * real column order of the {@code CREATE TABLE} statements.
 *
 * <p><b>Why this test exists.</b> Every read in the app is positional — callers do
 * {@code cursor.getString(DBAdapter.COLUMN_LOG_NOTES)}, never {@code getColumnIndex("notes")} —
 * and every cursor-returning method queries with a {@code null} projection, so cursor order is
 * table order. Inserting or reordering a column in a {@code CREATE TABLE} string therefore does
 * not fail, throw, or warn: it silently shifts every subsequent field, and the app reads an amount
 * where it expects a category id. Reading an existing {@code .edb} would return quiet nonsense.
 *
 * <p>These assertions do not need any data. An empty cursor still carries column metadata, so this
 * is a pure schema check and is the guard that makes the Step 3 migration safe to attempt.
 */
@RunWith(AndroidJUnit4.class)
public class DBAdapterSchemaTest {

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

    /**
     * Proves the harness works before anything relies on it. If this fails, every other test in
     * this class may be running against the live database.
     */
    @Test
    public void harness_redirectsAwayFromTheLiveDatabase() {
        String livePath = ApplicationProvider.<Context>getApplicationContext()
                .getDatabasePath(DBAdapter.DATABASE_NAME).getAbsolutePath();
        String testPath = context.getDatabasePath(DBAdapter.DATABASE_NAME).getAbsolutePath();

        assertTrue("test database name must carry the isolation prefix",
                testPath.contains(IsolatedDatabaseContext.PREFIX + DBAdapter.DATABASE_NAME));
        assertFalse("the two paths must not be the same file", livePath.equals(testPath));
    }

    @Test
    public void mainLogsTable_columnOrderMatchesConstants() {
        Cursor c = adapter.getAllLogs();
        try {
            assertEquals("_id", DBAdapter.COLUMN_LOG_ID, c.getColumnIndex("_id"));
            assertEquals("start_time", DBAdapter.COLUMN_LOG_TIME, c.getColumnIndex("start_time"));
            assertEquals("amount", DBAdapter.COLUMN_LOG_AMOUNT, c.getColumnIndex("amount"));
            assertEquals("categoryId", DBAdapter.COLUMN_LOG_CATEGORY, c.getColumnIndex("categoryId"));
            assertEquals("notes", DBAdapter.COLUMN_LOG_NOTES, c.getColumnIndex("notes"));
            assertEquals("picture", DBAdapter.COLUMN_LOG_IMAGE, c.getColumnIndex("picture"));
            assertEquals("expenseIncome", DBAdapter.COLUMN_LOG_EXPENSE_INCOME,
                    c.getColumnIndex("expenseIncome"));
            assertEquals("account", DBAdapter.COLUMN_LOG_ACCOUNT, c.getColumnIndex("account"));
            assertEquals("repeatingId", DBAdapter.COLUMN_LOG_REPEATING_ID,
                    c.getColumnIndex("repeatingId"));
            assertEquals("mainLogs column count", 9, c.getColumnCount());
        } finally {
            c.close();
        }
    }

    @Test
    public void tagTypesTable_columnOrderMatchesConstants() {
        Cursor c = adapter.getTags();
        try {
            assertEquals("_id", DBAdapter.COLUMN_TAG_ID, c.getColumnIndex("_id"));
            assertEquals("tag_label", DBAdapter.COLUMN_TAG_LABEL, c.getColumnIndex("tag_label"));
            assertEquals("tag_icon", DBAdapter.COLUMN_TAG_ICON, c.getColumnIndex("tag_icon"));
            assertEquals("tag_type", DBAdapter.COLUMN_TAG_TYPE, c.getColumnIndex("tag_type"));
            assertEquals("tagTypes column count", 4, c.getColumnCount());
        } finally {
            c.close();
        }
    }

    @Test
    public void accountsTable_columnOrderMatchesConstants() {
        Cursor c = adapter.getAccounts();
        try {
            assertEquals("_id", DBAdapter.COLUMN_ACCOUNT_ID, c.getColumnIndex("_id"));
            assertEquals("account_label", DBAdapter.COLUMN_ACCOUNT_LABEL,
                    c.getColumnIndex("account_label"));
            assertEquals("AccountsTable column count", 2, c.getColumnCount());
        } finally {
            c.close();
        }
    }

    @Test
    public void repeatingTable_columnOrderMatchesConstants() {
        Cursor c = adapter.getRepeatingEntry(1);
        try {
            assertEquals("_id", DBAdapter.COLUMN_REPEATING_ID, c.getColumnIndex("_id"));
            assertEquals("repeating_amount", DBAdapter.COLUMN_REPEATING_AMOUNT,
                    c.getColumnIndex("repeating_amount"));
            assertEquals("repeating_period", DBAdapter.COLUMN_REPEATING_PERIOD,
                    c.getColumnIndex("repeating_period"));
            assertEquals("repeating_period_frequency", DBAdapter.COLUMN_REPEATING_PERIOD_FREQUENCY,
                    c.getColumnIndex("repeating_period_frequency"));
            assertEquals("repeating_end_time", DBAdapter.COLUMN_REPEATING_END_TIME,
                    c.getColumnIndex("repeating_end_time"));
            assertEquals("RepeatingTable column count", 5, c.getColumnCount());
        } finally {
            c.close();
        }
    }

    /**
     * A tripwire, not a rule. {@code DATABASE_VERSION} is 1 with an effectively empty
     * {@code onUpgrade}, and every {@code .edb} in existence is version 1. Raising the version
     * without writing the migration first would drop the user through that empty method. When
     * Step 3 raises it, update this number in the same commit as the migration and its tests.
     */
    @Test
    public void databaseVersion_isStillOneAndHasNoMigrationYet() {
        assertEquals("DATABASE_VERSION changed -- was the migration written?",
                1, adapter.getDB().getVersion());
    }

    /** {@code verifyDatabase()} is what gates an {@code .edb} import; a fresh schema must pass. */
    @Test
    public void freshDatabase_passesVerification() {
        assertTrue(adapter.verifyDatabase());
    }
}
