package de.timowa.expenselog;

import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Every change to the database moves {@link DBAdapter#changeGeneration}, including one made while
 * the Dropbox marker already reads "changed" -- which is the only way an upload can tell that a
 * delete happened while it ran.
 */
@RunWith(AndroidJUnit4.class)
public class ChangeGenerationTest {

    private Context context;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    @After
    public void tearDown() {
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    /**
     * A write that has to wait for a snapshot must still move the generation after it lands. The
     * generation used to move only before the write: an upload could read it after that bump and
     * snapshot before the write, then find it unchanged and declare the missing delete synced.
     */
    @Test
    public void aWriteHeldBackByASnapshot_movesTheGenerationAfterTheSnapshot() throws Exception {
        DBAdapter adapter = new DBAdapter(context);
        adapter.newLog(1L, 0, 1, 1.0, 1, "", null, -1);
        android.database.sqlite.SQLiteDatabase db = adapter.getDB();
        java.util.concurrent.CountDownLatch deleteStarted = new java.util.concurrent.CountDownLatch(1);

        // Stand in for the snapshot: hold the connection in a transaction.
        db.beginTransactionNonExclusive();
        Thread deleter;
        long seenByUpload;
        try {
            deleter = new Thread(() -> {
                deleteStarted.countDown();
                adapter.deleteLog(1);
            });
            deleter.start();
            deleteStarted.await();
            Thread.sleep(300); // the delete has counted itself and is waiting for the connection
            seenByUpload = DBAdapter.changeGeneration();
        } finally {
            db.endTransaction();
        }
        deleter.join();
        assertTrue("the delete landed after the snapshot, so the generation must have moved since",
                DBAdapter.changeGeneration() > seenByUpload);
    }

    @Test
    public void everyWriteMovesTheGeneration_evenWhenTheMarkerAlreadySaysChanged() {
        DBAdapter adapter = new DBAdapter(context);
        adapter.newLog(1L, 0, 1, 1.0, 1, "", null, -1);
        long afterSave = DBAdapter.changeGeneration();

        adapter.deleteLog(1);
        long afterDelete = DBAdapter.changeGeneration();
        assertTrue("a delete must count as a change", afterDelete > afterSave);

        int tag = adapter.newTag("t", 0, "fa-tag");
        adapter.deleteTag(tag);
        assertTrue(DBAdapter.changeGeneration() > afterDelete);
    }
}
