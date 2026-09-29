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
import android.util.Log;
import android.widget.Toast;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.WriteMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.FileHelper;
import de.timowa.expenselog.MainActivity;

/**
 * Here we show uploading a file in a background thread, trying to show
 * typical exception handling and flow of control for an app that uploads a
 * file from Dropbox.
 */
public class DropboxAutoBackups extends DropboxTask<Boolean> {

    private DbxClientV2 dbxClientV2;
    private String mPath;
    private File mFile;

    @SuppressWarnings("FieldCanBeLocal")
    private static String KEY_WEEKLY_FILE_NAME = "weekly" + FileHelper.database_extension;
    @SuppressWarnings("FieldCanBeLocal")
    private static String KEY_MONTHLY_FILE_NAME = "monthly" + FileHelper.database_extension;

    private Context mContext;
    private String mErrorMsg = "default auto-backup error";

    public DropboxAutoBackups(Context context, DbxClientV2 client, String dropboxPath,
                              File file) {
        // We set the context this way so we don't accidentally leak activities
        mContext = context.getApplicationContext();

        dbxClientV2 = client;
        mPath = dropboxPath;
        mFile = file;
    }

    @Override
    protected Boolean doInBackground() {
        if (BuildConfig.DEBUG)
            Log.i(MainActivity.DROPBOX_TAG, "auto-backup attempt");

        try {
            String path = null;
            // check if weekly backup exists

            // Path lookups, not searches -- see DropboxPaths.
            FileMetadata weekly = DropboxPaths.fileAt(dbxClientV2, mPath + KEY_WEEKLY_FILE_NAME);
            // Straight from the Date. This used to parse Date.toString() -- always English -- with
            // the device's locale; on a German phone that threw, the error was swallowed, the age
            // came out as 0 days, and the weekly backup was never refreshed again.
            if (weekly == null || isDue(weekly.getServerModified().getTime(), System.currentTimeMillis(), 7)) {
                path = mPath + KEY_WEEKLY_FILE_NAME;
            } else {
                FileMetadata monthly = DropboxPaths.fileAt(dbxClientV2, mPath + KEY_MONTHLY_FILE_NAME);
                if (monthly == null || isDue(monthly.getServerModified().getTime(), System.currentTimeMillis(), 30)) {
                    path = mPath + KEY_MONTHLY_FILE_NAME;
                }
            }

            if (path != null) {

                // A consistent copy, never the live file. See FileHelper.snapshotDatabaseTo.
                File snapshot = FileHelper.snapshotForUpload(mContext);
                if (snapshot == null)
                    return false;
                FileMetadata meta;
                try (FileInputStream fis = new FileInputStream(snapshot)) {
                    meta = dbxClientV2.files().uploadBuilder(path)
                            .withMode(WriteMode.OVERWRITE) //always overwrite existing file
                            .uploadAndFinish(fis);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    snapshot.delete();
                }

                if (meta != null)
                    return true;
                else {
                    if (BuildConfig.DEBUG)
                        Log.i(DropBoxHelper.DROPBOX_TAG, "auto-backup meta is null");

                    return false;
                }

            } else {
                return false;
            }

        } catch (DbxException | IOException e) {
            e.printStackTrace();
            mErrorMsg = e.toString();
        }
        return false;
    }

    /**
     * Whether a backup last written at {@code modifiedMillis} is more than {@code days} whole days
     * old at {@code nowMillis}. {@code DropboxAutoBackupsTest}.
     */
    static boolean isDue(long modifiedMillis, long nowMillis, int days) {
        return (nowMillis - modifiedMillis) / (24L * 60 * 60 * 1000) > days;
    }

    @Override
    protected void onPostExecute(Boolean result) {
//        if (!result) {
//            showToast("Error Creating Auto-Backup");
//            showToast(mErrorMsg);
//        }
        mContext = null;
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        error.show();
    }
}
