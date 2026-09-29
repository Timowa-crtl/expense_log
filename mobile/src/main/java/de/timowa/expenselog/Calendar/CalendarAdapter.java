package de.timowa.expenselog.Calendar;

import de.timowa.expenselog.AppLocale;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;

import de.timowa.expenselog.DBAdapter;
import de.timowa.expenselog.LogItem;
import de.timowa.expenselog.PrefManager;
import de.timowa.expenselog.R;

import static androidx.core.content.ContextCompat.getColor;

public class CalendarAdapter extends BaseAdapter {

    static int firstDayOfWeekOffset = 0;


    Context context;
    Calendar cal;
    String recordsFilter;

    ArrayList<CalendarDay> calendarDayObjectArrayList = new ArrayList<CalendarDay>();

    private final PrefManager prefManager;

    public CalendarAdapter(Context context, Calendar cal, String filter, SharedPreferences prefs) {
        this.cal = cal;
        this.context = context;
        this.recordsFilter = filter;
        cal.set(Calendar.DAY_OF_MONTH, 1);
        this.prefManager = new PrefManager(context);

        firstDayOfWeekOffset = firstDayOfWeekOffset(context, prefs);
    }

    /** An adapter over days already loaded by {@link #loadMonth}, typically on a background thread. */
    public CalendarAdapter(Context context, Calendar cal, String filter, SharedPreferences prefs,
                           List<CalendarDay> days) {
        this(context, cal, filter, prefs);
        calendarDayObjectArrayList.addAll(days);
    }

    private static int firstDayOfWeekOffset(Context context, SharedPreferences prefs) {
        return -1 + Integer.parseInt(prefs.getString(context.getString(R.string.pref_key_first_day_of_week),
                context.getString(R.string.default_first_day_of_week)));
    }

    @Override
    public int getCount() {
        // return size of day objects, which includes empty days before first,
        // and offset by 7 to account for weekday labels
        return calendarDayObjectArrayList.size() + 7;
    }

    @Override
    public Object getItem(int position) {
        return calendarDayObjectArrayList.get(position);
    }

    @Override
    public long getItemId(int position) {
        return 0;
    }

    @SuppressLint("InflateParams")
    @Override
    public View getView(final int position, View convertView, ViewGroup parent) {

        View v;

        LayoutInflater vi = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (position >= 0 && position < 7) {
            v = vi.inflate(R.layout.calendar_day_of_week, null);
            TextView dayTextView = v.findViewById(R.id.textView_day);

            final Calendar c = Calendar.getInstance();
            DateFormat dateForm = new SimpleDateFormat("E", AppLocale.TEXT);

            if (position == 0) {
                c.set(Calendar.DAY_OF_WEEK, 1 + firstDayOfWeekOffset);
            } else if (position == 1) {
                c.set(Calendar.DAY_OF_WEEK, 2 + firstDayOfWeekOffset);
            } else if (position == 2) {
                c.set(Calendar.DAY_OF_WEEK, 3 + firstDayOfWeekOffset);
            } else if (position == 3) {
                c.set(Calendar.DAY_OF_WEEK, 4 + firstDayOfWeekOffset);
            } else if (position == 4) {
                c.set(Calendar.DAY_OF_WEEK, 5 + firstDayOfWeekOffset);
            } else if (position == 5) {
                c.set(Calendar.DAY_OF_WEEK, 6 + firstDayOfWeekOffset);
            } else if (position == 6) {
                c.set(Calendar.DAY_OF_WEEK, firstDayOfWeekOffset);
            }
            dayTextView.setText(dateForm.format(c.getTime()));

        } else {
            v = vi.inflate(R.layout.calendar_day_view, parent, false);
            TextView dayTextView = v.findViewById(R.id.textView_day);

            // get day object based on position
            // minus 7 to account for the first seven weekday label days
            CalendarDay calendarDay = calendarDayObjectArrayList.get(position - 7);

            if (calendarDay.getNumOfEvents() > 0) {
                dayTextView.setId(position - 7);
                TextView numOfEventsTextView = v.findViewById(R.id.textView_numOfRecords);
                String numOfEventsString = calendarDay.getNumOfEvents() + "";
                numOfEventsTextView.setText(numOfEventsString);

                // The day's total from the records it already holds. This used to open a database
                // connection and run two SUM queries per cell, on every getView -- after
                // refreshDays had already loaded every one of those records.
                double totalForDay = calendarDay.getTotal();
                TextView amountOnDayTextView = v.findViewById(R.id.textView_amountOnDay);
                if (totalForDay < 0) {
                    amountOnDayTextView.setTextColor(getColor(context, R.color.expenseColor));
                } else {
                    amountOnDayTextView.setTextColor(getColor(context, R.color.incomeColor));
                }
                amountOnDayTextView.setText(prefManager.formatMoney(totalForDay));
            }

            // if days before beginning of month make the view gone, otherwise set the day of the month
            if (calendarDay.getDay() == 0) {
                LinearLayout dayLinearLayout = v.findViewById(R.id.day_layout);
                dayLinearLayout.setVisibility(View.GONE);
            } else {
                dayTextView.setText(String.valueOf(calendarDay.getDay()));
            }
        }

        return v;
    }

    /** Reloads this month's days from the database. One query; see {@link #loadMonth}. */
    public void refreshDays() {
        List<CalendarDay> days = loadMonth(context, cal, recordsFilter,
                androidx.preference.PreferenceManager.getDefaultSharedPreferences(context));
        calendarDayObjectArrayList.clear();
        calendarDayObjectArrayList.addAll(days);
    }

    /**
     * The days of the month starting at {@code monthStart}, with every record in it, preceded by
     * the blank cells before the first day of the week the user chose.
     *
     * <p><b>One query for the month</b>, bucketed into days here. This used to run one range query
     * per day -- 31 -- on the main thread, and then two more per day with records from inside
     * {@code getView}. Safe to call on a background thread: it touches no view and holds no
     * activity.
     */
    public static List<CalendarDay> loadMonth(Context context, Calendar monthStart, String filter,
                                              SharedPreferences prefs) {
        Calendar first = (Calendar) monthStart.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int year = first.get(Calendar.YEAR);
        int month = first.get(Calendar.MONTH);
        int daysInMonth = first.getActualMaximum(Calendar.DAY_OF_MONTH);

        // The number of blank days to add, from the user's first day of week and the weekday of
        // the first of the month (0=Sunday, 1=Monday etc.).
        int offset = firstDayOfWeekOffset(context, prefs);
        int firstWeekdayOfMonth = first.get(Calendar.DAY_OF_WEEK) - 1;
        int numOfBlankDaysToAdd = Math.abs(offset - firstWeekdayOfMonth);
        // if the first weekday of the month is before the user selected first day of week
        // subtract 7 to get the number of blank days required
        if (firstWeekdayOfMonth < offset)
            numOfBlankDaysToAdd = 7 - numOfBlankDaysToAdd;
        // if adding 7 days, add none (prevent empty week)
        if (numOfBlankDaysToAdd == 7)
            numOfBlankDaysToAdd = 0;

        List<CalendarDay> days = new ArrayList<>();
        for (int i = 0; i < numOfBlankDaysToAdd; i++)
            days.add(new CalendarDay(context, 0, 0, 0, filter));
        CalendarDay[] byDate = new CalendarDay[daysInMonth + 1];
        for (int d = 1; d <= daysInMonth; d++) {
            byDate[d] = new CalendarDay(context, d, year, month, filter);
            days.add(byDate[d]);
        }

        long start = CalendarDay.dayStart(year, month, 1);
        long end = CalendarDay.dayEnd(year, month, daysInMonth);
        Calendar when = Calendar.getInstance();
        try (Cursor c = new DBAdapter(context).getLogsInRange(start, end, DBAdapter.KEY_LOG_TIME, filter)) {
            while (c.moveToNext()) {
                LogItem event = new LogItem();
                event.setId(c.getInt(DBAdapter.COLUMN_LOG_ID));
                event.setTimeStamp(c.getLong(DBAdapter.COLUMN_LOG_TIME));
                event.setAmount(c.getDouble(DBAdapter.COLUMN_LOG_AMOUNT));
                event.setAccountId(c.getInt(DBAdapter.COLUMN_LOG_ACCOUNT));
                event.setExpenseIncome(c.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME));
                event.setRepeatingId(c.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
                event.setNotes(c.getString(DBAdapter.COLUMN_LOG_NOTES));
                event.setCategory(c.getInt(DBAdapter.COLUMN_LOG_CATEGORY));
                when.setTimeInMillis(event.getTimeStamp());
                byDate[when.get(Calendar.DAY_OF_MONTH)].addEvent(event);
            }
        }
        return days;
    }
}
