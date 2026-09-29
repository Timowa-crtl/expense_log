package de.timowa.expenselog;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import com.androidplot.ui.SeriesRenderer;
import com.androidplot.util.PixelUtils;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.LineAndPointRenderer;
import com.androidplot.xy.PointLabelFormatter;
import com.androidplot.xy.PointLabeler;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.XYPlot;
import com.androidplot.xy.XYSeries;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.DecimalFormat;
import java.text.FieldPosition;
import java.text.Format;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;

import static androidx.core.content.ContextCompat.getColor;

public class LogGraphFragment extends BaseFragment {
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_RECORDS_MODE = "recordsMode";
    private static final String ARG_NAV_SECTION = "navSection";
    private static final String ARG_PAGE_INDEX = "pageIndex";
    private static final String ARG_FILTER = "filterString";

    private final int GROUPING_MODE_NONE = 0;
    private final int GROUPING_MODE_DAY = 1;
    private final int GROUPING_MODE_WEEK = 2;
    private final int GROUPING_MODE_MONTH = 3;
    private final int GROUPING_MODE_YEAR = 4;

    private int recordsTimeMode;
    private int nav_section;
    private int page_index;
    private String records_filter;

    private OnFragmentInteractionListener mListener;

    private XYPlot plot;
    private ArrayList<Long> dateList;
    private ArrayList<Double> rangeList;
    private double highestRangeValue;
    private double lowestRangeValue;
    private boolean expensesOnly;

    @Inject
    Navigator navigator;

    @Inject
    DBAdapter dbAdapter;

    @Inject
    Utility utility;

    @Inject
    PrefManager prefManager;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param recordsMode Parameter 0.
     * @param navMode     Parameter 1.
     * @param pageIndex   Parameter 2.
     * @return A new instance of fragment RecordsGraphFragment.
     */
    public LogGraphFragment newInstance(int recordsMode, int navMode, int pageIndex, String filter) {
        LogGraphFragment fragment = new LogGraphFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_RECORDS_MODE, recordsMode);
        args.putInt(ARG_NAV_SECTION, navMode);
        args.putInt(ARG_PAGE_INDEX, pageIndex);
        args.putString(ARG_FILTER, filter);
        fragment.setArguments(args);
        return fragment;
    }

    public LogGraphFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            recordsTimeMode = getArguments().getInt(ARG_RECORDS_MODE);
            nav_section = getArguments().getInt(ARG_NAV_SECTION);
            page_index = getArguments().getInt(ARG_PAGE_INDEX);
            records_filter = getArguments().getString(ARG_FILTER);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_graph, container, false);

        this.getComponent(ActivityComponent.class).inject(this);

        setupFab();

        // initialize our XYPlot reference:
        plot = view.findViewById(R.id.mySimpleXYPlot);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        initGraph(getActivity());
    }

    private void initGraph(Context ctx) {
        if (records_filter == null)
            records_filter = "";

        dateList = new ArrayList<>();
        rangeList = new ArrayList<>();
        highestRangeValue = 0;
        lowestRangeValue = 0;

        // if only expenses in range, let all values be positive
        expensesOnly = true;

        populatePlotPoints();

        int upperRangeLimit = getHighestRangeCategory();
        int lowerRangeLimit = getLowestRangeCategory();

        // switch all values in range to positive and switch range limits if only expenses in list
        if (expensesOnly) {
            for (int i = 0; i < rangeList.size(); i++) {
                rangeList.set(i, rangeList.get(i) * -1);
            }
            upperRangeLimit = lowerRangeLimit * -1;
            lowerRangeLimit = 0;
        }

        // setup the range of the y scale and the steps to be used
        plot.setRangeBoundaries(lowerRangeLimit, upperRangeLimit, BoundaryMode.FIXED);
        plot.setRangeStep(StepMode.SUBDIVIDE, 11);

        XYSeries series2 = new SimpleXYSeries(dateList, rangeList, "");

        plot.getGraph().getGridBackgroundPaint().setColor(getColor(ctx, R.color.graph_over_line));

        plot.getGraph().getBackgroundPaint().setColor(getColor(ctx, R.color.graph_background));
        plot.getBackgroundPaint().setColor(getColor(ctx, R.color.graph_background));
        plot.getGraph().getGridBackgroundPaint().setColor(getColor(ctx, R.color.graph_background));

        plot.getGraph().getDomainGridLinePaint().setColor(getColor(ctx, R.color.colorPrimaryLight));
        plot.getGraph().getDomainGridLinePaint().setPathEffect(new DashPathEffect(new float[]{1, 1}, 1));
        plot.getGraph().getRangeGridLinePaint().setColor(getColor(ctx, R.color.colorPrimaryLight));
        plot.getGraph().getRangeGridLinePaint().setPathEffect(new DashPathEffect(new float[]{1, 1}, 1));
        plot.getGraph().getDomainOriginLinePaint().setColor(Color.BLACK);
        plot.getGraph().getRangeOriginLinePaint().setColor(Color.BLACK);
        plot.getGraph().getDomainOriginLinePaint().setStrokeWidth(PixelUtils.dpToPix(0.5f));
        plot.getGraph().getRangeOriginLinePaint().setStrokeWidth(PixelUtils.dpToPix(0.5f));

        // set color for segment labels
        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.LEFT).getPaint().setColor(getColor(ctx, R.color.graph_text));
        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).getPaint().setColor(getColor(ctx, R.color.graph_text));

        // set colors for actual labels
        plot.getRangeTitle().getLabelPaint().setColor(Color.BLACK);

        // setup our line fill paint to be a slightly transparent gradient:
        Paint lineFill = new Paint();
        lineFill.setAlpha(100);

        plot.addSeries(series2, new CustomLineAndPointFormatter(ctx));

        // draw a domain tick for each year:
        plot.setDomainStep(StepMode.SUBDIVIDE, getNumOfDivisions());

        // customize our domain/range labels
        plot.setRangeLabel(ctx.getResources().getString(R.string.amount));

        // add padding to left side to avoid overlap between range label and range values

        // get number of digits of largest number in range and use it to estimate padding required
        long largest = (long) (Math.abs(getHighestRangeCategory()) > Math.abs(getLowestRangeCategory()) ? getHighestRangeCategory() : getLowestRangeCategory());
        String largestString = String.valueOf(Math.abs(largest));
        float rangeLabelPadding = largestString.length() * 9;
        plot.getGraph().setPaddingLeft(rangeLabelPadding);

        if (recordsTimeMode != LogTabsFragment.KEY_RECORDS_MODE_ALL) {
            plot.setDomainBoundaries(LogTabsFragment.getPeriodStart(recordsTimeMode, page_index,
                    ctx, prefManager.getPrefsFile()).getTimeInMillis() / 1000,
                    LogTabsFragment.getPeriodEnd(recordsTimeMode, page_index,
                            ctx, prefManager.getPrefsFile()).getTimeInMillis() / 1000, BoundaryMode.FIXED);
        } else {
            // if records mode all and only one record, set domain to be just before and after the single record
            if (dateList.size() == 1) {
                long domainStart = dateList.get(0) - 31536000;
                long domainEnd = dateList.get(0) + 31536000;
                plot.setDomainBoundaries(domainStart, domainEnd, BoundaryMode.FIXED);
            }
        }

        // get rid of decimal points in our range labels:
        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.LEFT).setFormat(new DecimalFormat("0"));

        plot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).setFormat(new Format() {

            @Override
            public StringBuffer format(Object obj, StringBuffer toAppendTo, FieldPosition pos) {

                SimpleDateFormat dateFormat = getDateFormat();

                // because our timestamps are in seconds and SimpleDateFormat expects milliseconds
                // we multiply our timestamp by 1000:
                long timestamp = ((Number) obj).longValue() * 1000;
                Calendar calendarDate = Calendar.getInstance();
                calendarDate.setTimeInMillis(timestamp);

                // round to nearest hour and set minutes to 0 if showing hours on graph
                if (recordsTimeMode == LogTabsFragment.KEY_RECORDS_MODE_DAY) {

                    if (calendarDate.get(Calendar.MINUTE) > 30)
                        calendarDate.set(Calendar.HOUR_OF_DAY, calendarDate.get(Calendar.HOUR_OF_DAY) + 1);

                    calendarDate.set(Calendar.MINUTE, 0);
                }

                return dateFormat.format(calendarDate.getTime(), toAppendTo, pos);
            }

            @Override
            public Object parseObject(String source, ParsePosition pos) {
                return null;

            }
        });

        // by default, AndroidPlot displays developer guides to aid in laying out your plot.
        // To get rid of them call disableAllMarkup():
        //plot.disableAllMarkup();
    }

    private void populatePlotPoints() {
        long periodStart = LogTabsFragment.getPeriodStart(recordsTimeMode, page_index, getActivity(),
                prefManager.getPrefsFile()).getTimeInMillis();

        long periodEnd = LogTabsFragment.getPeriodEnd(recordsTimeMode, page_index, getActivity(),
                prefManager.getPrefsFile()).getTimeInMillis();

        // populate plot points by actual time, or group by day, week, month, or year based on user pref
        // limit selection to only give options tat are available for this view eg, don't show grouping by year option on day view
        int groupingPreference = prefManager.getGraphGroupingPreference();

        switch (recordsTimeMode) {
            case (LogTabsFragment.KEY_RECORDS_MODE_DAY):
                // records by day, remove grouping preference
                prefManager.setGraphGroupingPreference(GROUPING_MODE_NONE);
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_WEEK): // records by week
                if (groupingPreference == GROUPING_MODE_YEAR ||
                        groupingPreference == GROUPING_MODE_MONTH ||
                        groupingPreference == GROUPING_MODE_WEEK) {
                    prefManager.setGraphGroupingPreference(GROUPING_MODE_NONE);
                }
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_MONTH): // records by month
                if (groupingPreference == GROUPING_MODE_YEAR ||
                        groupingPreference == GROUPING_MODE_MONTH) {
                    prefManager.setGraphGroupingPreference(GROUPING_MODE_NONE);
                }
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_YEAR): // records by year
                if (groupingPreference == GROUPING_MODE_YEAR)
                    prefManager.setGraphGroupingPreference(GROUPING_MODE_NONE);
                break;
        }


        switch (prefManager.getGraphGroupingPreference()) {
            case GROUPING_MODE_NONE:
                populateByActualTime(periodStart, periodEnd);
                break;
            case GROUPING_MODE_DAY:
                populateGroupingByDay(periodStart, periodEnd);
                break;
            case GROUPING_MODE_WEEK:
                populateGroupingByWeek(periodStart, periodEnd);
                break;
            case GROUPING_MODE_MONTH:
                populateGroupingByMonth(periodStart, periodEnd);
                break;
            case GROUPING_MODE_YEAR:
                populateGroupingByYear(periodStart, periodEnd);
                break;
        }
    }

    private void populateGroupingByDay(long periodStart, long periodEnd) {

        // get amount for each day, add to list, check if it's positive or negative, check if it's highest or lowest

        Cursor logsCursor = dbAdapter.getLogsInRange(periodStart, periodEnd, DBAdapter.KEY_LOG_TIME, records_filter);

        if (logsCursor != null) {

            int currentDay = -1;
            double currentDayTotal = 0;
            int dayYearCode = 0;
            double amount;
            Calendar currentDayCalendar = Calendar.getInstance();
            Calendar previousDayCalendar = Calendar.getInstance();

            if (logsCursor.getCount() > 0) {
                while (logsCursor.moveToNext()) {

                    // get what day the event is on and add to day total
                    currentDayCalendar.setTimeInMillis(logsCursor.getLong(DBAdapter.COLUMN_LOG_TIME));

                    dayYearCode = currentDayCalendar.get(Calendar.DAY_OF_YEAR) + currentDayCalendar.get(Calendar.YEAR);

                    amount = logsCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT);

                    // make negative if expense or set flag that positive values exist
                    if (logsCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME) == 0) {
                        amount = amount * -1;
                    } else {
                        expensesOnly = false;
                    }

                    // if starting a new day:
                    // - add the previous day's total and day to the global plot point list
                    // - check for highest and lowest values and set if the current value is either
                    // - save the current day of year + year as the current day code (to prevent days from different years from combining)
                    // - clear running total for day
                    if (currentDay != dayYearCode) {

                        // don't start adding and checking numbers on the first pass
                        if (currentDay != -1) {
                            rangeList.add(currentDayTotal);

                            // get the previous day's date, set to noon
                            previousDayCalendar.set(Calendar.HOUR_OF_DAY, 20);
                            dateList.add(previousDayCalendar.getTimeInMillis() / 1000);

                            // set to lowest in range if it is
                            if (currentDayTotal < lowestRangeValue)
                                lowestRangeValue = currentDayTotal;

                            // set highest range if it is
                            if (currentDayTotal > highestRangeValue)
                                highestRangeValue = currentDayTotal;
                        }

                        // set the current day as the previous day for the following pass
                        previousDayCalendar.setTimeInMillis(currentDayCalendar.getTimeInMillis());

                        currentDay = dayYearCode;
                        currentDayTotal = amount;

                    } else {
                        // add to running total
                        currentDayTotal += amount;
                    }
                }

                // manually add the last entry
                previousDayCalendar.set(Calendar.HOUR_OF_DAY, 20);
                dateList.add(previousDayCalendar.getTimeInMillis() / 1000);
                rangeList.add(currentDayTotal);

                // set to lowest in range if it is
                if (currentDayTotal < lowestRangeValue)
                    lowestRangeValue = currentDayTotal;

                // set highest range if it is
                if (currentDayTotal > highestRangeValue)
                    highestRangeValue = currentDayTotal;


            }
            logsCursor.close();
        }
    }

    private void populateGroupingByWeek(long periodStart, long periodEnd) {

        // get amount for each day, add to list, check if it's positive or negative, check if it's highest or lowest

        Cursor logsCursor = dbAdapter.getLogsInRange(periodStart, periodEnd, DBAdapter.KEY_LOG_TIME, records_filter);

        if (logsCursor != null) {

            int currentWeek = -1;
            double currentWeekTotal = 0;
            int weekYearCode;
            double amount;
            Calendar currentDayCalendar = Calendar.getInstance();
            Calendar previousDayCalendar = Calendar.getInstance();

            if (logsCursor.getCount() > 0) {
                while (logsCursor.moveToNext()) {

                    // get what day the event is on and add to day total
                    currentDayCalendar.setTimeInMillis(logsCursor.getLong(DBAdapter.COLUMN_LOG_TIME));

                    weekYearCode = currentDayCalendar.get(Calendar.WEEK_OF_YEAR) + currentDayCalendar.get(Calendar.YEAR);

                    amount = logsCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT);

                    // make negative if expense or set flag that positive values exist
                    if (logsCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME) == 0) {
                        amount = amount * -1;
                    } else {
                        expensesOnly = false;
                    }

                    // if starting a new day:
                    // - add the previous day's total and day to the global plot point list
                    // - check for highest and lowest values and set if the current value is either
                    // - save the current day of year + year as the current day code (to prevent days from different years from combining)
                    // - clear running total for day
                    if (currentWeek != weekYearCode) {

                        // don't start adding and checking numbers on the first pass
                        if (currentWeek != -1) {
                            rangeList.add(currentWeekTotal);

                            // get the previous day's date, set to a appropriate time to show all records
//                            Utility.log("GRPHZ", "date 1: "+previousDayCalendar.getTime());
//                            int weekOfYear = previousDayCalendar.get(Calendar.WEEK_OF_YEAR);
//                            previousDayCalendar.set(Calendar.DAY_OF_WEEK, 4);
//                            Utility.log("GRPHZ", "date 2: "+previousDayCalendar.getTime());
//                            previousDayCalendar.set(Calendar.WEEK_OF_YEAR, weekOfYear);
//                            Utility.log("GRPHZ", "date 3: "+previousDayCalendar.getTime());

                            dateList.add(previousDayCalendar.getTimeInMillis() / 1000);

                            // set to lowest in range if it is
                            if (currentWeekTotal < lowestRangeValue)
                                lowestRangeValue = currentWeekTotal;

                            // set highest range if it is
                            if (currentWeekTotal > highestRangeValue)
                                highestRangeValue = currentWeekTotal;
                        }

                        // set the current day as the previous day for the following pass
                        previousDayCalendar.setTimeInMillis(currentDayCalendar.getTimeInMillis());

                        currentWeek = weekYearCode;
                        currentWeekTotal = amount;

                    } else {
                        // add to running total
                        currentWeekTotal += amount;
                    }
                }

                // manually add the last entry
                dateList.add(previousDayCalendar.getTimeInMillis() / 1000);
                rangeList.add(currentWeekTotal);

                // set to lowest in range if it is
                if (currentWeekTotal < lowestRangeValue)
                    lowestRangeValue = currentWeekTotal;

                // set highest range if it is
                if (currentWeekTotal > highestRangeValue)
                    highestRangeValue = currentWeekTotal;


            }
            logsCursor.close();
        }
    }

    private void populateGroupingByMonth(long periodStart, long periodEnd) {

        // get amount for each day, add to list, check if it's positive or negative, check if it's highest or lowest

        Cursor logsCursor = dbAdapter.getLogsInRange(periodStart, periodEnd, DBAdapter.KEY_LOG_TIME, records_filter);

        if (logsCursor != null) {

            int currentMonth = -1;
            double currentMonthTotal = 0;
            int monthYearCode;
            double amount;
            Calendar currentDayCalendar = Calendar.getInstance();
            Calendar previousDayCalendar = Calendar.getInstance();

            if (logsCursor.getCount() > 0) {
                while (logsCursor.moveToNext()) {

                    // get what day the event is on and add to day total
                    currentDayCalendar.setTimeInMillis(logsCursor.getLong(DBAdapter.COLUMN_LOG_TIME));

                    monthYearCode = currentDayCalendar.get(Calendar.MONTH) + currentDayCalendar.get(Calendar.YEAR);

                    amount = logsCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT);

                    // make negative if expense or set flag that positive values exist
                    if (logsCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME) == 0) {
                        amount = amount * -1;
                    } else {
                        expensesOnly = false;
                    }

                    if (currentMonth != monthYearCode) {

                        // don't start adding and checking numbers on the first pass
                        if (currentMonth != -1) {
                            rangeList.add(currentMonthTotal);

                            // get the previous day's date, set to a appropriate time to show all records
                            previousDayCalendar.set(Calendar.DAY_OF_MONTH, 15);
                            dateList.add(previousDayCalendar.getTimeInMillis() / 1000);

                            // set to lowest in range if it is
                            if (currentMonthTotal < lowestRangeValue)
                                lowestRangeValue = currentMonthTotal;

                            // set highest range if it is
                            if (currentMonthTotal > highestRangeValue)
                                highestRangeValue = currentMonthTotal;
                        }

                        // set the current day as the previous day for the following pass
                        previousDayCalendar.setTimeInMillis(currentDayCalendar.getTimeInMillis());

                        currentMonth = monthYearCode;
                        currentMonthTotal = amount;

                    } else {
                        // add to running total
                        currentMonthTotal += amount;
                    }
                }

                // manually add the last entry
                previousDayCalendar.set(Calendar.DAY_OF_MONTH, 15);
                dateList.add(previousDayCalendar.getTimeInMillis() / 1000);
                rangeList.add(currentMonthTotal);

                // set to lowest in range if it is
                if (currentMonthTotal < lowestRangeValue)
                    lowestRangeValue = currentMonthTotal;

                // set highest range if it is
                if (currentMonthTotal > highestRangeValue)
                    highestRangeValue = currentMonthTotal;


            }
            logsCursor.close();
        }
    }

    private void populateGroupingByYear(long periodStart, long periodEnd) {

        // get amount for each day, add to list, check if it's positive or negative, check if it's highest or lowest

        Cursor logsCursor = dbAdapter.getLogsInRange(periodStart, periodEnd, DBAdapter.KEY_LOG_TIME, records_filter);

        if (logsCursor != null) {

            int currentYear = -1;
            double currentYearTotal = 0;
            int yearCode;
            double amount;
            Calendar currentDayCalendar = Calendar.getInstance();
            Calendar previousDayCalendar = Calendar.getInstance();

            if (logsCursor.getCount() > 0) {
                while (logsCursor.moveToNext()) {

                    // get what day the event is on and add to day total
                    currentDayCalendar.setTimeInMillis(logsCursor.getLong(DBAdapter.COLUMN_LOG_TIME));

                    yearCode = currentDayCalendar.get(Calendar.YEAR);

                    amount = logsCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT);

                    // make negative if expense or set flag that positive values exist
                    if (logsCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME) == 0) {
                        amount = amount * -1;
                    } else {
                        expensesOnly = false;
                    }

                    if (currentYear != yearCode) {

                        // don't start adding and checking numbers on the first pass
                        if (currentYear != -1) {
                            rangeList.add(currentYearTotal);

                            // get the previous day's date, set to a appropriate time to show all records
                            previousDayCalendar.set(Calendar.MONTH, 6);
                            dateList.add(previousDayCalendar.getTimeInMillis() / 1000);

                            // set to lowest in range if it is
                            if (currentYearTotal < lowestRangeValue)
                                lowestRangeValue = currentYearTotal;

                            // set highest range if it is
                            if (currentYearTotal > highestRangeValue)
                                highestRangeValue = currentYearTotal;
                        }

                        // set the current day as the previous day for the following pass
                        previousDayCalendar.setTimeInMillis(currentDayCalendar.getTimeInMillis());

                        currentYear = yearCode;
                        currentYearTotal = amount;

                    } else {
                        // add to running total
                        currentYearTotal += amount;
                    }
                }

                // manually add the last entry
                previousDayCalendar.set(Calendar.MONTH, 6);
                dateList.add(previousDayCalendar.getTimeInMillis() / 1000);
                rangeList.add(currentYearTotal);

                // set to lowest in range if it is
                if (currentYearTotal < lowestRangeValue)
                    lowestRangeValue = currentYearTotal;

                // set highest range if it is
                if (currentYearTotal > highestRangeValue)
                    highestRangeValue = currentYearTotal;


            }
            logsCursor.close();
        }
    }

    private void populateByActualTime(long periodStart, long periodEnd) {
        Cursor logsCursor = dbAdapter.getLogsInRange(periodStart, periodEnd, DBAdapter.KEY_LOG_TIME, records_filter);

        if (logsCursor != null) {
            while (logsCursor.moveToNext()) {
                // add to time list
                dateList.add(logsCursor.getLong(DBAdapter.COLUMN_LOG_TIME) / 1000);

                // add to x-axis list
                double amount = logsCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT);

                // make negative if expense
                if (logsCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME) == 0) {
                    amount = amount * -1;
                } else {
                    expensesOnly = false;
                }
                rangeList.add(amount);

                if (amount < lowestRangeValue)
                    lowestRangeValue = amount;

                // set highest range if it is
                if (amount > highestRangeValue)
                    highestRangeValue = amount;
            }
            logsCursor.close();
        }
    }

    private int getHighestRangeCategory() {
        // get highest num in range
        int range = 1000000000;
        if (highestRangeValue > 100000 && highestRangeValue < range) {
            range = 1000000;
        } else if (highestRangeValue > 10000) {
            range = 100000;
        } else if (highestRangeValue > 5000) {
            range = 10000;
        } else if (highestRangeValue > 1000) {
            range = 5000;
        } else if (highestRangeValue > 500) {
            range = 1000;
        } else if (highestRangeValue > 100) {
            range = 500;
        } else if (highestRangeValue > 50) {
            range = 100;
        } else if (highestRangeValue > 10) {
            range = 50;
        } else if (highestRangeValue < 10) {
            range = 10;
        }

        // return group the range fits in
        return range;
    }

    private int getLowestRangeCategory() {
        // get lowest num in range
        int range = -1000000000;
        if (lowestRangeValue < -100000 && lowestRangeValue > range) {
            range = -1000000;
        } else if (lowestRangeValue < -10000) {
            range = -100000;
        } else if (lowestRangeValue < -5000) {
            range = -10000;
        } else if (lowestRangeValue < -1000) {
            range = -5000;
        } else if (lowestRangeValue < -500) {
            range = -1000;
        } else if (lowestRangeValue < -100) {
            range = -500;
        } else if (lowestRangeValue < -50) {
            range = -100;
        } else if (lowestRangeValue < -10) {
            range = -50;
        } else if (lowestRangeValue > -10) {
            range = -10;
        }

        // return group the range fits in
        return range;
    }

    private void setupFab() {
        FloatingActionButton fab = this.requireActivity().findViewById(R.id.fab);
        fab.hide();
    }

    /**
     * A LineAndPointFormatter with the addition of paint to be used to "stroke" vertices.
     */
    class CustomLineAndPointFormatter extends LineAndPointFormatter {
        private Paint vertexCircleStrokePaint;
        private Paint linePaint;
        private Paint vertexPaint;

        /**
         * Some quick and dirty hard-coded params
         */
        CustomLineAndPointFormatter(Context ctx) {
            super(R.color.graph_vertices, R.color.graph_vertices, null, null);
            vertexCircleStrokePaint = new Paint();
            vertexCircleStrokePaint.setColor(getColor(ctx, R.color.graph_line));
            vertexCircleStrokePaint.setStrokeWidth(ctx.getResources().getDimension(R.dimen.graph_vertex_circle_stroke));
            vertexCircleStrokePaint.setStyle(Paint.Style.STROKE);
            vertexCircleStrokePaint.setAntiAlias(true);

            linePaint = new Paint();
            linePaint.setColor(getColor(ctx, R.color.graph_line));
            linePaint.setStrokeWidth(ctx.getResources().getDimension(R.dimen.graph_line_width));
            linePaint.setStyle(Paint.Style.STROKE);
            linePaint.setAntiAlias(true);
            linePaint.setStrokeCap(Paint.Cap.BUTT);

            vertexPaint = new Paint();
            vertexPaint.setColor(getColor(ctx, R.color.graph_vertices));
            vertexPaint.setStrokeWidth(ctx.getResources().getDimension(R.dimen.graph_vertex));
            vertexPaint.setAntiAlias(true);

        }

        Paint getVertexCircleStrokePaint() {
            return vertexCircleStrokePaint;
        }

        public Paint getVertexPaint() {
            return vertexPaint;
        }

        public Paint getLinePaint() {
            return linePaint;
        }

        @Override
        public Class<? extends SeriesRenderer> getRendererClass() {
            return CustomLineAndPointRenderer.class;
        }

        @Override
        public SeriesRenderer getRendererInstance(XYPlot plot) {
            return new CustomLineAndPointRenderer(plot);
        }
    }

    /**
     * A LineAndPointRenderer that can stroke vertices.
     */
    class CustomLineAndPointRenderer extends LineAndPointRenderer<CustomLineAndPointFormatter> {

        CustomLineAndPointRenderer(XYPlot plot) {
            super(plot);
        }

        /**
         * Overridden draw method to get the "vertex stroke" effect.  99% of this is copy/pasted from
         * the super class' implementation.
         *
         * @param canvas    canvas
         * @param plotArea  plot area
         * @param series    series
         * @param formatter formatter
         */
        @Override
        protected void drawSeries(Canvas canvas, RectF plotArea, XYSeries series, LineAndPointFormatter formatter) {
            PointF thisPoint;
            PointF lastPoint = null;
            PointF firstPoint = null;
            Paint linePaint = formatter.getLinePaint();

            Path path = null;
            ArrayList<PointF> points = new ArrayList<>(series.size());
            for (int i = 0; i < series.size(); i++) {
                Number y = series.getY(i);
                Number x = series.getX(i);

                if (y != null && x != null) {
                    thisPoint = getPlot().getBounds().transformScreen(x, y, plotArea);
                    points.add(thisPoint);
                } else {
                    thisPoint = null;
                }

                if (linePaint != null && thisPoint != null) {

                    // record the first point of the new Path
                    if (firstPoint == null) {
                        path = new Path();
                        firstPoint = thisPoint;
                        // create our first point at the bottom/x position so filling
                        // will look good
                        path.moveTo(firstPoint.x, firstPoint.y);
                    }

                    if (lastPoint != null) {
                        appendToPath(path, thisPoint, lastPoint);
                    }

                    lastPoint = thisPoint;
                } else {
                    if (lastPoint != null) {
                        renderPath(canvas, plotArea, path, firstPoint, lastPoint, formatter);
                    }
                    firstPoint = null;
                    lastPoint = null;
                }
            }
            if (linePaint != null && firstPoint != null) {
                renderPath(canvas, plotArea, path, firstPoint, lastPoint, formatter);
            }

            Paint vertexPaint = formatter.getVertexPaint();
            Paint strokePaint = ((CustomLineAndPointFormatter) formatter).getVertexCircleStrokePaint();
            PointLabelFormatter plf = formatter.getPointLabelFormatter();
            if (vertexPaint != null || plf != null) {
                for (PointF p : points) {
                    PointLabeler pointLabeler = formatter.getPointLabeler();

                    // if vertexPaint is available, draw vertex:
                    if (vertexPaint != null) {
                        canvas.drawPoint(p.x, p.y, vertexPaint);
                    }

                    // if stroke is available, draw stroke:
                    if (strokePaint != null) {
                        // you'll probably want to make the radius a configurable parameter
                        // instead of hard-coded like it is here.
                        canvas.drawCircle(p.x, p.y, getResources().getDimension(R.dimen.graph_vertex_diameter), strokePaint);
                    }

                }
            }
        }
    }

    private int getNumOfDivisions() {
        int num = 6;
        switch (recordsTimeMode) {
            case (LogTabsFragment.KEY_RECORDS_MODE_ALL): // all records
                num = 6;
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_YEAR): // records by year
                num = 12;
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_MONTH): // records month
                num = 10;
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_WEEK): // records week
                num = 7;
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_DAY): // records day
                num = 7;
                break;
        }

        return num;
    }

    private SimpleDateFormat getDateFormat() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy", AppLocale.TEXT);
        switch (recordsTimeMode) {
            case (LogTabsFragment.KEY_RECORDS_MODE_ALL): // all records
                dateFormat = new SimpleDateFormat("yyyy", AppLocale.TEXT);
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_YEAR): // records by year
                int currentapiVersion = Build.VERSION.SDK_INT;
                if (currentapiVersion > Build.VERSION_CODES.JELLY_BEAN) {
                    // for ICS (api 15)
                    dateFormat = new SimpleDateFormat("MMMMM", AppLocale.TEXT);
                } else {
                    // do something for phones running an SDK 15 or lower
                    dateFormat = new SimpleDateFormat("MMM", AppLocale.TEXT);
                }
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_MONTH): // records by month
                dateFormat = new SimpleDateFormat("d", AppLocale.TEXT);
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_WEEK): // records by week
                dateFormat = new SimpleDateFormat("E", AppLocale.TEXT);
                break;
            case (LogTabsFragment.KEY_RECORDS_MODE_DAY): // records by day
                dateFormat = new SimpleDateFormat("h:mm", AppLocale.TEXT);
                break;
        }
        return dateFormat;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p/>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnFragmentInteractionListener {
        void onFragmentNavigation(int passedInt);
    }

}