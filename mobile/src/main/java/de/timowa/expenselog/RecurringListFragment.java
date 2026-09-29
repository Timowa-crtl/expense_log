package de.timowa.expenselog;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.FontAwesomeIcons;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;

import de.timowa.expenselog.DependencyInjection.ActivityComponent;
import de.timowa.expenselog.databinding.FragmentRecurringListBinding;
import de.timowa.expenselog.databinding.LogListItemBinding;

/**
 * Every repeating series in one list: what it is, how often, and its next or last record.
 * Tapping a series edits its next record (its last, once it has ended), which is where its
 * schedule and values are changed. A long press selects it, as a repeating record is selected in
 * the records list: one series at a time, and the bar's Delete asks, for the record the row shows,
 * whether to delete only it, it and the following entries, or the whole series -- with Undo.
 */
public class RecurringListFragment extends BaseFragment {

    /** One thread, so a reload started while another runs cannot finish before it. */
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();

    @Inject
    Navigator navigator;
    @Inject
    DBAdapter dbAdapter;
    @Inject
    Utility utility;
    @Inject
    PrefManager prefManager;

    private static final String STATE_SELECTED = "selectedSeries";

    private FragmentRecurringListBinding binding;
    private SelectionBar selectionBar;
    /** The selected series' id, or -1. */
    private int selectedId = -1;
    private List<Row> rows = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** A series and what its row shows, read together off the main thread. */
    private static final class Row {
        RepeatingSeries series;
        String label;
        String icon;
        /** The record a tap edits and the date the row shows: the next one, or the last. */
        long shownTime;
        int shownLogId;
        boolean active;
    }

    public static RecurringListFragment newInstance() {
        return new RecurringListFragment();
    }

    public RecurringListFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentRecurringListBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        this.getComponent(ActivityComponent.class).inject(this);
        if (savedInstanceState != null)
            selectedId = savedInstanceState.getInt(STATE_SELECTED, -1);

        binding.recyclerViewRecurring.setLayoutManager(new LinearLayoutManager(requireContext()));
        // A change animation ends at full opacity, which would un-fade an ended series on select.
        RecyclerView.ItemAnimator animator = binding.recyclerViewRecurring.getItemAnimator();
        if (animator instanceof SimpleItemAnimator)
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        binding.recyclerViewRecurring.addItemDecoration(
                new DividerItemDecoration(requireActivity(), DividerItemDecoration.VERTICAL_LIST));
        setupFab();
    }

    /** An Undo, here or on a screen above, brought records back. */
    private final Runnable onRecordsChanged = () -> {
        if (binding != null)
            reload();
    };

    @Override
    public void onResume() {
        super.onResume();
        // on resume, so a series changed on the edit screen shows its new state on the way back
        reload();
        RecordsChanged.listen(onRecordsChanged);
    }

    @Override
    public void onPause() {
        super.onPause();
        RecordsChanged.stopListening(onRecordsChanged);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED, selectedId);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // A rotation brings the selection back; leaving the screen does not keep it.
        int kept = requireActivity().isChangingConfigurations() ? selectedId : -1;
        if (selectionBar != null)
            selectionBar.finish();
        selectedId = kept;
        binding = null;
    }

    private void setupFab() {
        FloatingActionButton fab = requireActivity().findViewById(R.id.fab);
        fab.setImageDrawable(ContextCompat.getDrawable(requireActivity(), R.drawable.ic_action_new));
        // hide and show again to address disappearing icon bug in material 1.0.0
        fab.hide();
        fab.show();
        fab.setOnClickListener(view -> navigator.newTempFragment(NewLogFragment.newRepeatingInstance(),
                getString(R.string.new_record)));
    }

    private void reload() {
        // Everything the load needs is captured here: the fragment may be gone when it finishes.
        final DBAdapter adapter = dbAdapter;
        final long now = System.currentTimeMillis();
        LOADER.execute(() -> {
            List<Row> rows = load(adapter, now);
            mainHandler.post(() -> {
                if (binding != null)
                    show(rows);
            });
        });
    }

    /** Active series first, soonest next record first; then ended ones, most recently ended first. */
    private static List<Row> load(DBAdapter adapter, long now) {
        List<Row> active = new ArrayList<>();
        List<Row> ended = new ArrayList<>();
        for (RepeatingSeries series : adapter.getAllRepeatingSeries()) {
            Row row = new Row();
            row.series = series;
            long next = series.nextAfter(now);
            row.active = next != -1;
            row.shownTime = row.active ? next : series.latest.getTimeStamp();
            row.shownLogId = series.logIds[series.positionOf(row.shownTime) - 1];
            try (Cursor tag = adapter.getTag(series.latest.getCategory())) {
                if (tag.moveToFirst()) {
                    row.label = tag.getString(DBAdapter.COLUMN_TAG_LABEL);
                    row.icon = tag.getString(DBAdapter.COLUMN_TAG_ICON);
                }
            }
            (row.active ? active : ended).add(row);
        }
        active.sort((a, b) -> Long.compare(a.shownTime, b.shownTime));
        ended.sort((a, b) -> Long.compare(b.shownTime, a.shownTime));
        active.addAll(ended);
        return active;
    }

    private void show(List<Row> rows) {
        this.rows = rows;
        binding.textViewRecurringEmpty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recyclerViewRecurring.setAdapter(new RowAdapter(shownItems()));
        // a selected series may have gone, or come back after a rotation
        updateSelection();
    }

    /**
     * What the list shows: the active series, then an "Ended (n)" row, then the ended series if
     * that is expanded. {@code null} stands for that row.
     */
    private List<Row> shownItems() {
        List<Row> items = new ArrayList<>();
        int ended = 0;
        for (Row row : rows) {
            if (row.active)
                items.add(row);
            else
                ended++;
        }
        if (ended > 0) {
            items.add(null);
            if (prefManager.recurringEndedExpanded())
                for (Row row : rows)
                    if (!row.active)
                        items.add(row);
        }
        return items;
    }

    private int endedCount() {
        int ended = 0;
        for (Row row : rows)
            if (!row.active)
                ended++;
        return ended;
    }

    private void toggleEnded() {
        boolean expanded = !prefManager.recurringEndedExpanded();
        prefManager.setRecurringEndedExpanded(expanded);
        // a selected ended series disappears with them
        Row selected = selectedRow();
        if (!expanded && selected != null && !selected.active && selectionBar != null)
            selectionBar.finish();
        binding.recyclerViewRecurring.setAdapter(new RowAdapter(shownItems()));
    }

    private void rebindRows() {
        RecyclerView.Adapter<?> adapter = binding == null ? null : binding.recyclerViewRecurring.getAdapter();
        if (adapter != null)
            adapter.notifyItemRangeChanged(0, adapter.getItemCount());
    }

    /** "Monthly", "Every 3 months", ... */
    static String describeInterval(Resources res, int frequency, int period) {
        int plural;
        switch (period) {
            case RepeatingSeries.PERIOD_WEEK:
                plural = R.plurals.recurring_every_week;
                break;
            case RepeatingSeries.PERIOD_MONTH:
                plural = R.plurals.recurring_every_month;
                break;
            case RepeatingSeries.PERIOD_YEAR:
                plural = R.plurals.recurring_every_year;
                break;
            default:
                plural = R.plurals.recurring_every_day;
                break;
        }
        return res.getQuantityString(plural, frequency, frequency);
    }

    private Row selectedRow() {
        for (Row row : rows)
            if (row.series.id == selectedId)
                return row;
        return null;
    }

    /** Selects {@code row}, or clears the selection if it already is the selected one. */
    private void select(Row row) {
        selectedId = row.series.id == selectedId ? -1 : row.series.id;
        rebindRows();
        updateSelection();
    }

    /** Shows the selection bar for the selected series, or ends it. */
    private void updateSelection() {
        Row row = selectedRow();
        if (row == null) {
            selectedId = -1;
            if (selectionBar != null)
                selectionBar.finish();
            return;
        }
        String date = prefManager.getDateFormat().format(new Date(row.shownTime));
        String interval = describeInterval(getResources(), row.series.frequency, row.series.period);
        bar().show(title(row), getString(row.active ? R.string.recurring_bar_next : R.string.recurring_bar_last,
                interval, date), false);
    }

    private SelectionBar bar() {
        if (selectionBar == null)
            selectionBar = new SelectionBar((AppCompatActivity) requireActivity(), new SelectionBar.Listener() {
                @Override
                public void onSelectAll() {
                }

                @Override
                public void onDelete() {
                    deleteSelected();
                }

                @Override
                public void onEnded() {
                    selectedId = -1;
                    rebindRows();
                }
            });
        return selectionBar;
    }

    /** The list's delete dialog, for the record the selected row shows. */
    private void deleteSelected() {
        Row row = selectedRow();
        if (row == null)
            return;
        LogItem shown = new LogItem();
        shown.setId(row.shownLogId);
        shown.setTimeStamp(row.shownTime);
        shown.setAmount(row.series.latest.getAmount());
        shown.setRepeatingId(row.series.id);
        RecurringScope.confirmDelete(requireActivity(), dbAdapter, utility, prefManager, shown, () -> {
            if (binding == null)
                return;
            if (selectionBar != null)
                selectionBar.finish();
            reload();
        });
    }

    /** What a row calls its series: the notes if there are any, as the row's first line does. */
    private static String title(Row row) {
        String notes = row.series.latest.getNotes();
        return notes.isEmpty() ? row.label : notes;
    }

    private void edit(Row row) {
        navigator.editRecord(row.shownLogId);
    }

    private class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private static final int TYPE_ROW = 0;
        private static final int TYPE_ENDED_HEADER = 1;
        /** The shown series; {@code null} is the "Ended (n)" row. */
        private final List<Row> rows;

        RowAdapter(List<Row> rows) {
            this.rows = rows;
        }

        class Holder extends RecyclerView.ViewHolder {
            final LogListItemBinding item;

            Holder(LogListItemBinding item) {
                super(item.getRoot());
                this.item = item;
            }
        }

        class HeaderHolder extends RecyclerView.ViewHolder {
            HeaderHolder(View view) {
                super(view);
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position) == null ? TYPE_ENDED_HEADER : TYPE_ROW;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_ENDED_HEADER)
                return new HeaderHolder(inflater.inflate(R.layout.recurring_ended_header, parent, false));
            return new Holder(LogListItemBinding.inflate(inflater, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
            if (viewHolder instanceof HeaderHolder) {
                View header = viewHolder.itemView;
                ((TextView) header.findViewById(R.id.textView_endedHeader))
                        .setText(getString(R.string.recurring_ended_header, endedCount()));
                header.findViewById(R.id.imageView_endedChevron)
                        .setRotation(prefManager.recurringEndedExpanded() ? 90f : 0f);
                header.setOnClickListener(v -> toggleEnded());
                return;
            }
            bindRow((Holder) viewHolder, position);
        }

        private void bindRow(@NonNull Holder holder, int position) {
            Row row = rows.get(position);
            LogItem latest = row.series.latest;
            Context context = holder.itemView.getContext();
            Resources res = context.getResources();

            String notes = latest.getNotes();
            holder.item.textViewMainNotes.setText(title(row));
            String interval = describeInterval(res, row.series.frequency, row.series.period);
            String until = prefManager.getDateFormat().format(new Date(row.active
                    ? row.series.endTime : latest.getTimeStamp()));
            String subText = res.getString(row.active ? R.string.recurring_until : R.string.recurring_ended,
                    interval, until);
            if (!notes.isEmpty() && row.label != null)
                subText = res.getString(R.string.recurring_category_join, row.label, subText);
            holder.item.textViewLogCategory.setText(subText);

            holder.item.textViewLogAmount.setText(prefManager.formatMoney(latest.getAmount()));
            holder.item.textViewLogAmount.setTextColor(ContextCompat.getColor(context,
                    latest.getExpenseIncome() == 0 ? R.color.expenseColor : R.color.incomeColor));
            String date = prefManager.getDateFormat().format(new Date(row.shownTime));
            holder.item.textViewLogDate.setText(res.getString(
                    row.active ? R.string.recurring_next : R.string.recurring_last, date));
            holder.item.imageViewRepeating.setVisibility(View.GONE);

            try {
                holder.item.imageViewLogIcon.setImageDrawable(new IconDrawable(context,
                        row.icon != null ? row.icon : "fa-tag"));
            } catch (Exception e) {
                holder.item.imageViewLogIcon.setImageDrawable(new IconDrawable(context, FontAwesomeIcons.fa_tag));
            }
            // an ended series is still listed, but reads as history
            holder.itemView.setAlpha(row.active ? 1f : 0.6f);

            holder.itemView.setActivated(row.series.id == selectedId);
            holder.itemView.setOnClickListener(v -> {
                if (selectedId == -1)
                    edit(row);
                else
                    select(row);
            });
            holder.itemView.setOnLongClickListener(v -> {
                if (row.series.id != selectedId)
                    select(row);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
