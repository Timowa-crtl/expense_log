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
import android.util.Log;
import android.widget.Toast;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.R;

/**
 *
 */

public class DropboxDelete extends DropboxTask<Boolean> {

    private Context mContext;
    private DbxClientV2 dbxClientV2;
    private String mPath;

    private boolean mCanceled;

    private String mErrorMsg = "";

    public DropboxDelete(Context context, DbxClientV2 clientV2, String dropboxPath) {
        // We set the context this way so we don't accidentally leak activities
        mContext = context.getApplicationContext();

        dbxClientV2 = clientV2;
        mPath = dropboxPath;
    }

    @Override
    protected Boolean doInBackground() {
        try {
            if (mCanceled) {
                return false;
            }

            String deleteFilePath = "/" + mPath;

            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "deleteFilePath file: " + deleteFilePath);

            // delete file
            dbxClientV2.files().delete(deleteFilePath);

            return true;

        } catch (DbxException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    protected void onPostExecute(Boolean result) {
        if (result) {
            // Set the image now that we have it
            showToast(mContext.getString(R.string.backup_deleted));
        } else {
            // Couldn't download it, so show an error
            showToast(mErrorMsg);
        }
        mContext = null;
    }

    private void showToast(String msg) {
        Toast error = Toast.makeText(mContext, msg, Toast.LENGTH_LONG);
        error.show();
    }
}
