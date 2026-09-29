package de.timowa.expenselog.dropbox;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.util.Log;
import android.widget.Toast;

import com.dropbox.core.DbxException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.SearchResult;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import de.timowa.expenselog.BuildConfig;
import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.FileHelper;
import de.timowa.expenselog.MainActivity;
import de.timowa.expenselog.R;

/**
 * get list of backups
 */

public class DropboxGetBackups extends DropboxTask<Boolean> {

    private Context mContext;
    private final Dialog mDialog;
    //    private DropboxAPI<?> mApi;
    private DbxClientV2 dbxClientV2;
    private String mPath;
    private ArrayList<String> backupsFileNamesList = new ArrayList<String>();
    private ArrayList<String> backupsModifiedTimeList = new ArrayList<String>();
    private FileOutputStream mFos;

    private boolean mCanceled;
    private String mErrorMsg = "";

    private OnTaskComplete onTaskComplete;

    public interface OnTaskComplete {
        void onDropboxBackupsReturn(ArrayList<String> filesList, ArrayList<String> timesList);
    }

    public void setMyTaskCompleteListener(OnTaskComplete onTaskComplete) {
        this.onTaskComplete = onTaskComplete;
    }

    public DropboxGetBackups(Context context, DbxClientV2 clientV2, String dropboxPath) {
        // We set the context this way so we don't accidentally leak activities
        mContext = context.getApplicationContext();

        dbxClientV2 = clientV2;
        mPath = dropboxPath;

        mDialog = DropboxDialogs.progress(context, context.getString(R.string.getting_information),
                (dialog, which) -> {
                    mCanceled = true;
                    mErrorMsg = "Canceled";

                    // This will cancel the listing by closing its stream
                    if (mFos != null) {
                        try {
                            mFos.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                });

        mDialog.show();
    }

    @Override
    protected Boolean doInBackground() {
        if (BuildConfig.DEBUG)
            Log.i(DropBoxHelper.DROPBOX_TAG, "start backup list task");
        try {
            if (mCanceled) {
                return false;
            }
            // Get the metadata for a directory
            ListFolderResult folderResult = dbxClientV2.files().listFolder("");

            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "isdbremote: " + folderResult.getEntries().size());
            List<Metadata> filesList = folderResult.getEntries();
            // Straight from the folder listing: this used to run a search first just to learn
            // whether any backup existed, and cast every entry to FileMetadata, which threw on a
            // folder.
            for (int i = 0; filesList.size() > i; i++) {
                if (!(filesList.get(i) instanceof FileMetadata))
                    continue;
                FileMetadata file = (FileMetadata) filesList.get(i);
                if (file.getName().contains(FileHelper.database_extension)) {
                    if (BuildConfig.DEBUG)
                        Log.i(DropBoxHelper.DROPBOX_TAG, "file list: " + i + " " + file);

                    backupsFileNamesList.add(file.getName());

                    Calendar cal = Calendar.getInstance();
                    cal.setTimeInMillis(file.getServerModified().getTime());// all done

                    if (BuildConfig.DEBUG)
                        Log.i(DropBoxHelper.DROPBOX_TAG, "formatted modified time: " + cal.getTime());

                    backupsModifiedTimeList.add(cal.getTime().toString());

                }
            }
//            Entry dirent = mApi.metadata(mPath, 1000, null, true, null);

//            if (!dirent.isDir || dirent.contents == null) {
//                // It's not a directory, or there's nothing in it
//                mErrorMsg = "File or empty directory";
//                return false;
//            }
//
//            // Make a list of everything in it that we can get a thumbnail for
//            for (Entry fileEntry : dirent.contents) {
//                // only add file to list if it has thew database extension
//                if (fileEntry.fileName().contains(FileHelper.database_extension)) {
//                    backupsFileNamesList.add(fileEntry.fileName());
//
//                    Calendar cal = Calendar.getInstance();
//                    SimpleDateFormat df = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.getDefault());
//                    try {
//                        cal.setTime(df.parse(fileEntry.modified));// all done
//                    } catch (ParseException e) {
//                        e.printStackTrace();
//                    }
//
//                    if (BuildConfig.DEBUG)
//                        Log.i(DropBoxHelper.DROPBOX_TAG, "formatted modified time: " + cal.getTime());
//
//                    backupsModifiedTimeList.add(cal.getTime().toString());
//                    if (BuildConfig.DEBUG)
//                        Log.i(DropBoxHelper.DROPBOX_TAG, "directory file: " + fileEntry.fileName()
//                                + " modified: " + fileEntry.modified + " rev: " + fileEntry.rev + " size: " + fileEntry.size + " mimetype: " + fileEntry.mimeType);
//                }
//            }

            if (mCanceled) {
                return false;
            }
            if (backupsFileNamesList.isEmpty()) {
                mErrorMsg = "No Backups";
                return false;
            }

            return true;

        } catch (DbxException e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    protected void onUncaught(RuntimeException e) {
        DropboxDialogs.dismiss(mDialog);
    }

    @Override
    protected void onPostExecute(Boolean result) {
        DropboxDialogs.dismiss(mDialog);
        if (result) {
            // Set the image now that we have it
//            showToast("Database Updated");

            if (BuildConfig.DEBUG)
                Log.i(DropBoxHelper.DROPBOX_TAG, "backuplist: " + backupsFileNamesList.size() + " taskcomplete: " + onTaskComplete.toString());

            //Sending update to UI in MainActivity for new DB
            onTaskComplete.onDropboxBackupsReturn(backupsFileNamesList, backupsModifiedTimeList);
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
