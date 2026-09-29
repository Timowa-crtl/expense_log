package de.timowa.expenselog;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells the screens showing records that records came back. An Undo can run after the screen that
 * deleted has gone -- the editor closes as soon as it deletes -- so it cannot refresh the list,
 * calendar or recurring overview underneath itself; they listen here while they are resumed.
 * Main thread only.
 */
final class RecordsChanged {

    private static final List<Runnable> LISTENERS = new ArrayList<>();

    private RecordsChanged() {
    }

    /** From {@code onResume}; remove it again in {@code onPause}. */
    static void listen(Runnable listener) {
        if (!LISTENERS.contains(listener))
            LISTENERS.add(listener);
    }

    static void stopListening(Runnable listener) {
        LISTENERS.remove(listener);
    }

    static void fire() {
        for (Runnable listener : new ArrayList<>(LISTENERS))
            listener.run();
    }
}
