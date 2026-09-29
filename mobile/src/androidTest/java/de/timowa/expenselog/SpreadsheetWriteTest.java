package de.timowa.expenselog;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * A CSV export that cannot be written says so, and leaves nothing behind to be offered.
 *
 * <p>{@code createSpreadsheet} used to catch the {@code IOException}, print it, and carry on:
 * the partial (or missing) file was recorded as the pending export, handed to the picker or share
 * sheet, and the user was told "export saved". It now goes through {@link SpreadsheetHelper#writeExport},
 * which reports failure and removes a partial file. docs/history/RELIABILITY_PLAN.md, Step 3 (F15).
 */
@RunWith(AndroidJUnit4.class)
public class SpreadsheetWriteTest {

    private Context context;
    private DBAdapter db;
    private File staging;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        db = new DBAdapter(context);
        db.open();
        db.newAccount("Personal");
        staging = FileHelper.getExportStagingDir(context);
    }

    @After
    public void tearDown() {
        db.close();
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        File[] leftovers = staging.listFiles();
        if (leftovers != null)
            for (File f : leftovers)
                //noinspection ResultOfMethodCallIgnored
                f.delete();
    }

    private SpreadsheetHelper helper(boolean withPrefs) {
        SpreadsheetHelper helper = new SpreadsheetHelper(context, null);
        if (withPrefs)
            helper.prefManager = new PrefManager(context);
        return helper;
    }

    @Test
    public void aWrittenExport_reportsSuccessAndHasContent() throws Exception {
        File out = new File(staging, "ok.csv");
        assertTrue(helper(true).writeExport(out, db, DBAdapter.KEY_LOG_TIME, new CsvFormatter()));
        assertTrue(out.isFile());
        String text = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue("header expected, was: " + text, text.contains(context.getString(R.string.amount)));
    }

    @Test
    public void aDestinationThatCannotBeOpened_reportsFailure() {
        File blocked = new File(staging, "blocked.csv");
        assertTrue(blocked.mkdirs());
        assertFalse(helper(true).writeExport(blocked, db, DBAdapter.KEY_LOG_TIME, new CsvFormatter()));
        assertTrue("the directory in the way is not the export's to delete", blocked.isDirectory());
    }

    @Test
    public void aWriteThatFailsPartWay_reportsFailureAndRemovesThePartialFile() {
        File partial = new File(staging, "partial.csv");
        // No PrefManager: the BOM is written, then the list's currency lookup throws.
        assertFalse(helper(false).writeExport(partial, db, DBAdapter.KEY_LOG_TIME, new CsvFormatter()));
        assertFalse("a partial export must not be left to be offered", partial.exists());
    }
}
