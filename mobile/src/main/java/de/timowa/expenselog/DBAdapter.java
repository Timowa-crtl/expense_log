package de.timowa.expenselog;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.preference.PreferenceManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javax.inject.Inject;

public class DBAdapter {

    /* Building Database */

    /** Package-visible: {@code FileHelper} compares an incoming .edb's user_version against it. */
    static final int DATABASE_VERSION = 1;
    public static final String DATABASE_NAME = "expenseLog" + FileHelper.database_extension;

    // Primary Log table
    private static final String MAIN_LOG_TABLE_NAME = "mainLogs";
    static final String KEY_ROW_ID = "_id";
    public static final String KEY_LOG_TIME = "start_time"; // long (timestamp)
    static final String KEY_AMOUNT = "amount"; // real
    static final String KEY_CATEGORY_ID = "categoryId"; //int
    static final String KEY_ACCOUNT_ID = "account"; // int
    private static final String KEY_NOTES = "notes"; // string
    private static final String KEY_IMAGE_URI = "picture"; // string
    static final String KEY_EXPENSE_INCOME = "expenseIncome"; // int
    private static final String KEY_REPEATING_ID = "repeatingId"; // int
    public static final int COLUMN_LOG_ID = 0;
    public static final int COLUMN_LOG_TIME = 1;
    public static final int COLUMN_LOG_AMOUNT = 2;
    public static final int COLUMN_LOG_CATEGORY = 3;
    public static final int COLUMN_LOG_NOTES = 4;
    public static final int COLUMN_LOG_IMAGE = 5;
    public static final int COLUMN_LOG_EXPENSE_INCOME = 6;
    public static final int COLUMN_LOG_ACCOUNT = 7;
    public static final int COLUMN_LOG_REPEATING_ID = 8;
    /**
     * <b>Column order is load-bearing.</b> Every read in the app is positional
     * ({@code cursor.getString(COLUMN_LOG_NOTES)}), and every cursor method queries with a
     * {@code null} projection, so cursor order is the order below. Inserting or reordering a
     * column does not fail or warn -- it shifts every later field, and the app reads an amount
     * where it expects a category id, in existing {@code .edb} files as well as new ones.
     *
     * <p><b>Append new columns to the end, never insert.</b> The same applies to the other three
     * tables. {@code DBAdapterSchemaTest} enforces this and will fail if it is violated.
     */
    private static final String MAIN_LOG_TABLE_STRUCTURE = " ("
            + KEY_ROW_ID + " integer primary key autoincrement, "
            + KEY_LOG_TIME + " long not null, "
            + KEY_AMOUNT + " real not null, "
            + KEY_CATEGORY_ID + " integer not null, "
            + KEY_NOTES + " text, "
            + KEY_IMAGE_URI + " text, "
            + KEY_EXPENSE_INCOME + " integer,"
            + KEY_ACCOUNT_ID + " integer,"
            + KEY_REPEATING_ID + " integer);";

    private static final String CREATE_PRIMARY_LOG_TABLE = "create table " + MAIN_LOG_TABLE_NAME +
            MAIN_LOG_TABLE_STRUCTURE;

    /* Tags structure */
    // tags reference table
    private static final String KEY_TAG_EXPENSE_INCOME_TYPE = "tag_type"; // int
    private static final String KEY_TAG_LABEL = "tag_label"; // string
    private static final String KEY_TAG_ICON = "tag_icon"; // string
    static final int COLUMN_TAG_ID = 0;
    static final int COLUMN_TAG_LABEL = 1;
    static final int COLUMN_TAG_ICON = 2;
    static final int COLUMN_TAG_TYPE = 3;
    private static final String TAGS_REFERENCE_TABLE_NAME = "tagTypes";
    private static final String TAGS_REFERENCE_TABLE_STRUCTURE = " (" + KEY_ROW_ID + " integer primary key autoincrement, "
            + KEY_TAG_LABEL + " text not null, " + KEY_TAG_ICON + " text not null, " + KEY_TAG_EXPENSE_INCOME_TYPE + " integer);";
    private static final String CREATE_TAGS_REFERENCE_TABLE = "create table " + TAGS_REFERENCE_TABLE_NAME +
            TAGS_REFERENCE_TABLE_STRUCTURE;

    /* Accounts Table Structure */
    private static final String KEY_ACCOUNT_LABEL = "account_label"; // string
    static final int COLUMN_ACCOUNT_ID = 0;
    static final int COLUMN_ACCOUNT_LABEL = 1;
    private static final String ACCOUNTS_REFERENCE_TABLE_NAME = "AccountsTable";
    private static final String ACCOUNTS_REFERENCE_TABLE_STRUCTURE = " (" +
            KEY_ROW_ID + " integer primary key autoincrement, "
            + KEY_ACCOUNT_LABEL + " text not null);";
    private static final String CREATE_ACCOUNTS_REFERENCE_TABLE = "create table " + ACCOUNTS_REFERENCE_TABLE_NAME +
            ACCOUNTS_REFERENCE_TABLE_STRUCTURE;

    /* Repeating Records Table Structure */
    private static final String KEY_REPEATING_AMOUNT = "repeating_amount"; // real
    private static final String KEY_REPEATING_PERIOD = "repeating_period"; // int
    private static final String KEY_REPEATING_PERIOD_FREQUENCY = "repeating_period_frequency"; // int
    private static final String KEY_REPEATING_END_TIME = "repeating_end_time"; // long (timestamp)
    public static final int COLUMN_REPEATING_ID = 0;
    public static final int COLUMN_REPEATING_AMOUNT = 1;
    public static final int COLUMN_REPEATING_PERIOD = 2;
    public static final int COLUMN_REPEATING_PERIOD_FREQUENCY = 3;
    public static final int COLUMN_REPEATING_END_TIME = 4;
    private static final String REPEATING_REFERENCE_TABLE_NAME = "RepeatingTable";
    private static final String REPEATING_REFERENCE_TABLE_STRUCTURE = " (" +
            KEY_ROW_ID + " integer primary key autoincrement, "
            + KEY_REPEATING_AMOUNT + " real not null, "
            + KEY_REPEATING_PERIOD + " integer not null, "
            + KEY_REPEATING_PERIOD_FREQUENCY + " integer not null, "
            + KEY_REPEATING_END_TIME + " long not null);";
    private static final String CREATE_REPEATING_REFERENCE_TABLE = "create table " + REPEATING_REFERENCE_TABLE_NAME +
            REPEATING_REFERENCE_TABLE_STRUCTURE;

    public static final String KEY_DATABASE_CHANGE = "localChange";

    private final Context context;
    /** The absolute path of this adapter's database, resolved once through the caller's context. */
    private final String databasePath;

    /**
     * One open helper -- one connection -- per database file, for the whole process.
     *
     * <p>Every {@code DBAdapter} used to build its own {@code SQLiteOpenHelper} and open it in the
     * constructor. Dagger provides a new adapter for every injection and nothing closed the injected
     * ones, so the app held a separate connection per fragment, per pager page, and per calendar
     * cell, all to the same file. One shared connection is also what lets Step 5 of
     * docs/history/RELIABILITY_PLAN.md take a consistent copy of the file (a transaction on it holds off every
     * writer) and overwrite the file safely (close it, and nothing else has the file open).
     *
     * <p>Keyed by path, not held as a singleton, so the test harness's {@code test_} database is a
     * different entry from the real one.
     */
    private static final Map<String, DatabaseHelper> HELPERS = new HashMap<>();

    Utility utility;

    @Inject
    public DBAdapter(Context ctx) {
        this.context = ctx;
        // Through the caller's context: that is what keeps IsolatedDatabaseContext's redirect.
        this.databasePath = ctx.getDatabasePath(DATABASE_NAME).getAbsolutePath();
        // Opened eagerly, as before: callers such as FileHelper.stageDbForExport expect a new
        // adapter to have created the file. Only the first adapter per process pays for the open.
        db();
    }

    /**
     * The shared connection, reopened if {@link #closeSharedConnection} closed it.
     *
     * <p>Opened under the {@code HELPERS} lock, so it cannot reopen the file while
     * {@link #replaceWhileClosed} has it closed: a connection opened in that gap holds the old
     * file, which the rename then unlinks, and every later read and write goes to it.
     */
    private SQLiteDatabase db() {
        synchronized (HELPERS) {
            DatabaseHelper helper = HELPERS.get(databasePath);
            if (helper == null) {
                // The application context, so no activity is retained by the static map; the
                // absolute path as the name, so the application context cannot undo the redirect.
                helper = new DatabaseHelper(context.getApplicationContext(), databasePath);
                HELPERS.put(databasePath, helper);
            }
            return helper.getWritableDatabase();
        }
    }

    /**
     * How many times, in this process, anything has marked the database as changed.
     *
     * <p>The marker preference cannot tell a second change from the first -- both write the same
     * "changed" value -- so a Dropbox upload compares this instead to learn whether anything
     * changed while it ran.
     */
    private static final AtomicLong CHANGE_GENERATION = new AtomicLong();

    /**
     * How many times, in this process, the database file has been replaced. Undoing a delete
     * compares it, so records deleted from one file are never put back into an imported one.
     */
    private static final AtomicLong REPLACEMENTS = new AtomicLong();

    /** See {@link #CHANGE_GENERATION}. */
    public static long changeGeneration() {
        return CHANGE_GENERATION.get();
    }

    /** Counts a change made without an adapter method -- an import replacing the file. */
    static void countChange() {
        CHANGE_GENERATION.incrementAndGet();
    }

    /**
     * Closes the shared connection to {@code ctx}'s database and runs {@code swap} before anything
     * can reopen it. For replacing the database file -- an import or a restore.
     *
     * @return what {@code swap} returned
     */
    static boolean replaceWhileClosed(Context ctx, BooleanSupplier swap) {
        String path = ctx.getDatabasePath(DATABASE_NAME).getAbsolutePath();
        // The write lock first, and without holding HELPERS while it waits: a transaction still
        // running needs HELPERS for its own db() calls, and it holds the read lock until it ends.
        CONNECTION_USE.writeLock().lock();
        try {
            synchronized (HELPERS) {
                DatabaseHelper helper = HELPERS.remove(path);
                if (helper != null)
                    helper.close();
                REPLACEMENTS.incrementAndGet();
                return swap.getAsBoolean();
            }
        } finally {
            CONNECTION_USE.writeLock().unlock();
        }
    }

    /**
     * Held for reading by anything that keeps one connection object across several statements --
     * a transaction, or a snapshot's holding transaction -- and for writing by
     * {@link #replaceWhileClosed}, so the connection is never closed in the middle of one.
     *
     * <p>Without it an import on one thread closed the connection a delete or a repeating series
     * was using on another, and that thread's next statement threw {@code IllegalStateException:
     * attempt to re-open an already-closed object}. {@code ImportSafetyTest}. Taken outside
     * {@code HELPERS}, never inside it, which is what keeps it from deadlocking with {@code db()}.
     */
    private static final ReentrantReadWriteLock CONNECTION_USE = new ReentrantReadWriteLock();

    /**
     * Runs one database operation on the shared connection, under the read side of
     * {@link #CONNECTION_USE}, so an import cannot close the connection while it runs.
     *
     * <p><b>A cursor is filled before the lock is released.</b> Android cursors run their query
     * lazily, on the first {@code getCount} or move, and they do not hold the connection open in
     * between: a cursor read after an import closed the connection threw {@code IllegalStateException:
     * Cannot perform this operation because the connection pool has been closed} -- on the records
     * list's and the calendar's background threads, killing the app. A filled cursor needs the
     * database again only for a result bigger than one cursor window (about 2 MB), which no query in
     * this app comes near. {@code ImportSafetyTest}.
     */
    private <T> T read(java.util.function.Function<SQLiteDatabase, T> operation) {
        CONNECTION_USE.readLock().lock();
        try {
            T result = operation.apply(db());
            if (result instanceof Cursor)
                ((Cursor) result).getCount();
            return result;
        } finally {
            CONNECTION_USE.readLock().unlock();
        }
    }

    /**
     * As {@link #read}, and counts a change <em>after</em> the write lands. {@code databaseChange()}
     * counts one before it, but a write can then wait behind a snapshot: an upload that read the
     * counter in between would see it unchanged and declare a change it never uploaded synced.
     * {@code ChangeGenerationTest}.
     */
    private <T> T write(java.util.function.Function<SQLiteDatabase, T> operation) {
        try {
            return read(operation);
        } finally {
            CHANGE_GENERATION.incrementAndGet();
        }
    }

    /** For a caller outside this class that holds one connection across statements; see above. */
    static Lock connectionUseLock() {
        return CONNECTION_USE.readLock();
    }

    /**
     * The shared connection to {@code ctx}'s database if one is <b>already open</b>, else null.
     *
     * <p>Never opens anything, and that is the point: opening a corrupt file through
     * {@code SQLiteOpenHelper} makes Android's default error handler delete it. A caller that only
     * wants to hold writers off while it copies the file -- {@code FileHelper.snapshotDatabaseTo} --
     * must not be the thing that destroys the file it came to copy.
     */
    static SQLiteDatabase peekSharedConnection(Context ctx) {
        String path = ctx.getDatabasePath(DATABASE_NAME).getAbsolutePath();
        synchronized (HELPERS) {
            DatabaseHelper helper = HELPERS.get(path);
            return helper == null ? null : helper.peek();
        }
    }

    /**
     * Closes the shared connection to {@code ctx}'s database, if one is open.
     *
     * <p><b>Only for the test harness</b>; replacing the file goes through
     * {@link #replaceWhileClosed}. Any adapter's next call reopens it. Everything else calls
     * {@link #close()}, which deliberately does nothing.
     */
    static void closeSharedConnection(Context ctx) {
        replaceWhileClosed(ctx, () -> true);
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context, String absolutePath) {
            super(context, absolutePath, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(CREATE_PRIMARY_LOG_TABLE);
            db.execSQL(CREATE_TAGS_REFERENCE_TABLE);
            db.execSQL(CREATE_ACCOUNTS_REFERENCE_TABLE);
            db.execSQL(CREATE_REPEATING_REFERENCE_TABLE);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            migrate(db, oldVersion, newVersion);
        }

        @Override
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Refuse rather than proceed. This override used to be empty, which SUPPRESSED
            // SQLiteOpenHelper's own downgrade guard and let the app open a database written by a
            // newer schema as though it were current -- reading columns that may have moved or
            // that do not exist. Callers that can recover (an .edb import) must catch this and
            // restore their backup; see FileHelper.importFileAndVerify.
            throw new SQLiteException("Cannot open database written by a newer version of the app"
                    + " (found v" + oldVersion + ", this build understands v" + newVersion + ")."
                    + " Downgrading is not supported.");
        }

        /** The open database, for {@link #peekSharedConnection}; null until opened and after close. */
        private volatile SQLiteDatabase openDatabase;

        @Override
        public void onOpen(SQLiteDatabase db) {
            db.disableWriteAheadLogging();  // Here the solution
            super.onOpen(db);
            openDatabase = db;
        }

        @Override
        public synchronized void close() {
            openDatabase = null;
            super.close();
        }

        SQLiteDatabase peek() {
            SQLiteDatabase db = openDatabase;
            return db != null && db.isOpen() ? db : null;
        }
    }

    /**
     * Applies every migration step between two schema versions, in order.
     *
     * <p>Steps are registered one version apart and chained, so a v1 database opened by a build at
     * v3 runs 1&rarr;2 then 2&rarr;3. Registering a step per version rather than per version-pair
     * is what keeps this from becoming an N&times;M matrix.
     *
     * <p><b>An unregistered step throws.</b> That is deliberate and is the whole point of this
     * method. {@code onUpgrade} used to be empty, so raising {@link #DATABASE_VERSION} without
     * writing a migration would have left the *old* schema in place while the app believed it had
     * the new one -- reads would silently return the wrong column, which is the worst available
     * outcome for a file holding financial records. Failing to open is recoverable; quiet
     * corruption is not.
     *
     * <p>Safe to throw from: {@link SQLiteOpenHelper} runs {@code onUpgrade} inside a transaction
     * and only calls {@code setVersion} after it returns, so a failed migration rolls back and the
     * database keeps its original version rather than being left half-migrated.
     *
     * <p><b>When adding a migration</b>, in one commit: add its {@code case} below, raise
     * {@link #DATABASE_VERSION}, update {@code databaseVersion_isStillOneAndHasNoMigrationYet} in
     * {@code DBAdapterSchemaTest}, and extend {@code DBAdapterMigrationTest} with a fixture at the
     * old version. Append new columns to the *end* of a {@code CREATE TABLE} -- see the note on
     * {@link #MAIN_LOG_TABLE_STRUCTURE}.
     */
    static void migrate(SQLiteDatabase db, int oldVersion, int newVersion) {
        for (int from = oldVersion; from < newVersion; from++) {
            applyMigrationStep(db, from);
        }
    }

    /** Applies the single step taking the schema from {@code from} to {@code from + 1}. */
    private static void applyMigrationStep(SQLiteDatabase db, int from) {
        switch (from) {
            // No migrations exist yet -- DATABASE_VERSION is still 1 and every .edb in the wild is
            // version 1. Add cases here as the schema changes, e.g.
            //
            //     case 1: // v1 -> v2
            //         db.execSQL("ALTER TABLE " + MAIN_LOG_TABLE_NAME + " ADD COLUMN ...");
            //         break;
            default:
                throw new IllegalStateException("No migration registered for schema v" + from
                        + " -> v" + (from + 1) + ". DATABASE_VERSION was raised without one being"
                        + " written; see DBAdapter.migrate.");
        }
    }

    // OPEN DATABASE
    public DBAdapter open() throws SQLException {
        db();
        return this;
    }

    /**
     * Does nothing, on purpose: the connection is shared by every adapter in the process and lives
     * as long as it does. Closing it here used to be harmless because each adapter had its own; now
     * it would pull the connection out from under every other screen and thread. See
     * {@link #closeSharedConnection}.
     */
    public void close() {
    }

    // return db
    SQLiteDatabase getDB() {
        return db();
    }

    // verify database
    boolean verifyDatabase() {
        boolean verified = true;
        try {
            Cursor tempC = read(d -> d.rawQuery("SELECT " + KEY_TAG_EXPENSE_INCOME_TYPE + " FROM " + TAGS_REFERENCE_TABLE_NAME, null));
            tempC.close();
        } catch (SQLException e) {
            verified = false;
        }
        return verified;
    }

    private void databaseChange() {
        if (BuildConfig.DEBUG)
            Log.i(DropBoxHelper.DROPBOX_TAG, "databaseChange");
        CHANGE_GENERATION.incrementAndGet();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(context.getResources().getString(R.string.pref_key_current_dropbox_db_version), KEY_DATABASE_CHANGE).apply();
    }

    //
    // Primary Log Management
    // **********************************************************************
    boolean newLog(long timeStamp, int expenseIncome, int accountId,
                   double amount, int categoryId, String notes, String imageUri, int repeatingId) {
        databaseChange();
        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_LOG_TIME, timeStamp);
        initialValues.put(KEY_AMOUNT, amount);
        initialValues.put(KEY_ACCOUNT_ID, accountId);
        initialValues.put(KEY_CATEGORY_ID, categoryId);
        initialValues.put(KEY_NOTES, notes);
        initialValues.put(KEY_IMAGE_URI, imageUri);
        initialValues.put(KEY_EXPENSE_INCOME, expenseIncome);
        initialValues.put(KEY_REPEATING_ID, repeatingId);
        return write(d -> d.insert(MAIN_LOG_TABLE_NAME, null, initialValues)) != -1;
    }

    boolean updateLog(int logId, long logTime, int expenseIncome, int accountId,
                      double amount, int selectedTagId, String notes, String imageUri, int repeatingId) {
        databaseChange();
        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_LOG_TIME, logTime);
        initialValues.put(KEY_AMOUNT, amount);
        initialValues.put(KEY_ACCOUNT_ID, accountId);
        initialValues.put(KEY_CATEGORY_ID, selectedTagId);
        initialValues.put(KEY_NOTES, notes);
        initialValues.put(KEY_IMAGE_URI, imageUri);
        initialValues.put(KEY_EXPENSE_INCOME, expenseIncome);
        initialValues.put(KEY_REPEATING_ID, repeatingId);
        return write(d -> d.update(MAIN_LOG_TABLE_NAME, initialValues, KEY_ROW_ID + "=" + logId, null)) > 0;
    }

    public Cursor getAllLogs() {
        return read(d -> d.query(MAIN_LOG_TABLE_NAME, null, null, null, null, null, KEY_LOG_TIME));
    }

    public long getTotalNumberOfLogs() {
        String selectQuery = "SELECT COUNT(*) FROM " + MAIN_LOG_TABLE_NAME;
        Cursor mCount = read(d -> d.rawQuery(selectQuery, null));
        long count = 0;
        if (mCount != null) {
            mCount.moveToFirst();
            count = mCount.getLong(0);
            mCount.close();
        }
        return count;
    }

    Cursor getLog(String logId) {
        return read(d -> d.query(MAIN_LOG_TABLE_NAME, null, KEY_ROW_ID + "=" + logId, null, null, null, null));
    }

    public Cursor getLogsInRange(long start, long end, String columnToSort, String filter) {
        if (filter == null)
            filter = "";
        String selection = filter + "(" + KEY_LOG_TIME + " BETWEEN " + start + " AND " + end + ")";
        return read(d -> d.query(MAIN_LOG_TABLE_NAME, null, selection, null, null, null,
                columnToSort));
    }

    /** How many records {@link #getLogsInRange} would return, without reading any of them. */
    long countLogsInRange(long start, long end, String filter) {
        if (filter == null)
            filter = "";
        String selection = filter + "(" + KEY_LOG_TIME + " BETWEEN " + start + " AND " + end + ")";
        return read(d -> DatabaseUtils.queryNumEntries(d, MAIN_LOG_TABLE_NAME, selection));
    }

    public double getTotalForRange(long dayStart, long dayEnd, String recordsFilter) {
        if (recordsFilter == null)
            recordsFilter = "";

        // get total expenses
        String expensesFilter = recordsFilter + KEY_EXPENSE_INCOME + " = 0 AND ";
        double expenses = getSumForRange(dayStart, dayEnd, DBAdapter.KEY_AMOUNT, expensesFilter);

        // get total income
        String incomeFilter = recordsFilter + KEY_EXPENSE_INCOME + " = 1 AND ";
        double income = getSumForRange(dayStart, dayEnd, DBAdapter.KEY_AMOUNT, incomeFilter);

        return income - expenses;
    }

    Cursor getAllUniqueCategoryIdsInRange(long start, long end, String filter) {
        if (filter == null)
            filter = "";
        String selection = filter + "(" + KEY_LOG_TIME + " BETWEEN " + start + " AND " + end + ")";

        // join tables and sort by category label
        return read(d -> d.query(true,
                MAIN_LOG_TABLE_NAME + " INNER JOIN " + TAGS_REFERENCE_TABLE_NAME + " on "
                        + MAIN_LOG_TABLE_NAME + "." + KEY_CATEGORY_ID + "=" + TAGS_REFERENCE_TABLE_NAME + "." + KEY_ROW_ID,
                new String[]{KEY_CATEGORY_ID, KEY_TAG_LABEL, KEY_TAG_ICON, KEY_TAG_EXPENSE_INCOME_TYPE},
                selection, null, null, null, KEY_TAG_LABEL + " COLLATE NOCASE ASC", null));
    }

    double getSumForRange(long rangeStart, long rangeEnd, String column, String filter) {
        String selection = filter + "(" + KEY_LOG_TIME + " BETWEEN " + rangeStart + " AND " + rangeEnd + ")";
        String rawQueryVar = "SELECT SUM(" + column + ") FROM " + MAIN_LOG_TABLE_NAME
                + " WHERE " + selection;
        Cursor mTotal = read(d -> d.rawQuery(rawQueryVar, null));
        double total = 0;
        if (mTotal.moveToFirst()) {
            total = mTotal.getDouble(0);
            mTotal.close();
        }
        return total;
    }

    // get specified list of logs
    Cursor getLogs(String selection, String order) {
        return read(d -> d.query(MAIN_LOG_TABLE_NAME, null, selection, null, null, null, order));
    }

    /**
     * Delete specified log and return true if successful
     */
    boolean deleteLog(int logId) {
        databaseChange();
        return write(d -> d.delete(MAIN_LOG_TABLE_NAME, KEY_ROW_ID + " = " + logId, null)) != 0;
    }

    /**
     * Records removed by {@link #deleteLogs}, every column as it was stored, id included, so that
     * {@link #restoreLogs} can put them back exactly -- a repeating entry keeps its series.
     */
    static final class DeletedLogs {
        final List<ContentValues> rows;
        /** {@link #REPLACEMENTS} when they were deleted; a restore into another file is refused. */
        final long replacement;
        /** The series' entry as it was before a series delete changed or dropped it; else null. */
        ContentValues seriesEntry;
        /** Whether that delete dropped the entry, rather than only moving its end. */
        boolean seriesDropped;
        /**
         * Every series the deleted records belonged to, as the delete left it (null if it had no
         * entry). An Undo puts records back into a series only if it is still exactly like this:
         * a series changed in between -- a new interval, amount or end -- keeps the change, and
         * the records come back as plain ones rather than mixing two schedules.
         */
        final Map<Long, ContentValues> seriesAfter = new HashMap<>();

        DeletedLogs(List<ContentValues> rows, long replacement) {
            this.rows = rows;
            this.replacement = replacement;
        }

        int size() {
            return rows.size();
        }
    }

    /**
     * Deletes the given records in one transaction, and only those: a repeating entry is removed
     * on its own, as "only this entry" does, and its series is left alone.
     */
    DeletedLogs deleteLogs(Collection<Integer> logIds) {
        if (logIds.isEmpty())
            return new DeletedLogs(new ArrayList<>(), REPLACEMENTS.get());
        StringBuilder ids = new StringBuilder();
        for (int id : logIds)
            ids.append(ids.length() == 0 ? "" : ",").append(id);
        return deleteKeeping(KEY_ROW_ID + " IN (" + ids + ")", null);
    }

    /**
     * Deletes a series' records from {@code fromTime} on -- all of them for
     * {@code Long.MIN_VALUE} -- and ends the series before them, or drops it if nothing is left
     * or all of it was asked for. What it removed and the series' entry as it was are kept, so
     * {@link #restoreLogs} can undo it.
     *
     * @return what was deleted, or null, with nothing written, for a cut of a series that does
     * not exist
     */
    DeletedLogs deleteSeriesRecords(int repeatingId, long fromTime) {
        boolean whole = fromTime == Long.MIN_VALUE;
        String selection = whole ? KEY_REPEATING_ID + " = " + repeatingId : recordsFrom(repeatingId, fromTime);
        return deleteKeeping(selection, (db, deleted) -> {
            try (Cursor c = db.query(REPEATING_REFERENCE_TABLE_NAME, null, KEY_ROW_ID + " = " + repeatingId,
                    null, null, null, null)) {
                if (c.moveToFirst())
                    deleted.seriesEntry = rowValues(c);
            }
            // A whole series' records are deleted even without an entry, as they always were.
            return whole || deleted.seriesEntry != null;
        }, (db, deleted) -> {
            if (whole) {
                db.delete(REPEATING_REFERENCE_TABLE_NAME, KEY_ROW_ID + " = " + repeatingId, null);
                deleted.seriesDropped = true;
            } else if (dropIfEmpty(db, repeatingId)) {
                deleted.seriesDropped = true;
            } else {
                setSeriesEntry(db, repeatingId, endBefore(db, repeatingId, fromTime));
            }
        });
    }

    private interface BeforeDelete {
        /** False refuses the delete: nothing is written and the caller gets null. */
        boolean prepare(SQLiteDatabase db, DeletedLogs deleted);
    }

    private interface AfterDelete {
        void finish(SQLiteDatabase db, DeletedLogs deleted);
    }

    private DeletedLogs deleteKeeping(String selection, AfterDelete after) {
        return deleteKeeping(selection, (db, deleted) -> true, after);
    }

    /** Deletes the records matching {@code selection} in one transaction, keeping every column. */
    private DeletedLogs deleteKeeping(String selection, BeforeDelete before, AfterDelete after) {
        CONNECTION_USE.readLock().lock();
        boolean written = false;
        try {
            DeletedLogs deleted = new DeletedLogs(new ArrayList<>(), REPLACEMENTS.get());
            SQLiteDatabase db = db();
            db.beginTransaction();
            try {
                if (!before.prepare(db, deleted))
                    return null;
                keepRows(db, selection, deleted);
                db.delete(MAIN_LOG_TABLE_NAME, selection, null);
                if (after != null)
                    after.finish(db, deleted);
                for (ContentValues row : deleted.rows) {
                    Long series = row.getAsLong(KEY_REPEATING_ID);
                    if (series != null && series > -1 && !deleted.seriesAfter.containsKey(series))
                        deleted.seriesAfter.put(series, seriesRow(db, series));
                }
                db.setTransactionSuccessful();
                written = true;
            } catch (SeriesRollback refused) {
                return null;
            } finally {
                db.endTransaction();
            }
            databaseChange();
            return deleted;
        } finally {
            // After the transaction, for the same reason as write(); not for a refused delete.
            if (written)
                CHANGE_GENERATION.incrementAndGet();
            CONNECTION_USE.readLock().unlock();
        }
    }

    /** Adds every record matching {@code selection} to {@code deleted}, every column as stored. */
    private static void keepRows(SQLiteDatabase db, String selection, DeletedLogs deleted) {
        try (Cursor c = db.query(MAIN_LOG_TABLE_NAME, null, selection, null, null, null, KEY_ROW_ID)) {
            while (c.moveToNext())
                deleted.rows.add(rowValues(c));
        }
    }

    /**
     * A new record of what a series change deletes, if a caller wants one for an Undo; else null,
     * so nothing is copied. Called inside the transaction, under {@code CONNECTION_USE}, so
     * {@link #REPLACEMENTS} is read where a replacement cannot slip in between -- as in
     * {@link #deleteKeeping}.
     */
    private static DeletedLogs keepingFor(Consumer<DeletedLogs> onRemoved) {
        return onRemoved == null ? null : new DeletedLogs(new ArrayList<>(), REPLACEMENTS.get());
    }

    /** Every column of the cursor's row, in its stored type -- a text round trip would round amounts. */
    private static ContentValues rowValues(Cursor c) {
        ContentValues values = new ContentValues();
        for (int i = 0; i < c.getColumnCount(); i++) {
            String column = c.getColumnName(i);
            switch (c.getType(i)) {
                case Cursor.FIELD_TYPE_NULL:
                    values.putNull(column);
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    values.put(column, c.getLong(i));
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    values.put(column, c.getDouble(i));
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    values.put(column, c.getBlob(i));
                    break;
                default:
                    values.put(column, c.getString(i));
            }
        }
        return values;
    }

    /**
     * Puts back records {@link #deleteLogs} removed, under their own ids, in one transaction.
     *
     * <p>A record whose category or account has been deleted since stays deleted -- putting it
     * back would leave it pointing at a row that no longer exists. One whose series has gone is
     * restored as a plain record.
     *
     * @return how many records were restored; -1, and nothing written, if the database file has
     * been replaced (an import or a Dropbox download) since they were deleted
     */
    int restoreLogs(DeletedLogs deleted) {
        if (deleted.rows.isEmpty() && deleted.seriesEntry == null)
            return 0;
        CONNECTION_USE.readLock().lock();
        try {
            if (REPLACEMENTS.get() != deleted.replacement)
                return -1;
            databaseChange();
            SQLiteDatabase db = db();
            int restored = 0;
            db.beginTransaction();
            try {
                Map<Long, Boolean> attach = new HashMap<>();
                restoreSeriesEntry(db, deleted, attach);
                for (ContentValues original : deleted.rows) {
                    ContentValues row = new ContentValues(original);
                    if (!rowExists(db, TAGS_REFERENCE_TABLE_NAME, row.getAsLong(KEY_CATEGORY_ID)))
                        continue;
                    Long account = row.getAsLong(KEY_ACCOUNT_ID);
                    if (account != null && !rowExists(db, ACCOUNTS_REFERENCE_TABLE_NAME, account))
                        continue;
                    Long series = row.getAsLong(KEY_REPEATING_ID);
                    if (series != null && series > -1) {
                        if (!attach.containsKey(series)) {
                            ContentValues now = seriesRow(db, series);
                            attach.put(series, now != null && now.equals(deleted.seriesAfter.get(series)));
                        }
                        if (!attach.get(series))
                            row.put(KEY_REPEATING_ID, -1);
                    }
                    // AUTOINCREMENT never hands a deleted id out again, so the old one is free.
                    if (db.insertWithOnConflict(MAIN_LOG_TABLE_NAME, null, row, SQLiteDatabase.CONFLICT_IGNORE) == -1) {
                        row.remove(KEY_ROW_ID);
                        if (db.insert(MAIN_LOG_TABLE_NAME, null, row) == -1)
                            continue;
                    }
                    restored++;
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
                // After the transaction, for the same reason as write(); not for a refused restore.
                CHANGE_GENERATION.incrementAndGet();
            }
            return restored;
        } finally {
            CONNECTION_USE.readLock().unlock();
        }
    }

    /**
     * Puts a series' entry back as it was before a series delete: re-created if the delete dropped
     * it, its end and values reset if the delete only cut it -- but only if nothing has changed the
     * series since. Records {@code attach} for the series either way.
     */
    private static void restoreSeriesEntry(SQLiteDatabase db, DeletedLogs deleted, Map<Long, Boolean> attach) {
        if (deleted.seriesEntry == null)
            return;
        Long id = deleted.seriesEntry.getAsLong(KEY_ROW_ID);
        ContentValues now = seriesRow(db, id);
        if (deleted.seriesDropped) {
            // AUTOINCREMENT never reuses the id, so a row there would be a surprise; leave it.
            boolean back = now == null && db.insertWithOnConflict(REPEATING_REFERENCE_TABLE_NAME, null,
                    deleted.seriesEntry, SQLiteDatabase.CONFLICT_IGNORE) != -1;
            attach.put(id, back);
        } else if (now != null && now.equals(deleted.seriesAfter.get(id))) {
            ContentValues values = new ContentValues(deleted.seriesEntry);
            values.remove(KEY_ROW_ID);
            db.update(REPEATING_REFERENCE_TABLE_NAME, values, KEY_ROW_ID + " = " + id, null);
            attach.put(id, true);
        } else {
            // deleted or changed since: the change stands, and the records come back plain
            attach.put(id, false);
        }
    }

    /** The series' entry, every column as stored, or null if it has none. */
    private static ContentValues seriesRow(SQLiteDatabase db, long id) {
        try (Cursor c = db.query(REPEATING_REFERENCE_TABLE_NAME, null, KEY_ROW_ID + " = " + id,
                null, null, null, null)) {
            return c.moveToFirst() ? rowValues(c) : null;
        }
    }

    private static boolean rowExists(SQLiteDatabase db, String table, Long id) {
        return id != null && DatabaseUtils.queryNumEntries(db, table, KEY_ROW_ID + " = " + id) > 0;
    }

    //
    // Tag Management
    //**********************************************************************************

    int newTag(String tagLabel, int expenseIncomeType, String icon) {
        databaseChange();
        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_TAG_LABEL, tagLabel);
        initialValues.put(KEY_TAG_EXPENSE_INCOME_TYPE, expenseIncomeType);
        initialValues.put(KEY_TAG_ICON, icon);
        return write(d -> d.insert(TAGS_REFERENCE_TABLE_NAME, null, initialValues)).intValue();
    }

    // get list of tags
    Cursor getTags() {
        return read(d -> d.query(TAGS_REFERENCE_TABLE_NAME, null, null, null, null, null, KEY_TAG_LABEL + " COLLATE NOCASE ASC"));
    }

    Cursor getTagsOfType(boolean expense) {
        int expenseIncome = expense ? 1 : 0;

        return read(d -> d.query(TAGS_REFERENCE_TABLE_NAME, null,
                KEY_TAG_EXPENSE_INCOME_TYPE + "=" + expenseIncome, null, null, null, KEY_TAG_LABEL + " COLLATE NOCASE ASC"));
    }

    // delete a tag
    void deleteTag(long tagId) {
        databaseChange();
        // Both statements or neither: a crash between them used to leave records pointing at a
        // row that no longer exists, counted by the totals but dropped by the summary's join.
        // One connection object for the whole transaction, which an import cannot close meanwhile.
        CONNECTION_USE.readLock().lock();
        try {
            SQLiteDatabase db = db();
            db.beginTransaction();
            try {
                // delete the tag
                db.delete(TAGS_REFERENCE_TABLE_NAME, KEY_ROW_ID + " = " + tagId, null);

                // delete all records with that tag id
                db.delete(MAIN_LOG_TABLE_NAME, KEY_CATEGORY_ID + " = " + tagId, null);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            // After the transaction, for the same reason as write().
            CHANGE_GENERATION.incrementAndGet();
            CONNECTION_USE.readLock().unlock();
        }
    }

    // get a tag
    Cursor getTag(int tagId) {
        Cursor tagEntries;
        tagEntries = read(d -> d.query(TAGS_REFERENCE_TABLE_NAME, null, KEY_ROW_ID + "=" + tagId, null, null, null, null));
        return tagEntries;
    }

    /**
     * return the timestamp for the most recent record for the given tag. returns 0 if no records exist for that tag
     */
    long getMostRecentRecordForCategory(int categoryId) {
        try (Cursor recentLog = read(d -> d.query(MAIN_LOG_TABLE_NAME, new String[]{KEY_LOG_TIME},
                KEY_CATEGORY_ID + "=" + categoryId, null, null, null, KEY_LOG_TIME + " DESC", "1"))) {
            return recentLog.moveToFirst() ? recentLog.getLong(0) : 0;
        }
    }

    /**
     * The ids of every expense or income category, most recently used first.
     *
     * <p>Categories with no records follow, alphabetically -- the order {@link #getTagsOfType}
     * returns them in. One grouped query: this used to be a query per category for its latest
     * record, plus a query per category to read back its label, on every Expense/Income toggle,
     * and the first of those cursors was never closed.
     */
    List<Integer> getTagIdsByRecency(boolean expense) {
        Map<Integer, Long> latest = new HashMap<>();
        try (Cursor c = read(d -> d.rawQuery("SELECT " + KEY_CATEGORY_ID + ", MAX(" + KEY_LOG_TIME + ") FROM "
                + MAIN_LOG_TABLE_NAME + " GROUP BY " + KEY_CATEGORY_ID, null))) {
            while (c.moveToNext())
                latest.put(c.getInt(0), c.getLong(1));
        }

        List<long[]> idAndSortKey = new ArrayList<>();
        try (Cursor tags = getTagsOfType(expense)) {
            int counter = 0;
            while (tags.moveToNext()) {
                int id = tags.getInt(COLUMN_TAG_ID);
                Long time = latest.get(id);
                // Unused categories get 1000 - position, as they always did: below any real
                // timestamp, and descending in alphabetical order.
                long key = time != null && time > 0 ? time : 1000 - counter;
                idAndSortKey.add(new long[]{id, key});
                counter++;
            }
        }
        // Stable, so equal keys keep alphabetical order.
        idAndSortKey.sort((a, b) -> Long.compare(b[1], a[1]));
        List<Integer> ids = new ArrayList<>();
        for (long[] entry : idAndSortKey)
            ids.add((int) entry[0]);
        return ids;
    }

    String getCategoryLabel(Integer categoryId) {
        try (Cursor categoryCursor = read(d -> d.query(TAGS_REFERENCE_TABLE_NAME,
                new String[]{KEY_TAG_LABEL}, KEY_ROW_ID + "=" + categoryId, null, null, null, null))) {
            return categoryCursor.moveToFirst() ? categoryCursor.getString(0) : "";
        }
    }

    void updateTag(int tagId, String newTagLabel, int expenseIncomeType, String icon) {
        databaseChange();
        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_TAG_LABEL, newTagLabel);
        initialValues.put(KEY_TAG_EXPENSE_INCOME_TYPE, expenseIncomeType);
        initialValues.put(KEY_TAG_ICON, icon);
        write(d -> d.update(TAGS_REFERENCE_TABLE_NAME, initialValues, KEY_ROW_ID + "=" + tagId, null));
    }

    boolean doesTagExist(String newTagString, int expenseIncomeValue) {
        if (newTagString.equals(""))
            return false;

        Cursor tagEntries;
        String[] args = {newTagString, "" + expenseIncomeValue};
        tagEntries = read(d -> d.query(TAGS_REFERENCE_TABLE_NAME, null,
                KEY_TAG_LABEL + "=? COLLATE NOCASE AND " +
                        KEY_TAG_EXPENSE_INCOME_TYPE + "=?", args, null, null, null));
        if (tagEntries != null && tagEntries.getCount() > 0) {
            tagEntries.close();
            return true;
        } else {
            if (tagEntries != null) {
                tagEntries.close();
            }
            return false;
        }
    }

    //
    // Account Management
    //***********************************************************************

    public Cursor getAccounts() {
        return read(d -> d.query(ACCOUNTS_REFERENCE_TABLE_NAME, null, null, null, null, null,
                KEY_ACCOUNT_LABEL + " COLLATE NOCASE ASC"));
    }

    Cursor getAccount(int accountId) {
        Cursor account;
        account = read(d -> d.query(ACCOUNTS_REFERENCE_TABLE_NAME, null, KEY_ROW_ID + "=" + accountId, null, null, null, null));
        return account;
    }

    String getAccountLabel(int accountId) {
        try (Cursor account = read(d -> d.query(ACCOUNTS_REFERENCE_TABLE_NAME,
                new String[]{KEY_ACCOUNT_LABEL}, KEY_ROW_ID + "=" + accountId, null, null, null, null))) {
            return account.moveToFirst() ? account.getString(0) : "";
        }
    }

    /** The category's label, or an empty string when no category has this id. */
    String getTagLabel(int tagId) {
        String label = "";
        try (Cursor tag = read(d -> d.query(TAGS_REFERENCE_TABLE_NAME, new String[]{KEY_TAG_LABEL},
                KEY_ROW_ID + "=" + tagId, null, null, null, null))) {
            if (tag.moveToFirst())
                label = tag.getString(0);
        }
        return label;
    }

    void deleteAccount(int accountId) {
        databaseChange();
        // Both statements or neither: a crash between them used to leave records pointing at a
        // row that no longer exists, counted by the totals but dropped by the summary's join.
        // One connection object for the whole transaction, which an import cannot close meanwhile.
        CONNECTION_USE.readLock().lock();
        try {
            SQLiteDatabase db = db();
            db.beginTransaction();
            try {
                // delete account name
                db.delete(ACCOUNTS_REFERENCE_TABLE_NAME, KEY_ROW_ID + " = " + accountId, null);

                // delete all records with this account id
                db.delete(MAIN_LOG_TABLE_NAME, KEY_ACCOUNT_ID + " = " + accountId, null);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } finally {
            // After the transaction, for the same reason as write().
            CHANGE_GENERATION.incrementAndGet();
            CONNECTION_USE.readLock().unlock();
        }
    }

    long newAccount(String account) {
        databaseChange();
        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_ACCOUNT_LABEL, account);
        return write(d -> d.insert(ACCOUNTS_REFERENCE_TABLE_NAME, null, initialValues));
    }

    void updateAccount(int id, String account) {
        databaseChange();
        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_ACCOUNT_LABEL, account);
        write(d -> d.update(ACCOUNTS_REFERENCE_TABLE_NAME, initialValues, KEY_ROW_ID + "=" + id, null));
    }

    //
    // Repeating Log Management
    //******************************************************************************
    /** The series' own entry only; {@link #createRepeatingSeries} is what the app calls. */
    int newRepeatingRecord(double amount, int repeatingPeriodFrequency, int repeatingPeriod, long frequencyEndTime) {
        ContentValues initialValues = new ContentValues();
        initialValues.put(KEY_REPEATING_AMOUNT, amount);
        initialValues.put(KEY_REPEATING_PERIOD_FREQUENCY, repeatingPeriodFrequency);
        initialValues.put(KEY_REPEATING_PERIOD, repeatingPeriod);
        initialValues.put(KEY_REPEATING_END_TIME, frequencyEndTime);
        return write(d -> d.insert(REPEATING_REFERENCE_TABLE_NAME, null, initialValues)).intValue();
    }

    public Cursor getRepeatingEntry(int repeatingId) {
        return read(d -> d.query(REPEATING_REFERENCE_TABLE_NAME, null, KEY_ROW_ID + "=" + repeatingId, null, null, null, null));
    }

    /** Deletes a whole series, its entry included; see {@link #deleteSeriesRecords}. */
    void deleteRepeatingLogs(int repeatingId) {
        deleteSeriesRecords(repeatingId, Long.MIN_VALUE);
    }

    /** A series operation refused: its records do not lie on one schedule. See {@link RepeatingSeries#findOrigin}. */
    static final int SERIES_NO_SCHEDULE = -2;
    /** A series operation refused: it would exceed {@link #MAX_REPEATING_OCCURRENCES}. */
    static final int SERIES_TOO_LONG = -3;

    /** Aborts a {@link #inSeriesTransaction} with a result code, rolling back everything it wrote. */
    private static final class SeriesRollback extends RuntimeException {
        final int code;

        SeriesRollback(int code) {
            super(null, null, false, false);
            this.code = code;
        }
    }

    /**
     * Runs a series operation as one transaction on the shared connection -- all of it or none.
     * {@code body} throws {@link SeriesRollback} to refuse; its code is returned and nothing is
     * written. Marks the database changed only if the transaction commits.
     */
    private int inSeriesTransaction(java.util.function.ToIntFunction<SQLiteDatabase> body) {
        // One connection object for the whole transaction, which an import cannot close meanwhile.
        CONNECTION_USE.readLock().lock();
        try {
            SQLiteDatabase db = db();
            db.beginTransaction();
            try {
                int result = body.applyAsInt(db);
                db.setTransactionSuccessful();
                databaseChange();
                return result;
            } catch (SeriesRollback refused) {
                return refused.code;
            } finally {
                db.endTransaction();
            }
        } finally {
            // After the transaction, for the same reason as write().
            CHANGE_GENERATION.incrementAndGet();
            CONNECTION_USE.readLock().unlock();
        }
    }

    /** The series and its records, or null if it has no {@code RepeatingTable} entry. */
    RepeatingSeries getRepeatingSeries(int repeatingId) {
        return read(d -> readSeries(d, repeatingId));
    }

    /**
     * Every series that still has a record, for the recurring overview. A series whose records
     * were all deleted one by one keeps its {@code RepeatingTable} entry, and is left out.
     */
    List<RepeatingSeries> getAllRepeatingSeries() {
        return read(d -> {
            List<Integer> ids = new ArrayList<>();
            try (Cursor c = d.query(REPEATING_REFERENCE_TABLE_NAME, new String[]{KEY_ROW_ID},
                    null, null, null, null, KEY_ROW_ID)) {
                while (c.moveToNext())
                    ids.add(c.getInt(0));
            }
            List<RepeatingSeries> all = new ArrayList<>();
            for (int id : ids) {
                RepeatingSeries series = readSeries(d, id);
                if (series != null && series.size() > 0)
                    all.add(series);
            }
            return all;
        });
    }

    private static RepeatingSeries readSeries(SQLiteDatabase db, int repeatingId) {
        double amount;
        int period, frequency;
        long endTime;
        try (Cursor c = db.query(REPEATING_REFERENCE_TABLE_NAME, null, KEY_ROW_ID + "=" + repeatingId,
                null, null, null, null)) {
            if (!c.moveToFirst())
                return null;
            amount = c.getDouble(COLUMN_REPEATING_AMOUNT);
            period = c.getInt(COLUMN_REPEATING_PERIOD);
            frequency = c.getInt(COLUMN_REPEATING_PERIOD_FREQUENCY);
            endTime = c.getLong(COLUMN_REPEATING_END_TIME);
        }
        try (Cursor c = db.query(MAIN_LOG_TABLE_NAME, null, KEY_REPEATING_ID + "=" + repeatingId,
                null, null, null, KEY_LOG_TIME + ", " + KEY_ROW_ID)) {
            int[] ids = new int[c.getCount()];
            long[] times = new long[c.getCount()];
            LogItem latest = null;
            for (int i = 0; c.moveToNext(); i++) {
                ids[i] = c.getInt(COLUMN_LOG_ID);
                times[i] = c.getLong(COLUMN_LOG_TIME);
                if (c.isLast()) {
                    latest = new LogItem();
                    latest.setId(ids[i]);
                    latest.setTimeStamp(times[i]);
                    latest.setAmount(c.getDouble(COLUMN_LOG_AMOUNT));
                    latest.setCategory(c.getInt(COLUMN_LOG_CATEGORY));
                    latest.setNotes(c.getString(COLUMN_LOG_NOTES) == null ? "" : c.getString(COLUMN_LOG_NOTES));
                    latest.setImageUri(c.getString(COLUMN_LOG_IMAGE));
                    latest.setExpenseIncome(c.getInt(COLUMN_LOG_EXPENSE_INCOME));
                    latest.setAccountId(c.getInt(COLUMN_LOG_ACCOUNT));
                    latest.setRepeatingId(repeatingId);
                }
            }
            RepeatingSeries series = new RepeatingSeries(repeatingId, amount, period, frequency,
                    endTime, ids, times);
            series.latest = latest;
            return series;
        }
    }

    /** The values a series writes onto its records -- everything but the time. */
    private static ContentValues seriesValues(LogItem values, int repeatingId) {
        ContentValues v = new ContentValues();
        v.put(KEY_AMOUNT, values.getAmount());
        v.put(KEY_ACCOUNT_ID, values.getAccountId());
        v.put(KEY_CATEGORY_ID, values.getCategory());
        v.put(KEY_NOTES, values.getNotes());
        v.put(KEY_IMAGE_URI, values.getImageUri());
        v.put(KEY_EXPENSE_INCOME, values.getExpenseIncome());
        v.put(KEY_REPEATING_ID, repeatingId);
        return v;
    }

    private static String recordsFrom(int repeatingId, long fromTime) {
        return KEY_REPEATING_ID + " = " + repeatingId + " AND " + KEY_LOG_TIME + " >= " + fromTime;
    }

    private static void setSeriesEntry(SQLiteDatabase db, int repeatingId, ContentValues entry) {
        if (db.update(REPEATING_REFERENCE_TABLE_NAME, entry, KEY_ROW_ID + " = " + repeatingId, null) != 1)
            throw new SeriesRollback(-1);
    }

    private static ContentValues seriesEntry(double amount, int frequency, int period, long endTime) {
        ContentValues entry = new ContentValues();
        entry.put(KEY_REPEATING_AMOUNT, amount);
        entry.put(KEY_REPEATING_PERIOD_FREQUENCY, frequency);
        entry.put(KEY_REPEATING_PERIOD, period);
        entry.put(KEY_REPEATING_END_TIME, endTime);
        return entry;
    }

    /**
     * The end for a series cut at {@code fromTime}: the last millisecond of the day before, so
     * the end date it shows is the day its last record can fall on -- but never before a record
     * it keeps. An end is exclusive, so any value in between leaves the same records.
     */
    private static ContentValues endBefore(SQLiteDatabase db, int repeatingId, long fromTime) {
        Calendar day = Calendar.getInstance();
        day.setTimeInMillis(fromTime);
        day.set(Calendar.HOUR_OF_DAY, 0);
        day.set(Calendar.MINUTE, 0);
        day.set(Calendar.SECOND, 0);
        day.set(Calendar.MILLISECOND, 0);
        long end = day.getTimeInMillis() - 1;
        try (Cursor c = db.rawQuery("SELECT MAX(" + KEY_LOG_TIME + ") FROM " + MAIN_LOG_TABLE_NAME
                + " WHERE " + KEY_REPEATING_ID + " = " + repeatingId + " AND " + KEY_LOG_TIME + " < " + fromTime, null)) {
            if (c.moveToFirst() && !c.isNull(0))
                end = Math.max(end, c.getLong(0) + 1);
        }
        ContentValues entry = new ContentValues();
        entry.put(KEY_REPEATING_END_TIME, Math.min(end, fromTime));
        return entry;
    }

    /** Drops the series' entry if no record carries its id any more; true if it did. */
    private static boolean dropIfEmpty(SQLiteDatabase db, int repeatingId) {
        if (DatabaseUtils.queryNumEntries(db, MAIN_LOG_TABLE_NAME, KEY_REPEATING_ID + " = " + repeatingId) > 0)
            return false;
        db.delete(REPEATING_REFERENCE_TABLE_NAME, KEY_ROW_ID + " = " + repeatingId, null);
        return true;
    }

    /**
     * Inserts occurrences {@code fromK}, {@code fromK + 1}, ... of the schedule at
     * {@code origin}, skipping any before {@code notBefore}, until one reaches {@code endTime}.
     * Refuses the whole transaction if that would pass {@link #MAX_REPEATING_OCCURRENCES}.
     *
     * @return how many were inserted
     */
    private static int insertOccurrences(SQLiteDatabase db, long origin, long fromK, int frequency,
                                         int period, long notBefore, long endTime, ContentValues values) {
        if (passesCeiling(origin, fromK, frequency, period, endTime))
            throw new SeriesRollback(SERIES_TOO_LONG);
        int inserted = 0;
        for (long k = fromK; ; k++) {
            long time = repeatingOccurrence(origin, (int) k, frequency, period);
            if (time >= endTime)
                return inserted;
            if (time < notBefore)
                continue;
            values.put(KEY_LOG_TIME, time);
            if (db.insert(MAIN_LOG_TABLE_NAME, null, values) == -1)
                throw new SeriesRollback(-1);
            inserted++;
        }
    }

    /**
     * Whether occurrences from {@code fromK} of the schedule at {@code origin} would pass
     * {@link #MAX_REPEATING_OCCURRENCES} before reaching {@code endTime} -- the refusal
     * {@link #insertOccurrences} makes, shared so {@link #occurrencesAddedBy} predicts it.
     */
    private static boolean passesCeiling(long origin, long fromK, int frequency, int period, long endTime) {
        return fromK + MAX_REPEATING_OCCURRENCES + 1 > Integer.MAX_VALUE
                || repeatingOccurrence(origin, (int) fromK + MAX_REPEATING_OCCURRENCES + 1, frequency, period) < endTime;
    }

    /**
     * Moves a series' end, inside a transaction already open on {@code db}.
     *
     * <p>Earlier: the records from the new end on are deleted. Later: the occurrences between the
     * old end and the new one are added, with the latest record's values, computed from the origin
     * {@link RepeatingSeries#findOrigin} recovers -- never stepped from the last record. Records
     * the user deleted one by one before the old end stay deleted. A series with no origin is
     * refused with {@link #SERIES_NO_SCHEDULE} if there is anything to add.
     *
     * @param removed if not null, filled with what an earlier end deleted and the series' entry
     *                as it was, so {@link #restoreLogs} can undo it
     */
    private static int applySeriesEnd(SQLiteDatabase db, int repeatingId, long newEnd, DeletedLogs removed) {
        RepeatingSeries series = readSeries(db, repeatingId);
        if (series == null)
            throw new SeriesRollback(-1);
        if (removed != null) {
            removed.seriesEntry = seriesRow(db, repeatingId);
            keepRows(db, recordsFrom(repeatingId, newEnd), removed);
        }
        int changed = db.delete(MAIN_LOG_TABLE_NAME, recordsFrom(repeatingId, newEnd), null);
        if (series.size() > 0 && newEnd > series.endTime) {
            RepeatingSeries.Origin origin = series.findOrigin();
            // Without an origin, an end that cannot reach another occurrence still only moves
            // the stored end; anything more is refused rather than guessed.
            if (origin == null && !nothingBetween(series, newEnd))
                throw new SeriesRollback(SERIES_NO_SCHEDULE);
            if (origin != null) {
                ContentValues values = seriesValues(series.latest, repeatingId);
                changed += insertOccurrences(db, origin.time, origin.lastK + 1, series.frequency,
                        series.period, series.endTime, newEnd, values);
            }
        }
        ContentValues entry = new ContentValues();
        entry.put(KEY_REPEATING_END_TIME, newEnd);
        setSeriesEntry(db, repeatingId, entry);
        boolean dropped = dropIfEmpty(db, repeatingId);
        if (removed != null) {
            removed.seriesDropped = dropped;
            removed.seriesAfter.put((long) repeatingId, dropped ? null : seriesRow(db, repeatingId));
        }
        return changed;
    }

    /**
     * How many records moving {@code series}' end to {@code newEnd} would add, as
     * {@link #applySeriesEnd} computes them; -1 if it cannot tell, because the series has no
     * schedule to extend. 0 for an end that is not later. More than
     * {@link #MAX_REPEATING_OCCURRENCES} for an end the save would refuse as too long.
     */
    static int occurrencesAddedBy(RepeatingSeries series, long newEnd) {
        if (series.size() == 0 || newEnd <= series.endTime)
            return 0;
        RepeatingSeries.Origin origin = series.findOrigin();
        if (origin == null)
            return nothingBetween(series, newEnd) ? 0 : -1;
        if (passesCeiling(origin.time, origin.lastK + 1, series.frequency, series.period, newEnd))
            return MAX_REPEATING_OCCURRENCES + 1;
        // Occurrences only move later as k grows, so what insertOccurrences adds -- those from
        // lastK + 1 at or after the old end and before the new one -- is the gap between the
        // first at or after each end. Found by bisection: this runs on the main thread, before
        // the save dialog, and a walk could take 20 000 steps. passesCeiling said the new end
        // is reached by the last k searched.
        int lo = (int) origin.lastK + 1;
        int hi = lo + MAX_REPEATING_OCCURRENCES + 1;
        return firstAtOrAfter(origin.time, lo, hi, series.frequency, series.period, newEnd)
                - firstAtOrAfter(origin.time, lo, hi, series.frequency, series.period, series.endTime);
    }

    /** The first k in [lo, hi] whose occurrence is at or after {@code time}; hi's must be. */
    private static int firstAtOrAfter(long origin, int lo, int hi, int frequency, int period, long time) {
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (repeatingOccurrence(origin, mid, frequency, period) >= time)
                hi = mid;
            else
                lo = mid + 1;
        }
        return lo;
    }

    /**
     * Whether moving the end to {@code newEnd} cannot add an occurrence: the new end is no later
     * than the old one or than one period after the last record.
     */
    private static boolean nothingBetween(RepeatingSeries series, long newEnd) {
        long last = series.times[series.size() - 1];
        long earliestNext = Math.max(series.endTime,
                repeatingOccurrence(last, 1, series.frequency, series.period));
        return newEnd <= earliestNext;
    }

    /**
     * Ends a series at {@code fromTime}: deletes its records from then on and ends it before them.
     * Drops the series if nothing is left of it.
     *
     * @return how many records were deleted, or -1 if the series does not exist
     */
    int deleteRepeatingFrom(int repeatingId, long fromTime) {
        DeletedLogs deleted = deleteSeriesRecords(repeatingId, fromTime);
        return deleted == null ? -1 : deleted.size();
    }

    /**
     * Moves a series' end; see {@link #applySeriesEnd}.
     *
     * @return how many records were added or deleted, or -1, {@link #SERIES_NO_SCHEDULE} or
     * {@link #SERIES_TOO_LONG} with nothing written
     */
    int setRepeatingEnd(int repeatingId, long newEnd) {
        return setRepeatingEnd(repeatingId, newEnd, null);
    }

    /**
     * As above; {@code onRemoved}, if not null, gets what an earlier end deleted -- once the
     * change is written, and only if it deleted something -- for an Undo.
     */
    int setRepeatingEnd(int repeatingId, long newEnd, Consumer<DeletedLogs> onRemoved) {
        DeletedLogs[] removed = new DeletedLogs[1];
        int result = inSeriesTransaction(db -> applySeriesEnd(db, repeatingId, newEnd,
                removed[0] = keepingFor(onRemoved)));
        return reportRemoved(result, removed[0], onRemoved);
    }

    private static int reportRemoved(int result, DeletedLogs removed, Consumer<DeletedLogs> onRemoved) {
        if (result >= 0 && removed != null && removed.size() > 0)
            onRemoved.accept(removed);
        return result;
    }

    /**
     * Gives a series' records from {@code fromTime} on new values -- everything but their dates --
     * and optionally a new end. Their dates are not recomputed, so this needs no origin unless
     * the end moves later.
     *
     * <p>From the series' first record on, the series is changed in place. From a later record,
     * the series is split: those records move to a new series with the same schedule, and the old
     * one ends before them. That is what keeps a raise from rewriting the salary already
     * paid, and lets either part be changed again on its own.
     *
     * @return the id of the series now holding the changed records, or -1,
     * {@link #SERIES_NO_SCHEDULE} or {@link #SERIES_TOO_LONG} with nothing written
     */
    int updateRepeatingFrom(int repeatingId, long fromTime, LogItem values, long newEnd) {
        return updateRepeatingFrom(repeatingId, fromTime, values, newEnd, null);
    }

    /** As above; {@code onRemoved} as for {@link #setRepeatingEnd(int, long, Consumer)}. */
    int updateRepeatingFrom(int repeatingId, long fromTime, LogItem values, long newEnd,
                            Consumer<DeletedLogs> onRemoved) {
        DeletedLogs[] removed = new DeletedLogs[1];
        int result = inSeriesTransaction(db -> {
            removed[0] = keepingFor(onRemoved);
            RepeatingSeries series = readSeries(db, repeatingId);
            if (series == null || series.size() == 0)
                throw new SeriesRollback(-1);
            int target = repeatingId;
            if (fromTime > series.times[0]) {
                target = (int) db.insert(REPEATING_REFERENCE_TABLE_NAME, null, seriesEntry(
                        values.getAmount(), series.frequency, series.period, series.endTime));
                if (target == -1)
                    throw new SeriesRollback(-1);
                setSeriesEntry(db, repeatingId, endBefore(db, repeatingId, fromTime));
            } else {
                ContentValues amount = new ContentValues();
                amount.put(KEY_REPEATING_AMOUNT, values.getAmount());
                setSeriesEntry(db, repeatingId, amount);
            }
            db.update(MAIN_LOG_TABLE_NAME, seriesValues(values, target),
                    recordsFrom(repeatingId, fromTime), null);
            // After the update, on purpose: records an earlier end deletes are kept with the new
            // values, so an Undo brings them back as members of the series as it now is, not
            // with values that would mix two amounts in one series.
            if (newEnd != series.endTime)
                applySeriesEnd(db, target, newEnd, removed[0]);
            return target;
        });
        // removed[0] is filled by the transaction above, so it is read only after it
        return reportRemoved(result, removed[0], onRemoved);
    }

    /**
     * Replaces a series' records from {@code fromTime} on with a new schedule starting at
     * {@code first} -- for a changed interval or date, where the old dates no longer apply.
     * {@code first}'s own record is included, so {@code first}'s time need not be
     * {@code fromTime}: that is how a date is moved.
     *
     * <p>From the series' first record on, the series keeps its id; otherwise the old part ends before
     * {@code fromTime} and the new schedule becomes a new series.
     *
     * @return the id of the series holding the new schedule, or -1 or {@link #SERIES_TOO_LONG}
     * with nothing written
     */
    int restartRepeatingFrom(int repeatingId, long fromTime, LogItem first, int frequency,
                             int period, long endTime) {
        if (frequency <= 0)
            return -1;
        return inSeriesTransaction(db -> {
            RepeatingSeries series = readSeries(db, repeatingId);
            if (series == null)
                throw new SeriesRollback(-1);
            db.delete(MAIN_LOG_TABLE_NAME, recordsFrom(repeatingId, fromTime), null);
            ContentValues entry = seriesEntry(first.getAmount(), frequency, period, endTime);
            int target;
            if (DatabaseUtils.queryNumEntries(db, MAIN_LOG_TABLE_NAME, KEY_REPEATING_ID + " = " + repeatingId) == 0) {
                target = repeatingId;
                setSeriesEntry(db, target, entry);
            } else {
                target = (int) db.insert(REPEATING_REFERENCE_TABLE_NAME, null, entry);
                if (target == -1)
                    throw new SeriesRollback(-1);
                setSeriesEntry(db, repeatingId, endBefore(db, repeatingId, fromTime));
            }
            ContentValues values = seriesValues(first, target);
            values.put(KEY_LOG_TIME, first.getTimeStamp());
            if (db.insert(MAIN_LOG_TABLE_NAME, null, values) == -1)
                throw new SeriesRollback(-1);
            insertOccurrences(db, first.getTimeStamp(), 1, frequency, period, Long.MIN_VALUE,
                    endTime, values);
            return target;
        });
    }

    /**
     * The most occurrences one series may have. A 50-year daily series is 18 263, so no real
     * series comes near it; it exists so that no stepping bug can ever insert without end again.
     */
    static final int MAX_REPEATING_OCCURRENCES = 20_000;

    /**
     * Creates a repeating series: its {@code RepeatingTable} entry and every occurrence after
     * {@code originalTime} that falls before {@code frequencyEndTime}. The record at
     * {@code originalTime} itself is not included; the caller saves it.
     *
     * <p>All or nothing, in one transaction. It used to insert one auto-committed row at a time,
     * so a failure part-way left half a series, and every row paid for its own journal commit.
     *
     * @return the new repeating id, or -1 if nothing was written -- the series would exceed
     * {@link #MAX_REPEATING_OCCURRENCES}, or an insert failed
     */
    int createRepeatingSeries(long originalTime, long frequencyEndTime, double amount,
                              int repeatingPeriodFrequency, int repeatingPeriod, int expenseIncome,
                              int accountId, int categoryId, String notes, String imageUri) {
        // Refused before anything is written. A frequency of 0 ("00" passes the caller's "0"
        // check) never advances, and occurrences only grow with k otherwise, so one lookup says
        // whether the cap would be hit -- instead of inserting 20 000 rows on the main thread and
        // rolling them back.
        if (repeatingPeriodFrequency <= 0 || repeatingOccurrence(originalTime,
                MAX_REPEATING_OCCURRENCES + 1, repeatingPeriodFrequency, repeatingPeriod) < frequencyEndTime) {
            Log.e(DropBoxHelper.DROPBOX_TAG, "repeating series refused: frequency "
                    + repeatingPeriodFrequency + " or more than " + MAX_REPEATING_OCCURRENCES + " occurrences");
            return -1;
        }
        // One connection object for the whole transaction, which an import cannot close meanwhile.
        CONNECTION_USE.readLock().lock();
        try {
            SQLiteDatabase db = db();
            db.beginTransaction();
            try {
                int repeatingId = newRepeatingRecord(amount, repeatingPeriodFrequency, repeatingPeriod,
                        frequencyEndTime);
                if (repeatingId == -1)
                    return -1;

                ContentValues values = new ContentValues();
                values.put(KEY_AMOUNT, amount);
                values.put(KEY_ACCOUNT_ID, accountId);
                values.put(KEY_CATEGORY_ID, categoryId);
                values.put(KEY_NOTES, notes);
                values.put(KEY_IMAGE_URI, imageUri);
                values.put(KEY_EXPENSE_INCOME, expenseIncome);
                values.put(KEY_REPEATING_ID, repeatingId);

                for (int k = 1; ; k++) {
                    long time = repeatingOccurrence(originalTime, k, repeatingPeriodFrequency, repeatingPeriod);
                    if (time >= frequencyEndTime)
                        break;
                    if (k > MAX_REPEATING_OCCURRENCES) {
                        Log.e(DropBoxHelper.DROPBOX_TAG, "repeating series abandoned: more than "
                                + MAX_REPEATING_OCCURRENCES + " occurrences");
                        return -1;
                    }
                    values.put(KEY_LOG_TIME, time);
                    if (db.insert(MAIN_LOG_TABLE_NAME, null, values) == -1)
                        return -1;
                }
                db.setTransactionSuccessful();
                databaseChange();
                return repeatingId;
            } finally {
                db.endTransaction();
            }
        } finally {
            // After the transaction, for the same reason as write().
            CHANGE_GENERATION.incrementAndGet();
            CONNECTION_USE.readLock().unlock();
        }
    }

    /**
     * The {@code k}th occurrence of a series that started at {@code originalTime}.
     *
     * <p><b>Always computed from the original, never from the previous occurrence.</b> The old
     * stepping did {@code set(field, get(field) + n)} on the previous date, which had two bugs.
     * Weekly: late December is often week 1 of the next year while the year field is still the
     * old one, so the step jumped back to January of the same year and the loop never ended.
     * Monthly: 31 Jan + 1 month normalised to 3 Mar, and every later month stayed on the 3rd.
     * {@code Calendar.add} rolls years over and clamps to the end of a short month, and adding
     * {@code k} periods to the original lands 31 Jan + 2 months back on 31 Mar.
     * {@code RepeatingScheduleTest}.
     *
     * <p>{@code k} may be negative: {@link RepeatingSeries#findOrigin} computes a series from one
     * of its later records, and a date before it is the same arithmetic backwards.
     *
     * @param period 0 day, 1 week, 2 month, 3 year -- the frequency spinner's positions
     */
    static long repeatingOccurrence(long originalTime, int k, int frequency, int period) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(originalTime);
        // In long arithmetic: in int, a large weekly frequency wrapped 7 * k * frequency negative,
        // and the series inserted a record millions of years in the past. An amount that does
        // not fit Calendar.add's int is that far in the future, so it is past any end time.
        long amount = (long) k * frequency * (period == 1 ? 7 : 1);
        if (amount > Integer.MAX_VALUE)
            return Long.MAX_VALUE;
        if (amount < Integer.MIN_VALUE)
            return Long.MIN_VALUE;
        switch (period) {
            case 2:
                cal.add(Calendar.MONTH, (int) amount);
                break;
            case 3:
                cal.add(Calendar.YEAR, (int) amount);
                break;
            default:
                // 0 day, and 1 week as seven days
                cal.add(Calendar.DATE, (int) amount);
                break;
        }
        long time = cal.getTimeInMillis();
        // An occurrence can only come after the original. One that does not has overflowed the
        // calendar's millisecond range -- a yearly frequency beyond about 292 million fits an int
        // but not a date -- and is just as far past any end time.
        return k > 0 && frequency > 0 && time <= originalTime ? Long.MAX_VALUE : time;
    }
}
