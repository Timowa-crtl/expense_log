/*
 * Vendored from android-iconify 2.2.2 (Joan Zapata, Apache License 2.0), which was last
 * published in 2016 and compiled against com.android.support:support-v4:22.2.1 -- the only
 * reason this project needed jetifier. The library was five small classes and three icon fonts;
 * this package is those classes, trimmed to what the app uses, with the fonts under
 * assets/icons/. Category icons are stored in the database as font keys ("fa-tags",
 * "md-local-dining", "mdi-cat"), so the keys, the enums and the glyph code points are kept
 * exactly as they were. See NOTICE for the font licences.
 */
package de.timowa.expenselog.icons;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * The three icon fonts and the lookup from key to glyph. Registration is static and lazy, so
 * there is nothing to call at start-up: the first {@link IconDrawable} loads the tables.
 */
public final class IconFonts {

    /** One font file and its glyphs. */
    private static final class Font {
        final String asset;
        final Map<String, Icon> byKey = new HashMap<>();
        Typeface typeface;

        Font(String asset, Icon[] icons) {
            this.asset = asset;
            for (Icon icon : icons) byKey.put(icon.key(), icon);
        }

        synchronized Typeface typeface(Context context) {
            if (typeface == null) {
                typeface = Typeface.createFromAsset(context.getAssets(), asset);
            }
            return typeface;
        }
    }

    private static final Font[] FONTS = {
            new Font("icons/android-iconify-fontawesome.ttf", FontAwesomeIcons.values()),
            new Font("icons/android-iconify-material.ttf", MaterialIcons.values()),
            new Font("icons/android-iconify-material-community.ttf", MaterialCommunityIcons.values()),
    };

    private IconFonts() {
    }

    /** The icon for a stored key, or null if no font has it. */
    public static Icon find(String key) {
        for (Font font : FONTS) {
            Icon icon = font.byKey.get(key);
            if (icon != null) return icon;
        }
        return null;
    }

    static Typeface typefaceOf(Context context, Icon icon) {
        for (Font font : FONTS) {
            if (font.byKey.get(icon.key()) == icon) return font.typeface(context);
        }
        throw new IllegalStateException("Icon " + icon.key() + " belongs to no font");
    }
}
