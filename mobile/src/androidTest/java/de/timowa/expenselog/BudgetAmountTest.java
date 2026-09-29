package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Pins {@link PrefManager#parseBudgetAmount}. AndroidX's {@code EditTextPreference} ignores the
 * XML {@code inputType}, so the budget amount once took any text, and a value
 * {@code Float.parseFloat} refused crashed the Summary screen and the CSV export on every visit.
 * The keyboard is numeric again, but a decimal keyboard still offers a comma in regions that use
 * one, and values stored before the fix are still on phones.
 */
@RunWith(AndroidJUnit4.class)
public class BudgetAmountTest {

    @Test
    public void plainNumbers_parse() {
        assertEquals(0f, PrefManager.parseBudgetAmount("0"), 0f);
        assertEquals(12.5f, PrefManager.parseBudgetAmount("12.5"), 0f);
        assertEquals(400f, PrefManager.parseBudgetAmount(" 400 "), 0f);
    }

    @Test
    public void aDecimalComma_isRead() {
        assertEquals(12.5f, PrefManager.parseBudgetAmount("12,50"), 0f);
    }

    @Test
    public void anythingElse_readsAsNoBudget() {
        assertEquals(0f, PrefManager.parseBudgetAmount(null), 0f);
        assertEquals(0f, PrefManager.parseBudgetAmount(""), 0f);
        assertEquals(0f, PrefManager.parseBudgetAmount("abc"), 0f);
        assertEquals(0f, PrefManager.parseBudgetAmount("1.234,56"), 0f);
        assertEquals(0f, PrefManager.parseBudgetAmount("NaN"), 0f);
        assertEquals(0f, PrefManager.parseBudgetAmount("Infinity"), 0f);
    }
}
