package de.timowa.expenselog;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.FontAwesomeIcons;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import javax.inject.Inject;

/**
 * Creates Spreadsheet
 */
public class SpreadsheetHelper {

    private final Context context;
    private final Activity activity;
    /** Where a finished export goes. */
    private enum Destination { SAVE, SHARE }

    // The three export types, derived from the dialog's Include boxes and switched on in createSpreadsheet.
    private static final int EXPORT_LIST = 0;
    private static final int EXPORT_SUMMARY = 1;
    private static final int EXPORT_LIST_AND_SUMMARY = 2;

    /** Index of the Amount column in the list export, and of the Currency column after it. */
    private static final int COLUMN_AMOUNT = 2;
    private static final int COLUMN_CURRENCY = 3;

    private int exportType;
    private int recordsTimeMode;
    private String recordsFilter;
    private long dateRangeStart;
    private long dateRangeEnd;

    @Inject
    PrefManager prefManager;

    @Inject
    SpreadsheetHelper(final Context context, Activity activity) {
        this.context = context;
        this.activity = activity;
    }

    /**
     * Opens the Export Spreadsheet dialog, with every control seeded from the records screen it was
     * opened on: its duration, the page it is showing, and -- for Include -- its view, so a Summary
     * view offers a summary and every other view a list. The user can change any of them; nothing
     * they change here is written back to that screen.
     *
     * @param pageOffset the records pager's page relative to the current period, 0 being this one
     * @param recordsFilter the active filter's SQL prefix, or null or empty for none. It is applied
     *                      to the export and not offered as a control, so chips say what it is.
     */
    // Positions in MainActivity.mFilterArray, as FilterDialogFragment fills it.
    private static final int FILTER_TYPE = 0;
    private static final int FILTER_ACCOUNTS = 1;
    private static final int FILTER_CATEGORIES = 2;
    // DBAdapter.KEY_EXPENSE_INCOME value an expenses-only filter holds; incomes-only holds 1.
    private static final int FILTER_TYPE_EXPENSES = 0;
    // Names a category or account chip lists before summarising the rest as "+N".
    private static final int FILTER_CHIP_MAX_NAMES = 2;

    /**
     * Adds one chip per part of the active filter, in the filter dialog's order: the type, in the
     * records list's expense or income colour; the categories, in amber like the toolbar's
     * active-filter icon; the accounts, in teal. Falls back to a single amber "Filter" chip if
     * the filter string is set but the array names nothing, so the dialog never claims a filter
     * without showing one.
     */
    private void addFilterChips(WrapRowLayout chips, DBAdapter db, List<ArrayList<Integer>> filter) {
        int gap = Math.round(6 * activity.getResources().getDisplayMetrics().density);
        chips.setSpacing(gap, gap);

        if (filter != null && filter.size() > FILTER_CATEGORIES) {
            List<Integer> type = filter.get(FILTER_TYPE);
            if (!type.isEmpty()) {
                boolean expenses = type.get(0) == FILTER_TYPE_EXPENSES;
                chips.addView(filterChip(activity.getString(expenses ? R.string.expenses : R.string.incomes),
                        expenses ? R.drawable.bg_filter_chip_expense : R.drawable.bg_filter_chip_income,
                        expenses ? R.color.expenseColor : R.color.incomeColor));
            }

            List<String> categories = new ArrayList<>();
            for (int id : filter.get(FILTER_CATEGORIES)) {
                String label = db.getTagLabel(id);
                if (!label.isEmpty())
                    categories.add(label);
            }
            if (!categories.isEmpty())
                chips.addView(filterChip(summariseNames(categories),
                        R.drawable.bg_filter_chip, R.color.colorAccent));

            List<String> accounts = new ArrayList<>();
            for (int id : filter.get(FILTER_ACCOUNTS)) {
                String label = db.getAccountLabel(id);
                if (!label.isEmpty())
                    accounts.add(label);
            }
            if (!accounts.isEmpty())
                chips.addView(filterChip(summariseNames(accounts),
                        R.drawable.bg_filter_chip_account, R.color.colorPrimaryDark));
        }

        if (chips.getChildCount() == 0)
            chips.addView(filterChip(activity.getString(R.string.filter),
                    R.drawable.bg_filter_chip, R.color.colorAccent));
    }

    /** "A, B" for up to two names, "A, B +3" beyond that. */
    private String summariseNames(List<String> names) {
        String shown = String.join(", ", names.subList(0, Math.min(names.size(), FILTER_CHIP_MAX_NAMES)));
        int more = names.size() - FILTER_CHIP_MAX_NAMES;
        return more > 0 ? activity.getString(R.string.export_filter_names_more, shown, more) : shown;
    }

    private TextView filterChip(String text, int backgroundRes, int iconColorRes) {
        float density = activity.getResources().getDisplayMetrics().density;
        TextView chip = new TextView(activity);
        chip.setText(text);
        chip.setContentDescription(activity.getString(R.string.export_filter_chip_description, text));
        chip.setBackgroundResource(backgroundRes);
        chip.setTextColor(activity.getColor(R.color.darkText));
        chip.setTextSize(12);
        chip.setMaxLines(1);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPaddingRelative(Math.round(8 * density), Math.round(3 * density),
                Math.round(10 * density), Math.round(3 * density));
        // The toolbar's active-filter icon, at text size, so chip and toolbar read as one signal.
        chip.setCompoundDrawablePadding(Math.round(6 * density));
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(
                new IconDrawable(activity, FontAwesomeIcons.fa_filter).sizeDp(12).colorRes(iconColorRes),
                null, null, null);
        return chip;
    }

    @SuppressWarnings("SameParameterValue")
    void exportCSV(int recordsTimeMode, int pageOffset, int recordsViewMode,
                   final String orderedColumn, final String recordsFilter) {
        this.recordsFilter = recordsFilter;

        View body = LayoutInflater.from(activity).inflate(R.layout.dialog_export_spreadsheet, null);
        Spinner modeSpinner = body.findViewById(R.id.export_period_mode);
        View stepper = body.findViewById(R.id.export_period_stepper);
        TextView periodLabel = body.findViewById(R.id.export_period_label);
        TextView preview = body.findViewById(R.id.export_period_preview);
        CheckBox includeList = body.findViewById(R.id.export_include_list);
        CheckBox includeSummary = body.findViewById(R.id.export_include_summary);

        boolean summaryView = recordsViewMode == LogTabsFragment.KEY_VIEW_MODE_SUMMARY;
        includeList.setChecked(!summaryView);
        includeSummary.setChecked(summaryView);

        // Held for the dialog's lifetime rather than per preview, because every step re-counts.
        DBAdapter db = new DBAdapter(context);
        boolean filtered = recordsFilter != null && !recordsFilter.isEmpty();
        if (filtered) {
            WrapRowLayout filterChips = body.findViewById(R.id.export_filter_chips);
            addFilterChips(filterChips, db, MainActivity.mFilterArray);
            filterChips.setVisibility(View.VISIBLE);
        }
        // A one-element array so the listeners below can replace the period they all share.
        ExportPeriod[] period = {new ExportPeriod(recordsTimeMode, pageOffset, activity,
                prefManager.getPrefsFile())};

        Runnable showPeriod = () -> {
            ExportPeriod p = period[0];
            stepper.setVisibility(p.isAllRecords() ? View.GONE : View.VISIBLE);
            periodLabel.setText(p.label());

            int count = (int) db.countLogsInRange(p.start, p.end, recordsFilter);
            String line;
            if (filtered) {
                int total = (int) db.countLogsInRange(p.start, p.end, null);
                line = activity.getResources().getQuantityString(
                        R.plurals.export_record_count_filtered, total, count, total);
            } else {
                line = activity.getResources().getQuantityString(
                        R.plurals.export_record_count, count, count);
            }
            if (!p.isAllRecords())
                line = activity.getString(R.string.export_preview_join, line, p.dateRange());
            preview.setText(line);
        };

        ArrayAdapter<CharSequence> modes = ArrayAdapter.createFromResource(activity,
                R.array.export_period_modes, android.R.layout.simple_spinner_item);
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modes);
        // Before the listener, so seeding the spinner is not taken for a change of duration.
        modeSpinner.setSelection(recordsTimeMode, false);
        modeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                period[0] = period[0].withMode(position);
                showPeriod.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        body.findViewById(R.id.export_period_previous).setOnClickListener(v -> {
            period[0] = period[0].step(-1);
            showPeriod.run();
        });
        body.findViewById(R.id.export_period_next).setOnClickListener(v -> {
            period[0] = period[0].step(1);
            showPeriod.run();
        });
        showPeriod.run();

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(activity);
        dialogBuilder.setTitle(activity.getResources().getString(R.string.export_spreadsheet));
        dialogBuilder.setView(body);

        dialogBuilder.setPositiveButton(R.string.save, (dialog, which) -> createSpreadsheet(period[0],
                includeList.isChecked(), includeSummary.isChecked(), orderedColumn, Destination.SAVE));
        dialogBuilder.setNegativeButton(R.string.share, (dialog, which) -> createSpreadsheet(period[0],
                includeList.isChecked(), includeSummary.isChecked(), orderedColumn, Destination.SHARE));
        // Neutral, not negative: Material renders the row as neutral ... negative positive, so
        // this puts Cancel on the left and keeps Share and Save together on the right.
        dialogBuilder.setNeutralButton(activity.getResources().getString(R.string.cancel),
                (dialog, which) -> {
                });
        dialogBuilder.setOnDismissListener(dialog -> db.close());

        AlertDialog dialog = dialogBuilder.create();
        dialog.show();

        // With neither box ticked there is nothing to write, so neither destination is offered.
        // The buttons exist only once the dialog is shown.
        CompoundButton.OnCheckedChangeListener updateButtons = (button, checked) -> {
            boolean any = includeList.isChecked() || includeSummary.isChecked();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(any);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(any);
        };
        includeList.setOnCheckedChangeListener(updateButtons);
        includeSummary.setOnCheckedChangeListener(updateButtons);
    }

    private void createSpreadsheet(ExportPeriod period, boolean list, boolean summary,
                                   String orderedColumn, Destination destination) {
        exportType = list && summary ? EXPORT_LIST_AND_SUMMARY
                : summary ? EXPORT_SUMMARY : EXPORT_LIST;
        recordsTimeMode = period.mode;
        dateRangeStart = period.start;
        dateRangeEnd = period.end;
        long start = period.start;
        long end = period.end;

        // Before anything is written: clears the previous export's leftover, if any. A shared file
        // cannot be deleted when its sheet closes, because the receiving app reads it later.
        FileHelper.discardPendingExport(context);

        DBAdapter db = new DBAdapter(context);
        CsvFormatter csv = new CsvFormatter();

        // The name is the file's own, and is handed to the share target as the subject too -- see
        // sendFile. Built before the write so both the file and the share agree on it.
        String fileName = exportFileName(start, end, csv);
        File createdFile = new File(FileHelper.getExportStagingDir(context), fileName);

        boolean written = writeExport(createdFile, db, orderedColumn, csv);
        db.close();
        if (!written) {
            // On the main thread -- a dialog button -- so this caller may present. Nothing is
            // recorded as pending and nothing is offered: a partial file used to be handed to the
            // picker or the share sheet, and then reported as "export saved".
            Toast.makeText(context, context.getString(R.string.export_save_failed),
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Recorded for both destinations: Save takes it back when the picker returns, Share leaves
        // it for the next export to clear.
        FileHelper.rememberPendingExport(context, createdFile);

        if (destination == Destination.SHARE) {
            activity.startActivity(FileHelper.createShareIntent(context, createdFile,
                    FileHelper.CSV_MIME_TYPE, fileName));
            return;
        }
        askWhereToSave(fileName, createdFile);
    }

    /**
     * Writes the CSV for the current export type into {@code createdFile}.
     *
     * <p>All or nothing from the caller's point of view: on any failure the partial file is
     * deleted and {@code false} returned, so the caller never offers half an export. It used to
     * print the stack trace and carry on as if the write had worked.
     *
     * @return true if the whole file was written and closed
     */
    boolean writeExport(File createdFile, DBAdapter db, String orderedColumn, CsvFormatter csv) {
        // Explicitly UTF-8, because FileWriter cannot be given a charset, and with a BOM, because
        // without one Excel on Windows reads the file as cp1252 and turns every euro sign into
        // "a-hat euro". try-with-resources so a failed write cannot leak the handle.
        try (Writer writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(createdFile), StandardCharsets.UTF_8))) {

            writer.write(CsvFormatter.BOM);

            // append list, summary, or both
            switch (exportType) {
                case EXPORT_LIST:
                    appendList(writer, db, orderedColumn, csv);
                    break;
                case EXPORT_SUMMARY:
                    appendSummary(writer, db, csv);
                    break;
                case EXPORT_LIST_AND_SUMMARY:
                    // Two tables of different widths in one file. A blank line between them, and a
                    // header on each, is what lets a spreadsheet treat them as two blocks rather
                    // than as one table with ragged rows.
                    appendSummary(writer, db, csv);
                    writer.write(CsvFormatter.LINE_ENDING);
                    writer.write(CsvFormatter.LINE_ENDING);
                    appendList(writer, db, orderedColumn, csv);
                    break;
            }

            writer.write(CsvFormatter.LINE_ENDING);
            return true;
        } catch (IOException | RuntimeException e) {
            Log.e(DropBoxHelper.DROPBOX_TAG, "CSV export failed while writing " + createdFile, e);
            if (createdFile.isFile() && !createdFile.delete())
                Log.w(DropBoxHelper.DROPBOX_TAG, "could not remove the partial export " + createdFile);
            return false;
        }
    }

    /**
     * The name of the exported file, which is also what the share target is offered as a title.
     *
     * <p>It ends in {@code .csv}, and that is the point. The file on disk used to be the constant
     * {@code "Expense Log.csv"} — so every export overwrote the last — while the name the user
     * actually received came from the share subject, which had no extension at all. Windows
     * therefore would not open it with a spreadsheet.
     *
     * <p>Shape: {@code Expense Log 2026-09-01 to 2026-09-30 list.csv}. Three things earn their
     * place in it:
     * <ul>
     *   <li>ISO dates, for the same reason the columns use them. A file called
     *       {@code ... 09/03/26.csv} is not a file name.</li>
     *   <li>{@code to} between them rather than a hyphen, because the dates already contain
     *       hyphens and {@code 2026-09-01 - 2026-09-30} hides its own boundary.</li>
     *   <li>The export type, because a list and a summary of the same range are different files
     *       and used to overwrite each other.</li>
     * </ul>
     *
     * <p>The constant app-name prefix means a folder listing still sorts these chronologically.
     */
    private String exportFileName(long start, long end, CsvFormatter csv) {
        String appName = context.getResources().getString(R.string.app_name);
        String type = exportTypeName();

        if (start == 0) {
            return CsvFormatter.fileName(appName,
                    context.getResources().getString(R.string.csv_name_all_records),
                    csv.date(Calendar.getInstance().getTimeInMillis()), type);
        }
        return CsvFormatter.fileName(appName, csv.date(start),
                context.getResources().getString(R.string.csv_name_to), csv.date(end), type);
    }

    /** The export type as it appears in a file name — {@code list}, {@code summary}, or both. */
    private String exportTypeName() {
        switch (exportType) {
            case 1:
                return context.getResources().getString(R.string.csv_name_type_summary);
            case 2:
                return context.getResources().getString(R.string.csv_name_type_list_summary);
            default:
                return context.getResources().getString(R.string.csv_name_type_list);
        }
    }

    private void appendSummary(Writer writer, DBAdapter db, CsvFormatter csv) throws IOException {

        String currency = prefManager.getCurrencyCodeForCsv();

        // add column titles
        writer.write(CsvFormatter.row(
                context.getResources().getString(R.string.category),
                context.getResources().getString(R.string.amount),
                context.getResources().getString(R.string.currency)));

        // get unique categories and totals
        Cursor uniqueCategories = db.getAllUniqueCategoryIdsInRange(dateRangeStart, dateRangeEnd, recordsFilter);

        // add a row for each category
        if (uniqueCategories != null) {
            for (int i = 0; i < uniqueCategories.getCount(); i++) {
                // go through list. for each item get total and title create a row
                uniqueCategories.moveToNext();

                // add category filter to records filter
                int currentCatId = uniqueCategories.getInt(0);
                String tempFilter = DBAdapter.KEY_CATEGORY_ID + " = " + currentCatId + " AND ";

                if (recordsFilter == null)
                    recordsFilter = "";
                tempFilter += recordsFilter;

                double amount = db.getTotalForRange(dateRangeStart, dateRangeEnd, tempFilter);

                writeAmountRow(writer, csv, db.getCategoryLabel(currentCatId), amount, currency);
            }
            uniqueCategories.close();
        }

        // get total expenses
        String expensesFilter = recordsFilter + DBAdapter.KEY_EXPENSE_INCOME + " = 0 AND ";
        double totalExpenses =
                db.getSumForRange(dateRangeStart, dateRangeEnd, DBAdapter.KEY_AMOUNT, expensesFilter) * -1;

        // get total incomes
        String incomeFilter = recordsFilter + DBAdapter.KEY_EXPENSE_INCOME + " = 1 AND ";
        double totalIncomes = db.getSumForRange(dateRangeStart, dateRangeEnd, DBAdapter.KEY_AMOUNT, incomeFilter);

        // if both expenses and incomes total is not 0, show the breakdown
        if (totalExpenses != 0 && totalIncomes != 0) {
            writeAmountRow(writer, csv, context.getResources().getString(R.string.expenses),
                    totalExpenses, currency);
            writeAmountRow(writer, csv, context.getResources().getString(R.string.incomes),
                    totalIncomes, currency);
        }

        double totalAmount = db.getTotalForRange(dateRangeStart, dateRangeEnd, recordsFilter);
        writeAmountRow(writer, csv, context.getResources().getString(R.string.total),
                totalAmount, currency);

        // add budget info
        addBudgetInfo(writer, db, totalAmount, csv);
    }

    /** One label/amount/currency row of the summary block, on its own line. */
    private void writeAmountRow(Writer writer, CsvFormatter csv, String label, double amount,
                                String currency) throws IOException {
        writer.write(CsvFormatter.LINE_ENDING);
        writer.write(CsvFormatter.row(label, CsvFormatter.amount(amount), currency));
    }

    private void addBudgetInfo(Writer writer, DBAdapter db, double totalAmount, CsvFormatter csv)
            throws IOException {
        // if viewing All Records, don't do budget stuff
        if (dateRangeStart != 0) {

            // if only one account exists, don't bother checking the filter
            Cursor accountsCursor = db.getAccounts();
            // more than one account, check filter
            if (accountsCursor != null) {
                if (accountsCursor.getCount() > 1) {
                    // if no filter is set and more than one account exists, skip showing budget info
                    if (MainActivity.mFilterArray != null) {
                        // get the active filter and check if only one account is active
                        ArrayList<ArrayList<Integer>> newFilter = MainActivity.mFilterArray;
                        ArrayList<Integer> accountsArray = newFilter.get(1);
                        // only one account in filter selected
                        if (accountsArray.size() == 1) {
                            int accountId = accountsArray.get(0);
                            // check if budget settings are set
                            if (prefManager.isBudgetEnabled(accountId))
                                appendBudgetInfo(accountId, writer, totalAmount, csv);
                        }
                    }
                } else {
                    // only one account exists
                    accountsCursor.moveToFirst();
                    int accountID = accountsCursor.getInt(DBAdapter.COLUMN_ACCOUNT_ID);
                    if (prefManager.isBudgetEnabled(accountID))
                        appendBudgetInfo(accountID, writer, totalAmount, csv);
                }
                accountsCursor.close();
            }
        }
    }

    private void appendBudgetInfo(int accountId, Writer writer, double totalAmount, CsvFormatter csv)
            throws IOException {
        // find the appropriate budget amount for the period being viewed and the budget setting
        double budgetAmount = prefManager.getBudgetAmount(accountId);
        int budgetPeriod = prefManager.getBudgetPeriod(accountId);
        budgetAmount = Utility.getTimeAdjustedBudget(budgetAmount, budgetPeriod, recordsTimeMode, dateRangeStart);
        double budgetDifference = budgetAmount + totalAmount;

        String currency = prefManager.getCurrencyCodeForCsv();

        writer.write(CsvFormatter.LINE_ENDING);
        writeAmountRow(writer, csv, context.getResources().getString(R.string.budget),
                budgetAmount, currency);
        writeAmountRow(writer, csv, context.getResources().getString(R.string.budget_remaining),
                budgetDifference, currency);
    }

    private void appendList(Writer writer, DBAdapter db, String orderedColumn, CsvFormatter csv)
            throws IOException {

        // Resolved once. This used to be called for the header and then twice for every row, and
        // each call opened an accounts cursor it never closed -- roughly 5000 leaked cursors on a
        // 2540-record export.
        boolean multipleAccounts = isMultipleAccounts(db);
        String currency = prefManager.getCurrencyCodeForCsv();

        List<String> header = new ArrayList<>();
        header.add(context.getResources().getString(R.string.date));
        header.add(context.getResources().getString(R.string.time));
        header.add(context.getResources().getString(R.string.amount));
        header.add(context.getResources().getString(R.string.currency));
        if (multipleAccounts)
            header.add(context.getResources().getString(R.string.accounts));
        header.add(context.getResources().getString(R.string.category));
        header.add(context.getResources().getString(R.string.notes));

        writer.write(CsvFormatter.row(header.toArray(new String[0])));

        db.open();
        Cursor tableCursor = db.getLogsInRange(dateRangeStart, dateRangeEnd, orderedColumn, recordsFilter);

        // if data exists start iterating and appending each line
        if (tableCursor.moveToFirst()) {
            do {
                long timeStamp = tableCursor.getLong(DBAdapter.COLUMN_LOG_TIME);

                // expenses are negative, incomes positive -- the sign is what carries the type
                double amount = tableCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT);
                if (tableCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME) == 0)
                    amount = amount * -1;

                List<String> row = new ArrayList<>();
                row.add(csv.date(timeStamp));
                row.add(csv.time(timeStamp));
                row.add(CsvFormatter.amount(amount));
                row.add(currency);
                if (multipleAccounts)
                    row.add(db.getAccountLabel(tableCursor.getInt(DBAdapter.COLUMN_LOG_ACCOUNT)));
                row.add(db.getCategoryLabel(tableCursor.getInt(DBAdapter.COLUMN_LOG_CATEGORY)));
                row.add(tableCursor.getString(DBAdapter.COLUMN_LOG_NOTES));

                writer.write(CsvFormatter.LINE_ENDING);
                writer.write(CsvFormatter.row(row.toArray(new String[0])));

            } while (tableCursor.moveToNext());
            // END ROWS /////////////////////////////////////////////////

            // The total row, padded to the header's width. It used to be three fields against a
            // five-column header, which put the word "Total" under the Time column.
            String[] total = new String[header.size()];
            Arrays.fill(total, "");
            total[COLUMN_AMOUNT] = CsvFormatter.amount(
                    db.getTotalForRange(dateRangeStart, dateRangeEnd, recordsFilter));
            total[COLUMN_CURRENCY] = currency;
            // second from last is Category, whichever side of it the optional Accounts column falls
            total[header.size() - 2] = context.getResources().getString(R.string.total);

            writer.write(CsvFormatter.LINE_ENDING);
            writer.write(CsvFormatter.row(total));
        }
        tableCursor.close();
    }

    /**
     * Asks the user where the finished CSV should go.
     *
     * <p>Was {@code sendFile}, which raised an {@code ACTION_SEND} chooser. A share sheet lists
     * apps that registered to receive a file of this type -- Drive, Signal, a mail client -- and
     * saving to the device is not something an app registers for, so local storage could not
     * appear in it however the intent was tuned. {@code ACTION_CREATE_DOCUMENT} asks the question
     * the user was actually asking, and still reaches Drive and Dropbox, which are document
     * providers.
     *
     * <p>{@code fileName} is handed over as {@code EXTRA_TITLE}. It is no longer also a subject
     * and a chooser title: the picker names the file from this one string, or from whatever the
     * user types over it, so the three-way agreement the old share needed is gone with the share.
     *
     * <p>The picker goes through {@link MainActivity#csvExportLauncher()}: this helper is
     * constructed with the hosting activity and has no lifecycle of its own to register a launcher
     * against, and the result has always been the activity's to handle rather than the fragment's.
     */
    private void askWhereToSave(String fileName, File createdFile) {
        Intent intent = FileHelper.createExportIntent(fileName, FileHelper.CSV_MIME_TYPE);
        if (activity instanceof MainActivity) {
            ((MainActivity) activity).csvExportLauncher().launch(intent);
        } else {
            // Nothing to report back to, so the staged file would sit in a directory no file
            // manager can reach.
            Log.w("SpreadsheetHelper", "no export launcher on " + activity.getClass().getName());
            FileHelper.discardPendingExport(activity);
        }
    }

    private boolean isMultipleAccounts(DBAdapter db) {
        Cursor cursor = db.getAccounts();
        if (cursor == null)
            return false;
        try {
            return cursor.getCount() > 1;
        } finally {
            cursor.close();
        }
    }

}
