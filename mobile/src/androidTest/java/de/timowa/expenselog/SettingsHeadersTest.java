package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.res.XmlResourceParser;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pins the settings root: Reminders is a section of its own, and every row in it actually opens.
 *
 * <p>The reminder settings were once a row inside the General screen wired to an intent; they are
 * a row of the root screen now. Two things can quietly undo that and neither fails the build: the
 * row can come back inside General, giving one screen two routes that then drift apart, and a
 * row's {@code app:fragment} can name a class that does not resolve.
 *
 * <p>That second one is the real trap. The attribute is a string, and the nested-class separator
 * is a {@code $}, not a dot -- {@code SettingsActivity$RemindersPreferenceFragment}. Get it wrong
 * and everything compiles, the row renders, and tapping it throws at the user. So this resolves
 * every declared row the way the framework will, rather than trusting the string.
 *
 * <p>The root was {@code pref_headers.xml}, a {@code <preference-headers>} document that only the
 * deprecated {@code PreferenceActivity} could read. It is an ordinary {@code PreferenceScreen}
 * now, so the attribute lives in the {@code app} namespace rather than {@code android}.
 *
 * <p>Reads resources only, touches no preferences and opens no database, so unlike the {@code
 * DBAdapter} tests it needs no {@link IsolatedDatabaseContext}.
 */
@RunWith(AndroidJUnit4.class)
public class SettingsHeadersTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";

    private static final String REMINDERS_FRAGMENT =
            "de.timowa.expenselog.SettingsActivity$RemindersPreferenceFragment";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    /** Every row names a fragment, and every one of those fragments exists and is a Fragment. */
    @Test
    public void everyRootRow_namesAFragmentThatResolves() {
        List<String> fragments = rootFragments();
        assertFalse("the settings root declares no rows", fragments.isEmpty());
        for (String name : fragments) {
            try {
                Class<?> type = Class.forName(name);
                assertTrue(name + " is not a Fragment", Fragment.class.isAssignableFrom(type));
                // The host instantiates by name through the fragment factory; a fragment with no
                // no-argument constructor fails there rather than here, so check that too.
                type.getConstructor();
            } catch (ClassNotFoundException e) {
                fail("no such fragment: " + name);
            } catch (NoSuchMethodException e) {
                fail(name + " has no public no-argument constructor");
            }
        }
    }

    /** Reminders is a row of the root, not buried inside another screen. */
    @Test
    public void reminders_isASectionOfItsOwn() {
        assertTrue("the reminder settings are not a row of the settings root",
                rootFragments().contains(REMINDERS_FRAGMENT));
    }

    /** The order the user sees: Preferences, Reminders, Data & sync, Accounts, About. */
    @Test
    public void rootRows_keepTheirOrder() {
        List<String> titles = rootTitles();
        assertEquals(5, titles.size());
        assertEquals(context.getString(R.string.pref_header_preferences), titles.get(0));
        assertEquals(context.getString(R.string.reminders), titles.get(1));
        assertEquals(context.getString(R.string.pref_header_data_sync), titles.get(2));
        assertEquals(context.getString(R.string.pref_header_accounts), titles.get(3));
        assertEquals(context.getString(R.string.about), titles.get(4));
    }

    /** The reminder rows live on their own screen, never back inside General. */
    @Test
    public void generalScreen_hasNoReminderRows() {
        List<String> keys = keysIn(R.xml.pref_general);
        for (String key : new String[]{
                context.getString(R.string.pref_key_reminder_enable),
                context.getString(R.string.pref_key_reminder_frequency),
                context.getString(R.string.pref_key_reminder_time)}) {
            assertFalse("a reminder preference is back inside the General screen: " + key,
                    keys.contains(key));
        }
    }

    private List<String> rootFragments() {
        return attributeIn(R.xml.pref_root, APP_NS, "fragment");
    }

    private List<String> rootTitles() {
        List<String> titles = new ArrayList<>();
        for (String raw : attributeIn(R.xml.pref_root, ANDROID_NS, "title")) {
            titles.add(raw);
        }
        return titles;
    }

    /** Values of one attribute on every element of a preference document, in document order. */
    private List<String> attributeIn(int xmlRes, String namespace, String name) {
        List<String> values = new ArrayList<>();
        try (XmlResourceParser parser = context.getResources().getXml(xmlRes)) {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    int resId = parser.getAttributeResourceValue(namespace, name, 0);
                    if (resId != 0) {
                        values.add(context.getString(resId));
                    } else {
                        String value = parser.getAttributeValue(namespace, name);
                        if (value != null) values.add(value);
                    }
                }
                event = parser.next();
            }
        } catch (XmlPullParserException | IOException e) {
            fail("could not read the preference document: " + e);
        }
        return values;
    }

    private List<String> keysIn(int xmlRes) {
        return attributeIn(xmlRes, ANDROID_NS, "key");
    }
}
