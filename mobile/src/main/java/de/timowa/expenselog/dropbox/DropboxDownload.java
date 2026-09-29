/*
 * Copyright (c) 2010-11 Dropbox, Inc.
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */


package de.timowa.expenselog.dropbox;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.SharedPreferences;
import android.os.Environment;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.DBAdapter;
import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.FileHelper;
import de.timowa.expenselog.MainActivity;
import de.timowa.expenselog.R;
import de.timowa.expenselog.Utility;

/**
 * Here we show getting metadata for a directory and downloading a file in a
 * background thread, trying to show typical exception handling and flow of
 * control for an app that downloads a file from Dropbox.
 */

public class DropboxDownload extends DropboxTask<Boolean> {

    private Context mContext;
    private final Dialog mDialog;
    private DbxClientV2 dbxClientV2;
    private String mRemotePath;
    private String mDownloadType;

    private FileOutputStream mFos;

    private boolean mCanceled;
    private String mErrorMsg = "Default Download Error";

    private OnTaskComplete onTaskComplete;

    public interface OnTaskComplete {
        void onDropboxAction(String message, int number);
    }

    public void setMyTaskCompleteListener(OnTaskComplete onTaskComplete) {
        this.onTaskComplete = onTaskComplete;
    }

    /** The marker the sync check saw when it chose this download; null for a user's choice. */
    private String mMarkerSeenByCheck;
    /** Set when a record was saved after the sync check decided, so this download stood down. */
    private boolean mStoodDown;

    /**
     * Makes this a download the sync check decided on: it replaces the local database only if
     * the marker is still {@code markerSeenByCheck}. See {@link DropboxSyncRules#mayOverwriteLocal}.
     */
    public void setMarkerSeenByCheck(String markerSeenByCheck) {
        mMarkerSeenByCheck = markerSeenByCheck;
    }

    private boolean localChangedSinceCheck() {
        String now = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(mContext.getString(R.string.pref_key_current_dropbox_db_version), "");
        return !DropboxSyncRules.mayOverwriteLocal(mMarkerSeenByCheck, now,
                () -> new DBAdapter(mContext).getTotalNumberOfLogs());
    }

    public DropboxDownload(Context context, DbxClientV2 clientV2,
                           String dropboxPath, String downloadType) {
        // We set the context this way so we don't accidentally leak activities
        mContext = context.getApplicationContext();
        mDownloadType = downloadType;
        dbxClientV2 = clientV2;
        mRemotePath = dropboxPath;

        mDialog = DropboxDialogs.progress(context, context.getString(R.string.downloading),
                (dialog, which) -> {
                    mCanceled = true;
                    mErrorMsg = "Canceled";

                    // This will cancel the download by closing its stream
                    if (mFos != null) {
                        try {
                            mFos.close();
                        } catch (IOException ignored) {
                        }
                    }
                });

        mDialog.show();
    }

    @Override
    protected Boolean doInBackground() {
        try {
            if (mCanceled) {
                return false;
            }
            if (localChangedSinceCheck()) {
                mStoodDown = true;
                return false;
            }

//            String backupPath = FileHelper.getInternalDbFolder(mContext) + "//" + FileHelper.dbDropBoxBackupFileName;
//            File data = Environment.getDataDirectory();
//            File tempFile = new File(data, backupPath);

//            String backupPath = FileHelper.externalFolder + "//" + FileHelper.dbDropBoxBackupFileName;
//            File sd = Environment.getExternalStorageDirectory();
//            File sd = mContext.getCacheDir();
//            File tempFile = new File(sd, backupPath);

            // create a temporary file in the ache dir to verify the file before importing it
            File tempFile = File.createTempFile(FileHelper.dbDropBoxBackupFileName, null, mContext.getCacheDir());

            FileMetadata downloadMetaData;
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                // Kept in a field only so the dialog's Cancel can close it mid-download.
                mFos = out;
                if (BuildConfig.DEBUG)
                    Log.i(DropBoxHelper.DROPBOX_TAG, "tempfile path: " + tempFile.getPath() + ", remote path: " + mRemotePath);
                // download file
                downloadMetaData = dbxClientV2.files().download(mRemotePath).download(out);
            } finally {
                mFos = null;
            }
            if (mCanceled) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                return false;
            }

            // Checked again after the download, which is where most of the time goes.
            if (localChangedSinceCheck()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
                mStoodDown = true;
                return false;
            }

            // Runs on a DropboxTask pool thread, which has no Looper. FileHelper used to raise
            // the Toast itself from here, which threw instead of showing anything (finding 6);
            // it now returns the reason and onPostExecute presents it on the main thread.
            FileHelper.ImportResult result;
            try {
                result = FileHelper.importFileAndVerify(mContext, tempFile.getPath(), null);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
            if (!result.isOk()) {
                if (BuildConfig.DEBUG) Log.i(DropBoxHelper.DROPBOX_TAG, "import failed: " + result);
                mErrorMsg = mContext.getString(result.messageRes());
                return false;
            }

            // Record the downloaded version -- unless this was a restore of a backup file. A backup
            // has its own version, unrelated to the main database's; storing it made the next sync
            // see a mismatch and download the old main database over the restore if the upload
            // that follows a restore failed. A restore keeps the "changed locally" marker the
            // import set, and that upload records the real version.
            if (!DropBoxHelper.DROPBOX_RESTORE_FLAG.equals(mDownloadType)) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
                prefs.edit()
                        .putString(mContext.getResources().getString(R.string.pref_key_current_dropbox_db_version), downloadMetaData.getRev())
                        .putString(mContext.getResources().getString(R.string.pref_key_last_known_dropbox_rev), downloadMetaData.getRev())
                        .apply();
            }
            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "downloaded file's rev is: " + downloadMetaData.getRev());

            return true;

        } catch (DbxException | IOException e) {
            e.printStackTrace();
            mErrorMsg = e.toString();
        }
        return false;
    }

    @Override
    protected void onUncaught(RuntimeException e) {
        DropboxDialogs.dismiss(mDialog);
        showToast(e.toString());
        mContext = null;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        DropboxDialogs.dismiss(mDialog);
        if (mStoodDown) {
            // A record was saved while the check ran; check again, which now sees the change and
            // offers the conflict dialog instead of overwriting it.
            if (onTaskComplete != null)
                onTaskComplete.onDropboxAction("", DropBoxHelper.KEY_DROPBOX_SYNC_NOW);
            mContext = null;
            return;
        }
        Utility.log(DropBoxHelper.DROPBOX_TAG, "download post execute result: "+result);
        if (result) {
            // Set the image now that we have it

//            showToast("Database Updated");

            if (onTaskComplete != null) {
                // Send update to UI in MainActivity for new DB
                onTaskComplete.onDropboxAction(mDownloadType, DropBoxHelper.KEY_DROPBOX_UPDATED_DB);
            } else {
              Utility.log(DropBoxHelper.DROPBOX_TAG, "download onTaskComplete null");
            }

        } else {
            // Couldn't download it, so show an error
            if (mErrorMsg != null)
                showToast(mErrorMsg);
        }
        mContext = null;

    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        error.show();
    }

}
