package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The {@code .edb} round trip, deferred from Step 2 and written here because Step 4 is what made it
 * safe to write.
 *
 * <p>In Step 2 this test was impossible to sandbox: {@code importFileAndVerify} overwrote the live
 * database and rooted its paths at {@code Environment.getExternalStorageDirectory()}. Now that
 * every path is either the app's own directory or a caller-supplied stream, the isolation wrapper
 * covers all of it.
 *
 * <p>The important case is {@link #import_fromAStreamedCopy_preservesEveryRecord}, which exercises
 * the buffer bug fixed in Step 4: the content-resolver branch used to write a whole fixed-size
 * buffer regardless of how many bytes it had actually read, padding the output with stale data on
 * every short read.
 */
@RunWith(AndroidJUnit4.class)
public class EdbRoundTripTest {

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

    /** Writes a known set of records and returns the on-disk database file. */
    private File seedDatabase() {
        adapter = new DBAdapter(context);
        adapter.open();
        adapter.newTag("Groceries", 0, "fa-cart");
        adapter.newAccount("Cash");
        adapter.newLog(1_600_000_000_000L, 0, 1, 12.34, 1, "first", null, 0);
        adapter.newLog(1_600_000_001_000L, 1, 1, 56.78, 1, "second", null, 0);
        adapter.close();
        adapter = null;
        return context.getDatabasePath(DBAdapter.DATABASE_NAME);
    }

    /**
     * Copies through a stream in awkward chunk sizes, the way a content:// import does.
     *
     * <p>Uses a deliberately odd buffer so that the final read is always short — the exact
     * condition the old {@code write(buffer)} bug corrupted.
     */
    private void streamCopy(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[777];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    @Test
    public void import_fromAStreamedCopy_preservesEveryRecord() throws Exception {
        File source = seedDatabase();
        File exported = new File(FileHelper.getAppFilesDir(context), "roundtrip.edb");
        streamCopy(source, exported);
        assertEquals("exported copy must be byte-identical in length",
                source.length(), exported.length());

        // Wipe, proving the data really comes back from the file rather than surviving in place.
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        adapter = new DBAdapter(context);
        adapter.open();
        assertEquals("database should be empty before import", 0, adapter.getTotalNumberOfLogs());
        adapter.close();
        adapter = null;

        assertEquals("import should succeed", FileHelper.ImportResult.OK,
                FileHelper.importFileAndVerify(context, exported.getPath(), null));

        adapter = new DBAdapter(context);
        adapter.open();
        assertTrue("imported database must verify", adapter.verifyDatabase());
        assertEquals("both records must come back", 2, adapter.getTotalNumberOfLogs());

        Cursor c = adapter.getAllLogs();
        try {
            assertTrue(c.moveToFirst());
            assertEquals(1_600_000_000_000L, c.getLong(DBAdapter.COLUMN_LOG_TIME));
            assertEquals(12.34, c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT), 0.0001);
            assertEquals("first", c.getString(DBAdapter.COLUMN_LOG_NOTES));
            assertEquals(0, c.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME));

            assertTrue(c.moveToNext());
            assertEquals(56.78, c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT), 0.0001);
            assertEquals("second", c.getString(DBAdapter.COLUMN_LOG_NOTES));
            assertEquals(1, c.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME));
        } finally {
            c.close();
        }

        assertEquals("Groceries", adapter.getCategoryLabel(1));
        assertEquals("Cash", adapter.getAccountLabel(1));
    }

    /** A file that is not a database must be rejected, not left in place as the live database. */
    @Test
    public void import_ofAnInvalidFile_isRejected() throws Exception {
        seedDatabase();

        File junk = new File(FileHelper.getAppFilesDir(context), "not-a-database.edb");
        try (OutputStream out = new FileOutputStream(junk)) {
            out.write("this is definitely not a SQLite database".getBytes("UTF-8"));
        }

        // Sharper than the boolean this used to assert on: a rejected file and a file that could
        // not be read were the same `false`, so the test could not tell the guard from a failure.
        assertEquals("junk must be refused as not our database",
                FileHelper.ImportResult.NOT_A_DATABASE,
                FileHelper.importFileAndVerify(context, junk.getPath(), null));
    }

    /**
     * A source that cannot be read is its own outcome, distinct from a file that was read and
     * refused. Both used to be {@code false}, and the Dropbox path reported them identically.
     */
    @Test
    public void import_ofAMissingFile_isUnreadable() {
        seedDatabase();

        File missing = new File(FileHelper.getAppFilesDir(context), "there-is-no-such-file.edb");
        assertEquals("a source that cannot be opened must say so",
                FileHelper.ImportResult.UNREADABLE,
                FileHelper.importFileAndVerify(context, missing.getPath(), null));

        // And nothing was touched on the way to finding that out.
        adapter = new DBAdapter(context);
        adapter.open();
        assertEquals("records must be untouched", 2, adapter.getTotalNumberOfLogs());
    }

    /**
     * A plain filesystem path must import whether or not a {@code ContentResolver} comes with it —
     * finding 4 of {@code docs/history/REVIEW_FINDINGS.md}.
     *
     * <p>{@code openImportSource} used to branch on {@code contentResolver != null} rather than on
     * the URI scheme, so {@code MainActivity}'s {@code file://} branch — which passes a bare path
     * and a live resolver — always threw {@code FileNotFoundException: Unknown URL}. The caller now
     * passes {@code null}, but the rule belongs in the callee, and this asserts it there.
     */
    @Test
    public void import_ofAPlainPath_succeedsEvenWhenGivenAResolver() throws Exception {
        File source = seedDatabase();
        File exported = new File(FileHelper.getAppFilesDir(context), "with-resolver.edb");
        streamCopy(source, exported);

        assertEquals("a bare path must not be parsed as a content URI",
                FileHelper.ImportResult.OK,
                FileHelper.importFileAndVerify(context, exported.getPath(),
                        context.getContentResolver()));

        adapter = new DBAdapter(context);
        adapter.open();
        assertEquals(2, adapter.getTotalNumberOfLogs());
    }

    /**
     * The same file offered as a whole {@code file://} URI.
     *
     * <p>Characterisation, not regression: this passed before the fix too, and that is the precise
     * shape of finding 4. {@code ContentResolver.openInputStream} accepts {@code file://} URIs
     * perfectly well, so the branch was not broken because a resolver cannot read one — it was
     * broken because {@code MainActivity} passed {@code uri.getPath()} and threw the scheme away,
     * leaving a bare path to be parsed as a URI. Handing over the whole URI always worked.
     */
    @Test
    public void import_ofAFileUri_succeeds() throws Exception {
        File source = seedDatabase();
        File exported = new File(FileHelper.getAppFilesDir(context), "as-file-uri.edb");
        streamCopy(source, exported);

        assertEquals("a file:// URI must be read from disk, not through the resolver",
                FileHelper.ImportResult.OK,
                FileHelper.importFileAndVerify(context,
                        android.net.Uri.fromFile(exported).toString(), context.getContentResolver()));

        adapter = new DBAdapter(context);
        adapter.open();
        assertEquals(2, adapter.getTotalNumberOfLogs());
    }

    /** The app's own directory must be usable without any permission being granted. */
    @Test
    public void appFilesDir_isWritableWithoutPermissions() throws Exception {
        File dir = FileHelper.getAppFilesDir(context);
        assertTrue("app files dir should exist", dir.exists());

        File probe = new File(dir, "probe.tmp");
        try (OutputStream out = new FileOutputStream(probe)) {
            out.write(new byte[]{1, 2, 3});
        }
        assertEquals(3, probe.length());
        //noinspection ResultOfMethodCallIgnored
        probe.delete();
    }
}
