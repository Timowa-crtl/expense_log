package de.timowa.expenselog.dropbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dropbox tasks run one at a time, in the order started, and a task that throws does not take the
 * process down. docs/history/RELIABILITY_PLAN.md, Step 6 (F7).
 */
@RunWith(AndroidJUnit4.class)
public class DropboxTaskTest {

    @Test
    public void tasksFinishInTheOrderTheyWereStarted() throws Exception {
        List<String> finished = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch both = new CountDownLatch(2);

        new DropboxTask<String>() {
            @Override
            protected String doInBackground() {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
                return "slow, started first";
            }

            @Override
            protected void onPostExecute(String result) {
                finished.add(result);
                both.countDown();
            }
        }.execute();

        new DropboxTask<String>() {
            @Override
            protected String doInBackground() {
                return "fast, started second";
            }

            @Override
            protected void onPostExecute(String result) {
                finished.add(result);
                both.countDown();
            }
        }.execute();

        assertTrue(both.await(10, TimeUnit.SECONDS));
        assertEquals(List.of("slow, started first", "fast, started second"), finished);
    }

    @Test
    public void aTaskThatThrows_reportsOnTheMainThreadAndLaterTasksStillRun() throws Exception {
        AtomicReference<RuntimeException> caught = new AtomicReference<>();
        AtomicReference<Looper> looper = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(2);
        IllegalStateException thrown = new IllegalStateException("simulated SDK failure");

        new DropboxTask<Void>() {
            @Override
            protected Void doInBackground() {
                throw thrown;
            }

            @Override
            protected void onUncaught(RuntimeException e) {
                caught.set(e);
                looper.set(Looper.myLooper());
                done.countDown();
            }
        }.execute();

        new DropboxTask<Boolean>() {
            @Override
            protected Boolean doInBackground() {
                return true;
            }

            @Override
            protected void onPostExecute(Boolean result) {
                done.countDown();
            }
        }.execute();

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertSame(thrown, caught.get());
        assertSame(Looper.getMainLooper(), looper.get());
    }
}
