package de.timowa.expenselog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import de.timowa.expenselog.icons.IconDrawable;

import java.util.ArrayList;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.databinding.FragmentSummaryBinding;

import static androidx.core.content.ContextCompat.getColor;

/**
 * Fragment that shows breakdown of entries for given time period
 */
public class SummaryFragment extends BaseFragment {
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    public static final String ARG_RECORDS_MODE = "recordsMode";
    public static final String ARG_NAV_SECTION = "navSection";
    public static final String ARG_PAGE_INDEX = "pageIndex";
    public static final String ARG_FILTER = "recordsFilter";

    private int records_mode;
    private int nav_section;
    private int page_index;
    private String records_filter = "";
    private long dateRangeStart = 0;
    private long dateRangeEnd = 9223372017126000L;
    private double totalAmount;

     FragmentSummaryBinding binding;

    @Inject
    DBAdapter dbAdapter;

    @Inject
    PrefManager prefManager;

    /**
     * @param recordsMode Parameter 1.
     * @param navMode     Parameter 2.
     * @return A new instance of fragment HomeFragment.
     */
    public static SummaryFragment newInstance(int recordsMode, int navMode, int pageIndex, String filter) {
        SummaryFragment fragment = new SummaryFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_RECORDS_MODE, recordsMode);
        args.putInt(ARG_NAV_SECTION, navMode);
        args.putInt(ARG_PAGE_INDEX, pageIndex);
        args.putString(ARG_FILTER, filter);
        fragment.setArguments(args);
        return fragment;
    }

    public SummaryFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            this.records_mode = getArguments().getInt(ARG_RECORDS_MODE);
            this.nav_section = getArguments().getInt(ARG_NAV_SECTION);
            this.page_index = getArguments().getInt(ARG_PAGE_INDEX);
            this.records_filter = getArguments().getString(ARG_FILTER);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        binding = FragmentSummaryBinding.inflate(inflater, container, false);

        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.initialize();
    }

    @Override
    public void onResume() {
        super.onResume();

        if (BuildConfig.DEBUG) Log.i("SUMM", "summary page created: " + page_index);

        // set date ranges before doing any calculations
        setDateRange();

        // make a row and show total for each category
        initCategories(requireContext());
;
        // get the totals on a separate thread
        initTotals();

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
        if (BuildConfig.DEBUG) Log.i("LEAK", "summary destroy ");
    }

    private void initializeBudgetInfo() {
        // if viewing All Records, don't do budget stuff
        if (records_mode != LogTabsFragment.KEY_RECORDS_MODE_ALL) {

            // if only one account exists, don't bother checking the filter
            Cursor accountsCursor = dbAdapter.getAccounts();
            // more than one account, check filter
            if (accountsCursor != null) {
                if (accountsCursor.getCount() > 1) {
                    // if no filter is set and more than one account exists, skip showing budget info
                    if (MainActivity.mFilterArray != null) {
                        // get the active filter and check if only one account is active
                        ArrayList<ArrayList<Integer>> newFilter = MainActivity.mFilterArray;
                        ArrayList<Integer> accountsArray = newFilter.get(1);
                        // only one account in filter selected
                        if (accountsArray.size() == 1) {
                            int accountId = accountsArray.get(0);
                            // check if budget settings are set
                            if (prefManager.isBudgetEnabled(accountId))
                                showBudgetInfo(accountId);
                        }
                    }
                } else {
                    // only one account exists
                    accountsCursor.moveToFirst();
                    int accountID = accountsCursor.getInt(DBAdapter.COLUMN_ACCOUNT_ID);
                    if (prefManager.isBudgetEnabled(accountID))
                        showBudgetInfo(accountID);
                }
                accountsCursor.close();
            }
        }
    }

    private void showBudgetInfo(int accountId) {
        // find the appropriate budget amount for the period being viewed and the budget setting
        binding.layoutBudget.setVisibility(View.VISIBLE);
        double budgetAmount = prefManager.getBudgetAmount(accountId);
        int budgetPeriod = prefManager.getBudgetPeriod(accountId);
        budgetAmount = Utility.getTimeAdjustedBudget(budgetAmount, budgetPeriod, records_mode, dateRangeStart);
        binding.textViewBudgetAmount.setText(prefManager.formatMoney(budgetAmount));
        double budgetDifference = budgetAmount + totalAmount;
        binding.textViewBudgetDifference.setText(prefManager.formatMoney(budgetDifference));
    }

    private void setDateRange() {
        if (records_mode == LogTabsFragment.KEY_RECORDS_MODE_ALL) {
            dateRangeStart = 0;
            dateRangeEnd = 9223372017126000L;
        } else {
            if (isAdded()) { //  only do this is the fragment is added
                dateRangeStart = LogTabsFragment.getPeriodStart(records_mode, page_index, getActivity(),
                        prefManager.getPrefsFile()).getTimeInMillis();
                dateRangeEnd = LogTabsFragment.getPeriodEnd(records_mode, page_index, getActivity(),
                        prefManager.getPrefsFile()).getTimeInMillis();
            }
        }
    }

    private void initTotals() {
        // get the totals

        // get total expenses
        String expensesFilter = records_filter + DBAdapter.KEY_EXPENSE_INCOME + " = 0 AND ";
        double totalExpenses =
                dbAdapter.getSumForRange(dateRangeStart, dateRangeEnd, DBAdapter.KEY_AMOUNT, expensesFilter) * -1;

        // get total incomes
        String incomeFilter = records_filter + DBAdapter.KEY_EXPENSE_INCOME + " = 1 AND ";
        double totalIncomes = dbAdapter.getSumForRange(dateRangeStart, dateRangeEnd, DBAdapter.KEY_AMOUNT, incomeFilter);

        // total
        totalAmount = dbAdapter.getTotalForRange(dateRangeStart, dateRangeEnd, records_filter);

        // if either expense or incomes total is 0, there is no value in showing the breakdown so hide it
        if (totalExpenses == 0 || totalIncomes == 0) {
            binding.layoutExpenseTotal.setVisibility(View.GONE);
            binding.layoutIncomeTotal.setVisibility(View.GONE);
            binding.viewDividerBreakdown.setVisibility(View.GONE);
        } else {
            binding.textViewExpenseTotal.setText(prefManager.formatMoney(totalExpenses));
            binding.textViewIncomeTotal.setText(prefManager.formatMoney(totalIncomes));
        }

        // set the total
        String total = "" + prefManager.formatMoney(totalAmount);
        binding.textViewTotal.setText(total);

        // show budget info
        // must be done after totals are initialized
        initializeBudgetInfo();
    }

    private void initCategories(Context context) {
        // add a row for each category showing it's name and total
        // get unique categories in range
        Cursor uniqueCategories = dbAdapter.getAllUniqueCategoryIdsInRange(dateRangeStart, dateRangeEnd, records_filter);
        if (isAdded()) {
            if (uniqueCategories != null) {
                for (int i = 0; i < uniqueCategories.getCount(); i++) {
                    if (BuildConfig.DEBUG) Log.i("SUMM", "cat row: " + i);
                    uniqueCategories.moveToNext();
                    // go through list. for each item get total and title and inflate a row showing that information
                    // add category filter to records filter

                    int currentCatId = uniqueCategories.getInt(0);
                    String catIcon = uniqueCategories.getString(2);
                    String tempFilter = DBAdapter.KEY_CATEGORY_ID + " = " + currentCatId + " AND ";

                    // if main filter is set, add an and
                    if (records_filter == null)
                        records_filter = "";

                    tempFilter += records_filter;

                    double amount = dbAdapter.getTotalForRange(dateRangeStart, dateRangeEnd, tempFilter);
                    LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    @SuppressLint("InflateParams")
                    LinearLayout categoryLayout = (LinearLayout) inflater.inflate(R.layout.summary_category_row, null);

                    TextView labelTextView = categoryLayout.findViewById(R.id.textView_summaryCategoryName);
                    TextView amountTextView = categoryLayout.findViewById(R.id.textView_summaryCategoryAmount);

                    IconDrawable tempIcon = new IconDrawable(getActivity(), catIcon);
                    Drawable d = tempIcon.mutate();
                    d.setBounds(3, 0, 60, 60);
                    labelTextView.setCompoundDrawables(d, null, null, null);
                    labelTextView.setCompoundDrawablePadding(26);

                    if (amount > 0) {
                        labelTextView.setTextColor(getColor(getActivity(), R.color.incomeColor));
                        amountTextView.setTextColor(getColor(getActivity(), R.color.incomeColor));
                    } else {
                        labelTextView.setTextColor(getColor(getActivity(), R.color.expenseColor));
                        amountTextView.setTextColor(getColor(getActivity(), R.color.expenseColor));
                    }

                    labelTextView.setText(dbAdapter.getCategoryLabel(currentCatId));
                    amountTextView.setText(prefManager.formatMoney(amount));
                    binding.linearLayoutSummaryCategoryContainer.addView(categoryLayout);
                }
                uniqueCategories.close();
            }
        }
        if (uniqueCategories != null)
            uniqueCategories.close();
    }

    private void initialize() {
        this.getComponent(ActivityComponent.class).inject(this);
    }

}
