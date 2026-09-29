package de.timowa.expenselog;

import androidx.appcompat.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.FontAwesomeIcons;
import de.timowa.expenselog.icons.MaterialIcons;

import java.util.ArrayList;
import java.util.Calendar;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.DependencyInjection.ActivityModule;
import de.timowa.expenselog.DependencyInjection.DaggerActivityComponent;
import de.timowa.expenselog.DependencyInjection.HasComponent;
import de.timowa.expenselog.reminders.NotificationPermission;
import de.timowa.expenselog.reminders.ReminderManager;
import de.timowa.expenselog.reminders.ReminderReceiver;
import de.timowa.expenselog.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity
        implements NavigationView.OnNavigationItemSelectedListener,
        HasComponent<ActivityComponent>,
        TimePickerFragment.TimePickedListener,
        DatePickerFragment.DatePickedListener,
        FilterDialogFragment.onFilterUpdateListener,
        SortingDialogFragment.onSortingUpdateListener,
        GroupingDialogFragment.onGroupingUpdateListener {

    private static final String NAV_TAG = "navigation";
    static final String EXTRAS_KEY_ACCOUNT_ID = "extra_account_id";
    private ActivityComponent activityComponent;

    public static ArrayList<ArrayList<Integer>> mFilterArray = null;

    public static String DROPBOX_TAG = DropBoxHelper.DROPBOX_TAG;
    public static final String REMINDER_TAG = "RTEST";

    // back presses through drawer screens, counted from the last drawer tap, that close the app;
    // backing out of a sub-screen does not count (see onBackPressed)
    public static final int BACK_PRESS_LIMIT = 3;
    public static int backPressCount = BACK_PRESS_LIMIT;

    @Inject
    PrefManager prefManager;
    @Inject
    Navigator navigator;
    @Inject
    DropboxBootstrap dropboxBootstrap;
    @Inject
    Utility utility;
    @Inject
    LaunchManager launchManager;
    @Inject
    DropBoxHelper dropBoxHelper;

    DrawerLayout drawer;

    Toolbar toolbar;

    NavigationView navigationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityMainBinding binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        drawer = binding.drawerLayout;
        toolbar = binding.appBarMain.toolbar;
        navigationView = binding.navView;

        this.activityComponent = DaggerActivityComponent.builder()
                .appComponent(((MyLogApplication) getApplication()).getComponent())
                .activityModule(new ActivityModule(this))
                .build();
        activityComponent.injectActivity(this);

        // Records the install date if this is the first launch. It used to live in
        // RatingManager.initialize(), which went with the rating prompt -- but the timestamp is
        // not the rating system's: LocalBackupManager gates the weekly and monthly backups on it.
        // Left unwritten it reads 0, which makes "time since first launch" about fifty years and
        // fires both backups immediately on a fresh install. Here it is also more accurate than
        // where it was, which was the first visit to the Records screen rather than the first
        // launch.
        prefManager.setFirstLaunch();

        setSupportActionBar(toolbar);

        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close) {
            /** Called when a drawer has settled in a completely closed state. */
            public void onDrawerClosed(View view) {
                super.onDrawerClosed(view);
                if (BuildConfig.DEBUG) Log.i("DRAW", "close");
                invalidateOptionsMenu(); // creates call to onPrepareOptionsMenu()
            }

            /** Called when a drawer has settled in a completely open state. */
            public void onDrawerOpened(View drawerView) {
                super.onDrawerOpened(drawerView);
                if (BuildConfig.DEBUG) Log.i("DRAW", "open");
                navigator.clearSoftKeyboard();
                invalidateOptionsMenu(); // creates call to onPrepareOptionsMenu()
            }
        };
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        setupDrawerMenuIcons();
        navigationView.setNavigationItemSelectedListener(this);

        registerBackHandling();

        launchManager.appStartup();

        // if not recreating a previously destroyed instance show first page
        if (savedInstanceState == null) {
            if (BuildConfig.DEBUG) Log.i(NAV_TAG, "save instance not null");
            // A fresh start shows today. exitApp clears this too, but the process can outlive
            // an activity that never went through it (swiped from Recents, reclaimed).
            ViewedPeriod.clear();
            // set the intent that's used to import a file
            navigator.changeFragment(MainActivity.this, navigationView.getMenu().findItem(prefManager.getStartScreen()));
        }

        // if sent here by opening db file show prompt
        checkForFileImport(getIntent());

        // add backstack listener
        navigator.addBackStackListener();

        // check if coming from notification
        checkForNotificationFlags(getIntent());

        dropboxBootstrap.initialize();

        // From API 33 a reminder cannot be posted without POST_NOTIFICATIONS, and notify() fails
        // silently without it. Asked here rather than at the alarm, which fires with no UI to
        // prompt from. Does nothing when reminders are switched off, or below 33.
        NotificationPermission.requestIfRemindersEnabled(this);

        if (BuildConfig.DEBUG) Log.i(NAV_TAG, "main activity created");
    }

    @SuppressWarnings("UnusedReturnValue")
    private boolean checkForNotificationFlags(Intent intent) {
        // if sent here from notification send user to appropriate screen
        if (intent != null) {
            int reminderAction = intent.getIntExtra(ReminderManager.ACTION_KEY, 0);
            if (BuildConfig.DEBUG) Log.d("RTEST", "passed intent: " + reminderAction);
            if (reminderAction == ReminderManager.ACTION_NEW_LOG
                    || reminderAction == ReminderManager.ACTION_SETTINGS) {
                // A notification action button is not covered by setAutoCancel -- only the content
                // intent is -- and since Step 5 the Settings button opens this activity directly
                // rather than passing through ReminderReceiver, which used to do the cancelling.
                ReminderReceiver.dismissNotification(this);
            }

            switch (reminderAction) {
                case ReminderManager.ACTION_NEW_LOG:

                    navigator.changeFragment(MainActivity.this, navigationView.getMenu().findItem(R.id.nav_new_log));
                    return true;
                case ReminderManager.ACTION_SETTINGS:

                    // Straight to the reminder settings themselves. The button says Settings
                    // because it sits on a reminder; General is only the screen that hosts the
                    // Reminders row, and stopping there still leaves the user looking for it.
                    navigator.openSettings(this,
                            SettingsActivity.RemindersPreferenceFragment.class.getName(),
                            R.string.reminders);
                    return true;
            }
        }
        return false;
    }

    private void checkForFileImport(Intent intent) {
        String fullUrl = intent.getDataString();
        if (fullUrl != null) {
            {
                // No permission check: the URI arrives with its own read grant from whichever app
                // sent it, and the destination is the app's internal database.
                final Uri uri = intent.getData();

                // set intent data to null to prevent asking for import again on activity recreation
                Intent newIntent = getIntent();
                newIntent.setData(null);
                setIntent(newIntent);

                // Only content:// can be imported, so refuse anything else *before* offering to
                // overwrite every record. There used to be a file:// branch here. It never worked
                // -- it passed uri.getPath(), a bare path, to a reader that parsed it as a URI --
                // and fixing that in Step 4 only moved the failure: since Phase 2 removed every
                // storage permission, the app may not read the /sdcard path a file:// URI names,
                // so the import ends in EACCES instead. The branch was unreachable by design on
                // API 29+; it is deleted rather than left looking supported. See docs/history/RECOVERY_PLAN.md.
                if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
                    Toast.makeText(getApplicationContext(),
                            getString(R.string.import_unsupported_source), Toast.LENGTH_LONG).show();
                    return;
                }

                AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
                alertDialog.setTitle(getResources().getText(R.string.dialog_import_title));
                alertDialog.setMessage(getResources().getText(R.string.dialog_import_confirm));
                alertDialog.setPositiveButton(getResources().getText(R.string.yes),
                        (dialog, which) -> {
                            // content:// only; the guard above has already turned everything else
                            // away. The whole URI goes across, not uri.getPath().
                            FileHelper.ImportResult result = FileHelper.importFileAndVerify(
                                    getApplicationContext(), uri.toString(), getContentResolver());
                            // The message names the reason -- a refusal, a rollback, or a rollback
                            // that failed -- instead of one "Error Importing" for all of it.
                            Toast.makeText(getApplicationContext(), getString(result.messageRes()),
                                    Toast.LENGTH_LONG).show();
                            if (result.isOk()) {
                                // refresh screen after import
                                navigator.refreshFrag();
                            }
                        });
                // Setting Negative "NO" Button
                alertDialog.setNegativeButton(getResources().getText(R.string.cancel),
                        (dialog, which) -> dialog.cancel());
                // Showing Alert Message
                alertDialog.show();
            }
        }
    }

    @Override
    public ActivityComponent getComponent() {
        return activityComponent;
    }

    /**
     * Receives the destination the user picked for a CSV export.
     *
     * <p>The export is raised from {@code LogTabsFragment} through {@code SpreadsheetHelper},
     * which holds this activity, so the result comes back here rather than to the fragment.
     * Registered as a field, which runs during construction, well before the activity is STARTED
     * -- {@code registerForActivityResult} refuses any later.
     */
    private final ActivityResultLauncher<Intent> csvExportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() != RESULT_OK || data == null || data.getData() == null) {
                    // Cancelled, or came back with nothing. Drop the staged copy: it lives where
                    // no file manager can reach it, so leaving it behind means it is there forever.
                    FileHelper.discardPendingExport(this);
                    return;
                }
                // On the main thread, so this caller may present its own result.
                boolean saved = FileHelper.copyStagedFileTo(this,
                        FileHelper.takePendingExport(this), data.getData());
                Toast.makeText(this,
                        getString(saved ? R.string.export_saved : R.string.export_save_failed),
                        Toast.LENGTH_LONG).show();
            });

    /** The launcher {@code SpreadsheetHelper} sends its "where should this go" intent to. */
    public ActivityResultLauncher<Intent> csvExportLauncher() {
        return csvExportLauncher;
    }

    /**
     * The app's only runtime-permission result. {@code PermissionHelper} was deleted in Step 4
     * precisely because it requested a permission and never implemented this method, so the request
     * had no way to report back.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        NotificationPermission.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    public void onResume() {
        super.onResume();
//        dropBoxHelper.completeDropboxInitialization();

        // if dropbox is enabled
        if (prefManager.dropboxSyncEnabled() && !dropBoxHelper.hasStoredCredential()) {
            dropBoxHelper.completeDropboxV2Init();
        }

        // DropboxBootstrap.initialize() can start an auth flow from onCreate, so a decline can land
        // here rather than on the settings screen. There is no switch to correct on this screen;
        // clearing the preference is what stops the next cold start opening the login again.
        dropBoxHelper.handleDeclinedAuth();

        if (BuildConfig.DEBUG) Log.i(NAV_TAG, "main activity resume");
    }

    /**
     * Back: close the drawer if it is open, otherwise either leave the app or pop a screen.
     *
     * <p>This is an {@link OnBackPressedCallback} on the dispatcher, not an {@code onBackPressed()}
     * override. At targetSdk 36 the override is never called -- the manifest used to carry
     * {@code enableOnBackInvokedCallback="false"} to get it called anyway, because
     * androidx.activity resolved to 1.0.0, which predates back callbacks. The activity library is
     * current now and this callback is the app's only back handler.
     *
     * <p>It stays enabled for the activity's whole life and pops the back stack itself. The
     * dispatcher runs callbacks newest-first, and {@code FragmentActivity} adds its own from
     * {@code super.onCreate}, so this one -- added later, here -- is reached first and the
     * fragment manager's never runs. That is why it calls {@code popBackStack} directly:
     * {@code onBackPressed()} would re-enter the dispatcher and loop back into this callback.
     * Nothing is left unhandled when the back stack is empty, because
     * {@link #backLeavesApp(boolean, int)} treats one entry or none as "leave the app".
     *
     * <p>The selection action mode consumes Back ahead of all of this, through AppCompat's own
     * back callback, which is what keeps clearing a selection from counting towards the exit cap.
     */
    private void registerBackHandling() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START);
                    return;
                }
                if (backLeavesApp(navigator.isOnSubScreen(),
                        getSupportFragmentManager().getBackStackEntryCount())) {
                    navigator.exitApp();
                } else {
                    getSupportFragmentManager().popBackStack();
                }
            }
        });
    }

    /**
     * Decides a Back press the drawer did not take: true to leave the app, false to go back a
     * screen. Separate from onBackPressed so BackNavigationTest can drive it without this
     * activity, which opens the real database on the way up.
     *
     * <p>Leaving a sub-screen (an editor, New Category) always returns to the screen below and
     * never counts. It used to count, so the third Edit -> Back from a list closed the app. Back
     * through drawer screens stays capped: BACK_PRESS_LIMIT presses since the last drawer tap leave
     * the app. On the last screen there is nothing to go back to, so it leaves at once rather than
     * popping that screen and showing an empty frame.
     */
    static boolean backLeavesApp(boolean onSubScreen, int backStackEntries) {
        if (onSubScreen) return false;
        backPressCount = backPressCount - 1;
        return backPressCount <= 0 || backStackEntries <= 1;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        menu.findItem(R.id.action_settings).setIcon(new IconDrawable(this, MaterialIcons.md_settings)
                .actionBarSize().colorRes(R.color.actionBarWhite));
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            navigator.openSettings(this);

            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        if (BuildConfig.DEBUG) Log.i(NAV_TAG, "nav item selected");
        // Handle navigation view item clicks here.
        navigator.changeFragment(this, item);

        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        // Always call the superclass so it can save the view hierarchy state
        super.onSaveInstanceState(savedInstanceState);
    }

    private void setupDrawerMenuIcons() {
        Menu navMenu = navigationView.getMenu();
        navMenu.findItem(R.id.nav_new_log).setIcon(new IconDrawable(this, MaterialIcons.md_edit)
                .colorRes(R.color.menu_icon_color)
                .actionBarSize());
        navMenu.findItem(R.id.nav_categories).setIcon(new IconDrawable(this, MaterialIcons.md_collections)
                .colorRes(R.color.menu_icon_color)
                .actionBarSize());
        navMenu.findItem(R.id.nav_accounts).setIcon(new IconDrawable(this, FontAwesomeIcons.fa_users)
                .colorRes(R.color.menu_icon_color)
                .actionBarSize());
        navMenu.findItem(R.id.nav_recurring).setIcon(new IconDrawable(this, FontAwesomeIcons.fa_history)
                .colorRes(R.color.menu_icon_color)
                .actionBarSize());
        navMenu.findItem(R.id.nav_summary).setIcon(new IconDrawable(this, MaterialIcons.md_assignment)
                .colorRes(R.color.menu_icon_color)
                .actionBarSize());
        navMenu.findItem(R.id.nav_calendar).setIcon(new IconDrawable(this, MaterialIcons.md_insert_invitation)
                .colorRes(R.color.menu_icon_color)
                .actionBarSize());
        navMenu.findItem(R.id.nav_list).setIcon(new IconDrawable(this, FontAwesomeIcons.fa_list)
                .colorRes(R.color.menu_icon_color)
                .actionBarSize());
        navMenu.findItem(R.id.nav_graph).setIcon(new IconDrawable(this, FontAwesomeIcons.fa_line_chart)
                .colorRes(R.color.menu_icon_color)
                .actionBarSize());
    }

    @Override
    public void onTimePicked(Calendar time) {
        NewLogFragment frag = (NewLogFragment) getSupportFragmentManager().findFragmentById(R.id.mainContentFragment);
        if (frag != null)
            frag.onTimePicked(time);
    }

    @Override
    public void onDatePicked(Calendar date) {
        NewLogFragment frag = (NewLogFragment) getSupportFragmentManager().findFragmentById(R.id.mainContentFragment);
        if (frag != null)
            frag.onDatePicked(date);
    }

    @Override
    public void onUpdateFilter(ArrayList<ArrayList<Integer>> newFilter) {
        mFilterArray = newFilter;
        LogTabsFragment frag = (LogTabsFragment) getSupportFragmentManager().findFragmentById(R.id.mainContentFragment);
        if (frag != null)
            frag.updateFilter(newFilter);
    }

    @Override
    public void onUpdateSort(int sortSetting) {
        prefManager.setListSortSetting(sortSetting);
//        navigator.refreshFrag();
        LogTabsFragment frag = (LogTabsFragment) getSupportFragmentManager().findFragmentById(R.id.mainContentFragment);
        if (frag != null)
            frag.updateRecordsFragment();
    }

    @Override
    public void onGroupingUpdate(int groupingSetting) {
        prefManager.setGraphGroupingPreference(groupingSetting);
//        navigator.refreshFrag();
        LogTabsFragment frag = (LogTabsFragment) getSupportFragmentManager().findFragmentById(R.id.mainContentFragment);
        if (frag != null)
            frag.updateRecordsFragment();
    }
}
