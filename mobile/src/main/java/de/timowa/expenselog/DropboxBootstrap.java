package de.timowa.expenselog;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import javax.inject.Inject;

/**
 * Bootstraps Dropbox at startup: initialises the client if sync is on or a credential is stored,
 * then runs the sync check and the automatic backups.
 * <p/>
 * Was {@code UpgradeHelper}: it managed in-app purchasing and gated this bootstrap behind a
 * premium check. Billing is gone and every feature it gated is unconditional, so the bootstrap is
 * all that is left, and the class is now named for it.
 */
public class DropboxBootstrap {
    private final Activity activity;

    @Inject
    DropBoxHelper dropBoxHelper;
    @Inject
    PrefManager prefManager;

    @Inject
    public DropboxBootstrap(final Activity activity) {
        this.activity = activity;
    }

    public void initialize() {
        if (!isOnline()) return;

        // start dropbox init if dropbox sync is enabled or if a credential is already stored
        if (prefManager.dropboxSyncEnabled() || dropBoxHelper.hasStoredCredential()) {
            // initialize client
            dropBoxHelper.initializeDropboxV2();

            // run sync
            dropBoxHelper.onDropboxAction("WEWEWE", DropBoxHelper.KEY_DROPBOX_SYNC);

            // run backup process
            dropBoxHelper.autoBackup();
        }
    }

    private boolean isOnline() {
        ConnectivityManager cm =
                (ConnectivityManager) activity.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = cm.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : cm.getNetworkCapabilities(network);
        // INTERNET only, deliberately: NET_CAPABILITY_VALIDATED is set after the platform's
        // connectivity probe succeeds, so requiring it would skip the startup sync and the auto
        // backup on a network whose probe is blocked or has not finished yet -- a captive or
        // corporate Wi-Fi, a VPN, or simply the first seconds after associating. The old
        // isConnectedOrConnecting() was permissive in the same way, and a request that fails
        // reports itself.
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
}
