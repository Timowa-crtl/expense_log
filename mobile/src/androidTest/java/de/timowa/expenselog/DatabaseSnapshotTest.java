package de.timowa.expenselog;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link FileHelper#snapshotDatabaseTo} copies the live database consistently while it is being
 * written, and never opens a database it was only asked to copy. docs/history/RELIABILITY_PLAN.md, Step 5 (F8).
 */
@RunWith(AndroidJUnit4.class)
public class DatabaseSnapshotTest {

    private Context context;
    private File dir;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        dir = new File(FileHelper.getAppFilesDir(context), "snapshots");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        File[] files = dir.listFiles();
        if (files != null)
            for (File f : files)
                //noinspection ResultOfMethodCallIgnored
                f.delete();
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }

    /** Integrity and row count of a copied file, opened read-only. */
    static long checkedCount(File copy) {
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(copy.getPath(), null,
                SQLiteDatabase.OPEN_READONLY, dbObj -> { })) {
            try (Cursor c = db.rawQuery("PRAGMA integrity_check", null)) {
                c.moveToFirst();
                assertEquals("snapshot " + copy.getName() + " is torn", "ok", c.getString(0));
            }
            try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM mainLogs", null)) {
                c.moveToFirst();
                return c.getLong(0);
            }
        }
    }

    @Test
    public void snapshotsTakenWhileAnotherThreadWrites_areAllConsistent() throws Exception {
        DBAdapter adapter = new DBAdapter(context);
        String note = new String(new char[300]).replace('\0', 'n');
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 3000; i++)
                    adapter.newLog(i, 0, 1, i, 1, note, null, -1);
            } catch (Throwable e) {
                writerFailure.set(e);
            } finally {
                done.set(true);
            }
        });
        writer.start();

        long previous = -1;
        int taken = 0;
        while (!done.get() || taken < 50) {
            File copy = new File(dir, "s" + taken + ".edb");
            assertTrue(FileHelper.snapshotDatabaseTo(context, copy));
            long count = checkedCount(copy);
            assertTrue("counts went backwards: " + previous + " then " + count, count >= previous);
            previous = count;
            //noinspection ResultOfMethodCallIgnored
            copy.delete();
            taken++;
            if (taken > 2000)
                break;
        }
        writer.join();
        if (writerFailure.get() != null)
            throw new AssertionError(writerFailure.get());
        assertTrue("took " + taken + " snapshots", taken >= 50);
    }

    @Test
    public void aSnapshotReplacesAnExistingFileAndLeavesNoTemporary() throws Exception {
        new DBAdapter(context).newLog(1L, 0, 1, 1.0, 1, "", null, -1);
        File copy = new File(dir, "backup.edb");
        Files.write(copy.toPath(), "old backup".getBytes("UTF-8"));

        assertTrue(FileHelper.snapshotDatabaseTo(context, copy));

        assertEquals(1, checkedCount(copy));
        assertFalse(new File(copy.getPath() + ".tmp").exists());
    }

    @Test
    public void aSnapshotThatCannotBeWritten_leavesTheExistingFileAlone() throws Exception {
        new DBAdapter(context).newLog(1L, 0, 1, 1.0, 1, "", null, -1);
        File copy = new File(dir, "backup.edb");
        byte[] old = "old backup".getBytes("UTF-8");
        Files.write(copy.toPath(), old);
        // The temporary name is taken by a non-empty directory, so the copy cannot be written.
        File blocker = new File(copy.getPath() + ".tmp");
        assertTrue(blocker.mkdirs());
        assertTrue(new File(blocker, "x").createNewFile());

        try {
            assertFalse(FileHelper.snapshotDatabaseTo(context, copy));
            assertArrayEquals(old, Files.readAllBytes(copy.toPath()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            new File(blocker, "x").delete();
            //noinspection ResultOfMethodCallIgnored
            blocker.delete();
        }
    }

    @Test
    public void aSnapshotOfACorruptDatabase_copiesItRatherThanDestroyingIt() throws Exception {
        new DBAdapter(context).newLog(1L, 0, 1, 1.0, 1, "", null, -1);
        DBAdapter.closeSharedConnection(context);
        File live = context.getDatabasePath(DBAdapter.DATABASE_NAME);
        byte[] corrupt = "not a database any more".getBytes("UTF-8");
        try (OutputStream out = new FileOutputStream(live)) {
            out.write(corrupt);
        }

        File copy = new File(dir, "corrupt.edb");
        assertTrue(FileHelper.snapshotDatabaseTo(context, copy));

        assertArrayEquals("the live file must be untouched", corrupt, Files.readAllBytes(live.toPath()));
        assertArrayEquals("and copied as it is", corrupt, Files.readAllBytes(copy.toPath()));
    }
}
