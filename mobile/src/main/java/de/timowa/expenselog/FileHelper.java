package de.timowa.expenselog;

import androidx.appcompat.app.AlertDialog.Builder;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.content.SharedPreferences;
import android.net.Uri;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.function.Consumer;

import static de.timowa.expenselog.DropBoxHelper.DROPBOX_TAG;

public class FileHelper {

    final static String dbFileName = DBAdapter.DATABASE_NAME;
    public static final String database_extension = ".edb";
    private final static String dbBackupFileName = "ExpenseLogBackup" + database_extension;
    public final static String dbDropBoxBackupFileName = "ExpenseLogDropBoxBackup" + database_extension;
    final static String appSDfolderName = "Expense Log";

    // No csvFileName constant any more. The export names its file per range, in
    // SpreadsheetHelper.exportFileName, so that consecutive exports stop overwriting each other and
    // the shared file actually carries a .csv extension.
    final static String externalBackupPath = appSDfolderName + "/" + dbBackupFileName;
    final static String externalFolder = appSDfolderName;
    /** The subfolder of {@link #getAppFilesDir} exports are staged in. See {@link #getExportStagingDir}. */
    private final static String EXPORT_STAGING_FOLDER = "exports";
    private static File internalFile = null;
    private static File externalFile = null;
    static File externalFileFolder = null;

    //    @Inject
//    public FileHelper(Context mainContext) {
//
////        this.activityComponent = DaggerActivityComponent.builder()
////                .appComponent(((MyLogApplication) getApplication()).getComponent())
////                .activityModule(new ActivityModule(this))
////                .build();
////        activityComponent.injectActivity(this);
//    }
////
////    @Override
////    public ActivityComponent getComponent() {
////        return activityComponent;
////    }

    /**
     * Prepares {@link #internalFile} (the live database) and {@link #externalFile} (a copy the app
     * is about to write) inside the app's own directory.
     *
     * <p>Was rooted at {@code Environment.getExternalStorageDirectory()} with an
     * {@code sd.canWrite()} guard. That whole approach stops working once
     * {@code requestLegacyExternalStorage} is no longer honoured, so the destination moved to
     * {@link #getAppFilesDir} — which needs no permission and no availability check. Only the file
     * name of {@code folderFile} is used now; its directory part is ignored.
     */
    static boolean fileSetup(Context baseContext, String folderFile) {
        boolean clean = false;

        try {
            internalFile = getInternalDbFile(baseContext);
            externalFileFolder = getAppFilesDir(baseContext);
            externalFile = new File(externalFileFolder, new File(folderFile).getName());

            if (internalFile.exists()) {
                clean = true;
            } else {
                Log.e(DROPBOX_TAG, "error 87: no database at " + internalFile);
            }
        } catch (Exception e) {
            Log.e(DROPBOX_TAG, "error 87: could not resolve the database and its copy", e);
        }

        return clean;
    }

    /**
     * Builds the intent that asks the system for an {@code .edb} to import.
     *
     * <p>Replaces a hand-rolled dialog that listed {@code .edb} files found in a shared
     * {@code /Expense Log/} folder. That folder is unreadable under scoped storage, so the list
     * would simply have come back empty — an import feature that silently offers nothing. The
     * system picker needs no storage permission, reaches Drive and Dropbox and anywhere else the
     * user keeps a backup, and hands back a {@code content://} URI that
     * {@link #importFileAndVerify} already knows how to read.
     *
     * <p>The caller launches this through its own {@code ActivityResultLauncher} and passes the
     * resulting URI to {@link #confirmAndImport}.
     *
     * <p>The MIME type is {@code *}/{@code *} deliberately: {@code .edb} has no registered type, so
     * most providers report {@code application/octet-stream} while some report nothing at all, and
     * a narrower filter greys out the very file the user is looking for.
     */
    static Intent createImportIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        return intent;
    }

    /** The CSV export's MIME type. */
    static final String CSV_MIME_TYPE = "text/csv";

    /**
     * The database export's MIME type.
     *
     * <p>{@code .edb} has no registered type, so this is the honest answer -- the same reasoning
     * that makes {@link #createImportIntent} ask for {@code *}/{@code *}. It replaces
     * {@code "file/db"}, which is not a MIME type at all: nothing matches it, so it narrowed the
     * old share sheet to whatever happened to accept anything.
     */
    static final String DB_MIME_TYPE = "application/octet-stream";

    /**
     * Builds the intent that asks the user where to put a file the app has just written.
     *
     * <p>The mirror of {@link #createImportIntent}, and the reason it exists: export used to hand
     * the file to {@code ACTION_SEND}, which is a <em>share sheet</em> -- a list of apps that
     * registered to receive a file of this type. Saving to the device is not something an app
     * registers for, it is what the Storage Access Framework does, so local storage could never
     * appear in that list however the intent was tuned. {@code ACTION_CREATE_DOCUMENT} asks the
     * question the user is actually asking, and reaches Drive, Dropbox and anywhere else a
     * document provider goes as well.
     *
     * <p>{@code EXTRA_TITLE} is the only say the app has in the file's name; the user may change
     * it in the picker, and whatever they settle on is what the returned URI names.
     *
     * <p>The caller launches this through its own {@code ActivityResultLauncher} and passes the
     * resulting URI to {@link #copyStagedFileTo}.
     */
    static Intent createExportIntent(String fileName, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        return intent;
    }

    /**
     * Builds the intent that offers a finished export to another app.
     *
     * <p>The other half of the choice {@link #createExportIntent} serves. Save answers "where
     * should this file live"; share answers "who should get it". Neither can do the other's job:
     * a share sheet lists apps registered to receive a file and cannot put one on the device, and
     * the document picker has no notion of sending anything to anybody.
     *
     * <p>{@code fileName} goes in as the subject as well as the title, deliberately. Some targets
     * name the saved file from the content URI's display name and some from the subject; making
     * both the same string is what gets a {@code .csv} or {@code .edb} on the end whichever rule
     * the target follows.
     *
     * <p>No {@code grantUriPermission} loop. {@code createChooser} propagates
     * {@code FLAG_GRANT_READ_URI_PERMISSION} to whichever target the user actually picks, which is
     * the mechanism that was doing the work all along -- finding 9 of
     * {@code docs/history/REVIEW_FINDINGS.md}.
     */
    static Intent createShareIntent(Context baseContext, File staged, String mimeType,
                                    String fileName) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType(mimeType);
        share.putExtra(Intent.EXTRA_SUBJECT, fileName);
        share.putExtra(Intent.EXTRA_TITLE, fileName);
        share.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(baseContext,
                baseContext.getApplicationContext().getPackageName() + ".provider", staged));
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(share, null);
    }

    /**
     * Remembers where an export is staged while the picker is in front of the user.
     *
     * <p>In {@code SharedPreferences} rather than a field, because the picker is another activity
     * and the app can be killed behind it. A static field would come back null and the export
     * would silently do nothing -- the user picks a destination and no file ever appears. Follows
     * {@code NotificationPermission}'s flag: the key is a string resource, never a literal.
     */
    static void rememberPendingExport(Context baseContext, File staged) {
        PreferenceManager.getDefaultSharedPreferences(baseContext)
                .edit()
                .putString(baseContext.getString(R.string.pref_key_pending_export_path),
                        staged.getAbsolutePath())
                .apply();
    }

    /**
     * Returns the staged export and forgets it, so a stale path cannot be copied twice.
     *
     * @return the staged file, or null if nothing is pending
     */
    static File takePendingExport(Context baseContext) {
        String key = baseContext.getString(R.string.pref_key_pending_export_path);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(baseContext);
        String path = prefs.getString(key, null);
        prefs.edit().remove(key).apply();
        return path == null ? null : new File(path);
    }

    /**
     * Deletes the staged export the app is still holding, if there is one.
     *
     * <p>The one cleanup rule, called from two places: when a Save picker comes back cancelled,
     * and at the start of every export. The second is what bounds a share -- a shared file cannot
     * be deleted when its sheet closes, because the receiving app reads the {@code content://} URI
     * on its own schedule, so it outlives its share and the next export clears it. At most one
     * leftover exists at a time.
     *
     * <p>Two conditions keep it away from anything the app means to keep, and it takes both:
     * <ul>
     *   <li><b>A recorded path</b>, never a pattern and never a listing. Deciding what to delete by
     *   name puts every file in a folder one bad predicate away from deletion -- finding 2 of
     *   {@code docs/history/REVIEW_FINDINGS.md} is what that looks like when it happens. It is
     *   also how {@link #importFileAndVerify} has always cleaned up after itself.</li>
     *   <li><b>Inside {@link #getExportStagingDir}</b>, checked here rather than assumed. A recorded
     *   path alone was not enough: exports used to be staged as {@code expenseLog.edb} in the files
     *   root, which is {@code LocalBackupManager}'s current backup, so every export deleted it. The
     *   check also refuses such a path recorded by that older build. {@code ExportStagingTest}.</li>
     * </ul>
     * A refused path is logged and forgotten, and the file is left alone.
     */
    static void discardPendingExport(Context baseContext) {
        File staged = takePendingExport(baseContext);
        if (staged == null || !staged.exists()) {
            return;
        }
        if (!isStagedExport(baseContext, staged)) {
            Log.w(DROPBOX_TAG, "not deleting " + staged + ": it is outside the export staging folder");
            return;
        }
        if (!staged.delete()) {
            Log.w(DROPBOX_TAG, "export cancelled but the staging copy is still at " + staged);
        }
    }

    /**
     * Whether {@code file} sits directly in {@link #getExportStagingDir}, the only place cleanup
     * may delete from.
     *
     * <p>Canonical paths, not absolute ones: {@code getExternalFilesDir} can come back through a
     * symlinked {@code /sdcard}, and a raw string comparison would then refuse every legitimate
     * delete and strand every staged file.
     */
    private static boolean isStagedExport(Context baseContext, File file) {
        try {
            File parent = file.getCanonicalFile().getParentFile();
            return parent != null
                    && parent.equals(getExportStagingDir(baseContext).getCanonicalFile());
        } catch (IOException e) {
            Log.w(DROPBOX_TAG, "could not resolve " + file + "; treating it as not staged", e);
            return false;
        }
    }

    /**
     * Copies a staged export into the document the user picked, then removes the staging copy.
     *
     * <p>Reports rather than presents, like the rest of this class: a {@code Toast} here would
     * throw on any caller without a Looper. The caller decides what to say.
     *
     * <p>The staging file is deleted only when the copy succeeded. A failed copy leaves it alone
     * -- there is nothing to be gained by destroying the one complete copy of the export at the
     * moment writing it somewhere else has just failed.
     *
     * @return true if every byte reached {@code target}
     */
    static boolean copyStagedFileTo(Context baseContext, File staged, Uri target) {
        if (staged == null || target == null) {
            Log.e(DROPBOX_TAG, "export failed: nothing staged, or no destination picked");
            return false;
        }
        try (InputStream in = new FileInputStream(staged);
             OutputStream out = baseContext.getContentResolver().openOutputStream(target)) {
            if (out == null) {
                Log.e(DROPBOX_TAG, "export failed: could not open " + target + " for writing");
                return false;
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (Exception e) {
            Log.e(DROPBOX_TAG, "export failed while copying " + staged + " to " + target, e);
            return false;
        }

        if (!isStagedExport(baseContext, staged)) {
            // A path recorded before exports had a folder of their own, and possibly a backup.
            Log.w(DROPBOX_TAG, "export succeeded; not deleting " + staged
                    + ": it is outside the export staging folder");
        } else if (!staged.delete()) {
            // Not a failure of the export -- the bytes are already at their destination. Worth a
            // line, because the leftover is somewhere no file manager can reach to clean up.
            Log.w(DROPBOX_TAG, "export succeeded but the staging copy is still at " + staged);
        }
        return true;
    }

    /**
     * Confirms the overwrite, then imports the picked document.
     *
     * <p>Always reads through {@link ContentResolver}: the SAF picker returns a {@code content://}
     * URI, and its path component is not a filesystem path that can be opened directly.
     */
    static void confirmAndImport(Context baseContext, Uri documentUri) {
        Builder myAlertDialog = new Builder(baseContext);
        myAlertDialog.setTitle(baseContext.getResources().getString(R.string.filehelper_import_title));
        myAlertDialog.setMessage(baseContext.getResources().getString(R.string.filehelper_overwrite_warning));
        myAlertDialog.setPositiveButton(baseContext.getResources().getString(R.string.ok),
                (dialog, which) -> {
                    // On the main thread, so this one may present its own result.
                    ImportResult result = importFileAndVerify(baseContext, documentUri.toString(),
                            baseContext.getContentResolver());
                    Toast.makeText(baseContext, baseContext.getString(result.messageRes()),
                            Toast.LENGTH_LONG).show();
                });
        myAlertDialog.setNegativeButton(baseContext.getResources().getString(R.string.cancel),
                (dialog, which) -> dialog.cancel());
        myAlertDialog.show();
    }

    /** What {@link #backupDbToSd} managed. */
    enum BackupOutcome {
        /** The live database is copied aside. */
        BACKED_UP,
        /** There is no live database, so there is nothing an import could lose. */
        NOTHING_TO_BACK_UP,
        /** A live database exists and could not be copied. An import must not go ahead. */
        FAILED
    }

    /**
     * Copies the live database aside so an import has something to roll back to.
     *
     * <p>A failure here used to be ignored: the import went ahead with nothing to roll back to.
     * {@code ImportSafetyTest}.
     */
    private static BackupOutcome backupDbToSd(Context baseContext) {
        if (!getInternalDbFile(baseContext).exists()) {
            return BackupOutcome.NOTHING_TO_BACK_UP;
        }
        if (!fileSetup(baseContext, externalBackupPath)) {
            Log.e(DROPBOX_TAG, "error 288: could not set up the backup before an import");
            return BackupOutcome.FAILED;
        }
        return snapshotDatabaseTo(baseContext, externalFile)
                ? BackupOutcome.BACKED_UP : BackupOutcome.FAILED;
    }

    /**
     * Puts the backup taken by {@link #backupDbToSd} back, and says whether it managed to.
     *
     * <p>Both branches used to be empty — the {@code catch} and the {@code else} — so a failed
     * rollback was indistinguishable from a successful one at the call site and left no trace in
     * the log. That silence is what let finding 1 reach a device: the restore could not run at
     * all, and nothing said so. Returning a boolean is the minimum; Step 3 carries the reason out
     * to the user.
     *
     * @return true if the live database was overwritten with the backup
     */
    private static boolean restoreBackupDbFromSd(Context baseContext) {
        if (!fileSetup(baseContext, externalBackupPath)) {
            Log.e(DROPBOX_TAG, "restore failed: could not resolve the database and its backup."
                    + " The live database is left as it is.");
            return false;
        }
        try (InputStream in = new FileInputStream(externalFile)) {
            if (!replaceDatabaseFile(baseContext, in)) {
                Log.e(DROPBOX_TAG, "restore failed while putting " + externalFile + " back");
                return false;
            }
            Utility.log(DROPBOX_TAG, "restore: backup put back from " + externalFile);
            return true;
        } catch (IOException e) {
            Log.e(DROPBOX_TAG, "restore failed while reading " + externalFile, e);
            return false;
        }
    }

    /**
     * Confirms the export, then hands the staged database back so the caller can ask where it goes.
     *
     * <p>Takes a callback rather than starting the picker itself, and that is the whole reason
     * this shape changed: {@code ACTION_CREATE_DOCUMENT} has to be started <em>for result</em>,
     * which needs an {@code Activity} or a {@code Fragment}, and this class only ever has a
     * {@code Context}. The caller has both, so the caller launches and the caller receives.
     */
    static void showExportMessage(Context baseContext, Consumer<File> onSave,
                                  Consumer<File> onShare) {
        Builder myAlertDialog = new Builder(baseContext);
        myAlertDialog.setMessage(baseContext.getResources().getString(R.string.dialog_export_msg));
        myAlertDialog.setPositiveButton(baseContext.getResources().getString(R.string.save),
                (arg0, arg1) -> stageThen(baseContext, onSave));
        myAlertDialog.setNegativeButton(baseContext.getResources().getString(R.string.share),
                (arg0, arg1) -> stageThen(baseContext, onShare));
        // Cancel sits in the neutral slot, not the negative one. Material lays the row out as
        // neutral ... negative positive, so this is what renders "Cancel ... Share Save" and keeps
        // the two real actions together; as the negative button it would sit between them.
        myAlertDialog.setNeutralButton(baseContext.getResources().getString(R.string.cancel),
                (arg0, arg1) -> {
                    // Nothing staged yet, so nothing to clean up.
                });
        myAlertDialog.show();
    }

    /** Stages the database and hands it on, or says why it could not. */
    private static void stageThen(Context baseContext, Consumer<File> destination) {
        // Clears the previous export's leftover, if any: a shared file cannot be deleted when its
        // sheet closes, because the receiving app reads the content:// URI on its own schedule.
        discardPendingExport(baseContext);
        File staged = stageDbForExport(baseContext);
        if (staged == null) {
            // On the main thread -- a dialog button -- so this one may present.
            Toast.makeText(baseContext,
                    baseContext.getResources().getString(R.string.error) + " 287",
                    Toast.LENGTH_LONG).show();
            return;
        }
        rememberPendingExport(baseContext, staged);
        destination.accept(staged);
    }

    /**
     * The name the exported database is staged under, and offered to the picker and share targets.
     *
     * <p>Dated, because {@code DATABASE_NAME} is one fixed name for every backup ever taken, and a
     * folder of identically-named backups is not a backup history. {@code EXTRA_TITLE} is the only
     * say the app has in a saved file's name, and a share target may name its copy from the
     * content URI's display name, which is the staged file's own. The staged file used to be
     * {@code DATABASE_NAME}, which also collided with {@code LocalBackupManager}'s current backup --
     * see {@link #discardPendingExport}.
     */
    static String exportDbFileName(Context baseContext) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", AppLocale.TEXT);
        return baseContext.getResources().getString(R.string.app_name)
                + " " + dateFormat.format(Calendar.getInstance().getTime())
                + database_extension;
    }

    /**
     * Copies the live database into the app's own directory, ready to be handed to a picker.
     *
     * <p>Was {@code exportDB}, which did this and then raised an {@code ACTION_SEND} chooser. The
     * chooser is gone: it offered only apps registered to receive a file, so the one destination a
     * backup most wants -- the device's own storage -- could not appear in it. The copy is all
     * that is left here, and the caller decides where it goes.
     *
     * @return the staged copy, or null if it could not be written
     */
    static File stageDbForExport(Context baseContext) {
        File database = getInternalDbFile(baseContext);
        if (!database.exists()) {
            Log.e(DROPBOX_TAG, "error 287: no database at " + database);
            return null;
        }
        File staged = new File(getExportStagingDir(baseContext), exportDbFileName(baseContext));
        Utility.log(MainActivity.DROPBOX_TAG, "staging export: " + staged.getPath());
        if (!snapshotDatabaseTo(baseContext, staged)) {
            Log.e(DROPBOX_TAG, "error 333: could not copy the database out for export");
            return null;
        }
        return staged;
    }

    /**
     * Copies the live database file to {@code dest}, consistently and atomically. <b>Every copy of
     * the live database's bytes goes through here</b> -- the import safety backup, the export, the
     * local backups, and the Dropbox uploads.
     *
     * <p><b>Consistent:</b> if the shared connection is open, the copy runs inside an immediate
     * transaction on it. That holds off every writer -- with one pooled connection, every other
     * thread -- so the file cannot change mid-copy. These copies used to read the file while a
     * save could be writing it, and a torn copy passes the import's table check.
     *
     * <p><b>Never opens the database.</b> With no connection open the file is copied as it is:
     * nothing in the process is writing it, and opening a corrupt file through
     * {@code SQLiteOpenHelper} would delete it -- the case a backup exists for. The same rule as
     * {@link #getInternalDbFile}.
     *
     * <p><b>Atomic:</b> written to {@code dest.tmp}, synced, and renamed over {@code dest}, so an
     * existing file at {@code dest} is only ever replaced by a complete copy. Reports, does not
     * present.
     *
     * @return true if {@code dest} now holds a complete copy
     */
    public static boolean snapshotDatabaseTo(Context context, File dest) {
        File database = getInternalDbFile(context);
        if (!database.exists()) {
            Log.e(DROPBOX_TAG, "snapshot failed: no database at " + database);
            return false;
        }
        File tmp = new File(dest.getPath() + ".tmp");
        // Held for the whole copy: an import on another thread must not close the connection
        // this holding transaction is on. See DBAdapter.connectionUseLock.
        java.util.concurrent.locks.Lock connectionUse = DBAdapter.connectionUseLock();
        connectionUse.lock();
        SQLiteDatabase lock = DBAdapter.peekSharedConnection(context);
        boolean inTransaction = false;
        try {
            if (lock != null) {
                lock.beginTransactionNonExclusive();
                inTransaction = true;
            }
            try (InputStream in = new FileInputStream(database)) {
                writeDurably(in, tmp);
            }
            if (!tmp.renameTo(dest)) {
                throw new IOException("could not rename " + tmp + " to " + dest);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(DROPBOX_TAG, "snapshot of the database to " + dest + " failed", e);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        } finally {
            if (inTransaction) {
                try {
                    // Nothing was written; ending without success is a no-op rollback.
                    lock.endTransaction();
                } catch (RuntimeException e) {
                    Log.w(DROPBOX_TAG, "snapshot: could not end the holding transaction", e);
                }
            }
            connectionUse.unlock();
        }
    }

    /**
     * A consistent copy of the live database in the cache directory, for a Dropbox upload to read
     * instead of the live file. The caller deletes it when done.
     *
     * @return the copy, or null if it could not be made
     */
    public static File snapshotForUpload(Context context) {
        File snapshot = new File(context.getCacheDir(), "upload-" + System.nanoTime() + database_extension);
        return snapshotDatabaseTo(context, snapshot) ? snapshot : null;
    }

    /** Writes a stream to a file and syncs it to storage before returning. */
    private static void writeDurably(InputStream in, File to) throws IOException {
        try (FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
            out.getFD().sync();
        }
    }

    /**
     * Replaces the live database file with the bytes of {@code in}.
     *
     * <p>In an order that cannot leave a half-written database or replay a stale journal onto the
     * new one: the incoming bytes go to a temporary file beside the database and are synced; then
     * the shared connection is closed, so nothing has the file open; then the {@code -journal},
     * {@code -wal} and {@code -shm} sidecars are deleted; then the temporary file is renamed over
     * the database. The old code truncated the database and wrote into it in place, and deleted
     * {@code -wal}/{@code -shm} but not {@code -journal} -- the journal this database actually
     * uses -- so a journal left by an interrupted write was replayed onto the imported file and
     * corrupted it. {@code ImportSafetyTest}.
     */
    private static boolean replaceDatabaseFile(Context context, InputStream in) {
        File database = getInternalDbFile(context);
        File incoming = new File(database.getPath() + ".incoming");
        try {
            writeDurably(in, incoming);
        } catch (IOException e) {
            Log.e(DROPBOX_TAG, "error 131: could not write the incoming database beside the live one", e);
            //noinspection ResultOfMethodCallIgnored
            incoming.delete();
            return false;
        }

        // Nothing may have the file open while it is replaced, and nothing may reopen it until the
        // rename is done: a connection opened in between would keep the old, unlinked file.
        return DBAdapter.replaceWhileClosed(context, () -> {
            for (String sidecar : new String[]{"-journal", "-wal", "-shm"}) {
                File f = new File(database.getPath() + sidecar);
                if (f.exists() && !f.delete()) {
                    Log.e(DROPBOX_TAG, "could not delete " + f + "; not replacing the database");
                    //noinspection ResultOfMethodCallIgnored
                    incoming.delete();
                    return false;
                }
            }
            if (!incoming.renameTo(database)) {
                Log.e(DROPBOX_TAG, "could not move " + incoming + " over " + database);
                //noinspection ResultOfMethodCallIgnored
                incoming.delete();
                return false;
            }
            return true;
        });
    }

    /**
     * Where exports wait for a picker or a share target: {@code exports/} under
     * {@link #getAppFilesDir}.
     *
     * <p>A folder of its own, because the files root is not a scratch folder -- it holds
     * {@code LocalBackupManager}'s backups and {@code ExpenseLogBackup.edb}. The export used to be
     * staged there as {@code expenseLog.edb}, which is the current backup's name, and the cleanup
     * that followed deleted that backup. Carries the same rule as {@link #getInternalDbFile}: it
     * must never open the database.
     */
    static File getExportStagingDir(Context context) {
        File dir = new File(getAppFilesDir(context), EXPORT_STAGING_FOLDER);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }
    /**
     * The app's own directory on external storage — {@code Android/data/<package>/files}.
     *
     * <p>Replaces {@code Environment.getExternalStorageDirectory()} everywhere the app writes files
     * for its own use. This location needs <b>no permission at any API level</b> and is exempt from
     * scoped storage, so it keeps working when {@code targetSdk} is raised past 29 and
     * {@code requestLegacyExternalStorage} stops being honoured.
     *
     * <p><b>Known trade-off:</b> unlike the old {@code /Expense Log/} folder, this directory is
     * deleted when the app is uninstalled or its data is cleared. Automatic local backups therefore
     * protect against mistakes inside the app, not against losing the app. Surviving an uninstall is
     * what the manual {@code .edb} export and Dropbox are for. Keeping the old behaviour would mean
     * asking the user for a folder via {@code ACTION_OPEN_DOCUMENT_TREE} and persisting that
     * permission — deliberately out of scope here, and noted in docs/history/REHABILITATION_PLAN.md.
     *
     * <p>Falls back to internal storage: {@code getExternalFilesDir} returns null when external
     * storage is not mounted, and a backup written somewhere is better than one skipped.
     */
    static File getAppFilesDir(Context context) {
        File dir = context.getExternalFilesDir(null);
        if (dir == null) {
            dir = context.getFilesDir();
        }
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /**
     * The live database file.
     *
     * <p><b>This must never open the database.</b> It used to: on API 28+ it built a
     * {@link DBAdapter}, called {@code getDB()}, disabled WAL, read {@code getPath()} and closed —
     * a working open performed to learn a string that {@code getDatabasePath} returns for free.
     * That made recovery impossible in the one case that needs it. {@code restoreBackupDbFromSd}
     * resolves its destination through here, so restoring a database that will not open threw
     * again inside the restore, {@code fileSetup} swallowed it, and the app was left unusable with
     * an intact backup sitting on disk beside it. Finding 1 of {@code docs/history/REVIEW_FINDINGS.md}.
     *
     * <p>Dropping the WAL side effect is safe: {@code DBAdapter}'s constructor and its
     * {@code onOpen} both call {@code disableWriteAheadLogging()}, so every normal open leaves the
     * file in a non-WAL journal mode — which is persistent in the file header, not per-connection.
     * The Dropbox upload paths that copy this file therefore still get a complete database.
     * {@code walIsDisabled_soUploadsCannotMissRecentWrites} pins that.
     *
     * <p>Asking the context also means the API level no longer matters — the old sub-28 branch
     * assembled the path by string from {@code Environment.getDataDirectory()}, which bypassed any
     * {@code ContextWrapper} and so escaped the test isolation harness as well.
     */
    public static File getInternalDbFile(Context context) {
        File databaseFile = context.getDatabasePath(DBAdapter.DATABASE_NAME);
        Utility.log(DROPBOX_TAG, "internal path exists: " + databaseFile.exists());
        Utility.log(DROPBOX_TAG, "get internal path: " + databaseFile.getAbsolutePath());
        return databaseFile;
    }

    /** The directory holding the live database. Carries the same rule as {@link #getInternalDbFile}. */
    public static String getInternalDbFolder(Context context) {
        return getInternalDbFile(context).getParent();
    }

    /**
     * Make copy of local db. Move provided file into local, verify and return
     * if failed or restore backup if passed
     */
    /**
     * Validates a candidate {@code .edb} and, only if it is sound, makes it the live database.
     *
     * <p><b>Validate first, then overwrite.</b> This used to run the other way round — overwrite the
     * live database, then open it and ask whether it was any good — which cannot work. When
     * {@link android.database.sqlite.SQLiteOpenHelper} opens a corrupt file, Android's
     * {@code DefaultDatabaseErrorHandler} <i>deletes it and creates a fresh empty database</i>. The
     * verification then passed against that newly-created empty schema, the import reported
     * success, and the restore never ran: importing any non-database file silently destroyed the
     * user's records and said it had worked. Caught by
     * {@code EdbRoundTripTest.import_ofAnInvalidFile_isRejected}.
     *
     * <p>The candidate is staged in the cache directory, opened read-only, and checked for the
     * app's own tables before anything touches the live database. A backup is still taken, as a
     * second line of defence against a failure during the copy itself.
     */
    public static ImportResult importFileAndVerify(Context context, String filePath, ContentResolver contentResolver) {
        if (BuildConfig.DEBUG) Log.i(MainActivity.DROPBOX_TAG, "verify step 1: " + filePath);

        File staged = new File(context.getCacheDir(), "import_candidate" + database_extension);
        try {
            // Stage the incoming bytes somewhere harmless.
            try (InputStream in = openImportSource(filePath, contentResolver);
                 OutputStream out = new FileOutputStream(staged)) {
                if (in == null) {
                    return ImportResult.UNREADABLE;
                }
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } catch (Exception e) {
                Log.e(DROPBOX_TAG, "error 131: could not read the import source", e);
                return ImportResult.UNREADABLE;
            }

            CandidateCheck check = inspectCandidate(staged);
            if (check == CandidateCheck.NOT_A_DATABASE) {
                Utility.log(DROPBOX_TAG, "import refused before any overwrite: not our database");
                return ImportResult.NOT_A_DATABASE;
            }
            if (check == CandidateCheck.NEWER_SCHEMA) {
                Utility.log(DROPBOX_TAG, "import refused before any overwrite: newer schema");
                return ImportResult.NEWER_SCHEMA;
            }

            if (BuildConfig.DEBUG) Log.i(MainActivity.DROPBOX_TAG, "verify step 2: " + filePath);

            // The candidate is sound. Back up the current database, then swap it in -- but only if
            // the backup worked: without one there is nothing to roll back to.
            BackupOutcome backup = backupDbToSd(context);
            if (backup == BackupOutcome.FAILED) {
                Utility.log(DROPBOX_TAG, "import refused before any overwrite: no backup");
                return ImportResult.BACKUP_FAILED;
            }
            boolean backedUp = backup == BackupOutcome.BACKED_UP;
            if (!importProvidedFile(context, staged.getPath(), null)) {
                return rollBack(context, backedUp, "the copy into place failed");
            }

            // Confirm the live database opens through the normal path, which also runs any
            // migration the file needs. DBAdapter refuses a database written by a newer schema.
            boolean success = false;
            DBAdapter db = null;
            try {
                db = new DBAdapter(context);
                db.open();
                success = db.verifyDatabase();
            } catch (RuntimeException e) {
                if (BuildConfig.DEBUG)
                    Log.i(MainActivity.DROPBOX_TAG, "import rejected on open: " + e.getMessage());
            } finally {
                if (db != null)
                    db.close();
            }

            if (!success) {
                return rollBack(context, backedUp, "the imported database did not verify");
            }
            if (BuildConfig.DEBUG) Log.i(MainActivity.DROPBOX_TAG, "verify step 4: " + filePath);
            // The imported file is not what Dropbox holds, so say so: sync then uploads it, or
            // asks. Left alone, the marker kept the old remote version, the import was never
            // uploaded, and the next remote change was downloaded over it without a prompt. A
            // Dropbox download records its own version right after this; apply() updates the
            // in-memory value at once and queues disk writes in order, so that one still wins.
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .putString(context.getString(R.string.pref_key_current_dropbox_db_version),
                            DBAdapter.KEY_DATABASE_CHANGE)
                    .apply();
            DBAdapter.countChange();
            return ImportResult.OK;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    /**
     * Opens {@code candidate} read-only and checks it carries this app's tables.
     *
     * <p>Read-only matters: it stops SQLite from creating or repairing anything, so a file that is
     * not a database fails instead of quietly becoming one. The error handler is explicitly a
     * no-op for the same reason — the default one deletes the file it was asked to open.
     */
    private static CandidateCheck inspectCandidate(File candidate) {
        if (!candidate.exists() || candidate.length() == 0) {
            return CandidateCheck.NOT_A_DATABASE;
        }
        SQLiteDatabase probe = null;
        try {
            probe = SQLiteDatabase.openDatabase(candidate.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY, dbToDelete -> {
                        // Deliberately empty: never delete or rebuild the file being inspected.
                    });
            Cursor c = probe.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{"tagTypes"});
            try {
                if (c.getCount() == 0) {
                    return CandidateCheck.NOT_A_DATABASE;
                }
            } finally {
                c.close();
            }

            // getVersion() is PRAGMA user_version, the same value SQLiteOpenHelper stores and
            // compares. Reading it here refuses a future file before anything is overwritten,
            // which beats overwriting and rolling back however well the rollback works.
            int version = probe.getVersion();
            if (version > DBAdapter.DATABASE_VERSION) {
                Utility.log(DROPBOX_TAG, "import refused: file is schema v" + version
                        + ", this build understands v" + DBAdapter.DATABASE_VERSION);
                return CandidateCheck.NEWER_SCHEMA;
            }
            return CandidateCheck.OK;
        } catch (Exception e) {
            return CandidateCheck.NOT_A_DATABASE;
        } finally {
            if (probe != null) {
                probe.close();
            }
        }
    }

    /**
     * Rolls the live database back after a failed import, and names the outcome.
     *
     * <p>The distinction between {@link ImportResult#ROLLED_BACK} and
     * {@link ImportResult#RESTORE_FAILED} is the whole point: one is an import that did not happen,
     * the other is a database the user needs to act on. Collapsing both into {@code false} is what
     * let finding 1 look like an ordinary rejected import.
     */
    private static ImportResult rollBack(Context context, boolean backedUp, String why) {
        if (!backedUp) {
            Log.e(DROPBOX_TAG, "import failed (" + why + ") and there was no backup to restore");
            return ImportResult.RESTORE_FAILED;
        }
        if (!restoreBackupDbFromSd(context)) {
            Log.e(DROPBOX_TAG, "import failed (" + why + ") AND the rollback failed;"
                    + " the live database may be unusable");
            return ImportResult.RESTORE_FAILED;
        }
        Utility.log(DROPBOX_TAG, "import failed (" + why + "); the previous database is back");
        return ImportResult.ROLLED_BACK;
    }

    /**
     * The outcome of an {@code .edb} import, and the message that goes with it.
     *
     * <p>{@code importFileAndVerify} used to return a boolean and raise its own {@code Toast}. That
     * was two bugs in one: five different failures were indistinguishable to the caller, so a
     * refusal could not be explained to the user (finding 1's newer-schema case was a silent
     * {@code false}); and the toast threw outright when the Dropbox download path called it from a
     * {@code DropboxTask} pool thread, which has no Looper (finding 6).
     *
     * <p>{@code FileHelper} now reports and does not present. The caller knows what thread it is
     * on, so the caller decides how to say it.
     */
    public enum ImportResult {
        /** Imported, and the new database verifies. */
        OK(R.string.filehelper_import_success),
        /** Not this app's database. Nothing was touched. */
        NOT_A_DATABASE(R.string.file_helper_invalid_db),
        /** A valid database from a newer build of the app. Nothing was touched. */
        NEWER_SCHEMA(R.string.import_refused_newer_schema),
        /** The source could not be read at all. Nothing was touched. */
        UNREADABLE(R.string.import_failed_unreadable),
        /** The current database could not be backed up first, so it was not replaced. */
        BACKUP_FAILED(R.string.import_failed_backup_failed),
        /** The import failed part-way and the previous database was put back. */
        ROLLED_BACK(R.string.import_failed_rolled_back),
        /** The import failed and the rollback did not work. The user must act. */
        RESTORE_FAILED(R.string.import_failed_restore_failed);

        private final int messageRes;

        ImportResult(int messageRes) {
            this.messageRes = messageRes;
        }

        /** True only for {@link #OK}; every other value is a failure with its own message. */
        public boolean isOk() {
            return this == OK;
        }

        /** The string resource describing this outcome to the user. */
        public int messageRes() {
            return messageRes;
        }
    }

    /**
     * What the read-only probe made of a candidate file.
     *
     * <p>Step 3 of {@code docs/history/RECOVERY_PLAN.md} widens this into the result {@code importFileAndVerify}
     * returns to its callers, so that a refusal can be shown to the user rather than collapsing
     * into {@code false}.
     */
    enum CandidateCheck {
        /** This build can open it. */
        OK,
        /** Not this app's database, or not a database at all. */
        NOT_A_DATABASE,
        /** A well-formed Expense Log database written by a newer build than this one. */
        NEWER_SCHEMA
    }

    @SuppressWarnings({"ResultOfMethodCallIgnored", "ConstantConditions"})
    /**
     * Overwrites the live database with the bytes at {@code filePath}.
     *
     * <p>Reads through {@link ContentResolver} when one is supplied — which is now every caller
     * that goes through the SAF picker or an email attachment — and falls back to a direct file
     * read for the plain paths that Dropbox's cached downloads still use.
     *
     * <p>The old {@code Environment.getExternalStorageDirectory().canWrite()} guard is gone. It
     * gated the *destination*, which is internal storage and always writable; all it ever did was
     * make the import fail for a reason unrelated to the file being imported.
     */
    private static boolean importProvidedFile(Context context, String filePath, ContentResolver contentResolver) {
        try (InputStream in = openImportSource(filePath, contentResolver)) {
            if (in == null) {
                return false;
            }
            return replaceDatabaseFile(context, in);
        } catch (Exception e) {
            // Silent for the same reason as the rest of the import path: importFileAndVerify calls
            // this, and the Dropbox download calls that from a pool thread with no Looper. The
            // caller turns the failure into ROLLED_BACK or RESTORE_FAILED and presents it.
            Log.e(DROPBOX_TAG, "error 131: could not copy the imported database into place", e);
            return false;
        }
    }

    /**
     * Opens an import source, whether it arrived as a content URI, a {@code file://} URI, or a
     * bare filesystem path.
     *
     * <p><b>Branches on the scheme, not on whether a resolver was passed.</b> It used to do the
     * latter, which made a caller's two arguments have to agree with each other: {@code
     * MainActivity}'s {@code file://} branch passed {@code uri.getPath()} — a path with no scheme —
     * together with a non-null resolver, so {@code openInputStream(Uri.parse("/storage/…/x.edb"))}
     * ran and threw {@code FileNotFoundException: Unknown URL} every single time. Finding 4 of
     * {@code docs/history/REVIEW_FINDINGS.md}.
     *
     * <p>That branch is gone — the app cannot read shared storage at all without a permission it
     * deliberately does not hold — but the rule stays here, because the bare-path case is still
     * live: {@code DropboxDownload} passes the path of a file it has just written into the cache,
     * with no resolver. A path is a path whatever else it is handed alongside.
     */
    private static InputStream openImportSource(String filePath, ContentResolver contentResolver)
            throws IOException {
        Uri uri = Uri.parse(filePath);
        String scheme = uri.getScheme();

        if (contentResolver != null && scheme != null
                && !ContentResolver.SCHEME_FILE.equals(scheme)) {
            return contentResolver.openInputStream(uri);
        }

        // A file:// URI carries its path in getPath(); a bare path is already one.
        String path = ContentResolver.SCHEME_FILE.equals(scheme) ? uri.getPath() : filePath;
        return new FileInputStream(new File(path));
    }

//    boolean importFile(Context baseContext, Uri uriFile, boolean email, ContentResolver contRes) {
//        boolean complete = false;
//        FileChannel src;
//        FileChannel dst;
//        File internalLoc = null;
//        File externalLoc = null;
//        File data = Environment.getDataDirectory();
//        internalLoc = getInternalDbFile(baseContext);
//        externalLoc = new File(uriFile.getPath());
//        DBAdapter db = new DBAdapter(baseContext);
//        db.open();
//        db.close();
//
//        // backup db to SD before bringing in unverified db
//        backupDbToSd(baseContext);
//
//        try {
//            if (email) {
//                try {
//                    InputStream attachment = contRes.openInputStream(uriFile);
//                    FileOutputStream f = new FileOutputStream(internalLoc);
//                    byte[] buffer = new byte[1024];
//                    @SuppressWarnings("unused")
//                    int len1 = 0;
//                    while ((len1 = attachment.read(buffer)) > 0) {
//                        f.write(buffer);
//                    }
//                    f.close();
//                    Toast.makeText(baseContext,
//                            baseContext.getResources().getString(R.string.filehelper_import_success), Toast.LENGTH_LONG)
//                            .show();
//                    complete = true;
//                } catch (Exception e) {
//                    Toast.makeText(baseContext,
//                            baseContext.getResources().getString(R.string.error) + " 131: " + e.toString(),
//                            Toast.LENGTH_LONG).show();
//                }
//
//            } else {
//                dst = new FileOutputStream(internalLoc).getChannel();
//                src = new FileInputStream(externalLoc).getChannel();
//                dst.transferFrom(src, 0, src.size());
//                src.close();
//                dst.close();
//                complete = true;
//            }
//
//            // verify new db
//            db.open();
//
//            if (!db.verifyDatabase()) {
//                // invalid db, restore backup and display toast
//                db.close();
//                restoreBackupDbFromSd(baseContext);
//                complete = false;
//                Toast.makeText(baseContext, baseContext.getResources().getString(R.string.file_helper_invalid_db),
//                        Toast.LENGTH_LONG).show();
//            } else {
//                Toast.makeText(baseContext, baseContext.getResources().getString(R.string.filehelper_import_success),
//                        Toast.LENGTH_LONG).show();
//                db.close();
//            }
//        } catch (Exception e) {
//            Toast.makeText(baseContext, baseContext.getResources().getString(R.string.error) + " 112: " + e.toString(),
//                    Toast.LENGTH_LONG).show();
//        }
//        db = null;
//        return complete;
//    }

}
