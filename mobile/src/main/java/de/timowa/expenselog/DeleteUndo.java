package de.timowa.expenselog;

import android.content.res.Resources;

/**
 * The "N records deleted · Undo" snackbar, for the records list and the series delete dialog.
 */
final class DeleteUndo {

    /** How long Undo stays offered after a delete. */
    static final int DURATION_MS = 8000;

    private DeleteUndo() {
    }

    /**
     * Says what was deleted and offers to put it back. Undo can outlive the screen that deleted
     * -- the user may page or navigate away, and the editor closes at once -- so a restore tells
     * whichever screens are showing through {@link RecordsChanged}.
     */
    static void offer(Utility utility, Resources res, DBAdapter db, DBAdapter.DeletedLogs deleted) {
        int count = deleted.size();
        offer(utility, res, db, deleted, res.getQuantityString(R.plurals.records_deleted, count, count),
                R.string.undo);
    }

    /**
     * As above, saying {@code message}, the button labelled {@code actionRes} -- for a save that
     * also deleted records, where "Undo" would read as taking back the whole save.
     */
    static void offer(Utility utility, Resources res, DBAdapter db, DBAdapter.DeletedLogs deleted,
                      String message, int actionRes) {
        int count = deleted.size();
        utility.snackBarAction(message, actionRes, DURATION_MS, () -> {
                    int restored = db.restoreLogs(deleted);
                    if (restored < 0)
                        utility.snackBarMessage(res.getString(R.string.records_restore_refused));
                    else if (restored < count)
                        utility.snackBarMessage(res.getString(R.string.records_restored_partly, restored, count));
                    else
                        utility.snackBarMessage(res.getQuantityString(R.plurals.records_restored, restored, restored));
                    RecordsChanged.fire();
                });
    }
}
