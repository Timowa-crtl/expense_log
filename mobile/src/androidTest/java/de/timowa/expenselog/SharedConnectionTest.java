package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Every {@link DBAdapter} for one database file shares one connection.
 *
 * <p>Each adapter used to open its own {@code SQLiteOpenHelper}, and injected adapters were never
 * closed, so the app held a connection per fragment, per pager page and per calendar cell.
 * docs/history/RELIABILITY_PLAN.md, Step 4 (F10).
 */
@RunWith(AndroidJUnit4.class)
public class SharedConnectionTest {

    private Context context;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    @Test
    public void twoAdapters_shareOneConnection() {
        DBAdapter a = new DBAdapter(context);
        DBAdapter b = new DBAdapter(context);
        assertSame(a.getDB(), b.getDB());
    }

    @Test
    public void aWriteThroughOneAdapter_isVisibleThroughAnother() {
        DBAdapter a = new DBAdapter(context);
        DBAdapter b = new DBAdapter(context);
        a.newLog(1L, 0, 1, 1.0, 1, "", null, -1);
        assertEquals(1, b.getTotalNumberOfLogs());
    }

    @Test
    public void anIsolatedAdapter_stillOpensTheTestDatabase() {
        String path = new DBAdapter(context).getDB().getPath();
        assertTrue("isolation lost, opened " + path,
                path.endsWith("/" + IsolatedDatabaseContext.PREFIX + DBAdapter.DATABASE_NAME));
    }

    @Test
    public void closingOneAdapter_leavesTheOthersWorking() {
        DBAdapter a = new DBAdapter(context);
        DBAdapter b = new DBAdapter(context);
        a.newLog(1L, 0, 1, 1.0, 1, "", null, -1);
        a.close();
        assertTrue(b.getDB().isOpen());
        assertEquals(1, b.getTotalNumberOfLogs());
    }

    @Test
    public void afterClosingTheSharedConnection_theNextCallReopensIt() {
        DBAdapter a = new DBAdapter(context);
        a.newLog(1L, 0, 1, 1.0, 1, "", null, -1);
        SQLiteDatabase before = a.getDB();

        DBAdapter.closeSharedConnection(context);
        assertTrue("the old connection must really be closed", !before.isOpen());

        assertEquals(1, a.getTotalNumberOfLogs());
        assertNotSame(before, a.getDB());
        assertSame(a.getDB(), new DBAdapter(context).getDB());
    }

    @Test
    public void twoThreadsWriting_throughTheirOwnAdapters_doNotCollide() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 2; t++) {
            final int base = t * 1000;
            Thread thread = new Thread(() -> {
                try {
                    DBAdapter adapter = new DBAdapter(context);
                    for (int i = 0; i < 300; i++)
                        adapter.newLog(base + i, 0, 1, 1.0, 1, "", null, -1);
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads)
            thread.join();
        if (failure.get() != null)
            throw new AssertionError(failure.get());
        assertEquals(600, new DBAdapter(context).getTotalNumberOfLogs());
    }
}
