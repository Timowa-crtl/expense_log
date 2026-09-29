package de.timowa.expenselog;

import android.content.Context;
import androidx.preference.PreferenceManager;
import android.widget.Toast;

import java.io.File;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

import static de.timowa.expenselog.Utility.log;

class LocalBackupManager {
    private static final String LOG_TAG = "FILEZ";
    private static final String BACKUP_NAME_PREFIX = "expenseLog";
    private static final String MONTHLY_BACKUP_NAME = BACKUP_NAME_PREFIX + "MonthlyBackup" + FileHelper.database_extension;
    private static final String WEEKLY_BACKUP_NAME = BACKUP_NAME_PREFIX + "WeeklyBackup" + FileHelper.database_extension;
    private static final String DAILY_BACKUP_NAME = BACKUP_NAME_PREFIX + "DailyBackup" + FileHelper.database_extension;
    private static final String CURRENT_BACKUP_NAME = BACKUP_NAME_PREFIX + FileHelper.database_extension;

    static void checkBackups(Context context) {

        {
            // get internal database file
            File internalDatabaseDirectory = new File(context.getDatabasePath(FileHelper.dbFileName).getPath());

            // Backups live in the app's own external directory rather than a shared /Expense Log/
            // folder: no permission is needed at any API level and scoped storage does not apply,
            // so this keeps working past targetSdk 29. See FileHelper.getAppFilesDir for the
            // trade-off -- these backups do not survive an uninstall.
            File backupDirectory = FileHelper.getAppFilesDir(context);

            // save current backup
            File currentBackupFile = new File(backupDirectory, CURRENT_BACKUP_NAME);
            copyFile(context, internalDatabaseDirectory, currentBackupFile);

            // check how long since the last update
            Calendar currentDate = Calendar.getInstance();

            // if over 2 days, do 2 day backup
            File dailyBackupFile = new File(backupDirectory, DAILY_BACKUP_NAME);

            // get the last daily backup time, and increase it by the backup period (one day)
            Calendar dailyCal = Calendar.getInstance();
            dailyCal.setTimeInMillis(dailyBackupFile.lastModified());
            dailyCal.set(Calendar.DAY_OF_YEAR, dailyCal.get(Calendar.DAY_OF_YEAR) + 2);

            log(LOG_TAG, "daily file last modified: " + timestampTpDate(dailyBackupFile.lastModified()));
            log(LOG_TAG, "current time: " + currentDate.getTime());
            log(LOG_TAG, "next daily backup time: " + dailyCal.getTime());

            // if the current time is past the next backup time, backup the daily
            if (currentDate.getTimeInMillis() > dailyCal.getTimeInMillis()) {
                log(LOG_TAG, "make a daily backup");
                copyFile(context, internalDatabaseDirectory, dailyBackupFile);
            }

            long firstLaunchDate = PrefManager.getFirstLaunch(context);

            // if installed for over 1 week, do weekly backup check
            long timePassed = currentDate.getTimeInMillis() - firstLaunchDate;
            log(LOG_TAG, "time passed since first launch: " + TimeUnit.DAYS.convert(timePassed, TimeUnit.MILLISECONDS));

            if (timePassed > TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)) {
                // if over 1 week since last backup, do weekly backup
                File weeklyBackupFile = new File(backupDirectory, WEEKLY_BACKUP_NAME);
                Calendar weeklyCal = Calendar.getInstance();
                weeklyCal.setTimeInMillis(weeklyBackupFile.lastModified());
                log(LOG_TAG, "weekly last modified: " + weeklyCal.getTime());
                if (currentDate.getTimeInMillis() - weeklyCal.getTimeInMillis()
                        > TimeUnit.MILLISECONDS.convert(7, TimeUnit.DAYS)) {
                    log(LOG_TAG, "make a weekly backup");
                    copyFile(context, internalDatabaseDirectory, weeklyBackupFile);
                }


                // if installed for over 1 month, do weekly backup check
                if (timePassed > TimeUnit.MILLISECONDS.convert(30, TimeUnit.DAYS)) {
                    // if over 1 month since last backup, do monthly backup check
                    File monthlyBackupFile = new File(backupDirectory, MONTHLY_BACKUP_NAME);
                    Calendar monthlyCal = Calendar.getInstance();
                    monthlyCal.setTimeInMillis(monthlyBackupFile.lastModified());
                    log(LOG_TAG, "monthly last modified: " + monthlyCal.getTime());
                    if (currentDate.getTimeInMillis() - monthlyCal.getTimeInMillis()
                            > TimeUnit.MILLISECONDS.convert(30, TimeUnit.DAYS)) {
                        log(LOG_TAG, "make a monthly backup");
                        copyFile(context, internalDatabaseDirectory, monthlyBackupFile);
                    }

                } else {
                    log(LOG_TAG, "less than one month installed");
                }

            } else {
                log(LOG_TAG, "less than one week installed");
            }

        }
    }

    private static String timestampTpDate(long lastModified) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(lastModified);
        return cal.getTime().toString();
    }

    /**
     * Replaces one backup with a consistent copy of the live database.
     *
     * <p>Through {@link FileHelper#snapshotDatabaseTo}, which writes a temporary file and renames
     * it over the backup. This used to open the backup for writing -- truncating it -- before
     * copying, so a copy that failed part-way destroyed the previous backup as well; and it read
     * the live file while a save could be writing it. {@code source} is always the live database.
     */
    private static void copyFile(Context context, File source, File destination) {
        if (!FileHelper.snapshotDatabaseTo(context, destination)) {
            // Only ever called from NewLogFragment's save, on the main thread, so it may present.
            Toast.makeText(context,
                    context.getResources().getString(R.string.error) + " 337: " + destination.getName(),
                    Toast.LENGTH_LONG).show();
        }
    }
}
