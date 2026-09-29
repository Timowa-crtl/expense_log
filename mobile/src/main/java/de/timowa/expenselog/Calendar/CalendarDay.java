package de.timowa.expenselog.Calendar;

import android.content.Context;
import android.database.Cursor;
import android.widget.BaseAdapter;

import java.util.ArrayList;
import java.util.Calendar;

import de.timowa.expenselog.DBAdapter;
import de.timowa.expenselog.LogItem;

public class CalendarDay {

    //    int monthEndDay;
    int day;
    int year;
    int month;
    String recordsFilter = "";

    Context context;
    BaseAdapter adapter;
    ArrayList<LogItem> eventsLogArrayList = new ArrayList<>();

    CalendarDay(Context context, int day, int year, int month, String filter) {
        this.recordsFilter = filter;
        this.day = day;
        this.year = year;
        this.month = month;
        this.context = context;
        Calendar cal = Calendar.getInstance();
        cal.set(year, month - 1, day);
        int end = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        cal.set(year, month, end);
    }

    public int getMonth() {
        return month;
    }

    public int getYear() {
        return year;
    }

    public int getDay() {
        return day;
    }


    /** Adds a record loaded for this day; see {@link CalendarAdapter#loadMonth}. */
    void addEvent(LogItem event) {
        eventsLogArrayList.add(event);
    }

    /**
     * Income minus expenses for the day, from the records it already holds -- the same number
     * {@code DBAdapter.getTotalForRange} computes over the same range and filter, without asking
     * the database again for every cell on every layout pass. {@code CalendarTotalsTest}.
     */
    public double getTotal() {
        double total = 0;
        for (LogItem event : eventsLogArrayList) {
            if (event.getExpenseIncome() == 1)
                total += event.getAmount();
            else if (event.getExpenseIncome() == 0)
                total -= event.getAmount();
        }
        return total;
    }

    public int getNumOfEvents() {
        return eventsLogArrayList.size();
    }

    /**
     * Get all the events on the day
     *
     * @return list of events
     */
    public ArrayList<LogItem> getEvents() {
        return eventsLogArrayList;
    }

    public void setAdapter(BaseAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * The first millisecond of a calendar day, 00:00:00.000 local time.
     *
     * <p>The millisecond is set explicitly. These bounds used to clear hours, minutes and seconds
     * but not the millisecond, so a day started at whatever millisecond the clock was on, and a
     * record saved at 00:00:00.216 dropped out of its day whenever that value was above 216 --
     * the same bug e7b9eb0 fixed for the records pages. {@code CalendarDayBoundsTest}.
     */
    public static long dayStart(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, 0, 0, 0);
        return c.getTimeInMillis();
    }

    /** The last millisecond of a calendar day: one before the next day starts, 23:59:59.999. */
    public static long dayEnd(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month, day, 0, 0, 0);
        c.add(Calendar.DATE, 1);
        return c.getTimeInMillis() - 1;
    }

    public void noAsync(DBAdapter db) {
        long dayStart = dayStart(year, month, day);
        long dayEnd = dayEnd(year, month, day);

        Cursor eventsForDayCursor = db.getLogsInRange(dayStart, dayEnd, DBAdapter.KEY_LOG_TIME, recordsFilter);

        if (eventsForDayCursor != null) {
            if (eventsForDayCursor.moveToFirst()) {
                do {
                    // create new event with headache ID and start time
                    LogItem calendarEvent = new LogItem();
                    calendarEvent.setId(eventsForDayCursor.getInt(DBAdapter.COLUMN_LOG_ID));
                    calendarEvent.setTimeStamp(eventsForDayCursor.getLong(DBAdapter.COLUMN_LOG_TIME));
                    calendarEvent.setAmount(eventsForDayCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT));
                    calendarEvent.setAccountId(eventsForDayCursor.getInt(DBAdapter.COLUMN_LOG_ACCOUNT));
                    calendarEvent.setExpenseIncome(eventsForDayCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME));
                    calendarEvent.setRepeatingId(eventsForDayCursor.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
                    calendarEvent.setNotes(eventsForDayCursor.getString(DBAdapter.COLUMN_LOG_NOTES));
                    calendarEvent.setCategory(eventsForDayCursor.getInt(DBAdapter.COLUMN_LOG_CATEGORY));
                    eventsLogArrayList.add(calendarEvent);
                } while (eventsForDayCursor.moveToNext());
            }
            eventsForDayCursor.close();
        }
    }

}
