package de.timowa.expenselog;

import java.util.Calendar;

/**
 * One repeating series as it stands in the database: its {@code RepeatingTable} entry and the
 * records that still carry its id, oldest first. Read with {@link DBAdapter#getRepeatingSeries}.
 *
 * <p><b>The schedule's origin is not stored, so it is worked out here.</b> {@code RepeatingTable}
 * holds the interval and the end but not the time the series started, and the first record may
 * have been deleted or edited out of the series since. {@link #findOrigin} recovers a time the
 * whole series can be computed from -- with {@link DBAdapter#repeatingOccurrence}, as every
 * occurrence must be -- and checks every remaining record against it. Extending a series and
 * the overview's "next date" rely on it; nothing else does.
 */
final class RepeatingSeries {

    static final int PERIOD_DAY = 0, PERIOD_WEEK = 1, PERIOD_MONTH = 2, PERIOD_YEAR = 3;

    final int id;
    final double amount;
    final int period;
    final int frequency;
    /** Occurrences lie strictly before this, as {@link DBAdapter#createRepeatingSeries} lays them out. */
    final long endTime;
    /** The series' records, oldest first; {@code times[i]} belongs to {@code logIds[i]}. */
    final int[] logIds;
    final long[] times;

    /** The latest record's values, which an extension copies and the overview shows. */
    LogItem latest;

    RepeatingSeries(int id, double amount, int period, int frequency, long endTime,
                    int[] logIds, long[] times) {
        this.id = id;
        this.amount = amount;
        this.period = period;
        this.frequency = frequency;
        this.endTime = endTime;
        this.logIds = logIds;
        this.times = times;
    }

    int size() {
        return times.length;
    }

    /**
     * The records from {@code time} on, as the series "This and following" splits off there
     * would hold them: same schedule and end, so {@link #findOrigin} sees what the save will.
     */
    RepeatingSeries from(long time) {
        int first = 0;
        while (first < times.length && times[first] < time)
            first++;
        RepeatingSeries part = new RepeatingSeries(id, amount, period, frequency, endTime,
                java.util.Arrays.copyOfRange(logIds, first, logIds.length),
                java.util.Arrays.copyOfRange(times, first, times.length));
        part.latest = latest;
        return part;
    }

    /** The 1-based position of the record at {@code time}, or 0 if none of the series' records is there. */
    int positionOf(long time) {
        for (int i = 0; i < times.length; i++)
            if (times[i] == time)
                return i + 1;
        return 0;
    }

    /**
     * The id of the record next to the one at {@code time}: {@code direction} -1 for the one
     * before, +1 for the one after. -1 at either end, or if no record is at {@code time}.
     */
    int neighbourOf(long time, int direction) {
        int position = positionOf(time);
        int index = position - 1 + direction;
        if (position == 0 || index < 0 || index >= times.length)
            return -1;
        return logIds[index];
    }

    /** How many of the series' records lie at or after {@code time}. */
    int countFrom(long time) {
        int n = 0;
        for (long t : times)
            if (t >= time)
                n++;
        return n;
    }

    /** The first record after {@code now}, or -1 if the series has none left. */
    long nextAfter(long now) {
        for (long t : times)
            if (t > now)
                return t;
        return -1;
    }

    /**
     * Where a series' schedule can be computed from: occurrence {@code k} lies at
     * {@code repeatingOccurrence(time, k, ...)}, and the remaining records are occurrences
     * {@code firstK} to {@code lastK}. {@code k} may be negative -- the origin is a record of the
     * series, not necessarily its first.
     */
    static final class Origin {
        final long time;
        final long firstK;
        final long lastK;

        Origin(long time, long firstK, long lastK) {
            this.time = time;
            this.firstK = firstK;
            this.lastK = lastK;
        }
    }

    /** {@link #findOrigin(long[], int, int)} for this series. */
    Origin findOrigin() {
        return findOrigin(times, frequency, period);
    }

    /**
     * Recovers a schedule origin from a series' remaining records, or returns null if the records
     * do not lie on one regular schedule.
     *
     * <p>For days and weeks any record will do: every occurrence is a whole number of days from
     * any other. Months and years are the hard case. A series from 31 January stores 28 February,
     * and a schedule computed from the 28th stays on the 28th -- so the origin is the earliest
     * record on the <b>latest day of the month</b> any record has. {@code Calendar.add} clamps
     * each occurrence to its month's length independently, so a series computed from 31 March
     * is the same series as one computed from 31 January. The same holds for 29 February in a
     * yearly series.
     *
     * <p>The one case this gets wrong: every record that showed the real day has gone -- a series
     * on the 31st whose remaining records all fall in 30-day months or February. Every remaining
     * record still matches, but occurrences added in longer months land a day or more early.
     *
     * <p>Every record is checked against the result, to the millisecond. A series that fails --
     * one written by the stepping bug before {@link DBAdapter#repeatingOccurrence} existed, or one
     * whose records were created in another time zone -- has no origin, and the caller refuses
     * whatever needed it rather than guessing.
     */
    static Origin findOrigin(long[] times, int frequency, int period) {
        if (times.length == 0 || frequency <= 0 || period < PERIOD_DAY || period > PERIOD_YEAR)
            return null;
        long origin = times[0];
        if (period == PERIOD_MONTH || period == PERIOD_YEAR) {
            int latestDay = 0;
            for (long t : times)
                latestDay = Math.max(latestDay, calendar(t).get(Calendar.DAY_OF_MONTH));
            for (long t : times) {
                if (calendar(t).get(Calendar.DAY_OF_MONTH) == latestDay) {
                    origin = t;
                    break;
                }
            }
        }
        long firstK = 0, lastK = 0;
        for (int i = 0; i < times.length; i++) {
            Long k = occurrenceIndex(origin, times[i], frequency, period);
            if (k == null || (i > 0 && k <= lastK))
                return null;
            if (i == 0)
                firstK = k;
            lastK = k;
        }
        return new Origin(origin, firstK, lastK);
    }

    /** The {@code k} with {@code repeatingOccurrence(origin, k, ...) == time}, or null if there is none. */
    private static Long occurrenceIndex(long origin, long time, int frequency, int period) {
        long k;
        if (period == PERIOD_MONTH || period == PERIOD_YEAR) {
            Calendar o = calendar(origin);
            Calendar t = calendar(time);
            long months = (t.get(Calendar.YEAR) - o.get(Calendar.YEAR)) * 12L
                    + t.get(Calendar.MONTH) - o.get(Calendar.MONTH);
            long step = (long) frequency * (period == PERIOD_YEAR ? 12 : 1);
            if (months % step != 0)
                return null;
            k = months / step;
        } else {
            // Daylight saving moves a day by an hour at most, which rounding absorbs.
            long days = Math.round((time - origin) / 86_400_000.0);
            long step = (long) frequency * (period == PERIOD_WEEK ? 7 : 1);
            if (days % step != 0)
                return null;
            k = days / step;
        }
        if (Math.abs(k) > Integer.MAX_VALUE)
            return null;
        return DBAdapter.repeatingOccurrence(origin, (int) k, frequency, period) == time ? k : null;
    }

    private static Calendar calendar(long time) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(time);
        return c;
    }
}
