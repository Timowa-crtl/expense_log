package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Pins the First Day of Week default to Monday.
 *
 * <p>The day list does not start on Sunday: it runs Saturday, Sunday, Monday, ... so Monday is
 * value {@code "2"}. The number alone is meaningless, which is the point of this test — it
 * resolves the stored value back through the two arrays to a label, so reordering the day list
 * without moving the default fails here rather than silently shifting everyone's week.
 *
 * <p>{@code R.string.default_first_day_of_week} is not decoration. Nothing calls
 * {@code PreferenceManager.setDefaultValues}, so {@code android:defaultValue} only pre-selects the
 * radio in the settings dialog; the string is passed as the {@code getString} fallback by all
 * three readers of the preference ({@code LogTabsFragment} twice, for the start and end of a week,
 * and {@code CalendarAdapter}), and that fallback is what actually decides where a week starts
 * while the key is absent.
 *
 * <p>Reads resources only and touches no preferences, so unlike
 * {@link PrefManagerDateFormatTest} it needs no isolation harness.
 */
@RunWith(AndroidJUnit4.class)
public class FirstDayOfWeekDefaultTest {

    private Context context;
    private String[] values;
    private String[] labels;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        values = context.getResources().getStringArray(R.array.pref_first_day_of_week_values);
        labels = context.getResources().getStringArray(R.array.pref_first_day_of_week_options);
    }

    /** The whole point of the change: the default day is Monday, whatever number encodes it. */
    @Test
    public void declaredDefault_selectsMonday() {
        String declared = context.getString(R.string.default_first_day_of_week);
        int index = Arrays.asList(values).indexOf(declared);

        assertTrue("the default must be one of the offered values, was " + declared, index >= 0);
        assertEquals("Monday", labels[index]);
    }

    /** {@code ListPreference} pairs the two arrays positionally. */
    @Test
    public void optionLabelsAndValues_areTheSameLength() {
        assertEquals(labels.length, values.length);
    }

    /** Seven days, offered once each — a duplicate would make the selected radio ambiguous. */
    @Test
    public void everyDayIsOfferedExactlyOnce() {
        assertEquals(7, labels.length);
        assertEquals("duplicate day labels", labels.length, Arrays.stream(labels).distinct().count());
        assertEquals("duplicate day values", values.length, Arrays.stream(values).distinct().count());
    }
}
