package de.timowa.expenselog;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import androidx.appcompat.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.DialogFragment;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.FontAwesomeIcons;
import de.timowa.expenselog.icons.MaterialIcons;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.reminders.ReminderManager;
import de.timowa.expenselog.reminders.ReminderReceiver;
import de.timowa.expenselog.databinding.FragmentNewLogBinding;

import static androidx.core.content.ContextCompat.getColor;

/**
 * Create a new log
 */
public class NewLogFragment extends BaseFragment implements View.OnClickListener {

    private FragmentNewLogBinding binding;
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PASSED_LOG = "param1";
    private static final String ARG_PARAM2 = "param2";
    private static final String ARG_REPEAT = "repeat";
    /** The unit spinner's plurals, in {@link RepeatingSeries} period order. */
    private static final int[] REPEAT_UNIT_PLURALS = {R.plurals.repeat_unit_day,
            R.plurals.repeat_unit_week, R.plurals.repeat_unit_month, R.plurals.repeat_unit_year};
    private static final String NEWLOG = "NEWLOG";

    private String mPassedLog = null;
    private String mPassedTime;

    private long logTime;
    private long frequencyEndTime;
    private int selectedTagId;
    private int editingRepeatingId = -1;
    /** The series of the record being edited, as it was when the screen opened; null otherwise. */
    private RepeatingSeries editingSeries;
    /** The edited record's time as stored -- what "this and following" counts from. */
    private long editingOriginalTime;
    /**
     * The form's values as first filled from the record ({@link #formValues}), so a save can tell
     * a new end alone from other changes by what the user changed on screen -- not by comparing
     * with the database, which the form rounds and falls back from.
     */
    private String filledValues;
    /**
     * Everything a save could write, as the form showed it when filled from the record
     * ({@link #formState}), so saving an unchanged record writes nothing and asks nothing.
     */
    private String filledForm;
    private boolean startRepeating;

    private static String SAVED_TIMESTAMP_TAG = "SAVED_TIMESTAMP_TAG";
    private static String SAVED_EXPINC_TAG = "SAVED_EXPINC_TAG";
    private static String SAVED_CATEGORY_TAG = "SAVED_CATEGORY_TAG";
    private static String SAVED_ACCOUNT_TAG = "SAVED_ACCOUNT_TAG";
    private static final String SAVED_END_TIME_TAG = "SAVED_END_TIME_TAG";
    private static final String SAVED_FORM_FILLED_TAG = "SAVED_FORM_FILLED_TAG";
    private static final String SAVED_FILLED_VALUES_TAG = "SAVED_FILLED_VALUES_TAG";
    private static final String SAVED_FILLED_FORM_TAG = "SAVED_FILLED_FORM_TAG";

    /**
     * Whether the form has had its starting values -- defaults, or the edited record's. They are
     * put in once: initializeViews runs on every resume, and used to put them in each time, so
     * leaving the app and coming back reset the date, category, account and series end, and threw
     * away every change to a record being edited. After a rotation the fields below come back
     * from saved state and the text fields restore themselves.
     */
    private boolean formFilled;
    private int selectedAccountId = -1;

    private ArrayList<Integer> accountIdList;
    private ArrayList<Integer> categoryIdList;
    private ArrayList<String> labelList;
    private ArrayList<String> iconList;
    /**
     * The image path stored on the record being edited. The image feature was removed, but
     * existing rows keep their COLUMN_LOG_IMAGE value: this carries it through an edit so
     * saving a record does not blank a path the user still has the file for.
     */
    private String existingImageUri = "";
    private Calendar newEvent = null;

    @Inject
    Navigator navigator;
    @Inject
    DBAdapter dbAdapter;
    @Inject
    Utility utility;
    @Inject
    PrefManager prefManager;
    @Inject
    DropBoxHelper dropBoxHelper;
    TextView selectedTagLabel;
    Spinner accountSpinner;
    LinearLayout accountSelectionLayout;
    TextView logTimeTextView;
    TextView logDateTextView;
    SwitchCompat expenseIncomeSwitch;
    EditText amountEditText;
    EditText notesEditText;
    SwitchCompat repeatingSwitch;
    LinearLayout frequencyLayout;
    LinearLayout frequencyEndingLayout;
    Spinner frequencySpinner;
    EditText frequencyEditText;
    TextView frequencyLabelTextView;
    TextView frequencyEndingLabelTextView;
    TextView frequencyEndingDateTextView;
    private TextView seriesInfoTextView;
    private View repeatToggleLayout;
    private TextView repeatErrorTextView;
    /** The form, which fades in briefly when moving to another entry of the series. */
    private View scrollView;
    private ImageButton seriesPreviousButton;
    private ImageButton seriesNextButton;
    private static final int MENU_DELETE_RECORD = 0x7e01;
    private TextView repeatSummaryTextView;
    /** The unit spinner's words, re-pluralised whenever the count changes. */
    private final ArrayList<String> repeatUnits = new ArrayList<>();
    private ArrayAdapter<String> repeatUnitAdapter;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment HomeFragment.
     */
    public static NewLogFragment newInstance(String param1, String param2) {
        NewLogFragment fragment = new NewLogFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PASSED_LOG, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    /** A new record with "Repeat Transaction" already on -- the recurring overview's add button. */
    public static NewLogFragment newRepeatingInstance() {
        NewLogFragment fragment = newInstance("", "");
        fragment.requireArguments().putBoolean(ARG_REPEAT, true);
        return fragment;
    }

    public NewLogFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mPassedLog = getArguments().getString(ARG_PASSED_LOG);
            mPassedTime = getArguments().getString(ARG_PARAM2);
            // only on first creation: after a rotation the switch restores the user's own choice
            startRepeating = savedInstanceState == null && getArguments().getBoolean(ARG_REPEAT);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        binding = FragmentNewLogBinding.inflate(inflater, container, false);
        View view = binding.getRoot();

        selectedTagLabel = binding.textViewTagListItem;
        accountSpinner = binding.spinnerAccountList;
        accountSelectionLayout = binding.layoutAccountSelection;
        logTimeTextView = binding.textViewLogTime;
        logDateTextView = binding.textViewLogDate;
        expenseIncomeSwitch = binding.switchExpenseIncome;
        amountEditText = binding.editTextNewAmount;
        notesEditText = binding.editTextNewNotes;
        repeatingSwitch = binding.switchRepeating;
        frequencyLayout = binding.layoutFrequency;
        frequencyEndingLayout = binding.layoutFrequencyEnding;
        frequencySpinner = binding.spinnerFrequencyList;
        frequencyEditText = binding.editTextFrequency;
        frequencyLabelTextView = binding.textViewFrequencyLabel;
        frequencyEndingLabelTextView = binding.textViewFrequencyEndingLabel;
        frequencyEndingDateTextView = binding.textViewFrequencyEndingDate;
        seriesInfoTextView = binding.textViewSeriesInfo;
        repeatToggleLayout = binding.layoutRepeatToggle;
        repeatErrorTextView = binding.textViewRepeatError;
        scrollView = binding.scrollView;
        // the drawer's Recurring icon, which marks the series block in both of its forms
        ((ImageView) view.findViewById(R.id.imageView_repeatIcon)).setImageDrawable(recurringIcon());
        seriesInfoTextView.setCompoundDrawablesRelativeWithIntrinsicBounds(recurringIcon(), null, null, null);
        seriesPreviousButton = view.findViewById(R.id.button_seriesPrevious);
        seriesNextButton = view.findViewById(R.id.button_seriesNext);
        seriesPreviousButton.setOnClickListener(v -> moveInSeries(-1));
        seriesNextButton.setOnClickListener(v -> moveInSeries(1));
        repeatSummaryTextView = view.findViewById(R.id.textView_repeatSummary);
        // Here rather than in initializeViews, which runs on every resume: a new adapter resets
        // the spinner to its first item, after the selection was restored from saved state.
        setupRepeatUnits();

        return view;
    }

    private void setupRepeatUnits() {
        repeatUnits.clear();
        for (int i = 0; i < REPEAT_UNIT_PLURALS.length; i++)
            repeatUnits.add("");
        repeatUnitAdapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, repeatUnits);
        repeatUnitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        refreshRepeatUnits();
        frequencySpinner.setAdapter(repeatUnitAdapter);
        frequencySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                updateRepeatSummary();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        frequencyEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refreshRepeatUnits();
                updateRepeatSummary();
            }
        });
        // Whether an end before this entry is allowed depends on whether anything else changed
        // (endOnlyMovesPastThisEntry), so the reason under Ending is re-checked on those edits too.
        TextWatcher recheck = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                recheckSeriesEnd();
            }
        };
        amountEditText.addTextChangedListener(recheck);
        notesEditText.addTextChangedListener(recheck);
    }

    /** {@link #updateRepeatSummary} after an edit that is not the schedule's, for a series entry. */
    private void recheckSeriesEnd() {
        if (editingSeries != null)
            updateRepeatSummary();
    }

    /** "day" beside 1, "days" beside anything else; an empty count reads as 1. */
    private void refreshRepeatUnits() {
        int count = Math.max(1, enteredFrequency());
        for (int i = 0; i < REPEAT_UNIT_PLURALS.length; i++)
            repeatUnits.set(i, getResources().getQuantityString(REPEAT_UNIT_PLURALS[i], count));
        repeatUnitAdapter.notifyDataSetChanged();
    }

    /**
     * Spells out the schedule the controls describe, under the series block's header: "Monthly on
     * day 17 · 13 entries" -- no end date, which Ending shows right below. The count is left out
     * when editing: "Entry 3 of 12" already says where the record sits, and how many a change
     * would produce depends on the scope chosen at save time.
     *
     * <p>Why the schedule can't be saved goes under Ending instead, and the header keeps the last
     * schedule that could, so it does not flicker while a number is typed.
     */
    private void updateRepeatSummary() {
        if (repeatSummaryTextView == null || prefManager == null)
            return;
        if (!repeatingSwitch.isChecked()) {
            repeatSummaryTextView.setVisibility(View.GONE);
            repeatErrorTextView.setVisibility(View.GONE);
            return;
        }
        int frequency = enteredFrequency();
        int period = frequencySpinner.getSelectedItemPosition();
        String text = null;
        String error = null;
        if (frequency <= 0) {
            error = getString(R.string.recurring_invalid_interval);
        } else if (frequencyEndTime <= logTime && !endOnlyMovesPastThisEntry(frequency, period)) {
            error = getString(R.string.recurring_end_before_entry);
        } else {
            text = describeSchedule(frequency, period);
            if (editingSeries == null) {
                int entries = countOccurrences(logTime, frequencyEndTime, frequency, period);
                if (entries > DBAdapter.MAX_REPEATING_OCCURRENCES + 1)
                    error = getResources().getQuantityString(R.plurals.recurring_too_long,
                            DBAdapter.MAX_REPEATING_OCCURRENCES, DBAdapter.MAX_REPEATING_OCCURRENCES);
                else
                    text = getString(R.string.repeat_summary_join, text,
                            getResources().getQuantityString(R.plurals.repeat_summary_entries, entries, entries));
            }
        }
        repeatErrorTextView.setText(error);
        repeatErrorTextView.setVisibility(error == null ? View.GONE : View.VISIBLE);
        if (error == null && text != null)
            repeatSummaryTextView.setText(text);
        repeatSummaryTextView.setVisibility(repeatSummaryTextView.getText().length() > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * Whether an end before this entry is allowed: editing a series entry whose interval and date
     * are unchanged, where the new end deletes this entry and those after it. With a new interval
     * or date the schedule restarts here, and an end before it leaves nothing to restart.
     */
    private boolean endOnlyMovesPastThisEntry(int frequency, int period) {
        return editingSeries != null && frequency == editingSeries.frequency
                && period == editingSeries.period && logTime == editingOriginalTime
                && !valuesChanged();
    }

    private String describeSchedule(int frequency, int period) {
        return describeSchedule(getResources(), logTime, frequency, period);
    }

    /**
     * "Every 3 days", "Every 3 weeks on Thursday", "Monthly on day 17", "Monthly on day 31 (or last
     * day)", "Yearly on Sep 17": how often, and on which day of the record at {@code time}. In
     * English whatever the device language; see {@link AppLocale}.
     */
    static String describeSchedule(Resources res, long time, int frequency, int period) {
        String interval = RecurringListFragment.describeInterval(res, frequency, period);
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        switch (period) {
            case RepeatingSeries.PERIOD_WEEK:
                return res.getString(R.string.repeat_summary_on, interval,
                        new SimpleDateFormat("EEEE", AppLocale.TEXT).format(c.getTime()));
            case RepeatingSeries.PERIOD_MONTH:
                int day = c.get(Calendar.DAY_OF_MONTH);
                // DBAdapter.repeatingOccurrence clamps to shorter months
                return res.getString(day > 28 ? R.string.repeat_summary_on_day_or_last
                        : R.string.repeat_summary_on_day, interval, day);
            case RepeatingSeries.PERIOD_YEAR:
                return res.getString(R.string.repeat_summary_on, interval,
                        new SimpleDateFormat("MMM d", AppLocale.TEXT).format(c.getTime()));
            default:
                return interval;
        }
    }

    /**
     * The records a new series would have, this one included, counted as
     * {@link DBAdapter#createRepeatingSeries} writes them -- which is the cap plus this one at
     * most. Past that, the count stops one higher.
     */
    static int countOccurrences(long originalTime, long endTime, int frequency, int period) {
        int k = 1;
        while (k <= DBAdapter.MAX_REPEATING_OCCURRENCES + 1
                && DBAdapter.repeatingOccurrence(originalTime, k, frequency, period) < endTime)
            k++;
        return k;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        addMenu();

        this.initialize();

        if (BuildConfig.DEBUG)
            Log.i("FRAGT", "restore state instance has data: " + (savedInstanceState != null));
        if (savedInstanceState != null) {
            logTime = savedInstanceState.getLong(SAVED_TIMESTAMP_TAG);
            selectedTagId = savedInstanceState.getInt(SAVED_CATEGORY_TAG);
            selectedAccountId = savedInstanceState.getInt(SAVED_ACCOUNT_TAG);
            frequencyEndTime = savedInstanceState.getLong(SAVED_END_TIME_TAG);
            formFilled = savedInstanceState.getBoolean(SAVED_FORM_FILLED_TAG);
            filledValues = savedInstanceState.getString(SAVED_FILLED_VALUES_TAG);
            filledForm = savedInstanceState.getString(SAVED_FILLED_FORM_TAG);
            if (BuildConfig.DEBUG) Log.i("FRAGT", "restored time: " + logTime);
            if (BuildConfig.DEBUG) Log.i("FRAGT", "restored cat: " + selectedTagId);
        }

        setupFab();
    }

    @Override
    public void onResume() {
        super.onResume();

        initializeViews();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (BuildConfig.DEBUG) Log.i("FRAGT", "onSaveInstanceState");

        // save the current expense/income, category, account and date
        if (isAdded()) {
            outState.putLong(SAVED_TIMESTAMP_TAG, logTime);
            int expenseIncome = 0;
            if (expenseIncomeSwitch.isChecked())
                expenseIncome = 1;
            outState.putInt(SAVED_EXPINC_TAG, expenseIncome);

            int selectedAccount;
            // if accounts are visible get the selected one, otherwise get the only available one
            if (accountSelectionLayout.getVisibility() == View.VISIBLE) {
                selectedAccount = accountIdList.get(accountSpinner.getSelectedItemPosition());
            } else {
                Cursor accountCursor = dbAdapter.getAccounts();
                accountCursor.moveToFirst();
                selectedAccount = accountCursor.getInt(DBAdapter.COLUMN_ACCOUNT_ID);
                accountCursor.close();
            }
            outState.putInt(SAVED_ACCOUNT_TAG, selectedAccount);

            outState.putInt(SAVED_CATEGORY_TAG, selectedTagId);
            outState.putLong(SAVED_END_TIME_TAG, frequencyEndTime);
            outState.putBoolean(SAVED_FORM_FILLED_TAG, formFilled);
            outState.putString(SAVED_FILLED_VALUES_TAG, filledValues);
            outState.putString(SAVED_FILLED_FORM_TAG, filledForm);
            if (BuildConfig.DEBUG) Log.i("FRAGT", "saved time: " + logTime);
            if (BuildConfig.DEBUG) Log.i("FRAGT", "saved cat: " + selectedTagId);
            if (BuildConfig.DEBUG) Log.i("FRAGT", "saved exp: " + selectedAccount);
        }
    }

    private void initializeViews() {
        // Everything below wires up the views on every resume; only a first fill sets values.
        boolean fill = !formFilled;
        selectedTagLabel.setOnClickListener(this);
        setupAccountsSpinner(fill);
        setupDateAndTime(fill);
        setupExpenseIncomeSwitch(fill);
        setupFrequency(fill);
        // Once: initializeViews runs on every resume, and turning the switch back on each time
        // would silently save a record the user switched off as a series.
        if (startRepeating && (mPassedLog == null || mPassedLog.equals("")))
            repeatingSwitch.setChecked(true);
        startRepeating = false;

        // if time is provided set provided time
        if (fill && !mPassedTime.equals("")) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(Long.parseLong(mPassedTime));
            onDatePicked(c);
            onTimePicked(c);
        }

        // if a log is passed initialize with that data
        if (mPassedLog != null && !mPassedLog.equals("")) {
            // editing an existing record
            // get cursor from db
            Cursor logCursor = dbAdapter.getLog(mPassedLog);
            if (logCursor != null && logCursor.moveToFirst()) {
                // The record's values only on the first fill; after that the form holds the
                // user's changes. What is not shown is read every time: it does not survive a
                // rotation.
                if (fill) {
                    // set previous expense/income setting
                    if (logCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME) == 1)
                        expenseIncomeSwitch.setChecked(true);
                    else
                        expenseIncomeSwitch.setChecked(false);

                    setPreviousAccount(logCursor.getInt(DBAdapter.COLUMN_LOG_ACCOUNT));
                    setPreviousDateAndTime(logCursor.getLong(DBAdapter.COLUMN_LOG_TIME));
                    String amount = String.format(Locale.getDefault(), "%.2f", logCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT)).replace(",", ".");
                    amountEditText.setText(amount);
                    notesEditText.setText(logCursor.getString(DBAdapter.COLUMN_LOG_NOTES));
                }
                // preserved, not displayed -- see existingImageUri
                if (logCursor.getString(DBAdapter.COLUMN_LOG_IMAGE) != null)
                    existingImageUri = logCursor.getString(DBAdapter.COLUMN_LOG_IMAGE);

                // setup repeating from edit
                if (logCursor.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID) > -1) {
                    setRepeatingData(logCursor.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID),
                            logCursor.getLong(DBAdapter.COLUMN_LOG_TIME), fill);
                }
            }
            if (logCursor != null)
                logCursor.close();
            if (BuildConfig.DEBUG) Log.i(NEWLOG, "new title");
            navigator.setToolbarTitle(requireActivity().getResources().getString(R.string.edit_record));
            if (fill) {
                filledValues = formValues();
                filledForm = formState();
            }
        }

        formFilled = true;
    }

    /**
     * Shows the record's series: its schedule, now editable, and where the record sits in it.
     * The switch stays locked -- a record leaves its series through "Only this entry".
     */
    private void setRepeatingData(int repeatingId, long recordTime, boolean fill) {
        editingSeries = dbAdapter.getRepeatingSeries(repeatingId);
        if (editingSeries == null) {
            // Its series entry is gone, so there is no schedule to show or change; the record
            // saves as an ordinary one.
            return;
        }
        editingRepeatingId = repeatingId;
        editingOriginalTime = recordTime;
        repeatingSwitch.setChecked(true);
        repeatingSwitch.setEnabled(false);
        frequencySwitched(true);
        if (fill) {
            // Locale.ROOT: enteredFrequency() parses it back with Integer.parseInt
            frequencyEditText.setText(String.format(Locale.ROOT, "%d", editingSeries.frequency));
            if (editingSeries.period >= 0 && editingSeries.period < frequencySpinner.getCount())
                frequencySpinner.setSelection(editingSeries.period);
            setFrequencyEndTime(editingSeries.endTime);
        }
        int position = editingSeries.positionOf(recordTime);
        if (position > 0) {
            // The locked switch says nothing here; the header names the entry instead.
            String text = getString(R.string.recurring_position, position, editingSeries.size());
            SpannableString styled = new SpannableString(text);
            int entryEnd = text.indexOf(" of ");
            if (entryEnd > 0)
                styled.setSpan(new StyleSpan(Typeface.BOLD), 0, entryEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            seriesInfoTextView.setText(styled);
            repeatToggleLayout.setVisibility(View.GONE);
            seriesInfoTextView.setVisibility(View.VISIBLE);
            seriesPreviousButton.setVisibility(View.VISIBLE);
            seriesNextButton.setVisibility(View.VISIBLE);
            setArrowEnabled(seriesPreviousButton, position > 1);
            setArrowEnabled(seriesNextButton, position < editingSeries.size());
        }
        updateRepeatSummary();
    }

    private IconDrawable recurringIcon() {
        return new IconDrawable(requireContext(), FontAwesomeIcons.fa_history)
                .colorRes(R.color.menu_icon_color).sizeDp(20);
    }

    private static void setArrowEnabled(ImageButton arrow, boolean enabled) {
        arrow.setEnabled(enabled);
        arrow.setAlpha(enabled ? 1f : 0.25f);
    }

    /**
     * Shows the previous ({@code -1}) or next ({@code +1}) entry of the series being edited.
     * Unsaved changes to this entry are dropped -- saving stays the save button, which asks which
     * entries a change is for. Does nothing at either end, or outside a series.
     */
    private void moveInSeries(int direction) {
        if (editingSeries == null || !isEditing())
            return;
        int targetId = editingSeries.neighbourOf(editingOriginalTime, direction);
        if (targetId == -1)
            return;
        showRecord(targetId);
        // a short fade, so the change reads as a new entry although the fields look alike
        scrollView.setAlpha(0.3f);
        scrollView.animate().alpha(1f).setDuration(150);
    }

    /** Loads another record into the form, as if the screen had been opened for it. */
    private void showRecord(int logId) {
        mPassedLog = String.valueOf(logId);
        // kept in the arguments too, so a rotation stays on this entry
        requireArguments().putString(ARG_PASSED_LOG, mPassedLog);
        formFilled = false;
        existingImageUri = "";
        amountEditText.setError(null);
        hideKeyboard();
        initializeViews();
    }

    private boolean isEditing() {
        return mPassedLog != null && !mPassedLog.equals("");
    }

    private void hideKeyboard() {
        View focused = requireActivity().getCurrentFocus();
        if (focused != null) {
            InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null)
                imm.hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    /**
     * The trash icon, on an existing record only.
     *
     * <p>A {@link MenuProvider} bound to the view lifecycle, not {@code setHasOptionsMenu}, which
     * is deprecated. Bound to {@code getViewLifecycleOwner} so the item goes with the screen: the
     * fragment's own lifecycle outlives its view on the back stack, which would leave a trash icon
     * on the toolbar of whatever came next.
     */
    private void addMenu() {
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
                if (!isEditing())
                    return;
                MenuItem delete = menu.add(Menu.NONE, MENU_DELETE_RECORD, 1, R.string.menu_delete);
                try {
                    delete.setIcon(new IconDrawable(requireActivity(), MaterialIcons.md_delete)
                            .actionBarSize().colorRes(R.color.actionBarWhite));
                } catch (Exception e) {
                    e.printStackTrace();
                }
                delete.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem item) {
                if (item.getItemId() == MENU_DELETE_RECORD) {
                    deleteRecord();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    /**
     * Deletes the record being edited -- a series entry through the usual only this / following /
     * whole series choice -- then closes the editor; the snackbar offers Undo. The record's
     * values as stored count, not as edited.
     */
    private void deleteRecord() {
        LogItem record = new LogItem();
        try (Cursor log = dbAdapter.getLog(mPassedLog)) {
            if (log == null || !log.moveToFirst())
                return;
            record.setId(log.getInt(DBAdapter.COLUMN_LOG_ID));
            record.setTimeStamp(log.getLong(DBAdapter.COLUMN_LOG_TIME));
            record.setAmount(log.getDouble(DBAdapter.COLUMN_LOG_AMOUNT));
            record.setRepeatingId(log.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
        }
        Runnable close = () -> {
            if (!isAdded())
                return;
            hideKeyboard();
            formFilled = false;
            requireActivity().getSupportFragmentManager().popBackStack();
        };
        if (record.getRepeatingId() > -1) {
            RecurringScope.confirmDelete(requireActivity(), dbAdapter, utility, prefManager, record, close);
        } else {
            DBAdapter.DeletedLogs deleted = dbAdapter.deleteLogs(Collections.singletonList(record.getId()));
            DeleteUndo.offer(utility, getResources(), dbAdapter, deleted);
            close.run();
        }
    }

    private void setupFrequency(boolean fill) {
        if (fill) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.YEAR, cal.get(Calendar.YEAR) + 1);
            setFrequencyEndTime(endOfDay(cal));
        } else {
            setFrequencyEndTime(frequencyEndTime);
        }
        frequencyEndingDateTextView.setOnClickListener(this);
        repeatingSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    frequencySwitched(true);
                    // an empty interval used to save the record as not repeating, silently
                    if (frequencyEditText.getText().toString().isEmpty()) {
                        frequencyEditText.setText("1");
                        frequencySpinner.setSelection(RepeatingSeries.PERIOD_MONTH);
                    }
                } else {
                    frequencySwitched(false);
                }
            }
        });
        // A recreated switch restores its state before this listener exists.
        frequencySwitched(repeatingSwitch.isChecked());
    }

    /**
     * The series' end is exclusive, so the last millisecond of the chosen day makes that day's
     * occurrence the last one -- whatever time of day the series runs at.
     */
    private static long endOfDay(Calendar day) {
        day.set(Calendar.HOUR_OF_DAY, 23);
        day.set(Calendar.MINUTE, 59);
        day.set(Calendar.SECOND, 59);
        day.set(Calendar.MILLISECOND, 999);
        return day.getTimeInMillis();
    }

    private void setFrequencyEndTime(long endTime) {
        frequencyEndTime = endTime;
        frequencyEndingDateTextView.setText(prefManager.getDateFormat().format(new java.util.Date(endTime)));
        updateRepeatSummary();
    }

    /** The entered interval, or 0 if there is none. */
    private int enteredFrequency() {
        try {
            return Integer.parseInt(frequencyEditText.getText().toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void frequencySwitched(boolean repeat) {
        int visible = repeat ? View.VISIBLE : View.GONE;
        frequencyLayout.setVisibility(visible);
        frequencyEndingLayout.setVisibility(visible);
        updateRepeatSummary();
    }

    private void setupAccountsSpinner(boolean fill) {
        accountIdList = new ArrayList<>();
        ArrayList<String> accountLabelList = new ArrayList<>();
        View accountsDivider = requireActivity().findViewById(R.id.view_accounts_divider);

        Cursor accountsCursor = dbAdapter.getAccounts();
        if (accountsCursor != null) {
            if (accountsCursor.getCount() > 1) {
                // only setup spinner if more than one account exists
                while (accountsCursor.moveToNext()) {
                    accountIdList.add(accountsCursor.getInt(DBAdapter.COLUMN_ACCOUNT_ID));
                    accountLabelList.add(accountsCursor.getString(DBAdapter.COLUMN_ACCOUNT_LABEL));
                }
                accountSelectionLayout.setVisibility(View.VISIBLE);
                accountsDivider.setVisibility(View.VISIBLE);

                ArrayAdapter<String> adapter = new ArrayAdapter<>(requireActivity(), R.layout.accounts_spinner_dropdown_large, accountLabelList);
                accountSpinner.setAdapter(adapter);
                accountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                        selectedAccountId = accountIdList.get(position);
                        recheckSeriesEnd();
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });

                // the default account on the first fill, the user's choice after that
                int defaultAccount = fill ? prefManager.getAccountDefault() : selectedAccountId;
                // if the default account is in the id list, set it
                for (int i = 0; i < accountIdList.size(); i++) {
                    if (defaultAccount == accountIdList.get(i)) {
                        accountSpinner.setSelection(i);
                    }
                }
            } else {
                // only one account exists, hide spinner and spinner label
                accountSelectionLayout.setVisibility(View.GONE);
                accountsDivider.setVisibility(View.GONE);
            }
            accountsCursor.close();
        }
    }

    private void setupExpenseIncomeSwitch(boolean fill) {
        final TextView expenseTextView = requireActivity().findViewById(R.id.textView_expense);
        final TextView incomeTextView = requireActivity().findViewById(R.id.textView_income);

        // if default is in setting, set to default
        if (fill)
            expenseIncomeSwitch.setChecked(prefManager.getDefaultLogExpenseIncome() != 0);
        // setup category and animate views based on initial setting (after default)
        setupCategorySpinner(!fill);
        animateExpenseIncomeChange(expenseTextView, incomeTextView, !expenseIncomeSwitch.isChecked());

        expenseIncomeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                setupCategorySpinner(false);
                recheckSeriesEnd();
                if (isChecked) {
                    prefManager.setDefaultLogExpenseIncome(1);
                    animateExpenseIncomeChange(expenseTextView, incomeTextView, false);
                } else {
                    prefManager.setDefaultLogExpenseIncome(0);
                    animateExpenseIncomeChange(expenseTextView, incomeTextView, true);
                }
            }
        });
        expenseTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expenseIncomeSwitch.setChecked(!expenseIncomeSwitch.isChecked());
            }
        });

        incomeTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                expenseIncomeSwitch.setChecked(!expenseIncomeSwitch.isChecked());
            }
        });
    }

    private void setupDateAndTime(boolean fill) {
        // now, or the chosen date, on the first fill only
        Calendar c = Calendar.getInstance();
        if (fill) {
            if (newEvent != null)
                c = newEvent;
            logTime = c.getTimeInMillis();
        } else {
            c.setTimeInMillis(logTime);
        }
        logTimeTextView.setOnClickListener(this);
        logDateTextView.setOnClickListener(this);

        onTimePicked(c);
        onDatePicked(c);
    }

    /** @param keepSelection keep the category already chosen, if the list still has it */
    private void setupCategorySpinner(boolean keepSelection) {
        // show categories either alphabetically or by recency bases on user setting
        int catSorting = prefManager.getCategorySortingPreference();
        if (catSorting == 0)
            initializeCategoryList();
        else
            initializeCategoryListByRecency();

        // if new category tag setting exists, set the spinner to the right item
        if (prefManager.getNewTagFromNewLog() > -1) {
            setPreviousCategory(prefManager.getNewTagFromNewLog());
            prefManager.clearNewTagFromNewLogSetting();
            return;
        }

        if (keepSelection && categoryIdList.contains(selectedTagId)) {
            setPreviousCategory(selectedTagId);
            return;
        }

        if (mPassedLog != null && !mPassedLog.equals("")) { // if a log is passed initialize spinner data
            // get cursor from db
            Cursor logCursor = dbAdapter.getLog(mPassedLog);
            if (logCursor != null && logCursor.moveToFirst()) {
                // if previous expense setting matches current, set category to correct value
                boolean expenseSwitch = false;
                if (logCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME) == 1)
                    expenseSwitch = true;

                if (expenseIncomeSwitch.isChecked() == expenseSwitch)
                    setPreviousCategory(logCursor.getInt(DBAdapter.COLUMN_LOG_CATEGORY));

                logCursor.close();
            }
            return;
        }

        // set default selection if no other selections were made
        setSelectedCategory(getDefCatSelection(expenseIncomeSwitch.isChecked()));
    }

    private void initializeCategoryList() {
        labelList = new ArrayList<>();
        iconList = new ArrayList<>();
        categoryIdList = new ArrayList<>();

        // add new category option
        labelList.add(NewLogFragment.this.getResources().getString(R.string.new_category));
        iconList.add("md-add");
        categoryIdList.add(666);

        try (Cursor categoryTagsCursor = dbAdapter.getTagsOfType(expenseIncomeSwitch.isChecked())) {
            while (categoryTagsCursor.moveToNext()) {
                labelList.add(categoryTagsCursor.getString(DBAdapter.COLUMN_TAG_LABEL));
                iconList.add(categoryTagsCursor.getString(DBAdapter.COLUMN_TAG_ICON));
                categoryIdList.add(categoryTagsCursor.getInt(DBAdapter.COLUMN_TAG_ID));
            }
        }
    }

    private void initializeCategoryListByRecency() {
        labelList = new ArrayList<>();
        iconList = new ArrayList<>();
        categoryIdList = new ArrayList<>();

        // add new category option
        labelList.add(NewLogFragment.this.getResources().getString(R.string.new_category));
        iconList.add("md-add");
        categoryIdList.add(666);

        boolean income = expenseIncomeSwitch.isChecked();
        Map<Integer, String[]> labelAndIcon = new HashMap<>();
        try (Cursor tags = dbAdapter.getTagsOfType(income)) {
            while (tags.moveToNext())
                labelAndIcon.put(tags.getInt(DBAdapter.COLUMN_TAG_ID), new String[]{
                        tags.getString(DBAdapter.COLUMN_TAG_LABEL), tags.getString(DBAdapter.COLUMN_TAG_ICON)});
        }
        // One grouped query for the order; this used to be two queries per category.
        for (int id : dbAdapter.getTagIdsByRecency(income)) {
            String[] tag = labelAndIcon.get(id);
            if (tag == null)
                continue;
            labelList.add(tag[0]);
            iconList.add(tag[1]);
            categoryIdList.add(id);
        }
    }

    private void setPreviousCategory(int previousCategory) {
        for (int i = 0; i < categoryIdList.size(); i++) {
            if (categoryIdList.get(i) == previousCategory) {
                setSelectedCategory(i);
            }
        }
    }

    private void setPreviousAccount(int previousAccount) {
        for (int i = 0; i < accountIdList.size(); i++) {
            if (accountIdList.get(i) == previousAccount) {
                accountSpinner.setSelection(i);
            }
        }
    }

    private void setPreviousDateAndTime(long logTimeStamp) {
        // Exactly, seconds and milliseconds included: the pickers below set only the date, hour
        // and minute, and used to leave the current second on the record -- so every edit moved
        // it, and a series record always looked moved, which changes what saving it offers.
        logTime = logTimeStamp;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(logTimeStamp);
        onDatePicked(c);
        onTimePicked(c);
    }

    private int getDefCatSelection(boolean checked) {
        int defaultCategory;
        if (checked)
            defaultCategory = prefManager.getIncomeCategoryDefault();
        else
            defaultCategory = prefManager.getExpenseCategoryDefault();

        for (int i = 0; i < categoryIdList.size(); i++) {
            if (defaultCategory == categoryIdList.get(i))
                return i;
        }
        return 1;
    }

    private void initialize() {
        this.getComponent(ActivityComponent.class).inject(this);
    }

    private void setupFab() {
        FloatingActionButton fab = this.requireActivity().findViewById(R.id.fab);
        IconDrawable icon = new IconDrawable(requireActivity(), MaterialIcons.md_check);
        icon.color(getColor(requireActivity(), R.color.colorPrimaryLight));
        fab.setImageDrawable(icon);
        // hide and show again to address disappearing icon bug in material 1.0.0
        fab.hide();
        fab.show();
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // verify data and save new log
                saveLog();
            }
        });
    }

    private void animateExpenseIncomeChange(TextView expenseText, TextView incomeText, boolean expense) {
        final float largeText = getResources().getInteger(R.integer.expenseIncomeSwitchLargeText);
        final float smallText = getResources().getInteger(R.integer.expenseIncomeSwitchSmallText);
        // Animation duration in ms
        final int animationDuration = getResources().getInteger(R.integer.expenseIncomeAnimationDuration);
        int lightTextColor = getColor(requireActivity(), R.color.lightText);
        int expenseTextColor = getColor(requireActivity(), R.color.expenseColor);
        int incomeTextColor = getColor(requireActivity(), R.color.incomeColor);

        if (expense) {
            // animate change to expense
            // enlarge
            textAnimator(expenseText, smallText, largeText, animationDuration);
            // shrink
            textAnimator(incomeText, largeText, smallText, animationDuration);

            // darken
            textColorAnimator(expenseText, lightTextColor, expenseTextColor, animationDuration).start();
            // lighten
            textColorAnimator(incomeText, incomeTextColor, lightTextColor, animationDuration).start();
        } else {
            // animate change to income
            // enlarge
            textAnimator(incomeText, smallText, largeText, animationDuration);
            // shrink
            textAnimator(expenseText, largeText, smallText, animationDuration);

            // darken
            textColorAnimator(incomeText, lightTextColor, incomeTextColor, animationDuration).start();
            // lighten
            textColorAnimator(expenseText, expenseTextColor, lightTextColor, animationDuration).start();
        }

    }

    private ValueAnimator textColorAnimator(final TextView textView, int startColor, int endColor, int animationDuration) {
        ValueAnimator colorAnimation = ValueAnimator.ofObject(new ArgbEvaluator(), startColor, endColor);
        colorAnimation.setDuration(animationDuration);
        colorAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {

            @Override
            public void onAnimationUpdate(ValueAnimator animator) {
                textView.setTextColor((Integer) animator.getAnimatedValue());
            }

        });
        return colorAnimation;
    }

    private ValueAnimator textAnimator(final TextView textView, float startSize, float endSize, int animationDuration) {
        float firstBounceSize = startSize > endSize ? endSize - 1 : endSize + 1;
        float secondBounceSize = startSize > endSize ? endSize + 1 : endSize - 1;
        // total of the animations should equal the animation duration
        int secondMoveDuration = 80;
        int thirdMoveDuration = 100;
        int firstMoveDuration = animationDuration - secondMoveDuration - thirdMoveDuration;

        // first move
        ValueAnimator textOverSizeAnimation = ValueAnimator.ofFloat(startSize, firstBounceSize);
        textOverSizeAnimation.setDuration(firstMoveDuration);
        textOverSizeAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                float animatedValue = (float) valueAnimator.getAnimatedValue();
                textView.setTextSize(animatedValue);
            }
        });
        textOverSizeAnimation.start();

        // second move
        ValueAnimator textNormalSizeAnimation = ValueAnimator.ofFloat(firstBounceSize, secondBounceSize);
        textNormalSizeAnimation.setDuration(secondMoveDuration);
        textNormalSizeAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                float animatedValue = (float) valueAnimator.getAnimatedValue();
                textView.setTextSize(animatedValue);
            }
        });
        textNormalSizeAnimation.setStartDelay(firstMoveDuration);
        textNormalSizeAnimation.start();

        // third/final move
        ValueAnimator finalSizeAnimation = ValueAnimator.ofFloat(secondBounceSize, endSize);
        finalSizeAnimation.setDuration(thirdMoveDuration);
        finalSizeAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator valueAnimator) {
                float animatedValue = (float) valueAnimator.getAnimatedValue();
                textView.setTextSize(animatedValue);
            }
        });
        finalSizeAnimation.setStartDelay(firstMoveDuration + secondMoveDuration);
        finalSizeAnimation.start();

        return textNormalSizeAnimation;
    }

    private void saveLog() {
        if (!hasAmount())
            return;

        // An unchanged record: no scope dialog, no write, no upload or backup -- and no
        // "Record Updated", which would not be true.
        if (isEditing() && filledForm != null && filledForm.equals(formState())) {
            utility.snackBarMessage(requireActivity().getString(R.string.no_changes_to_save));
            leaveEditor(logTime);
            return;
        }

        LogItem logItem = getLogData();

        // if editing a repeating record
        if (editingSeries != null) {
            logItem.setRepeatingId(editingRepeatingId);
            saveSeriesRecord(logItem);
        } else {
            // if repeating is enabled and not editing a repeating set, start setting that up
            logItem.setRepeatingId(-1);
            if (repeatingSwitch.isChecked()) {
                if (enteredFrequency() <= 0) {
                    utility.snackBarMessage(requireActivity().getString(R.string.recurring_invalid_interval));
                    return;
                }
                int repeatingId = createRepeatingRecords(logItem);
                if (repeatingId == -1) {
                    // Nothing of the series was written, so the first record is not saved
                    // either: the user retries rather than keeping an orphan.
                    utility.snackBarMessage(requireActivity().getString(R.string.error_saving));
                    return;
                }
                logItem.setRepeatingId(repeatingId);

            }

            // update an existing log
            if (mPassedLog != null && !mPassedLog.equals("")) {
                if (dbAdapter.updateLog(Integer.parseInt(mPassedLog), logItem.getTimeStamp(), logItem.getExpenseIncome(),
                        logItem.getAccountId(), logItem.getAmount(), logItem.getCategory(), logItem.getNotes(), logItem.getImageUri(), logItem.getRepeatingId())) {
                    utility.snackBarMessage(requireActivity().getString(R.string.record_updated));
                    successfulSave(false, logItem.getTimeStamp());
                } else {
                    utility.snackBarMessage(requireActivity().getString(R.string.error_updating));
                }
            } else {
                // save a new log
                if (dbAdapter.newLog(logItem.getTimeStamp(), logItem.getExpenseIncome(), logItem.getAccountId(),
                        logItem.getAmount(), logItem.getCategory(), logItem.getNotes(), logItem.getImageUri(), logItem.getRepeatingId())) {
                    utility.snackBarMessage(requireActivity().getString(R.string.record_saved));
                    successfulSave(true, logItem.getTimeStamp());
                } else {
                    utility.snackBarMessage(requireActivity().getString(R.string.error_saving));
                }
            }
        }
    }

    /**
     * @param recordTime the saved record's time -- the first entry of a new series, the edited
     *                   entry of an existing one -- so the screen after it opens on its period
     */
    private void successfulSave(boolean save, long recordTime) {
        afterWrite();
        leaveEditor(recordTime);
    }

    /** Goes where a save goes, opening on the record's period. */
    private void leaveEditor(long recordTime) {
        clearEnteredValues();
        navigator.changeFragment((AppCompatActivity) requireActivity(),
                navigator.getMenuItem(prefManager.getAfterNewLogScreen()), recordTime);
    }

    /** What every save does once written: the Dropbox upload and the local backups. */
    private void afterWrite() {
        // upload changes if premium and syncing enabled
        if (prefManager.dropboxSyncEnabled())
            dropBoxHelper.onDropboxAction("", DropBoxHelper.KEY_DROPBOX_UPLOAD);

        // create local backup
        // Was gated on WRITE_EXTERNAL_STORAGE, which meant automatic backups silently never ran
        // for anyone who declined it. Backups now go to the app's own directory: no permission.
        LocalBackupManager.checkBackups(requireContext());
    }

    private void clearEnteredValues() {
        amountEditText.setText("");
        notesEditText.setText("");
        frequencyEditText.setText("");
        repeatingSwitch.setChecked(false);
        // Back can return to this screen: it starts over from the defaults
        formFilled = false;
        // hide keyboard if visible
        View view = requireActivity().getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    /**
     * Saves a record of a repeating series, asking which of its records the change is for.
     *
     * <p>All three choices are always shown; what can apply depends on what changed. A new
     * interval or end belongs to the series, so "Only this entry" is disabled with one. A new
     * interval or date replaces the dates from this record on, so "The whole series" is disabled
     * with one except from the series' first record, where it is the same as "This and following".
     *
     * <p>A new end alone is the whole series' and nothing else: "This and following" is disabled
     * too, and the end moves without splitting the series, which "This and following" would.
     * It may fall before this entry, deleting it with the rest. A line above the choices says
     * what the end does, and whatever it deletes is offered back with Undo.
     */
    private void saveSeriesRecord(final LogItem logItem) {
        final RepeatingSeries series = editingSeries;
        final int frequency = enteredFrequency();
        final int period = frequencySpinner.getSelectedItemPosition();
        if (frequency <= 0) {
            utility.snackBarMessage(requireActivity().getString(R.string.recurring_invalid_interval));
            return;
        }
        final boolean scheduleChanged = frequency != series.frequency || period != series.period;
        final boolean dateChanged = logItem.getTimeStamp() != editingOriginalTime;
        final boolean endChanged = frequencyEndTime != series.endTime;
        final long endTime = frequencyEndTime;
        final boolean pastEnd = endTime <= logItem.getTimeStamp();
        if (pastEnd && (scheduleChanged || (endChanged && dateChanged))) {
            utility.snackBarMessage(requireActivity().getString(R.string.recurring_end_before_entry));
            return;
        }
        final boolean endOnly = endChanged && !scheduleChanged && !dateChanged && !valuesChanged();
        if (pastEnd && endChanged && !endOnly) {
            // This entry is past the new end and would be deleted, so its other changes could
            // only be "The whole series" -- written onto every earlier entry. Refused instead,
            // and said under Ending too, where the form gives its reasons (the form re-checks
            // only when the schedule or date changes, not on every keystroke in the amount).
            String reason = requireActivity().getString(R.string.recurring_end_before_entry_with_changes);
            repeatErrorTextView.setText(reason);
            repeatErrorTextView.setVisibility(View.VISIBLE);
            utility.snackBarMessage(reason);
            return;
        }
        // What a later end adds, counted once. With a new interval or date the records from
        // here on are replaced anyway, so there is nothing to count. "This and following" from
        // a later entry extends only the part it splits off, whose schedule is recovered from
        // that part alone, so it is counted too.
        final boolean countable = !scheduleChanged && !dateChanged;
        final int added = endChanged && countable ? DBAdapter.occurrencesAddedBy(series, endTime) : 0;
        final boolean splits = endChanged && countable && !endOnly && series.positionOf(editingOriginalTime) > 1;
        final int addedToSplit = splits
                ? DBAdapter.occurrencesAddedBy(series.from(editingOriginalTime), endTime) : added;
        // Refused now, if every choice would refuse, rather than after a dialog offering it.
        if (refusesExtension(added) && refusesExtension(addedToSplit)) {
            if (added < 0)
                utility.snackBarMessage(requireActivity().getString(R.string.recurring_no_schedule));
            else
                utility.snackBarMessage(getResources().getQuantityString(R.plurals.recurring_too_long,
                        DBAdapter.MAX_REPEATING_OCCURRENCES, DBAdapter.MAX_REPEATING_OCCURRENCES));
            return;
        }
        String followingUnavailable = endOnly ? getString(R.string.recurring_unavailable_end_only) : null;
        // A count only where both choices would add the same; otherwise the line gives the date.
        String endLine = endChanged
                ? describeEndChange(series, endTime, countable, added == addedToSplit ? added : 0, pastEnd) : null;

        RecurringScope.chooseSave(requireActivity(), prefManager, series, editingOriginalTime,
                !scheduleChanged && !endChanged, !scheduleChanged && !dateChanged,
                followingUnavailable, endLine, scope -> {
                    DBAdapter.DeletedLogs[] removed = new DBAdapter.DeletedLogs[1];
                    int result;
                    if (scope == RecurringScope.ONE) {
                        // the record leaves its series, as it always has
                        result = dbAdapter.updateLog(Integer.parseInt(mPassedLog), logItem.getTimeStamp(),
                                logItem.getExpenseIncome(), logItem.getAccountId(), logItem.getAmount(),
                                logItem.getCategory(), logItem.getNotes(), logItem.getImageUri(), -1) ? 0 : -1;
                    } else if (endOnly) {
                        // the whole series' end, and nothing else: no split, no values rewritten
                        result = dbAdapter.setRepeatingEnd(series.id, endTime, d -> removed[0] = d);
                    } else if (scope == RecurringScope.FOLLOWING && (scheduleChanged || dateChanged)) {
                        if (pastEnd) {
                            utility.snackBarMessage(requireActivity().getString(R.string.recurring_end_before_entry));
                            return;
                        }
                        result = dbAdapter.restartRepeatingFrom(series.id, editingOriginalTime, logItem,
                                frequency, period, endTime);
                    } else {
                        long from = scope == RecurringScope.ALL ? Long.MIN_VALUE : editingOriginalTime;
                        result = dbAdapter.updateRepeatingFrom(series.id, from, logItem, endTime,
                                d -> removed[0] = d);
                    }

                    if (result >= 0) {
                        if (removed[0] == null)
                            utility.snackBarMessage(requireActivity().getString(
                                    scope == RecurringScope.ONE ? R.string.record_updated : R.string.records_updated));
                        else if (endOnly)
                            DeleteUndo.offer(utility, getResources(), dbAdapter, removed[0]);
                        else
                            // The values were saved too, and the button does not take them back:
                            // it restores the deleted entries, at the new values, and the old end.
                            // "Undo" after "Records updated" would read as taking back the save.
                            DeleteUndo.offer(utility, getResources(), dbAdapter, removed[0],
                                    getResources().getQuantityString(R.plurals.records_updated_deleted,
                                            removed[0].size(), removed[0].size()), R.string.restore);
                        if (pastEnd && endChanged) {
                            // An end before this entry deleted it: close this editor, as the trash
                            // does, back to the screen it was opened from -- pushing the screen
                            // after an entry as well would stack it on that one -- and have the
                            // records views show where the series now ends.
                            afterWrite();
                            hideKeyboard();
                            formFilled = false;
                            ViewedPeriod.focusOn(endTime);
                            requireActivity().getSupportFragmentManager().popBackStack();
                        } else {
                            successfulSave(false, logItem.getTimeStamp());
                        }
                    } else if (result == DBAdapter.SERIES_NO_SCHEDULE) {
                        utility.snackBarMessage(requireActivity().getString(R.string.recurring_no_schedule));
                    } else if (result == DBAdapter.SERIES_TOO_LONG) {
                        utility.snackBarMessage(getResources().getQuantityString(R.plurals.recurring_too_long,
                                DBAdapter.MAX_REPEATING_OCCURRENCES, DBAdapter.MAX_REPEATING_OCCURRENCES));
                    } else {
                        utility.snackBarMessage(requireActivity().getString(R.string.error_updating));
                    }
                });
    }

    /** Whether a count from {@link DBAdapter#occurrencesAddedBy} means the save would refuse. */
    private static boolean refusesExtension(int added) {
        return added < 0 || added > DBAdapter.MAX_REPEATING_OCCURRENCES;
    }

    /**
     * Whether the form changes anything of the record but its date and series: amount, type,
     * account, category or notes, against what it showed when filled from the record.
     */
    private boolean valuesChanged() {
        return filledValues == null || !filledValues.equals(formValues());
    }

    /** The amount, notes, type, account and category as the form shows them. */
    private String formValues() {
        int account = accountSelectionLayout.getVisibility() == View.VISIBLE
                ? accountSpinner.getSelectedItemPosition() : -1;
        return amountEditText.getText().toString().trim() + "\n" + notesEditText.getText() + "\n"
                + expenseIncomeSwitch.isChecked() + "\n" + account + "\n" + selectedTagId;
    }

    /** Everything a save could write, as the form shows it now; see {@link #filledForm}. */
    private String formState() {
        return formState(formValues(), logTime, repeatingSwitch.isChecked(), enteredFrequency(),
                frequencySpinner.getSelectedItemPosition(), frequencyEndTime);
    }

    /**
     * The values, the date and time, and -- only with Repeat on -- the schedule and its end. With
     * Repeat off the hidden schedule fields save nothing, so switching Repeat on and off again,
     * whatever was typed meanwhile, is no change.
     */
    static String formState(String values, long time, boolean repeat, int frequency, int period,
                            long endTime) {
        String state = values + "\n" + time;
        return repeat ? state + "\nrepeat " + frequency + "/" + period + " until " + endTime
                : state + "\nonce";
    }

    /**
     * "Ends Mar 5, 2027 · deletes 7 entries", "· adds 7 entries", or just the date: what moving
     * the end does to the series as it stands. With a new interval or date the records from here on
     * are replaced anyway, so there is no count to give.
     */
    private String describeEndChange(RepeatingSeries series, long endTime, boolean countable, int added,
                                     boolean pastEnd) {
        String day = prefManager.getDateFormat().format(new java.util.Date(endTime));
        if (!countable)
            return getString(R.string.recurring_end_line, day);
        int deleted = series.countFrom(endTime);
        if (deleted > 0)
            return getResources().getQuantityString(pastEnd ? R.plurals.recurring_end_line_deletes_this
                    : R.plurals.recurring_end_line_deletes, deleted, deleted, day);
        if (added > 0)
            return getResources().getQuantityString(R.plurals.recurring_end_line_adds, added, added, day);
        return getString(R.string.recurring_end_line, day);
    }

    private int createRepeatingRecords(LogItem logItem) {
        int repeatingId;
        int repeatingPeriodFrequency = enteredFrequency();
        int repeatingPeriod = frequencySpinner.getSelectedItemPosition();
        // the repeating entry and every occurrence after the first, in one transaction; -1 if
        // nothing was written
        repeatingId = dbAdapter.createRepeatingSeries(logItem.getTimeStamp(), frequencyEndTime,
                logItem.getAmount(), repeatingPeriodFrequency, repeatingPeriod,
                logItem.getExpenseIncome(), logItem.getAccountId(), logItem.getCategory(),
                logItem.getNotes(), logItem.getImageUri());
        return repeatingId;
    }

    /**
     * An empty amount used to save as 0 and report "Record saved" -- a whole series of them, with
     * repeat on. Refused at the field instead. A typed 0 is still a deliberate amount and saves.
     */
    private boolean hasAmount() {
        String amount = amountEditText.getText().toString().trim();
        if (!amount.isEmpty() && !amount.equals("."))
            return true;
        amountEditText.setError(getString(R.string.amount_required));
        amountEditText.requestFocus();
        InputMethodManager imm = (InputMethodManager) requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
            imm.showSoftInput(amountEditText, InputMethodManager.SHOW_IMPLICIT);
        return false;
    }

    private LogItem getLogData() {
        LogItem item = new LogItem();
        // verify amount data
        double amount = 0;
        if (!amountEditText.getText().toString().equals("") && !amountEditText.getText().toString().equals(".")) {
            // replace comma in case user has commas instead of periods
            amount = Double.parseDouble(amountEditText.getText().toString());
        }
        item.setAmount(amount);

        item.setTimeStamp(logTime);
        int expenseIncome = 0;
        if (expenseIncomeSwitch.isChecked())
            expenseIncome = 1;
        item.setExpenseIncome(expenseIncome);

        int selectedAccount;
        // if accounts are visible get the selected one, otherwise get the only available one
        if (accountSelectionLayout.getVisibility() == View.VISIBLE) {
            selectedAccount = accountIdList.get(accountSpinner.getSelectedItemPosition());
            prefManager.setAccountDefault(selectedAccount);
        } else {
            Cursor accountCursor = dbAdapter.getAccounts();
            accountCursor.moveToFirst();
            selectedAccount = accountCursor.getInt(DBAdapter.COLUMN_ACCOUNT_ID);
            accountCursor.close();
        }
        item.setAccountId(selectedAccount);

        item.setNotes(notesEditText.getText().toString());

        // carried through unchanged; the image feature is gone but the column is not
        item.setImageUri(existingImageUri);

        item.setCategory(selectedTagId);
        if (item.getExpenseIncome() == 0)
            prefManager.setExpenseCategoryDefault(selectedTagId);
        else
            prefManager.setIncomeCategoryDefault(selectedTagId);

        return item;
    }

    void onDatePicked(Calendar date) {
        Calendar c = Calendar.getInstance();
        // set the new log dates as necessary
        c.setTimeInMillis(logTime);
        c.set(Calendar.YEAR, date.get(Calendar.YEAR));
        c.set(Calendar.MONTH, date.get(Calendar.MONTH));
        c.set(Calendar.DAY_OF_YEAR, date.get(Calendar.DAY_OF_YEAR));
        // save the new log time
        logTime = c.getTimeInMillis();
        // set the updated display date
        logDateTextView.setText(prefManager.getDateFormat().format(c.getTime()));
        updateRepeatSummary();
    }

    void onTimePicked(Calendar time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(logTime);
        c.set(Calendar.HOUR_OF_DAY, time.get(Calendar.HOUR_OF_DAY));
        c.set(Calendar.MINUTE, time.get(Calendar.MINUTE));
        logTime = c.getTimeInMillis();
        logTimeTextView.setText(prefManager.getTimeFormat().format(c.getTime()));
        updateRepeatSummary();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.textView_tagListItem) {
            showCategorySelectionDialog();
        } else if (id == R.id.textView_logDate) {
            DialogFragment newFrag = new DatePickerFragment();
            Bundle argsD = new Bundle();
            argsD.putLong(DatePickerFragment.ARG_DATE, logTime);
            newFrag.setArguments(argsD);
            newFrag.show(requireFragmentManager(), "datePicker");
        } else if (id == R.id.textView_logTime) {
            // show the time picker dialog
            DialogFragment newFragment = new TimePickerFragment();
            Bundle args = new Bundle();
            args.putLong("time", logTime);
            newFragment.setArguments(args);
            newFragment.show(requireFragmentManager(), "timePicker");
        } else if (id == R.id.textView_frequencyEndingDate) {
            // show a date picker dialog
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(frequencyEndTime);
            Dialog d = AppLocale.datePicker(requireActivity(), datePickerListener, c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
            d.show();
        }
    }

    private void showCategorySelectionDialog() {
        final CategoryListAdapter adapter =
                new CategoryListAdapter(requireActivity(), R.layout.tag_list_item, labelList, iconList, categoryIdList);

        int sortingPref = prefManager.getCategorySortingPreference();
        String titleString;
        String optionString;
        if (sortingPref == 0) {
            titleString = requireContext().getString(R.string.alphabetical);
            optionString = requireContext().getString(R.string.sort) + " " + requireContext().getString(R.string.latest);
        } else {
            titleString = requireContext().getString(R.string.latest);
            optionString = requireContext().getString(R.string.sort) + " " + requireContext().getString(R.string.alphabetical);
        }

        AlertDialog.Builder builderSingle = new AlertDialog.Builder(requireActivity());
        builderSingle.setTitle(getResources().getString(R.string.category) + " (" + titleString + ")");
        builderSingle.setNeutralButton(optionString, ((dialog, which) -> {

            // category sorting option selected. change setting, repopulate category list and show category selection again
            prefManager.setCategorySortingPreference(sortingPref == 0 ? 1 : 0);
            setupCategorySpinner(true);
            showCategorySelectionDialog();

        }));
        builderSingle.setNegativeButton(getResources().getString(R.string.cancel), (dialog, which) -> dialog.dismiss());
        builderSingle.setAdapter(
                adapter,
                (dialog, which) -> {
                    // if new cat selected, go to new cat screen with flag set
                    if (which == 0) {
                        // add a new category
                        navigator.newTempFragment(NewCategoryFragment.newInstance(NewCategoryFragment.NEW_TAG_KEY, true),
                                getResources().getString(R.string.new_category));
                    } else {
                        // set display text to selected item
                        setSelectedCategory(which);
                    }
                });
        builderSingle.show();
    }

    private void setSelectedCategory(int selectedCat) {
        IconDrawable tempIcon;
        try {
            tempIcon = new IconDrawable(requireActivity(), iconList.get(selectedCat));
        } catch (Exception e) {
            tempIcon = new IconDrawable(requireActivity(), FontAwesomeIcons.fa_tag);
            e.printStackTrace();
        }
        Drawable d = tempIcon.mutate();
        d.setBounds(0, 0, 100, 100);
        selectedTagLabel.setCompoundDrawables(d, null, null, null);
        selectedTagLabel.setCompoundDrawablePadding(35);

        try {
            selectedTagLabel.setText(labelList.get(selectedCat));
            selectedTagId = categoryIdList.get(selectedCat);
            recheckSeriesEnd();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public class CategoryListAdapter extends ArrayAdapter<String> {

        private final LayoutInflater inflater;
        private Context ctx;
        private ArrayList<String> labelArray;
        private ArrayList<String> iconArray;
        private ArrayList<Integer> idArray;

        public CategoryListAdapter(Context context, int resource, ArrayList<String> labels,
                                   ArrayList<String> icons, ArrayList<Integer> ids) {
            super(context, resource, labels);
            this.ctx = context;
            this.labelArray = labels;
            this.iconArray = icons;
            this.idArray = ids;

            inflater = (LayoutInflater) ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
            return getCustomView(position, convertView, parent);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            return getCustomView(position, convertView, parent);
        }

        View getCustomView(int position, View convertView, ViewGroup parent) {
//            LayoutInflater inflater = (LayoutInflater) ctx.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View row = inflater.inflate(R.layout.tag_list_item, parent, false);
            row.setTag(idArray.get(position));

            TextView textView = row.findViewById(R.id.textView_tagListItem);
            textView.setText(labelArray.get(position));

            ImageView imageView = row.findViewById(R.id.imageView_tagIcon);

            try {
                IconDrawable icon = new IconDrawable(requireActivity(), iconArray.get(position));
                imageView.setImageDrawable(icon);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return row;
        }
    }

    private DatePickerDialog.OnDateSetListener datePickerListener
            = new DatePickerDialog.OnDateSetListener() {

        // when dialog box is closed, below method will be called.
        public void onDateSet(DatePicker view, int selectedYear,
                              int selectedMonth, int selectedDay) {
            Calendar c = Calendar.getInstance();
            c.set(selectedYear, selectedMonth, selectedDay);
            setFrequencyEndTime(endOfDay(c));
        }
    };

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
