package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * What Back does, over a real fragment back stack.
 *
 * <p><b>Why this exists.</b> Opening a record with Edit and pressing Back closed the app on the
 * third round, with the list still underneath. {@code MainActivity.backPressCount} counted every
 * Back press, and only a drawer tap reset it, so leaving an editor used up the same three presses
 * that are meant to cap walking back through drawer screens. Sub-screens no longer count; the cap on
 * drawer screens stays.
 *
 * <p>Each Back is taken the way {@code MainActivity.onBackPressed} takes it: ask
 * {@link MainActivity#backLeavesApp}, then pop the stack if it says stay. Drawer screens are added
 * the way {@code Navigator.changeFragment} adds them, with their title as fragment tag and
 * back-stack name, because building the real ones needs the Dagger graph and the database;
 * sub-screens go through the real {@link Navigator#newTempFragment}. No database is opened.
 */
@RunWith(AndroidJUnit4.class)
public class BackNavigationTest {

    private static final String START = "Expense Log";
    private static final String LIST = "List";

    private int savedCount;

    @Before
    public void setUp() {
        savedCount = MainActivity.backPressCount;
    }

    @After
    public void tearDown() {
        MainActivity.backPressCount = savedCount;
    }

    /** The reported bug: Edit, Back, repeated, must keep returning to the list. */
    @Test
    public void editThenBack_repeated_staysOnTheList() {
        try (ActivityScenario<BackStackHostActivity> scenario =
                     ActivityScenario.launch(BackStackHostActivity.class)) {
            scenario.onActivity(activity -> {
                openDrawerScreen(activity, START);
                openDrawerScreen(activity, LIST);
                Navigator navigator = navigator(activity);

                for (int round = 1; round <= 5; round++) {
                    openSubScreen(activity, navigator, "Edit Record");
                    assertTrue("round " + round + ": the editor is a sub-screen",
                            navigator.isOnSubScreen());
                    assertStays(activity, navigator, "round " + round + ": Back from the editor");
                    assertEquals("round " + round, LIST, currentTag(activity));
                    assertFalse(navigator.isOnSubScreen());
                }

                // Now the list's own Back goes to the start screen, and the start screen's leaves.
                assertStays(activity, navigator, "Back from the list");
                assertEquals(START, currentTag(activity));
                assertTrue("Back from the first screen leaves the app",
                        MainActivity.backLeavesApp(navigator.isOnSubScreen(), entries(activity)));
            });
        }
    }

    /** Drawer -> Recurring -> Edit -> New Category, then Back, Back, Back. */
    @Test
    public void nestedSubScreens_backReturnsThroughEachToTheStartScreen() {
        try (ActivityScenario<BackStackHostActivity> scenario =
                     ActivityScenario.launch(BackStackHostActivity.class)) {
            scenario.onActivity(activity -> {
                openDrawerScreen(activity, START);
                openDrawerScreen(activity, "Recurring");
                Navigator navigator = navigator(activity);
                openSubScreen(activity, navigator, "New Record");
                openSubScreen(activity, navigator, "New Category");

                assertStays(activity, navigator, "Back from New Category");
                assertEquals("New Record", entryName(activity));
                assertStays(activity, navigator, "Back from the editor");
                assertEquals("Recurring", currentTag(activity));
                assertStays(activity, navigator, "Back from Recurring");
                assertEquals(START, currentTag(activity));
            });
        }
    }

    /**
     * New Record is both a drawer entry and a sub-screen title, so the back-stack name cannot
     * tell them apart; the fragment tag does.
     */
    @Test
    public void newRecordFromTheDrawer_countsAsADrawerScreen() {
        try (ActivityScenario<BackStackHostActivity> scenario =
                     ActivityScenario.launch(BackStackHostActivity.class)) {
            scenario.onActivity(activity -> {
                openDrawerScreen(activity, START);
                openDrawerScreen(activity, "New Record");
                assertFalse(navigator(activity).isOnSubScreen());
                openSubScreen(activity, navigator(activity), "New Record");
                assertTrue(navigator(activity).isOnSubScreen());
            });
        }
    }

    /** The cap on walking back through drawer screens is deliberate, and stays. */
    @Test
    public void drawerHistory_isStillCappedAtTheLimit() {
        try (ActivityScenario<BackStackHostActivity> scenario =
                     ActivityScenario.launch(BackStackHostActivity.class)) {
            scenario.onActivity(activity -> {
                openDrawerScreen(activity, START);
                openDrawerScreen(activity, LIST);
                openDrawerScreen(activity, "Graph");
                openDrawerScreen(activity, "Calendar");
                openDrawerScreen(activity, "Summary");
                Navigator navigator = navigator(activity);

                for (int press = 1; press < MainActivity.BACK_PRESS_LIMIT; press++) {
                    assertStays(activity, navigator, "drawer Back " + press);
                }
                assertTrue("Back " + MainActivity.BACK_PRESS_LIMIT + " leaves, screens or not",
                        MainActivity.backLeavesApp(navigator.isOnSubScreen(), entries(activity)));
            });
        }
    }

    /** The sub-screen check reads the fragment tag because that survives recreation. */
    @Test
    public void subScreen_isStillRecognisedAfterRecreation() {
        try (ActivityScenario<BackStackHostActivity> scenario =
                     ActivityScenario.launch(BackStackHostActivity.class)) {
            scenario.onActivity(activity -> {
                openDrawerScreen(activity, START);
                openDrawerScreen(activity, LIST);
                openSubScreen(activity, navigator(activity), "Edit Record");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals(3, entries(activity));
                assertTrue(navigator(activity).isOnSubScreen());
                assertStays(activity, navigator(activity), "Back from the recreated editor");
                assertEquals(LIST, currentTag(activity));
            });
        }
    }

    /** Mirrors Navigator.changeFragment, including the counter reset. */
    private static void openDrawerScreen(BackStackHostActivity activity, String title) {
        activity.getSupportFragmentManager().beginTransaction()
                .replace(R.id.mainContentFragment, new Fragment(), title)
                .addToBackStack(title)
                .commit();
        activity.getSupportFragmentManager().executePendingTransactions();
        MainActivity.backPressCount = MainActivity.BACK_PRESS_LIMIT;
    }

    private static void openSubScreen(BackStackHostActivity activity, Navigator navigator,
                                      String title) {
        navigator.newTempFragment(new Fragment(), title);
        activity.getSupportFragmentManager().executePendingTransactions();
    }

    private static void assertStays(BackStackHostActivity activity, Navigator navigator,
                                    String what) {
        assertFalse(what + " must not leave the app",
                MainActivity.backLeavesApp(navigator.isOnSubScreen(), entries(activity)));
        assertTrue(what + ": nothing to pop",
                activity.getSupportFragmentManager().popBackStackImmediate());
    }

    private static Navigator navigator(BackStackHostActivity activity) {
        return new Navigator(activity, activity);
    }

    private static int entries(BackStackHostActivity activity) {
        return activity.getSupportFragmentManager().getBackStackEntryCount();
    }

    private static String entryName(BackStackHostActivity activity) {
        FragmentManager fm = activity.getSupportFragmentManager();
        return fm.getBackStackEntryAt(fm.getBackStackEntryCount() - 1).getName();
    }

    private static String currentTag(BackStackHostActivity activity) {
        return activity.getSupportFragmentManager()
                .findFragmentById(R.id.mainContentFragment).getTag();
    }
}
