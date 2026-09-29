package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Pins the half of the export the user never sees: the intent that asks where the file goes, and
 * the copy that puts it there.
 *
 * <p>Export used to hand its file to {@code ACTION_SEND}. A share sheet lists apps that registered
 * to receive a file of that MIME type, and saving to the device is not something an app registers
 * for -- so local storage could not appear in it however the intent was tuned, and the export
 * looked broken to anyone who wanted the file on their own phone.
 * {@code ACTION_CREATE_DOCUMENT} asks the question the user was actually asking. The first test
 * below is what stops it quietly turning back into a share.
 *
 * <p>The picker itself cannot be driven from here -- that needs Espresso-Intents, which this
 * module does not have -- so this covers the two halves either side of it.
 *
 * <p>The staged file lives in the <b>isolated</b> {@code exports/} folder, because cleanup deletes
 * nothing outside the staging folder and the real one is the user's. The destination goes in
 * {@code getFilesDir()} under a distinctive name, because it needs a {@code content://} URI and the
 * isolation sandbox is not a declared FileProvider root; it is deliberately <b>not</b>
 * {@code getAppFilesDir()}, which on a real phone holds {@code ExpenseLogBackup.edb}. Preferences
 * go through {@link IsolatedDatabaseContext} too, so a test run cannot leave a pending-export path
 * in the user's own settings.
 */
@RunWith(AndroidJUnit4.class)
public class ExportDestinationTest {

    private static final String STAGED_CONTENT = "Date,Amount\n2026-09-12,12.34\n";

    private Context realContext;
    private Context isolated;
    private File staged;
    private File destination;

    @Before
    public void setUp() throws IOException {
        realContext = ApplicationProvider.getApplicationContext();
        isolated = new IsolatedDatabaseContext(realContext);

        staged = new File(FileHelper.getExportStagingDir(isolated), "test_staged_export.csv");
        destination = new File(realContext.getFilesDir(), "test_export_destination.csv");
        write(staged, STAGED_CONTENT);
    }

    @After
    public void tearDown() {
        //noinspection ResultOfMethodCallIgnored
        staged.delete();
        //noinspection ResultOfMethodCallIgnored
        destination.delete();
        FileHelper.discardPendingExport(isolated);
    }

    /**
     * The whole point of the change: export asks the system to create a document, which is what
     * puts local storage in the list, rather than asking who wants to receive one.
     */
    @Test
    public void createExportIntent_asksTheSystemToCreateADocument() {
        Intent intent = FileHelper.createExportIntent("Records.csv", FileHelper.CSV_MIME_TYPE);

        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.getAction());
        assertNotEquals("export must not go back to being a share sheet: a share sheet cannot"
                + " offer local storage", Intent.ACTION_SEND, intent.getAction());
        assertTrue("the picker only lists openable documents without CATEGORY_OPENABLE",
                intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals(FileHelper.CSV_MIME_TYPE, intent.getType());
        assertEquals("EXTRA_TITLE is the only say the app has in the file name",
                "Records.csv", intent.getStringExtra(Intent.EXTRA_TITLE));
    }

    /** The database export carries a real MIME type now, not the old {@code "file/db"}. */
    @Test
    public void createExportIntent_givesTheDatabaseARealMimeType() {
        Intent intent = FileHelper.createExportIntent("Expense Log 2026-09-12.edb",
                FileHelper.DB_MIME_TYPE);

        assertEquals("application/octet-stream", intent.getType());
        assertNotEquals("\"file/db\" is not a MIME type; nothing matches it",
                "file/db", intent.getType());
    }

    /** The exported database must be findable later, so its name carries the date. */
    @Test
    public void exportDbFileName_isDatedAndCarriesTheExtension() {
        String name = FileHelper.exportDbFileName(realContext);

        assertTrue(name + " does not end in " + FileHelper.database_extension,
                name.endsWith(FileHelper.database_extension));
        assertNotEquals("every backup would be named the same thing again",
                DBAdapter.DATABASE_NAME, name);
        assertTrue(name + " carries no date", name.matches(".*\\d{4}-\\d{2}-\\d{2}.*"));
    }

    @Test
    public void copyStagedFileTo_writesEveryByteAndRemovesTheStagingCopy() throws IOException {
        assertTrue(FileHelper.copyStagedFileTo(isolated, staged, uriFor(destination)));

        assertEquals(STAGED_CONTENT, read(destination));
        assertFalse("the staging copy sits where no file manager can reach it, so a successful"
                + " export must not leave one behind", staged.exists());
    }

    /**
     * A failed copy keeps the staging file. Deleting it would destroy the only complete copy of
     * the export at the moment writing it elsewhere has just failed.
     */
    @Test
    public void copyStagedFileTo_keepsTheStagingCopyWhenTheWriteFails() {
        Uri unreachable = Uri.parse("content://de.timowa.expenselog.no.such.provider/export.csv");

        assertFalse(FileHelper.copyStagedFileTo(isolated, staged, unreachable));
        assertTrue(staged.exists());
    }

    /** Nothing staged, or no destination picked, is a failure and not a crash. */
    @Test
    public void copyStagedFileTo_reportsFailureRatherThanThrowing() {
        assertFalse(FileHelper.copyStagedFileTo(isolated, null, uriFor(destination)));
        assertFalse(FileHelper.copyStagedFileTo(isolated, staged, null));
    }

    /**
     * The pending path outlives the picker, and is handed out exactly once.
     *
     * <p>It is stored rather than held in a field because the picker is another activity and the
     * app can be killed behind it; taking it clears it so a stale path cannot be copied a second
     * time into some later export's destination.
     */
    @Test
    public void pendingExport_isRememberedOnceAndThenForgotten() {
        FileHelper.rememberPendingExport(isolated, staged);

        File taken = FileHelper.takePendingExport(isolated);
        assertNotNull(taken);
        assertEquals(staged.getAbsolutePath(), taken.getAbsolutePath());

        assertNull("a pending export must not be handed out twice",
                FileHelper.takePendingExport(isolated));
    }

    /** Cancelling the picker throws the staged file away rather than stranding it. */
    @Test
    public void discardPendingExport_deletesTheStagedFile() {
        FileHelper.rememberPendingExport(isolated, staged);

        FileHelper.discardPendingExport(isolated);

        assertFalse(staged.exists());
        assertNull(FileHelper.takePendingExport(isolated));
    }

    /** Share offers the file to other apps; it is the other half of the choice, not a fallback. */
    @Test
    public void createShareIntent_offersTheFileToOtherApps() {
        // In a declared FileProvider root; the isolated staging folder, under the cache, is not.
        File shareable = new File(realContext.getFilesDir(), "test_staged_export.csv");
        Intent chooser = FileHelper.createShareIntent(realContext, shareable,
                FileHelper.CSV_MIME_TYPE, "Records.csv");

        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent share = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertNotNull("createChooser must wrap the ACTION_SEND intent", share);
        assertEquals(Intent.ACTION_SEND, share.getAction());
        assertEquals(FileHelper.CSV_MIME_TYPE, share.getType());
        assertNotNull("the target needs a content:// URI to read",
                share.getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals("some targets name the file from the subject and some from the title,"
                        + " so both carry the same string",
                "Records.csv", share.getStringExtra(Intent.EXTRA_SUBJECT));
        assertEquals("Records.csv", share.getStringExtra(Intent.EXTRA_TITLE));
        assertTrue("without the read grant the target cannot open the file",
                (share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    /**
     * Cleanup deletes the one recorded path and cannot reach anything else, even in its own folder.
     *
     * <p>Deleting by name or by listing a folder puts everything in it one bad predicate away from
     * being destroyed, which is finding 2 of {@code docs/history/REVIEW_FINDINGS.md}. Deleting by
     * recorded path cannot. This is the test that fails if someone turns it back into a scan;
     * {@code ExportStagingTest} covers the other half, that the recorded path must also be inside
     * {@code exports/}.
     */
    @Test
    public void discardPendingExport_touchesOnlyTheFileItRecorded() throws IOException {
        File sibling = new File(staged.getParentFile(), "test_pretend_backup.edb");
        try {
            write(sibling, "precious");
            FileHelper.rememberPendingExport(isolated, staged);

            FileHelper.discardPendingExport(isolated);

            assertFalse("the recorded staged export is what cleanup is for", staged.exists());
            assertTrue("a file the app never recorded must be untouchable by cleanup",
                    sibling.exists());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            sibling.delete();
        }
    }

    private Uri uriFor(File file) {
        return FileProvider.getUriForFile(realContext,
                realContext.getPackageName() + ".provider", file);
    }

    private void write(File file, String content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
