package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

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
 * A successful {@code .edb} import marks the database as changed locally, so Dropbox sync uploads
 * it -- or asks -- instead of later downloading over it.
 *
 * <p>The import used to leave the Dropbox version marker at the remote version it held before, so
 * the sync check thought the imported file was already on Dropbox: it never uploaded it, and the
 * next time the remote changed it downloaded over the import without a prompt.
 * docs/history/RELIABILITY_PLAN.md, Step 7 (F6).
 */
@RunWith(AndroidJUnit4.class)
public class ImportSyncMarkerTest {

    private static final String REMOTE_REV = "0123456789abcdef";
    private Context context;
    private SharedPreferences prefs;
    private String markerKey;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        markerKey = context.getString(R.string.pref_key_current_dropbox_db_version);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        prefs.edit().clear().commit();
        File[] files = FileHelper.getAppFilesDir(context).listFiles();
        if (files != null)
            for (File f : files)
                //noinspection ResultOfMethodCallIgnored
                f.delete();
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0)
                out.write(buffer, 0, read);
        }
    }

    /** A valid candidate file, and a live database whose marker says it matches the remote. */
    private File candidateWithSyncedLiveDatabase() throws IOException {
        DBAdapter adapter = new DBAdapter(context);
        adapter.newTag("Groceries", 0, "fa-cart");
        adapter.newLog(1L, 0, 1, 1.0, 1, "incoming", null, -1);
        DBAdapter.closeSharedConnection(context);
        File candidate = new File(FileHelper.getAppFilesDir(context), "candidate.edb");
        copy(context.getDatabasePath(DBAdapter.DATABASE_NAME), candidate);
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        new DBAdapter(context).newLog(2L, 0, 1, 2.0, 1, "current", null, -1);
        prefs.edit().putString(markerKey, REMOTE_REV).commit();
        return candidate;
    }

    @Test
    public void aSuccessfulImport_marksTheDatabaseAsChangedLocally() throws Exception {
        File candidate = candidateWithSyncedLiveDatabase();
        assertEquals(FileHelper.ImportResult.OK,
                FileHelper.importFileAndVerify(context, candidate.getPath(), null));
        assertEquals(DBAdapter.KEY_DATABASE_CHANGE, prefs.getString(markerKey, null));
    }

    @Test
    public void aRefusedImport_leavesTheMarkerAlone() throws Exception {
        candidateWithSyncedLiveDatabase();
        File junk = new File(FileHelper.getAppFilesDir(context), "junk.edb");
        Files.write(junk.toPath(), "not a database".getBytes("UTF-8"));
        assertEquals(FileHelper.ImportResult.NOT_A_DATABASE,
                FileHelper.importFileAndVerify(context, junk.getPath(), null));
        assertEquals(REMOTE_REV, prefs.getString(markerKey, null));
    }
}
