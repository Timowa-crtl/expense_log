package de.timowa.expenselog;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;

/**
 * Redirects every filesystem and SharedPreferences access to a test-only location.
 *
 * <p>This is a safety harness, not a convenience. {@link DBAdapter#DATABASE_NAME} is a fixed
 * constant and {@code DBAdapter}'s constructor calls {@code getWritableDatabase()} immediately, so
 * an instrumentation test that passed the real application context would open the user's live
 * database and write test rows into it. Instrumentation runs against the installed app and its
 * real data directory, so on a device in daily use that means real financial records.
 *
 * <p>{@code DBAdapter.databaseChange()} additionally writes the Dropbox revision key to the
 * default {@code SharedPreferences} on every insert, update, and delete, so preferences are
 * redirected for the same reason.
 *
 * <p><b>The rule is every filesystem accessor {@link FileHelper} can reach, not just the database
 * ones.</b> The first version of this class covered only {@code getDatabasePath} and
 * {@code openOrCreateDatabase}, and that was not enough: {@code FileHelper.getAppFilesDir} resolves
 * through {@code getExternalFilesDir}, falling back to {@code getFilesDir}, and
 * {@code importFileAndVerify} stages through {@code getCacheDir}. Any of the three left unwrapped
 * puts test writes in the real {@code Android/data/de.timowa.expenselog/files} — where
 * {@code backupDbToSd} would copy the test database over the user's own
 * {@code ExpenseLogBackup.edb}. That is finding 2 of {@code docs/history/REVIEW_FINDINGS.md}, and it is the
 * reason a test run used to destroy the backup that a failed import needs. Adding a call to a
 * context accessor that is not overridden here reopens it.
 *
 * <p>The one accessor that used to escape regardless of what was overridden here was
 * {@code FileHelper.getInternalDbFile}, which below API 28 assembled the database path as a string
 * from {@code Environment.getDataDirectory()} instead of asking the context. Step 2 removed that
 * branch, so the database path now comes through {@code getDatabasePath} on every API level.
 *
 * <p>Every test must build its {@code DBAdapter} from one of these. Passing a raw context is a
 * data-loss bug, not a style problem.
 */
public class IsolatedDatabaseContext extends ContextWrapper {

    /** Prefix applied to every database and preferences file name. */
    static final String PREFIX = "test_";

    /** Directory under the real cache dir that holds every redirected directory. */
    private static final String SANDBOX = "test_sandbox";

    public IsolatedDatabaseContext(Context base) {
        super(base);
    }

    @Override
    public File getDatabasePath(String name) {
        return super.getDatabasePath(PREFIX + name);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                                               SQLiteDatabase.CursorFactory factory) {
        return super.openOrCreateDatabase(PREFIX + name, mode, factory);
    }

    @Override
    public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                                               SQLiteDatabase.CursorFactory factory,
                                               DatabaseErrorHandler errorHandler) {
        return super.openOrCreateDatabase(PREFIX + name, mode, factory, errorHandler);
    }

    /**
     * Closes the process-wide shared connection before deleting, so the next test opens a fresh
     * file. Since Step 4 of docs/history/RELIABILITY_PLAN.md every {@code DBAdapter} shares one connection per
     * file and {@code close()} does nothing; deleting the file under that open connection would
     * leave the next test writing into the unlinked old one.
     */
    @Override
    public boolean deleteDatabase(String name) {
        if (DBAdapter.DATABASE_NAME.equals(name))
            DBAdapter.closeSharedConnection(this);
        return super.deleteDatabase(PREFIX + name);
    }

    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        return super.getSharedPreferences(PREFIX + name, mode);
    }

    /**
     * The destination {@code FileHelper.getAppFilesDir} resolves first, and the one that matters
     * most: unwrapped, it is the directory holding {@code ExpenseLogBackup.edb}.
     */
    @Override
    public File getExternalFilesDir(String type) {
        return sandbox(type == null ? "external_files" : "external_files_" + type);
    }

    /** {@code getAppFilesDir}'s fallback when external storage is unavailable. */
    @Override
    public File getFilesDir() {
        return sandbox("files");
    }

    /** Where {@code importFileAndVerify} stages the candidate before validating it. */
    @Override
    public File getCacheDir() {
        return sandbox("cache");
    }

    /**
     * Returns a named directory inside the sandbox, creating it. Rooted at the real cache dir via
     * {@code super}, so it is disposable, private to the app, and cannot recurse through the
     * override above.
     */
    private File sandbox(String name) {
        File dir = new File(new File(super.getCacheDir(), SANDBOX), name);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }
}
