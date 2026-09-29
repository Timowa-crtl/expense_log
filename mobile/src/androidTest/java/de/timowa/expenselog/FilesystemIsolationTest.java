package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

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
import java.util.Map;
import java.util.TreeMap;

/**
 * Pins the isolation harness itself — finding 2 of {@code docs/history/REVIEW_FINDINGS.md}.
 *
 * <p>{@link IsolatedDatabaseContext} used to redirect the database and preferences but not the
 * directories, so {@code FileHelper.getAppFilesDir} resolved to the real
 * {@code Android/data/de.timowa.expenselog/files} and {@code backupDbToSd} copied the test database
 * over the user's own {@code ExpenseLogBackup.edb}. Running the suite on a phone in daily use
 * destroyed the backup — while testing the import path whose failure is the one situation that
 * backup exists for.
 *
 * <p>Every assertion here is written against the <b>real</b> application context rather than against
 * helper methods on the wrapper, so this class compiles unchanged against the harness as it stood at
 * {@code f05eaf6}. That is what makes the red run before the fix meaningful evidence rather than a
 * tautology, and it means the next accessor someone forgets to override is caught by its effect on
 * the real directory, not by a list that has to be kept up to date.
 */
@RunWith(AndroidJUnit4.class)
public class FilesystemIsolationTest {

    private Context real;
    private Context context;
    private DBAdapter adapter;

    @Before
    public void setUp() {
        real = ApplicationProvider.getApplicationContext();
        context = new IsolatedDatabaseContext(real);
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    @After
    public void tearDown() {
        if (adapter != null) {
            adapter.close();
            adapter = null;
        }
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        // Only ever delete through the wrapper: if isolation is broken these are the real
        // directories, so anything stronger than removing our own files would destroy user data.
        deleteOurs(context.getExternalFilesDir(null));
        deleteOurs(context.getCacheDir());
    }

    /** Every directory accessor must hand back somewhere other than the real one. */
    @Test
    public void everyDirectoryAccessor_isRedirected() {
        assertNotEquals("getExternalFilesDir must be redirected",
                path(real.getExternalFilesDir(null)), path(context.getExternalFilesDir(null)));
        assertNotEquals("getFilesDir must be redirected",
                path(real.getFilesDir()), path(context.getFilesDir()));
        assertNotEquals("getCacheDir must be redirected",
                path(real.getCacheDir()), path(context.getCacheDir()));

        // Disposable by construction: everything redirected lives under the app's cache.
        String cache = path(real.getCacheDir());
        for (File dir : new File[]{context.getExternalFilesDir(null), context.getFilesDir(),
                context.getCacheDir()}) {
            assertTrue(dir + " should live under the cache dir " + cache,
                    path(dir).startsWith(cache));
            assertTrue("the wrapper should have created " + dir, dir.isDirectory());
        }
    }

    /** {@code getAppFilesDir} is the accessor {@code FileHelper} actually writes backups through. */
    @Test
    public void getAppFilesDir_doesNotResolveToTheRealDir() {
        assertNotEquals("FileHelper.getAppFilesDir must not reach the real app files dir",
                path(realAppFilesDir()), path(FileHelper.getAppFilesDir(context)));
    }

    /**
     * The finding-2 regression: a full import round trip must not write a byte into the real
     * directory, which on the maintainer's phone holds the only local backup.
     */
    @Test
    public void import_leavesTheRealAppFilesDirUntouched() throws Exception {
        File realDir = realAppFilesDir();
        Map<String, String> before = snapshot(realDir);

        adapter = new DBAdapter(context);
        adapter.open();
        adapter.newTag("Groceries", 0, "fa-cart");
        adapter.newAccount("Cash");
        adapter.newLog(1_600_000_000_000L, 0, 1, 12.34, 1, "first", null, 0);
        adapter.close();
        adapter = null;

        File exported = new File(FileHelper.getAppFilesDir(context), "isolation.edb");
        copy(context.getDatabasePath(DBAdapter.DATABASE_NAME), exported);

        // The import takes a backup on its way through, and that is the write that used to escape.
        assertEquals("import should succeed", FileHelper.ImportResult.OK,
                FileHelper.importFileAndVerify(context, exported.getPath(), null));

        assertEquals("the real app files dir must be exactly what it was",
                before, snapshot(realDir));
    }

    /** The real destination, resolved the same way {@code FileHelper.getAppFilesDir} resolves it. */
    private File realAppFilesDir() {
        File dir = real.getExternalFilesDir(null);
        return dir != null ? dir : real.getFilesDir();
    }

    private String path(File file) {
        return file == null ? "null" : file.getAbsolutePath();
    }

    /** Name, size and modification time of everything under {@code dir}, recursively. */
    private Map<String, String> snapshot(File dir) {
        Map<String, String> entries = new TreeMap<>();
        collect(dir, "", entries);
        return entries;
    }

    private void collect(File dir, String prefix, Map<String, String> into) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = prefix + child.getName();
            if (child.isDirectory()) {
                into.put(name + "/", "dir");
                collect(child, name + "/", into);
            } else {
                into.put(name, child.length() + "@" + child.lastModified());
            }
        }
    }

    private void copy(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    /** Deletes only the files this class creates, never a whole directory tree. */
    private void deleteOurs(File dir) {
        if (dir == null) {
            return;
        }
        for (String name : new String[]{"isolation.edb", "ExpenseLogBackup.edb",
                "import_candidate.edb"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(dir, name).delete();
        }
    }
}
