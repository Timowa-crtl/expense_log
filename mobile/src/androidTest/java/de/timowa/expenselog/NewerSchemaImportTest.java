package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
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
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Finding 1 of {@code docs/history/REVIEW_FINDINGS.md}: importing an {@code .edb} written by a newer build used
 * to leave the app permanently unopenable.
 *
 * <p>The chain was: the probe checked only that a {@code tagTypes} table existed, so a future file
 * passed it; the live database was overwritten; opening it threw from {@code DBAdapter.onDowngrade}
 * by design; the rollback then resolved its destination through {@code getInternalDbFile}, which
 * opened the database it was trying to replace and threw again; {@code fileSetup} swallowed that;
 * and {@code restoreBackupDbFromSd}'s {@code else} was empty. The user was left with an unreadable
 * database and an intact backup on disk that nothing put back.
 *
 * <p>Only a crafted file can reach this. {@code DATABASE_VERSION} has only ever been 1, so a
 * genuine newer-schema {@code .edb} does not exist yet — {@link #edbFromANewerSchema} raises
 * {@code user_version} on a real export, which is the same value {@code SQLiteOpenHelper} compares.
 * The test proves the guard, not that a real v2 file behaves like a real v2 file.
 */
@RunWith(AndroidJUnit4.class)
public class NewerSchemaImportTest {

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

    /** Two records, so an import that wipes them is visible rather than inferred. */
    private void seedDatabase() {
        adapter = new DBAdapter(context);
        adapter.open();
        adapter.newTag("Groceries", 0, "fa-cart");
        adapter.newAccount("Cash");
        adapter.newLog(1_600_000_000_000L, 0, 1, 12.34, 1, "first", null, 0);
        adapter.newLog(1_600_000_001_000L, 1, 1, 56.78, 1, "second", null, 0);
        adapter.close();
        adapter = null;
    }

    /**
     * A real export of the live database, with its schema version raised past this build's.
     *
     * <p>The current version is read back off the file rather than from {@code DATABASE_VERSION},
     * so this class compiles and runs against the code as it stood before the fix — where that
     * constant was private. A regression test that only compiles after its fix proves nothing.
     */
    private File edbFromANewerSchema() throws Exception {
        File exported = new File(FileHelper.getAppFilesDir(context), "from-the-future.edb");
        copy(context.getDatabasePath(DBAdapter.DATABASE_NAME), exported);

        SQLiteDatabase raised = SQLiteDatabase.openDatabase(exported.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
        try {
            raised.setVersion(raised.getVersion() + 1);
        } finally {
            raised.close();
        }
        return exported;
    }

    /**
     * The whole finding in one test: refuse it, keep the records, and stay openable afterwards.
     */
    @Test
    public void importingANewerSchema_isRefusedAndChangesNothing() throws Exception {
        seedDatabase();
        File future = edbFromANewerSchema();
        long liveSizeBefore = context.getDatabasePath(DBAdapter.DATABASE_NAME).length();

        // The reason matters, not just the refusal: this is the message the user now sees.
        assertEquals("an .edb from a newer schema must be refused as such",
                FileHelper.ImportResult.NEWER_SCHEMA,
                FileHelper.importFileAndVerify(context, future.getPath(), null));

        // The point of refusing before the overwrite: the live file was never touched.
        assertEquals("the live database must not have been overwritten",
                liveSizeBefore, context.getDatabasePath(DBAdapter.DATABASE_NAME).length());

        // And it still opens, which is what finding 1 took away.
        adapter = new DBAdapter(context);
        adapter.open();
        assertTrue("the database must still verify", adapter.verifyDatabase());
        assertEquals("both records must still be there", 2, adapter.getTotalNumberOfLogs());
        assertEquals("Groceries", adapter.getCategoryLabel(1));
        assertEquals("Cash", adapter.getAccountLabel(1));
    }

    /** A same-version export must still import — the guard has to reject the future, not everything. */
    @Test
    public void importingTheCurrentSchema_stillSucceeds() throws Exception {
        seedDatabase();
        File exported = new File(FileHelper.getAppFilesDir(context), "current.edb");
        copy(context.getDatabasePath(DBAdapter.DATABASE_NAME), exported);

        assertEquals("a current-schema .edb must still import", FileHelper.ImportResult.OK,
                FileHelper.importFileAndVerify(context, exported.getPath(), null));

        adapter = new DBAdapter(context);
        adapter.open();
        assertEquals(2, adapter.getTotalNumberOfLogs());
    }

    /**
     * {@code getInternalDbFile} must resolve a path without opening anything.
     *
     * <p>This is the half of the fix that makes recovery possible at all: a restore resolves its
     * destination through here, so a getter that opens the database cannot serve a database that
     * will not open.
     *
     * <p>The assertion is on the file's <em>size measured before the call</em>, which matters. The
     * old implementation did not throw on a corrupt file — Android's
     * {@code DefaultDatabaseErrorHandler} deletes it and creates a fresh empty database instead —
     * so comparing two sizes read after the call compares the replacement with itself and passes
     * against the bug. Recording the size first is what makes this fail before the fix, and it also
     * states the real requirement: resolving a path must not touch the file at all.
     */
    @Test
    public void internalDbFile_resolvesWithoutTouchingTheDatabase() throws Exception {
        seedDatabase();
        File live = context.getDatabasePath(DBAdapter.DATABASE_NAME);

        byte[] corrupt = "not a database any more".getBytes("UTF-8");
        try (OutputStream out = new FileOutputStream(live)) {
            out.write(corrupt);
        }
        long sizeBefore = live.length();
        assertEquals(corrupt.length, sizeBefore);

        File resolved = FileHelper.getInternalDbFile(context);

        assertEquals("the path must still come back", live.getAbsolutePath(),
                resolved.getAbsolutePath());
        assertEquals("resolving a path must not delete and recreate the database",
                sizeBefore, resolved.length());
    }

    /**
     * Pins the reason it is safe for {@code getInternalDbFile} to have stopped disabling WAL:
     * {@code DBAdapter} does it on every open, and the journal mode is persistent in the file. The
     * Dropbox paths copy this file directly, so a WAL-mode database would upload without whatever
     * sits in the {@code -wal} sidecar.
     */
    @Test
    public void walIsDisabled_soUploadsCannotMissRecentWrites() {
        seedDatabase();

        adapter = new DBAdapter(context);
        adapter.open();
        String journalMode;
        android.database.Cursor c = adapter.getDB().rawQuery("PRAGMA journal_mode", null);
        try {
            assertTrue(c.moveToFirst());
            journalMode = c.getString(0);
        } finally {
            c.close();
        }

        assertNotEquals("journal_mode must not be WAL, or the Dropbox upload copies an"
                + " incomplete database", "wal", journalMode.toLowerCase());

        File wal = new File(context.getDatabasePath(DBAdapter.DATABASE_NAME).getPath() + "-wal");
        assertFalse("no -wal sidecar should exist beside the database", wal.exists());
    }

    private void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }
}
