package de.timowa.expenselog;

import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.MaterialIcons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * The "only this entry / this and following / the whole series" dialogs, for saving or deleting a
 * record of a repeating series -- from the records list, the calendar, the recurring overview and
 * the edit screen. One design for both ({@link #showMenu}): a title and three cards, each an
 * icon, a title and one short line.
 *
 * <p>The save and delete dialogs always show all three choices. One that cannot apply is shown
 * disabled, its line saying why, rather than left out -- so the dialog always looks the same.
 */
final class RecurringScope {

    static final int ONE = 0;
    static final int FOLLOWING = 1;
    static final int ALL = 2;

    interface OnChosen {
        void onChosen(int scope);
    }

    /**
     * One card. {@code detail} says why it is unavailable when {@code enabled} is false.
     *
     * <p>{@code iconColor} is the only colour that varies: the delete dialog runs its icons amber,
     * orange, red as the choice takes more with it -- never green, since none of the three is safe
     * -- while the save dialog, which deletes nothing, keeps the app's teal. Each card also has its
     * own icon shape and says how many entries it covers, so nothing rests on colour alone.
     */
    private static final class Option {
        final MaterialIcons icon;
        final String title;
        final String detail;
        final boolean enabled;
        final int iconColor;
        final int background;
        final Runnable action;

        Option(MaterialIcons icon, String title, String detail, boolean enabled, int iconColor,
               int background, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.detail = detail;
            this.enabled = enabled;
            this.iconColor = iconColor;
            this.background = background;
            this.action = action;
        }
    }

    /** A card of the save dialog: nothing is deleted, so it is the app's teal. */
    private static Option save(MaterialIcons icon, String title, String detail, boolean enabled, Runnable action) {
        return new Option(icon, title, detail, enabled, R.color.colorPrimary,
                R.drawable.bg_scope_option_teal, action);
    }

    /** A card of the delete dialog, its icon coloured by how much the choice takes with it. */
    private static Option delete(MaterialIcons icon, String title, String detail, int severity, Runnable action) {
        return new Option(icon, title, detail, true, severity, R.drawable.bg_scope_option, action);
    }

    private RecurringScope() {
    }

    /**
     * Asks which records a saved change applies to.
     *
     * <p>From the series' first record, "this and following" and "the whole series" are the same
     * records; both are shown, and {@link #ALL} is passed back as {@link #FOLLOWING}, which is the
     * path that can also restart the schedule.
     *
     * @param series     null for a record whose series entry is gone; only {@link #ONE} can apply
     * @param recordTime the record's time as stored, not as edited
     * @param oneAllowed false for a new interval or end, which belong to the series
     * @param allAllowed false for a new interval or date, which apply from this record on only
     * @param followingUnavailable why "this and following" cannot apply -- only the end changed,
     *                             which belongs to the whole series -- or null if it can. (A new
     *                             end before this record with other changes too never reaches
     *                             the dialog: the editor refuses it.)
     * @param endLine    what a new end does to the series, shown above the choices; or null
     */
    static void chooseSave(Context context, PrefManager prefManager, RepeatingSeries series, long recordTime,
                           boolean oneAllowed, boolean allAllowed, String followingUnavailable,
                           String endLine, OnChosen callback) {
        Resources res = context.getResources();
        String day = prefManager.getDateFormat().format(new Date(recordTime));
        boolean fromFirst = series != null && series.positionOf(recordTime) <= 1;
        boolean one = oneAllowed || series == null;
        boolean all = series != null && (allAllowed || fromFirst);

        List<Option> options = new ArrayList<>();
        options.add(save(MaterialIcons.md_today, res.getString(R.string.recurring_scope_one),
                one ? day : res.getString(R.string.recurring_unavailable_series_change), one,
                () -> callback.onChosen(ONE)));
        if (series != null) {
            boolean following = followingUnavailable == null;
            options.add(save(MaterialIcons.md_fast_forward, res.getString(R.string.recurring_delete_following),
                    following ? entries(res, series.countFrom(recordTime)) : followingUnavailable, following,
                    () -> callback.onChosen(FOLLOWING)));
            options.add(save(MaterialIcons.md_repeat, res.getString(R.string.recurring_delete_all),
                    all ? entriesAll(res, series) : res.getString(R.string.recurring_unavailable_date_change),
                    all, () -> callback.onChosen(fromFirst ? FOLLOWING : ALL)));
        } else {
            options.addAll(missingSeries(res));
        }
        showMenu(context, res.getString(R.string.recurring_save_title), endLine, options);
    }

    /**
     * Asks how much of its series to delete with {@code record} -- only it, it and the following
     * entries, or the whole series -- deletes that, and offers Undo. {@code onChanged} runs once,
     * after a delete that happened; an Undo is announced through {@link RecordsChanged}.
     */
    static void confirmDelete(Context context, DBAdapter dbAdapter, Utility utility, PrefManager prefManager,
                              LogItem record, Runnable onChanged) {
        RepeatingSeries series = dbAdapter.getRepeatingSeries(record.getRepeatingId());
        Resources res = context.getResources();
        long time = record.getTimeStamp();

        List<Option> options = new ArrayList<>();
        options.add(delete(MaterialIcons.md_today, res.getString(R.string.recurring_scope_one),
                prefManager.getDateFormat().format(new Date(time)), R.color.scopeSeverityLow,
                () -> deleteAndOffer(context, dbAdapter, utility,
                        dbAdapter.deleteLogs(Collections.singletonList(record.getId())), onChanged)));
        if (series != null && series.size() > 0) {
            options.add(delete(MaterialIcons.md_fast_forward, res.getString(R.string.recurring_delete_following),
                    entries(res, series.countFrom(time)), R.color.scopeSeverityMedium,
                    () -> deleteAndOffer(context, dbAdapter, utility,
                            dbAdapter.deleteSeriesRecords(series.id, time), onChanged)));
            options.add(delete(MaterialIcons.md_delete, res.getString(R.string.recurring_delete_all),
                    entriesAll(res, series), R.color.scopeSeverityHigh,
                    () -> deleteAndOffer(context, dbAdapter, utility,
                            dbAdapter.deleteSeriesRecords(series.id, Long.MIN_VALUE), onChanged)));
        } else {
            options.addAll(missingSeries(res));
        }
        showMenu(context, res.getString(R.string.recurring_delete_title), null, options);
    }

    /**
     * Offers Undo for what a delete removed, and refreshes. A delete that was refused -- the
     * series changed or went while the dialog was open -- wrote nothing: it says so, and
     * {@code onChanged} does not run, so an editor stays open on a record that still exists.
     */
    private static void deleteAndOffer(Context context, DBAdapter dbAdapter, Utility utility,
                                       DBAdapter.DeletedLogs deleted, Runnable onChanged) {
        if (deleted == null) {
            utility.snackBarMessage(context.getString(R.string.recurring_delete_refused));
            return;
        }
        DeleteUndo.offer(utility, context.getResources(), dbAdapter, deleted);
        onChanged.run();
    }

    private static String entries(Resources res, int count) {
        return res.getQuantityString(R.plurals.recurring_entries_count, count, count);
    }

    private static String entriesAll(Resources res, RepeatingSeries series) {
        return res.getQuantityString(R.plurals.recurring_entries_all, series.size(), series.size());
    }

    /** The two series choices, disabled, for a record whose series entry is gone. */
    private static List<Option> missingSeries(Resources res) {
        String reason = res.getString(R.string.recurring_series_missing);
        List<Option> options = new ArrayList<>();
        options.add(save(MaterialIcons.md_fast_forward,
                res.getString(R.string.recurring_delete_following), reason, false, null));
        options.add(save(MaterialIcons.md_repeat,
                res.getString(R.string.recurring_delete_all), reason, false, null));
        return options;
    }

    /** The shared series dialog: a title, one card per option, and Cancel. */
    private static void showMenu(Context context, CharSequence title, CharSequence message, List<Option> options) {
        ViewGroup container = (ViewGroup) LayoutInflater.from(context).inflate(R.layout.dialog_recurring_scope, null);
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .create();
        for (Option option : options)
            container.addView(card(container, option, () -> {
                dialog.dismiss();
                option.action.run();
            }));
        dialog.show();
    }

    /** One tappable card; its icon carries the colour, everything else is alike on all three. */
    private static View card(ViewGroup parent, Option option, Runnable onClick) {
        Context context = parent.getContext();
        View card = LayoutInflater.from(context).inflate(R.layout.recurring_scope_option, parent, false);
        card.setBackgroundResource(option.background);
        ((ImageView) card.findViewById(R.id.imageView_scopeIcon))
                .setImageDrawable(new IconDrawable(context, option.icon).colorRes(option.iconColor));
        ((TextView) card.findViewById(R.id.textView_scopeTitle)).setText(option.title);
        TextView detail = card.findViewById(R.id.textView_scopeDetail);
        detail.setText(option.detail);
        detail.setVisibility(option.detail == null || option.detail.isEmpty() ? View.GONE : View.VISIBLE);
        if (option.enabled) {
            card.setOnClickListener(v -> onClick.run());
        } else {
            card.setEnabled(false);
            card.setClickable(false);
            card.setAlpha(0.45f);
        }
        return card;
    }
}
