# Expense Log — where the project stands

**Status: three phases, the SDK 36 bump, the format clean-up, the settings tidy-up, the Records
toolbar and export work (PR #7), the export staging fix (PR #8), the reliability pass (PR #9), the
finishing touches (PR #10), editing recurring entries (PR #11), the Back fix (PR #12), the New
Record rework (PR #14), the multi-select work (PR #15), the modernization (PR #16) and the tweaks
(PR #18) are merged to `main`. Every library older than 2020 is gone, so is every deprecated API the app used, and the
project produces a signed, R8-shrunk App Bundle. The signed release build of PR #16 — and with it
everything merged before it — has run on both physical phones against real records, and the tweaks passed their phone checklist
on a debug build.**

This is the live document. `CLAUDE.md` holds the constraints that still bind when changing code;
`README.md` holds the handover terms and what a builder must supply. The detailed phase plans are
archived under [`docs/history/`](docs/history/) — history, not instructions.

| | |
| --- | --- |
| App | `de.timowa.expenselog` v2.0 (versionCode 1) |
| Toolchain | Gradle 9.7.1, AGP 9.4.0, JDK 21, compileSdk 36, minSdk 26, **targetSdk 36**, Java 17 |
| Tests | 301 instrumentation tests across 47 classes, 0 failures. JVM unit tests are still a stub. |
| Lint | 0 errors, nothing suppressed (86 warnings, down from 222) |
| Verified on | Pixel 4a (Android 14) and Unihertz Jelly Star (Android 13), against 2540 real records, and again on the signed release build of PR #16; the SDK 36 work adds an Android 16 emulator pass, plus a device check of the Dropbox login and the reminder notification; the format, settings and Records work since then was verified on a `Pixel_6` emulator (API 33); the reliability pass on two API 33 emulators (`Pixel_6`, `Pixel_6_B`) with Dropbox sync between them |

---

## The three phases, in one page each

### Phase 1 — Revival · merged · [`REVIVAL_PLAN.md`](docs/history/REVIVAL_PLAN.md)

A January 2021 source snapshot that **did not build** — it failed at Gradle configuration time,
before any source compiled. Phase 1 made it build and run.

Removed outright rather than ported: Firebase Analytics and Crashlytics, AdMob and the MoPub
mediation adapter, Play Services, and the entire in-app billing stack (8 `util/Iab*` classes plus
the AIDL). Premium features are unconditional as a result. A developer backdoor that toggled premium
when you typed `1111111` as an amount went with them.

**No data leaves the device**, and that was verified against the built APK rather than the dependency
tree — all dex files scanned for Firebase, Crashlytics, Play Services, MoPub, billing and ads.

Its own review is archived at [`PR_REVIEW_REVIVAL.md`](docs/history/PR_REVIEW_REVIVAL.md).

### Phase 2 — Rehabilitation · merged (PR #2) · [`REHABILITATION_PLAN.md`](docs/history/REHABILITATION_PLAN.md)

Seven steps to make the app current, gated on device testing throughout.

- **targetSdk 29 → 35**, which is what everything else in the phase existed to make possible.
- **Scoped storage**: the app writes only to its own directory and reads user files through
  `ACTION_OPEN_DOCUMENT`. No storage permissions remain in the manifest.
- **The image feature was deleted** rather than migrated — the maintainer's call after device
  testing showed what migrating it would have cost.
- **Notifications**: `POST_NOTIFICATIONS` requested, and the notification-trampoline path removed
  (it is blocked at targetSdk 31+ and fails silently).
- **Dropbox** moved to SDK 7.0.0 with the PKCE auth flow, the credential in
  `EncryptedSharedPreferences`, and the 7 `AsyncTask` jobs rewritten onto `ExecutorService`.
- **Identity**: 78 files moved off the original author's namespace to `de.timowa.expenselog` v2.0.
- Migration scaffolding that **throws on an unregistered version step** instead of silently doing
  nothing.

### Phase 3 — Recovery · merged (PR #3) · [`RECOVERY_PLAN.md`](docs/history/RECOVERY_PLAN.md)

Phase 2 was merged *as tested* rather than edited, so that its two-phone verification record stayed
intact — which left its own code review unactioned. Phase 3 is that debt paid: all nine findings in
[`REVIEW_FINDINGS.md`](docs/history/REVIEW_FINDINGS.md) closed, in seven steps.

**Two were data-loss bugs**, and they are the reason the phase existed:

- **A newer-schema `.edb` import could leave the app permanently unopenable.** The path getters
  opened the database to learn a path, so the rollback could not restore a database that would not
  open — the only case a rollback exists for. Now the import refuses a newer file before touching
  anything.
- **The instrumentation suite overwrote the user's real backup.** The isolation harness redirected
  the database but not the directories, so a test run copied its own database over
  `ExpenseLogBackup.edb` — while testing the import path whose failure is the one situation that
  backup exists for.

The other seven: the CSV fallback root, the dead `file://` import branch, a denial toast on every
launch, `FileHelper` toasting from worker threads, Keystore work twice per resume, a dead method, and
a `<queries>` filter that never matched.

**Running the checks corrected three things reading the code had not**: the `file://` branch was
unreachable *by design* rather than merely buggy (deleted); one of the new regression tests passed
against the bug it was meant to catch; and finding 4's stated cause was imprecise. All recorded in
the plan.

---

## SDK 36 · merged (PR #4) · [`SDK36_PLAN.md`](docs/history/SDK36_PLAN.md)

Google Play requires API 36 of a new listing since 31 August 2026, and this app has no listing yet,
so the bump is not optional. compileSdk and targetSdk both moved 35 → 36 in two commits, the split
deliberate so a build failure and a behaviour failure could not be confused for one another.

The toolchain did **not** move: AGP 8.13.2 supports up to API 36.1, so ButterKnife,
`android.nonFinalResIds=false`, jetifier and AppCompat 1.2.0 were never at risk. That was the
pivotal question, since AGP 9 would have dragged the whole ButterKnife migration along with it.

**One behaviour change actually bit: predictive back.** At targetSdk 36 `onBackPressed()` is no
longer called, `MainActivity` overrides it to close the drawer and run `backPressCount`, and
`androidx.activity` resolves to 1.0.0 here — before `OnBackInvokedCallback` existed — so nothing
would have registered a back callback at all. The manifest now carries the documented temporary
opt-out, `android:enableOnBackInvokedCallback="false"`, with the reasoning beside it. Migrating to
`androidx.activity` 1.6+ and an `OnBackPressedCallback` is the follow-up that removes it, and is
deferred because it means moving AndroidX under the pinned AppCompat/Material.

Everything else on the API 36 list is a no-op here and was checked one by one: no orientation or
resizability locks, no `elegantTextHeight`, no `ScheduledExecutorService`, no sensors, no Bluetooth,
no LAN discovery, no native code (so the 16 KB page-size rule is already satisfied — the APK has
zero `.so` entries).

**Running it on Android 16 found a real bug that predates the bump.** The preference screens were
not inset for edge-to-edge: `SettingsActivity`'s first header row, "General", was drawn underneath
the toolbar and could not be tapped, and the strip behind the status bar was unpainted, leaving
white status-bar icons on a near-white background. Edge-to-edge is enforced from targetSdk **35**
(the platform's `ENFORCE_EDGE_TO_EDGE` compat id says so, and disabling it for the package moved the
row back below the toolbar), so this was live on Android 15 and 16 and unseen only because both
verification phones run Android 13 and 14. targetSdk 36 removes the opt-out that could have hidden
it, so it is fixed rather than deferred — in the shared `AppCompatPreferenceActivity`, which covers
`SettingsActivity` and `AccountPreferencesActivity` together. `MainActivity` was never affected.

That fix needed a second pass after the maintainer ran the build on the two phones. It had been
written to run on every Android version, and on Android 13 and 14 both halves of it are wrong:
painting the decor view replaced the theme's `android:windowBackground`, which was the only thing
drawing those screens white, and the inset padding was added on top of an inset the framework had
already applied, leaving a blank strip below the toolbar. `applySystemBarInsets` now returns
immediately below API 35 — `ENFORCE_EDGE_TO_EDGE` exists only on Android 15 and above — and the
window background is restored on the content root where it does run.

**Three more fixes came out of running it, and two of them predate the bump as well.** Backing out
of the Dropbox login relaunched the login, with no way off the screen: a `SwitchPreference` persists
the moment it is tapped, so sync read as enabled before any account was connected, which is exactly
the condition that starts an auth. `DropBoxHelper.handleDeclinedAuth()` now turns the preference
back off, from both resume paths that can receive a decline. The reminder notification's Settings
action opened the settings header list rather than the reminder settings — it has never done
anything else, in this branch or in the 2021 snapshot. And sending it where it belonged exposed the
third: the reminder settings were a nested `<PreferenceScreen>`, which the legacy framework renders
as a bare `Dialog` — no toolbar, no title, no insets, so under edge-to-edge the switch sat behind
the status-bar icons. They are now `RemindersPreferenceFragment` over `pref_reminders.xml`, a real
screen, reached identically from the notification and from the settings list (where PR #6 later
promoted it out of General into a section of its own).

The Android 16 pass covered the suite (49/49), back navigation, both screen edges, reminders, the
notification permission, and export plus the local backup rotation. Dropbox could not be run in it —
not for want of credentials, but because the emulator would not boot again once Android Studio was
open and competing for host memory — and was covered afterwards instead, on the maintainer's own
phone: the login both ways, completing it and declining it, against the fix above. The same pass
confirmed that the reminder notification's Settings action opens the reminder settings as intended,
and exercised the build as a whole by ordinary use.

---

## Format clean-up · merged (PR #5) · [`CSV_EXPORT_PLAN.md`](docs/history/CSV_EXPORT_PLAN.md)

Three pieces of format work, ordered so each made the next possible.

- **Date Format options**: two ambiguous weekday formats retired and both ISO 8601 forms added,
  pinned to `Locale.US` so a locale with non-Latin digits cannot render a non-ISO "ISO" date. New
  values are **appended, never reusing a retired number**, and `migrateRetiredDateFormat` rewrites a
  stored value once — without it the settings row shows a blank summary and no selected radio.
- **First day of week → Monday.** Changing the resource alone would have moved nothing: nothing
  calls `PreferenceManager.setDefaultValues()`, so the default that actually decided where a week
  starts was a hardcoded `"0"` at three call sites. All three read the resource now.
- **The CSV export**, all twelve findings in the plan closed: UTF-8 with a BOM (without it Excel
  read the file as cp1252), bare dot-decimal amounts with currency in its own column, `yyyy-MM-dd`
  and `HH:mm` columns that sort, RFC 4180 quoting on every field, a padded total row, a cursor leak
  that cost ~5000 leaked cursors on a 2540-record export, and file names that carry the range **and**
  the export type — list and summary of the same range used to overwrite each other silently.

The new `CsvFormatter` is pure — no context, preferences or I/O — which is what makes the format
testable at all, and is why **the screen and the export are now independent**: `Jan 25, 2016` on
screen, ISO in exports. Before this they were a single setting and you could not have both.

---

## Settings tidy-up · merged (PR #6) · [`REMINDERS_SECTION_PLAN.md`](docs/history/REMINDERS_SECTION_PLAN.md) · [`CSV_EXPORT_LOCATION_PLAN.md`](docs/history/CSV_EXPORT_LOCATION_PLAN.md)

Three changes, each about where something belongs: the reminder settings, an exported file, and
the three-dot menu.

**Reminders became a top-level settings section**, between General and Data & sync. The screen
itself was already a real fragment (the `sdk36` work); all that remained was a row in
`pref_general.xml` and the intent behind it, replaced by a header. `SettingsHeadersTest` is the
tripwire: a wrong `android:fragment` name fails at tap time, not at build time.

**Exports choose where they go, and both destinations are offered.** Export raised a share sheet,
which answers *who should receive this file* — saving to the device is not something an app
registers an intent filter for, so local storage could never appear in that list. Both exports now
offer **Save** (`ACTION_CREATE_DOCUMENT`, the picker the import side has used since Step 4; Drive
and Dropbox appear in it as document providers) beside **Share**. The `.edb` export also stopped
asking for `"file/db"`, which is not a MIME type and matched nothing, and gets a dated filename via
`EXTRA_TITLE` rather than being `expenseLog.edb` every time. The staged path lives in
`SharedPreferences`, not a field: the picker is another activity and the app can be killed behind
it.

**Staged exports are cleaned up by recorded path, not by a directory listing.** The first version
swept the staging folder against an allowlist — but that folder also holds `ExpenseLogBackup.edb`,
what a failed import is rolled back from, and `LocalBackupManager`'s three rotating copies. Deleting
by name left all of them one bad predicate away from destruction, which is finding 2 of
[`REVIEW_FINDINGS.md`](docs/history/REVIEW_FINDINGS.md) again. Cleanup can now only reach a path the
app itself wrote, which is the idiom `importFileAndVerify` has always used.

**The three-dot menu is down to Settings** — plus Export on the Records screens, the only item there
that acts on what you are looking at (both later left the menu; see the next section). Rate and Share both pointed at a Play listing that does not
exist. About is a settings section now: version read from the installed package, three links, and a
licence line naming both copyright holders.

Removing the rating system **nearly broke the backups**: `setFirstLaunch()` was called from
`RatingManager.initialize()`, so deleting the rating prompt would have taken the only writer of
`pref_key_first_launch_time` with it — and `LocalBackupManager` gates the weekly and monthly backups
on time since first launch. Unwritten it reads 0, that span becomes about fifty years, and both fire
immediately on a fresh install, visibly failing nothing. The write moved to `MainActivity.onCreate`.

Verified on a `Pixel_6` emulator (API 33): 113 tests green, lint 0 errors. On device: all four
export combinations, a saved CSV read back with its BOM, CRLF and ISO dates intact, a saved `.edb`
imported back, and the four backups intact through every export.

---

## Records toolbar and export · merged (PR #7)

Seven commits, all about the Records screens and the spreadsheet export they lead to. No plan file:
each was mocked up, agreed, built and checked on the emulator against the 2544-record test database.

**A period boundary bug, fixed first.** `getPeriodStart` and `getPeriodEnd` cleared hours, minutes
and seconds but not milliseconds, so a period began at whatever millisecond the clock was on. A
record saved in a period's first second was in or out by chance — May 2022 exported 40 records once
and 39 after, missing a 2290.00 salary at 00:00:00.216. The records lists and totals used the same
functions. Start now sets 0 and end 999; `PeriodBoundsTest` pins both and checks neighbouring
periods tile across both daylight-saving changes.

**The export dialog shows what it exports.** It used to take whatever page the records screen was
on, silently. It now opens on that screen's duration, period and view and lets you change them: a
"Records by" dropdown, a period stepper, a preview line with the record count and exact dates, and
List / Summary checkboxes. Nothing changed there is written back to the records screen.

**Export is a mini FAB, not a menu item.** A white 40 dp button with a spreadsheet icon, a second
`FloatingActionButton` in `app_bar_main.xml` that only `LogTabsFragment` shows (in `onResume`) and
hides (in `onStop`), the same lifecycle as the duration spinner. Seven fragments re-purpose the
shared `R.id.fab`, which is why it is a separate view. It sits over + on List and Summary and drops
into the + corner on Graph and Calendar, where + is hidden; it is placed by margins, not
`layout_anchor`, because `hide()` leaves + `GONE`. List and Summary pad for both buttons with their
own dimen, and the graph pads for the lone button, which otherwise covered the last x-axis label.

**The toolbar lost its three-dot menu.** Grouping Options moved onto Graph's toolbar in the slot
Sort takes on List (Graph has no sort). That left Settings as the menu's only item on every screen,
so it is now a gear button in the same rightmost slot. Having the three dots open Settings directly
was considered and not done: the three dots promise a menu. The duration spinner's closed label was
also shortened to "by Month" and so on, the open list keeping "Records by …".

**The export's filter chip says what the filter is.** One chip per part, in the filter dialog's
order: the type in the list's own colours (red Expenses, green Incomes), categories in amber,
accounts in teal, each naming two and then "+N". The filtered count moved into the preview line
("13 of 15 records · dates"). The chips wrap in `WrapRowLayout`, a small new `ViewGroup`, because
Material's `ChipGroup`/`Chip` need a MaterialComponents theme and this app is AppCompat.

---

## Export staging · merged (PR #8) · [`EXPORT_STAGING_PLAN.md`](docs/history/EXPORT_STAGING_PLAN.md)

**A database export deleted the current local backup.** Raised by the review on PR #7; the bug came
with PR #6. The `.edb` export was staged at `getAppFilesDir()/expenseLog.edb`, which is also
`LocalBackupManager`'s current backup, and PR #6 deletes the staged path after Save, after Cancel,
and at the next export of either kind after a Share. The backup was then missing until the next
record save.

The rule "cleanup deletes only the path it recorded" held; what nobody checked was that the recorded
path was not also a file the app keeps. So exports, `.edb` and CSV, are now staged in
`getAppFilesDir()/exports/`, the `.edb` under its dated name (which is also what a share target now
sees), and cleanup refuses to delete anything whose canonical parent is not that folder. The refusal
also covers a path recorded by the previous build, which still names `expenseLog.edb`.
`CLAUDE.md` carries it as `FileHelper` rule 5.

`ExportStagingTest` runs the real staging against a real `checkBackups`; it was seen to fail against
unchanged code first. Verified on a `Pixel_6` emulator (API 33) with the test database: 122 tests
green, lint 0 errors; Save and Cancel leave `exports/` empty, Share leaves the dated `.edb` there, a
later CSV Save clears it and `expenseLog.edb` survives; the saved `.edb` imports back with every
record and the CSV carries its BOM. The upgrade case was run for real: Share on `main`'s build
recorded `…/files/expenseLog.edb`, this branch installed over it, and the next CSV export logged the
refusal and left the backup in place.

---

## Reliability pass · merged (PR #9) · [`RELIABILITY_PLAN.md`](docs/history/RELIABILITY_PLAN.md)

A full-codebase review for data integrity and main-thread work raised 20 findings; executing the
plan added six more, four follow-up reviews of the branch found nine more in its own changes, and
testing the Dropbox paths against a real account found one more. Every finding is fixed, deferred
with a reason, or shown not to reproduce. Where it was possible, each fix has a test that was first
seen to fail against the old code, and a before/after run on an emulator using an APK built from
`main`. The plan is the full record, step by step.

**What was wrong, and is not any more:**

- **Repeating records.** A weekly series crossing New Year never stopped inserting (on the main
  thread, until the disk filled); monthly series from the 29th–31st drifted onto the 1st–3rd; a huge
  or zero frequency froze the save or landed a record millions of years in the past. Occurrences are
  computed from the original date, in one transaction, and an impossible series is refused up front.
- **Records pages.** On the 29th–31st next month's page ran into the month after it, and week pages
  overlapped with a Monday or Saturday start — skewing lists, totals, budgets and CSV exports. The
  calendar dropped records saved in a day's first or last second.
- **Dropbox.** Every save uploaded with `OVERWRITE` and marked itself synced, so a device with a
  stale view silently replaced another device's upload. Uploads are now conditional on the version
  the device last saw, and a refusal lands in the conflict dialog — shown once, not stacked. An
  imported `.edb` is uploaded; a restore no longer adopts the backup's version; a download stands
  down when a record was saved during it; a delete made during an upload is not declared synced; a
  main file deleted on Dropbox is recreated without looping; tasks run one at a time again; the
  German-locale weekly backup refreshes.
- **Copies and imports.** Copies of the live database were taken while it could be written (3 of
  6 436 raw copies corrupt under load); a stale `-journal` was replayed onto an imported file behind
  an "OK"; an import went ahead when its safety backup failed. Copies are now consistent snapshots
  and a replacement is an atomic rename.
- **Connections and concurrency.** Every `DBAdapter` opened its own connection and none were closed
  (18 open after a short session; `SQLITE_BUSY` with two writers). There is one connection per
  process, and a read/write lock keeps an import from closing it under a read, a cursor or a
  transaction on another thread — each of which used to crash or lose data.
- **Crashes and smaller bugs.** Paging the calendar quickly crashed the app. A failed CSV export
  said "saved". The repeat end-date picker opened on the wrong date. Cursor leaks; two-table deletes
  without a transaction.
- **Performance.** The calendar ran 31 queries on the main thread plus a connection and two `SUM`s
  per day cell per layout pass; it loads a month in one background query. An index on `start_time`
  was measured and not needed (under 1.5 ms per query at 10 000 records).

**How it was verified:**

- **192 instrumentation tests** (122 before), lint 0 errors / 237 warnings.
- **The whole on-phone checklist on two emulators**, with no human steps: upgrade from `main` over
  existing data with every captured page identical; every calendar day of two months against an
  independent host computation; repeating records through the UI; export, import and CSV; the
  suite-isolation check (`am instrument`, real backup checksum unchanged); and two-device Dropbox
  sync against the throwaway account — handover both ways, a save by a device that had not seen the
  other's upload, an offline device, a save during an 80 MB download, and a restore received by the
  other device. No crashes on either.

**Still worth doing on the real phones (optional):** the Dropbox handover with the real 2540 records
over a real mobile network. Nothing above depends on it; it is the only part an emulator cannot
supply.

---

## Finishing touches · merged (PR #10)

Small, visible polish, each piece mocked up, agreed and checked on the `Pixel_6` emulator.

**Settings.** "General" is now **Preferences**, with Material's sliders icon; it had shared About's
circled "i". The screen's title bar says "Settings" instead of the app name (a label on
`SettingsActivity`; sub-screens still take their header's title). The Accounts header used a grey,
oversized PNG that pushed its label out of line; it now uses the drawer's group-of-people icon,
traced from the FontAwesome `users` glyph, so both ways into accounts share one symbol.

**A new launcher icon.** The old one was the original author's calendar-note PNG, part of an
identity the Apache licence does not grant. The new icon keeps the teal (`#009688`), the amber
(`#FFAA00`) and the long shadow to the lower right, with a rounded euro sign: Nunito Black's outline
(SIL OFL 1.1), centred on its round body rather than its bounding box, which had looked pushed
right. It is an adaptive icon — background colour, foreground vector with a gradient shadow, and a
monochrome layer for Android 13+ themed icons — so the five legacy PNG densities are gone.
Mockups that drew on the whole 108-unit canvas were scaled by 72/108 for the visible area, which
keeps the sign inside the 66 dp safe zone. The About screen shows the same icon. The reminder
notification's small icon is the same euro as a white vector, reusing the monochrome path.

**Store icon.** `fastlane/metadata/android/en-US/images/icon.png`, 512×512, rendered from the same
layers as a full square (Play rounds it). F-Droid reads it from that path; upload the same file to
Play.

**Verified:** build green, lint 0 errors / 232 warnings (five fewer: removing the notification PNGs
emptied five obsolete `drawable-*-v11` folders). On the emulator: the Settings list, the Preferences
sub-screen title, the drawer, the icon in the app list (square launcher mask) and on About, and a
fired reminder showing the euro. Then on the Pixel 4a (circle mask) and the Unihertz Jelly Star
(squircle mask), both fine. The themed icon has been checked only in renders of the resource files.
A code review of the branch found no defects. The instrumentation suite was not re-run; nothing it
covers changed.

---

## Recurring entries · merged (PR #11)

A feature request: a recurring entry could not really be edited, and the Delete dialog offered only
Delete One and Delete All. A salary that rises from April had no way to change from April on. The
design was settled in five questions with the maintainer, with one constraint added afterwards:
**no schema change**.

**One choice everywhere: only this entry / this and following / the whole series.**
`RecurringScope` is the single dialog, used by the edit screen, the Records list, the calendar and
the new overview. Choices come with counts ("This and following (4 entries)"). From a series' first
entry, "this and following" already is the whole series, so it is offered once. The calendar used
to delete a recurring record without asking about its series at all.

- **Values from here on** (a raise) *split* the series: the following records keep their dates and
  ids and move to a new series; the old one ends the day before. Past amounts are untouched, and a
  later "whole series" edit of either part cannot undo the other.
- **A new interval or date** from here on replaces the following records with a new schedule from the
  edited date. "Only this entry" is not offered with a schedule change; "the whole series" only from
  the first entry.
- **The end date is editable.** Earlier deletes the records past it; later adds the missing ones,
  with the latest record's values.

**Extending needs a start date, and the database has none.** `RepeatingTable` stores interval and
end, not the start, and adding a column would bump `DATABASE_VERSION` and make new backups
unimportable on older builds. `RepeatingSeries.findOrigin` recovers one from the remaining records:
for months and years, the earliest record on the latest day of the month any record has, so a
series on the 31st stays on the 31st even when its first remaining record is 28 February. Every
record is checked against the result to the millisecond, and a series that does not fit (drifted by
the old stepping bug, or created in another time zone) is refused rather than guessed at. **All 39
non-empty series in the realistic test database fit exactly.** The one wrong case is documented and
pinned: if every record that showed the real day has been deleted, an extension lands early in
longer months.

**The Recurring overview** is a new drawer entry: each series with its interval, end, and next (or
last) date; ended series greyed below the active ones. Tap edits the next entry; a long press offers
Edit, End series (deletes future entries) and Delete series. Its + opens a new record with repeat
already on. The edit screen now shows "Entry 9 of 12 in this series" and the schedule, which was
hidden before.

**Two small fixes on the way.** Opening a record for editing replaced its seconds and milliseconds
with the current ones, so every edit moved the record slightly, and a series record always looked
"moved". Turning on repeat with no interval saved an ordinary record without a word; the interval
now defaults to 1 month, and an empty one is refused.

**Verified:** 220 instrumentation tests (28 new in `RecurringEditTest`), lint 0 errors / 233
warnings (one new: the drawer's switch gains a case). On the `Pixel_6` emulator with the test
database: the overview, a raise from the 9th of 12 salary entries (ids and timestamps unchanged,
earlier amounts untouched), an interval change to every 2 months, "this and following" and "only
this" deletes from the list and the calendar, End series, extending a yearly series by two years
through the date picker, the + with repeat preset, the empty-interval refusal; each checked in the
pulled database, no crashes in logcat.

**Code review.** A review of the PR found one bug, fixed: the overview's + turned "Repeat
Transaction" back on at every resume and rotation, so a record the user had switched to a one-off
could silently be saved as a series. Checked on the emulator afterwards (off survives leaving the app
and rotating both ways); suite and lint re-run, unchanged. It also noted, without changing either:
the edit screen reloads the schedule from the database on resume, as it already did for amount and
notes; and moving a date more than one interval earlier with "this and following" makes the new
series overlap the kept part.

**The edit screen says "Edit Record".** It said "New Record" whenever opened from the list, the
calendar or the overview: every caller used that as the back-stack name, which the navigation
listener writes into the toolbar after the screen has set its own title. The same name made New
Record in the drawer do nothing while a record was open for editing. `Navigator.editRecord` now
opens every edit under "Edit Record"; checked on the emulator from the overview and the list,
including New Record from the drawer and back navigation.

---

## Back no longer closes the app after a few edits · merged (PR #12)

Reported by the maintainer: open the third entry of a series with Edit, press Back, and the app
closes. It was not a crash, and not the third entry: `MainActivity.backPressCount` counted every
Back press and only a drawer tap reset it, so the third Edit → Back of any kind closed the app with
the list still underneath. Reproduced on the emulator first (third Back → launcher, crash log empty).

The counter was the original author's cap on walking back through drawer screens, and the
maintainer chose to keep that: **Back from a sub-screen (an editor, New Record from a list, New
Category) never counts; three Back presses through drawer screens still leave the app.** A
sub-screen is recognised by its fragment having no tag: only `Navigator.changeFragment` sets one,
and a tag survives rotation. The back-stack name cannot tell them apart, since New Record is both.
The rule is `MainActivity.backLeavesApp`, separate so a test can drive it.

Two changes on the way. Back on the last screen now leaves at once, instead of popping it and
leaving from the empty-stack listener. And leaving no longer calls `System.exit(0)`, which also
killed a Dropbox upload the last save had just queued; the filter it used to clear is now cleared
in `Navigator.exitApp`.

**Verified:** `BackNavigationTest` (5 tests, over a real back stack in a debug-only host activity,
no database) fails at "round 3" with the old counting and passes with the fix. Full suite 225
tests, 0 failures; lint 0 errors / 233 warnings, unchanged. On the `Pixel_6` emulator with the test database: Edit → Back six times from the list stays
on the list; Back with the drawer open only closes it; Back from the list goes to the start screen
and Back from there leaves (process still alive, relaunch fine); Recurring → Edit → New Category →
Back ×4 goes New Category → Edit Record → Recurring → start screen → out. No crashes in logcat.

## Repeat settings, and a form that keeps its values · merged (PR #14)

Reported by the maintainer from a phone screenshot: the unit beside "Every" was cut off ("Mo..")
and nothing said what the number counts. The layout was picked from options put to him:

- **One sentence per row**: "Every [ 3 ] [ weeks ▾ ]". The count is a 64dp field (4 digits, no hint:
  a grey "1" read as an entered value), and the unit takes the rest of the row. The unit's words follow the count — "month" beside 1,
  "months" otherwise — through `repeat_unit_*` plurals, in `RepeatingSeries.PERIOD_*` order; the old
  `frequency_spinner` array ("Day(s)" …) is gone. The adapter is set in `onCreateView`, never on
  resume, because a new adapter resets the spinner after its selection was restored.
- **A summary line under Ending**, updated from the count, unit, record date and end date: "Monthly
  on day 16 · until Sep 16, 2027 · 13 entries", "Every 3 weeks on Wednesday …", "… on day 31, or the
  month's last day". Otherwise it shows why the series cannot be saved (no count, end before the
  record, over the 20 000 cap). The count is left out when editing a series — "Entry 3 of 12" is
  already shown, and what a change touches depends on the scope chosen at save.
  `NewLogFragment.countOccurrences` is what saving writes, plus the record itself;
  `RepeatSummaryCountTest` (3 tests) pins that against `createRepeatingSeries`.
- **Ending is unchanged**, by choice.

**Fixed on the way: the form reset itself on every resume.** `initializeViews` runs from
`onResume` and put the starting values in each time, so switching away from the app and back reset
a new record's date to now, its category and account to the defaults and its series end to a year
out — and threw away every change to a record being edited, amount and notes included. Values are
now filled once (`formFilled`, saved with the instance state alongside the series end); later
resumes only rewire the views and redisplay what the fields hold. What is not shown (the edited
series, the image path) is still read every time, since it does not survive a rotation. A save
clears the flag, as Back can return to the same New Record screen. Re-sorting categories no longer
resets the chosen one either, and a recreated screen with the repeat switch on now shows its rows.

**An empty amount is refused.** It used to save as 0 and report "Record saved" — with repeat on,
a whole series of zeros — for new and edited records alike. `NewLogFragment.hasAmount` now stops the
save with a field error ("Enter an amount"), focus and the keyboard on Amount. A typed 0 still saves:
the maintainer's call, for deliberate zero-cost entries. (A lone "." counts as empty.)

**Verified:** full suite 228 tests, 0 failures; lint 0 errors / 232 warnings (one fewer: the count
field declares `importantForAutofill`). The amount check was added after that run and is not covered
by a test; on the emulator an empty save showed the error and saved nothing, typing cleared it, and
a typed 0 saved. On the `Pixel_6` emulator: the unit dropdown reads
days/weeks/months/years beside 3; summaries as above, with "18 entries" for every 3 weeks over a
year; clearing the count shows the interval message. A new record with its end moved to
5 Sep 2027 kept it across Home → relaunch and across landscape and back; saving it wrote 12 records
(the summary said 12). Editing entry 2 of that series to 99 kept 99 across Home → relaunch (it went
back to 12.00 before), and "Only this entry" saved it (list total 144 → 231). The maintainer then
tried the build on a phone: the row fits at that font size ("Every [1] day" in full), and the
reports of the grey "1" hint and of empty records saving led to the last two changes above.

## Selecting and deleting records, and the series dialogs · merged (PR #15)

Asked for by the maintainer, with the shape picked from options put to him:

- **A long press starts selection**; the old Edit / Delete context menu is gone. Taps then tick and
  untick records. **A tap outside selection opens the record for editing** — it used to do nothing,
  and Edit was only in that menu. The bar shows "3 selected" over "Total: -$231.75" (income minus
  expenses of the selection), with Select all (the current page) and Delete. Back or the arrow ends
  it without leaving the screen or counting towards the exit cap; paging away ends it too; a
  rotation keeps it.
- **Delete of plain records has no dialog; a snackbar offers Undo for 8 s.** Undo restores the
  records exactly, same ids (see `CLAUDE.md`), and refuses if an import or Dropbox download replaced
  the database in between.
- **Repeating entries are selected only on their own** (second round, after the maintainer noticed
  the first version had dropped the series choices from the list). Long-pressing one selects it
  alone, without Select all, and Delete asks Cancel / Only this entry / This and following / The
  whole series, as before. Tapping a repeating entry into a selection, or anything into a repeating
  one, is refused with a snackbar that says why; Select all leaves them out and says how many.
- **Undo covers every choice of that dialog**, in the list and the calendar: the series' entry (end,
  amount, interval) is kept with the records, so undoing "this and following" restores the old end
  and undoing "the whole series" re-creates the series under its own id.
- **Every series dialog is one design, always with all three choices** (third and fourth rounds,
  at the maintainer's request): after a first, wordier version, simplified to a title and outlined
  cards of icon, title and one short line ("Oct 1, 2019", "13 entries", "All 13 entries"). Colour
  ended up on the icons alone (eighth round, below). From the first entry both series choices are
  offered and do the same. When saving, a choice that cannot apply is greyed out with a short
  reason — "Only this entry" with a new interval or end, "The whole series" with a new interval or
  date from a later entry. The recurring overview's long-press menu uses the same cards (Edit
  "Next Oct 1, 2026", End series "7 future entries", Delete series "All 25 entries"); its End and Delete no longer ask
  first but offer Undo, like every other delete.
- **The recurring overview selects like the list** (fifth round, replacing that menu): tap edits,
  a long press selects one series, and the bar ("Sim24 / Monthly · next Oct 1, 2026") has only the
  trash, which opens the same three-choice dialog for the record the row shows. Tapping another
  series moves the selection; tapping the selected one or Back clears it; a rotation keeps it. The
  bar is one class now, `SelectionBar`, used by both screens.
- **The edit screen walks a series and deletes** (sixth round). On a series entry, swiping (finger
  left = next) or the ‹ › beside "Entry 9 of 12" loads the neighbouring entry with a short slide;
  unsaved edits are dropped, the arrows grey out at the ends, and a plain record does not move.
  Every edit screen has a trash icon: a plain record is deleted at once, a series entry asks the
  three-choice question, the editor closes, and the snackbar offers Undo. Undo now tells whichever
  list, calendar or overview is showing to refresh (`RecordsChanged`), since the editor is gone by
  then. The recurring overview's ended series sit under a collapsible "Ended (36)" row, collapsed
  by default and remembered. Together that is the answer to "only following entries" from the
  overview: open a series, swipe to the entry, act there.
- **One series block, no swipe, English dates** (seventh round, after mockups on the maintainer's
  request). The swipe felt wobbly — no finger moves perfectly sideways, so the form scrolled under
  it — and was removed; the arrows are the way to move. New Record and a series entry now share
  one block: the header row holds the Repeat switch or the arrows around "**Entry 9** of 12", both
  with the drawer's Recurring icon, and the schedule moved into the header as its second line
  ("Monthly on day 17 · 13 entries"; no "until …", which Ending shows). Errors ("Enter an interval
  of at least 1.") sit under Ending. "on day 31, or the month's last day" became "on day 31 (or last
  day)". A series entry fits a Pixel 4a without scrolling. Everything the app writes is English
  now: dates, weekdays and months ("Sep", not "Sept.") and the date and time pickers, whatever the
  phone's language; amounts keep the device format, and the picker's week starts on the app's
  First day of week.
- **Only the icons carry colour** (eighth round, from mockups). Red on the whole-series card alone
  read as though the other two choices were safe. The delete dialog's cards are neutral now and its
  icons run amber → orange → red as the choice takes more with it, with no green anywhere — none of
  the three is safe. The save dialog, which deletes nothing, keeps the app's teal. Each card also
  has its own icon shape and says how many entries it covers, so nothing rests on colour alone.

**Verified:** `RecordDeleteUndoTest` (15 tests: exact deletes, every column and id restored, a new
record saved meanwhile, gone category/account/series, refusal after a replacement, change counting,
undo of this-and-following, of a cut from the first entry and of a whole series, a cut undone after
the rest of the series went, a cut of a missing series, the selection total, Select all skipping
repeating entries). Full suite 243 tests, 0 failures. Lint 0 errors / 232 warnings.
Second round on the emulator: long-pressing DAK showed "1 selected / Total: -$111.75" without
Select all, and a tap on another record was refused with the explanation; Delete offered Only this
entry / The whole series (13 entries) / Cancel (DAK Oct 2019 is the series' first entry); the whole
series went (expense total down 13 × 111.75) and Undo brought back records and the series entry
identical to before (both tables hashed). With a plain record selected, a tap on DAK was refused;
Select all then selected 2112 of 2544 and said "432 repeating entries not selected" (the database
has 432).
Third round on the emulator: the delete dialog for DAK showed the three cards with the header and
details above; editing Salary entry 9 of 12 with the interval changed to 2 showed the save dialog
with "Only this entry" and "The whole series" greyed out and their reasons, "This and following"
enabled. Nothing was saved. Fourth round: the Sim24 menu showed the three cards above; End series
removed its 7 future entries (2544 → 2537, the row moved to the ended series) and Undo put both
tables back to the same hashes as before; the simplified delete dialog for DAK showed "Oct 1, 2019",
"13 entries", "All 13 entries". Full suite 243 tests, 0 failures; lint 0 errors / 232 warnings.
Fifth round on the emulator: a long press on Sim24 showed the bar above and highlighted the row;
a tap on Salary moved the selection, and its trash offered Only this entry "Sep 27, 2026", This and
following "4 entries", The whole series "All 12 entries"; This and following removed 4 records and
moved Salary to the ended series, and Undo restored both tables to the same hashes. Selecting
first un-faded the ended series; with change animations off they stay faded. Tapping the selected
series and Back each cleared the selection without leaving the screen. Full suite 243 tests,
0 failures; lint 0 errors / 232 warnings.
Sixth round on the emulator: the overview showed 3 active series and "Ended (36)", which expanded
and collapsed. Salary opened at "Entry 9 of 12" (Sep 27, 2026) with the trash icon; a swipe left
moved to entry 10 (Oct 27); › twice more reached entry 12 (Dec 27) with › greyed, and a third tap
stayed there; a rotation there and back stayed on Dec 27. The trash → Only this entry closed the
editor onto the overview with "1 record deleted" (2544 → 2543), and Undo said "1 record restored"
with both tables back to the same hashes. On a plain record ($7, Jan 1, 1982) a swipe did nothing
and no arrows showed; its trash returned to the list with the row gone and the expense total down
7.00, and Undo brought both back in the list underneath. `SeriesNavigationTest` (2 tests) pins the
neighbour lookup. Full suite 245 tests, 0 failures; lint 0 errors / 229 warnings (three fewer:
strings the old overview menu used are gone).
Seventh round on the emulator, switched to German: New Record showed "Sep 17, 2026" and "4:41 PM";
Repeat on gave "Monthly on day 17 · 13 entries" with no line under Ending; 3 weeks gave "Every 3
weeks on Thursday · 18 entries"; an empty count showed "Enter an interval of at least 1." under
Ending while the header kept the schedule. Amounts stayed "2.539,45 €". Salary entry 9 of 12
showed the arrows, "Entry 9 of 12" and "Monthly on day 27"; a swipe did nothing and › moved to
Oct 27. The date picker read "Thu, Sep 17" and "September 2026" with Monday first; the time picker
AM / PM. It first crashed on opening — the English dialog context reused the activity's Theme
object, which the time picker's overlay could not resolve — and opens since the theme is given by
id. Full suite 248 tests, 0 failures, run on the German emulator; `EnglishTextTest` (3 tests) pins
the English schedule wording under a German default. Lint 0 errors / 222 warnings; one local
suppression, `AppBundleLocaleChanges` on `AppLocale.forDialog`, with the reason beside it.
Its own review (`/code-review high`) raised two low findings, both fixed here: a refused series
delete still ran its callback, so the editor closed on a record that still existed (it now says
"Nothing was deleted: this series has changed" and stays); and an Undo wrote the whole old series
entry back, reverting a series edit made in between and leaving mixed schedules — an Undo now
re-attaches records only to a series still exactly as the delete left it, and otherwise brings them
back detached. The same guard covers "Only this entry". Three tests cover it; full suite 251 tests,
0 failures. Colour in the delete dialog was then reworked, from mockups: red on the whole-series card alone
read as though the other two were safe, so colour moved to the icons only and escalates amber →
orange → red, on neutral cards, with no green anywhere. The save dialog, which deletes nothing,
keeps teal icons and outlines. Verified on the emulator, both dialogs; full suite 251 tests, 0
failures.

First round:
On the `Pixel_6` emulator against the realistic test database: selecting $7 + $20 showed
"Total: -$27.00"; Back cleared the selection and stayed on the list; deleting 20, Pp splid and one
DAK series entry removed exactly those 3 rows (2544 → 2541, the rest of series 7 intact), the
expense total fell by 231.75 and the scroll position held; Undo brought the database back to a
row-for-row identical state (same hash); a tap on DAK opened Edit Record.

---

## Modernization · Step 1 — dead code, dead resources, vestigial names · merged (PR #16)

The first step of [`MODERNIZATION_PLAN.md`](docs/history/MODERNIZATION_PLAN.md). Nothing the user sees changed;
the app lost what only a maintainer or a Play reviewer would have had to ask about.

- **Deleted**: `AnalyticsHelper` and every `logAnalytic` call (29 sites, 8 files) and
  `mFirebaseAnalytics` field; `UpdateManager` and its "what's new" dialog, whose text still listed
  the 2020 changes; `TestFragment` with its layout and Dagger injector; `analytics_tracker.xml`
  (a Google Analytics id, referenced by nothing); `drawables.xml`; the `drawable-v21/` and
  `values-v21/` folders (minSdk is 26, so they always won — their contents moved into the base
  folders); 18 PNGs whose names were unreferenced or shadowed by a vector; 43 resources that a
  full lint run reports unused (strings, colours, dimens, anims, a selector), each checked by grep
  before deletion; the unused `WAKE_LOCK` permission; and the `http`/`https` `.edb` intent
  filters, which advertised an import `MainActivity` refuses on arrival.
- **Renamed**: `UpgradeHelper` → `DropboxBootstrap`, with its `NetworkInfo` check replaced by
  `NetworkCapabilities`. `MainActivity.KEY_DEBUG` (always false) → `BuildConfig.DEBUG` at 132 log
  sites, so the release build logs nothing; `buildConfig true` was needed for that, AGP 8 having
  stopped generating the class. The `GGG` log tags became class names.
- **Hardened**: `ReminderReceiver` checks the intent's action, so the boot broadcast re-arms the
  alarm without reading extras (lint `UnsafeProtectedBroadcastReceiver`). The filter dialog's
  static `TextView` is an instance field.

**Verified:** build green; lint **0 errors / 162 warnings** (222 before: `UnusedResources` 43 →
0, `ObsoleteSdkInt` 8 → 6, `UnsafeProtectedBroadcastReceiver` and `RedundantLabel` gone); full
suite **251 tests, 0 failures** on `Pixel_6`. On the emulator: launch and the notification
permission prompt; a reminder broadcast produced the notification; a `BOOT_COMPLETED` broadcast
(as root) re-armed the alarm without reading extras; no crashes in logcat.

## Modernization · Step 2 — ButterKnife out, view binding everywhere

ButterKnife 7.0.1 was last published in 2015 and was the reason `android.nonFinalResIds=false`
existed: its `@Bind` annotations need constant `R` fields, as did the 30 `case R.id.…` labels.

- **23 `@Bind` fields in 7 classes** became generated `viewBinding` fields, following the pattern
  `LogTabsFragment` and `SummaryFragment` already used: inflate into a `binding` field, assign the
  views from it once, null it in `onDestroyView`. `MainActivity` binds `ActivityMainBinding`, which
  needed an id on the `<include>` of `app_bar_main` so the toolbar is reachable through it.
  `NewLogFragment` (16 bindings, plus four `findViewById` calls that a comment explained away) is
  the bulk of it.
- **The five `switch` statements on resource ids** became `if`/`else if` chains: the filter, sorting
  and grouping dialogs, the nav drawer's fragment choice, and the record screen's click handling.
- **ButterKnife and `android.nonFinalResIds=false` are gone.** The dex was checked: zero
  `butterknife` references.

**Verified:** build green; lint **0 errors / 104 warnings** (162 before: `NonConstantResourceId`
56 → 0, `UnusedResources` 2 → 0); suite **251 tests, 0 failures**. On the `Pixel_6` emulator with
the 2544-record test database, every screen that lost a `@Bind` or a `switch`: New Record (the
repeat switch and its summary, the category dialog, both pickers, the ending-date picker),
Categories with a long-press Edit, Accounts, the Calendar, the records List with its long-press
selection bar ("1 selected / Total: -$4.99"), and the sort, filter and grouping dialogs, each
applied and reflected in the totals. No crashes in logcat.

## Modernization · Step 3 — Iconify vendored, jetifier gone

android-iconify 2.2.2 was last published in 2016 and its only dependency was
`com.android.support:support-v4:22.2.1` — the single reason this project set
`android.enableJetifier=true`, which AGP 10 removes.

**The library is now five files in `de.timowa.expenselog.icons`.** Replacing the fonts with vector
drawables was rejected: a category's icon is stored in the database as that project's key
(`fa-tags`, `md-local-dining`, `mdi-cat`), so a key that stopped resolving would blank the icon on
every existing record, and the keys in an old database need not all be in `icon_list.xml`. The
three `.ttf` files moved to `assets/icons/`, the three key-to-code-point enums and the `Icon`
interface came across unchanged, and `Iconify`'s registry plus its descriptor wrapper collapsed
into `IconFonts` — static, lazy, so `MyLogApplication` no longer registers anything at start-up.
`IconDrawable` kept its drawing code, with `getColor` through `ContextCompat` and an honest
`getOpacity`.

`IconFontTest` (6 tests) resolves every key in `icon_list.xml` and both default-category arrays,
checks all three fonts answer, checks an unknown key is null rather than an exception, and draws a
glyph into a bitmap to prove the typeface loads. `NOTICE` names the fonts and their licences
(Font Awesome and Material Design Icons under SIL OFL 1.1, Material Icons under Apache-2.0).

**Verified:** build green; lint 0 errors / 104 warnings, unchanged; suite **257 tests, 0 failures**.
The dependency tree has no `com.android.support` entry and the dex has no `joanzapata` reference.
On the emulator: the drawer's eight icons, the category list across all three fonts, and the
records list's per-category icons with the recurring glyph, all identical to before.

## Modernization · Step 4 — the pivot: Bridge theme, AndroidX forward, real back handling

The step the SDK 36 work deferred. AppCompat 1.2.0 and Material 1.2.1 were pinned from Phase 1
because Material 1.5+ throws *"requires your app theme to be Theme.MaterialComponents"* at launch
against a `Theme.AppCompat` theme — and that pin held every other AndroidX library at its 2019
version, including `androidx.activity` **1.0.0**, which predates `OnBackInvokedCallback` and is why
the manifest carried `enableOnBackInvokedCallback="false"`.

- **The theme's parent is a MaterialComponents *Bridge* variant**, which exists for exactly this
  case and changes nothing on screen. Every screen was compared against the previous build: New
  Record is pixel-identical, as are the list, summary, graph and recurring overview.
- **AppCompat 1.8.0 and Material 1.14.0**, with `activity`, `fragment`, `core`, `lifecycle`,
  `recyclerview`, `coordinatorlayout` and `viewpager` now declared explicitly rather than taken
  transitively, so nothing resolves to 2019 again by accident. `core` is pinned at 1.18.0, not the
  newest: 1.19 requires compileSdk 37 and AGP 9.1, which Step 8 will bring.
- **`MainActivity` registers an `OnBackPressedCallback`** instead of overriding `onBackPressed()`,
  and the manifest opt-out is gone. Nothing else about the back rules changed: the drawer closes
  first, sub-screens never count, the cap still leaves after `BACK_PRESS_LIMIT` drawer screens.
- The two picker fragments moved from the removed `onAttach(Activity)` to `onAttach(Context)`.

**A bug this found, before it shipped:** the callback was written but never registered, so Back did
nothing at all — the drawer stayed open. Caught on the emulator by dumping the view tree before and
after a Back press, and fixed by calling the registration from `onCreate`.

**Verified on API 33** (`Pixel_6`, 2544-record database): Back closes an open drawer; three
Edit → Back rounds each return to the list; a long-press selection is cleared by Back without
leaving the screen; Back from the list reaches the start screen and the next Back leaves the app.
Suite **257 tests, 0 failures**; lint 0 errors / 104 warnings.

**Verified on API 36** (`Pixel_6_API_36`, Android 16): the system log now shows the app registering
an `OnBackInvokedCallback` with the window — priority 0, `mIsAnimationCallback=true` — which is
precisely what `androidx.activity` 1.0.0 could never do and what the manifest opt-out was standing
in for. `BackNavigationTest`, `SettingsHeadersTest`, `ReminderNotificationTest` and `IconFontTest`
run there green (19 tests). **The API 36 UI pass itself is outstanding**: that emulator image
renders black and accepts no touch input on this host (system-server ANRs under memory pressure),
so screenshots, the view tree and taps are unavailable. It belongs with the maintainer's release
pass.

## Modernization · Step 5 — `android.preference` out, AndroidX Preference in

21 files imported the framework preference classes, deprecated since API 29. Four of them *were*
the settings: a `PreferenceActivity` with `<preference-headers>` and an `AppCompatDelegate` bolted
on, which is what made two edge-to-edge bugs possible in the first place — it built its own decor
and left the content root behind the status bar, and it rendered a nested `<PreferenceScreen>` as a
bare dialog with no toolbar or insets.

- **`SettingsActivity` is an ordinary `AppCompatActivity`** with the same layout shape as
  `MainActivity`: a toolbar over a container the fragments are swapped into, inset by
  `fitsSystemWindows`. `AppCompatPreferenceActivity` and its 60 lines of hand-written inset and
  background code are deleted; the framework does it now.
- **The root list is `pref_root.xml`**, a `PreferenceScreen` whose rows name their fragment in
  `app:fragment`, replacing `pref_headers.xml`. Same five rows, same order, same icons. An
  `ALLOWED_FRAGMENTS` list replaces the framework's `isValidFragment`, for the same reason it
  existed: the fragment name can arrive in an Intent extra.
- **The five fragments keep their names and their `pref_*.xml`**, so the reminder notification's
  deep link still lands on the same definition as the settings row. `SwitchPreference` became
  `SwitchPreferenceCompat`.
- **`TimePreference` is split in two**, as AndroidX requires: the preference holds the value, and
  a new `TimePreferenceDialogFragment` owns the picker. Same key, same stored long, same format.
- **`AccountPreferencesActivity` follows the same shape**, and its account id is a fragment
  argument rather than an `EXTRA_SHOW_FRAGMENT` / `EXTRA_NO_HEADERS` pair, which AndroidX has not.
  Each account still reads and writes its own `account<id>` preference file.
- `SettingsHeadersTest` and `AboutScreenTest` were rewritten against the new root.

**A crash this found, before it shipped:** both settings activities still used the theme with a
decor action bar while now supplying their own toolbar, so opening Settings threw *"This Activity
already has an action bar supplied by the window decor"*. They take `AppTheme.NoActionBar` now, as
`MainActivity` does.

**Verified** on the `Pixel_6` emulator, every screen: the root list and its five rows in order;
Preferences with its bound summaries; Reminders, including the time dialog, which persisted a new
value and re-armed the alarm for the next day; Data & sync with the export dialog; Accounts and one
account's budget screen, reading `account1.xml` and showing the budget info dialog; About with the
version from the installed package. The reminder notification's Settings action still lands
directly on the reminder screen. Back restores the previous title at each level. Suite **258 tests,
0 failures**; lint **0 errors / 94 warnings** (104 before).

## Modernization · Step 6 — the remaining deprecated APIs

Mechanical, once Step 4 had put a current `androidx.activity` and `androidx.fragment` underneath.

- **`AsyncTask` is gone.** The records list and the calendar load through a new `BackgroundLoad`:
  one executor per screen, so a calendar month and a list page no longer queue behind each other
  on `AsyncTask`'s process-wide serial executor. `DropboxTask` keeps its own single shared thread,
  which is load-bearing.
- **The three `ProgressDialog`s** became an `AlertDialog` with a spinner, built by
  `DropboxDialogs.progress` and dismissed through the same helper as before.
- **`startActivityForResult` is gone**: the CSV export goes through a launcher on `MainActivity`,
  the database import and export through two on the Data & sync fragment, and the Dropbox backup
  list is a plain `startActivity` because nothing ever read its result.
- **`BackupListActivity` is an `AppCompatActivity` over a `RecyclerView`**, with a toolbar; it was a
  `ListActivity` binding `@android:id/list` by framework convention and had no toolbar at all.
- **`onActivityCreated` → `onViewCreated`** in 12 files; **`setHasOptionsMenu` → `MenuProvider`**
  in the two fragments that have a menu, bound to the *view* lifecycle so the items go with the
  screen; six fragments that asked for a menu they never created simply stopped asking.
- `android.app.AlertDialog` → the AppCompat one everywhere, so every dialog takes the app theme,
  and `Bundle.getSerializable` → `BundleCompat`.

**A crash this found, before it shipped:** `BackgroundLoad.cancel()` shut its executor down for
good, but a fragment on the back stack outlives its view and loads again when it returns — so
coming back from the editor to the records list threw `RejectedExecutionException` and killed the
app. Cancelling now drops pending results and releases the thread, and the next load starts a new
one.

**Verified** on the `Pixel_6` emulator: the toolbar carries exactly the right items per screen
(List: Sort, Filter, Settings; Graph: Grouping, Filter; Calendar: Filter, Settings), the editor
adds a trash icon that is gone again after Back while the list's own icons return, fast calendar
paging is clean, and a CSV export ran through the picker to a saved 112 KB file in Downloads with
the staging directory cleared afterwards. Suite **258 tests, 0 failures**; lint **0 errors /
92 warnings**.

## Modernization · Step 7 — the Dropbox credential off a deprecated library

`androidx.security:security-crypto` was deprecated **in full** at 1.1.0 — every API, no successor
library — "in favour of existing platform APIs and direct use of Android Keystore". It held the
Dropbox credential.

`SecretStore` is that direct use: an AES-256-GCM key in `AndroidKeyStore`, a fresh random IV per
write, and IV plus ciphertext Base64-encoded into ordinary `SharedPreferences`. The key is created
on first use and needs no user authentication, because the background sync reads the credential
with the screen off. A value that cannot be decrypted reads as **no credential** rather than
throwing: this is read at every launch, and an exception there would crash the app instead of
asking for a login.

**Nothing is migrated, by the maintainer's decision**: the old library is gone, so its file cannot
be read, and `discardLegacyStore` deletes that file and its master key on first open. Devices log
into Dropbox once more after this update.

**Verified** on the `Pixel_6` emulator against the throwaway test account: enabling sync opened the
PKCE login with the four expected scopes, Allow completed it, and the credential landed in the new
`dropbox_credential.xml` as Base64 ciphertext with no readable token. The client then ran a real
sync check and raised the conflict dialog, so the credential works. A force-stop and relaunch went
straight back to syncing with **no second login**. `SecretStoreTest` (9 tests) covers the round
trip, a second instance reading the same value, that the plaintext is not on disk, that two writes
of the same value differ, and that a tampered, unparseable or missing value reads as absent.
Suite **267 tests, 0 failures**; lint 0 errors / 92 warnings.

## Modernization · Step 8 — Gradle 9, AGP 9, and the dependency bumps

Steps 2 and 3 had already paid the entry price: no ButterKnife, so `android.enableAppCompileTimeRClass`
(on by default in AGP 9) costs nothing, and no jetifier.

- **Gradle 8.14.3 → 9.7.1, AGP 8.13.2 → 9.4.0.** Two things had to change with it. The optimized
  ProGuard default file went in first, as its own commit, because AGP 9 refuses
  `proguard-android.txt` outright. And `buildFeatures { resValues true }` is now required: AGP 9
  turns that feature off by default, and the two Dropbox key strings are generated from
  `local.properties` that way, so the build failed at configuration time without it.
- **Dagger 2.57.2 → 2.60.1**, `androidx.viewpager` 1.0.0 → 1.1.0, and the four `androidx.test`
  artifacts to their current versions.
- **Two bumps were refused, with the reason in the build file**: `androidx.core` 1.19 and
  AndroidPlot 1.6.0 both require **compileSdk 37**, and this project compiles against 36 because
  that is its targetSdk and what Play requires of the listing. Raising compileSdk is its own change
  with its own device pass, not a dependency bump.

**Verified:** clean build; lint **0 errors / 86 warnings** (92 before — the dependency warnings
went); suite **267 tests, 0 failures** on the `Pixel_6` emulator under the new toolchain. Note that
AGP 9's test task no longer prints per-test progress, so a run now reports only at the end.

## Modernization · Step 9 — the release build

`assembleRelease` had apparently never been run in this project's life. It has now, along with
`bundleRelease`, and the result was installed and exercised.

- **Signing** reads `keystore.properties` (gitignored) the way the Dropbox key reads
  `local.properties`, with `SIGNING_*` environment variables as the CI path. With neither, the
  signing config is simply **absent rather than failing**, so a contributor with no keystore can
  still build and test. The upload key is the maintainer's to create; a throwaway key was used for
  this verification and then deleted.
- **R8 and resource shrinking are on**, and `proguard-rules.pro` — empty since 2021 — now carries
  the keep rules, each naming what breaks without it: AndroidPlot (its XML attributes are applied
  by reflection), the Dropbox SDK (its AAR ships no consumer rules; checked), the preference
  fragments and `TimePreference` (resolved by name from XML and from an Intent extra), and views
  inflated by name. What is deliberately *not* kept is listed too, with reasons.
  **The APK is 4.1 MB, down from 7.8 MB.**
- **Backups stay off on every Android version**: `dataExtractionRules` and `fullBackupContent`
  exclude every domain, beside `allowBackup="false"`, because Android 12+ ignores the attribute and
  reads the rules instead. The Dropbox credential could not survive a transfer anyway — its key
  lives in that device's Keystore.

**Verified on the `Pixel_6` emulator, on the shrunk, signed build installed from scratch**: the app
launches; all five settings screens open, which is the R8 risk that matters most since their
fragments are named by string; the reminder time dialog opens (a custom preference resolved from
XML); the graph draws with its rotated axis labels and hidden legend, so AndroidPlot's reflective
configuration survived; an `.edb` import through the system picker restored 2544 records with the
totals matching the test database exactly; the records list, calendar, summary, recurring overview,
categories and accounts all open; and enabling reminders arms a real `RTC_WAKEUP` alarm.
The APK verifies under signature scheme v2. Its single dex contains **no** `butterknife`,
`joanzapata`, `support-v4`, `security-crypto`, Firebase, Crashlytics, MoPub or ads reference, and
the App Bundle carries the three icon fonts. Lint release: **0 errors / 85 warnings**.

**One thing the release build cannot be asked to do from a shell**: `am broadcast` to the reminder
receiver is refused, because the receiver is deliberately not exported and only a *debuggable* app
lets the shell target such a component. That is the boundary working; the notification itself was
exercised on the debug build.

## Modernization · Step 3, finished — the last PNG icons are vectors

The step 3 bullet that was left undone: nine legacy PNG icons, each in five density folders, are
now vector drawables and **`res/` has no `drawable-*dpi` folder at all**.

The four that are drawn at their intrinsic size — the two filter-dialog chevrons, set with
`setCompoundDrawablesWithIntrinsicBounds`, and the two FAB pluses, which a
`FloatingActionButton` centres rather than scales — were **traced from the PNGs they replace**: the
vector's viewport is the old 96×96 pixel grid and its size is the old 32dp, so nothing moves. The
other five are standard Material glyphs at 24dp: the drawer's three placeholders, which
`setupDrawerMenuIcons` overwrites at runtime anyway, and the notification's Settings and Snooze
actions, which the system draws from the alpha channel.

**A crash this found, and it was not the icons.** Opening the filter dialog with no filter set
threw a `NullPointerException` immediately. Step 6 had rewritten a `getSerializable` call and
dropped the guard around it: the field holds a non-empty default, the original code only replaced
it when the arguments actually carried a filter, and the new code assigned the null unconditionally.
The guard is back. This had been broken since step 6 and no test covers that dialog.

**Verified** on the `Pixel_6` emulator: the filter dialog opens with no filter set, each group's
chevron points down and flips up when the group expands, the FAB's + is unchanged, and the reminder
notification posts with both actions. Suite **267 tests, 0 failures**; lint 0 errors / 85 warnings.

## Modernization · the Android 16 pass, on an emulator that works

The one thing the ten steps could not do. The `Pixel_6_API_36` AVD used the **google_apis** system
image, which on this host renders an all-black screen, accepts no touch input and ANRs
`system_server` repeatedly. A new AVD, `Pixel_6_A36`, on the **AOSP (`default`)** image and
provisioned with 4 GB and 4 cores rather than `avdmanager`'s 1.5 GB and no GPU, behaves like the
API 33 one.

**What the pass found — a real regression from step 5.** The settings and Dropbox-backup screens
left the strip behind the status bar unpainted, so Android 15 and 16 drew white status icons on the
near-white window background. `MainActivity` is unaffected: its `DrawerLayout` paints that strip
itself, and these screens have no drawer. It is the half of the old `AppCompatPreferenceActivity`
fix that went with that class when step 5 deleted it — `android:statusBarColor` cannot stand in,
being ignored from targetSdk 35 on. Their `AppBarLayout` now takes the top inset as padding and
draws `colorPrimaryDark` into it, which is what `MainActivity` looks like. Checked on Android 16
and on Android 13, where the framework insets the window itself and no gap appears.

**Everything else checked on Android 16, where predictive back is on by default:** Back closes an
open drawer and keeps the app; three Edit → Back rounds each return to the list; a long-press
selection is cleared by Back without leaving the screen; the settings root and its sub-screens are
inset correctly; every drawer screen opens; the recurring overview's selection bar and its
three-choice delete dialog render with their counts; and the reminder notification posts.

## Modernization · a second review of PR #16 — the budget amount

**A regression from step 5.** AndroidX's `EditTextPreference` ignores EditText attributes in the
XML, which `android.preference` passed through to its dialog, so the account budget amount lost
`inputType="numberDecimal"` and took any text. `PrefManager.getBudgetAmount` runs
`Float.parseFloat` on it, so a letter, or `12,50` on a German-region phone, crashed the Summary
screen and the CSV export on every visit. The fragment now sets the input type in an
`OnBindEditTextListener`, and `getBudgetAmount` reads a decimal comma and treats anything else it
cannot parse as no budget, which also covers a value stored before the fix. `BudgetAmountTest`.

**Verified** on `Pixel_6_A36`: the dialog's field reports input type `0x2002` (number, decimal) and
typing `ab12.5x` leaves `12.5`. Suite **270 tests, 0 failures**; lint 0 errors / 85 warnings.

## Modernization · the two phones, on the release build · merged (PR #16)

**A fourth review** found one build issue, fixed before the merge: a `keystore.properties` without
`storeFile` made `rootProject.file(null)` throw at configuration, failing every build, debug
included. An incomplete file now counts as no signing, with a warning.

**The maintainer installed the signed release build over the existing app on the Pixel 4a and the
Jelly Star.** The Dropbox login (asked once more, as expected, since the old credential is deleted
rather than migrated) works; existing records are intact and were edited and created; reminders
arrive; and sync carries records between the two phones. PR #16 was merged after it.

## Tweaks · merged (PR #18) · [`TWEAKS_PLAN.md`](docs/history/TWEAKS_PLAN.md)

Four usability fixes, each settled with the maintainer through multiple-choice questions before it
was built:

- **After a save, the saved record's period shows**, not today's — in List, Graph, Calendar and
  Summary, whichever is the screen after an entry. `LogTabsFragment.showingTime`.
- **The records views share the period on screen** (`ViewedPeriod`): on July, every view and every
  period length opens on July; narrowing a past month shows its 1st, the current one today. It is
  one focus time — paging by hand sets it, any other showing keeps it while the period holds it —
  after a period, an anchor and a "current" flag were tried and broke each other (July → Year →
  Month opened September). A period that held today follows it into the next month.
- **The record's date picker opens on the record's date.** It was passed and never read.
- **A series' new end moves the end, says what it does, and can be undone.** A new end alone used
  to offer a split ("This and following") or a rewrite of every entry; now only "The whole series",
  through `setRepeatingEnd`, with a line above the cards ("Ends Dec 15, 2026 · deletes 4 entries" or
  "· adds 6 entries") and Undo for whatever it deletes, old end included. The end may fall before
  the entry being edited — on its own; with other changes too it is refused. A save that changed
  values and deleted entries offers RESTORE rather than UNDO, since it brings back only the entries
  (the plan, archived, records why the values stay).
- **Saving an unchanged record writes nothing and asks nothing** — no scope dialog, no upload or
  backup, "No changes to save" instead of "Record Updated", then on to the screen after an entry.
  The form is compared with a snapshot taken when it was filled (`NewLogFragment.formState`):
  values, date and time, and the schedule and end only with Repeat on.

**Four code reviews** each found real bugs, all fixed; the notable ones: "only the end
changed" misread a rounded amount (it now compares with what the form showed), the dialog could
promise an extension the save refused, a save that deleted its own record left its editor on the
back stack, and editing values with an end before the entry wrote the edit onto every earlier
entry. The "which page holds this time" lookup is now one helper for the records pager and the
export dialog. Declined, with reasons in the PR: a result object for the series Undo, and caching a
split series' schedule.

**Verified:** full suite 301 tests in 47 classes, 0 failures (new: `SavedRecordPageTest`,
`SeriesEndUndoTest`, `ExportPeriodTest`, `UnchangedSaveTest`); lint 0 errors, 87 warnings. Every item, and every review
fix, driven on the `Pixel_6` emulator against the realistic test database. The maintainer then ran
the whole checklist in PR #18 on a phone, on the debug build, with no issues; a fifth review of the
final PR found no bugs. PR #18 was merged after it.

## What is open

Nothing is broken. These are known, deliberate, and none blocks use of the app.

The ten-step modernization that finished the revival is recorded step by step above, and its plan
is archived at [`docs/history/MODERNIZATION_PLAN.md`](docs/history/MODERNIZATION_PLAN.md) — history
now, not instructions.

**Worth doing at some point**

| Area | What |
| --- | --- |
| JVM unit tests | Still only `ExampleUnitTest`. Nothing depends on them. The maintainer's call. |
| compileSdk 37 | `androidx.core` 1.19 and AndroidPlot 1.6 want it. Its own change, with its own device pass. |
| Android 16 UI | Now covered on `Pixel_6_A36` (AOSP image). The google_apis image cannot render on this host. |

**Known and unresolved**

- **The Records pages are rebuilt on every resume** (F13 of the reliability pass). Wasteful, but it
  is also the only thing refreshing them after a Settings change or a Dropbox download, so removing
  it needs explicit refreshes first.
- **The amount field is not locale-aware** (F18). With a German keyboard, a typed comma is dropped —
  `12,50` becomes `1250` — rather than accepted or refused. Visible before saving.
- **Monthly repeating series that drifted before the fix stay drifted** in existing data. Repairing
  them would silently edit financial records, so it needs a deliberate decision.
- **A delete made at the exact moment an upload reads its change counter** can still be counted
  before the snapshot it misses. The window is a few instructions wide; closing it fully would mean
  counting changes inside the write lock.
- **The import stages the whole file on the main thread** before validating it — ~20 s for a cloud
  file, and it would ANR on a large enough one. Moving it to a background thread is fine;
  **reordering staging and validation is not** — see `CLAUDE.md`.
- **Automatic local backups are app-private**: invisible to every file manager, and deleted with the
  app. The manual `.edb` export is the only backup that survives losing the app.
- **The About screen's Play and F-Droid links lead nowhere** until the app is listed. They are the
  URLs those listings will have, built from the package name, so they become correct on publication
  day with no code change — the deliberate trade for not having to remember them later.
- **A staged export the app never recorded is not cleaned up** — a CSV left in the files root by an
  older build, or by the original app. Since exports moved to `exports/`, nothing overwrites those
  either, so they stay until the app's data is cleared. One small file per range, and not worth
  reintroducing a name-pattern deletion in the backup directory for.

- **The export dialog's period does not remember the month across a round trip** through a period
  holding today (Jun → Year → Month shows September), unlike the records views since PR #18. The
  maintainer's call: kept, since the label and preview show the period and ‹ › step back.

- **The Records FAB stack has two unchecked corners.** Nobody has yet seen a snackbar lift the export
  and + buttons together (the default FAB behaviour should, but none was triggered in testing), and
  in landscape the stack covers the amounts of the rows behind it, at the bottom right — as + alone
  already did.

- **The launcher icon's themed version is unchecked on a device.** Renders of the resource files
  look right; the check is the Pixel 4a with themed icons switched on in its home-screen settings.
- **The icon's paths were generated, not drawn**, from Nunito Black by a throwaway script
  (fontTools + shapely). Changing the sign means regenerating them; the numbers to reproduce it
  are in the foreground drawable's comment.

**Not reimplemented**

In-app billing. If paid features are ever wanted, they need writing against the Play Billing Library.

---

## Testing

```
gradlew.bat :mobile:assembleDebug          # APK → mobile/build/outputs/apk/debug/
gradlew.bat :mobile:connectedDebugAndroidTest
gradlew.bat :mobile:lintDebug              # does NOT run under assembleDebug
```

**To run the suite against a phone holding real records**, use
`adb shell am instrument -w de.timowa.expenselog.test/androidx.test.runner.AndroidJUnitRunner`
instead — `connectedDebugAndroidTest` uninstalls the app afterwards and deletes
`Android/data/de.timowa.expenselog/`, backup included.

After any change to the import, backup or reminder paths, re-run the ten-check device pass in
[`RECOVERY_PLAN.md`](docs/history/RECOVERY_PLAN.md). It has the exact commands and what a pass looks
like for each check.

**Two-device Dropbox checks do not need two phones.** A second headless emulator (`Pixel_6_B`, port
5556) runs beside `Pixel_6`, and both can log into the throwaway test account;
[`RELIABILITY_PLAN.md`](docs/history/RELIABILITY_PLAN.md)'s last section has the scenarios and the
tricks that make them reproducible (padding the database to widen timing windows, since the
emulator's network throttle does not slow this traffic).
