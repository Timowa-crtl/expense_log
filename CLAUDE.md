# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

*Expense Log* (`de.timowa.expenselog` v2.0), an offline-first Android expense tracker handed over
by its original author under Apache-2.0, revived from a January 2021 source snapshot. Step 7 moved
it off the original author's namespace; the only place his name still belongs is `NOTICE`.
Single Gradle module (`:mobile`), 78 Java files / ~21.1k LOC, no Kotlin, no backend.

**It builds, runs, and ships.** Billing, ads and telemetry are gone — premium features are
unconditional and no data leaves the device — and the nine-step modernization
(`docs/history/MODERNIZATION_PLAN.md`) has since retired every library older than 2020 and every
deprecated API the app used. `:mobile:bundleRelease` produces a signed, R8-shrunk App Bundle.

`docs/history/REVIVAL_PLAN.md` is the authoritative record of that work — the exact toolchain versions, the
dependency decisions (including which upgrades are deliberately *deferred* because they break the
app), the step order, what each gate actually produced, and a list of what Phase 2 must still
cover. **Read it before changing any build file, manifest, or dependency.** `README.md` is the
public face of the app, aimed at users of the original; `BUILDING.md` covers the toolchain a
builder needs and the credentials they must supply.

Toolchain, all verified together: Gradle 9.7.1, AGP 9.4.0, JDK 21, compileSdk 36, minSdk 26,
**targetSdk 36**, Java 17 source/target. Phase 2 (`rehabilitation` branch) raised targetSdk from 29
to 35 and did the scoped-storage, migration and notification work that the raise required;
`docs/history/REHABILITATION_PLAN.md` is the record of it. The `sdk36` work then took both to 36,
which is what Google Play requires of a new listing since 31 August 2026; AGP 8.13.2 supports up to
API 36.1, so no part of the toolchain moved with it.

**Phase 3 (`recovery` branch, merged as PR #3) closed the nine findings that Phase 2's own review
raised**, two of them data-loss bugs: a newer-schema `.edb` import could leave the app permanently
unopenable, and the instrumentation suite overwrote the user's real backup. Both are closed and
verified on two phones against real records.

**`PROGRESS.md` is the current status of the project — read that first.** The phase plans are
archived under `docs/history/` and are history, not instructions; **every constraint they established
that still binds has been lifted into this file**, so you should not need to read them before making
a change. Go to them for *why* a decision was made, or for the ten-check device pass in
`docs/history/RECOVERY_PLAN.md`, which is worth re-running after any change to the import, backup or
reminder paths.

## Build environment — you CAN build from this WSL shell

WSL itself has no JDK, Gradle, or Android SDK, but **Windows binaries are executable from WSL via
interop**, and a JBR lives at `C:\Users\Tim\.jdks\jbr-21.0.11`. Setting `JAVA_HOME` to it makes
the entire Windows toolchain driveable from here: Gradle, `sdkmanager`, `avdmanager`, and `adb`.

**Verified working 2026-08-25:** `:mobile:connectedDebugAndroidTest` run from WSL against a running
emulator, 16 tests green. Also used to install a system image and create an AVD.

**Quoting is the one trap.** `cmd.exe` treats `;` as a separator, so a package name like
`system-images;android-33;default;x86_64` gets split before the tool sees it, and escaped quotes get
mangled crossing the WSL→cmd boundary. It fails with a `cmd` syntax error while the *pipeline* still
exits 0, so a naive success check lies. **Write a `.bat` file to a Windows-visible path and execute
that** — `cmd` then parses the quoted argument from the file:

```bash
cat > "/mnt/c/Users/Tim/AppData/Local/Temp/task.bat" <<'EOF'
@echo off
set JAVA_HOME=C:\Users\Tim\.jdks\jbr-21.0.11
cd /d C:\Users\Tim\AndroidStudioProjects\expense_log
gradlew.bat :mobile:assembleDebug --console=plain
EOF
timeout 1800 cmd.exe /c "C:\Users\Tim\AppData\Local\Temp\task.bat" 2>&1 | tail -60
```

Run builds with `run_in_background: true` — a cold build or an instrumentation run takes minutes.

Useful paths: SDK at `C:\Users\Tim\AppData\Local\Android\Sdk`; `adb.exe` under
`platform-tools/`; `sdkmanager.bat` / `avdmanager.bat` under `cmdline-tools/latest/bin/`. An AVD
named `Pixel_6` (API 33, AOSP, x86_64) exists for instrumentation runs; start it before
`connectedDebugAndroidTest` or Gradle reports no devices. `cmdline-tools` is older than Studio, so
every SDK command prints a harmless "SDK XML version 4" warning.

**Still worth doing:** prefer inspecting build output over trusting a green result —
`mobile/build/outputs/apk/debug/` for the APK, `mobile/build/intermediates/merged_manifest/` for the
readable merged manifest, `mobile/build/outputs/androidTest-results/connected/debug/*.xml` for test
results (check `failures`/`errors`/`skipped`, not just BUILD SUCCESSFUL), and unzipping the APK with
Python plus grepping the concatenated `classes*.dex` to prove a dependency is really gone.

**The release build is R8-shrunk and signed, and its keep rules are load-bearing.**
`mobile/proguard-rules.pro` keeps AndroidPlot (its XML attributes are applied by reflection), the
Dropbox SDK (its AAR ships no consumer rules), and everything resolved by name at runtime: the
preference fragments named in `pref_root.xml` and in `SettingsActivity`'s Intent extra,
`TimePreference`, and views inflated from layouts. **A new class reached only by name needs a rule**,
or it works in debug and throws in release, where no test runs. Signing reads a gitignored
`keystore.properties` or `SIGNING_*` environment variables and is *absent rather than failing*
without them. Keep each release's `mapping.txt`.

**Lint does not run under `assembleDebug`** — run `:mobile:lintDebug` explicitly. Phase 1 never ran
it, so its first run surfaced six pre-existing errors. **Nothing is suppressed in
`mobile/build.gradle` any more**: `ExpiredTargetSdkVersion` and `NotificationTrampoline` were
disabled only while targetSdk sat at 29, and Phase 2's Step 5 re-enabled both. If you need to
suppress a check, say why in that file. The current baseline is **0 errors and 86 warnings**; treat
a new error as a finding to fix, not to suppress.

There **is** now a working test setup: `src/androidTest/` holds 296 instrumentation tests in 46
classes — over `DBAdapter`, multi-delete and its Undo, the `.edb` round trip and import safety, period bounds, repeating
records and editing them, the calendar, the Dropbox sync rules, the reminder notification, the
sync-conflict dialog, Back navigation, the icon fonts and the credential store (`src/test/` still
has only a JVM stub).
**`IsolatedDatabaseContext` is mandatory** —
`DATABASE_NAME` is a fixed constant and `DBAdapter`'s constructor opens the database immediately, so
a test given the real application context writes into the live database. On the maintainer's phone
that is real financial data. Passing a raw context to `DBAdapter` in a test is a data-loss bug.
Its `deleteDatabase` also closes the shared connection (below), which is what gives each test a
fresh file; delete through it, never by deleting the file directly.

## Architecture

**Single activity, drawer + fragments.** `MainActivity` hosts a `DrawerLayout` and swaps fragments
into `R.id.mainContentFragment`. All fragment transactions, back-stack handling, and toolbar titles
live in `Navigator`, not in the activity.

**The back stack is keyed by menu-item title strings.** `Navigator.changeFragment` uses
`menuItem.getTitle().toString()` as the transaction tag, and `getCurrentMenuId` maps a title back to
a `MenuItem` by string comparison against the nav menu. Renaming a nav menu string silently breaks
navigation and the "already selected" de-duplication.

**Only drawer screens count towards the Back cap.** `MainActivity.backLeavesApp` leaves the app
after `BACK_PRESS_LIMIT` Back presses through drawer screens, but Back from a sub-screen (anything
opened with `Navigator.newTempFragment`) never counts. It used to, so the third Edit → Back closed
the app. A sub-screen is recognised by its fragment having **no tag**, so `newTempFragment` must
keep adding its fragment untagged, and `changeFragment` must keep tagging. Leaving goes through
`Navigator.exitApp`, which finishes rather than calling `System.exit`. `BackNavigationTest`.

**Back goes through the dispatcher, not through `onBackPressed()`.** The modernization retired the
`android:enableOnBackInvokedCallback="false"` opt-out the manifest used to carry: at targetSdk 36
`onBackPressed()` is never called, and `androidx.activity` used to resolve to **1.0.0**
(transitively, under `appcompat:1.2.0`), which predates `OnBackInvokedCallback` entirely, so nothing
would have registered a back callback. `androidx.activity` is now declared explicitly at 1.13.0 and
`MainActivity.registerBackHandling` adds an `OnBackPressedCallback` in `onCreate` — the app's only
back handler. **The declared `androidx.activity` version is load-bearing: do not let it fall below
1.6**, or back silently stops closing the drawer and starts exiting the app from every sub-screen.
The callback is added after `FragmentActivity` has attached its own (that happens in
`super.onCreate`), and the dispatcher runs callbacks newest-first, so this one wins and pops the
back stack itself — it must **not** call `onBackPressed()`, which re-enters the dispatcher and
loops. The selection action mode consumes Back ahead of all of this, through AppCompat's own
back callback, which is what keeps clearing a selection from counting towards the exit cap.

**Dagger 2, hand-wired (no dagger-android).** `MyLogApplication` builds `AppComponent`
(`AppModule` → the app's `SharedPreferences`). Each injectable activity builds its own
`ActivityComponent` in `onCreate` from that `AppComponent` plus `ActivityModule(this)`, then calls
`injectActivity(this)`. Fragments extend `BaseFragment` and reach the activity's component via the
`HasComponent<ActivityComponent>` interface — so **a new injectable fragment needs an explicit
`void inject(...)` method added to `ActivityComponent`**. Most collaborators (`Navigator`,
`PrefManager`, `DBAdapter`, `DropboxBootstrap`, `Utility`, `DropBoxHelper`, …) are `@Inject`-constructed.

**Views are bound with generated `viewBinding` classes**, never `findViewById` chains or a
binding library: a `binding` field set in `onCreateView` (or `onCreate`, for `MainActivity`),
nulled in `onDestroyView`, with the fields the rest of the class uses assigned from it once.
ButterKnife 7 is gone, and with it `android.nonFinalResIds=false`: `R` fields are not constants,
so a `switch` on a resource id does not compile — use `if`/`else if` chains, as the five former
switches now do.

**Icons are glyphs in three vendored fonts**, not drawables: `de.timowa.expenselog.icons` holds
`IconDrawable`, the `IconFonts` registry and the three key-to-code-point enums, taken from
android-iconify 2.2.2 (Apache-2.0, last published 2016, the only reason this project needed
jetifier). **A category's icon is stored in the database as that project's key** — `fa-tags`,
`md-local-dining`, `mdi-cat` — so the keys, the enum names and the code points must not be
"tidied"; a changed key blanks the icon on every existing record. `IconFonts.find` returns null
for an unknown key rather than throwing. `IconFontTest` resolves every key in `icon_list.xml` and
both default-category arrays.

**Storage: `DBAdapter` is the whole data layer.** Raw `SQLiteOpenHelper`, four tables (`mainLogs`,
`tagTypes`, `AccountsTable`, `RepeatingTable`), no ORM, no Room. Two traps:
- Callers read cursors by **positional** `COLUMN_*` int constants, so column order in the `CREATE
  TABLE` strings is load-bearing — reordering or inserting a column silently corrupts every read.
- `DATABASE_VERSION = 1`, and existing `.edb` files in the wild are all version 1. Step 3 replaced
  the empty `onUpgrade` with scaffolding that **throws on an unregistered version step** rather than
  silently doing nothing, and added a downgrade guard — but each migration still has to be written
  by hand. Append new columns to the *end* of a `CREATE TABLE`, never into the middle, because of
  the positional constants above.
- **`DATABASE_VERSION` is a schema version, not an app version — never bump it on a release.** Bump
  it only when the schema actually changes, including additively, because an old database will not
  have the new column and the migration is what adds it. Bumping it gratuitously has a real cost:
  `onDowngrade` throws by design and `FileHelper.inspectCandidate` refuses any `.edb` whose
  `user_version` exceeds this build's, so every backup exported from the new build becomes
  un-importable on the old one for no reason. `DBAdapter.migrate`'s javadoc carries the
  one-commit checklist; `DBAdapterSchemaTest.databaseVersion_isStillOneAndHasNoMigrationYet` is a
  tripwire that fails the moment the number moves, and is meant to force you through it.
- The image feature was removed in Phase 2 (Step 4b), but `COLUMN_LOG_IMAGE = 5` **stays in the
  schema** and existing rows keep their paths. Do not "clean it up" — dropping it shifts every
  constant after it.

**One connection per database file, for the whole process.** Every `DBAdapter` shares a static
helper keyed by the database's absolute path (resolved through the caller's context, which is what
keeps the test harness's `test_` database separate). **`close()` is deliberately a no-op** — closing
the shared connection from one screen would pull it out from under every other screen and thread.
The only closer is `DBAdapter.closeSharedConnection(Context)`, and only for replacing the database
file (import, restore) and in the test harness; any adapter's next call reopens it. Before this,
each adapter opened its own connection and injected ones were never closed: 18 were open after a
few minutes of browsing, and two adapters writing from two threads failed with `SQLITE_BUSY`.
`SharedConnectionTest`. Hold one `SQLiteDatabase` local for the length of a transaction, **and hold
`CONNECTION_USE`'s read lock around it** (`FileHelper.snapshotDatabaseTo` does, via
`connectionUseLock()`): `replaceWhileClosed` takes the write lock before closing, so an import cannot
close a connection mid-transaction. Take that lock outside the `HELPERS` monitor, never inside it,
or it deadlocks with `db()`. **Every other access goes through `read(d -> …)` or `write(d -> …)`,
never a bare `db()`**: they hold the read lock, fill a returned cursor before releasing it (cursors
query lazily and do not keep the connection open, so an import used to kill background loads
mid-read), and `write` counts a change *after* the write lands, which is what lets a Dropbox upload
see a delete that waited behind its snapshot.

**A repeating series is computed from its original time, never stepped from the previous
occurrence**, and written in one transaction. `DBAdapter.repeatingOccurrence` uses `Calendar.add`
on the original. The old `set(field, get(field) + n)` looped forever on a weekly series crossing New
Year (late December is week 1 of the next year) and drifted monthly series from the 31st onto the
3rd. `createRepeatingSeries` has a hard ceiling (20 000 occurrences) and writes nothing if it is hit.
`RepeatingScheduleTest`. Two-table deletes (`deleteTag`, `deleteAccount`, `deleteRepeatingLogs`) are
transactions too.

**A series' start date is not stored, and must not be added as a column.** `RepeatingTable` holds
amount, interval and end only, and a new column would bump `DATABASE_VERSION` (see below). Anything
that needs to compute more of a series — extending its end — recovers an origin with
`RepeatingSeries.findOrigin`: any record for days and weeks, and for months and years the earliest
record on the **latest day of the month any record has**, since a series from the 31st stores the
28th in February and a schedule computed from that would stay on the 28th. Every record is checked
against the result to the millisecond; a series that fails (drifted by the old stepping bug, or
written in another time zone) is **refused with `SERIES_NO_SCHEDULE`, never guessed at**. The one
known wrong case is pinned in `RecurringEditTest`: when no remaining record shows the real day, an
extension into a longer month lands early.

**Editing a series works by record time, and splits rather than rewrites.** `RecurringScope` is the
single "only this entry / this and following / the whole series" dialog for the list, the calendar,
the overview and the edit screen. "This and following" with new values moves those records to a
**new** series id (`updateRepeatingFrom`) so a raise never rewrites past amounts; a new interval or
date replaces them with a fresh schedule from the edited date (`restartRepeatingFrom`). From a
series' first record both work in place and keep the id. A cut series ends on the day before the
cut (`endBefore`); ends are exclusive. "Only this entry" still detaches the record
(`repeatingId = -1`), as it always did. Every one of these is one transaction that writes nothing
when refused. `RecurringEditTest`. **A new end alone is the whole series' and nothing else**: the
save dialog greys out "This and following" too, and saving goes through `setRepeatingEnd` —
never `updateRepeatingFrom`, which would split the series from this record. The end may fall
before the record being edited (it is deleted with the rest, and the editor pops itself back to
where it was opened from, as the trash does — not `successfulSave`, whose push would stack a second
records screen — with `ViewedPeriod.focusOn` the new end) — but only on its own: with other changes too it is refused, since the
only choice left would write them onto every earlier entry. A line above the cards says what the
end deletes or adds (`DBAdapter.occurrencesAddedBy`, counted for the part "This and following"
splits off as well, `RepeatingSeries.from`; no count where the two differ), an end every choice
would refuse is refused before the dialog, and what it deleted is offered back with Undo, old end
included. "The end alone" is judged against what the form showed when filled
(`NewLogFragment.filledValues`), not against the database, whose amounts the form rounds.
`occurrencesAddedBy` must keep applying `insertOccurrences`' ceiling (`passesCeiling`), or the
dialog promises entries the save refuses. `SeriesEndUndoTest`.

**Records UI.** `LogTabsFragment` is a `ViewPager` host parameterised by a *time mode*
(`KEY_RECORDS_MODE_ALL`/`YEAR`/`MONTH`/`WEEK`/`DAY`) and a *view mode*
(`KEY_VIEW_MODE_LIST`/`GRAPH`/`CALENDAR`/`SUMMARY`), which select `LogListFragment`,
`LogGraphFragment` (AndroidPlot), `LogsCalendarFragment` (`Calendar/` adapter), or `SummaryFragment`.
Nav-drawer entries for List / Graph / Calendar / Summary are all this one fragment with different
arguments. Paging works around a fixed 1000-page window centred on `MID_PAGE`. **The page is
shared, not per screen**: `ViewedPeriod` (in memory, cleared by a fresh `MainActivity`) holds one *focus* time, and every rebuild of the pager — a view switch, a new
period length, Back — pages to it through `LogTabsFragment.pageOffsetFor` (the clamped form of `offsetHolding`, which
the export dialog's `ExportPeriod.withMode` uses too — keep one lookup). Paging by hand sets it
(`paged`: today if the period holds today, and then it follows today, so September left on the
30th shows October on the 1st; else the period's start). Any other showing (`shown`) keeps it
while the period holds it, which is what brings July → Year → Month back to July, and leaves it
alone when the pager's ±500-page window clamped the page. It used to be a period, an anchor and a
"current" flag, and those three rules broke each other; keep it one time. Do not reintroduce a
jump to `MID_PAGE` on a period-length change. A save opens on the record's period
(`showingTime`). `SavedRecordPageTest`.

**The records list selects by long press, and deletes with Undo rather than a dialog.** A tap
opens the editor; a long press starts an AppCompat action mode (`RecordSelection`, by id) whose bar
shows the count and the balance and offers Select all and Delete. **A repeating entry is only ever
selected alone**, because its delete needs the only this / this and following / whole series choice
that a mixed selection cannot ask: it cannot join a selection, nothing joins it, Select all skips
them, and its Delete opens `RecurringScope.confirmDelete` (as the calendar's does).
**Both series dialogs are one minimal card design** (`RecurringScope.showMenu`: a title, then per
choice an icon, a title and one short line) and always show all three choices; one that cannot
apply is disabled with a short reason, never left out. **Only the icon carries colour**: the delete
dialog runs amber → orange → red as a choice takes more with it (`scopeSeverityLow/Medium/High`),
never green — none of the three is safe — while the save dialog, which deletes nothing, stays the
app's teal. Each card also has its own icon shape and says how many entries it covers, so nothing
rests on colour alone. Deletes happen at once and offer Undo. A delete the database refused (the series changed or went
while the dialog was open) writes nothing, says so, and does not run its caller's callback — an
editor must not close on a record that still exists. **An Undo re-attaches records to their series
only if that series is still exactly as the delete left it** (`DeletedLogs.seriesAfter`); a series
changed meanwhile keeps the change and the records come back detached, rather than mixing two
schedules into one series that `RepeatingSeries.findOrigin` would then refuse.
**Selection is one pattern, `SelectionBar`**, shared by the records list and the recurring
overview: tap edits, long press selects, the bar's trash deletes. The overview selects one series
at a time (a tap on another moves the selection), titles the bar with the series and its
schedule, and its Delete is the same three-choice dialog for the record the row shows. Its
RecyclerView has change animations off, because they end at full opacity and un-fade ended series;
its ended series sit under a collapsible "Ended (n)" row (`pref_key_recurring_ended_expanded`).

**The record screen has one series block for creating and editing.** Its header row holds either
the Repeat switch (New Record, and a record outside a series) or, on a series entry, the ‹ ›
arrows around "**Entry n** of m"; both carry the drawer's Recurring icon, and under either sits the
schedule ("Monthly on day 17 · 13 entries" when creating, without the count when editing, never
the end date, which Ending shows). Why a schedule can't be saved goes in `textView_repeatError`
under Ending, and the header keeps the last valid schedule meanwhile. **There is no swipe** — it
was tried and removed; the arrows load the neighbour (`RepeatingSeries.neighbourOf`) into the same
screen, dropping unsaved edits, and the record id is written back into the arguments so a
rotation stays on it. Every edit screen for an existing record has a trash icon: a plain
record is deleted at once, a series entry goes through `RecurringScope.confirmDelete`, and the
editor then pops itself. **An Undo refreshes through `RecordsChanged`**, not through the screen
that deleted — that screen may be gone — so the records list, the calendar and the recurring
overview listen to it between `onResume` and `onPause`; a new screen that shows records must too. Saving
from a series' first record passes "the whole series" back as "this and following", the path that
can also restart the schedule — do not route it to `updateRepeatingFrom(Long.MIN_VALUE, …)`, which
keeps the old dates.
Every delete there offers Undo (`DeleteUndo`). `DBAdapter.deleteLogs` and `deleteSeriesRecords`
return every deleted column as stored, plus the series' entry as it was, so `restoreLogs` puts
records back under their own ids (AUTOINCREMENT never reuses one) and resets or re-creates the
series' entry. A restore skips a record whose category or account has gone since, detaches one
whose series has gone, and **refuses outright after the file was replaced** (`REPLACEMENTS`, bumped
in `replaceWhileClosed`) so Undo never writes old records into an imported or downloaded database.
`deleteRepeatingFrom` and `deleteRepeatingLogs` are thin wrappers over `deleteSeriesRecords`.
The action mode consumes Back before `MainActivity.onBackPressed`, so clearing a selection does not
count towards the exit cap; it ends when its page is paged away from or its view is destroyed.
`windowActionModeOverlay` must stay on `AppTheme.NoActionBar`, or the bar pushes the toolbar down.
`RecordDeleteUndoTest`.

**A period ends one millisecond before the next one starts — by definition.**
`LogTabsFragment.getPeriodEnd` is `getPeriodStart(offset + 1) - 1`; do not give it arithmetic of its
own again. It had some, and on the 29th–31st month pages ran into the next month (February to
3 March) while weeks with a Monday or Saturday start overlapped on some days — feeding the list,
summary, graph, budgets and CSV export. The week start is computed directly (days back to the first
day of week). Both take an optional "now" so `PinnedPeriodBoundsTest` can check every day of
2026–2028; `PeriodBoundsTest` alone only catches a boundary bug on the day it happens to run.
Calendar days use `CalendarDay.dayStart/dayEnd` (millisecond set explicitly), and the list's
selection is inclusive at both ends like the totals' `BETWEEN`.

**The calendar loads a month with one query, off the main thread.** `CalendarAdapter.loadMonth`
buckets the month's records into days and `CalendarDay.getTotal()` sums them; `getView` must not
touch the database (it used to open a connection and run two `SUM`s per cell per layout pass). The
background load in `LogsCalendarFragment` must not touch the fragment either — capture what it needs
on the main thread first — or fast paging detaches the fragment mid-load and crashes the app.

**Preferences.** Every preference key is a string resource in `res/values/settings_keys.xml`, read
through `PrefManager` — do not hardcode key strings. That file also holds the placeholder AdMob and
developer-link values (the original author's real ones were removed). The Dropbox app key is
**not** there and not in the repository at all: set `dropbox.appKey` in `local.properties` and
`mobile/build.gradle` generates `@string/dropbox_key` (with the `db-` redirect-scheme prefix) and
`@string/dropbox_app_key` (bare) from it. Do not paste a literal key back into a tracked file.

**The settings are AndroidX `PreferenceFragmentCompat`s in a plain `AppCompatActivity`.**
`SettingsActivity` inflates `activity_settings.xml` (toolbar over a container) and swaps fragments
into it; `res/xml/pref_root.xml` is the root list, each row naming its fragment in `app:fragment`.
**A new sub-screen is a fragment, added to `SettingsActivity.ALLOWED_FRAGMENTS`** — that list is
what stops another app naming an arbitrary fragment in the `EXTRA_FRAGMENT` extra, the job the
framework's `isValidFragment` used to do. **Never nest a `<PreferenceScreen>`**: it renders as a
bare dialog with no toolbar and no insets, which is what put the reminder switch behind the status
bar before the `sdk36` work. Both ways into the reminder screen — the root row, and the reminder
notification's Settings action through `Navigator.openSettings(activity, fragmentName, titleRes)` —
land on the same fragment, so they cannot drift apart. `SettingsHeadersTest` pins the rows, their
order, and that each names a real `Fragment` with a no-argument constructor; a wrong name otherwise
fails at tap time, not at build time. A custom preference needs its dialog shown from
`onDisplayPreferenceDialog` (see `TimePreference` and `TimePreferenceDialogFragment`), and both
settings activities must keep `AppTheme.NoActionBar`, or AppCompat throws over two action bars.

**Files and sync.** `FileHelper` owns the `.edb` format (the SQLite database exported verbatim) plus
import/export; `SpreadsheetHelper` does CSV; `LocalBackupManager` does local backups.

**Six rules in `FileHelper` are load-bearing, and all six were bought with a data-loss bug.**
Phase 3 closed the first four; breaking any one re-opens the corresponding failure.
1. **Stage, then validate, then overwrite — never reorder this.** `importFileAndVerify` copies the
   candidate to the cache and inspects it *before* touching the live database. The obvious
   "optimisation" of validating the original in place is exactly the ordering that let an invalid
   import destroy the database while reporting success. It costs up to ~20 s for a cloud file, on
   the main thread; moving that work to a background thread is fine, reordering it is not.
2. **The path getters must not open the database.** `getInternalDbFile` and `getInternalDbFolder`
   return `context.getDatabasePath(...)` and nothing else. They used to construct a `DBAdapter` to
   learn a path — and since `restoreBackupDbFromSd` resolves its destination through them, that made
   restoring an unopenable database impossible, which is the only case a restore exists for.
3. **`FileHelper` reports, it does not present.** `importFileAndVerify` returns an `ImportResult`;
   `importProvidedFile`, `backupDbToSd` and `fileSetup` log rather than toast. All are reachable
   from a `DropboxTask` pool thread, which has no Looper, so a `Toast` there throws instead of
   showing. Only main-thread callers (`confirmAndImport`, `exportDB`) may raise one.
4. **Import accepts `content://` only.** `MainActivity` refuses any other scheme before offering to
   overwrite. The app holds no storage permission, so a `file://` URI names a path it cannot read.
5. **Exports are staged only in `exports/`, and cleanup deletes nothing outside it.**
   `getExportStagingDir` is `getAppFilesDir()/exports`; `discardPendingExport` and
   `copyStagedFileTo` delete a recorded path only when its canonical parent is that folder. The files
   root is not a scratch folder — it holds `LocalBackupManager`'s four backups and
   `ExpenseLogBackup.edb`. The `.edb` export used to be staged there as `expenseLog.edb`, the current
   backup's name, so every Save, Cancel, or later export after a Share deleted that backup. Deleting
   by recorded path alone did not prevent it; the folder check is what does. `ExportStagingTest`
   pins it against the real backup names.
6. **Every byte copy of the live database goes through `snapshotDatabaseTo`, and every replacement
   through `replaceDatabaseFile`.** A snapshot holds writers off with an immediate transaction on
   the shared connection **only if one is already open** — it must never open the database itself,
   because opening a corrupt file through `SQLiteOpenHelper` deletes it — and writes a synced temp
   file renamed into place. This covers the import safety backup, `.edb` export, `LocalBackupManager`
   and all three Dropbox uploaders (which upload a snapshot, never the live file). A replacement
   writes the incoming bytes beside the database, closes the shared connection, deletes `-journal`
   as well as `-wal`/`-shm`, and renames. Raw copies taken during inserts were measurably corrupt
   (3 in 6 436), and a stale `-journal` was replayed onto an imported file, corrupting it behind an
   "OK". An import is refused (`BACKUP_FAILED`) if a live database exists and could not be backed up.
   `DatabaseSnapshotTest`, `ImportSafetyTest`.

**`provider_paths.xml` must declare both roots** — `getAppFilesDir` falls back from
`getExternalFilesDir` to `getFilesDir`, and a file in the fallback is unshareable unless
`<files-path>` is declared too. `FileProviderPathsTest` pins it. Since Step 4
**the app writes only to its own directory** (`FileHelper.getAppFilesDir`, i.e.
`Android/data/<package>/files`) and reads user-chosen files through `ACTION_OPEN_DOCUMENT`; neither
needs a permission, and there are no storage permissions in the manifest. Do not reintroduce
`Environment.getExternalStorageDirectory()`. Note that `Android/data/` cannot be browsed by any file
manager since Android 11 — use `adb shell ls` to inspect it, and do not conclude from an empty
listing in Files that nothing was written.

`dropbox/` is 7 background jobs driven by `DropBoxHelper`, all extending `DropboxTask` — a small
`ExecutorService` + `Handler` base class that replaced `AsyncTask` in Step 6 and keeps the same
`doInBackground`/`onPostExecute` shape. **It is a single thread on purpose**: tasks run one at a time
in the order started, as `AsyncTask` did — a cached pool let two uploads finish out of order. An
unchecked exception reaches `onUncaught` on the main thread instead of killing the process; a task
with a progress dialog must override it, and dismiss through `DropboxDialogs`. Find Dropbox files
with `DropboxPaths.fileAt` (a path lookup), not `searchV2`. Auth is PKCE against
`dropbox-core-sdk`/`dropbox-android-sdk` 7.0.0, and the resulting `DbxCredential` goes through
`SecretStore` — an AES-256-GCM key in `AndroidKeyStore`, ciphertext in plain `SharedPreferences`
(`dropbox_credential`). It replaced `EncryptedSharedPreferences`, whose library was deprecated in
full with no successor; **a value that will not decrypt must keep reading as "no credential"**, so
a corrupt store starts a login instead of crashing a launch.

**A declined Dropbox login must turn the sync preference back off.** `SwitchPreference` persists
the moment it is tapped, so `pref_key_dropbox_sync` reads as enabled before any account is
connected — and "sync enabled, no credential" is exactly the condition
`DropBoxHelper.initializeDropboxV2()` treats as "start an auth". Backing out of the Dropbox login
therefore relaunched it, from the `onResume` the decline returned to, with no way off the screen.
`DropBoxHelper.handleDeclinedAuth()` closes that: call it from `onResume` **after**
`completeDropboxV2Init()` has had its chance at a credential, and it clears the preference when a
flow it started produced nothing. Both resume paths that can receive a decline call it —
`SettingsActivity`'s Data & sync fragment, which also unchecks the switch, and `MainActivity`,
which can receive one started by `DropboxBootstrap.initialize()`. Do not restore a bare
"enabled and no credential → authenticate" check in either place without that guard ahead of it.

**Sync is whole-file handover, and a device replaces only what it has seen.** The rules live in
`DropboxSyncRules`, free of network calls and tested there. The *marker*
(`pref_key_current_dropbox_db_version`) holds the Dropbox version the database was last in step
with, or `DBAdapter.KEY_DATABASE_CHANGE`; `last_known_dropbox_rev` holds the last remote version
this device saw.
- **Uploads of the main file are conditional** — `WriteMode.update(lastKnown)`, or `ADD` if this
  device never saw one — and a refusal records nothing and runs `KEY_DROPBOX_SYNC_NOW`, which lands
  in the existing conflict dialog. Only *keep local* and the upload after a restore are forced
  (`OVERWRITE`). Every save used to overwrite, which let a stale device silently replace another
  device's upload.
- An upload records its version as the marker only if the marker did not change while it ran.
- A successful `.edb` import sets the marker to "changed", so sync uploads it or asks.
- A restore from the backup list does not store the backup file's version.
- A download the sync check started carries the marker it decided on and stands down if a record
  was saved since.

`reminders/` is an `AlarmManager` + `BroadcastReceiver` pair; its
alarms are deliberately **inexact**, so the app needs no exact-alarm permission — see the comment in
`ReminderManager`. Measured on a phone, that costs **+1 to +4 minutes** on any setting, short or
long; it is a bounded slack, not a proportional overshoot.

**`setupReminder`'s "has the time already passed today" guard is load-bearing.**
`firstReminderCalendar` is *today's* date at the configured hour and minute, so a time earlier in the
day is in the past — and `setRepeating` on a past `RTC_WAKEUP` time fires **immediately**. Delete the
guard and setting 08:00 at 17:00 delivers a reminder the moment it is saved, then daily.

**`NotificationPermission` asks once, deliberately asymmetrically.**
`requestIfRemindersEnabled` (launch) asks at most once per install; `request` (the reminder switch)
asks every time, because flipping the switch is a fresh question. The stored flag is what stops the
denial message reappearing on every launch and rotation once Android starts auto-denying.

**A notification action must never route through `ReminderReceiver` to start an activity.** That is
a trampoline, blocked at targetSdk 31+ — the notification posts, the button is tappable, and the
system silently drops the `startActivity` behind it. `ReminderNotificationTest` pins the shape of
both actions: Settings must be a `getActivity` `PendingIntent`, Snooze must stay a broadcast.

**Premium gating is gone.** Billing, the 8 legacy IAB files under `util/`, the AIDL, and the ads
fragment were all removed in Phase 1. Multiple accounts, CSV export, Dropbox upload, and the
settings that were gated are now unconditional — do not reintroduce a check for them.

`DropboxBootstrap` (formerly `UpgradeHelper`, which managed billing and gated this inside its
premium branch) has a single job: bootstrapping Dropbox at startup. `MainActivity` and
`SettingsActivity` call its `initialize()`, and nothing else touches it.

## Conventions

- **The app is English everywhere, numbers aside.** Strings exist only in `values/`, and every date
  or time the app writes out uses `AppLocale.TEXT` (Locale.US), never `Locale.getDefault()` — so a
  German phone shows "Sep 17, 2026" and "Every 3 weeks on Thursday". Date and time pickers go
  through `AppLocale.datePicker` / `timePicker`, which build them with an English default (their
  header ignores the dialog's resources) and start the week on the app's First day of week setting.
  **Amounts and the first day of week keep the device's region format** ("4.214,00 €") — the
  maintainer's call. `EnglishTextTest`.

- Debug logging is gated on `BuildConfig.DEBUG` (`if (BuildConfig.DEBUG) Log.i(...)`), so a release
  build logs nothing. It used to be a `MainActivity.KEY_DEBUG` static that was always false.
- **There is no analytics.** `AnalyticsHelper` was a Firebase no-op kept so ~70 call sites could
  stay; the modernization deleted the class and every call. Do not add an analytics backend
  without saying so explicitly. Nothing leaves the device.
