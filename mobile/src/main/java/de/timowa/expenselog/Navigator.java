package de.timowa.expenselog;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.navigation.NavigationView;

import java.util.List;

import javax.inject.Inject;

public class Navigator {

    private static final String NAV_TAG = "navigation";
    private Context context;
    private Activity activity;
    private MenuItem previousMenuItem;
    private NewLogFragment mNewLogFragment;
    private Fragment currentFrag;

    @Inject
    public Navigator(final Context context, final Activity activity) {
        this.context = context;
        this.activity = activity;
    }

    void changeFragment(final AppCompatActivity activityTest, final MenuItem menuItem) {
        changeFragment(activityTest, menuItem, null);
    }

    /**
     * As above; a records screen (List, Graph, Calendar, Summary) opens on the period holding
     * {@code showTime} rather than today's. Null, or any other screen, ignores it.
     */
    void changeFragment(final AppCompatActivity activityTest, final MenuItem menuItem, Long showTime) {
        if (BuildConfig.DEBUG) Log.i(NAV_TAG, "change frag called");
        // Create a new fragment and specify the fragment to show based on position
        Fragment fragment;

        fragment = getFragment(menuItem);
        if (showTime != null && fragment instanceof LogTabsFragment)
            ((LogTabsFragment) fragment).showingTime(showTime);

        // Insert the fragment by replacing any existing fragment
        FragmentManager fragmentManager = ((AppCompatActivity) activity).getSupportFragmentManager();

        boolean addFragment = true;
        // check if new selection is already selected
        if (fragmentManager.getBackStackEntryCount() > 0) {
            String lastBackStackItem = (fragmentManager.getBackStackEntryAt(fragmentManager.getBackStackEntryCount() - 1)).getName();
            String selectedItem = menuItem.getTitle().toString();
            if (BuildConfig.DEBUG)
                Log.i("Navigator", "backstack selectedItem: " + selectedItem + " " + lastBackStackItem);
            // don't replace fragment if it's the same as currently selected
            addFragment = !selectedItem.equals(lastBackStackItem);
        }
        if (addFragment) {
            String backStackTag = menuItem.getTitle().toString();
            if (BuildConfig.DEBUG)
                Log.i(NAV_TAG, "new backstack: " + backStackTag);
            FragmentTransaction transaction = fragmentManager.beginTransaction();

            // only add custom animations if not on the first screen viewed
            if (fragmentManager.getBackStackEntryCount() > 0)
                transaction.setCustomAnimations(R.anim.fade_in, R.anim.fade_out);

            transaction.replace(R.id.mainContentFragment, fragment, backStackTag)
                    .addToBackStack(backStackTag)
                    .commit();

            // if adding a fragment reset the back press counter
            MainActivity.backPressCount = MainActivity.BACK_PRESS_LIMIT;

            //todo
//            analyticsTracker.trackScreen((AppCompatActivity) activity, menuItem.getTitle().toString());

            // hide keyboard if it's open
            View view = activity.getCurrentFocus();
            if (view != null) {
                InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }


        updateMenuSelection(menuItem);

//        menuItem.setChecked(true);
    }

    void addBackStackListener() {
        FragmentManager fragmentManager = ((AppCompatActivity) activity).getSupportFragmentManager();
        fragmentManager.addOnBackStackChangedListener(new FragmentManager.OnBackStackChangedListener() {
            public void onBackStackChanged() {
                // Update the navbar selection whenever backstack is changed here.
                FragmentManager fm = ((AppCompatActivity) activity).getSupportFragmentManager();
                String backStackTag = activity.getResources().getString(R.string.app_name);
                if (fm.getBackStackEntryCount() > 0) {
                    backStackTag = (fm.getBackStackEntryAt(fm.getBackStackEntryCount() - 1)).getName();
                } else {
                    // MainActivity.onBackPressed leaves before popping the last screen, so this
                    // is only a fallback. finish() here, then return: the title and menu lookups
                    // below have no screen to describe.
                    exitApp();
                    return;
                }
                if (BuildConfig.DEBUG) Log.i(NAV_TAG, "backstack change: " + backStackTag);
                setToolbarTitle(backStackTag);

                MenuItem currentMenuItem = getCurrentMenuId(backStackTag);
                updateMenuSelection(currentMenuItem);
//                currentMenuItem.setChecked(true);

                int backListSize = fm.getBackStackEntryCount();
                for (int i = 0; i < backListSize; i++) {
                    if (BuildConfig.DEBUG)
                        Log.i(NAV_TAG, "back list tag: " + fm.getBackStackEntryAt(i));
                }
            }
        });

    }

    /**
    refresh the current fragment
    */
    void refreshFrag() {
        try {
            FragmentManager fragmentManager = ((AppCompatActivity) activity).getSupportFragmentManager();
            String backStackTag;
            if (fragmentManager.getBackStackEntryCount() > 0) {
                FragmentManager.BackStackEntry currentEntry = (fragmentManager.getBackStackEntryAt(fragmentManager.getBackStackEntryCount() - 1));
                backStackTag = currentEntry.getName();

                Fragment f = fragmentManager.findFragmentByTag(backStackTag);
                if (f == null) {
                    if (BuildConfig.DEBUG) Log.i(NAV_TAG, "null frag");
                } else {
                    if (BuildConfig.DEBUG) Log.i(NAV_TAG, "not null frag");
                    fragmentManager.beginTransaction()
                            //                        .remove(f)
                            .detach(f).attach(f)
                            //                        .replace(R.id.mainContentFragment, newFrag, backStackTag)
                            .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                            //                        .addToBackStack(null)
                            .commit();
                }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.i("ERROR", e.toString());
            e.printStackTrace();
        }
    }

    private Fragment getFragment(MenuItem menuItem) {
        if (BuildConfig.DEBUG) Log.i(NAV_TAG, "get a fragment");
        Fragment fragment = null;
        Fragment fragmentClass;
        int id = menuItem.getItemId();
        if (id == R.id.nav_new_log) {
            fragmentClass = NewLogFragment.newInstance("", "");
            mNewLogFragment = (NewLogFragment) fragmentClass;
        } else if (id == R.id.nav_categories) {
            fragmentClass = TagListFragment.newInstance("param 1 PASS", menuItem.getTitle().toString());
        } else if (id == R.id.nav_accounts) {
            fragmentClass = AccountListFragment.newInstance("param 1 PASS", menuItem.getTitle().toString());
        } else if (id == R.id.nav_recurring) {
            fragmentClass = RecurringListFragment.newInstance();
        } else if (id == R.id.nav_summary) {
            fragmentClass = LogTabsFragment.newInstance(LogTabsFragment.KEY_RECORDS_MODE_DEFAULT, LogTabsFragment.KEY_VIEW_MODE_SUMMARY);
        } else if (id == R.id.nav_list) {
            fragmentClass = LogTabsFragment.newInstance(LogTabsFragment.KEY_RECORDS_MODE_DEFAULT, LogTabsFragment.KEY_VIEW_MODE_LIST);
        } else if (id == R.id.nav_calendar) {
            fragmentClass = LogTabsFragment.newInstance(LogTabsFragment.KEY_RECORDS_MODE_MONTH, LogTabsFragment.KEY_VIEW_MODE_CALENDAR);
        } else if (id == R.id.nav_graph) {
            fragmentClass = LogTabsFragment.newInstance(LogTabsFragment.KEY_RECORDS_MODE_DEFAULT, LogTabsFragment.KEY_VIEW_MODE_GRAPH);
        } else {
            fragmentClass = NewLogFragment.newInstance(null, "");
        }

        try {
            fragment = fragmentClass;
            currentFrag = fragment;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fragment;
    }

    private void updateMenuSelection(MenuItem newMenuItem) {
        if (previousMenuItem != null) {
            previousMenuItem.setChecked(false);
        }
        previousMenuItem = newMenuItem;
        newMenuItem.setChecked(true);
    }

    /**
     * Opens a record in the editor. The back-stack name is also the toolbar title -- the listener
     * above sets it after the editor has set its own -- so it must say "Edit Record": callers used
     * to pass "New Record", which also made tapping New Record in the drawer do nothing while
     * editing, as though it were already open.
     */
    void editRecord(int logId) {
        newTempFragment(NewLogFragment.newInstance(logId + "", ""),
                activity.getResources().getString(R.string.edit_record));
    }

    /**
     * Whether the screen showing is a sub-screen: one opened by {@link #newTempFragment} (an
     * editor, New Category) rather than from the drawer. Only {@link #changeFragment} gives its
     * fragment a tag, and a fragment's tag survives rotation, which a flag here would not. The
     * back-stack name cannot tell them apart: New Record is both a drawer entry and a sub-screen.
     */
    boolean isOnSubScreen() {
        FragmentManager fragmentManager = ((AppCompatActivity) activity).getSupportFragmentManager();
        Fragment current = fragmentManager.findFragmentById(R.id.mainContentFragment);
        return fragmentManager.getBackStackEntryCount() > 1
                && current != null && current.getTag() == null;
    }

    /**
     * Leave the app from Back. This used to be finish() plus System.exit(0), and killing the
     * process also killed a Dropbox upload the last save had queued. The filter is cleared here
     * because the process exit is what used to clear it: a fresh start shows every record. (The
     * records views' period is cleared by the fresh MainActivity instead: see ViewedPeriod.)
     */
    void exitApp() {
        MainActivity.mFilterArray = null;
        activity.finish();
    }

    void newTempFragment(Fragment fragment, String backStackTag) {
        FragmentManager fragmentManager = ((AppCompatActivity) activity).getSupportFragmentManager();
        fragmentManager.beginTransaction()
                .replace(R.id.mainContentFragment, fragment)
                .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                .addToBackStack(backStackTag)
                .commit();
    }

    private MenuItem getCurrentMenuId(String currentFragTitle) {
        NavigationView navigationView = activity.findViewById(R.id.nav_view);
        Menu menu = navigationView.getMenu();
        MenuItem selectedMenuItem = menu.getItem(0);

        for (int i = 0; i < menu.size(); i++) {
            // if the item has a sb menu, iterate through it
            if (menu.getItem(i).hasSubMenu()) {
                Menu subMenu = menu.getItem(i).getSubMenu();
                for (int j = 0; j < subMenu.size(); j++) {
                    if (subMenu.getItem(j).getTitle().equals(currentFragTitle)) {
                        selectedMenuItem = subMenu.getItem(j);
                    }
                }
            } else if (menu.getItem(i).getTitle().equals(currentFragTitle)) {
                selectedMenuItem = menu.getItem(i);
            }
        }
        return selectedMenuItem;
    }


    /** Open Settings at its header list -- General, Data and sync, Accounts. */
    public void openSettings(final AppCompatActivity activity) {
        openSettings(activity, null, 0);
    }

    /**
     * Open Settings, optionally landing directly on one preference screen rather than on the
     * header list.
     *
     * <p>The reminder notification's Settings action uses this: a reminder is what the user was
     * looking at, so the reminder settings are what the button should open, not a menu they then
     * have to navigate. {@code fragmentName} must be one of the fragments
     * {@code SettingsActivity.ALLOWED_FRAGMENTS} lists -- anything else is refused,
     * deliberately, to stop other apps injecting a fragment into this activity.
     *
     * <p>Opened this way the screen *is* the activity, with no root list behind it, so Back
     * returns to wherever the user came from rather than to a settings list they never asked for.
     *
     * @param fragmentName fully-qualified fragment name, or null for the header list
     * @param titleRes     string resource for the toolbar title, or 0 to leave it to the activity
     */
    public void openSettings(final AppCompatActivity activity, final String fragmentName,
                             final int titleRes) {
        Intent intent = new Intent(activity, SettingsActivity.class);
        if (fragmentName != null) {
            intent.putExtra(SettingsActivity.EXTRA_FRAGMENT, fragmentName);
            if (titleRes != 0) {
                intent.putExtra(SettingsActivity.EXTRA_FRAGMENT_TITLE_RES, titleRes);
            }
        }
        activity.startActivity(intent);
    }


    void setToolbarTitle(String newTitle) {
        Toolbar toolbar = activity.findViewById(R.id.toolbar);
        toolbar.setTitle(newTitle);
    }

    String getToolbarTitle() {
        String pageTitle = "Default";

        // catch error if not on a page with a toolbar title
        try {
            Toolbar toolbar = activity.findViewById(R.id.toolbar);
            pageTitle = toolbar.getTitle().toString();
        } catch (Exception e) {
            e.printStackTrace();
        }

        return pageTitle;
    }

    void clearSoftKeyboard() {
        View view = activity.getCurrentFocus();
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    MenuItem getMenuItem(int menuItemId) {
        NavigationView navigationView = activity.findViewById(R.id.nav_view);
        return navigationView.getMenu().findItem(menuItemId);
    }

    /**
     * Checks if the a page that needs to be refreshed is currently being viewed
     *
     * @return should the page be refreshed
     */
    boolean shouldRefresh() {
        // TODO
        return false;
    }
}
