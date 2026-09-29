package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@link ExportPeriod#withMode}: the export dialog's period in another duration holds the same
 * anchor -- today if the period holds it, else its start -- found with the records pager's own
 * {@link LogTabsFragment#offsetHolding}, but without the pager's 500-page clamp.
 *
 * <p>Writes only a test-named preferences file, deleted afterwards; opens no database.
 */
@RunWith(AndroidJUnit4.class)
public class ExportPeriodTest {

    private static final String PREFS = "export_period_test";
    private static final int YEAR = LogTabsFragment.KEY_RECORDS_MODE_YEAR;
    private static final int MONTH = LogTabsFragment.KEY_RECORDS_MODE_MONTH;
    private static final int WEEK = LogTabsFragment.KEY_RECORDS_MODE_WEEK;
    private static final int DAY = LogTabsFragment.KEY_RECORDS_MODE_DAY;

    private Context context;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() {
        prefs.edit().clear().commit();
        context.deleteSharedPreferences(PREFS);
    }

    private static boolean holds(ExportPeriod p, long time) {
        return p.start <= time && time <= p.end;
    }

    @Test
    public void theCurrentPeriod_narrowsToToday() {
        long now = System.currentTimeMillis();
        ExportPeriod month = new ExportPeriod(MONTH, 0, context, prefs);
        assertTrue(holds(month.withMode(WEEK), now));
        assertTrue(holds(month.withMode(DAY), now));
        assertTrue(holds(month.withMode(YEAR), now));
    }

    @Test
    public void aPastPeriod_changesDurationAroundItsStart() {
        ExportPeriod month = new ExportPeriod(MONTH, -13, context, prefs);
        for (int mode : new int[]{YEAR, WEEK, DAY})
            assertTrue(holds(month.withMode(mode), month.start));
        assertEquals(month.start, month.withMode(DAY).start);
    }

    @Test
    public void farBack_isNotClampedToThePagerWindow() {
        // 40 months is well over 500 days
        ExportPeriod month = new ExportPeriod(MONTH, -40, context, prefs);
        ExportPeriod day = month.withMode(DAY);
        assertEquals(month.start, day.start);
        assertTrue(day.offset < -500);
    }
}
