package de.timowa.expenselog;

import android.view.Menu;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;

import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.MaterialIcons;

/**
 * The selection bar a long press starts, for every list that selects: the records list and the
 * recurring overview. It replaces the toolbar (an AppCompat action mode, which is why
 * {@code windowActionModeOverlay} must stay on) and offers Select all, where the list allows it,
 * and Delete. Back or its arrow ends it without leaving the screen.
 */
final class SelectionBar {

    interface Listener {
        void onSelectAll();

        void onDelete();

        /** The bar has closed -- by Back, its arrow or {@link #finish}; clear the selection. */
        void onEnded();
    }

    private final AppCompatActivity activity;
    private final Listener listener;
    private ActionMode mode;
    private boolean selectAllVisible;

    SelectionBar(AppCompatActivity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
    }

    boolean isShowing() {
        return mode != null;
    }

    /** Shows the bar, or updates it if it is showing. */
    void show(CharSequence title, CharSequence subtitle, boolean selectAll) {
        selectAllVisible = selectAll;
        if (mode == null)
            mode = activity.startSupportActionMode(callback);
        if (mode == null)
            return;
        mode.setTitle(title);
        mode.setSubtitle(subtitle);
        mode.invalidate();
    }

    void finish() {
        if (mode != null)
            mode.finish();
    }

    private final ActionMode.Callback callback = new ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
            actionMode.getMenuInflater().inflate(R.menu.log_list_selection, menu);
            try {
                menu.findItem(R.id.action_select_all).setIcon(new IconDrawable(activity,
                        MaterialIcons.md_select_all).actionBarSize().colorRes(R.color.actionBarWhite));
                menu.findItem(R.id.action_delete_selected).setIcon(new IconDrawable(activity,
                        MaterialIcons.md_delete).actionBarSize().colorRes(R.color.actionBarWhite));
            } catch (Exception e) {
                e.printStackTrace();
            }
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            MenuItem selectAll = menu.findItem(R.id.action_select_all);
            if (selectAll.isVisible() == selectAllVisible)
                return false;
            selectAll.setVisible(selectAllVisible);
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode actionMode, MenuItem item) {
            int id = item.getItemId();
            if (id == R.id.action_select_all) {
                listener.onSelectAll();
                return true;
            } else if (id == R.id.action_delete_selected) {
                listener.onDelete();
                return true;
            }
            return false;
        }

        @Override
        public void onDestroyActionMode(ActionMode actionMode) {
            mode = null;
            listener.onEnded();
        }
    };
}
