package de.timowa.expenselog;

/**
 * The period the records views -- List, Summary, Graph, Calendar -- were last showing, so that
 * switching between them, or changing the period length, keeps the user on July rather than
 * jumping back to today. Kept in memory only: a fresh {@code MainActivity} clears it, so a fresh
 * start shows today.
 *
 * <p>It is one time, the <i>focus</i>, that every view pages to -- not a page, since pages mean
 * different things in different period lengths. Two rules decide it:
 * <ul>
 * <li>Paging by hand sets it: to today if the new period holds today, else to its first
 * millisecond. A period that held today <i>follows</i> today, so September left on the 30th
 * shows October on the 1st.</li>
 * <li>Showing a period for any other reason -- a view switch, a new period length, Back, a save
 * -- keeps it while the period holds it. That is what brings July -> Week -> Day -> Month, and
 * July -> Year -> Month, back to July: each step pages to the focus, and the focus is still
 * 1 July. Only a period that does not hold it replaces it.</li>
 * </ul>
 * It replaced a period plus an anchor plus a "current" flag, whose rules broke each other:
 * July -> Year -> Month opened September. {@code SavedRecordPageTest}.
 */
final class ViewedPeriod {

    private static boolean set;
    private static long focus;
    /** Whether the focus is "today" rather than a date: then it moves on with the clock. */
    private static boolean followsToday;

    private ViewedPeriod() {
    }

    /** The user paged to this period. */
    static void paged(long periodStart, long periodEnd, long now) {
        followsToday = periodStart <= now && now <= periodEnd;
        focus = anchorFor(periodStart, periodEnd, now);
        set = true;
    }

    /**
     * The time a period stands for when another length is chosen: today if it holds today, else
     * its first millisecond. The export dialog's {@link ExportPeriod#withMode} uses it too.
     */
    static long anchorFor(long periodStart, long periodEnd, long now) {
        return periodStart <= now && now <= periodEnd ? now : periodStart;
    }

    /**
     * Pages the records views to {@code time} -- for a screen that returns to them without a
     * save's own page, as an editor closed by the save that deleted its record does.
     */
    static void focusOn(long time) {
        focus = time;
        followsToday = false;
        set = true;
    }

    /**
     * A view showed this period, paged to from {@code target} -- normally {@link #focusTime}, or
     * a saved record's time. The focus stays if the period holds it. A target the period does not
     * hold means the pager's window clamped it, and the period shown is not one the user chose:
     * nothing is remembered, so the next view still pages to where the user was.
     */
    static void shown(long periodStart, long periodEnd, long target, long now) {
        if (target < periodStart || target > periodEnd)
            return;
        long current = focusTime(now);
        if (set && periodStart <= current && current <= periodEnd)
            return;
        paged(periodStart, periodEnd, now);
    }

    static void clear() {
        set = false;
    }

    static boolean isSet() {
        return set;
    }

    /** The time every records view pages to. */
    static long focusTime(long now) {
        return followsToday ? now : focus;
    }
}
