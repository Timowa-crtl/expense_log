package de.timowa.expenselog;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Turns values into RFC 4180 CSV fields, rows and timestamps.
 *
 * <p>This exists as a class of its own so the export format can be tested without an
 * {@link android.app.Activity}. {@code SpreadsheetHelper} runs its export behind a private method
 * reached through an {@code AlertDialog}, so nothing about the produced file was assertable while
 * the formatting lived inside it. Everything here is pure: no context, no preferences, no I/O.
 *
 * <p><b>Every field written to a CSV must go through {@link #field(String)}.</b> The export used to
 * append category and account labels raw, so a category named {@code Food, drink} shifted every
 * column after it, and it "escaped" notes by <i>deleting</i> quote characters, which lost data. Both
 * are what this class exists to stop.
 *
 * <p>Dates and times are deliberately <b>not</b> the user's display format. The Date Format setting
 * governs what is on screen; an export is data, and data is ISO 8601. Both formatters are pinned to
 * {@link Locale#US} so a locale with non-Latin digits cannot render a machine-readable column in
 * digits a parser will not accept.
 *
 * <p>Instances are cheap and are <b>not</b> thread-safe, because {@link SimpleDateFormat} is not.
 * Build one per export and keep it on that thread.
 */
class CsvFormatter {

    /** RFC 4180's separator, and what the Currency column exists to keep out of the amounts. */
    static final String DELIMITER = ",";

    /** LF rather than the RFC's CRLF: every target in play accepts it and CRLF buys nothing. */
    static final String LINE_ENDING = "\n";

    /**
     * The byte-order mark, written as the first character of the file.
     *
     * <p>Without it Excel on Windows reads a UTF-8 file as cp1252 and renders {@code €} as
     * {@code â‚¬}. The content was always UTF-8; only the marker announcing it was missing.
     */
    static final char BOM = '\uFEFF';

    private final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private final DateFormat timeFormat = new SimpleDateFormat("HH:mm", Locale.US);

    /** The date of {@code millis}, as {@code yyyy-MM-dd}. */
    String date(long millis) {
        return dateFormat.format(new Date(millis));
    }

    /**
     * The time of {@code millis}, as {@code HH:mm}.
     *
     * <p>Zero-padded and 24-hour, so midnight is {@code 00:00} rather than the {@code 0:00} the
     * display formatter produced, and the column sorts as text.
     */
    String time(long millis) {
        return timeFormat.format(new Date(millis));
    }

    /**
     * An amount as a bare number: dot decimal, no grouping, no currency symbol, two decimals.
     *
     * <p>The currency belongs in its own column. Amounts used to be written as display currency —
     * {@code "-6.666,00 €"}, quoted, grouped, with a non-breaking space — which no spreadsheet
     * could total.
     */
    static String amount(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    /**
     * One field, quoted only when it has to be.
     *
     * <p>RFC 4180: a field containing the delimiter, a quote, CR or LF is wrapped in quotes and its
     * own quotes are doubled. Everything else is written bare, which is what keeps the file
     * readable. A null becomes empty — {@code KEY_NOTES} is nullable, and the export used to call
     * {@code replace} on it unguarded.
     */
    static String field(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(DELIMITER) || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** One row: every field escaped, joined by the delimiter. No line ending. */
    static String row(String... fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                line.append(DELIMITER);
            }
            line.append(field(fields[i]));
        }
        return line.toString();
    }

    /**
     * Assembles a file name from its parts and gives it the extension.
     *
     * <p>Space-separated, sanitised, always ending in {@code .csv}. Empty and null parts are
     * dropped rather than leaving a double space, so a caller can pass an optional part
     * unconditionally.
     *
     * <p>The extension is the point. The exported file used to arrive with none at all, so Windows
     * would not open it with a spreadsheet — appending it here rather than at the call sites is
     * what makes that impossible to forget.
     */
    static String fileName(String... parts) {
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            if (name.length() > 0) {
                name.append(' ');
            }
            name.append(part.trim());
        }
        return sanitizeFileName(name.toString()) + ".csv";
    }

    /**
     * Strips the characters Windows and Android refuse in a file name.
     *
     * <p>The pieces a name is built from are the app's own strings and ISO dates, so this changes
     * nothing today; it is here so a translated string containing a colon cannot produce a file
     * that will not save.
     */
    static String sanitizeFileName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
    }
}
