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

/** One glyph in an icon font: its key, as stored in the database ("fa-tag"), and its code point. */
public interface Icon {

    String key();

    char character();
}
