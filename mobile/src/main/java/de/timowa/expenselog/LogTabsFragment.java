package de.timowa.expenselog;

import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.PagerTabStrip;
import androidx.viewpager.widget.ViewPager;
import androidx.core.view.MenuProvider;
import androidx.lifecycle.Lifecycle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.FontAwesomeIcons;
import de.timowa.expenselog.icons.MaterialIcons;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.databinding.FragmentRecordsTabsBinding;

public class LogTabsFragment extends BaseFragment implements AdapterView.OnItemSelectedListener {
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";
    private static final String ARG_SHOW_TIME = "show_time";

    private static final String SAVED_PAGE_TAG = "saved_page_tag";
    private static SectionsPagerAdapter mSectionsPagerAdapter;

    private String mFilterString = null;

    private int recordsTimeMode = 0;
    private int recordsViewMode = 0;
    static final int KEY_VIEW_MODE_LIST = 0;
    static final int KEY_VIEW_MODE_GRAPH = 1;
    static final int KEY_VIEW_MODE_CALENDAR = 2;
    static final int KEY_VIEW_MODE_SUMMARY = 3;

    static final int KEY_RECORDS_MODE_DEFAULT = -1;
    static final int KEY_RECORDS_MODE_ALL = 0;
    static final int KEY_RECORDS_MODE_YEAR = 1;
    static final int KEY_RECORDS_MODE_MONTH = 2;
    static final int KEY_RECORDS_MODE_WEEK = 3;
    static final int KEY_RECORDS_MODE_DAY = 4;

    private static final int NUM_OF_PAGES = 1000;
    private static final int MID_PAGE = NUM_OF_PAGES / 2;

    private FragmentRecordsTabsBinding binding;

    @Inject
    Navigator navigator;
    @Inject
    PrefManager prefManager;
    @Inject
    SpreadsheetHelper spreadsheetHelper;

    ViewPager mViewPager;

    private Spinner navSpinner;
    @SuppressWarnings("FieldCanBeLocal")
    private int OPTIONS_MENU_GROUP_ID = 345;
    private int currentPage = MID_PAGE;
    /** Set while updateRecordsFragment rebuilds the pager, whose own page changes mean nothing. */
    private boolean rebuildingPager;
    /** From chooseTarget: no time to page to, so this screen keeps its own page. */
    private static final long NO_TARGET = Long.MIN_VALUE;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param paramTimeMode     Passed Records mode to start page on.
     * @param param2RecordsMode Parameter 2.
     * @return A new instance of fragment RecordsTabsFragment.
     */
    public static LogTabsFragment newInstance(int paramTimeMode, int param2RecordsMode) {
        LogTabsFragment fragment = new LogTabsFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_PARAM1, paramTimeMode);
        args.putInt(ARG_PARAM2, param2RecordsMode);
        fragment.setArguments(args);
        return fragment;
    }

    /**
     * Opens on the period holding {@code timeMillis} rather than on the {@link ViewedPeriod}.
     * Consumed on the first showing, which then becomes the viewed period. Used after a save, so
     * the record just saved is in view.
     */
    LogTabsFragment showingTime(long timeMillis) {
        requireArguments().putLong(ARG_SHOW_TIME, timeMillis);
        return this;
    }

    public LogTabsFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            recordsTimeMode = getArguments().getInt(ARG_PARAM1);
            recordsViewMode = getArguments().getInt(ARG_PARAM2);
        }

    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        binding = FragmentRecordsTabsBinding.inflate(inflater, container, false);
        View view = binding.getRoot();

        mViewPager = binding.pager;
        mViewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (!rebuildingPager && isAdded())
                    ViewedPeriod.paged(periodStartAt(position), periodEndAt(position), System.currentTimeMillis());
            }
        });

        setupFab();

        return view;
    }

    private void setupFab() {
        FloatingActionButton fab = this.requireActivity().findViewById(R.id.fab);
        // show fab for appropriate records modes
        if (showsNewRecordFab()) {
            fab.setImageDrawable(ContextCompat.getDrawable(requireActivity(), R.drawable.ic_action_new));
            // hide and show again to address disappearing icon bug in material 1.0.0
            fab.hide();
            fab.show();
            fab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    // if looking at a range outside of current time, send to new log with date
                    long now = Calendar.getInstance().getTimeInMillis();
                    Calendar start = getPeriodStart(recordsTimeMode, mViewPager.getCurrentItem() - MID_PAGE,
                            requireActivity(), prefManager.getPrefsFile());
                    Calendar end = getPeriodEnd(recordsTimeMode, mViewPager.getCurrentItem() - MID_PAGE,
                            requireActivity(), prefManager.getPrefsFile());
                    if (BuildConfig.DEBUG)
                        Log.i("TABS", "fab: " + start.getTime() + " : " + end.getTime());
                    if (now < start.getTimeInMillis() | now > end.getTimeInMillis())
                        navigator.newTempFragment(NewLogFragment.newInstance("", start.getTimeInMillis() + ""),
                                getResources().getString(R.string.new_record));
                    else // send to new category frag
                        navigator.changeFragment((AppCompatActivity) requireActivity(),
                                navigator.getMenuItem(R.id.nav_new_log));
                }
            });
        } else {
            fab.hide();
        }
    }

    private boolean showsNewRecordFab() {
        return recordsViewMode == KEY_VIEW_MODE_SUMMARY || recordsViewMode == KEY_VIEW_MODE_LIST;
    }

    /**
     * Shows the export mini FAB, which lives in the activity layout but belongs to this screen
     * alone: shown here from onResume and hidden again in onStop, the same lifecycle as
     * spinner_nav. It sits over the + FAB where that is shown and drops into its corner where it
     * is not (Graph, Calendar), so export is in the same reach on every view.
     */
    private void setupExportFab() {
        FloatingActionButton exportFab = requireActivity().findViewById(R.id.fab_export);
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) exportFab.getLayoutParams();
        params.bottomMargin = getResources().getDimensionPixelSize(showsNewRecordFab()
                ? R.dimen.fab_export_margin_bottom_stacked : R.dimen.fab_export_margin_bottom_alone);
        exportFab.setLayoutParams(params);
        exportFab.setTooltipText(getString(R.string.export_spreadsheet));
        exportFab.setOnClickListener(v -> exportSpreadsheet());
        exportFab.show();
    }

    private void exportSpreadsheet() {
        // No permission check: the CSV is written to the app's own directory and shared as
        // a FileProvider URI, so WRITE_EXTERNAL_STORAGE was never what made this work.

        spreadsheetHelper.exportCSV(recordsTimeMode, mViewPager.getCurrentItem() - MID_PAGE,
                recordsViewMode, DBAdapter.KEY_LOG_TIME, mFilterString);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        addMenu();

        this.getComponent(ActivityComponent.class).inject(this);

        if (savedInstanceState != null) {
            // if a page is saved
            currentPage = savedInstanceState.getInt(SAVED_PAGE_TAG, MID_PAGE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (recordsViewMode != KEY_VIEW_MODE_CALENDAR) {
            requireActivity().findViewById(R.id.spinner_nav).setVisibility(View.VISIBLE);
        }
        // update the filter if it's set
        setFilter(MainActivity.mFilterArray);

        setupSpinner();
        setupExportFab();
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (isAdded()) {
            Utility.log("LogTabsFragment", "onSaveInstanceState: " + mViewPager.getCurrentItem());
            outState.putInt(SAVED_PAGE_TAG, mViewPager.getCurrentItem());
        }
    }

    private void setupSpinner() {
        // set time view mode from settings is default record mode is sent
        if (recordsTimeMode == KEY_RECORDS_MODE_DEFAULT)
            recordsTimeMode = prefManager.getTimeViewModeDefault();

        navSpinner = requireActivity().findViewById(R.id.spinner_nav);
        // The open list shows the full "Records by …" labels; the toolbar shows the short ones
        final CharSequence[] shortLabels = getResources().getTextArray(R.array.date_range_spinner_short);
        ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(requireActivity(),
                R.layout.toolbar_spinner_selected, getResources().getTextArray(R.array.date_range_spinner)) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setText(shortLabels[position]);
                return view;
            }
        };
        // Specify the layout to use when the list of choices appears
        adapter.setDropDownViewResource(R.layout.toolbar_spinner_dropdown);

        // switch spinner so that it always selects a new item on initiation
        if (recordsTimeMode == 0)
            navSpinner.setSelection(1);
        else
            navSpinner.setSelection(0);

        // Apply the adapter to the spinner
        navSpinner.setAdapter(adapter);
        navSpinner.setOnItemSelectedListener(this);
        navSpinner.setSelection(recordsTimeMode, true);
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // No page change here: a new period length pages to the viewed period, in
        // updateRecordsFragment. It used to jump to today.
        if (BuildConfig.DEBUG) Log.i("CALT", "onItemSelected");
        recordsTimeMode = position;

        // save current time mode as default if not selecting calendar
        if (recordsViewMode != LogTabsFragment.KEY_VIEW_MODE_CALENDAR)
            prefManager.setLastRecordsTimeMode(recordsTimeMode);

        updateRecordsFragment();
    }

    void updateRecordsFragment() {
        if (isAdded()) {
            long target = chooseTarget();
            if (target != NO_TARGET)
                currentPage = MID_PAGE + pageOffsetFor(recordsTimeMode, target, requireActivity(),
                        prefManager.getPrefsFile(), Calendar.getInstance());
            else if (mViewPager.getCurrentItem() != 0)
                // Nothing remembered -- a second MainActivity (an .edb opened from a file manager)
                // cleared it -- so stay on the page showing: paging by hand does not update
                // currentPage, only a rebuild does.
                currentPage = mViewPager.getCurrentItem();

            if (BuildConfig.DEBUG) Log.i("CALT", "updateRecordsFragment");
            if (BuildConfig.DEBUG) Log.i("LogTabsFragment", "current page: " + currentPage);

            // finally: a flag left set would stop paging by hand being remembered for good
            rebuildingPager = true;
            try {
                mSectionsPagerAdapter = new SectionsPagerAdapter(getChildFragmentManager());
                Animation anim = AnimationUtils.loadAnimation(requireActivity(), R.anim.fade_in);
                mViewPager.startAnimation(anim);
                if (mViewPager.getAdapter() != null)
                    mViewPager.setAdapter(null);
                mViewPager.setAdapter(mSectionsPagerAdapter);

                // set this to prevent pager strip from being gone on some devices
                PagerTabStrip tabStrip = requireActivity().findViewById(R.id.tabStrip);
                ((ViewPager.LayoutParams) tabStrip.getLayoutParams()).isDecor = true;

                mSectionsPagerAdapter.notifyChangeInPosition(1);
                mSectionsPagerAdapter.notifyDataSetChanged();
                mViewPager.setCurrentItem(currentPage, true);
            } finally {
                rebuildingPager = false;
            }
            long now = System.currentTimeMillis();
            if (target == NO_TARGET)
                ViewedPeriod.paged(periodStartAt(currentPage), periodEndAt(currentPage), now);
            else
                ViewedPeriod.shown(periodStartAt(currentPage), periodEndAt(currentPage), target, now);
            updateSpinner();
        } else {
            if (BuildConfig.DEBUG) Log.i("LogTabsFragment", "update frag error save");
        }
    }

    /**
     * The time to page to: a just-saved record's, else the records views' focus
     * ({@link ViewedPeriod}); or {@link #NO_TARGET} if there is neither, and this screen keeps the
     * page it was on -- today's, or the one saved over a process restart.
     */
    private long chooseTarget() {
        Bundle args = getArguments();
        if (args != null && args.containsKey(ARG_SHOW_TIME)) {
            long time = args.getLong(ARG_SHOW_TIME);
            args.remove(ARG_SHOW_TIME);
            return time;
        }
        return ViewedPeriod.isSet() ? ViewedPeriod.focusTime(System.currentTimeMillis()) : NO_TARGET;
    }

    private long periodStartAt(int position) {
        return getPeriodStart(recordsTimeMode, position - MID_PAGE, requireActivity(),
                prefManager.getPrefsFile()).getTimeInMillis();
    }

    private long periodEndAt(int position) {
        return getPeriodEnd(recordsTimeMode, position - MID_PAGE, requireActivity(),
                prefManager.getPrefsFile()).getTimeInMillis();
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    private void updateSpinner() {
        if (BuildConfig.DEBUG) Log.i("CALT", "updateSpinner");
        switch (recordsViewMode) {
            case KEY_VIEW_MODE_SUMMARY:
                navSpinner.setVisibility(View.VISIBLE);
                navigator.setToolbarTitle("");
                break;
            case KEY_VIEW_MODE_LIST:
                navSpinner.setVisibility(View.VISIBLE);
                navigator.setToolbarTitle("");
                break;
            case KEY_VIEW_MODE_GRAPH:
                navigator.setToolbarTitle("");
                navSpinner.setVisibility(View.VISIBLE);
                break;
            case KEY_VIEW_MODE_CALENDAR:
                navSpinner.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }

    void updateFilter(ArrayList<ArrayList<Integer>> newFilter) {
        // set the filter on the global level
        MainActivity.mFilterArray = newFilter;
        // convert filter array into a database selections statement including all possible headache ids
        if (MainActivity.mFilterArray != null)
            mFilterString = covertFilterArrayToString(newFilter);
        else
            mFilterString = "";

        if (BuildConfig.DEBUG) Log.i("FTEST", "updated filter: " + mFilterString);

        updateRecordsFragment();
        requireActivity().invalidateOptionsMenu();
    }

    private void setFilter(ArrayList<ArrayList<Integer>> newFilter) {
        // set the filter on the global level
        MainActivity.mFilterArray = newFilter;
        // convert filter array into a database selections statement including all possible headache ids
        if (MainActivity.mFilterArray != null)
            mFilterString = covertFilterArrayToString(newFilter);
        else
            mFilterString = "";

    }

    /**
     * Convert the arraylist of arraylists holding the selected tags into a selection string
     *
     * @param newFilter arraylist of arraylists
     * @return selection string
     */
    private String covertFilterArrayToString(ArrayList<ArrayList<Integer>> newFilter) {
        String filterString = "";

        // check if all/expense/income
        if (newFilter.get(0).size() > 0)
            filterString = DBAdapter.KEY_EXPENSE_INCOME + " = " + newFilter.get(0).get(0);

        // check if account limits set
        if (newFilter.get(1).size() > 0) {
            if (!filterString.equals(""))
                filterString += " AND ";

            filterString += "(";
            ArrayList<Integer> accountsArray = newFilter.get(1);
            for (int i = 0; i < accountsArray.size(); i++) {
                if (BuildConfig.DEBUG) Log.i("FTEST", "filterstring: " + filterString);
                // add OR if not the first entry
                if (i != 0)
                    filterString += " OR ";
                filterString += DBAdapter.KEY_ACCOUNT_ID + " = " + accountsArray.get(i);
            }
            filterString += ")";
        }

        // check is category limits are set
        if (newFilter.get(2).size() > 0) {
            if (!filterString.equals(""))
                filterString += " AND ";

            filterString += "(";
            ArrayList<Integer> categoriesArray = newFilter.get(2);
            for (int i = 0; i < categoriesArray.size(); i++) {
                if (BuildConfig.DEBUG) Log.i("FTEST", "filterstring: " + filterString);
                // add OR if not the first entry
                if (i != 0)
                    filterString += " OR ";
                filterString += DBAdapter.KEY_CATEGORY_ID + " = " + categoriesArray.get(i);
            }
            filterString += ")";
        }
        if (!filterString.equals(""))
            filterString += " AND ";

        // return string
        return filterString;
    }

    private class SectionsPagerAdapter extends FragmentStatePagerAdapter {

        SectionsPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        private long baseId = 0;

        @SuppressWarnings("DuplicateBranchesInSwitch")
        @NonNull
        @Override
        public Fragment getItem(int position) {
            if (BuildConfig.DEBUG) Log.i("FTEST", "sent filter: " + mFilterString);
            Fragment currentFrag;

            updateSpinner();
            switch (recordsViewMode) {
                case KEY_VIEW_MODE_SUMMARY:
                    currentFrag = SummaryFragment.newInstance(recordsTimeMode, 2, position - MID_PAGE, mFilterString);
                    break;
                case KEY_VIEW_MODE_LIST:
                    currentFrag = LogListFragment.newInstance(recordsTimeMode, 2, position - MID_PAGE, mFilterString);
                    break;
                case KEY_VIEW_MODE_GRAPH:
                    currentFrag = new LogGraphFragment().newInstance(recordsTimeMode, 2, position - MID_PAGE, mFilterString);
                    break;
                case KEY_VIEW_MODE_CALENDAR:
                    currentFrag = LogsCalendarFragment.newInstance(
                            recordsTimeMode, 2, position - MID_PAGE, mFilterString);
                    break;
                default:
                    currentFrag = LogListFragment.newInstance(recordsTimeMode, 2, position - MID_PAGE, mFilterString);
                    break;
            }

            return currentFrag;
        }

        @Override
        public int getItemPosition(@NonNull Object object) {
            return FragmentPagerAdapter.POSITION_NONE;
        }

        void notifyChangeInPosition(int n) {
            // shift the ID returned by getItemId outside the range of all previous fragments
            baseId += NUM_OF_PAGES + n;
        }

        @Override
        public int getCount() {
            if (recordsTimeMode == LogTabsFragment.KEY_RECORDS_MODE_ALL) {
                // if all logs are chosen, return only 1 page to show
                return 1;
            }
            return NUM_OF_PAGES;
        }

        @Override
        public CharSequence getPageTitle(int position) {
            if (BuildConfig.DEBUG) Log.i("CALT", "getPageTitle position: " + position);
            Calendar periodStart = LogTabsFragment.getPeriodStart(recordsTimeMode, position - MID_PAGE,
                    requireActivity(), prefManager.getPrefsFile());
            Calendar periodEnd = LogTabsFragment.getPeriodEnd(recordsTimeMode, position - MID_PAGE,
                    requireActivity(), prefManager.getPrefsFile());

            switch (recordsTimeMode) {
                case LogTabsFragment.KEY_RECORDS_MODE_ALL:
                    return requireActivity().getResources().getString(R.string.all_records);
                case LogTabsFragment.KEY_RECORDS_MODE_YEAR:
                    return periodStart.get(Calendar.YEAR) + " " + requireActivity().getResources().getString(R.string.records);
                case LogTabsFragment.KEY_RECORDS_MODE_MONTH:
                    DateFormat dateTextMonthYear = new SimpleDateFormat("MMM yyyy", AppLocale.TEXT);
                    return dateTextMonthYear.format(periodStart.getTime());
                case LogTabsFragment.KEY_RECORDS_MODE_WEEK:
                    DateFormat dateText = new SimpleDateFormat("EEE, MMM dd", AppLocale.TEXT);
                    String formattedStart = (dateText.format(periodStart.getTime()));
                    String formattedEnd = (dateText.format(periodEnd.getTime()));
                    return formattedStart + " - " + formattedEnd;
                case LogTabsFragment.KEY_RECORDS_MODE_DAY:
                    DateFormat dateTextDay = new SimpleDateFormat("EEE, MMM dd", AppLocale.TEXT);
                    return (dateTextDay.format(periodStart.getTime()));
            }
            return requireActivity().getResources().getString(R.string.page) + ": " + (position - MID_PAGE);
        }
    }

    /** The First day of week setting, as a {@code Calendar.DAY_OF_WEEK}. */
    static int firstDayOfWeek(Context ctx, SharedPreferences prefs) {
        // The stored value is 0 for Saturday, then 1..6 for Sunday..Friday, which are
        // Calendar's own DAY_OF_WEEK numbers.
        int firstDayOfWeekOffset = Integer.parseInt(prefs.getString(ctx.getString(R.string.pref_key_first_day_of_week),
                ctx.getString(R.string.default_first_day_of_week)));
        return firstDayOfWeekOffset == 0 ? Calendar.SATURDAY : firstDayOfWeekOffset;
    }

    static Calendar getPeriodStart(int recordsMode, int indexOffset, Context ctx, SharedPreferences prefs) {
        return getPeriodStart(recordsMode, indexOffset, ctx, prefs, Calendar.getInstance());
    }

    /** As above, with "now" supplied, so a test can pin today's date. */
    static Calendar getPeriodStart(int recordsMode, int indexOffset, Context ctx, SharedPreferences prefs,
                                   Calendar now) {
        Calendar tempCal = (Calendar) now.clone();
        tempCal.set(Calendar.HOUR_OF_DAY, tempCal.getMinimum(Calendar.HOUR_OF_DAY));
        tempCal.set(Calendar.MINUTE, tempCal.getMinimum(Calendar.MINUTE));
        tempCal.set(Calendar.SECOND, tempCal.getMinimum(Calendar.SECOND));
        // Without this the period starts at whatever millisecond the clock was on, so a record
        // saved in the first second of the period -- 00:00:00.216, say -- is in or out by chance.
        tempCal.set(Calendar.MILLISECOND, tempCal.getMinimum(Calendar.MILLISECOND));
        switch (recordsMode) {
            case KEY_RECORDS_MODE_ALL:
                tempCal.setTimeInMillis(0);
                break;
            case KEY_RECORDS_MODE_YEAR:
                // First day first, so no intermediate date (29 Feb in a common year) is normalised.
                tempCal.set(Calendar.DAY_OF_YEAR, tempCal.getMinimum(Calendar.DAY_OF_YEAR));
                tempCal.set(Calendar.YEAR, tempCal.get(Calendar.YEAR) + indexOffset);
                break;
            case KEY_RECORDS_MODE_MONTH:
                // First day first, so no intermediate date (31 Feb) is normalised into March.
                tempCal.set(Calendar.DAY_OF_MONTH, tempCal.getMinimum(Calendar.DAY_OF_MONTH));
                tempCal.set(Calendar.MONTH, tempCal.get(Calendar.MONTH) + indexOffset);
                break;
            case KEY_RECORDS_MODE_WEEK:
                int firstDayOfWeek = firstDayOfWeek(ctx, prefs);

                // Back to the most recent first-day-of-week on or before today, then whole weeks.
                // This replaces an estimate and two corrections, one of which compared with a
                // strict "<" and so put 00:00:00.000 on the first day of a week into the previous
                // one. add(DATE) rolls over year ends and keeps midnight across DST.
                int daysBack = (tempCal.get(Calendar.DAY_OF_WEEK) - firstDayOfWeek + 7) % 7;
                tempCal.add(Calendar.DATE, -daysBack + indexOffset * 7);
                break;
            case KEY_RECORDS_MODE_DAY:
                tempCal.set(Calendar.DAY_OF_YEAR, tempCal.get(Calendar.DAY_OF_YEAR) + indexOffset);
                break;
        }
        Utility.log("CAL", "start 3: " + tempCal.getTime());
        return tempCal;
    }

    /**
     * The page offset whose period holds {@code timeMillis}, clamped to the pager's window. See
     * {@link #offsetHolding}.
     */
    static int pageOffsetFor(int recordsMode, long timeMillis, Context ctx, SharedPreferences prefs,
                             Calendar now) {
        int offset = offsetHolding(recordsMode, timeMillis, ctx, prefs, now);
        return Math.max(-MID_PAGE, Math.min(NUM_OF_PAGES - 1 - MID_PAGE, offset));
    }

    /**
     * The offset whose period holds {@code timeMillis}, unclamped -- for the records pager, through
     * {@link #pageOffsetFor}, and for the export dialog's period, one algorithm for both. Found
     * with {@link #getPeriodStart}/{@link #getPeriodEnd} themselves, so it cannot disagree with
     * what a page shows: an estimate by calendar arithmetic, then corrected a step at a time
     * across a daylight-saving change or a week boundary.
     */
    static int offsetHolding(int recordsMode, long timeMillis, Context ctx, SharedPreferences prefs,
                             Calendar now) {
        if (recordsMode == KEY_RECORDS_MODE_ALL)
            return 0;
        Calendar then = (Calendar) now.clone();
        then.setTimeInMillis(timeMillis);
        int years = then.get(Calendar.YEAR) - now.get(Calendar.YEAR);
        long dayMillis = 24L * 60 * 60 * 1000;
        int offset;
        switch (recordsMode) {
            case KEY_RECORDS_MODE_YEAR:
                offset = years;
                break;
            case KEY_RECORDS_MODE_MONTH:
                offset = years * 12 + then.get(Calendar.MONTH) - now.get(Calendar.MONTH);
                break;
            default:
                long thisStart = getPeriodStart(recordsMode, 0, ctx, prefs, now).getTimeInMillis();
                long periodMillis = recordsMode == KEY_RECORDS_MODE_WEEK ? 7 * dayMillis : dayMillis;
                offset = (int) Math.floorDiv(timeMillis - thisStart, periodMillis);
                break;
        }
        while (timeMillis < getPeriodStart(recordsMode, offset, ctx, prefs, now).getTimeInMillis())
            offset--;
        while (timeMillis > getPeriodEnd(recordsMode, offset, ctx, prefs, now).getTimeInMillis())
            offset++;
        return offset;
    }

    static Calendar getPeriodEnd(int recordsMode, int indexOffset, Context ctx, SharedPreferences prefs) {
        return getPeriodEnd(recordsMode, indexOffset, ctx, prefs, Calendar.getInstance());
    }

    /**
     * As above, with "now" supplied, so a test can pin today's date.
     *
     * <p><b>A period ends one millisecond before the next one starts</b>, by definition rather
     * than by arithmetic of its own. It used to be computed separately, and wrongly in two ways:
     * the month branch set the month and then asked for its last day while today's day number was
     * still in the calendar, so on the 29th-31st February ran to 3 March; and the week branch's
     * own offset correction disagreed with {@link #getPeriodStart}'s, so with Monday or Saturday
     * as the first day of week, weeks overlapped on some days. Deriving the end from the start is
     * what makes neighbouring pages tile whatever day it is. {@code PinnedPeriodBoundsTest}.
     */
    static Calendar getPeriodEnd(int recordsMode, int indexOffset, Context ctx, SharedPreferences prefs,
                                 Calendar now) {
        Calendar end = getPeriodStart(recordsMode, indexOffset + 1, ctx, prefs, now);
        if (recordsMode == KEY_RECORDS_MODE_ALL) {
            end.setTimeInMillis(9223372017126000L);
        } else {
            end.setTimeInMillis(end.getTimeInMillis() - 1);
        }
        Utility.log("CAL", "end: " + end.getTime());
        return end;
    }

    @Override
    public void onPause() {
        if (BuildConfig.DEBUG) Log.i("LogTabsFragment", "nav gone PAUSE");
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (BuildConfig.DEBUG) Log.i("LogTabsFragment", "nav gone STOP");
        requireActivity().findViewById(R.id.spinner_nav).setVisibility(View.GONE);
        ((FloatingActionButton) requireActivity().findViewById(R.id.fab_export)).hide();
    }

    /**
     * The toolbar's Filter, and Sort or Grouping depending on the view.
     *
     * <p>A {@link MenuProvider} on the view lifecycle rather than {@code setHasOptionsMenu}: the
     * items belong to this screen, and the fragment outlives its view on the back stack.
     */
    private void addMenu() {
        requireActivity().addMenuProvider(menuProvider, getViewLifecycleOwner(),
                Lifecycle.State.RESUMED);
    }

    private final MenuProvider menuProvider = new MenuProvider() {
    @Override
    public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {

        // if a filter is present show highlighted filter button, otherwise show normal
        setupFilterIcon(menu);

        // if on list view add sorting options menu
        if (recordsViewMode == KEY_VIEW_MODE_LIST) {
            try {
                IconDrawable tempIcon = new IconDrawable(requireActivity(), MaterialIcons.md_sort)
                        .actionBarSize().colorRes(R.color.actionBarWhite);
                menu.add(OPTIONS_MENU_GROUP_ID, 114, 2, R.string.sort).setIcon(tempIcon)
                        .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // if on graph view and not on Day View, add graph grouping options button, in the toolbar
        // slot Sort takes on list view (graph has no sort)
        if (recordsViewMode == KEY_VIEW_MODE_GRAPH && recordsTimeMode != KEY_RECORDS_MODE_DAY) {
            try {
                IconDrawable tempIcon = new IconDrawable(requireActivity(), MaterialIcons.md_insert_chart)
                        .actionBarSize().colorRes(R.color.actionBarWhite);
                menu.add(OPTIONS_MENU_GROUP_ID, 118, 2, R.string.grouping).setIcon(tempIcon)
                        .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

    }

    private void setupFilterIcon(Menu menu) {
        if (isAdded()) {
            if (MainActivity.mFilterArray == null) {
                IconDrawable tempIcon = new IconDrawable(requireActivity(), FontAwesomeIcons.fa_filter)
                        .actionBarSize().colorRes(R.color.actionBarWhite);
                menu.add(OPTIONS_MENU_GROUP_ID, 113, 5, R.string.filter).setIcon(tempIcon)
                        .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS);
            } else {
                LayoutInflater inflaterT = (LayoutInflater) requireActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                ImageView iv = (ImageView) inflaterT.inflate(R.layout.imageview_filter_animation, null);

                IconDrawable tempIcon = new IconDrawable(requireActivity(), FontAwesomeIcons.fa_filter)
                        .actionBarSize().colorRes(R.color.colorAccent);
                Drawable d = tempIcon.mutate();
                iv.setImageDrawable(d);

                // start attention animation
                tada(iv, 1).start();

                menu.add(OPTIONS_MENU_GROUP_ID, 113, 1, R.string.filter).setChecked(true)
                        .setShowAsActionFlags(MenuItem.SHOW_AS_ACTION_ALWAYS).setActionView(iv);

                final MenuItem filterMenuItem = menu.findItem(113);
                filterMenuItem.getActionView().setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (BuildConfig.DEBUG) Log.i("LogTabsFragment", "options click");
                        requireActivity().onMenuItemSelected(0, filterMenuItem);
                    }
                });
            }
        }
    }

    // handle actionbar clicks
    @Override
    public boolean onMenuItemSelected(@NonNull MenuItem item) {
        if (BuildConfig.DEBUG) Log.i("LogTabsFragment", "options click");
        int id = item.getItemId();
        if (id == 113) {
            FilterDialogFragment newFilterFrag = FilterDialogFragment.newInstance(MainActivity.mFilterArray);
            newFilterFrag.show(requireFragmentManager(), "");
        } else if (id == 114) {
            SortingDialogFragment newSortingDialog = SortingDialogFragment.newInstance(prefManager.getListSortSetting());
            newSortingDialog.show(requireFragmentManager(), "");
        } else if (id == 118) {
            GroupingDialogFragment newGroupingDialog = GroupingDialogFragment.newInstance(prefManager.getGraphGroupingPreference(), recordsTimeMode);
            newGroupingDialog.show(requireFragmentManager(), "");
        }

        return false;
    }
    };

    @SuppressWarnings("SameParameterValue")
    private static ObjectAnimator tada(View view, float shakeFactor) {
        float startOffset = .05f;
        float step1 = 0.01f + startOffset;
        float step2 = 0.02f + startOffset;
        float step3 = 0.03f + startOffset;
        float step4 = 0.04f + startOffset;
        float step5 = 0.05f + startOffset;
        float step6 = 0.06f + startOffset;
        float step7 = 0.07f + startOffset;

        PropertyValuesHolder pvhScaleX = PropertyValuesHolder.ofKeyframe(View.SCALE_X,
                Keyframe.ofFloat(0f, 1f),
                Keyframe.ofFloat(startOffset, 1f),
                Keyframe.ofFloat(step1, .9f),
                Keyframe.ofFloat(step2, 1.1f),
                Keyframe.ofFloat(step3, .95f),
                Keyframe.ofFloat(step4, 1.05f),
                Keyframe.ofFloat(step5, .98f),
                Keyframe.ofFloat(step6, 1.03f),
                Keyframe.ofFloat(step7, 1f),
                Keyframe.ofFloat(1f, 1f)
        );

        PropertyValuesHolder pvhScaleY = PropertyValuesHolder.ofKeyframe(View.SCALE_Y,
                Keyframe.ofFloat(0f, 1f),
                Keyframe.ofFloat(startOffset, 1f),
                Keyframe.ofFloat(step1, .9f),
                Keyframe.ofFloat(step2, 1.1f),
                Keyframe.ofFloat(step3, .95f),
                Keyframe.ofFloat(step4, 1.05f),
                Keyframe.ofFloat(step5, .98f),
                Keyframe.ofFloat(step6, 1.03f),
                Keyframe.ofFloat(step7, 1f),
                Keyframe.ofFloat(1f, 1f)
        );

        PropertyValuesHolder pvhRotate = PropertyValuesHolder.ofKeyframe(View.ROTATION,
                Keyframe.ofFloat(0f, 0f),
                Keyframe.ofFloat(step1, -1f * shakeFactor),
                Keyframe.ofFloat(step2, 1f * shakeFactor),
                Keyframe.ofFloat(step3, -1f * shakeFactor),
                Keyframe.ofFloat(step4, 1f * shakeFactor),
                Keyframe.ofFloat(step5, -1f * shakeFactor),
                Keyframe.ofFloat(step6, 1f * shakeFactor),
                Keyframe.ofFloat(step7, 0f),
                Keyframe.ofFloat(1f, 0f)
        );
        ObjectAnimator obb = ObjectAnimator.ofPropertyValuesHolder(view, pvhScaleX, pvhScaleY, pvhRotate).
                setDuration(7000);
        obb.setRepeatMode(ValueAnimator.RESTART);
        obb.setRepeatCount(4);
        return obb;
    }

    public static ObjectAnimator nope(View view) {
        int delta = 11;

        PropertyValuesHolder pvhTranslateX = PropertyValuesHolder.ofKeyframe(View.TRANSLATION_X,
                Keyframe.ofFloat(0f, 0),
                Keyframe.ofFloat(.10f, -delta),
                Keyframe.ofFloat(.26f, delta),
                Keyframe.ofFloat(.42f, -delta),
                Keyframe.ofFloat(.58f, delta),
                Keyframe.ofFloat(.74f, -delta),
                Keyframe.ofFloat(.90f, delta),
                Keyframe.ofFloat(1f, 0f)
        );

        return ObjectAnimator.ofPropertyValuesHolder(view, pvhTranslateX).
                setDuration(500);
    }

}