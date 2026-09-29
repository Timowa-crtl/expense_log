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

import android.app.Dialog;
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
import java.util.Calendar;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.FileHelper;
import de.timowa.expenselog.R;

/**
 * Here we show uploading a file in a background thread, trying to show
 * typical exception handling and flow of control for an app that uploads a
 * file from Dropbox.
 */
public class DropboxNewBackup extends DropboxTask<Boolean> {

    private DbxClientV2 dbxClientV2;
    private String mPath;
    private File mFile;

    private Context mContext;
    private final Dialog mDialog;

    public DropboxNewBackup(Context context, DbxClientV2 clientV2, String dropboxPath,
                            File file) {
        // We set the context this way so we don't accidentally leak activities
        mContext = context.getApplicationContext();

        dbxClientV2 = clientV2;
        mPath = dropboxPath;
        mFile = file;

        mDialog = DropboxDialogs.progress(context, context.getString(R.string.uploading_backup), null);
        mDialog.show();
    }

    @Override
    protected Boolean doInBackground() {
        try {
            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "starting upload task");
            // By creating a request, we get a handle to the putFile operation,
            // so we can cancel it later if we want to

            // save the file with a timestamp as the file name
            Calendar cal = Calendar.getInstance();
            String newFileName = cal.getTimeInMillis() / 1000 + FileHelper.database_extension;

            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "cal file name: " + cal.getTimeInMillis() + " new " + newFileName);

            String path = mPath + newFileName;
            // A consistent copy, never the live file. See FileHelper.snapshotDatabaseTo.
            File snapshot = FileHelper.snapshotForUpload(mContext);
            if (snapshot == null)
                return false;
            try (FileInputStream fis = new FileInputStream(snapshot)) {
                FileMetadata result = dbxClientV2.files().uploadBuilder(path).withMode(WriteMode.OVERWRITE).uploadAndFinish(fis);
                if (result != null)
                    return true;
            } finally {
                //noinspection ResultOfMethodCallIgnored
                snapshot.delete();
            }

        } catch (DbxException | IOException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    protected void onUncaught(RuntimeException e) {
        DropboxDialogs.dismiss(mDialog);
        showToast(mContext.getString(R.string.error_backing_up));
    }

    @Override
    protected void onPostExecute(Boolean result) {
        DropboxDialogs.dismiss(mDialog);
        if (result) {
            showToast(mContext.getString(R.string.successful_backup));
        } else {
            showToast(mContext.getString(R.string.error_backing_up));
        }
        mContext = null;
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        error.show();
    }
}
