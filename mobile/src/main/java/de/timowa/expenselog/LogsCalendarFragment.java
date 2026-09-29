package de.timowa.expenselog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.FontAwesomeIcons;

import java.util.Calendar;
import java.util.List;

import javax.inject.Inject;

import de.timowa.expenselog.Calendar.CalendarAdapter;
import de.timowa.expenselog.Calendar.CalendarDay;
import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.databinding.FragmentCalendarBinding;

import static androidx.core.content.ContextCompat.getColor;

public class LogsCalendarFragment extends BaseFragment implements AdapterView.OnItemClickListener {

    private FragmentCalendarBinding binding;
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_RECORDS_MODE = "recordsMode";
    private static final String ARG_NAV_SECTION = "navSection";
    private static final String ARG_PAGE_INDEX = "pageIndex";
    private static final String ARG_FILTER = "recordsFilter";

    private int records_mode;
    private int nav_section;
    private int page_index;
    private String records_filter;

    @Inject
    Navigator navigator;

    @Inject
    DBAdapter dbAdapter;

    @Inject
    Utility utility;

    @Inject
    PrefManager prefManager;

    GridView gridViewCalendar;

    private final BackgroundLoad load = new BackgroundLoad();
    private CalendarAdapter mAdapter;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param recordsMode Parameter 0.
     * @param navMode     Parameter 1.
     * @param pageIndex   Parameter 2.
     * @return A new instance of fragment RecordsGraphFragment.
     */
    public static LogsCalendarFragment newInstance(int recordsMode, int navMode, int pageIndex, String filter) {
        LogsCalendarFragment fragment = new LogsCalendarFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_RECORDS_MODE, recordsMode);
        args.putInt(ARG_NAV_SECTION, navMode);
        args.putInt(ARG_PAGE_INDEX, pageIndex);
        args.putString(ARG_FILTER, filter);
        fragment.setArguments(args);
        return fragment;
    }

    public LogsCalendarFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            records_mode = getArguments().getInt(ARG_RECORDS_MODE);
            nav_section = getArguments().getInt(ARG_NAV_SECTION);
            page_index = getArguments().getInt(ARG_PAGE_INDEX);
            records_filter = getArguments().getString(ARG_FILTER);
        }

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        this.initialize();

        // Inflate the layout for this fragment
        binding = FragmentCalendarBinding.inflate(inflater, container, false);
        View view = binding.getRoot();
        gridViewCalendar = binding.gridViewCalendar;

        initializeCalendar();
        return view;
    }

    private void initializeCalendar() {
        if (records_filter == null)
            records_filter = "";

        // Everything the background load needs is captured here, on the main thread. The load
        // used to call requireActivity() from doInBackground, after an isAdded() check the pager
        // could invalidate in between: paging quickly detached the fragment first and crashed the
        // app with "not attached to an activity" (docs/history/RELIABILITY_PLAN.md, F26).
        Context appContext = requireContext().getApplicationContext();
        SharedPreferences prefs = prefManager.getPrefsFile();
        Calendar monthStart = LogTabsFragment.getPeriodStart(LogTabsFragment.KEY_RECORDS_MODE_MONTH,
                page_index, appContext, prefs);
        loadMonth(appContext, prefs, monthStart, records_filter);

        gridViewCalendar.setOnItemClickListener(this);
    }

    /**
     * Loads one month off the main thread.
     *
     * <p>Everything the background half needs is captured here, on the main thread: fast paging
     * detaches the fragment mid-load, and asking a detached fragment for its context throws.
     */
    private void loadMonth(Context appContext, SharedPreferences prefs, Calendar monthStart,
                           String filter) {
        load.run(() -> CalendarAdapter.loadMonth(appContext, monthStart, filter, prefs),
                days -> {
                    // binding, not gridViewCalendar: that field keeps pointing at the old view
                    // after onDestroyView, so it is never null once a month has loaded and the
                    // check was doing nothing. isAdded() is true for a fragment on the back stack
                    // whose view is gone, so it cannot carry this on its own either.
                    if (binding == null || !isAdded()) return;
                    mAdapter = new CalendarAdapter(requireActivity(), monthStart, filter, prefs, days);
                    gridViewCalendar.setAdapter(mAdapter);
                });
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        int day = position - 7;

        // prevent user from accessing days before they are initialized, or if the day header is being selected
        if (mAdapter != null && day >= 0) {
            AlertDialog alert = new calendarItemAlertDialog(requireActivity(), (CalendarDay) mAdapter.getItem(day));
            alert.show();
        }
    }

    private class calendarItemAlertDialog extends AlertDialog {
        @SuppressLint("InflateParams")
        calendarItemAlertDialog(final Context ctx, final CalendarDay dayObject) {
            super(ctx);

            if (dayObject.getDay() != 0) {

                LayoutInflater inflater = (LayoutInflater) ctx
                        .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                View tempParent = inflater.inflate(R.layout.records_info_dialog, null);
                final LinearLayout parent = tempParent.findViewById(R.id.eventDialogLayout);

                if (dayObject.getEvents().size() > 0) {

//                for each event add a view showing the events details
                    for (int i = 0; i < dayObject.getEvents().size(); i++) {
                        LogItem event = dayObject.getEvents().get(i);
                        final int eventId = event.getId();
                        View eventView = inflater.inflate(R.layout.records_info_item_dialog, null);

                        // set date and repeating icon
                        setDateView(eventView, event);

                        setAccountView(eventView, event);

                        setAmountView(eventView, event);

                        setCategoryView(eventView, event);

                        // set notes
                        setNotesView(eventView, event);

                        Button update = eventView.findViewById(R.id.button_update);
                        update.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View arg0) {
                                dismiss();
                                // Send to update record
                                navigator.editRecord(eventId);
                            }
                        });

                        Button delete = eventView.findViewById(R.id.button_delete);
                        delete.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View arg0) {
                                dismiss();
                                // warn user they're about to delete
                                deleteWarningDialog(event);

                            }
                        });

                        parent.addView(eventView, 0);
                    }

                } else {
                    setTitle(ctx.getResources().getString(R.string.no_records));
                }

                Button cancel = parent.findViewById(R.id.button_dialog_cancel);
                cancel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View arg0) {
                        dismiss();
                    }
                });

                Button newHeadache = parent.findViewById(R.id.button_dialog_new_headache);
                newHeadache.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View arg0) {
                        dismiss();
                        Calendar newEvent = Calendar.getInstance();
                        newEvent.set(dayObject.getYear(), dayObject.getMonth(), dayObject.getDay());
                        final long newEventMillis = newEvent.getTimeInMillis();
                        // send to new log with date
                        navigator.newTempFragment(NewLogFragment.newInstance("", newEventMillis + ""),
                                getResources().getString(R.string.new_record));
                    }
                });

                setView(tempParent);

            }
        }

        private void setCategoryView(View eventView, LogItem event) {
            TextView categoryText = eventView.findViewById(R.id.textView_dialog_category);
            categoryText.setText(dbAdapter.getCategoryLabel(event.getCategory()));
        }

        private void setAccountView(View eventView, LogItem event) {
            TextView accountsText = eventView.findViewById(R.id.textView_dialog_account);
            Cursor accountsCursor = dbAdapter.getAccounts();
            if (accountsCursor != null && accountsCursor.getCount() > 1) {
                accountsText.setText(dbAdapter.getAccountLabel(event.getAccountId()));
                accountsText.setVisibility(View.VISIBLE);
            } else {
                accountsText.setVisibility(View.GONE);
            }
        }

        private void setDateView(View eventView, LogItem event) {
            TextView dateText = eventView.findViewById(R.id.textView_dialog_date);
            Calendar startCal = Calendar.getInstance();
            startCal.setTimeInMillis(event.getTimeStamp());
            String dateTextString = prefManager.getTimeFormat().format(startCal.getTime());
            dateText.setText(dateTextString);

            // if even is repeating add repeat icon
            if (event.getRepeatingId() > -1) {
                IconDrawable tempIcon = new IconDrawable(requireActivity(), FontAwesomeIcons.fa_history);
                Drawable d = tempIcon.mutate();
                d.setBounds(0, 0, 40, 40);
                dateText.setCompoundDrawables(null, null, d, null);
                dateText.setCompoundDrawablePadding(22);
            }
        }

        private void setAmountView(View eventView, LogItem event) {
            TextView amountText = eventView.findViewById(R.id.textView_dialog_amount);
            String amount = prefManager.formatMoney(event.getAmount()) + "";
            if (event.getExpenseIncome() == 0) {
                amount = "-" + amount;
                amountText.setTextColor(getColor(requireActivity(), R.color.expenseColor));
            } else {
                amountText.setTextColor(getColor(requireActivity(), R.color.incomeColor));
            }
            amountText.setText(amount);
        }

        private void setNotesView(View eventView, LogItem event) {
            TextView notesText = eventView.findViewById(R.id.textView_dialog_notes);
            if (event.getNotes().equals("")) {
                notesText.setVisibility(View.GONE);
            } else {
                String notes = requireActivity().getResources().getString(R.string.notes) + ": " + event.getNotes();
                notesText.setText(notes);
            }
        }

        private void deleteWarningDialog(final LogItem event) {
            final int eventId = event.getId();
            if (event.getRepeatingId() > -1) {
                // it used to delete just this record, without asking about the rest of its series
                RecurringScope.confirmDelete(requireActivity(), dbAdapter, utility, prefManager, event, () -> {
                    if (getView() != null && mAdapter != null)
                        refreshCalendar();
                });
                return;
            }
            Builder myAlertDialog = new Builder(requireActivity());
            myAlertDialog.setMessage(requireActivity().getResources().getString(R.string.delete_log_warning));
            myAlertDialog.setPositiveButton(requireActivity().getResources().getString(R.string.menu_delete),
                    new OnClickListener() {
                        public void onClick(DialogInterface arg0, int arg1) {
                            // delete
                            if (dbAdapter.deleteLog(eventId)) {
                                utility.snackBarMessage(getResources().getString(R.string.record_deleted));

                                // update calendar
                                refreshCalendar();
                            }
                        }
                    });
            myAlertDialog.setNegativeButton(requireActivity().getResources().getString(R.string.cancel),
                    new OnClickListener() {
                        public void onClick(DialogInterface arg0, int arg1) {
                            // do something when the Cancel button is clicked
                        }
                    });
            myAlertDialog.show();
        }

    }

    /**
     * Refreshes the month
     */
    private void refreshCalendar() {
        mAdapter.refreshDays();
        mAdapter.notifyDataSetChanged();
    }

    /** An Undo, here or on a screen above, brought records back. */
    private final Runnable onRecordsChanged = () -> {
        if (getView() != null && mAdapter != null)
            refreshCalendar();
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

//    @Override
//    public void onDetach() {
//        super.onDetach();
//        mListener = null;
//    }

    private void initialize() {
        this.getComponent(ActivityComponent.class).inject(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        load.cancel();
        binding = null;
    }
}
