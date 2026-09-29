/*
 * Copyright (c) 2011 Dropbox, Inc.
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

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.UploadErrorException;
import com.dropbox.core.v2.files.WriteMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.DBAdapter;
import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.FileHelper;
import de.timowa.expenselog.R;

/**
 * Here we show uploading a file in a background thread, trying to show
 * typical exception handling and flow of control for an app that uploads a
 * file from Dropbox.
 */
public class DropboxUpload extends DropboxTask<Boolean> {

    //    private DropboxAPI<?> mApi;
    private DbxClientV2 dbxClientV2;
    private String mPath;
    private File mFile;

    private Context mContext;
//    private final ProgressDialog mDialog;

    private String mErrorMsg = "default upload error";


    /** Whether this upload may replace the remote whatever version it is at. */
    private final boolean mForced;
    /** Set when Dropbox refused a conditional upload because the remote changed. */
    private boolean mConflict;
    private OnTaskComplete onTaskComplete;

    public interface OnTaskComplete {
        void onDropboxAction(String message, int number);
    }

    public void setMyTaskCompleteListener(OnTaskComplete onTaskComplete) {
        this.onTaskComplete = onTaskComplete;
    }

    /**
     * @param forced true only where the user explicitly chose to replace the remote -- see
     *               {@link DropboxSyncRules#writeModeFor}
     */
    public DropboxUpload(Context context, DbxClientV2 clientV2, String dropboxPath,
                         File file, boolean forced) {
        mForced = forced;
        // We set the context this way so we don't accidentally leak activities
        mContext = context.getApplicationContext();

        dbxClientV2 = clientV2;
        mPath = dropboxPath;
        mFile = file;
    }

    @Override
    protected Boolean doInBackground() {
        try {
            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "starting upload task");
            // By creating a request, we get a handle to the putFile operation,
            // so we can cancel it later if we want to
            String path = mPath + mFile.getName();

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            String markerKey = mContext.getString(R.string.pref_key_current_dropbox_db_version);
            String lastKnownKey = mContext.getString(R.string.pref_key_last_known_dropbox_rev);
            // Read before the snapshot: a change after this point may not be in what is uploaded.
            String markerBefore = prefs.getString(markerKey, "");
            long generationBefore = DBAdapter.changeGeneration();
            WriteMode mode = DropboxSyncRules.writeModeFor(
                    prefs.getString(lastKnownKey, DropboxSyncRules.NO_LAST_KNOWN_REV), mForced);

            // Upload a consistent copy, never the live file: a save during the upload could
            // otherwise send a torn database. See FileHelper.snapshotDatabaseTo.
            File snapshot = FileHelper.snapshotForUpload(mContext);
            if (snapshot == null) {
                mErrorMsg = mContext.getString(R.string.error) + " 333";
                return false;
            }
            FileMetadata result;
            try {
                try (FileInputStream fis = new FileInputStream(snapshot)) {
                    result = dbxClientV2.files().uploadBuilder(path).withMode(mode).uploadAndFinish(fis);
                } catch (UploadErrorException e) {
                    // A conditional update is refused when the file it names is gone -- deleted on
                    // Dropbox. The sync check then decides UPLOAD again, and the two would bounce
                    // off each other without end. Nothing is there to overwrite, so add it.
                    if (!DropboxSyncRules.isConflict(e) || mode == WriteMode.ADD
                            || DropboxPaths.fileAt(dbxClientV2, path) != null)
                        throw e;
                    try (FileInputStream fis = new FileInputStream(snapshot)) {
                        result = dbxClientV2.files().uploadBuilder(path).withMode(WriteMode.ADD).uploadAndFinish(fis);
                    }
                }
            } catch (UploadErrorException e) {
                if (!DropboxSyncRules.isConflict(e))
                    throw e;
                // The remote is not the version this device last saw: someone else uploaded.
                // Nothing is recorded; onPostExecute sends it to the sync check, whose conflict
                // dialog asks the user.
                if (BuildConfig.DEBUG)
                    Log.i(DropBoxHelper.DROPBOX_TAG, "upload refused as a conflict (" + mode + ")");
                mConflict = true;
                return false;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                snapshot.delete();
            }
            if (result != null) {
                String newRev = result.getRev();
                if (BuildConfig.DEBUG)
                    Log.i(DropBoxHelper.DROPBOX_TAG, "uploaded file rev is: " + newRev);

                SharedPreferences.Editor edit = prefs.edit().putString(lastKnownKey, newRev);
                if (DropboxSyncRules.mayRecordUploadAsCurrent(markerBefore, prefs.getString(markerKey, ""),
                        generationBefore, DBAdapter.changeGeneration()))
                    edit.putString(markerKey, newRev);
                edit.apply();

                return true;
            }

//            mRequest = mApi.putFileOverwriteRequest(path, fis, mFile.length(),
//                    new ProgressListener() {
//                        @Override
//                        public long progressInterval() {
//                            // Update the progress bar every half-second or so
//                            return 500;
//                        }
//
//                        @Override
//                        public void onProgress(long bytes, long total) {
//                            publishProgress(bytes);
//                        }
//                    });
//
//            if (mRequest != null) {
//                mRequest.upload();
//
//                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
//
//                if (BuildConfig.DEBUG)
//                    Log.i(DropBoxHelper.DROPBOX_TAG, "old saved file rev is: " + prefs.getString(mContext.getString(R.string.pref_key_current_dropbox_db_version), "none"));
//
//
//                // get the rev of the uploaded file and save it
//                DropboxAPI.Entry existingEntry = mApi.metadata("/" + mFile.getName(), 1, null, false, null);
//                if (BuildConfig.DEBUG)
//                    Log.i(DropBoxHelper.DROPBOX_TAG, "uploaded file rev is: " + existingEntry.rev);
//
//                prefs.edit().putString(mContext.getResources().getString(R.string.pref_key_current_dropbox_db_version), existingEntry.rev).apply();
//                prefs.edit().putString(mContext.getResources().getString(R.string.pref_key_last_known_dropbox_rev), existingEntry.rev).apply();
//
//                return true;
//            }

        } catch (DbxException | IOException e) {
            e.printStackTrace();
            mErrorMsg = e.getMessage();
        }
        return false;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (mConflict) {
            if (onTaskComplete != null)
                onTaskComplete.onDropboxAction("", DropBoxHelper.KEY_DROPBOX_SYNC_NOW);
        } else if (!result) {
            showToast(mErrorMsg);
        }
        mContext = null;
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        error.show();
    }
}
