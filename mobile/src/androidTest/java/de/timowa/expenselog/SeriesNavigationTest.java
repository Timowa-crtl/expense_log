package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * The edit screen's previous / next entry of a series: {@link RepeatingSeries#neighbourOf} walks
 * the series in date order and stops at both ends.
 */
@RunWith(AndroidJUnit4.class)
public class SeriesNavigationTest {

    private static final long JAN_2026 = 1_767_225_600_000L;
    private static final long DAY_MS = 86_400_000L;

    private Context context;
    private DBAdapter adapter;
    private RepeatingSeries series;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        adapter = new DBAdapter(context);
        adapter.open();
        int category = adapter.newTag("Rent", 0, "fa-home");
        int account = (int) adapter.newAccount("Personal");
        // a plain record in between, which the series must not step onto
        assertTrue(adapter.newLog(JAN_2026 + DAY_MS / 2, 0, account, 1.0, category, "", "", -1));
        int id = adapter.createRepeatingSeries(JAN_2026, JAN_2026 + 4 * DAY_MS, 500.0, 1, 0,
                0, account, category, "rent", "");
        assertNotEquals(-1, id);
        assertTrue(adapter.newLog(JAN_2026, 0, account, 500.0, category, "rent", "", id));
        series = adapter.getRepeatingSeries(id);
        assertEquals(4, series.size());
    }

    @After
    public void tearDown() {
        adapter.close();
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    @Test
    public void nextAndPrevious_walkTheSeriesInDateOrder() {
        long second = series.times[1];
        assertEquals(series.logIds[2], series.neighbourOf(second, 1));
        assertEquals(series.logIds[0], series.neighbourOf(second, -1));

        // walk all the way forward from the first entry
        long time = series.times[0];
        int steps = 0;
        int id;
        while ((id = series.neighbourOf(time, 1)) != -1) {
            steps++;
            time = series.times[steps];
            assertEquals(series.logIds[steps], id);
        }
        assertEquals(3, steps);
    }

    @Test
    public void bothEnds_andARecordOutsideTheSeries_goNowhere() {
        assertEquals(-1, series.neighbourOf(series.times[0], -1));
        assertEquals(-1, series.neighbourOf(series.times[3], 1));
        assertEquals(-1, series.neighbourOf(JAN_2026 + DAY_MS / 2, 1));
    }
}
