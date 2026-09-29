package de.timowa.expenselog;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

/**
 * One account's settings, in that account's own preference file.
 *
 * <p>Each account keeps its budget in {@code account<id>}, not in the default preferences, which
 * is what {@code setSharedPreferencesName} below arranges. The account id arrives as an Intent
 * extra from the Accounts list in {@link SettingsActivity}.
 *
 * <p>An {@link AppCompatActivity} hosting a {@link PreferenceFragmentCompat}, like
 * {@code SettingsActivity}. It was a {@code PreferenceActivity} reached through
 * {@code EXTRA_SHOW_FRAGMENT} plus {@code EXTRA_NO_HEADERS}; AndroidX has neither, and the
 * fragment is simply this activity's content.
 */
public class AccountPreferencesActivity extends AppCompatActivity {

    private static final String EXTRAS_KEY_ACCOUNT_ID = "extra_account_id";

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

        Bundle bundle = savedInstanceState != null ? savedInstanceState : getIntent().getExtras();
        int passedAccount = bundle == null ? 0 : bundle.getInt(EXTRAS_KEY_ACCOUNT_ID);

        DBAdapter db = new DBAdapter(getApplicationContext());
        Cursor accountCursor = db.getAccount(passedAccount);
        String accountLabel = "";
        if (accountCursor != null) {
            accountCursor.moveToFirst();
            accountLabel = accountCursor.getString(DBAdapter.COLUMN_ACCOUNT_LABEL);
            accountCursor.close();
        }
        db.close();
        setTitle(accountLabel + " " + getString(R.string.settings));

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settings_container,
                            GeneralAccountSettingsPreferenceFragment.newInstance(passedAccount))
                    .commit();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Bundle extras = getIntent().getExtras();
        if (extras != null) outState.putInt(EXTRAS_KEY_ACCOUNT_ID, extras.getInt(EXTRAS_KEY_ACCOUNT_ID));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** The budget switch, amount and period for one account. */
    public static class GeneralAccountSettingsPreferenceFragment extends PreferenceFragmentCompat {

        private static final String ARG_ACCOUNT_ID = "account_id";

        static GeneralAccountSettingsPreferenceFragment newInstance(int accountId) {
            GeneralAccountSettingsPreferenceFragment fragment =
                    new GeneralAccountSettingsPreferenceFragment();
            Bundle args = new Bundle(1);
            args.putInt(ARG_ACCOUNT_ID, accountId);
            fragment.setArguments(args);
            return fragment;
        }

        private int accountId() {
            Bundle args = getArguments();
            return args == null ? 0 : args.getInt(ARG_ACCOUNT_ID);
        }

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            // set the preference file for the passed account
            getPreferenceManager().setSharedPreferencesName("account" + accountId());
            setPreferencesFromResource(R.xml.pref_account_settings, rootKey);

            Preference budgetEnable = findPreference(getString(R.string.pref_key_budget_enable));
            if (budgetEnable != null) budgetEnable.setOnPreferenceChangeListener(budgetListener);
            EditTextPreference budgetAmount =
                    findPreference(getString(R.string.pref_key_budget_amount));
            if (budgetAmount != null) {
                // AndroidX ignores android:inputType in the XML; this is the only way to set it
                budgetAmount.setOnBindEditTextListener(editText -> {
                    editText.setInputType(
                            InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                    editText.setSingleLine(true);
                    editText.selectAll();
                });
            }
            bindPreferenceSummaryToValue(budgetAmount);
            bindPreferenceSummaryToValue(findPreference(getString(R.string.pref_key_budget_period)));
        }

        /**
         * Binds a preference's summary to its value, reading from this account's own file rather
         * than the default preferences.
         */
        private void bindPreferenceSummaryToValue(Preference preference) {
            if (preference == null) return;
            if (BuildConfig.DEBUG) Log.i("PREF", "pref: " + preference.getTitle());
            preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);
            SharedPreferences prefs = getPreferenceManager().getSharedPreferences();
            sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                    prefs == null ? "" : prefs.getString(preference.getKey(), ""));
        }

        private final Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener =
                (preference, value) -> {
                    String stringValue = value.toString();
                    if (preference instanceof ListPreference) {
                        ListPreference listPreference = (ListPreference) preference;
                        int index = listPreference.findIndexOfValue(stringValue);
                        preference.setSummary(
                                index >= 0 ? listPreference.getEntries()[index] : null);
                    } else {
                        preference.setSummary(stringValue);
                    }
                    return true;
                };

        /**
         * Explains, when a budget is switched on, that it only shows while a single account is
         * being viewed. Informational: the change is always accepted.
         */
        private final Preference.OnPreferenceChangeListener budgetListener =
                (preference, newValue) -> {
                    if ((boolean) newValue) {
                        new AlertDialog.Builder(preference.getContext())
                                .setMessage(R.string.budget_enable_info)
                                .setPositiveButton(R.string.ok, (dialog, which) -> dialog.dismiss())
                                .show();
                    }
                    return true;
                };
    }
}
