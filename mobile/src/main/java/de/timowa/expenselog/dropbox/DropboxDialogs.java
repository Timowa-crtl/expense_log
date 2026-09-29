package de.timowa.expenselog.dropbox;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import de.timowa.expenselog.DropBoxHelper;
import de.timowa.expenselog.R;

/** Dismissing a task's progress dialog when the task ends, whatever became of its screen. */
final class DropboxDialogs {

    private DropboxDialogs() {
    }

    /**
     * A spinner and a line of text, shown while a Dropbox task runs.
     *
     * <p>Replaces {@code ProgressDialog}, deprecated since API 26. Same shape as before: the
     * message, an indeterminate spinner, and an optional Cancel button. It is not cancellable by
     * Back or by a tap outside, as the old dialog was not, because the task behind it keeps
     * running either way; {@link #dismiss} is the only thing that closes it.
     *
     * @param onCancel the Cancel button's action, or null for a dialog with no button
     */
    static Dialog progress(Context context, CharSequence message,
                           DialogInterface.OnClickListener onCancel) {
        int padding = Math.round(24 * context.getResources().getDisplayMetrics().density);

        ProgressBar spinner = new ProgressBar(context);
        spinner.setIndeterminate(true);

        TextView text = new TextView(context);
        text.setText(message);
        text.setPadding(padding, 0, 0, 0);

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(padding, padding, padding, padding);
        row.addView(spinner);
        row.addView(text, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setView(row)
                .setCancelable(false);
        if (onCancel != null) {
            builder.setPositiveButton(context.getString(R.string.cancel), onCancel);
        }
        return builder.create();
    }

    /**
     * Dismisses {@code dialog} if it is still showing on a live window.
     *
     * <p>A Dropbox task outlives its screen: the user can rotate or leave while it runs, and
     * dismissing a dialog whose activity has been destroyed throws
     * {@code IllegalArgumentException: View not attached to window manager} on the main thread,
     * crashing the app at the moment the task finishes. docs/history/RELIABILITY_PLAN.md, Step 6 (F24).
     */
    static void dismiss(Dialog dialog) {
        if (dialog == null || !dialog.isShowing())
            return;
        if (windowGone(dialog.getContext()))
            return;
        try {
            dialog.dismiss();
        } catch (IllegalArgumentException e) {
            // The window went between the checks above and the dismiss.
            Log.w(DropBoxHelper.DROPBOX_TAG, "progress dialog was no longer attached", e);
        }
    }

    /** Whether {@code context} belongs to an activity that can no longer show a dialog. */
    static boolean windowGone(Context context) {
        Activity activity = activityOf(context);
        return activity != null && (activity.isFinishing() || activity.isDestroyed());
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity)
                return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
