package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Pins the CSV export format.
 *
 * <p>Every assertion here corresponds to something the export got wrong in a real file
 * (`Expense Log Records from 2026-09-01 - 2026-09-30`, September 2026): amounts written as quoted
 * display currency that no spreadsheet could total, a time column reading {@code 0:00}, category
 * and account labels written unquoted so a comma in a label shifted every column after it, and
 * notes "escaped" by deleting their quote characters.
 *
 * <p>{@link CsvFormatter} is pure, so this needs no isolation harness, no preferences and no
 * database — which is the reason the formatting was lifted out of {@code SpreadsheetHelper} in the
 * first place. See {@code docs/history/CSV_EXPORT_PLAN.md}.
 */
@RunWith(AndroidJUnit4.class)
public class CsvFormatterTest {

    private CsvFormatter csv;

    @Before
    public void setUp() {
        csv = new CsvFormatter();
    }

    // ---------- quoting: finding E, the silent column-shifter ----------

    /** The common case. A field needing no quotes must not get any, or the file is unreadable. */
    @Test
    public void plainField_isNotQuoted() {
        assertEquals("Activities", CsvFormatter.field("Activities"));
    }

    /** The bug: a category named "Food, drink" used to shift every column after it. */
    @Test
    public void fieldWithComma_isQuoted() {
        assertEquals("\"Food, drink\"", CsvFormatter.field("Food, drink"));
    }

    /** RFC 4180 escapes a quote by doubling it. The export used to delete it, losing data. */
    @Test
    public void fieldWithQuote_doublesItRatherThanDeletingIt() {
        assertEquals("\"He said \"\"hi\"\"\"", CsvFormatter.field("He said \"hi\""));
    }

    /** A newline inside a note is legal inside a quoted field, and must stay inside one. */
    @Test
    public void fieldWithNewline_isQuoted() {
        assertEquals("\"line one\nline two\"", CsvFormatter.field("line one\nline two"));
        assertEquals("\"line one\rline two\"", CsvFormatter.field("line one\rline two"));
    }

    /** KEY_NOTES is nullable text, and the export used to call replace() on it unguarded. */
    @Test
    public void nullField_becomesEmpty() {
        assertEquals("", CsvFormatter.field(null));
    }

    @Test
    public void emptyField_staysEmpty() {
        assertEquals("", CsvFormatter.field(""));
    }

    // ---------- rows ----------

    @Test
    public void row_joinsWithCommasAndEscapesEachField() {
        assertEquals("2026-09-03,18:21,-6666.00,EUR,Activities,NeuJelly",
                CsvFormatter.row("2026-09-03", "18:21", "-6666.00", "EUR", "Activities", "NeuJelly"));
    }

    @Test
    public void row_escapesAFieldThatWouldBreakTheRow() {
        assertEquals("a,\"b,c\",\"d\"\"e\"", CsvFormatter.row("a", "b,c", "d\"e"));
    }

    /** A padded row keeps its width: trailing empty fields are separators, not nothing. */
    @Test
    public void row_keepsEmptyTrailingFields() {
        assertEquals("a,,,", CsvFormatter.row("a", "", "", ""));
    }

    // ---------- amounts: finding B ----------

    /** The whole point. This used to be the string "-6.666,00 €", quoted, in the Amount column. */
    @Test
    public void amount_isABareNumberWithADotDecimal() {
        assertEquals("-6666.00", CsvFormatter.amount(-6666.0));
        assertEquals("2539.45", CsvFormatter.amount(2539.45));
    }

    /** No thousands grouping — a grouping separator is what made the column unparseable. */
    @Test
    public void amount_hasNoThousandsSeparator() {
        String formatted = CsvFormatter.amount(-86207.31);
        assertEquals("-86207.31", formatted);
        assertTrue("a grouped amount is not a number to a parser", formatted.indexOf(',') < 0);
    }

    /** Always two decimals, so the column is rectangular. */
    @Test
    public void amount_alwaysHasTwoDecimals() {
        assertEquals("0.00", CsvFormatter.amount(0));
        assertEquals("-1.00", CsvFormatter.amount(-1));
        assertEquals("4.99", CsvFormatter.amount(4.99));
    }

    /** An amount never needs quoting, so it stays bare through a row. */
    @Test
    public void amount_survivesAsAnUnquotedField() {
        assertEquals("-4.99", CsvFormatter.field(CsvFormatter.amount(-4.99)));
    }

    // ---------- date and time: findings C and D ----------

    @Test
    public void date_isIso() {
        assertEquals("2026-09-03", csv.date(millisAt(2026, Calendar.SEPTEMBER, 3, 18, 21)));
    }

    /** The reported symptom: midnight came out as "0:00", which does not sort. */
    @Test
    public void time_isZeroPaddedAndTwentyFourHour() {
        assertEquals("00:00", csv.time(millisAt(2026, Calendar.SEPTEMBER, 1, 0, 0)));
        assertEquals("09:22", csv.time(millisAt(2026, Calendar.SEPTEMBER, 5, 9, 22)));
        assertEquals("18:21", csv.time(millisAt(2026, Calendar.SEPTEMBER, 3, 18, 21)));
    }

    /** Zero-padding is what makes the column sortable as text, which is why it is pinned. */
    @Test
    public void times_sortAsText() {
        String midnight = csv.time(millisAt(2026, Calendar.SEPTEMBER, 1, 0, 0));
        String morning = csv.time(millisAt(2026, Calendar.SEPTEMBER, 1, 9, 22));
        String evening = csv.time(millisAt(2026, Calendar.SEPTEMBER, 1, 18, 21));

        assertTrue(midnight.compareTo(morning) < 0);
        assertTrue(morning.compareTo(evening) < 0);
    }

    /** Dates sort as text too — the property that made ISO worth defaulting to. */
    @Test
    public void dates_sortAsText() {
        String first = csv.date(millisAt(2026, Calendar.SEPTEMBER, 1, 12, 0));
        String later = csv.date(millisAt(2026, Calendar.SEPTEMBER, 30, 12, 0));
        String nextYear = csv.date(millisAt(2027, Calendar.JANUARY, 1, 12, 0));

        assertTrue(first.compareTo(later) < 0);
        assertTrue(later.compareTo(nextYear) < 0);
    }

    // ---------- file names: finding K ----------

    /** The received file had no extension at all; the name is now the file's own. */
    @Test
    public void sanitizeFileName_leavesAnOrdinaryNameAlone() {
        assertEquals("Expense Log Records from 2026-09-01 - 2026-09-30",
                CsvFormatter.sanitizeFileName("Expense Log Records from 2026-09-01 - 2026-09-30"));
    }

    /** A translated string carrying a colon must not produce a file that will not save. */
    @Test
    public void sanitizeFileName_stripsCharactersAFilesystemRefuses() {
        // the colon is replaced in place, so no space appears where it stood
        assertEquals("Expense Log- records", CsvFormatter.sanitizeFileName("Expense Log: records"));
        assertEquals("a-b-c-d", CsvFormatter.sanitizeFileName("a/b\\c|d"));
    }

    // ---------- assembling a name ----------

    /** The chosen scheme: readable, ISO dates, "to" between them, export type last. */
    @Test
    public void fileName_joinsPartsAndAddsTheExtension() {
        assertEquals("Expense Log 2026-09-01 to 2026-09-30 list.csv",
                CsvFormatter.fileName("Expense Log", "2026-09-01", "to", "2026-09-30", "list"));
    }

    /** The type is what stops a list and a summary of the same range overwriting each other. */
    @Test
    public void fileName_distinguishesTheExportTypes() {
        String list = CsvFormatter.fileName("Expense Log", "2026-09-01", "to", "2026-09-30", "list");
        String summary = CsvFormatter.fileName("Expense Log", "2026-09-01", "to", "2026-09-30", "summary");

        assertNotEquals(list, summary);
    }

    /** An absent part must not leave a double space behind it. */
    @Test
    public void fileName_dropsEmptyAndNullParts() {
        assertEquals("Expense Log list.csv",
                CsvFormatter.fileName("Expense Log", "", null, "  ", "list"));
    }

    /** The extension is added here so no call site can forget it — the original defect. */
    @Test
    public void fileName_alwaysEndsInCsv() {
        assertTrue(CsvFormatter.fileName("anything").endsWith(".csv"));
        assertTrue(CsvFormatter.fileName("Expense Log", "all records", "2026-09-06", "list")
                .endsWith(".csv"));
    }

    /** A name is sanitised before the extension, so the extension itself cannot be mangled. */
    @Test
    public void fileName_sanitisesThePartsButKeepsTheExtension() {
        assertEquals("Expense Log- records list.csv",
                CsvFormatter.fileName("Expense Log: records", "list"));
    }

    /** Names sort chronologically, because the app-name prefix is constant. */
    @Test
    public void fileNames_sortChronologically() {
        String july = CsvFormatter.fileName("Expense Log", "2026-07-01", "to", "2026-07-31", "list");
        String august = CsvFormatter.fileName("Expense Log", "2026-08-01", "to", "2026-08-31", "list");
        String september = CsvFormatter.fileName("Expense Log", "2026-09-01", "to", "2026-09-30", "list");

        assertTrue(july.compareTo(august) < 0);
        assertTrue(august.compareTo(september) < 0);
    }

    // ---------- the BOM: finding A ----------

    /** Without this first character Excel on Windows reads the file as cp1252. */
    @Test
    public void bom_isTheUnicodeByteOrderMark() {
        assertEquals('\uFEFF', CsvFormatter.BOM);
    }

    /** Local time, because that is what the export writes and what the user reads back. */
    private long millisAt(int year, int month, int day, int hour, int minute) {
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.set(year, month, day, hour, minute, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }
}
