package de.timowa.expenselog;

import android.content.Context;

import javax.inject.Inject;

import de.timowa.expenselog.reminders.ReminderManager;

public class LaunchManager {

    private Context context;

    @Inject
    PrefManager prefManager;

    @Inject
    DBAdapter dbAdapter;

    @Inject
    LaunchManager(final Context context) {
        this.context = context;
    }

    void appStartup() {

        // start reminder manager
        ReminderManager.updateReminder(context);

        // if first time launching
        if (prefManager.firstRunCheck()) {
            // initialize categories
            setupDefaultCategories();

            setupDefaultAccount();

            prefManager.setDefaultTimeFormat();
        }
    }

       private void setupDefaultAccount() {
        dbAdapter.newAccount(context.getString(R.string.personal));
    }

    private void setupDefaultCategories() {
        String[] mCategoryArrayExpenses = context.getResources().getStringArray(R.array.defaultExpenses);
        String[] mCategoryIconsArrayExpenses = context.getResources().getStringArray(R.array.defaultExpenseIcons);
        for (int i = 0; i < mCategoryArrayExpenses.length; i++) {
            dbAdapter.newTag(mCategoryArrayExpenses[i],0, mCategoryIconsArrayExpenses[i]);
        }

        String[] mCategoryArrayIncome = context.getResources().getStringArray(R.array.defaultIncomes);
        String[] mCategoryIconsArrayIncome = context.getResources().getStringArray(R.array.defaultIncomeIcons);
        for (int i = 0; i < mCategoryArrayIncome.length; i++) {
            dbAdapter.newTag(mCategoryArrayIncome[i],1, mCategoryIconsArrayIncome[i]);
        }
    }
}
