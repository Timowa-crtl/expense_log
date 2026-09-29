package de.timowa.expenselog;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Log;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Currency;
import java.util.Locale;

import javax.inject.Inject;

public class PrefManager {

    private SharedPreferences preferences;
    private Context context;

    private final static DateFormat timeText = new SimpleDateFormat("h:mm a", AppLocale.TEXT);
    private final static DateFormat timeText24 = new SimpleDateFormat("H:mm", AppLocale.TEXT);
    // English month names whatever the device language; see AppLocale
    private final static DateFormat dateFormatMedium = new SimpleDateFormat("MMM d, yyyy", AppLocale.TEXT);
    private final static DateFormat dateFormatMDY = new SimpleDateFormat("MM/dd/yy", AppLocale.TEXT);
    private final static DateFormat dateFormatDMY = new SimpleDateFormat("dd/MM/yy", AppLocale.TEXT);
    // ISO 8601, extended format (with separators) and basic format (without). Both are
    // machine-readable, so both are pinned to Locale.US rather than the device locale: a locale
    // with non-Latin digits would otherwise render them in those digits and the result would no
    // longer be ISO 8601.
    private final static DateFormat dateFormatIso = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final static DateFormat dateFormatIsoBasic = new SimpleDateFormat("yyyyMMdd", Locale.US);

    /**
     * What a fresh install renders on screen, and what any unrecognised stored value falls back to.
     *
     * <p>Must stay in step with {@code R.string.default_date_format}, which is what
     * {@code android:defaultValue} in {@code pref_general.xml} gives the ListPreference to
     * pre-select. {@code PrefManagerDateFormatTest.declaredDefault_matchesTheCodeDefault} pins
     * the two together.
     *
     * <p><b>This is the on-screen default only.</b> A CSV export does not consult it, or any other
     * part of the Date Format setting — {@link CsvFormatter} pins exports to ISO 8601 so that the
     * file stays machine-readable whatever the app is displaying. The two were coupled until the
     * export clean-up, and changing this line used to change the export with it.
     */
    private final static DateFormat defaultDateFormat = dateFormatMedium;

    // The stored values of pref_key_date_format, kept in step with R.array.pref_date_format_values.
    private final static String DATE_FORMAT_MEDIUM = "0";
    private final static String DATE_FORMAT_MDY = "1";
    private final static String DATE_FORMAT_DMY = "2";
    private final static String DATE_FORMAT_ISO = "5";
    private final static String DATE_FORMAT_ISO_BASIC = "6";

    /** The value that selects {@link #defaultDateFormat}. */
    private final static String DATE_FORMAT_DEFAULT = DATE_FORMAT_MEDIUM;

    @Inject
    public PrefManager(final Context context) {
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.context = context;
        migrateRetiredDateFormat();
    }

    /**
     * Rewrites a retired date format value to the default.
     *
     * <p>Values {@code "3"} ({@code EEE, MMM d/yy}) and {@code "4"} ({@code EEE, MMM d}) were
     * removed from the Date Format setting. A phone that had one of them selected still holds it
     * in preferences, and a stored value that is no longer in
     * {@code R.array.pref_date_format_values} leaves the settings row with no radio selected and
     * a blank summary — {@code SettingsActivity}'s summary listener maps an unknown value to a
     * {@code null} summary. {@link #getDateFormat()} already falls back to the default for such a
     * value, so this only brings the stored value and the UI back into agreement.
     *
     * <p>Cheap and idempotent: one string read per construction, and a write only on the first
     * construction after the update.
     */
    private void migrateRetiredDateFormat() {
        String stored = preferences.getString(context.getString(R.string.pref_key_date_format), null);
        if ("3".equals(stored) || "4".equals(stored)) {
            if (BuildConfig.DEBUG)
                Log.i("PREF", "retiring date format " + stored + ", falling back to default");
            preferences.edit()
                    .putString(context.getString(R.string.pref_key_date_format), DATE_FORMAT_DEFAULT)
                    .apply();
        }
    }

    SharedPreferences getPrefsFile() {
        return preferences;
    }

    DateFormat getDateFormat() {
        DateFormat dFormat = defaultDateFormat;
        if (preferences.contains(context.getString(R.string.pref_key_date_format))) {
            String userTimePref = preferences.getString(context.getString(R.string.pref_key_date_format),
                    DATE_FORMAT_DEFAULT);
            // An unrecognised value — a retired one that migrateRetiredDateFormat has not yet
            // rewritten — keeps the default it was initialised with.
            if (userTimePref.equals(DATE_FORMAT_MEDIUM)) {
                dFormat = dateFormatMedium;
            } else if (userTimePref.equals(DATE_FORMAT_MDY)) {
                dFormat = dateFormatMDY;
            } else if (userTimePref.equals(DATE_FORMAT_DMY)) {
                dFormat = dateFormatDMY;
            } else if (userTimePref.equals(DATE_FORMAT_ISO)) {
                dFormat = dateFormatIso;
            } else if (userTimePref.equals(DATE_FORMAT_ISO_BASIC)) {
                dFormat = dateFormatIsoBasic;
            }
        }
        return dFormat;
    }

    DateFormat getTimeFormat() {
        DateFormat tFormat = timeText;
        if (preferences.contains(context.getString(R.string.pref_key_time_format))) {
            String userTimePref = preferences.getString(context.getString(R.string.pref_key_time_format), "0");
            if (userTimePref.equals("0")) {
                tFormat = timeText;
            } else if (userTimePref.equals("1")) {
                tFormat = timeText24;
            }
        } else {
            String systemPref = android.provider.Settings.System.getString(context.getContentResolver()
                    , android.provider.Settings.System.TIME_12_24);
            if (systemPref != null) {
                if (systemPref.equals("12")) {
                    tFormat = timeText;
                } else if (systemPref.equals("24")) {
                    tFormat = timeText24;
                }
            }
        }
        return tFormat;
    }

    void setDefaultTimeFormat() {
        if (!preferences.contains(context.getString(R.string.pref_key_time_format))) {
            String systemPref = android.provider.Settings.System.getString(context.getContentResolver()
                    , android.provider.Settings.System.TIME_12_24);
            if (systemPref != null) {
                if (systemPref.equals("12")) {
                    preferences.edit().putString(context.getString(R.string.pref_key_time_format), "0").apply();
                } else if (systemPref.equals("24")) {
                    preferences.edit().putString(context.getString(R.string.pref_key_time_format), "1").apply();
                }
            }
        }
    }

    boolean firstRunCheck() {
        // if first run isn't set, return false, otherwise set it and return true
        if (preferences.contains(context.getString(R.string.pref_key_first_run))) {
            return false;
        } else {
            preferences.edit().putBoolean(context.getString(R.string.pref_key_first_run), false).apply();
            return true;
        }
    }

    int getDefaultLogExpenseIncome() {
        return preferences.getInt(context.getString(R.string.pref_key_default_expense_income), 0);
    }

    void setDefaultLogExpenseIncome(int newDef) {
        preferences.edit().putInt(context.getString(R.string.pref_key_default_expense_income), newDef).apply();
    }

    public String formatMoney(double amount) {
        String formatted = "";
        // if a preference doesn't exist, or isn't set to default
        if (preferences.contains(context.getString(R.string.pref_key_currency))
                && !preferences.getString(context.getString(R.string.pref_key_currency), "")
                .equals(context.getString(R.string.default_string))) {
            // if negative, remove negative and place it before currency
            if (amount < 0) {
                amount = amount * -1;
                formatted = "-";
            }
            NumberFormat customMoneyFormat = NumberFormat.getCurrencyInstance();
            customMoneyFormat.setCurrency(getCurrencyCode(preferences.getString(context.getString(R.string.pref_key_currency), "$")));

            log("CURR", "currency: " + customMoneyFormat.format(amount));
            formatted += customMoneyFormat.format((amount));

            // if DKK is used as currency symbol, replace it with kr
            if (formatted.contains("DKK"))
                formatted = formatted.replace("DKK", "kr");
            else if (formatted.contains("PHP"))
                formatted = formatted.replace("PHP", "₱");
            else if (formatted.contains("SGD"))
                formatted = formatted.replace("SGD", "S$");
            else if (formatted.contains("MYR"))
                formatted = formatted.replace("MYR", "RM");
            else if (formatted.contains("THB"))
                formatted = formatted.replace("THB", "฿");
            else if (formatted.contains("AMD"))
                formatted = formatted.replace("AMD", "֏");
            else if (formatted.contains("PKR"))
                formatted = formatted.replace("PKR", "₨");

        } else {
            //   use system default
            NumberFormat moneyFormat = NumberFormat.getCurrencyInstance();
            formatted = moneyFormat.format(amount);
        }
        return formatted;
    }

    /**
     * The ISO 4217 code for the Currency column of a CSV export — {@code EUR}, {@code USD}, ...
     *
     * <p>Replaces {@code formatMoneyForSpreadsheet}, which wrapped {@link #formatMoney(double)} in
     * quotes and so wrote the amount as <i>display</i> currency: symbol, non-breaking space, locale
     * grouping and locale decimal separator, all inside a quoted string. No spreadsheet could read
     * that column as a number. The amount is now a bare number ({@link CsvFormatter#amount(double)})
     * and the currency it is denominated in lives here, in a column of its own, so nothing is lost.
     *
     * <p>Mirrors {@code formatMoney}'s own branch: the currency preference when one is set to
     * something other than Default, and the system currency otherwise.
     */
    public String getCurrencyCodeForCsv() {
        if (preferences.contains(context.getString(R.string.pref_key_currency))
                && !preferences.getString(context.getString(R.string.pref_key_currency), "")
                .equals(context.getString(R.string.default_string))) {
            return getCurrencyCode(preferences.getString(context.getString(R.string.pref_key_currency), "$"))
                    .getCurrencyCode();
        }
        Currency systemCurrency = NumberFormat.getCurrencyInstance().getCurrency();
        return systemCurrency == null ? "" : systemCurrency.getCurrencyCode();
    }

    @SuppressWarnings("IfCanBeSwitch")
    private Currency getCurrencyCode(String currencySymbol) {
        log("CURR", "currencySymbol: " + currencySymbol);
        Currency currency = Currency.getInstance(Locale.getDefault());
        if (currencySymbol.equals("$"))
            currency = Currency.getInstance("USD");
        else if (currencySymbol.equals("¥"))
            currency = Currency.getInstance("JPY");
        else if (currencySymbol.equals("€"))
            currency = Currency.getInstance("EUR");
        else if (currencySymbol.equals("£"))
            currency = Currency.getInstance("GBP");
        else if (currencySymbol.equals("₩"))
            currency = Currency.getInstance("KRW");
        else if (currencySymbol.equals("₪"))
            currency = Currency.getInstance("ILS");
        else if (currencySymbol.equals("₫"))
            currency = Currency.getInstance("VND");
        else if (currencySymbol.equals("₹"))
            currency = Currency.getInstance("INR");
        else if (currencySymbol.equals("kr"))
            currency = Currency.getInstance("DKK");
        else if (currencySymbol.equals("₱"))
            currency = Currency.getInstance("PHP");
        else if (currencySymbol.equals("S$"))
            currency = Currency.getInstance("SGD");
        else if (currencySymbol.equals("RM"))
            currency = Currency.getInstance("MYR");
        else if (currencySymbol.equals("฿"))
            currency = Currency.getInstance("THB");
        else if (currencySymbol.equals("֏"))
            currency = Currency.getInstance("AMD");
        else if (currencySymbol.equals("₨"))
            currency = Currency.getInstance("PKR");

        log("CURR", "new symbol: " + currency.getSymbol());
        return currency;
    }

    int getAccountDefault() {
        return preferences.getInt(context.getString(R.string.pref_key_default_account), 0);
    }

    void setAccountDefault(int accountDefault) {
        preferences.edit().putInt(context.getString(R.string.pref_key_default_account), accountDefault).apply();
    }

    int getExpenseCategoryDefault() {
        return preferences.getInt(context.getString(R.string.pref_key_default_expense_tag), 999);
    }

    void setExpenseCategoryDefault(int catDef) {
        preferences.edit().putInt(context.getString(R.string.pref_key_default_expense_tag), catDef).apply();
    }

    int getIncomeCategoryDefault() {
        return preferences.getInt(context.getString(R.string.pref_key_default_income_tag), 999);
    }

    void setIncomeCategoryDefault(int catDef) {
        preferences.edit().putInt(context.getString(R.string.pref_key_default_income_tag), catDef).apply();
    }

    int getTimeViewModeDefault() {
        return preferences.getInt(context.getString(R.string.pref_key_last_records_time_mode), 0);
    }

    void setLastRecordsTimeMode(int recordsTimeMode) {
        preferences.edit().putInt(context.getString(R.string.pref_key_last_records_time_mode), recordsTimeMode).apply();
    }

    void setNewTagFromNewLog(int newCatId) {
        preferences.edit().putInt(context.getString(R.string.pref_key_new_tag_from_new_log), newCatId).apply();
    }

    int getNewTagFromNewLog() {
        return preferences.getInt(context.getString(R.string.pref_key_new_tag_from_new_log), -1);
    }

    void clearNewTagFromNewLogSetting() {
        preferences.edit().remove(context.getString(R.string.pref_key_new_tag_from_new_log)).apply();
    }

    int getStartScreen() {
        return getMenuID(preferences.getString(context.getString(R.string.pref_key_starting_screen), "0"));
    }

    int getAfterNewLogScreen() {
        return getMenuID(preferences.getString(context.getString(R.string.pref_key_after_new_log_screen), "2"));
    }

    private int getMenuID(String string) {
        int selected = Integer.parseInt(string);
        int menuId = R.id.nav_new_log;
        switch (selected) {
            case 0:
                menuId = R.id.nav_new_log;
                break;
            case 1:
                menuId = R.id.nav_summary;
                break;
            case 2:
                menuId = R.id.nav_list;
                break;
            case 3:
                menuId = R.id.nav_calendar;
                break;
            case 4:
                menuId = R.id.nav_graph;
                break;
        }
        return menuId;
    }

    /**
     * Records the install date once, on first launch.
     *
     * <p>Survives the rating system it used to belong to, because it was never really the rating
     * system's: {@link LocalBackupManager} gates the weekly and monthly backups on how long the
     * app has been installed. Unwritten it reads 0, and "time since first launch" becomes about
     * fifty years, so both backups fire immediately on a fresh install.
     *
     * @return true if this call is what wrote it
     */
    boolean setFirstLaunch() {
        if (!preferences.contains(context.getString(R.string.pref_key_first_launch_time))) {
            Calendar c = Calendar.getInstance();
            preferences.edit().putLong(context.getString(R.string.pref_key_first_launch_time), c.getTimeInMillis()).apply();
            return true;
        }
        return false;
    }

    long getFirstLaunch() {
        return preferences.getLong(context.getString(R.string.pref_key_first_launch_time), 0);
    }

    static long getFirstLaunch(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getLong(context.getString(R.string.pref_key_first_launch_time), 0);
    }

    /** Whether the recurring overview shows its ended series; collapsed by default. */
    boolean recurringEndedExpanded() {
        return preferences.getBoolean(context.getString(R.string.pref_key_recurring_ended_expanded), false);
    }

    void setRecurringEndedExpanded(boolean expanded) {
        preferences.edit().putBoolean(context.getString(R.string.pref_key_recurring_ended_expanded), expanded).apply();
    }

    boolean dropboxSyncEnabled() {
        return preferences.getBoolean(context.getString(R.string.pref_key_dropbox_sync_enable), false);
    }

    /**
     * Turn the Dropbox sync preference off. Used when an auth flow comes back without a
     * credential: the switch is persisted the moment it is tapped, well before any account is
     * connected, so a declined login has to clear it or the app keeps believing sync is on.
     */
    void setDropboxSyncEnabled(boolean enabled) {
        preferences.edit()
                .putBoolean(context.getString(R.string.pref_key_dropbox_sync_enable), enabled)
                .apply();
    }

    private SharedPreferences getAccountPreferences(int accountId) {
        // get account settings
        String prefFile;
        prefFile = "account" + accountId;
        return context.getSharedPreferences(prefFile, Context.MODE_PRIVATE);
    }

    boolean isBudgetEnabled(Integer accountId) {
        return getAccountPreferences(accountId).getBoolean(context.getString(R.string.pref_key_budget_enable), false);
    }

    float getBudgetAmount(int accountId) {
        return parseBudgetAmount(getAccountPreferences(accountId).getString(context.getString(R.string.pref_key_budget_amount), "0"));
    }

    /**
     * Reads a stored budget amount, accepting a comma as the decimal separator (the decimal
     * keyboard offers one in regions that use it) and reading anything unparseable as no budget,
     * so a bad value cannot crash the Summary screen or the CSV export.
     */
    static float parseBudgetAmount(String stored) {
        if (stored == null) return 0f;
        try {
            float amount = Float.parseFloat(stored.trim().replace(',', '.'));
            return Float.isNaN(amount) || Float.isInfinite(amount) ? 0f : amount;
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    int getBudgetPeriod(int accountId) {
        return Integer.parseInt(getAccountPreferences(accountId).getString(context.getString(R.string.pref_key_budget_period), context.getString(R.string.default_budget_period)));
    }

    /**
     * Checks if the last sync time was within limit
     * <p/>
     * Returns true if it's ok to check sync again
     */
    boolean syncLimitCheck() {
        // compare last sync to current time
        long lastSync = preferences.getLong(context.getString(R.string.pref_key_dropbox_sync_time), 0);
        Calendar tempCal = Calendar.getInstance();
//        final int SYNC_LIMIT = 60000; // max once a minute
        final int SYNC_LIMIT = 20000; // max once every 20 sec
        long sinceLastSync = tempCal.getTimeInMillis() - lastSync;
        return sinceLastSync > SYNC_LIMIT;
    }

    void setSyncTime() {
        Calendar tempCal = Calendar.getInstance();
        preferences.edit().putLong(context.getString(R.string.pref_key_dropbox_sync_time), tempCal.getTimeInMillis()).apply();
    }

    boolean autoBackupLimitCheck() {
        // compare last sync to current time
        long lastBackup = preferences.getLong(context.getString(R.string.pref_key_dropbox_autobackup_time), 0);
        Calendar tempCal = Calendar.getInstance();
        final long BACKUP_LIMIT = 24 * 60 * 60 * 1000; // max once a day
        long sinceLastBackup = tempCal.getTimeInMillis() - lastBackup;
        return sinceLastBackup > BACKUP_LIMIT;
    }

    void setAutoBackupTime() {
        Calendar tempCal = Calendar.getInstance();
        preferences.edit().putLong(context.getString(R.string.pref_key_dropbox_autobackup_time), tempCal.getTimeInMillis()).apply();
    }

    int getListSortSetting() {
        return preferences.getInt(context.getString(R.string.pref_key_list_sorting_selection), 0);
    }

    void setListSortSetting(int sortSetting) {
        preferences.edit().putInt(context.getString(R.string.pref_key_list_sorting_selection), sortSetting).apply();
    }

    int getCategorySortingPreference() {
        return preferences.getInt(context.getString(R.string.pref_key_category_sorting), 0);
    }

    void setCategorySortingPreference(int sortSetting) {
        preferences.edit().putInt(context.getString(R.string.pref_key_category_sorting), sortSetting).apply();
    }

    int getGraphGroupingPreference() {
        return preferences.getInt(context.getString(R.string.pref_key_graph_grouping), 0);
    }

    void setGraphGroupingPreference(int sortSetting) {
        preferences.edit().putInt(context.getString(R.string.pref_key_graph_grouping), sortSetting).apply();
    }

    static boolean hasPermissionBeenRequested(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(context.getString(R.string.pref_key_permission_requested), false);
    }

    static void setPermissionRequested(Context context, boolean requested) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(context.getString(R.string.pref_key_permission_requested), requested).apply();
    }

    void log(String tag, String msg) {
        if (BuildConfig.DEBUG) Log.i(tag, msg);
    }

}
