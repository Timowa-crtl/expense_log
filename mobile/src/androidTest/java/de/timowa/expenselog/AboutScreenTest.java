package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.fragment.app.Fragment;
import android.content.Context;
import android.content.res.XmlResourceParser;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Pins the About screen: that it is reachable, that it resolves, and that its links are real URLs.
 *
 * <p>About moved out of the three-dot menu, where it was an AlertDialog with room for a version
 * and one link, into a section of its own. It is the only settings screen that is a plain {@link
 * Fragment} rather than a {@code PreferenceFragment}, because it hosts a layout; the header
 * mechanism allows that, but nothing checks it at compile time -- {@code android:fragment} is a
 * string, and a wrong one throws when the user taps the row rather than when the build runs.
 *
 * <p>Reads resources only, so it needs no {@link IsolatedDatabaseContext}.
 */
@RunWith(AndroidJUnit4.class)
public class AboutScreenTest {

    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String APP_NS = "http://schemas.android.com/apk/res-auto";

    private static final String ABOUT_FRAGMENT =
            "de.timowa.expenselog.SettingsActivity$AboutFragment";

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    /** About is a section of the settings root, not a menu item. */
    @Test
    public void about_hasAHeaderOfItsOwn() throws Exception {
        assertTrue("pref_root.xml declares no row for " + ABOUT_FRAGMENT,
                headerFragments().contains(ABOUT_FRAGMENT));
    }

    /** The fragment the header names must load, and must be one the activity will show. */
    @Test
    public void aboutHeader_resolvesToAFragmentThatWillOpen() throws Exception {
        Class<?> fragmentClass;
        try {
            fragmentClass = Class.forName(ABOUT_FRAGMENT);
        } catch (ClassNotFoundException e) {
            fail(ABOUT_FRAGMENT + " does not resolve to a class"
                    + " (a nested class is separated by $, not by a dot)");
            return;
        }
        assertTrue("a settings row can only open an androidx Fragment",
                Fragment.class.isAssignableFrom(fragmentClass));
    }

    /** Every link has to be an absolute http(s) URL, placeholder listing or not. */
    @Test
    public void aboutLinks_areAbsoluteUrls() {
        for (int urlRes : new int[]{R.string.url_google_play, R.string.url_f_droid,
                R.string.url_github}) {
            String url = context.getString(urlRes);
            Uri uri = Uri.parse(url);
            assertNotNull(url, uri.getScheme());
            assertTrue(url + " is not http(s)",
                    "https".equals(uri.getScheme()) || "http".equals(uri.getScheme()));
            assertNotNull(url + " has no host", uri.getHost());
        }
    }

    /**
     * The Play and F-Droid listings do not exist yet, so their URLs are the ones those listings
     * will have -- which only works if they carry this build's actual package name.
     */
    @Test
    public void listingLinks_carryThisPackageName() {
        String pkg = context.getPackageName();
        assertTrue("the Play URL must name this package, or it will not become correct on publish",
                context.getString(R.string.url_google_play).contains(pkg));
        assertTrue("the F-Droid URL must name this package",
                context.getString(R.string.url_f_droid).contains(pkg));
    }

    /** The old route must stay gone, so About has one home rather than two. */
    @Test
    public void overflowMenu_noLongerCarriesAbout() throws Exception {
        XmlResourceParser parser = context.getResources().getXml(R.menu.main);
        try {
            for (int event = parser.getEventType();
                 event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (event != XmlPullParser.START_TAG) {
                    continue;
                }
                assertFalse("the three-dot menu carries About again; it is a settings section now",
                        parser.getAttributeResourceValue(ANDROID_NS, "title", 0)
                                == R.string.about);
            }
        } finally {
            parser.close();
        }
    }

    /** Settings is what the overflow is left holding. */
    @Test
    public void overflowMenu_stillOffersSettings() throws Exception {
        XmlResourceParser parser = context.getResources().getXml(R.menu.main);
        boolean found = false;
        try {
            for (int event = parser.getEventType();
                 event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (event == XmlPullParser.START_TAG
                        && parser.getAttributeResourceValue(ANDROID_NS, "title", 0)
                        == R.string.action_settings) {
                    found = true;
                }
            }
        } finally {
            parser.close();
        }
        assertTrue("Settings is the one item the overflow must keep", found);
    }

    /**
     * The fragments the settings root opens.
     *
     * <p>Reads {@code pref_root.xml}, an ordinary PreferenceScreen. It was
     * {@code pref_headers.xml} with {@code android:fragment} on {@code <header>} elements, which
     * only the deprecated {@code PreferenceActivity} could read; the attribute is in the
     * {@code app} namespace now.
     */
    private java.util.List<String> headerFragments()
            throws XmlPullParserException, IOException {
        java.util.List<String> fragments = new java.util.ArrayList<>();
        XmlResourceParser parser = context.getResources().getXml(R.xml.pref_root);
        try {
            for (int event = parser.getEventType();
                 event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    String fragment = parser.getAttributeValue(APP_NS, "fragment");
                    if (fragment != null) {
                        fragments.add(fragment);
                    }
                }
            }
        } finally {
            parser.close();
        }
        return fragments;
    }
}
