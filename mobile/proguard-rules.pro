# R8 rules for the release build.
#
# Every rule here names what would break without it and how it was found. A keep rule that nobody
# can justify is a keep rule nobody can remove, and the reason this file was empty until the
# release work is that no release build had ever been run.

# ---------------------------------------------------------------------------
# AndroidPlot
# ---------------------------------------------------------------------------
# The graph is configured from fragment_graph.xml through AndroidPlot's XmlConfigurator, which
# resolves attribute names like ap:lineLabelRotationBottom to setters by REFLECTION at inflation
# time. R8 sees no caller for any of them and removes or renames them, and the graph then inflates
# with none of its styling -- or throws, depending on which setter went.
-keep class com.androidplot.** { *; }
-dontwarn com.androidplot.**

# ---------------------------------------------------------------------------
# The Dropbox SDK
# ---------------------------------------------------------------------------
# The AAR ships no consumer rules of its own (checked: neither 7.0.0 nor 7.1.1 contains a
# proguard.txt), so the app has to carry them.
#
# The wire format is Jackson-based: every request and response type is serialized by name through
# the Stone-generated Serializer classes, which R8 cannot see being used.
-keep class com.dropbox.core.** { *; }
-keep class com.dropbox.android.** { *; }
-dontwarn com.dropbox.core.**
# The SDK compiles against a handful of things it does not ship and does not need at runtime on
# Android: the servlet API (for its web-app helpers), the desktop OkHttp and Google App Engine
# request-config implementations, and the JSR-305 annotations.
-dontwarn javax.servlet.**
-dontwarn com.google.appengine.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------------------
# Preference screens, and everything else the framework instantiates by name
# ---------------------------------------------------------------------------
# res/xml/pref_root.xml names its fragments in app:fragment, res/xml/pref_reminders.xml names
# TimePreference as a custom element, and SettingsActivity opens a fragment named in an Intent
# extra. All three are resolved by string at tap time, so R8 sees no reference at all: it would
# strip the classes and the screens would throw when opened. SettingsHeadersTest catches a wrong
# name in a debug build; it cannot catch a class R8 removed from the release one.
-keep class de.timowa.expenselog.SettingsActivity$* { *; }
-keep class de.timowa.expenselog.AccountPreferencesActivity$* { *; }
-keep class de.timowa.expenselog.reminders.TimePreference { *; }
-keep class de.timowa.expenselog.reminders.TimePreferenceDialogFragment { *; }
# AndroidX Preference inflates every preference type from XML by class name, through a constructor
# that takes (Context, AttributeSet).
-keep public class * extends androidx.preference.Preference {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# ---------------------------------------------------------------------------
# Views inflated from layouts
# ---------------------------------------------------------------------------
# WrapRowLayout is used from records_export_dialog.xml by name.
-keep public class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------------------------------------------------------------------------
# What is deliberately NOT kept
# ---------------------------------------------------------------------------
# Dagger 2 generates ordinary Java that R8 can follow, so it needs no rules.
# The icon fonts are read by key at runtime, but the keys are data in the database and the enums
# are reached through IconFonts, which R8 does see.
# The .edb format is SQLite, not serialization, so no model class is reflected over.
# Serializable is used only for ArrayLists passed between activities in the same process, where
# the JDK's own serialization of ArrayList and String applies.
