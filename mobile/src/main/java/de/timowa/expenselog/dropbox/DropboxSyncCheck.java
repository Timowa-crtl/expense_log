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

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;

import javax.inject.Inject;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.DBAdapter;
import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.FileHelper;
import de.timowa.expenselog.R;

/**
 * Here we show getting metadata for a directory and downloading a file in a
 * background thread, trying to show typical exception handling and flow of
 * control for an app that downloads a file from Dropbox.
 */

public class DropboxSyncCheck extends DropboxTask<Boolean> {


    private static final int KEY_UPLOAD = 1;
    private static final int KEY_DOWNLOAD = 2;
    private static final int KEY_CHOICE_DIALOG = 5;

    private Context mContext;
    private String mPath;
    private DbxClientV2 dbxClientV2;

    private boolean mCanceled;
    private String mErrorMsg;
    private int ACTION;

    private OnTaskComplete onTaskComplete;
    // Kept as millis rather than formatted here: SyncConflictDialog formats them, and it needs the
    // raw values to say which side is the newer one.
    private long remoteModifiedMillis;
    private long localModifiedMillis;
    private String mMarkerSeen;

    public interface OnTaskComplete {
        void onDropboxAction(String message, int number);
    }

    public void setMyTaskCompleteListener(OnTaskComplete onTaskComplete) {
        this.onTaskComplete = onTaskComplete;
    }

    @Inject
    public DropboxSyncCheck(Context context, DbxClientV2 clientV2,
                            String dropboxPath) {
        this.mContext = context;
        this.dbxClientV2 = clientV2;
        this.mPath = dropboxPath;
    }

    @Override
    protected Boolean doInBackground() {

        if (BuildConfig.DEBUG)
            Log.i(DropBoxHelper.DROPBOX_TAG, "dropbox sync");
        try {
            if (mCanceled) {
                if (BuildConfig.DEBUG)
                    Log.i(DropBoxHelper.DROPBOX_TAG, "sync cancelled");
                mErrorMsg = "Sync Interrupted";
                return false;
            }

            // check if local exists, get saved rev if it does
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
            String localRev = prefs.getString(mContext.getResources().getString(R.string.pref_key_current_dropbox_db_version), "");
            String lastKnownRev = prefs.getString(mContext.getResources().getString(R.string.pref_key_last_known_dropbox_rev),
                    DropboxSyncRules.NO_LAST_KNOWN_REV);
            // Handed to a download this check starts, so it can tell whether a record was saved
            // while the check ran. See DropboxSyncRules.mayOverwriteLocal.
            mMarkerSeen = localRev;

            // A path lookup, not a search -- see DropboxPaths.
            FileMetadata fileMeta = DropboxPaths.fileAt(dbxClientV2, "/" + DBAdapter.DATABASE_NAME);
            String remoteRev = fileMeta == null ? null : fileMeta.getRev();
            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "local marker: " + localRev + ", last known: "
                        + lastKnownRev + ", remote: " + remoteRev);

            DropboxSyncRules.Action decision = DropboxSyncRules.decide(localRev, lastKnownRev, remoteRev,
                    () -> new DBAdapter(mContext).getTotalNumberOfLogs());
            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "sync decision: " + decision);
            switch (decision) {
                case UPLOAD:
                    ACTION = KEY_UPLOAD;
                    return true;
                case DOWNLOAD:
                    ACTION = KEY_DOWNLOAD;
                    return true;
                case CONFLICT:
                    // Both sides as plain millis; SyncConflictDialog does the formatting.
                    remoteModifiedMillis = fileMeta.getServerModified().getTime();
                    localModifiedMillis = FileHelper.getInternalDbFile(mContext).lastModified();
                    ACTION = KEY_CHOICE_DIALOG;
                    return true;
                default:
                    return false;
            }

        } catch (DbxException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (result) {
            if (BuildConfig.DEBUG) Log.i(DropBoxHelper.DROPBOX_TAG, "action: " + ACTION);
            if (ACTION == KEY_DOWNLOAD) {
                onTaskComplete.onDropboxAction(mMarkerSeen, DropBoxHelper.KEY_DROPBOX_DOWNLOAD);
            } else if (ACTION == KEY_UPLOAD) {
                onTaskComplete.onDropboxAction("", DropBoxHelper.KEY_DROPBOX_UPLOAD);
            } else if (ACTION == KEY_CHOICE_DIALOG) {
                remoteToLocalComparisonDialog();
            }

        } else {
            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "sync returned false: " + mErrorMsg);
            // Couldn't download it, so show an error
            if (mErrorMsg != null)
                showToast(mErrorMsg);
        }
        mContext = null;
    }

    private void showToast(String msg) {
        if (BuildConfig.DEBUG)
            Log.i(DropBoxHelper.DROPBOX_TAG, "toast: " + msg);
        Toast error = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        error.show();
    }

    private void remoteToLocalComparisonDialog() {
        // The check can finish after its screen is gone; showing a dialog on it would throw
        // BadTokenException. The next sync check asks again.
        if (DropboxDialogs.windowGone(mContext))
            return;
        SyncConflictDialog.show(mContext, localModifiedMillis, remoteModifiedMillis,
                new SyncConflictDialog.OnChoice() {
                    @Override
                    public void onKeepLocal() {
                        onTaskComplete.onDropboxAction("local", DropBoxHelper.KEY_DROPBOX_SYNC_CONFLICT_RESOLVE);
                    }

                    @Override
                    public void onKeepRemote() {
                        onTaskComplete.onDropboxAction("remote", DropBoxHelper.KEY_DROPBOX_SYNC_CONFLICT_RESOLVE);
                    }
                });
    }

}
