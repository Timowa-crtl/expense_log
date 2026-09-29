package de.timowa.expenselog;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.DialogPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.DependencyInjection.ActivityModule;
import de.timowa.expenselog.DependencyInjection.DaggerActivityComponent;
import de.timowa.expenselog.DependencyInjection.HasComponent;
import de.timowa.expenselog.reminders.NotificationPermission;
import de.timowa.expenselog.reminders.ReminderManager;
import de.timowa.expenselog.reminders.TimePreference;
import de.timowa.expenselog.reminders.TimePreferenceDialogFragment;

/**
 * The settings screens: a root list, and a fragment behind each row.
 *
 * <p>An ordinary {@link AppCompatActivity} hosting {@link PreferenceFragmentCompat}s. It was a
 * {@code PreferenceActivity} with {@code <preference-headers>} and an {@code AppCompatDelegate}
 * bolted on, which is deprecated, builds its own decor, and was the reason two edge-to-edge bugs
 * were possible at all: it left the content root behind the status bar, and it renders a nested
 * {@code <PreferenceScreen>} as a bare dialog with no toolbar or insets. Neither can happen here,
 * because this is the same layout shape as {@code MainActivity}.
 *
 * <p>The root's rows live in {@code res/xml/pref_root.xml} and name their fragment in
 * {@code app:fragment}; {@link #onPreferenceStartFragment} opens it. A screen can also be opened
 * directly with {@link #EXTRA_FRAGMENT} -- the reminder notification's Settings action does that
 * through {@code Navigator.openSettings}, which is why the notification and the root row land on
 * one definition and cannot drift apart.
 *
 * <p>{@link #ALLOWED_FRAGMENTS} replaces the framework's {@code isValidFragment}, for the same
 * reason it existed: the fragment name arrives in an Intent extra, and another app can send one.
 */
public class SettingsActivity extends AppCompatActivity
        implements HasComponent<ActivityComponent>,
        PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    /** Fully-qualified fragment name to open instead of the root list. */
    public static final String EXTRA_FRAGMENT = "de.timowa.expenselog.extra.SETTINGS_FRAGMENT";

    /** String resource for that screen's toolbar title. */
    public static final String EXTRA_FRAGMENT_TITLE_RES = "de.timowa.expenselog.extra.SETTINGS_TITLE";

    /**
     * The only fragments this activity will instantiate.
     *
     * <p>{@link #EXTRA_FRAGMENT} is an Intent extra, so any app that can start this activity can
     * name a class here; without this list that is fragment injection. The legacy framework called
     * the same check {@code isValidFragment}.
     */
    private static final List<String> ALLOWED_FRAGMENTS = Arrays.asList(
            GeneralPreferenceFragment.class.getName(),
            RemindersPreferenceFragment.class.getName(),
            DataSyncPreferenceFragment.class.getName(),
            AccountSelectionPreferenceFragment.class.getName(),
            AboutFragment.class.getName());

    private ActivityComponent activityComponent;

    /**
     * A preference value change listener that updates the preference's summary to reflect its new
     * value.
     */
    private static final Preference.OnPreferenceChangeListener
            sBindPreferenceSummaryToValueListener = (preference, value) -> {
        String stringValue = value.toString();

        if (preference instanceof ListPreference) {
            ListPreference listPreference = (ListPreference) preference;
            int index = listPreference.findIndexOfValue(stringValue);

            // Set the summary to reflect the new value.
            preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);

            // if reminder frequency change, run reminder manager update
            if (preference.getKey().equals(preference.getContext().getResources()
                    .getString(R.string.pref_key_reminder_frequency))) {
                if (BuildConfig.DEBUG)
                    Log.i(MainActivity.REMINDER_TAG, "reminder frequency change");

                // save new frequency before updating reminder
                SharedPreferences prefs =
                        PreferenceManager.getDefaultSharedPreferences(preference.getContext());
                prefs.edit().putString(preference.getKey(), (String) value).apply();

                ReminderManager.updateReminder(preference.getContext());
            }
        } else {
            // For all other preferences, set the summary to the value's simple string
            // representation.
            preference.setSummary(stringValue);

            if (BuildConfig.DEBUG) Log.i("SETT", "change: " + preference);
        }
        return true;
    };

    /**
     * Binds a preference's summary to its value, and shows the current value at once.
     */
    private static void bindPreferenceSummaryToValue(Preference preference) {
        if (preference == null) return;
        if (BuildConfig.DEBUG) Log.i("PREF", "pref: " + preference.getTitle());
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                PreferenceManager.getDefaultSharedPreferences(preference.getContext())
                        .getString(preference.getKey(), ""));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settings_toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        if (BuildConfig.DEBUG) Log.i("SettingsActivity", "Setting activity onCreate");
        this.activityComponent = DaggerActivityComponent.builder()
                .appComponent(((MyLogApplication) getApplication()).getComponent())
                .activityModule(new ActivityModule(this))
                .build();
        activityComponent.injectActivity(this);

        // The title follows the back stack: a sub-screen names itself when it is pushed, and
        // popping back restores whatever is underneath. Without this the toolbar would keep the
        // sub-screen's title after Back, which is the sort of drift the old header list hid.
        getSupportFragmentManager().addOnBackStackChangedListener(this::updateTitle);

        if (savedInstanceState == null) {
            String fragmentName = getIntent().getStringExtra(EXTRA_FRAGMENT);
            if (fragmentName != null && ALLOWED_FRAGMENTS.contains(fragmentName)) {
                // No back-stack entry: this screen *is* the activity, so Back leaves it rather
                // than revealing a root list the user never asked for. Same shape the
                // EXTRA_NO_HEADERS pairing used to give.
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.settings_container, instantiate(fragmentName))
                        .commit();
            } else {
                getSupportFragmentManager().beginTransaction()
                        .replace(R.id.settings_container, new RootFragment())
                        .commit();
            }
        }

        // Also on a recreation, where the back stack is restored before the listener above is
        // registered, so no change event fires and the toolbar would otherwise fall back to the
        // activity's manifest label: rotating on the reminder screen said "Settings".
        updateTitle();
    }

    private Fragment instantiate(String name) {
        return getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), name);
    }

    /** The toolbar title: the screen on top of the back stack, the deep-linked screen, or Settings. */
    private void updateTitle() {
        FragmentManager fm = getSupportFragmentManager();
        int count = fm.getBackStackEntryCount();
        if (count > 0) {
            CharSequence name = fm.getBackStackEntryAt(count - 1).getName();
            if (name != null) {
                setTitle(name);
                return;
            }
        }
        // A screen opened directly by EXTRA_FRAGMENT has no back-stack entry to name it.
        int titleRes = getIntent().getIntExtra(EXTRA_FRAGMENT_TITLE_RES, 0);
        if (count == 0 && titleRes != 0 && getIntent().getStringExtra(EXTRA_FRAGMENT) != null) {
            setTitle(titleRes);
            return;
        }
        setTitle(R.string.settings);
    }

    /** Opens the fragment a root row names. */
    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller,
                                             @NonNull Preference pref) {
        String fragmentName = pref.getFragment();
        if (fragmentName == null || !ALLOWED_FRAGMENTS.contains(fragmentName)) return false;
        CharSequence title = pref.getTitle();
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings_container, instantiate(fragmentName))
                .addToBackStack(title == null ? null : title.toString())
                .commit();
        return true;
    }

    @Override
    public ActivityComponent getComponent() {
        return activityComponent;
    }

    /**
     * Receives the POST_NOTIFICATIONS result for the request the reminder switch makes.
     *
     * <p>A fragment's permission request is routed through its hosting activity, so this has to
     * live here rather than in {@code RemindersPreferenceFragment}.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        NotificationPermission.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** The settings root: one row per screen, from {@code pref_root.xml}. */
    public static class RootFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_root, rootKey);
        }
    }

    /**
     * The app's own preferences: starting screen, formats, first day of week, currency.
     */
    public static class GeneralPreferenceFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_general, rootKey);

            // Bind the summaries of list preferences to their values, so each row shows what it
            // is set to rather than only what it is.
            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_starting_screen)));
            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_after_new_log_screen)));
            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_date_format)));
            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_time_format)));
            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_first_day_of_week)));
            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_currency)));
        }
    }

    /**
     * What the app is, where it lives, and who holds the copyright.
     *
     * <p>A plain {@link Fragment} rather than a preference screen, and the only settings screen
     * that is: it hosts a layout of its own so the icon, name and version can sit together as a
     * header. The host instantiates whatever fragment a row names, so this costs nothing
     * structurally.
     *
     * <p>Replaces the AlertDialog that the overflow menu used to raise, which had room for a
     * version and one link and no more.
     */
    public static class AboutFragment extends Fragment {

        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                                 Bundle savedInstanceState) {
            View view = inflater.inflate(R.layout.fragment_about, container, false);

            TextView version = view.findViewById(R.id.about_version);
            version.setText(getString(R.string.about_version, versionName()));

            openOnClick(view, R.id.about_play, R.string.url_google_play);
            openOnClick(view, R.id.about_fdroid, R.string.url_f_droid);
            openOnClick(view, R.id.about_github, R.string.url_github);

            return view;
        }

        /** The versionName from the installed package, so it cannot drift from the build. */
        private String versionName() {
            try {
                return requireActivity().getPackageManager()
                        .getPackageInfo(requireActivity().getPackageName(), 0).versionName;
            } catch (PackageManager.NameNotFoundException e) {
                // Asking the package manager about the package we are running in. If this happens
                // the install is broken in ways a version string will not explain.
                Log.e("ABOUT", "could not read this app's own version", e);
                return "";
            }
        }

        /**
         * Opens a URL in whatever handles it.
         *
         * <p>Guarded, because a device with no browser -- or with links handed to a profile the
         * app cannot reach -- throws {@link ActivityNotFoundException} rather than doing nothing,
         * and an About screen is not worth a crash.
         */
        private void openOnClick(View parent, int buttonId, final int urlRes) {
            parent.findViewById(buttonId).setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(getString(urlRes))));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(getActivity(), R.string.about_no_browser,
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    /**
     * The reminder settings: the switch, the frequency and the time.
     *
     * <p>A screen of its own, and deliberately so. These three preferences used to sit in a nested
     * {@code <PreferenceScreen>}, which the legacy framework opened as a bare dialog -- no
     * toolbar, no title, and no window insets, so under edge-to-edge the first row was drawn
     * behind the status bar.
     *
     * <p>Both ways in land here: the root row, and the Settings action on the reminder
     * notification, which deep-links straight at this fragment through
     * {@code Navigator.openSettings}. One definition, so they cannot drift apart.
     */
    public static class RemindersPreferenceFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_reminders, rootKey);

            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_reminder_frequency)));

            SwitchPreferenceCompat reminderEnablePreference =
                    findPreference(getString(R.string.pref_key_reminder_enable));
            if (reminderEnablePreference != null) {
                reminderEnablePreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    // update reminders
                    // new value isn't implemented until after, so implement opposite
                    boolean reminderEnable = (boolean) newValue;
                    if (BuildConfig.DEBUG)
                        Log.i(MainActivity.REMINDER_TAG, "reminder enabled: " + reminderEnable);
                    if (reminderEnable) {
                        ReminderManager.setupReminder(preference.getContext());
                        // The moment the user actually asks for reminders is the moment to ask for
                        // the permission they need from API 33 on. Without the grant the alarm
                        // still fires and notify() does nothing at all, so the switch would read
                        // "on" while nothing ever arrived.
                        NotificationPermission.request(getActivity());
                    } else {
                        ReminderManager.disableReminder(preference.getContext());
                    }
                    return true;
                });
            }

            // reminder time preference update
            Preference time = findPreference(getString(R.string.pref_key_reminder_time));
            if (time != null) {
                time.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (BuildConfig.DEBUG)
                        Log.i("RTEST", "time pref change: " + preference.getKey()
                                + " val: " + newValue);

                    // save new time preference
                    SharedPreferences prefs =
                            PreferenceManager.getDefaultSharedPreferences(preference.getContext());
                    prefs.edit().putLong(preference.getKey(), (long) newValue).apply();

                    // update reminders
                    ReminderManager.updateReminder(preference.getContext());
                    return true;
                });
            }
        }

        /**
         * The time preference needs its own dialog fragment.
         *
         * <p>AndroidX only knows how to show the dialogs for its own preference types; without
         * this the reminder time could not be set at all.
         */
        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if (preference instanceof TimePreference) {
                DialogPreference dialogPreference = (DialogPreference) preference;
                TimePreferenceDialogFragment dialog =
                        TimePreferenceDialogFragment.newInstance(dialogPreference.getKey());
                dialog.setTargetFragment(this, 0);
                dialog.show(getParentFragmentManager(), "TimePreferenceDialog");
                return;
            }
            super.onDisplayPreferenceDialog(preference);
        }
    }

    /** One row per account, each opening that account's own settings. */
    public static class AccountSelectionPreferenceFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(requireContext());

            for (AccountItem account : getAccountList()) {
                // for each account add an intent preference with the account ID as an extra
                Intent intent = new Intent(requireContext(), AccountPreferencesActivity.class);
                intent.putExtra(MainActivity.EXTRAS_KEY_ACCOUNT_ID, account.getId());
                Preference preference = new Preference(requireContext());
                preference.setTitle(account.getTitle());
                preference.setIntent(intent);
                screen.addPreference(preference);
            }

            setPreferenceScreen(screen);
        }

        private ArrayList<AccountItem> getAccountList() {
            DBAdapter db = new DBAdapter(requireContext());
            Cursor accountListCursor = db.getAccounts();
            ArrayList<AccountItem> tempList = new ArrayList<>();

            if (accountListCursor != null) {
                while (accountListCursor.moveToNext()) {
                    AccountItem item = new AccountItem();
                    item.setId(accountListCursor.getInt(DBAdapter.COLUMN_TAG_ID));
                    item.setTitle(accountListCursor.getString(DBAdapter.COLUMN_TAG_LABEL));
                    tempList.add(item);
                }
                accountListCursor.close();
            }
            db.close();
            return tempList;
        }

        static class AccountItem {
            private String title;
            private int id;

            public int getId() {
                return id;
            }

            public void setId(int id) {
                this.id = id;
            }

            public String getTitle() {
                return title;
            }

            public void setTitle(String title) {
                this.title = title;
            }
        }
    }

    /** Dropbox sync, the manual backup, and the database import and export. */
    public static class DataSyncPreferenceFragment extends PreferenceFragmentCompat {

        @Inject
        DropboxBootstrap dropboxBootstrap;
        @Inject
        DropBoxHelper dropBoxHelper;

        @SuppressWarnings("unchecked")
        protected <C> C getComponent(Class<C> componentType) {
            return componentType.cast(((HasComponent<C>) requireActivity()).getComponent());
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.pref_data_sync, rootKey);
        }

        @Override
        public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            if (BuildConfig.DEBUG) Log.i("SettingsActivity", "datasync activity onCreate");

            this.getComponent(ActivityComponent.class).inject(this);
            dropboxBootstrap.initialize();

            if (BuildConfig.DEBUG)
                Log.i(MainActivity.DROPBOX_TAG, "dropbox activity created");

            // if coming to sync screen and sync is enabled, try to init dropbox
            SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(requireContext());
            if (prefs.getBoolean(getString(R.string.pref_key_dropbox_sync_enable), false))
                dropBoxHelper.initializeDropboxV2();

            SwitchPreferenceCompat dropboxSyncPreference =
                    findPreference(getString(R.string.pref_key_dropbox_sync_enable));
            if (dropboxSyncPreference != null) {
                dropboxSyncPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                    if (BuildConfig.DEBUG)
                        Log.i(MainActivity.DROPBOX_TAG, "drop sync enabled: " + newValue);

                    // if syncing enabled
                    if ((boolean) newValue) {
                        dropBoxHelper.onDropboxAction("", DropBoxHelper.KEY_DROPBOX_SYNC);
                    } else {
                        // remove dropbox credential if syncing is disabled
                        dropBoxHelper.clearStoredCredential();
                    }
                    return true;
                });
            }

            setClickListener(R.string.pref_key_dropbox_do_backup, sDropboxListener);
            setClickListener(R.string.pref_key_dropbox_view_backups, sDropboxListener);

            setClickListener(R.string.pref_key_backup_db, preference -> {
                // No permission check: the export is staged in the app's own directory, and
                // the picker grants write access to the one document the user chooses.
                //
                // FileHelper stages and reports; the picker is launched from here because it
                // needs a Fragment or an Activity and FileHelper has only a Context.
                FileHelper.showExportMessage(preference.getContext(),
                        staged -> exportLauncher.launch(
                                FileHelper.createExportIntent(
                                        FileHelper.exportDbFileName(requireContext()),
                                        FileHelper.DB_MIME_TYPE)),
                        staged -> startActivity(FileHelper.createShareIntent(requireContext(),
                                staged, FileHelper.DB_MIME_TYPE,
                                FileHelper.exportDbFileName(requireContext()))));
                return false;
            });

            setClickListener(R.string.pref_key_import_db, preference -> {
                // No permission check: the system picker grants access to the one document the
                // user chooses, so WRITE_EXTERNAL_STORAGE was never what made this work.
                importLauncher.launch(FileHelper.createImportIntent());
                return false;
            });
        }

        private void setClickListener(int keyRes, Preference.OnPreferenceClickListener listener) {
            Preference preference = findPreference(getString(keyRes));
            if (preference != null) preference.setOnPreferenceClickListener(listener);
        }

        @Override
        public void onResume() {
            super.onResume();

            dropBoxHelper.completeDropboxV2Init();

            // A resume that follows a declined login must not start another one -- that is the
            // loop this guard exists for. handleDeclinedAuth() turns the preference back off, so
            // the check below no longer matches and the switch stops claiming sync is on.
            if (dropBoxHelper.handleDeclinedAuth()) {
                SwitchPreferenceCompat syncPreference =
                        findPreference(getString(R.string.pref_key_dropbox_sync_enable));
                if (syncPreference != null) syncPreference.setChecked(false);
                Toast.makeText(getActivity(), R.string.dropbox_not_connected,
                        Toast.LENGTH_SHORT).show();
                return;
            }

            // if coming to sync screen and sync is enabled, AND a credential is not already
            // stored, try to init dropbox
            if (!dropBoxHelper.hasStoredCredential()) {
                SharedPreferences prefs =
                        PreferenceManager.getDefaultSharedPreferences(requireContext());
                if (prefs.getBoolean(getString(R.string.pref_key_dropbox_sync_enable), false))
                    dropBoxHelper.initializeDropboxV2();
            }
        }

        /**
         * The file the user picked to import.
         *
         * <p>Registered as a field: {@code registerForActivityResult} must be called before the
         * fragment is STARTED, and a field initializer runs when it is constructed.
         */
        private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() == Activity.RESULT_OK
                            && data != null && data.getData() != null) {
                        FileHelper.confirmAndImport(getActivity(), data.getData());
                    }
                });

        /** Where the user chose to save the exported database. */
        private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Intent data = result.getData();
                    if (result.getResultCode() != Activity.RESULT_OK
                            || data == null || data.getData() == null) {
                        // Cancelled. Drop the staged copy rather than leave it in a directory no
                        // file manager has been able to reach since Android 11.
                        FileHelper.discardPendingExport(requireContext());
                        return;
                    }
                    // On the main thread, so this caller may present its own result.
                    boolean saved = FileHelper.copyStagedFileTo(requireContext(),
                            FileHelper.takePendingExport(requireContext()), data.getData());
                    Toast.makeText(getActivity(),
                            saved ? R.string.export_saved : R.string.export_save_failed,
                            Toast.LENGTH_LONG).show();
                });

        private final Preference.OnPreferenceClickListener sDropboxListener = preference -> {
            // No storage permission needed: the Dropbox paths use getCacheDir() and the
            // internal database.
            if (preference.getKey().equals(preference.getContext().getResources()
                    .getString(R.string.pref_key_dropbox_do_backup))) {

                if (BuildConfig.DEBUG)
                    Log.i(MainActivity.DROPBOX_TAG, "drop test manual");

                dropBoxHelper.onDropboxAction("test", DropBoxHelper.KEY_DROPBOX_MAKE_BACKUP);

            } else if (preference.getKey().equals(preference.getContext().getResources()
                    .getString(R.string.pref_key_dropbox_view_backups))) {

                if (BuildConfig.DEBUG)
                    Log.i(MainActivity.DROPBOX_TAG, "drop test list");

                dropBoxHelper.showDropboxBackupsActivity();
            }
            return false;
        };
    }
}
