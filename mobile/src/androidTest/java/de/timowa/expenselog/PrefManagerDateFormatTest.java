package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pins the Date Format setting: the two ISO 8601 options, the month-name format as the on-screen
 * default, and the retirement of values 3 and 4.
 *
 * <p>This setting governs the app's own screens only. CSV exports are pinned to ISO 8601 by
 * {@code CsvFormatter} regardless of it, which is what lets the default here be a readable
 * month-name format without making an export ambiguous. {@code CsvFormatterTest} covers that side.
 *
 * <p>The two {@code EEE, MMM d} formats were removed from
 * {@code R.array.pref_date_format_values} and {@code "5"} and {@code "6"} were appended for
 * ISO 8601 extended and basic. New values are appended rather than reusing a retired number, so
 * a phone still holding {@code "3"} can never silently come back as a different format — it
 * falls back to the default and {@code PrefManager}'s constructor rewrites it.
 *
 * <p>Runs on an {@link IsolatedDatabaseContext}, which is mandatory here and not a formality:
 * {@code PrefManager} reads and writes the app's <b>default</b> {@code SharedPreferences} through
 * {@code PreferenceManager.getDefaultSharedPreferences}, which resolves via
 * {@code Context.getSharedPreferences}. Given the real application context these tests would
 * overwrite the maintainer's own date format — and, since {@link #setUp()} clears the file first,
 * every other setting on the phone with it.
 */
@RunWith(AndroidJUnit4.class)
public class PrefManagerDateFormatTest {

    /** 25 January 2016, the date the option labels in {@code strings.xml} all show. */
    private static final int YEAR = 2016;
    private static final int MONTH = Calendar.JANUARY;
    private static final int DAY = 25;

    private Context context;
    private SharedPreferences preferences;
    private String key;

    @Before
    public void setUp() {
        context = new IsolatedDatabaseContext(ApplicationProvider.getApplicationContext());
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        key = context.getString(R.string.pref_key_date_format);
        preferences.edit().clear().commit();
    }

    @After
    public void tearDown() {
        preferences.edit().clear().commit();
    }

    /** ISO 8601 extended. Pinned to ASCII digits: a localised ISO 8601 date is not ISO 8601. */
    @Test
    public void isoOption_formatsAsYearMonthDay() {
        assertEquals("2016-01-25", formatSampleWith("5"));
    }

    /** ISO 8601 basic: the same date with the separators dropped. */
    @Test
    public void isoBasicOption_formatsAsCompactDigits() {
        assertEquals("20160125", formatSampleWith("6"));
    }

    /** The two ISO options must differ only by their separators. */
    @Test
    public void isoBasic_isTheExtendedFormWithoutSeparators() {
        assertEquals(formatSampleWith("5").replace("-", ""), formatSampleWith("6"));
    }

    /**
     * Value {@code "0"} renders the month-name format, and is the on-screen default.
     *
     * <p>The month name is English whatever the device language (see {@code AppLocale}), so this
     * is compared against an English rendering, not the device locale's.
     */
    @Test
    public void mediumOption_rendersWithAMonthName() {
        assertEquals(expectedMedium(), formatSampleWith("0"));
    }

    /**
     * A fresh install with nothing stored renders the month-name format on screen.
     *
     * <p>ISO was briefly the on-screen default too. It is not any more, and it does not need to be:
     * {@code CsvFormatter} pins <i>exports</i> to ISO independently of this setting, so the screen
     * can show {@code Jan 25, 2016} while the CSV stays machine-readable. Before the export
     * clean-up those were the same decision.
     */
    @Test
    public void unsetPreference_rendersTheMonthNameFormat() {
        assertEquals(expectedMedium(), new PrefManager(context).getDateFormat().format(sampleDate()));
    }

    /** The ISO options remain available, they are simply not the default. */
    @Test
    public void isoOptions_areStillOffered() {
        assertEquals("2016-01-25", formatSampleWith("5"));
        assertEquals("20160125", formatSampleWith("6"));
    }

    /**
     * {@code R.string.default_date_format} and {@code PrefManager}'s own default must agree.
     *
     * <p>They are two separate declarations of one fact: the resource is what
     * {@code android:defaultValue} gives the ListPreference to pre-select, while {@code PrefManager}
     * is what actually renders a date while the key is absent. Let them drift and the settings row
     * shows one format selected while the app displays another.
     */
    @Test
    public void declaredDefault_matchesTheCodeDefault() {
        String declared = context.getString(R.string.default_date_format);

        assertEquals("the declared default must render what an unset preference renders",
                new PrefManager(context).getDateFormat().format(sampleDate()),
                formatSampleWith(declared));
    }

    /**
     * A retired value must not resolve to a format. {@code getDateFormat} keeps the default it was
     * initialised with for any value it does not recognise.
     */
    @Test
    public void retiredValues_fallBackToTheDefaultFormat() {
        assertEquals(expectedMedium(), formatSampleWith("3"));
        assertEquals(expectedMedium(), formatSampleWith("4"));
    }

    /**
     * A retired value left in preferences is rewritten to the default on construction.
     *
     * <p>Without this the settings row shows no selected radio and a blank summary:
     * {@code SettingsActivity}'s summary listener maps a value that is not in the entry array to a
     * {@code null} summary.
     */
    @Test
    public void retiredValue_isRewrittenToTheDefault() {
        for (String retired : new String[]{"3", "4"}) {
            preferences.edit().putString(key, retired).commit();

            new PrefManager(context);

            assertEquals("a retired value must not survive construction",
                    context.getString(R.string.default_date_format),
                    preferences.getString(key, null));
        }
    }

    /** A value that is still offered must be left alone. */
    @Test
    public void offeredValues_areNotRewritten() {
        for (String offered : context.getResources().getStringArray(R.array.pref_date_format_values)) {
            preferences.edit().putString(key, offered).commit();

            new PrefManager(context);

            assertEquals("an offered value must survive construction",
                    offered, preferences.getString(key, null));
        }
    }

    /** An unset preference stays unset — the migration must not create the key. */
    @Test
    public void unsetPreference_isNotCreated() {
        new PrefManager(context);

        assertNull(preferences.getString(key, null));
    }

    /** The labels and the values are read positionally by {@code ListPreference}. */
    @Test
    public void optionLabelsAndValues_areTheSameLength() {
        assertEquals(context.getResources().getStringArray(R.array.pref_date_format_options).length,
                context.getResources().getStringArray(R.array.pref_date_format_values).length);
    }

    /**
     * The tripwire for the two arrays drifting out of step with {@code PrefManager}.
     *
     * <p>An offered value that {@code getDateFormat} does not recognise is invisible in the UI —
     * it silently renders as the default, so the setting simply appears not to work. Distinct
     * output per option is what proves every offered value is actually wired up.
     */
    @Test
    public void everyOfferedValue_rendersDistinctly() {
        String[] values = context.getResources().getStringArray(R.array.pref_date_format_values);
        Set<String> rendered = new HashSet<>();
        for (String value : values) {
            rendered.add(formatSampleWith(value));
        }
        assertEquals("every offered value must map to a format of its own in PrefManager.getDateFormat",
                values.length, rendered.size());
    }

    /** The sample date through the month-name format, in English. */
    private String expectedMedium() {
        return new SimpleDateFormat("MMM d, yyyy", Locale.US).format(sampleDate());
    }

    /** Stores {@code value} as the date format and formats the sample date through it. */
    private String formatSampleWith(String value) {
        preferences.edit().putString(key, value).commit();
        return new PrefManager(context).getDateFormat().format(sampleDate());
    }

    /** Midday on the sample date, so no format can be shifted across a day boundary. */
    private java.util.Date sampleDate() {
        Calendar sample = Calendar.getInstance();
        sample.set(YEAR, MONTH, DAY, 12, 0, 0);
        return sample.getTime();
    }
}
