package de.timowa.expenselog;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Loads something off the main thread and delivers it back on it.
 *
 * <p>Replaces the {@code AsyncTask} subclasses the records list and the calendar used: the class
 * is deprecated, and its shared serial executor also queued a screen's load behind every other
 * {@code AsyncTask} in the process. Each screen owns one of these, so a calendar month and a list
 * page do not wait for one another — unlike {@code DropboxTask}, whose single shared thread is
 * load-bearing because two uploads must not overlap.
 *
 * <p><b>The background work must not touch the fragment.</b> Capture what it needs on the main
 * thread before starting, as {@code LogsCalendarFragment} does: fast paging detaches a fragment
 * mid-load, and {@code requireContext()} from a detached fragment throws.
 *
 * <p><b>{@link #cancel()} must leave the object usable.</b> It is called from
 * {@code onDestroyView}, and a fragment on the back stack outlives its view: the same instance
 * loads again when the view comes back. Cancelling therefore drops the results of everything
 * started so far and releases the thread, and the next {@link #run} starts a new one. An earlier
 * version shut the executor down for good, and returning from an editor to the records list threw
 * {@code RejectedExecutionException} and killed the app.
 */
public final class BackgroundLoad {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Bumped by {@link #cancel()}; a result from an earlier generation is dropped. */
    private volatile int generation;

    private ExecutorService executor;

    /** Runs {@code work} on a background thread, then {@code then} on the main thread. */
    public <T> void run(Work<T> work, Result<T> then) {
        final int started;
        final ExecutorService running;
        synchronized (this) {
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor();
            }
            started = generation;
            running = executor;
        }
        running.execute(() -> {
            final T result;
            try {
                result = work.run();
            } catch (RuntimeException e) {
                Log.e("BackgroundLoad", "background load failed", e);
                return;
            }
            if (started != generation) return;
            mainHandler.post(() -> {
                if (started == generation) then.accept(result);
            });
        });
    }

    /** Drops any pending result and releases the thread. Call it when the screen's view goes. */
    public void cancel() {
        final ExecutorService running;
        synchronized (this) {
            generation++;
            running = executor;
            executor = null;
        }
        if (running != null) running.shutdown();
    }

    public interface Work<T> {
        T run();
    }

    public interface Result<T> {
        void accept(T result);
    }
}
