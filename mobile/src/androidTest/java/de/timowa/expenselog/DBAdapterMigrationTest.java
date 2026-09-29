package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;

/**
 * Covers the migration scaffolding added in Step 3: the version dispatch, the downgrade refusal,
 * and the fact that a version-1 database — which is every {@code .edb} in existence — still opens
 * and reads back unchanged.
 *
 * <p>These tests exist because {@code onUpgrade} was previously empty. Raising
 * {@code DATABASE_VERSION} would then have left the old schema in place while the app believed it
 * had the new one, and positional reads would have returned the wrong column from every existing
 * file. The scaffolding converts that silent corruption into a loud failure; these tests hold it
 * to that.
 */
@RunWith(AndroidJUnit4.class)
public class DBAdapterMigrationTest {

    private Context context;
    private DBAdapter adapter;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    @After
    public void tearDown() {
        if (adapter != null) {
            adapter.close();
            adapter = null;
        }
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    /** A step with no registered migration must throw rather than quietly do nothing. */
    @Test
    public void migrate_throwsForAnUnregisteredStep() {
        adapter = new DBAdapter(context);
        adapter.open();
        SQLiteDatabase db = adapter.getDB();

        try {
            DBAdapter.migrate(db, 1, 2);
            fail("migrate should refuse a version step that has no registered migration");
        } catch (IllegalStateException expected) {
            assertTrue("message should name the version step, was: " + expected.getMessage(),
                    expected.getMessage().contains("v1") && expected.getMessage().contains("v2"));
        }
    }

    /** Chained steps fail at the first unregistered one, not silently partway through. */
    @Test
    public void migrate_throwsOnTheFirstMissingStepOfAChain() {
        adapter = new DBAdapter(context);
        adapter.open();

        try {
            DBAdapter.migrate(adapter.getDB(), 1, 4);
            fail("migrate should refuse a multi-version chain with no registered migrations");
        } catch (IllegalStateException expected) {
            assertTrue("should fail at the 1 -> 2 step first, was: " + expected.getMessage(),
                    expected.getMessage().contains("v1 -> v2"));
        }
    }

    /** Opening at the current version must not run any migration. */
    @Test
    public void migrate_isANoOpWhenVersionsMatch() {
        adapter = new DBAdapter(context);
        adapter.open();

        DBAdapter.migrate(adapter.getDB(), 1, 1);  // must not throw

        assertTrue("database should still be usable", adapter.verifyDatabase());
    }

    /**
     * The real-world case: a version-1 database with records in it survives a close and reopen
     * with every field intact. Every {@code .edb} in the wild is version 1, so this is the path
     * that must never regress.
     */
    @Test
    public void versionOneDatabase_reopensWithDataIntact() {
        adapter = new DBAdapter(context);
        adapter.open();
        adapter.newLog(1_600_000_000_000L, 0, 3, 42.50, 7, "survives a reopen", null, 0);
        int tagId = adapter.newTag("Groceries", 0, "fa-cart");
        long accountId = adapter.newAccount("Cash");
        assertEquals("fixture should be at schema version 1", 1, adapter.getDB().getVersion());
        adapter.close();

        // Reopen through a fresh DBAdapter, exactly as a cold app start would.
        adapter = new DBAdapter(context);
        adapter.open();

        assertEquals("still version 1", 1, adapter.getDB().getVersion());
        assertTrue(adapter.verifyDatabase());
        assertEquals(1, adapter.getTotalNumberOfLogs());

        Cursor c = adapter.getAllLogs();
        try {
            assertTrue(c.moveToFirst());
            assertEquals(1_600_000_000_000L, c.getLong(DBAdapter.COLUMN_LOG_TIME));
            assertEquals(42.50, c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT), 0.0001);
            assertEquals(7, c.getInt(DBAdapter.COLUMN_LOG_CATEGORY));
            assertEquals("survives a reopen", c.getString(DBAdapter.COLUMN_LOG_NOTES));
            assertEquals(3, c.getInt(DBAdapter.COLUMN_LOG_ACCOUNT));
        } finally {
            c.close();
        }

        assertEquals("Groceries", adapter.getCategoryLabel(tagId));
        assertEquals("Cash", adapter.getAccountLabel((int) accountId));
    }

    /**
     * A database written by a newer build must be refused, not opened as though it were current.
     *
     * <p>The previous empty {@code onDowngrade} suppressed {@link android.database.sqlite.SQLiteOpenHelper}'s
     * own guard, so this silently succeeded and the app then read a schema it did not understand.
     * The realistic trigger is importing an {@code .edb} from a future version of the app.
     */
    @Test
    public void openingANewerDatabase_isRefused() {
        File path = context.getDatabasePath(DBAdapter.DATABASE_NAME);
        //noinspection ResultOfMethodCallIgnored
        path.getParentFile().mkdirs();
        SQLiteDatabase raw = SQLiteDatabase.openOrCreateDatabase(path, null);
        raw.setVersion(99);
        raw.close();

        try {
            adapter = new DBAdapter(context);
            fail("opening a v99 database should be refused by this build");
        } catch (RuntimeException expected) {
            assertTrue("message should explain the version mismatch, was: " + expected.getMessage(),
                    expected.getMessage() != null && expected.getMessage().contains("99"));
        }
    }
}
