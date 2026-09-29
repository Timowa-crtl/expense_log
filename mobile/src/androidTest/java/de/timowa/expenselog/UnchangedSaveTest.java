package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Saving an edited record that was not changed writes nothing and asks nothing: the edit screen
 * compares {@link NewLogFragment#formState} as filled with the same state at save. Every field a
 * save could write must therefore change the state, and nothing a save ignores may.
 */
@RunWith(AndroidJUnit4.class)
public class UnchangedSaveTest {

    private static final String VALUES = "12.50\nlunch\nfalse\n-1\n3";
    private static final long TIME = 1_767_225_612_345L;
    private static final long END = 1_798_761_599_999L;

    private static String state(String values, long time, boolean repeat, int frequency, int period,
                                long end) {
        return NewLogFragment.formState(values, time, repeat, frequency, period, end);
    }

    @Test
    public void sameForm_isNoChange() {
        assertEquals(state(VALUES, TIME, false, 0, 0, END), state(VALUES, TIME, false, 0, 0, END));
        assertEquals(state(VALUES, TIME, true, 1, 2, END), state(VALUES, TIME, true, 1, 2, END));
    }

    @Test
    public void valuesOrTime_areAChange() {
        String filled = state(VALUES, TIME, false, 0, 0, END);
        assertNotEquals(filled, state("12.51\nlunch\nfalse\n-1\n3", TIME, false, 0, 0, END));
        // the time to the millisecond, as it is stored
        assertNotEquals(filled, state(VALUES, TIME + 1, false, 0, 0, END));
    }

    @Test
    public void aSeriesSchedule_isAChange() {
        String filled = state(VALUES, TIME, true, 1, 2, END);
        assertNotEquals(filled, state(VALUES, TIME, true, 2, 2, END));
        assertNotEquals(filled, state(VALUES, TIME, true, 1, 1, END));
        assertNotEquals(filled, state(VALUES, TIME, true, 1, 2, END + 1));
    }

    @Test
    public void repeatSwitchedOn_isAChange() {
        assertNotEquals(state(VALUES, TIME, false, 1, 2, END), state(VALUES, TIME, true, 1, 2, END));
    }

    @Test
    public void withRepeatOff_theHiddenScheduleIsNoChange() {
        // switched on, edited, switched off again: nothing of it would be saved
        assertEquals(state(VALUES, TIME, false, 0, 0, END), state(VALUES, TIME, false, 3, 1, END + 5));
    }
}
