package de.timowa.expenselog;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * The stretch of records an export covers, as the records screen addresses it: a duration (one of
 * the {@code LogTabsFragment.KEY_RECORDS_MODE_*} values) and a page offset from the current period.
 *
 * <p>The range comes from {@link LogTabsFragment#getPeriodStart} and {@link
 * LogTabsFragment#getPeriodEnd} rather than from arithmetic of its own, so an export of "Sep 2026"
 * covers exactly what the Sep 2026 page shows, including the first-day-of-week setting.
 *
 * <p>Immutable: {@link #step} and {@link #withMode} return a new period.
 */
final class ExportPeriod {

    final int mode;
    final int offset;
    final long start;
    final long end;

    private final Context context;
    private final SharedPreferences prefs;

    ExportPeriod(int mode, int offset, Context context, SharedPreferences prefs) {
        this.mode = mode;
        // All Records has one page, so its offset means nothing. The records pager reports -500 for
        // it (page 0 minus MID_PAGE); pinning it to 0 keeps that from leaking into withMode.
        this.offset = mode == LogTabsFragment.KEY_RECORDS_MODE_ALL ? 0 : offset;
        this.context = context;
        this.prefs = prefs;
        this.start = LogTabsFragment.getPeriodStart(mode, this.offset, context, prefs).getTimeInMillis();
        this.end = LogTabsFragment.getPeriodEnd(mode, this.offset, context, prefs).getTimeInMillis();
    }

    boolean isAllRecords() {
        return mode == LogTabsFragment.KEY_RECORDS_MODE_ALL;
    }

    /** The period {@code delta} pages before (negative) or after (positive) this one. */
    ExportPeriod step(int delta) {
        return new ExportPeriod(mode, offset + delta, context, prefs);
    }

    /**
     * The period of duration {@code newMode} that contains this one's anchor: today, if today is in
     * this period, otherwise this period's start. So Sep 2025 becomes 2025 rather than the current
     * year, and the current month becomes the current week rather than the month's first week.
     */
    ExportPeriod withMode(int newMode) {
        if (newMode == mode)
            return this;
        long now = System.currentTimeMillis();
        long anchor = isAllRecords() ? now : ViewedPeriod.anchorFor(start, end, now);

        // The records pager's own lookup, so the two cannot drift apart; unclamped, since the
        // dialog steps beyond the pager's window.
        return new ExportPeriod(newMode, LogTabsFragment.offsetHolding(newMode, anchor, context, prefs,
                Calendar.getInstance()), context, prefs);
    }

    /**
     * The period as the stepper shows it: {@code 2026}, {@code Sep 2026}, {@code Sep 7 – 13, 2026},
     * {@code Sun, Sep 13, 2026}. Every form carries the year, unlike the records screen's tab
     * titles, because the dialog has no neighbouring tabs to give it away. Empty for All Records,
     * which has no stepper. In English whatever the device language; see {@link AppLocale}.
     */
    String label() {
        switch (mode) {
            case LogTabsFragment.KEY_RECORDS_MODE_YEAR:
                return format("yyyy", start);
            case LogTabsFragment.KEY_RECORDS_MODE_MONTH:
                return format("MMM yyyy", start);
            case LogTabsFragment.KEY_RECORDS_MODE_WEEK:
                return weekRange();
            case LogTabsFragment.KEY_RECORDS_MODE_DAY:
                return format("EEE, MMM d, yyyy", start);
            default:
                return "";
        }
    }

    /** "Sep 7 – 13, 2026", "Sep 28 – Oct 4, 2026", "Dec 28, 2026 – Jan 3, 2027". */
    private String weekRange() {
        Calendar first = Calendar.getInstance();
        first.setTimeInMillis(start);
        Calendar last = Calendar.getInstance();
        last.setTimeInMillis(end);
        if (first.get(Calendar.YEAR) != last.get(Calendar.YEAR))
            return format("MMM d, yyyy", start) + " \u2013 " + format("MMM d, yyyy", end);
        if (first.get(Calendar.MONTH) != last.get(Calendar.MONTH))
            return format("MMM d", start) + " \u2013 " + format("MMM d, yyyy", end);
        return format("MMM d", start) + " \u2013 " + format("d, yyyy", end);
    }

    private static String format(String pattern, long time) {
        return new SimpleDateFormat(pattern, AppLocale.TEXT).format(time);
    }

    /** The exact dates covered, in the user's date format, for the preview line. Empty for All Records. */
    String dateRange() {
        if (isAllRecords())
            return "";
        java.text.DateFormat dates = new PrefManager(context).getDateFormat();
        return dates.format(start) + " \u2013 " + dates.format(end);
    }
}
