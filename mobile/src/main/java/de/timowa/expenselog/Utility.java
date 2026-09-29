package de.timowa.expenselog;

import android.app.Activity;
import android.util.Log;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import java.util.Calendar;

import javax.inject.Inject;

/**
 * Various general useful methods
 */
public class Utility {
    private Activity activity;

    @Inject
    public Utility(final Activity passedActivity) {
        this.activity = passedActivity;
    }

    void snackBarMessage(String snackMsg) {
        // if on screen with coordinator layout
        if (activity.findViewById(R.id.coordinator_layout) != null)
            Snackbar.make(activity.findViewById(R.id.coordinator_layout), snackMsg, Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show();
    }

    /** A snackbar with one action, shown for {@code durationMs}; does nothing off a coordinator screen. */
    void snackBarAction(String snackMsg, int actionRes, int durationMs, Runnable action) {
        if (activity.findViewById(R.id.coordinator_layout) != null)
            Snackbar.make(activity.findViewById(R.id.coordinator_layout), snackMsg, Snackbar.LENGTH_LONG)
                    .setDuration(durationMs)
                    .setAction(actionRes, v -> action.run())
                    .show();
    }

    void toastMessage(String toastMsg) {
        Toast.makeText(activity, toastMsg, Toast.LENGTH_SHORT).show();
    }

    public static void log(String logTag, String logMsg) {
        if (BuildConfig.DEBUG) Log.i(logTag, logMsg);
    }

    /**
     * Return the budget for the time period. For example, if the period is 1 month but the user
     * is viewing a week, adjust the budget to be 1/4 of the base amount
     *
     * @param budgetAmount The budget amount for the given period
     * @param budgetPeriod The period by which the amount is set
     * @param records_mode
     * @param dateRangeStart
     * @return return the adjusted amount
     */
    public static double getTimeAdjustedBudget(double budgetAmount, int budgetPeriod, int records_mode, long dateRangeStart) {
        final int DAILY_BUDGET = 0;
        final int WEEKLY_BUDGET = 1;
        final int MONTHLY_BUDGET = 2;
        final int YEARLY_BUDGET = 3;
        // todo account for different cases
        switch (records_mode) {
            case LogTabsFragment.KEY_RECORDS_MODE_YEAR:
                switch (budgetPeriod) {
                    case DAILY_BUDGET:
                        budgetAmount = budgetAmount * 365;
                        break;
                    case WEEKLY_BUDGET:
                        budgetAmount = budgetAmount * 52;
                        break;
                    case MONTHLY_BUDGET:
                        budgetAmount = budgetAmount * 12;
                        break;
                }
                break;
            case LogTabsFragment.KEY_RECORDS_MODE_MONTH:
                switch (budgetPeriod) {
                    case DAILY_BUDGET:
                        budgetAmount = budgetAmount * getNumOfDaysInCurrentMonth(dateRangeStart);
                        break;
                    case WEEKLY_BUDGET:
                        budgetAmount = budgetAmount / 7 * getNumOfDaysInCurrentMonth(dateRangeStart);
                        break;
                    case YEARLY_BUDGET:
                        budgetAmount = budgetAmount / 12;
                        break;
                }
                break;
            case LogTabsFragment.KEY_RECORDS_MODE_WEEK:
                switch (budgetPeriod) {
                    case DAILY_BUDGET:
                        budgetAmount = budgetAmount * 7;
                        break;
                    case MONTHLY_BUDGET:
                        budgetAmount = budgetAmount / 4;
                        break;
                    case YEARLY_BUDGET:
                        budgetAmount = budgetAmount / 52;
                        break;
                }
                break;
            case LogTabsFragment.KEY_RECORDS_MODE_DAY:
                switch (budgetPeriod) {
                    case WEEKLY_BUDGET:
                        budgetAmount = budgetAmount / 7;
                        break;
                    case MONTHLY_BUDGET:
                        budgetAmount = budgetAmount / getNumOfDaysInCurrentMonth(dateRangeStart);
                        break;
                    case YEARLY_BUDGET:
                        budgetAmount = budgetAmount / 365;
                        break;
                }
                break;
        }
        return budgetAmount;
    }

    private static int getNumOfDaysInCurrentMonth(long dateRangeStart) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(dateRangeStart);
        if (BuildConfig.DEBUG)
            Log.i("BUDG", "days in month: " + c.getActualMaximum(Calendar.DAY_OF_MONTH));
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }
}
