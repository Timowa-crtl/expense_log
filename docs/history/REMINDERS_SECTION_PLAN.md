# Reminders as its own settings section

**Status: implemented and verified on device.** On branch `tweaks`, in PR #6. One departure from the
plan: `SettingsHeadersTest` asserts every header names a `Fragment`, not a `PreferenceFragment` —
the narrower assertion written here failed as soon as the About screen, a plain `Fragment`, was
added as a header in the same branch.

Move the reminder settings out of **General** and up to a top-level section in the settings root,
between General and Data & sync.

## Why this is small

The work the name suggests — turning a nested screen into a real one — was already done by the
`sdk36` branch. `RemindersPreferenceFragment` is a genuine `PreferenceFragment` over
`pref_reminders.xml`, it is already listed in `SettingsActivity.isValidFragment`, and it already
gets a toolbar and window insets like every other settings screen.

The *only* thing that makes it read as "inside General" is a single row:

- a bare `<Preference android:key="@string/pref_key_reminders_screen">` in `pref_general.xml`, and
- the intent that gives that row a destination, built in `GeneralPreferenceFragment.onCreate`
  (`SettingsActivity.java:239-250`) so the fragment name stays compile-checked.

So the change is: delete that row, and declare a header instead.

## What does *not* change

**The reminder notification's Settings action already points straight at the fragment.**
`MainActivity.java:192` routes `ACTION_SETTINGS` through
`navigator.openSettings(this, RemindersPreferenceFragment.class.getName(), R.string.reminders)`,
with `EXTRA_NO_HEADERS`. It never went via General, so promoting Reminders does not break it and it
needs no edit. Back from that screen leaves settings, which is what the Accounts deep links do too;
that stays as it is — the notification is a one-shot jump, not an entry into the settings tree.

Also unchanged: `isValidFragment` (already whitelists the fragment), `Navigator.openSettings`,
`ReminderReceiver`, `ReminderManager`, `NotificationPermission`, and every preference key the
reminder screen actually reads (`pref_key_reminder_enable` / `_frequency` / `_time`). No persisted
value moves, so there is no migration and no upgrade path to think about.

## Steps

### 1. Declare the header

`mobile/src/main/res/xml/pref_headers.xml` — insert between the General and Data & sync headers:

```xml
<header
    android:fragment="de.timowa.expenselog.SettingsActivity$RemindersPreferenceFragment"
    android:icon="@drawable/ic_notifications_black_24dp"
    android:title="@string/reminders" />
```

`ic_notifications_black_24dp` already exists in every density bucket — it is the icon the
commented-out `NotificationPreferenceFragment` header above it was going to use. Delete that dead
comment block while here; the header it describes is the one being added, and leaving both is
confusing.

Title reuses `@string/reminders` ("Reminders", `strings.xml:270`) rather than minting a
`pref_header_reminders`. The siblings use `pref_header_*` only because those strings exist for no
other purpose; `@string/reminders` is already the title the notification deep link passes as
`EXTRA_SHOW_FRAGMENT_TITLE`, so reusing it means the header and the screen title cannot drift.

### 2. Remove the row from General

`mobile/src/main/res/xml/pref_general.xml` — delete the `<Preference>` and the comment above it
explaining why the intent is built in Java.

### 3. Remove the wiring

`SettingsActivity.GeneralPreferenceFragment.onCreate` — delete the `final Preference reminders = …`
block. `Intent` and `PreferenceActivity` imports stay; both are used elsewhere in the file
(`AccountSelectionPreferenceFragment`, `onBuildHeaders`).

Update the javadoc on `RemindersPreferenceFragment`: the "Both ways in land here" paragraph now
names the header rather than the General row.

### 4. Drop the now-unused key

`mobile/src/main/res/values/settings_keys.xml:43` — delete `pref_key_reminders_screen`. It is
`translatable="false"` and was never a persisted preference, only a `findPreference` handle, so
nothing reads it after step 3. Grepped: no other Java, XML, or test reference.

### 5. Pin it

New `mobile/src/androidTest/java/de/timowa/expenselog/SettingsHeadersTest.java`, in the style of
`FileProviderPathsTest` — assert the declaration directly rather than driving the UI (there is no
Espresso in this module, and adding it for this would be out of proportion):

- parse `R.xml.pref_headers` with `getResources().getXml(...)` and assert a `<header>` names
  `…SettingsActivity$RemindersPreferenceFragment`;
- assert `isValidFragment` accepts that name (it does today — this catches someone pruning the
  whitelist later);
- parse `R.xml.pref_general` and assert it declares no preference pointing at the reminders
  fragment, so the row cannot quietly come back and give two routes to one screen.

Uses the real application context and touches no database, so `IsolatedDatabaseContext` is neither
needed nor appropriate here.

### 6. Verify

- `:mobile:assembleDebug`, then `:mobile:lintDebug` — baseline is 0 errors / 241 warnings; an
  unused-resource warning would be the expected shape of a regression if step 4 were skipped.
- `:mobile:connectedDebugAndroidTest` on the `Pixel_6` AVD — 49 existing tests plus the new one.
- On the emulator: open Settings and confirm the root list reads General / Reminders / Data & sync /
  Accounts; open Reminders from the header and confirm the toolbar title, the insets, and that the
  switch, frequency and time rows all still work and still re-arm the alarm.
- Fire a reminder and tap its **Settings** action; confirm it still lands on the reminder screen and
  that Back leaves settings. This is the one path a reviewer would assume broke, so it is worth
  showing that it did not.

## Files touched

| File | Change |
| --- | --- |
| `res/xml/pref_headers.xml` | add header, drop dead comment |
| `res/xml/pref_general.xml` | remove the Reminders row |
| `SettingsActivity.java` | remove the intent wiring, update javadoc |
| `res/values/settings_keys.xml` | remove `pref_key_reminders_screen` |
| `androidTest/…/SettingsHeadersTest.java` | new tripwire |

## Risk

Low. No persisted state, no schema, no migration, nothing in the `FileHelper` / import / backup /
alarm paths. The realistic failure mode is a typo in the `android:fragment` attribute — the `$`
nested-class separator is easy to get wrong, and a wrong name fails at tap time with
`IllegalArgumentException` rather than at build time. Step 5's test is aimed squarely at that.
