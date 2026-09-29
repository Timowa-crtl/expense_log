package de.timowa.expenselog;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * A database export must not delete the backups that share its directory.
 *
 * <p>The export used to be staged at {@code getAppFilesDir()/expenseLog.edb}, which is also
 * {@code LocalBackupManager}'s current backup, and since PR #6 the staged path is deleted after
 * Save, after Cancel, and by the next export after a Share. Every export therefore deleted the
 * current backup. {@code ExportDestinationTest} stated the right cleanup rule but staged a file of
 * its own under a test name, so the real staging path never met the real backup names.
 *
 * <p>This test runs the real {@code stageDbForExport} against a real {@code checkBackups}, all in
 * {@link IsolatedDatabaseContext}: the backups live in the directory it redirects.
 */
@RunWith(AndroidJUnit4.class)
public class ExportStagingTest {

    /** {@code LocalBackupManager}'s current backup, whose name is private there. */
    private static final String CURRENT_BACKUP_NAME = "expenseLog.edb";

    /** Every file the app keeps in the files root: the four local backups and the import rollback. */
    private static final String[] BACKUP_NAMES = {
            CURRENT_BACKUP_NAME,
            "expenseLogDailyBackup.edb",
            "expenseLogWeeklyBackup.edb",
            "expenseLogMonthlyBackup.edb",
            "ExpenseLogBackup.edb",
    };

    private Context context;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        FileHelper.discardPendingExport(context);
    }

    @After
    public void tearDown() {
        FileHelper.discardPendingExport(context);
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    /** The reported bug: stage an export, cancel it, and the current backup is gone. */
    @Test
    public void discardingAnExport_leavesTheCurrentBackupAlone() {
        File currentBackup = takeCurrentBackup();
        long length = currentBackup.length();

        File staged = FileHelper.stageDbForExport(context);
        assertNotNull("the export could not be staged", staged);
        FileHelper.rememberPendingExport(context, staged);
        FileHelper.discardPendingExport(context);

        assertTrue("discarding an export deleted LocalBackupManager's current backup",
                currentBackup.exists());
        assertEquals(length, currentBackup.length());
    }

    /**
     * The staged database has a folder and a name of its own, so no backup can be mistaken for it.
     */
    @Test
    public void stagedDatabase_isInExportsAndNamedLikeNoBackup() {
        new DBAdapter(context).close();

        File staged = FileHelper.stageDbForExport(context);

        assertNotNull("the export could not be staged", staged);
        assertEquals(FileHelper.getExportStagingDir(context), staged.getParentFile());
        assertEquals("exports", staged.getParentFile().getName());
        assertEquals(FileHelper.exportDbFileName(context), staged.getName());
        for (String backup : BACKUP_NAMES) {
            assertNotEquals("the staged export is named like a backup", backup, staged.getName());
        }
        //noinspection ResultOfMethodCallIgnored
        staged.delete();
    }

    /**
     * A path recorded by the build before this one still names the current backup. Cleanup must
     * refuse it, and forget it, rather than delete the backup one last time after the update.
     */
    @Test
    public void discardPendingExport_refusesAPathOutsideExports() {
        File currentBackup = takeCurrentBackup();
        FileHelper.rememberPendingExport(context, currentBackup);

        FileHelper.discardPendingExport(context);

        assertTrue("cleanup deleted a file outside exports/", currentBackup.exists());
        assertNull("the refused path must still be forgotten", FileHelper.takePendingExport(context));
    }

    /** The same pre-upgrade path, saved through the picker: copied, and left where it was. */
    @Test
    public void copyStagedFileTo_copiesButKeepsAFileOutsideExports() throws IOException {
        File currentBackup = takeCurrentBackup();
        Context real = ApplicationProvider.getApplicationContext();
        File destination = new File(real.getFilesDir(), "test_export_staging_destination.edb");
        try {
            Uri target = FileProvider.getUriForFile(real, real.getPackageName() + ".provider",
                    destination);

            assertTrue(FileHelper.copyStagedFileTo(context, currentBackup, target));

            assertTrue("a successful copy deleted a file outside exports/", currentBackup.exists());
            assertArrayEquals(Files.readAllBytes(currentBackup.toPath()),
                    Files.readAllBytes(destination.toPath()));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            destination.delete();
        }
    }

    /** The guard must not strand legitimate staged files: one in exports/ is still cleaned up. */
    @Test
    public void discardPendingExport_stillDeletesAStagedDatabase() {
        new DBAdapter(context).close();
        File staged = FileHelper.stageDbForExport(context);
        assertNotNull("the export could not be staged", staged);
        FileHelper.rememberPendingExport(context, staged);

        FileHelper.discardPendingExport(context);

        assertFalse(staged.exists());
    }

    /** Creates the database and lets {@code checkBackups} write {@code expenseLog.edb}. */
    private File takeCurrentBackup() {
        new DBAdapter(context).close();
        // checkBackups raises a Toast when a copy fails, which throws off the main thread.
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> LocalBackupManager.checkBackups(context));
        File currentBackup = new File(FileHelper.getAppFilesDir(context), CURRENT_BACKUP_NAME);
        assertTrue("checkBackups did not write the current backup", currentBackup.exists());
        return currentBackup;
    }
}
