package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.preference.PreferenceManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * The install date must keep being recorded, now that the rating system it lived in is gone.
 *
 * <p>{@code setFirstLaunch()} was called from {@code RatingManager.initialize()}, so deleting the
 * rating prompt would have taken the only writer of {@code pref_key_first_launch_time} with it.
 * Nothing would have failed visibly. {@link LocalBackupManager} gates the weekly and monthly
 * backups on how long the app has been installed, and an unwritten timestamp reads {@code 0} --
 * which makes "time since first launch" about fifty years, so both backups fire immediately on a
 * fresh install instead of after a week and a month.
 *
 * <p>The write moved to {@code MainActivity.onCreate}. These tests pin the behaviour rather than
 * the call site: that a first call records a plausible timestamp, and that a second call leaves it
 * alone, because an install date that moves is not an install date.
 *
 * <p>Uses {@link IsolatedDatabaseContext}, so it reads and writes test preferences and never the
 * real install's.
 */
@RunWith(AndroidJUnit4.class)
public class FirstLaunchTimeTest {

    private Context isolated;
    private PrefManager prefManager;
    private String key;

    @Before
    public void setUp() {
        isolated = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        key = isolated.getString(R.string.pref_key_first_launch_time);
        PreferenceManager.getDefaultSharedPreferences(isolated).edit().remove(key).apply();
        prefManager = new PrefManager(isolated);
    }

    @Test
    public void firstLaunch_isRecordedTheFirstTimeAndIsPlausible() {
        assertTrue("the first call is the one that writes it", prefManager.setFirstLaunch());

        long recorded = prefManager.getFirstLaunch();
        long drift = Math.abs(System.currentTimeMillis() - recorded);
        assertTrue("recorded install time is not close to now: " + recorded,
                drift < TimeUnit.MINUTES.toMillis(5));
    }

    /** An install date that moves would keep pushing the weekly and monthly backups away. */
    @Test
    public void firstLaunch_isNotRewrittenOnLaterLaunches() {
        prefManager.setFirstLaunch();
        long first = prefManager.getFirstLaunch();

        assertFalse("a later launch must not rewrite the install date",
                prefManager.setFirstLaunch());
        assertEquals(first, prefManager.getFirstLaunch());
    }

    /** The static reader LocalBackupManager uses must see the same value. */
    @Test
    public void localBackupManagersReader_seesTheSameTimestamp() {
        prefManager.setFirstLaunch();

        assertEquals(prefManager.getFirstLaunch(), PrefManager.getFirstLaunch(isolated));
    }
}
