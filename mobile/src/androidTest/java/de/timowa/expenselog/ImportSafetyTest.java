package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;

/**
 * An import replaces the live database only when it is safe to, and replaces it completely.
 * docs/history/RELIABILITY_PLAN.md, Step 5 (F9).
 */
@RunWith(AndroidJUnit4.class)
public class ImportSafetyTest {

    private Context context;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        clearFilesDir();
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        clearFilesDir();
        //noinspection ResultOfMethodCallIgnored
        new File(live().getPath() + "-journal").delete();
    }

    /** Recursive: a test leaves a non-empty directory behind, and it must not leak into others. */
    private void clearFilesDir() {
        deleteContents(FileHelper.getAppFilesDir(context));
    }

    private static void deleteContents(File dir) {
        File[] files = dir.listFiles();
        if (files == null)
            return;
        for (File f : files) {
            if (f.isDirectory())
                deleteContents(f);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private File live() {
        return context.getDatabasePath(DBAdapter.DATABASE_NAME);
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0)
                out.write(buffer, 0, read);
        }
    }

    private void seed(int rows, String note) {
        DBAdapter adapter = new DBAdapter(context);
        adapter.newTag("Groceries", 0, "fa-cart");
        adapter.newAccount("Cash");
        for (int i = 0; i < rows; i++)
            adapter.newLog(1_600_000_000_000L + i, 0, 1, i, 1, note + " " + i, null, -1);
    }

    /** A copy of a database holding {@code rows} records noted {@code note}, and an empty live one. */
    private File candidate(int rows, String note) throws IOException {
        seed(rows, note);
        File candidate = new File(FileHelper.getAppFilesDir(context), "candidate.edb");
        DBAdapter.closeSharedConnection(context);
        copy(live(), candidate);
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        return candidate;
    }

    private long count(String where) {
        try (Cursor c = new DBAdapter(context).getDB()
                .rawQuery("SELECT COUNT(*) FROM mainLogs WHERE " + where, null)) {
            c.moveToFirst();
            return c.getLong(0);
        }
    }

    @Test
    public void whenTheSafetyBackupCannotBeWritten_theImportIsRefusedAndNothingChanges()
            throws Exception {
        File candidate = candidate(3, "incoming");
        seed(5, "current");

        // Something the backup cannot be written over.
        File backup = new File(FileHelper.getAppFilesDir(context), "ExpenseLogBackup.edb");
        assertTrue(backup.mkdirs());
        assertTrue(new File(backup, "occupied").createNewFile());

        FileHelper.ImportResult result = FileHelper.importFileAndVerify(context, candidate.getPath(), null);

        assertEquals("the import must be refused, not carried out without a backup",
                "BACKUP_FAILED", result.name());
        assertEquals(5, count("notes LIKE 'current%'"));
        assertEquals(0, count("notes LIKE 'incoming%'"));
    }

    @Test
    public void aStaleJournalBesideTheDatabase_isNotReplayedOntoTheImportedFile() throws Exception {
        File candidate = candidate(3, "incoming");
        seed(1500, "current " + new String(new char[200]).replace('\0', 'x'));

        // A real rollback journal for the current database, captured mid-transaction. A tiny page
        // cache forces SQLite to spill changed pages to the database file before commit, and it
        // syncs the journal first -- which is what makes a journal hot, as a crash would leave it.
        File journal = new File(live().getPath() + "-journal");
        byte[] stale;
        SQLiteDatabase db = new DBAdapter(context).getDB();
        db.execSQL("PRAGMA cache_size = 2");
        db.beginTransaction();
        try {
            db.delete("mainLogs", null, null);
            stale = Files.readAllBytes(journal.toPath());
        } finally {
            db.endTransaction();
        }
        assertTrue("could not capture a non-empty journal (" + stale.length + " bytes)", stale.length > 512);

        // The state a crash in the middle of a write leaves behind.
        DBAdapter.closeSharedConnection(context);
        Files.write(journal.toPath(), stale);

        assertEquals(FileHelper.ImportResult.OK,
                FileHelper.importFileAndVerify(context, candidate.getPath(), null));

        assertEquals("the imported records must be the ones in the file", 3,
                count("notes LIKE 'incoming%'"));
        assertEquals("nothing of the old database may come back", 0, count("notes LIKE 'current%'"));
        try (Cursor c = new DBAdapter(context).getDB().rawQuery("PRAGMA integrity_check", null)) {
            c.moveToFirst();
            assertEquals("ok", c.getString(0));
        }
    }

    /**
     * Another thread reading all through the import must not reopen the old file between the
     * close and the rename: a connection opened in that gap keeps the unlinked old file, and every
     * later read and write -- through any adapter -- goes to it.
     */
    @Test
    public void readsFromAnotherThreadDuringTheImport_endOnTheImportedFile() throws Exception {
        File candidate = candidate(3, "incoming");
        seed(5, "current");
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread reader = new Thread(() -> {
            DBAdapter adapter = new DBAdapter(context);
            while (!done.get()) {
                try {
                    adapter.getTotalNumberOfLogs();
                } catch (RuntimeException ignored) {
                    // A read that meets the swap may fail; what matters is where reads end up.
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 20; i++) {
                assertEquals(FileHelper.ImportResult.OK,
                        FileHelper.importFileAndVerify(context, candidate.getPath(), null));
                assertEquals("round " + i + ": reads went to the old, replaced file", 3,
                        count("notes LIKE 'incoming%'"));
                assertEquals("round " + i, 0, count("notes LIKE 'current%'"));
            }
        } finally {
            done.set(true);
            reader.join();
        }
    }

    /**
     * A transaction running on another thread must never have its connection closed under it by
     * an import. Deletes and repeating series hold one connection for the whole transaction; an
     * import used to close that connection mid-transaction, and the next statement threw
     * {@code IllegalStateException: attempt to re-open an already-closed object}.
     */
    @Test
    public void transactionsOnAnotherThreadDuringImports_areNeverCutOff() throws Exception {
        File candidate = candidate(3, "incoming");
        seed(5, "current");
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread writer = new Thread(() -> {
            DBAdapter adapter = new DBAdapter(context);
            try {
                while (!done.get()) {
                    int series = adapter.createRepeatingSeries(0L, 40L * 86_400_000L, 1.0, 1, 0, 0, 1, 1,
                            "series", null);
                    if (series != -1)
                        adapter.deleteRepeatingLogs(series);
                    int tag = adapter.newTag("temp", 0, "fa-tag");
                    adapter.deleteTag(tag);
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        writer.start();
        try {
            for (int i = 0; i < 20 && failure.get() == null; i++)
                assertEquals(FileHelper.ImportResult.OK,
                        FileHelper.importFileAndVerify(context, candidate.getPath(), null));
        } finally {
            done.set(true);
            writer.join();
        }
        if (failure.get() != null)
            throw new AssertionError("a transaction was cut off by an import", failure.get());
        assertEquals(3, count("notes LIKE 'incoming%'"));
    }

    /**
     * Ordinary reads -- queries and the cursors they return -- on another thread must survive an
     * import. They took no lock, so an import could close the connection between a query and the
     * reading of its cursor, and the background thread died with {@code IllegalStateException}: the
     * records list's and the calendar's loads are exactly that.
     */
    @Test
    public void cursorReadsOnAnotherThreadDuringImports_neverFail() throws Exception {
        File candidate = candidate(3, "incoming");
        seed(5, "current");
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread reader = new Thread(() -> {
            DBAdapter adapter = new DBAdapter(context);
            try {
                while (!done.get()) {
                    try (android.database.Cursor c = adapter.getLogsInRange(0, Long.MAX_VALUE,
                            DBAdapter.KEY_LOG_TIME, null)) {
                        while (c.moveToNext())
                            c.getString(DBAdapter.COLUMN_LOG_NOTES);
                    }
                    adapter.getTotalNumberOfLogs();
                    adapter.getTotalForRange(0, Long.MAX_VALUE, null);
                    try (android.database.Cursor c = adapter.getTags()) {
                        c.getCount();
                    }
                }
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 20 && failure.get() == null; i++)
                assertEquals(FileHelper.ImportResult.OK,
                        FileHelper.importFileAndVerify(context, candidate.getPath(), null));
        } finally {
            done.set(true);
            reader.join();
        }
        if (failure.get() != null)
            throw new AssertionError("a read was cut off by an import", failure.get());
    }

    @Test
    public void anAdapterInUseBeforeTheImport_seesTheImportedDataAfterIt() throws Exception {
        File candidate = candidate(3, "incoming");
        seed(5, "current");
        DBAdapter inUse = new DBAdapter(context);
        assertEquals(5, inUse.getTotalNumberOfLogs());

        assertEquals(FileHelper.ImportResult.OK,
                FileHelper.importFileAndVerify(context, candidate.getPath(), null));

        assertEquals(3, inUse.getTotalNumberOfLogs());
    }
}
