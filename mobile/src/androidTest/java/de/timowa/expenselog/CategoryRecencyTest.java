package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.database.Cursor;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * {@link DBAdapter#getTagIdsByRecency} orders categories exactly as the per-category loop it
 * replaced did, and the deletes that span two tables still remove exactly what they should.
 * docs/history/RELIABILITY_PLAN.md, Step 3 (F14, F17).
 */
@RunWith(AndroidJUnit4.class)
public class CategoryRecencyTest {

    private Context context;
    private DBAdapter adapter;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
        adapter = new DBAdapter(context);
        adapter.open();
    }

    @After
    public void tearDown() {
        adapter.close();
        context.deleteDatabase(DBAdapter.DATABASE_NAME);
    }

    /** The ordering NewLogFragment.initializeCategoryListByRecency computed before Step 3. */
    private List<Integer> oldOrdering(boolean income) {
        long[][] catIdAndDate;
        try (Cursor tags = adapter.getTagsOfType(income)) {
            catIdAndDate = new long[tags.getCount()][2];
            int counter = 0;
            while (tags.moveToNext()) {
                int catId = tags.getInt(DBAdapter.COLUMN_TAG_ID);
                catIdAndDate[counter][0] = catId;
                long mostRecentLog = adapter.getMostRecentRecordForCategory(catId);
                catIdAndDate[counter][1] = mostRecentLog > 0 ? mostRecentLog : 1000 - counter;
                counter++;
            }
        }
        Arrays.sort(catIdAndDate, (o1, o2) -> Long.compare(o2[1], o1[1]));
        List<Integer> ids = new ArrayList<>();
        for (long[] c : catIdAndDate)
            ids.add((int) c[0]);
        return ids;
    }

    @Test
    public void recencyOrdering_matchesThePerCategoryLoopItReplaced() {
        Random random = new Random(20260914);
        String[] names = {"rent", "Groceries", "alcohol", "Zoo", "books", "Eating Out", "fuel",
                "Gifts", "health", "Insurance", "salary", "Bonus", "interest"};
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < names.length; i++)
            ids.add(adapter.newTag(names[i], i % 3 == 0 ? 1 : 0, "fa-tag"));

        // Some categories unused, two sharing the same latest time, one used only before 1970.
        for (int n = 0; n < 400; n++) {
            int cat = ids.get(random.nextInt(ids.size() - 3));
            long time = 1_600_000_000_000L + random.nextInt(1_000_000) * 1000L;
            adapter.newLog(time, 0, 1, 1.0, cat, "", null, -1);
        }
        adapter.newLog(1_700_000_000_000L, 0, 1, 1.0, ids.get(1), "", null, -1);
        adapter.newLog(1_700_000_000_000L, 0, 1, 1.0, ids.get(2), "", null, -1);
        adapter.newLog(-5_000L, 0, 1, 1.0, ids.get(ids.size() - 3), "", null, -1);

        for (boolean income : new boolean[]{false, true})
            assertEquals("income=" + income, oldOrdering(income), adapter.getTagIdsByRecency(income));
    }

    @Test
    public void recencyOrdering_withNoRecords_isAlphabetical() {
        int b = adapter.newTag("banana", 0, "fa-tag");
        int a = adapter.newTag("Apple", 0, "fa-tag");
        int c = adapter.newTag("cherry", 0, "fa-tag");
        assertEquals(Arrays.asList(a, b, c), adapter.getTagIdsByRecency(false));
    }

    private long count(String sql) {
        try (Cursor cursor = adapter.getDB().rawQuery(sql, null)) {
            cursor.moveToFirst();
            return cursor.getLong(0);
        }
    }

    @Test
    public void deleteTag_removesTheCategoryAndOnlyItsRecords() {
        int keep = adapter.newTag("keep", 0, "fa-tag");
        int gone = adapter.newTag("gone", 0, "fa-tag");
        adapter.newLog(1L, 0, 1, 1.0, keep, "", null, -1);
        adapter.newLog(2L, 0, 1, 1.0, gone, "", null, -1);
        adapter.newLog(3L, 0, 1, 1.0, gone, "", null, -1);
        adapter.deleteTag(gone);
        assertEquals(1, count("SELECT COUNT(*) FROM tagTypes"));
        assertEquals(1, count("SELECT COUNT(*) FROM mainLogs WHERE categoryId=" + keep));
        assertEquals(0, count("SELECT COUNT(*) FROM mainLogs WHERE categoryId=" + gone));
    }

    @Test
    public void deleteAccount_removesTheAccountAndOnlyItsRecords() {
        int keep = (int) adapter.newAccount("keep");
        int gone = (int) adapter.newAccount("gone");
        adapter.newLog(1L, 0, keep, 1.0, 1, "", null, -1);
        adapter.newLog(2L, 0, gone, 1.0, 1, "", null, -1);
        adapter.deleteAccount(gone);
        assertEquals(1, count("SELECT COUNT(*) FROM AccountsTable"));
        assertEquals(1, count("SELECT COUNT(*) FROM mainLogs"));
        assertEquals(keep, count("SELECT account FROM mainLogs"));
    }

    @Test
    public void deleteRepeatingLogs_removesTheSeriesAndItsEntry() {
        int series = adapter.createRepeatingSeries(0L, 10L * 86_400_000L, 1.0, 1, 0, 0, 1, 1, "", null);
        adapter.newLog(5L, 0, 1, 1.0, 1, "unrelated", null, -1);
        adapter.deleteRepeatingLogs(series);
        assertEquals(0, count("SELECT COUNT(*) FROM RepeatingTable"));
        assertEquals(1, count("SELECT COUNT(*) FROM mainLogs"));
    }
}
