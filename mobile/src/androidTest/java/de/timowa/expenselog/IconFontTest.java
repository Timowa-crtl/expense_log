package de.timowa.expenselog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import de.timowa.expenselog.icons.Icon;
import de.timowa.expenselog.icons.IconDrawable;
import de.timowa.expenselog.icons.IconFonts;

/**
 * Every icon key the app can store must resolve to a glyph.
 *
 * <p>Category icons live in the database as font keys ("fa-tags", "md-local-dining", "mdi-cat"),
 * written there by the picker from {@code icon_list.xml} and by the first-run defaults from
 * {@code settings_keys.xml}. The icon fonts were vendored out of android-iconify 2.2.2 in the
 * modernization; a key that stopped resolving would leave a blank square beside a record, on a
 * database the app cannot re-create. This is the tripwire for that.
 */
@RunWith(AndroidJUnit4.class)
public class IconFontTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    /** Every key offered by the category icon picker. */
    @Test
    public void everyPickerKey_resolvesToAGlyph() {
        assertAllResolve(R.array.iconList);
    }

    /** Every key the first run writes for the default categories. */
    @Test
    public void everyDefaultCategoryKey_resolvesToAGlyph() {
        assertAllResolve(R.array.defaultExpenseIcons);
        assertAllResolve(R.array.defaultIncomeIcons);
    }

    /** The three fonts are distinct and each contributes its own keys. */
    @Test
    public void allThreeFonts_areRegistered() {
        assertNotNull("FontAwesome", IconFonts.find("fa-tag"));
        assertNotNull("Material", IconFonts.find("md-local-dining"));
        assertNotNull("Material Community", IconFonts.find("mdi-cat"));
    }

    /** An unknown key is null rather than an exception, so a stale database row cannot crash a list. */
    @Test
    public void unknownKey_isNull() {
        assertNull(IconFonts.find("fa-there-is-no-such-icon"));
    }

    /** Keys keep the dashed form the database stores, not the enum's underscores. */
    @Test
    public void keys_keepTheirStoredForm() {
        Icon icon = IconFonts.find("md-local-gas-station");
        assertNotNull(icon);
        assertEquals("md-local-gas-station", icon.key());
    }

    /** A drawable actually paints the glyph: a coloured icon marks the bitmap it is drawn into. */
    @Test
    public void iconDrawable_paintsItsGlyph() {
        IconDrawable drawable = new IconDrawable(context, "fa-tag").sizePx(48).color(Color.RED);
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        drawable.draw(new Canvas(bitmap));
        int painted = 0;
        for (int x = 0; x < 48; x++) {
            for (int y = 0; y < 48; y++) {
                if (bitmap.getPixel(x, y) != Color.TRANSPARENT) painted++;
            }
        }
        bitmap.recycle();
        assertTrue("the glyph painted no pixels", painted > 0);
    }

    private void assertAllResolve(int arrayRes) {
        String[] keys = context.getResources().getStringArray(arrayRes);
        assertTrue("empty icon array", keys.length > 0);
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (IconFonts.find(key) == null) missing.add(key);
        }
        if (!missing.isEmpty()) fail("no glyph for " + missing);
    }
}
