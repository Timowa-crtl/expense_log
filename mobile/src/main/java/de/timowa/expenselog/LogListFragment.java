package de.timowa.expenselog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.FontAwesomeIcons;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.databinding.FragmentLogListBinding;

import static androidx.core.content.ContextCompat.getColor;

/**
 * Show list of records.
 *
 * <p>A tap opens a record for editing. A long press starts selection mode, where taps tick and
 * untick records and the selection bar deletes them all at once, with an Undo.
 *
 * <p>A repeating entry is only ever selected on its own, because deleting one needs the
 * "only this / this and following / the whole series" choice, which a mixed selection cannot
 * ask. Long-pressing one selects it alone, and Delete asks that question; a repeating entry
 * cannot join a selection, Select all leaves them out, and nothing joins a repeating one.
 */

public class LogListFragment extends BaseFragment implements Button.OnClickListener {

    private FragmentLogListBinding binding;
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_RECORDS_MODE = "recordsMode";
    private static final String ARG_NAV_SECTION = "navSection";
    private static final String ARG_PAGE_INDEX = "pageIndex";
    private static final String ARG_FILTER = "recordsFilter";
    private static final String STATE_SELECTED = "selectedRecords";

    private int records_mode;
    private int nav_section;
    private int page_index;
    private String records_filter = "";
    private boolean moreThanOneAccount = false;
    private long dateRangeStart = 0;
    private long dateRangeEnd = 9223372017126000L;

    private List<LogItem> logList;
    private MyAdapter mAdapter;

    private final BackgroundLoad load = new BackgroundLoad();
    private final RecordSelection selection = new RecordSelection();
    private SelectionBar selectionBar;
    /** A selection saved across recreation, applied once the list has loaded. */
    private int[] pendingSelection;

    RecyclerView mRecyclerView;
    TextView expenseTotalTextView;
    TextView incomeTotalTextView;

    @Inject
    Navigator navigator;
    @Inject
    DBAdapter dbAdapter;
    @Inject
    Utility utility;
    @Inject
    PrefManager prefManager;

    /**
     * @param recordsMode Parameter 1.
     * @param navMode     Parameter 2.
     * @return A new instance of fragment HomeFragment.
     */
    public static LogListFragment newInstance(int recordsMode, int navMode, int pageIndex, String filter) {
        LogListFragment fragment = new LogListFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_RECORDS_MODE, recordsMode);
        args.putInt(ARG_NAV_SECTION, navMode);
        args.putInt(ARG_PAGE_INDEX, pageIndex);
        args.putString(ARG_FILTER, filter);
        fragment.setArguments(args);
        return fragment;
    }

    public LogListFragment() {
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
        if (savedInstanceState != null)
            pendingSelection = savedInstanceState.getIntArray(STATE_SELECTED);

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        binding = FragmentLogListBinding.inflate(inflater, container, false);
        View view = binding.getRoot();
        mRecyclerView = binding.recyclerViewLogList;
        expenseTotalTextView = binding.textViewListExpense;
        incomeTotalTextView = binding.textViewListIncome;

        return view;
    }

    @Override
    public void onClick(View v) {

    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        this.initialize();

        if (BuildConfig.DEBUG) Log.i("SUMM", "log list page created: " + page_index);

        // use a linear layout manager
        RecyclerView.LayoutManager mLayoutManager = new LinearLayoutManager(requireActivity().getApplicationContext());
        mRecyclerView.setLayoutManager(mLayoutManager);
        if (BuildConfig.DEBUG)
            Log.i("CONTEXT", "mRecyclerView: " + mRecyclerView.getId());

        mRecyclerView.addItemDecoration(new DividerItemDecoration(requireActivity(), DividerItemDecoration.VERTICAL_LIST));
        mRecyclerView.setItemAnimator(new DefaultItemAnimator());

        // get list of logs and apply to adapter on a separate thread
        // Captured on the main thread: the background half must not touch the fragment.
        final Context context = requireContext().getApplicationContext();
        final android.content.SharedPreferences prefsFile = prefManager.getPrefsFile();
        load.run(() -> getLogList(prefsFile, context), this::onLogsLoaded);

        // set more than one account flag if accounts should be shown on list
        try (Cursor accounts = dbAdapter.getAccounts()) {
            moreThanOneAccount = accounts.getCount() > 1;
        }
    }

    /** The loaded records, back on the main thread. */
    private void onLogsLoaded(List<LogItem> result) {
        if (binding == null) return;

        // specify an adapter
        logList = result;
        mAdapter = new MyAdapter(result);
        mRecyclerView.setAdapter(mAdapter);

        // show totals ( must be called after dates are initiated in getLogList()
        showExpenseIncomeTotals();

        if (pendingSelection != null) {
            selection.addAll(pendingSelection);
            pendingSelection = null;
            selection.retainAll(logList);
            if (getUserVisibleHint())
                updateSelectionMode();
            else
                selection.clear();
        }
    }

    private void showExpenseIncomeTotals() {
        // get total expenses
        String expensesFilter = records_filter + DBAdapter.KEY_EXPENSE_INCOME + " = 0 AND ";
        double totalExpenses =
                dbAdapter.getSumForRange(dateRangeStart, dateRangeEnd, DBAdapter.KEY_AMOUNT, expensesFilter) * -1;
        expenseTotalTextView.setText(prefManager.formatMoney(totalExpenses));

        // get total income
        String incomeFilter = records_filter + DBAdapter.KEY_EXPENSE_INCOME + " = 1 AND ";
        double totalIncomes =
                dbAdapter.getSumForRange(dateRangeStart, dateRangeEnd, DBAdapter.KEY_AMOUNT, incomeFilter);
        incomeTotalTextView.setText(prefManager.formatMoney(totalIncomes));
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putIntArray(STATE_SELECTED, pendingSelection != null ? pendingSelection : selection.toArray());
    }

    /** An Undo, here or on a screen above, brought records back. */
    private final Runnable onRecordsChanged = () -> {
        if (getView() != null)
            reloadList();
    };

    @Override
    public void onResume() {
        super.onResume();
        RecordsChanged.listen(onRecordsChanged);
    }

    @Override
    public void onPause() {
        super.onPause();
        RecordsChanged.stopListening(onRecordsChanged);
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        // Paged away: the selection bar belongs to the page it was started on.
        if (!isVisibleToUser)
            endSelection();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        load.cancel();
        binding = null;
        if (BuildConfig.DEBUG) Log.i("CONTEXT", "log list destroy ");
        endSelection();
        mRecyclerView.setAdapter(null);
        mAdapter = null;
    }

    private List<LogItem> getLogList(SharedPreferences prefs, Context ctx) {
        String SELECTION = "((" +
                DBAdapter.KEY_ROW_ID + " NOTNULL) AND (" +
                DBAdapter.KEY_ROW_ID + " != '' ))";

        String SORT_ORDER = getSortOrder(prefManager.getListSortSetting());

        if (records_mode == LogTabsFragment.KEY_RECORDS_MODE_ALL) {
            dateRangeStart = 0;
            dateRangeEnd = 9223372017126000L;
        } else {
            dateRangeStart = LogTabsFragment.getPeriodStart(records_mode, page_index, ctx,
                    prefs).getTimeInMillis();
            dateRangeEnd = LogTabsFragment.getPeriodEnd(records_mode, page_index, ctx,
                    prefs).getTimeInMillis();

            // Inclusive at both ends, like the totals' BETWEEN: a period's bounds are its own first
            // and last millisecond, so an exclusive bound dropped a record on either from the list
            // while the totals above it still counted it.
            SELECTION = "((" + DBAdapter.KEY_LOG_TIME + " >= " + dateRangeStart + ") " +
                    "AND (" + DBAdapter.KEY_LOG_TIME + " <= " + dateRangeEnd + " ))";
        }
        if (records_filter != null) {
            SELECTION = records_filter + SELECTION;
        }

        Cursor tagListCursor = dbAdapter.getLogs(SELECTION, SORT_ORDER);
        LogItem item;
        List<LogItem> tempLogList = new ArrayList<>();

        if (tagListCursor != null) {
            while (tagListCursor.moveToNext()) {
                item = new LogItem();
                item.setId(tagListCursor.getInt(DBAdapter.COLUMN_LOG_ID));
                item.setTimeStamp(tagListCursor.getLong(DBAdapter.COLUMN_LOG_TIME));
                item.setAmount(tagListCursor.getDouble(DBAdapter.COLUMN_LOG_AMOUNT));
                item.setAccountId(tagListCursor.getInt(DBAdapter.COLUMN_LOG_ACCOUNT));
                item.setCategory(tagListCursor.getInt(DBAdapter.COLUMN_LOG_CATEGORY));
                item.setNotes(tagListCursor.getString(DBAdapter.COLUMN_LOG_NOTES));
                item.setExpenseIncome(tagListCursor.getInt(DBAdapter.COLUMN_LOG_EXPENSE_INCOME));
                item.setRepeatingId(tagListCursor.getInt(DBAdapter.COLUMN_LOG_REPEATING_ID));
                tempLogList.add(item);
            }
        }
        return tempLogList;
    }

    private String getSortOrder(int listSortSetting) {
        String orderString = "" + DBAdapter.KEY_LOG_TIME + " ASC";
        switch (listSortSetting) {
            case 0: // date low
                orderString = "" + DBAdapter.KEY_LOG_TIME + " ASC";
                break;
            case 1: // date high
                orderString = "" + DBAdapter.KEY_LOG_TIME + " DESC";
                break;
            case 2: // amount low
                orderString = "" + DBAdapter.KEY_AMOUNT + " ASC";
                break;
            case 3: // amount high
                orderString = "" + DBAdapter.KEY_AMOUNT + " DESC";
                break;
        }
        return orderString;
    }

    private void initialize() {
        this.getComponent(ActivityComponent.class).inject(this);
    }

    public class MyAdapter extends RecyclerView.Adapter<MyAdapter.ViewHolder> {
        private List<LogItem> tagItemList;

        public class ViewHolder extends RecyclerView.ViewHolder {
            // each data item is just a string in this case
            TextView mTextViewLogAmount;
            TextView mTextViewMainText;
            TextView mTextViewLogDate;
            TextView mTextViewSubText;
            ImageView mImageViewIcon;
            ImageView mImageViewRepeatingIcon;
            LinearLayout mItemLayout;

            ViewHolder(View v) {
                super(v);
                mItemLayout = v.findViewById(R.id.layout_logListItem);
                mTextViewLogAmount = v.findViewById(R.id.textView_logAmount);
                mTextViewMainText = v.findViewById(R.id.textView_mainNotes);
                mTextViewLogDate = v.findViewById(R.id.textView_logDate);
                mTextViewSubText = v.findViewById(R.id.textView_logCategory);
                mImageViewIcon = v.findViewById(R.id.imageView_logIcon);
                mImageViewRepeatingIcon = v.findViewById(R.id.imageView_repeating);
                v.setOnClickListener(view -> onRecordClicked(getAdapterPosition()));
                v.setOnLongClickListener(view -> onRecordLongClicked(getAdapterPosition()));
            }
        }

        // Provide a suitable constructor (depends on the kind of dataset)
        MyAdapter(List<LogItem> tagItemList) {
            this.tagItemList = tagItemList;
        }

        // Create new views (invoked by the layout manager)
        @NonNull
        @Override
        public MyAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                       int viewType) {
            // create a new view
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.log_list_item, parent, false);
            return new ViewHolder(v);
        }

        // Replace the contents of a view (invoked by the layout manager)
        @Override
        public void onBindViewHolder(final ViewHolder holder, final int position) {
            final LogItem logItem = tagItemList.get(position);

            String moneyFormatted = prefManager.formatMoney(logItem.getAmount());
            holder.mTextViewLogAmount.setText(moneyFormatted);

            // do repeating log check and init
            if (logItem.getRepeatingId() > -1) {
                IconDrawable tempIcon = new IconDrawable(getActivity(), FontAwesomeIcons.fa_history);
                holder.mImageViewRepeatingIcon.setImageDrawable(tempIcon);
                holder.mImageViewRepeatingIcon.setVisibility(View.VISIBLE);
            } else {
                holder.mImageViewRepeatingIcon.setVisibility(View.GONE);
            }

            // set background based on expense or income
            if (logItem.getExpenseIncome() == 0) {
                holder.mTextViewLogAmount.setTextColor(getColor(requireActivity(), R.color.expenseColor));
            } else {
                holder.mTextViewLogAmount.setTextColor(getColor(requireActivity(), R.color.incomeColor));
            }

            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(logItem.getTimeStamp());
            holder.mTextViewLogDate.setText(prefManager.getDateFormat().format(c.getTime()));

            String subTextString = "";

            // if accounts flag is set, show accounts label
            if (moreThanOneAccount) {
                subTextString = dbAdapter.getAccountLabel(logItem.getAccountId());
            }

            Cursor tagCursor = dbAdapter.getTag(logItem.getCategory());
            if (tagCursor != null) {
                if (tagCursor.getCount() > 0) {
                    tagCursor.moveToFirst();

                    // if no notes are set, make main line category
                    if (logItem.getNotes().equals("")) {
                        holder.mTextViewMainText.setText(tagCursor.getString(DBAdapter.COLUMN_TAG_LABEL));
                    } else {
                        if (!subTextString.equals(""))
                            subTextString += " - ";

                        subTextString += tagCursor.getString(DBAdapter.COLUMN_TAG_LABEL);
                        holder.mTextViewMainText.setText(logItem.getNotes());
                    }

                    try {
                        IconDrawable icon = new IconDrawable(getActivity(), tagCursor.getString(DBAdapter.COLUMN_TAG_ICON));
                        holder.mImageViewIcon.setImageDrawable(icon);
                        if (BuildConfig.DEBUG)
                            Log.i("LogListFragment", "icon: " + tagCursor.getString(DBAdapter.COLUMN_TAG_ICON));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                tagCursor.close();
            }

            if (subTextString.equals("")) {
                holder.mTextViewSubText.setVisibility(View.GONE);
            } else {
                holder.mTextViewSubText.setVisibility(View.VISIBLE);
                holder.mTextViewSubText.setText(subTextString);
            }

            holder.mItemLayout.setTag(logItem);
            holder.itemView.setActivated(selection.contains(logItem.getId()));
        }

        // Return the size of your dataset (invoked by the layout manager)
        @Override
        public int getItemCount() {
            return (null != tagItemList ? tagItemList.size() : 0);
        }

    }

    private void onRecordClicked(int position) {
        if (position == RecyclerView.NO_POSITION || logList == null)
            return;
        if (selection.isActive())
            toggleRecord(position);
        else
            navigator.editRecord(logList.get(position).getId());
    }

    private boolean onRecordLongClicked(int position) {
        if (position == RecyclerView.NO_POSITION || logList == null)
            return false;
        toggleRecord(position);
        return true;
    }

    /** Ticks or unticks a record, unless that would put a repeating entry in company. */
    private void toggleRecord(int position) {
        LogItem record = logList.get(position);
        if (!selection.contains(record.getId()) && selection.isActive()) {
            if (isRepeating(record)) {
                utility.snackBarMessage(getString(R.string.selection_no_repeating));
                return;
            }
            if (selection.holdsRepeating(logList)) {
                utility.snackBarMessage(getString(R.string.selection_repeating_alone));
                return;
            }
        }
        selection.toggle(record.getId());
        mAdapter.notifyItemChanged(position);
        updateSelectionMode();
    }

    private static boolean isRepeating(LogItem record) {
        return record.getRepeatingId() > -1;
    }

    /** Starts, updates or ends the selection bar to match the selection. */
    private void updateSelectionMode() {
        if (!selection.isActive()) {
            if (selectionBar != null)
                selectionBar.finish();
            return;
        }
        int count = selection.size();
        // Select all has nothing to add to a repeating entry, which stays alone.
        bar().show(getResources().getQuantityString(R.plurals.records_selected, count, count),
                getString(R.string.records_selected_total, prefManager.formatMoney(selection.total(logList))),
                !selection.holdsRepeating(logList));
    }

    private void endSelection() {
        if (selectionBar != null && selectionBar.isShowing())
            selectionBar.finish();
        else
            selection.clear();
    }

    private SelectionBar bar() {
        if (selectionBar == null)
            selectionBar = new SelectionBar((AppCompatActivity) requireActivity(), new SelectionBar.Listener() {
                @Override
                public void onSelectAll() {
                    int skipped = selection.selectAllPlain(logList);
                    mAdapter.notifyItemRangeChanged(0, logList.size());
                    updateSelectionMode();
                    if (skipped > 0)
                        utility.snackBarMessage(getResources().getQuantityString(
                                R.plurals.selection_repeating_skipped, skipped, skipped));
                }

                @Override
                public void onDelete() {
                    deleteSelected();
                }

                @Override
                public void onEnded() {
                    selection.clear();
                    if (mAdapter != null && logList != null)
                        mAdapter.notifyItemRangeChanged(0, logList.size());
                }
            });
        return selectionBar;
    }

    private void deleteSelected() {
        if (selection.holdsRepeating(logList)) {
            // Alone, by construction; the selection stays until a choice is made, so Cancel keeps it.
            LogItem record = selection.selected(logList).get(0);
            RecurringScope.confirmDelete(requireActivity(), dbAdapter, utility, prefManager, record, () -> {
                if (getView() != null) {
                    endSelection();
                    reloadList();
                }
            });
            return;
        }
        final DBAdapter.DeletedLogs deleted = dbAdapter.deleteLogs(selection.ids());
        endSelection();
        reloadList();
        DeleteUndo.offer(utility, getResources(), dbAdapter, deleted);
    }

    /** Reads the page's records again, keeping the list's scroll position. */
    // Any records may have gone or come back, anywhere in the list: a full refresh is the change.
    @SuppressLint("NotifyDataSetChanged")
    private void reloadList() {
        if (logList == null || mAdapter == null)
            return;
        List<LogItem> fresh = getLogList(prefManager.getPrefsFile(), requireActivity());
        logList.clear();
        logList.addAll(fresh);
        mAdapter.notifyDataSetChanged();
        showExpenseIncomeTotals();
    }

}
