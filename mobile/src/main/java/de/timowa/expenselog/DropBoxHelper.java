package de.timowa.expenselog;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;


import com.dropbox.core.DbxRequestConfig;
import com.dropbox.core.android.Auth;
import com.dropbox.core.json.JsonReadException;
import com.dropbox.core.oauth.DbxCredential;
import com.dropbox.core.v2.DbxClientV2;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import de.timowa.expenselog.dropbox.BackupListActivity;
import de.timowa.expenselog.dropbox.DropboxAutoBackups;
import de.timowa.expenselog.dropbox.DropboxDelete;
import de.timowa.expenselog.dropbox.DropboxDownload;
import de.timowa.expenselog.dropbox.DropboxGetBackups;
import de.timowa.expenselog.dropbox.DropboxNewBackup;
import de.timowa.expenselog.dropbox.DropboxSyncCheck;
import de.timowa.expenselog.dropbox.DropboxUpload;

/**
 * Handles Dropbox
 */
public class DropBoxHelper implements DropboxGetBackups.OnTaskComplete,
        DropboxDownload.OnTaskComplete, DropboxSyncCheck.OnTaskComplete, DropboxUpload.OnTaskComplete {

    private static final String DROPBOX_HELPER = "Dropbox Helper";
    /** The download type of a restore from the backup list; see DropboxDownload. */
    public static final String DROPBOX_RESTORE_FLAG = "restore";
    //    private static DropboxAPI<AndroidAuthSession> mDBApi;
    private static DbxClientV2 dbxClientV2;
    // Whether the credential handed back by the auth flow has already been picked up in this
    // process. Auth.getDbxCredential() consumes nothing -- it rebuilds a DbxCredential from the
    // SDK's static AuthActivity.result Intent on every call, and that static lives as long as the
    // process -- so without this flag completeDropboxV2Init() re-runs its whole post-auth branch,
    // toast and sync and auto-backup included, on every resume of a screen that calls it.
    // Reset only where a new auth is actually started, in initializeDropboxV2() below.
    private static boolean authCredentialConsumed;

    // Whether an auth flow this class started is still waiting for its result. Set when
    // Auth.startOAuth2PKCE() is called and cleared by handleDeclinedAuth(), which is what tells a
    // decline apart from an ordinary resume. Static for the same reason as the flag above: the
    // flow leaves our task entirely and comes back to a different activity instance.
    private static boolean authInProgress;

    // The four scopes every dbxClientV2.files().* call in dropbox/ actually needs -- see
    // docs/history/REHABILITATION_PLAN.md Step 6 for the audit. Must match what's enabled on the app's
    // Permissions tab, or the first API call fails with a scope error at auth time.
    private static final List<String> SCOPES = Arrays.asList(
            "files.metadata.read", "files.metadata.write",
            "files.content.read", "files.content.write");

    private static final String KEY_DBX_CREDENTIAL = "dbx_credential";

    /** Opened lazily and held; see {@link #getEncryptedPrefs()} for why it is not reopened. */
    private SecretStore secretStore;

    public static final int KEY_DROPBOX_UPLOAD = 8;
    public static final int KEY_DROPBOX_DOWNLOAD = 1;
    public static final int KEY_DROPBOX_UPDATED_DB = 2;
    public static final int KEY_DROPBOX_SYNC = 3;
    public static final int KEY_DROPBOX_MAKE_BACKUP = 4;
    public static final int KEY_DROPBOX_SYNC_CONFLICT_RESOLVE = 7;
    /** A sync check that skips the rate limit: sent after an upload or download found a conflict. */
    public static final int KEY_DROPBOX_SYNC_NOW = 9;

    final public static String DROPBOX_TAG = "DROPTEST";

    @Inject
    Navigator navigator;
    @Inject
    Utility utility;
    @Inject
    PrefManager prefManager;

    private Activity activity;

    @Inject
    public DropBoxHelper(final Activity activity) {
        this.activity = activity;
    }

    // initialized dropbox v2 client
    void initializeDropboxV2() {
//        analyticsTracker.send(DROPBOX_HELPER, MainActivity.DROPBOX_TAG, "Dropbox V2 Initializing");
        DbxCredential credential = getStoredCredential();
        if (credential != null) {
            DbxRequestConfig config = DbxRequestConfig.newBuilder("expenseLog").build();
            // Passing the full DbxCredential (not just the access token) lets DbxClientV2 refresh
            // the short-lived access token itself, transparently, off the long-lived refresh token.
            dbxClientV2 = new DbxClientV2(config, credential);
        } else {
            // no stored credential, start auth process
            if (BuildConfig.DEBUG)
                Log.i(MainActivity.DROPBOX_TAG, "start dropbox auth process");
            // A new flow will overwrite AuthActivity.result, so its next value is worth consuming.
            authCredentialConsumed = false;
            authInProgress = true;
            // Bare key (no "db-" prefix); both forms are generated by mobile/build.gradle from
            // dropbox.appKey in local.properties, which is not in version control.
            Auth.startOAuth2PKCE(activity.getApplicationContext(),
                    activity.getString(R.string.dropbox_app_key),
                    DbxRequestConfig.newBuilder("expenseLog").build(), SCOPES);
        }
    }

    /**
     * call this in the resume method of an activity that starts a dropbox initialization
     */
    void completeDropboxV2Init() {
        // Idempotent per auth flow, and deliberately so at the method rather than at the call
        // sites: a caller that forgets the guard would otherwise resurrect a credential the user
        // has just cleared by turning sync off, since clearing the store does not clear the SDK's
        // static copy of it.
        if (authCredentialConsumed) return;

        DbxCredential credential = Auth.getDbxCredential();
        if (credential != null) {
            authCredentialConsumed = true;
            authInProgress = false;
            storeCredential(credential);
            DbxRequestConfig config = DbxRequestConfig.newBuilder("expenseLog").build();
            dbxClientV2 = new DbxClientV2(config, credential);

            Toast.makeText(activity, "Dropbox Authentication Complete", Toast.LENGTH_SHORT).show();

            // after completing initial dropbox setup, check if syncing is on to sync, and autobackup
            // dropbox sync if premium and enabled
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
            if (prefs.getBoolean(activity.getString(R.string.pref_key_dropbox_sync_enable), false))
                onDropboxAction("WEWEWE", KEY_DROPBOX_SYNC);

            // do dropbox auto-backup if premium and if dropbox is already initialized
            autoBackup();
        } else {
            if (BuildConfig.DEBUG) Log.i(DROPBOX_TAG, "dropbox auth token was null");
        }
    }

    /**
     * Handle an auth flow that came back without a credential -- the user declined, by backing out
     * of the Dropbox login instead of finishing it.
     *
     * <p>Call this from {@code onResume}, after {@link #completeDropboxV2Init()} has had its
     * chance to pick up a credential. It returns true only for a flow this class actually started
     * that produced nothing, and in that case it turns the sync preference back off.
     *
     * <p>That preference is the whole problem. A {@code SwitchPreference} persists the moment it
     * is tapped, so sync reads as enabled long before an account is connected, and declining never
     * unset it. The resume that follows the decline then saw "sync enabled, no credential", which
     * is exactly the condition {@link #initializeDropboxV2()} treats as "start an auth" -- so
     * backing out of the login relaunched the login, with no way out of the screen. Clearing the
     * preference here breaks that cycle at its source, and also stops the same condition firing
     * from {@code DropboxBootstrap.initialize()} on the next cold start.
     *
     * <p>Two limits worth knowing. The in-flight marker is process-local: if the app is killed
     * while the login is open the marker is lost, and the next visit to the sync screen offers the
     * login once more before this catches a second decline -- one prompt, not a loop. And coming
     * back to the app while the login is still open, through recents rather than by finishing it,
     * looks exactly like a decline from here, so it is treated as one; the SDK offers nothing that
     * tells the two apart. Finishing the login afterwards still stores the credential, it just
     * leaves sync switched off until the user turns it on again.
     */
    boolean handleDeclinedAuth() {
        if (!authInProgress) return false;
        authInProgress = false;
        if (hasStoredCredential()) return false;
        if (BuildConfig.DEBUG)
            Log.i(DROPBOX_TAG, "dropbox auth declined, turning sync back off");
        prefManager.setDropboxSyncEnabled(false);
        return true;
    }

    /**
     * Whether a Dropbox credential is already on disk -- callers use this instead of poking at
     * the (now encrypted) prefs file directly to decide whether {@link #initializeDropboxV2()} or
     * {@link #completeDropboxV2Init()} is needed.
     */
    boolean hasStoredCredential() {
        return getStoredCredential() != null;
    }

    /** Called when the user turns Dropbox sync off. */
    void clearStoredCredential() {
        secretStore().remove(KEY_DBX_CREDENTIAL);
    }

    private DbxCredential getStoredCredential() {
        String serialized = secretStore().get(KEY_DBX_CREDENTIAL);
        if (serialized == null) return null;
        try {
            return DbxCredential.Reader.readFully(serialized);
        } catch (JsonReadException e) {
            if (BuildConfig.DEBUG)
                Log.i(DROPBOX_TAG, "stored dropbox credential unreadable: " + e);
            return null;
        }
    }

    private void storeCredential(DbxCredential credential) {
        secretStore().put(KEY_DBX_CREDENTIAL, DbxCredential.Writer.writeToString(credential));
    }

    /**
     * The credential store, opened once and held.
     *
     * <p><b>Cached deliberately.</b> Opening it touches the Android Keystore, which was happening
     * on the main thread on every {@code onResume}/{@code onCreate} that checked for a credential
     * -- twice per resume, for a file whose contents had not changed. Finding 7 of
     * {@code docs/history/REVIEW_FINDINGS.md}.
     *
     * <p>The first open also deletes the {@code security-crypto} store this replaced. That library
     * was deprecated in full with no successor, so nothing can read its file any more; deleting it
     * and its master key is the only thing left to do with it, and the user logs into Dropbox once
     * more.
     */
    private SecretStore secretStore() {
        if (secretStore == null) {
            secretStore = new SecretStore(activity.getApplicationContext());
            secretStore.discardLegacyStore();
        }
        return secretStore;
    }

    void autoBackup() {
        // do auto-backup if it hasn't been done within limit
        if (dbxClientV2 != null) {
            if (prefManager.autoBackupLimitCheck()) {
                prefManager.setAutoBackupTime();
                DropboxAutoBackups dropboxAuto = new DropboxAutoBackups(activity, dbxClientV2, "/",
                        FileHelper.getInternalDbFile(activity.getApplicationContext()));
                dropboxAuto.execute();
            } else {
                if (BuildConfig.DEBUG)
                    Log.i(MainActivity.DROPBOX_TAG, "too soon to auto-backup check again");
            }
        } else {
            initializeDropboxV2();
        }
    }

    @Override
    public void onDropboxAction(String message, int requiredAction) {
        // The storage-permission gate that used to wrap this is gone: everything under dropbox/
        // reads and writes getCacheDir() and the internal database, neither of which needs one.
        {
            if (BuildConfig.DEBUG)
                Log.i(MainActivity.DROPBOX_TAG, "drop test action: " + requiredAction);
            if (dbxClientV2 != null) {
                if (BuildConfig.DEBUG) Log.i(DROPBOX_TAG, "the message " + message);
                switch (requiredAction) {
                    case KEY_DROPBOX_SYNC:
                        // check last sync time, if less than limit, don't sync
                        if (prefManager.syncLimitCheck()) {
                            // save time of this sync to limit frequency
                            prefManager.setSyncTime();

                            DropboxSyncCheck sync = new DropboxSyncCheck(activity, dbxClientV2, "/");
                            sync.setMyTaskCompleteListener(this);
                            sync.execute();
                        } else {
                            if (BuildConfig.DEBUG)
                                Log.i(MainActivity.DROPBOX_TAG, "too soon to sync check again");
                        }
                        break;
                    case KEY_DROPBOX_SYNC_NOW:
                        DropboxSyncCheck syncNow = new DropboxSyncCheck(activity, dbxClientV2, "/");
                        syncNow.setMyTaskCompleteListener(this);
                        syncNow.execute();
                        break;
                    case KEY_DROPBOX_UPLOAD:
                        // Conditional: it succeeds only if the remote is still the version this
                        // device last saw. A refusal comes back as KEY_DROPBOX_SYNC_NOW.
                        DropboxUpload upload = new DropboxUpload(activity, dbxClientV2, "/",
                                FileHelper.getInternalDbFile(activity.getApplicationContext()), false);
                        upload.setMyTaskCompleteListener(this);
                        upload.execute();
                        break;
                    case KEY_DROPBOX_DOWNLOAD:
                        // get default dropbox sync db with same name as internal

                        DropboxDownload download = new DropboxDownload(activity, dbxClientV2, "/"
                                + DBAdapter.DATABASE_NAME, "");
                        download.setMyTaskCompleteListener(this);
                        // Only ever sent by the sync check, with the marker it decided on.
                        download.setMarkerSeenByCheck(message);
                        download.execute();
                        break;
                    case KEY_DROPBOX_UPDATED_DB:
                        // if local database was updated with a remote, upload the current database as the main remote
                        if (message.equals(DROPBOX_RESTORE_FLAG)) {
                            if (BuildConfig.DEBUG)
                                Log.i(MainActivity.DROPBOX_TAG, "update remote with current local after downloading an new version");
                            // Forced: the user chose to make this backup the main database.
                            DropboxUpload up = new DropboxUpload(activity, dbxClientV2, "/",
                                    FileHelper.getInternalDbFile(activity.getApplicationContext()), true);
                            up.execute();
                        }

                        Utility.log(MainActivity.DROPBOX_TAG, "try showing snackbar");

                        // take user back to main screen and show "updated" msg
                        Toast.makeText(activity.getApplicationContext(),
                                activity.getApplicationContext().getResources().getString(R.string.filehelper_import_success),
                                Toast.LENGTH_LONG).show();
//                        navigator.refreshFrag();

//                        // let user know data changed, offer option to refresh
//                        if (activity.findViewById(R.id.coordinator_layout) != null) {
//                            if (BuildConfig.DEBUG)
//                                Log.i(MainActivity.DROPBOX_TAG, "show snackbar with refresh option");
//                            Snackbar snackbar = Snackbar
//                                    .make(activity.findViewById(R.id.coordinator_layout),
//                                            activity.getString(R.string.local_data_updated), Snackbar.LENGTH_LONG)
//                                    .setAction(activity.getResources().getString(R.string.refresh), new View.OnClickListener() {
//                                        @Override
//                                        public void onClick(View view) {
//                                            navigator.refreshFrag();
//                                        }
//                                    });
//                            snackbar.show();
//                        }

                        break;
                    case KEY_DROPBOX_MAKE_BACKUP:
                        DropboxNewBackup task = new DropboxNewBackup(activity, dbxClientV2, "/",
                                FileHelper.getInternalDbFile(activity.getApplicationContext()));
                        task.execute();
                        break;

                    case KEY_DROPBOX_SYNC_CONFLICT_RESOLVE:
                        // show conflict resolution dialog that warns users about their action
                        showConflictResolveWarningDialog(message);
                        break;
                }
            } else {
                if (BuildConfig.DEBUG) Log.i(DROPBOX_TAG, "client is null, init client");
                initializeDropboxV2();
            }
        }
    }

    private void showConflictResolveWarningDialog(final String action) {
        final AlertDialog.Builder alertDialog = new AlertDialog.Builder(activity);

        if (action.equals("local")) {
            // if local
            alertDialog.setMessage(activity.getString(R.string.upload_local_database_overwrite_remote));
        } else if (action.equals("remote")) {
            // if remote
            alertDialog.setMessage(activity.getString(R.string.download_remote_overwrite_local));
        }
        alertDialog.setTitle(activity.getString(R.string.conflict_resolution));
        alertDialog.setPositiveButton(activity.getResources().getText(R.string.yes),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        if (action.equals("local")) {
                            // Forced: the user chose "keep local" in the conflict dialog.
                            DropboxUpload upload = new DropboxUpload(activity, dbxClientV2, "/",
                                    FileHelper.getInternalDbFile(activity.getApplicationContext()), true);
                            upload.execute();
                        } else if (action.equals("remote")) {
                            // download remote
                            DropboxDownload download = new DropboxDownload(activity, dbxClientV2, "/"
                                    + DBAdapter.DATABASE_NAME, "");
                            download.setMyTaskCompleteListener(DropBoxHelper.this);
                            download.execute();
                        }
                    }
                });
        // Setting Negative "NO" Button
        alertDialog.setNegativeButton(activity.getResources().getText(R.string.cancel),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });
        // Showing Alert Message
        alertDialog.show();
    }

    void showDropboxBackupsActivity() {
        if (BuildConfig.DEBUG) Log.i(DROPBOX_TAG, "showDropboxBackupsActivity 1");
        if (dbxClientV2 != null) {
            DropboxGetBackups dropboxBackups = new DropboxGetBackups(activity, dbxClientV2, "");
            dropboxBackups.setMyTaskCompleteListener(this);
            dropboxBackups.execute();
            if (BuildConfig.DEBUG) Log.i(DROPBOX_TAG, "showDropboxBackupsActivity 2");
        } else {
//            initialize();
            initializeDropboxV2();
        }
    }

    @Override
    public void onDropboxBackupsReturn
            (ArrayList<String> namesList, ArrayList<String> timesList) {
        if (BuildConfig.DEBUG) Log.i(DROPBOX_TAG, "onDropboxBackupsReturn");

        Intent i = new Intent(activity, BackupListActivity.class);
        i.putExtra("list", namesList);
        i.putExtra("timeList", timesList);
        // Plain start: nothing ever read this activity's result, and the backup list
        // reports what it did through the Dropbox tasks it starts.
        activity.startActivity(i);
    }

    public void dropboxDeleteRemoteFile(String fileName) {
        if (dbxClientV2 != null) {
            if (BuildConfig.DEBUG) Log.i(DROPBOX_TAG, "dropboxDeleteRemoteFile: " + fileName);
            DropboxDelete dropbox = new DropboxDelete(activity, dbxClientV2, fileName);
            dropbox.execute();
        } else {
            initializeDropboxV2();
        }
    }

    public void dropboxImportBackup(String fileName) {
        if (dbxClientV2 != null) {
            if (BuildConfig.DEBUG) Log.i(DROPBOX_TAG, "dropboxImportBackup: " + fileName);
            DropboxDownload download = new DropboxDownload(activity, dbxClientV2, "/" + fileName, DROPBOX_RESTORE_FLAG);
            download.setMyTaskCompleteListener(DropBoxHelper.this);
            download.execute();
        } else {
            initializeDropboxV2();
        }
    }
}
