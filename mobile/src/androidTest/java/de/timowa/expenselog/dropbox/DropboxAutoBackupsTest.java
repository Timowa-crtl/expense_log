package de.timowa.expenselog.dropbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

/**
 * The Dropbox auto-backups decide their age from the file's {@code Date}, in any locale.
 *
 * <p>The weekly branch used to parse {@code Date.toString()} -- which is always English -- with the
 * device's locale. On a German phone that threw, the exception was swallowed, the age came out as
 * zero days, and the weekly backup was never refreshed. docs/history/RELIABILITY_PLAN.md, Step 3 (F5).
 */
@RunWith(AndroidJUnit4.class)
public class DropboxAutoBackupsTest {

    private static final long DAY = 24L * 60 * 60 * 1000;
    private Locale saved;

    @Before
    public void setUp() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
    }

    @After
    public void tearDown() {
        Locale.setDefault(saved);
    }

    @Test
    public void aWeeklyBackupEightDaysOld_isDueInAGermanLocale() {
        long now = System.currentTimeMillis();
        assertTrue(DropboxAutoBackups.isDue(now - 8 * DAY, now, 7));
    }

    @Test
    public void aBackupExactlySevenDaysOld_isNotYetDue() {
        long now = System.currentTimeMillis();
        assertFalse(DropboxAutoBackups.isDue(now - 7 * DAY, now, 7));
        assertFalse(DropboxAutoBackups.isDue(now - 6 * DAY, now, 7));
    }

    @Test
    public void aMonthlyBackup_isDueAfterThirtyDays() {
        long now = System.currentTimeMillis();
        assertTrue(DropboxAutoBackups.isDue(now - 31 * DAY, now, 30));
        assertFalse(DropboxAutoBackups.isDue(now - 29 * DAY, now, 30));
    }
}
