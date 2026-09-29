package de.timowa.expenselog.dropbox;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.timowa.expenselog.DropBoxHelper;

/**
 * Replaces the {@code AsyncTask} every class in this package used to extend. {@link
 * #doInBackground()} runs on a background thread; {@link #onPostExecute(Object)} is posted
 * back to the main thread. Callers are unaffected: {@code new SomeTask(...).execute()} behaves
 * the same as it did on {@code AsyncTask}.
 *
 * <p><b>One thread, so tasks run one at a time in the order they were started.</b> That is
 * {@code AsyncTask}'s own default, and the replacement first used a cached pool, which ran them
 * concurrently: two saves in quick succession started two uploads that could finish in either
 * order, leaving the older database on Dropbox marked as current, and a download could replace the
 * database while an upload was reading it. {@code DropboxTaskTest}.
 *
 * <p><b>An unchecked exception does not kill the process.</b> On a pool thread it used to; it is
 * now logged and handed to {@link #onUncaught} on the main thread instead of
 * {@link #onPostExecute}.
 */
abstract class DropboxTask<Result> {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    protected abstract Result doInBackground();

    protected void onPostExecute(Result result) {
    }

    /**
     * Called on the main thread, instead of {@link #onPostExecute}, when {@link #doInBackground}
     * threw. A task that shows a dialog must override it to dismiss the dialog.
     */
    protected void onUncaught(RuntimeException e) {
    }

    public final void execute() {
        EXECUTOR.execute(() -> {
            final Result result;
            try {
                result = doInBackground();
            } catch (RuntimeException e) {
                Log.e(DropBoxHelper.DROPBOX_TAG, getClass().getSimpleName() + " failed", e);
                MAIN_HANDLER.post(() -> onUncaught(e));
                return;
            }
            MAIN_HANDLER.post(() -> onPostExecute(result));
        });
    }
}
