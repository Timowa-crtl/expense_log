# Expense Log — Reliability pass

> **Status: complete, archived — in review as PR #9, not merged.** Written and executed
> 2026-09-14/15 from a full-codebase review focused on data integrity and main-thread work, then
> hardened by four follow-up code reviews and two rounds of Dropbox and device testing (the last on
> two emulators, covering the whole on-phone checklist). **192 tests, 0 failures; lint 0 errors /
> 237 warnings.** This document is the record of it; `PROGRESS.md` has the summary and `CLAUDE.md`
> the rules that came out of it.
>
> | Step | Findings | State |
> | --- | --- | --- |
> | 0 — branch, baseline, emulator | — | **Done** — 122 tests green; F26 found |
> | 1 — repeating records | F1, F2, F12 | **Done** — red proved, 133 tests green |
> | 2 — period and day boundaries | F3, F21, F22 | **Done** — red proved (weeks too), 137 tests green |
> | 3 — small, independent correctness fixes | F5, F14, F15, F17, F18, F19 | **Done** — F18 did not reproduce; 148 tests green |
> | 4 — one shared database connection | F10 | **Done** — 18 connections → 1; 154 tests green |
> | 5 — consistent copies, safe overwrites | F8, F9, F16 | **Done** — torn copies and journal replay proved; 161 tests green |
> | 6 — Dropbox task ordering and hardening | F7, F20, F24 | **Done** — checked against the test account; 165 tests green |
> | 7 — Dropbox never overwrites what it has not seen | F4, F6, F23, F25 | **Done** — silent overwrite proved on `main`, refused on branch; F25 unit-only; 182 tests green |
> | 8 — calendar performance and crash | F11, F26 (+ index, conditional) | **Done** — crash proved on `main`, gone on branch; no index needed; 183 tests green |
> | 9 — final pass and documentation | — | **Done** — import checks and suite isolation re-run; docs updated |

## The goal

**No ordinary use of the app — a repeating record, a month page, a save, a sync, an import — can
silently produce wrong numbers or silently replace data the user has not seen.**

The review's worst findings share a shape: they fail quietly. A weekly series that never stops
inserting, a February that includes March 1–3, an upload that replaces another device's database and
then reports itself in sync. Every step below therefore ends with a test that fails against today's
code wherever that is possible, and an emulator check against realistic data.

**Done means:** every finding in the table is fixed or explicitly deferred with a reason in this
file, the instrumentation suite is green with the new tests in it, lint has 0 errors, the final
device pass in Step 9 is recorded, and `PROGRESS.md` and `CLAUDE.md` describe the result.

## Rules for executing this plan

These restate constraints from `CLAUDE.md` and from how this project has been run. They bind every
step.

1. **Work on a branch named `reliability`, off `main`.** One commit per step at minimum, with the
   step number in the subject. Pushing `reliability` to back the work up is authorised (Tim,
   2026-09-14), and so is opening a PR **at the very end**, after Step 9. **Never merge.**
2. **Emulators only.** Run `adb devices` before any `adb` command; if a physical serial is attached,
   use `-s emulator-5554` explicitly and never target the phone. Anything that needs real hardware
   or real records is written down as owed to Tim, with the exact Windows `cmd` commands.
3. **`IsolatedDatabaseContext` in every test that touches `DBAdapter`, `FileHelper`,
   `LocalBackupManager` or default preferences.** A raw context writes into the live database.
4. **`DATABASE_VERSION` stays 1.** Nothing here changes a column. If a step seems to need a schema
   change, stop and ask.
5. **Red first.** Write the regression test, run it against the unchanged code, record that it
   fails (and how), then fix. Where a red run is impossible or unsafe (noted per step), say so in
   the step's record rather than skipping silently.
6. **A green build is not evidence on its own.** Read
   `mobile/build/outputs/androidTest-results/connected/debug/*.xml` for `failures`, `errors` and
   `skipped`; read lint's report for the error count.
7. **Narrowest change that closes the finding.** Tim uses Dropbox as a *handover* between devices,
   not as a concurrently shared database, and whole-file replacement is the intended design. Do not
   introduce merging, record-level sync, Room, or an architecture change that a finding does not
   require.
8. **Keep the five `FileHelper` rules in `CLAUDE.md` intact** — stage → validate → overwrite; path
   getters never open the database; `FileHelper` reports and does not present; `content://` only;
   cleanup deletes only inside `exports/`.
9. **Update the step table and the step's *Record* section as you go**, with test counts and what
   the gate actually showed.

### Stop and ask Tim when

- Android Studio (`studio64.exe`) is running and the emulator will not boot in reasonable time —
  it is his session; ask before closing it (`gradlew.bat --stop` for the daemon is fine).
- The Dropbox login on the emulator asks for a confirmation or 2FA code.
- A fix would need a schema change, a new dependency, or an AndroidX upgrade.
- A finding turns out not to reproduce on the emulator *and* its test cannot be made red — record
  the evidence and ask whether to keep or drop the fix.
- Anything would touch existing user data in place (for example, repairing already-drifted monthly
  series — deferred, see the end of this file).

## Commands

All via a `.bat` in a Windows-visible path, per `CLAUDE.md` (never put `;`-containing arguments on
the `cmd.exe` command line). Run long ones with `run_in_background: true`.

```bat
@echo off
set JAVA_HOME=C:\Users\Tim\.jdks\jbr-21.0.11
cd /d C:\Users\Tim\AndroidStudioProjects\expense_log
rem build + lint
gradlew.bat :mobile:assembleDebug :mobile:lintDebug --console=plain
rem one test class
gradlew.bat :mobile:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=de.timowa.expenselog.RepeatingScheduleTest --console=plain
rem full suite
gradlew.bat :mobile:connectedDebugAndroidTest --console=plain
```

Emulator (headless is steadier; see the memory notes on RAM and `hide_error_dialogs`):

```bat
@echo off
set SDK=C:\Users\Tim\AppData\Local\Android\Sdk
start "emu" /b %SDK%\emulator\emulator.exe -avd Pixel_6 -no-window -gpu swiftshader_indirect -no-snapshot-save
```

Realistic data: load `C:\Users\Tim\AndroidStudioProjects\Expense Log 2026-09-12.edb` (2544 records,
outside the repo — never commit it) with the verified `run-as` steps, **after setting the emulator
timezone to `Europe/Berlin`**. `connectedDebugAndroidTest` uninstalls the app and wipes it, so do
data-dependent UI checks first and run the suite after, or reload.

Locale for German-specific checks (AOSP image, so `adb root` works):

```bat
%ADB% root
%ADB% shell "setprop persist.sys.locale de-DE; setprop ctl.restart zygote"
```

UI assertions: `adb shell uiautomator dump /sdcard/ui.xml` + `adb pull`, and
`adb shell screencap -p /sdcard/s.png` + `adb pull` (never `exec-out` through `cmd`). Jank:
`adb shell dumpsys gfxinfo de.timowa.expenselog reset`, drive the UI, then
`adb shell dumpsys gfxinfo de.timowa.expenselog` and read *Janky frames*.

---

## Findings

Numbers F1–F20 are from the review. F21–F25 were found while writing this plan, by reading the code
paths the fixes touch.

| # | Severity | Finding | Where |
| --- | --- | --- | --- |
| F1 | Critical | A weekly repeating series that crosses New Year loops forever, inserting on the main thread. `set(WEEK_OF_YEAR, get+n)` jumps back to January when late December is week 1 of the next year. Reproduced on the desktop JVM: from 1 Dec 2025 it goes 29 Dec → 6 Jan **2025**. US locale is hit in Dec 2026; de_DE first in Dec 2029. | `DBAdapter.nextRecordTime` `:606-625`, `createRepeatingLogs` `:585-604` |
| F2 | Critical | Monthly series drift and stay drifted: from 31 Jan → 3 Mar, 3 Apr, 3 May… (same `set(MONTH, …)` pattern; yearly from 29 Feb likewise). | same |
| F3 | Critical | On the 29th–31st of a month, month pages (and year pages on 29 Feb) run into the following period: with today 30 Jan, February is 1 Feb – 3 Mar. Feeds list, summary, graph, budget and CSV export. `PeriodBoundsTest.neighbouringPeriodsTile` only fails when run on those days. | `LogTabsFragment.getPeriodEnd` `:596-601`, `ExportPeriod.java:42` |
| F4 | Critical | Every save uploads with `WriteMode.OVERWRITE` and records the result as the last-known version, bypassing the conflict check. A save on the second device before (or without) a sync check replaces the first device's upload and hides the conflict. Reachable in handover use: the launch sync check is asynchronous and rate-limited to once per 20 s. | `NewLogFragment.java:771`, `DropboxUpload.java:86-101` |
| F5 | High | Weekly Dropbox auto-backup is never refreshed on a German-locale phone: `Date.toString()` (always English) is parsed with `Locale.getDefault()`, the `ParseException` is swallowed, and the age computes as 0 days. Reproduced on the desktop JVM. | `DropboxAutoBackups.java:108-113` |
| F6 | High | A successful `.edb` import leaves the Dropbox version marker at the old remote version, so the import is never uploaded, and the next remote change is downloaded over it without a prompt. | `FileHelper.importFileAndVerify`, `MainActivity.java:239` |
| F7 | High | `DropboxTask` uses `newCachedThreadPool`; `AsyncTask` was serial. Uploads can finish out of order (the older one wins remotely), and a download can overlap an upload. Unchecked exceptions in `doInBackground` crash the process. | `DropboxTask.java:18-29` |
| F8 | High | The live database file is copied byte-for-byte while other connections may be writing — uploads, local backups, export staging, the import safety backup — and import truncates and rewrites it under open connections. Copies can be torn. | `DropboxUpload.java:83`, `DropboxAutoBackups`, `DropboxNewBackup`, `LocalBackupManager.copyFile`, `FileHelper.stageDbForExport`/`backupDbToSd`/`importProvidedFile` |
| F9 | High | Import continues when the safety backup failed. `importProvidedFile` deletes `-wal`/`-shm` but not `-journal`, the journal this database actually uses; a hot journal would be replayed onto the imported file. | `FileHelper.java:621-622`, `:797-810` |
| F10 | Medium | `DBAdapter` is unscoped: every injection and every `new DBAdapter` opens a separate `SQLiteOpenHelper` connection in its constructor, on the main thread, and injected ones are never closed. | `DBAdapter.java:125-131` |
| F11 | Medium | The calendar opens a new database connection and runs two `SUM` queries per day cell on every `getView`, after `refreshDays` already ran 31 range queries and holds every day's records in memory. | `CalendarAdapter.java:112-125`, `:179-204` |
| F12 | Medium | Repeating series insert one auto-committed row at a time (plus a preferences write per row) on the main thread; a failure leaves half a series. | `DBAdapter.java:585-607` |
| F13 | Medium | `LogTabsFragment.onResume` resets the spinner, which rebuilds the whole pager on every resume. **Deferred** — see the end of this file. | `LogTabsFragment.java:231`, `:278-280` |
| F14 | Low | Leaked cursors: `getMostRecentRecordForCategory` (called per category on every Expense/Income toggle), `NewLogFragment.initializeCategoryList`, `LogListFragment.java:157`, `getCategoryLabel`/`getAccountLabel` when not found. Category recency is N+1 queries. | `DBAdapter.java:423`, `:437-503`; `NewLogFragment.java:484`, `:504-558` |
| F15 | Low | CSV export swallows `IOException`, then offers the partial file and reports "export saved". | `SpreadsheetHelper.java:295-336` |
| F16 | Low | `LocalBackupManager.copyFile` truncates the existing backup before copying, leaks channels on exceptions. | `LocalBackupManager.java:108-123` |
| F17 | Low | `deleteTag`, `deleteAccount`, `deleteRepeatingLogs` run two deletes without a transaction. | `DBAdapter.java:403`, `:516`, `:556` |
| F18 | Low (verify) | At targetSdk ≥ 26 a `numberDecimal` field may accept `,` in a German locale; `Double.parseDouble("12,50")` then crashes the save. | `NewLogFragment.java:869` |
| F19 | Low | The repeat end-date picker is initialised with `DAY_OF_YEAR` as the day of month. | `NewLogFragment.java:953-954` |
| F20 | Low | Sync and auto-backup locate files with `searchV2` (eventually consistent, ranked, can return a folder) instead of a path lookup; a debug log does `get(0)` on a possibly empty result. | `DropboxSyncCheck.java:122-131`, `DropboxAutoBackups.java:90-95`, `:135-138` |
| F21 | Low | The list selects with exclusive `>`/`<` while totals use inclusive `BETWEEN`, so a record at exactly a period's first millisecond counts in the totals but is missing from the list. | `LogListFragment.java:223-224` |
| F22 | Medium | Calendar day bounds never set `MILLISECOND`, so the day starts at the current wall-clock millisecond. The fixture's record at 1 May 2022 00:00:00.216 disappears from the calendar whenever the clock's millisecond is above 216 — the bug e7b9eb0 fixed for pages, still live here. | `CalendarAdapter.getDayStart/getDayEnd` `:140-160`, `CalendarDay.java:93-106`, `:148-161`, `:195-208` |
| F23 | Medium | Restoring a Dropbox backup stores the *backup file's* version as the main database's version. If the follow-up upload fails, the next sync sees versions differ and downloads the old main database over the restore. | `DropboxDownload.java:157-159`, `DropBoxHelper.java:335-341` |
| F24 | Low | Dropbox tasks never close their upload `FileInputStream`s; `DropboxDownload` passes a null stream on `FileNotFoundException`, never closes `mFos`, and dismisses a `ProgressDialog` that may belong to a destroyed activity. | `DropboxUpload.java:83`, `DropboxAutoBackups.java:163`, `DropboxNewBackup`, `DropboxDownload.java:131-180` |
| F26 | High | **Found on the emulator in Step 0.** Paging the calendar quickly crashes the app: `CalendarSetupAsync.doInBackground` checks `isAdded()` and then calls `requireActivity()` on a background thread, and the `ViewPager` can detach the page in between — `IllegalStateException: Fragment LogsCalendarFragment … not attached to an activity`, `FATAL EXCEPTION: AsyncTask #4`. | `LogsCalendarFragment.java:135-141` |
| F25 | Medium | A sync check that decided "download" still downloads if the user saved a record while it was running, overwriting that record — the check read the version marker before the save changed it. | `DropboxSyncCheck.doInBackground`, `DropboxDownload.doInBackground` |

---

## Why the order is what it is

- **Steps 1–3 first:** they are the worst user-visible bugs (F1–F3) and completely contained — no
  threading, no Dropbox, no file handling — so they can be fixed and verified without touching
  anything load-bearing.
- **Step 4 before 5, and 5 before 6–7:** a consistent copy of the database (F8) and a safe overwrite
  (F9) are only achievable when the whole process talks to the file through one connection (F10).
  The Dropbox fixes then upload those consistent copies.
- **Step 6 before 7:** conditional uploads (F4) depend on uploads and downloads running in order
  (F7); otherwise the conflict handling races itself.
- **Step 8 last among code steps:** it is performance, not correctness, and it is measured before
  and after, so it should run on the final data layer.

---

## Step 0 — Branch, baseline, emulator

1. `git switch -c reliability` from a clean `main`.
2. Build and lint. Record the lint error/warning counts (expected **0 errors, 241 warnings**).
3. Boot `Pixel_6`, run the full suite, and record tests / failures / errors / skipped from the XML
   (`PROGRESS.md` says **122 tests in 19 classes**). If the baseline is not green, stop and report —
   nothing below can be judged against a red baseline.
4. Load the fixture in `Europe/Berlin` and take baseline screenshots of the September 2026 list and
   summary pages, and of the May 2022 calendar page (note whether 1 May shows its 00:00:00.216
   record — see F22).
5. Baseline jank for Step 8: `gfxinfo reset`, swipe the calendar month pages 10× each way, record
   *Janky frames* and the 90th percentile.

**Gate:** baseline numbers written into this step's record.

### Record

2026-09-14, branch `reliability` off `main` at `496ff5e`.

- **Build and lint:** green; lint **0 errors, 241 warnings**.
- **Suite:** `Pixel_6` (API 33) — **122 tests, 0 failures, 0 errors, 0 skipped.** No physical
  device attached (`adb devices` showed `emulator-5554` only). Android Studio was running; the
  emulator was already up, so nothing had to be closed.
- **Fixture (Berlin):** Sep 2026 month list shows **$2,553.94 / −$88,763.76**, matching the host
  recomputation. All Records **$185,660.22 / −$267,512.51** = host totals over 2544 records.
- **F22 reproduced on the emulator:** May 2022 calendar shows 1 May as **4 records, $1,771.51**.
  The host recomputation is **5 records, $4,061.51** — the missing one is the 00:00:00.216 income of
  2,290.00. The calendar drops it.
- **F26 found:** the first attempt to page the calendar back to May 2022 (quick swipes) crashed the
  app — `IllegalStateException: Fragment LogsCalendarFragment … not attached to an activity` from
  `CalendarSetupAsync.doInBackground`. Added to the findings and to Step 8.
- **Jank baseline** (May 2022 calendar, 10 taps on the previous-month tab then 10 on the next, 1.3 s
  apart): 55 frames, **55 janky (100 %)**, 50th percentile 1000 ms, 90th 1500 ms, *Slow UI thread*
  55. The headless emulator renders in software, so absolute numbers are meaningless; Step 8
  compares against these on the same emulator.

---

## Step 1 — Repeating records (F1, F2, F12)

### Change

1. **Compute each occurrence from the original, never from the previous occurrence.** Replace
   `nextRecordTime`'s `set(field, get(field) + n)` with a pure, package-visible function, e.g.
   `static long occurrence(long originalTime, int k, int frequency, int period)`, that clones the
   original calendar and calls `add` once:
   `DAY → add(DATE, k*f)`, `WEEK → add(DATE, 7*k*f)`, `MONTH → add(MONTH, k*f)`,
   `YEAR → add(YEAR, k*f)`. `Calendar.add` handles year roll-over, clamps 31 Jan + 1 month to 28/29
   Feb, and — because every occurrence starts from the original — 31 Jan + 2 months is 31 Mar again,
   not a drifted 28 Mar. `add(DATE)` keeps wall-clock time across DST.
2. **Loop on `k`,** `k = 1, 2, …` while `occurrence(k) < frequencyEndTime`, keeping today's
   exclusive end and "first record not included" semantics.
3. **Defensive ceiling:** if `k` exceeds a generous bound (e.g. 20 000 — a 50-year daily series is
   18 263), abandon the series. It must be impossible for any future stepping bug to fill the disk.
4. **One transaction.** A new `DBAdapter` method creates the `RepeatingTable` entry and all its
   records inside `beginTransaction`/`setTransactionSuccessful`/`endTransaction`, calls
   `databaseChange()` **once**, and returns the repeating id or `-1`. `nextRecordTime`'s stray
   `databaseChange()` goes. Hitting the ceiling or any insert failure rolls the whole series back.
5. `NewLogFragment.createRepeatingRecords`/`saveLog`: on `-1`, show the existing
   `R.string.error_saving` snackbar and do not save the first record either — the user retries
   rather than ending up with a single orphan.

### Tests — new `RepeatingScheduleTest` (instrumentation, so it runs on Android's own `Calendar`)

For each of `Locale.US`, `Locale.GERMANY`, `Locale.UK`, with `TimeZone` set to `Europe/Berlin` and
`America/New_York` (restore both in `@After`):

- **Weekly across New Year:** from 1 Dec 2025, 15 Dec 2026 and 1 Dec 2029, every occurrence is
  exactly 7 calendar days after the previous one, strictly increasing, and a series to 1 Mar of the
  next year has the expected count.
- **Monthly from the 31st:** 31 Jan 2026 → 28 Feb, 31 Mar, 30 Apr, 31 May …; from 30 Jan → 28 Feb,
  30 Mar; 29 Feb 2024 yearly → 28 Feb 2025, 28 Feb 2026, 28 Feb 2027, **29 Feb 2028**.
- **Every 2 weeks / every 3 months** frequencies.
- **DST:** a daily 09:30 series across the last Sunday of March and of October stays at 09:30.
- **DB level, isolated:** creating a series inserts exactly the expected number of rows with one
  repeating id; a series whose end is 60 years out on a daily period hits the ceiling and leaves
  **zero** new rows and no `RepeatingTable` entry.

**Red run:** the monthly and yearly cases against today's `createRepeatingLogs` (through the DB
level test) must fail on the dates. **Do not run the weekly New-Year case against today's code** —
it does not terminate and fills the test database; record that its red evidence is the desktop JVM
reproduction in the review (29 Dec 2025 → 6 Jan 2025, runaway past 2 000 rows).

### Emulator check

With the fixture loaded: create a weekly repeating record on 1 Dec 2026 ending 1 Feb 2027 (US
locale and then de-DE). The save returns promptly; the list for Dec 2026 and Jan 2027 shows 9
records, one per week, no January 2026 records added (compare the Jan 2026 count before/after).
Create a monthly record on 31 Jan 2027 ending 1 Jul 2027 and confirm 28 Feb, 31 Mar, 30 Apr, 31 May,
30 Jun on the list.

**Gate:** new tests green, full suite green, lint 0 errors, emulator observations recorded.

### Record

- **Red, on the emulator, against today's `createRepeatingLogs`:** the monthly case stored
  `[2026-03-03, 2026-04-03, 2026-05-03, 2026-06-03]` — February missing entirely, every later month
  on the 3rd; the leap-day yearly case stored `[2025-03-01, 2026-03-01, 2027-03-01]` and lost 2028.
  The weekly New-Year case was not run against the old code (it does not terminate); its red
  evidence remains the desktop JVM reproduction.
- **Change:** `DBAdapter.repeatingOccurrence` (pure, from the original) and
  `DBAdapter.createRepeatingSeries` (one transaction, one `databaseChange()`, ceiling of 20 000,
  returns −1 with nothing written). `createRepeatingLogs` and `nextRecordTime` are gone.
  `NewLogFragment` shows `error_saving` and saves nothing on −1. A frequency of 0 (which never
  advances) also ends at the ceiling and writes nothing.
- **Tests:** `RepeatingScheduleTest`, 11 tests (US/DE/UK × Berlin/New York; weekly checked against
  `java.time`, independent of `Calendar`). Green.
- **Emulator, en_US, Berlin, clock set to 1 Dec 2026 10:00** — the exact runaway case for US
  locale: a weekly record with the default one-year end saved normally; the pulled database holds
  **53 rows, one repeating id, every gap exactly 7 days, 2026-12-29 → 2027-01-05**, integrity `ok`;
  the All Records expense total rose by exactly 53 × 12.34. **Clock at 31 Jan 2027:** a monthly
  record stored `2027-01-31, 02-28, 03-31, 04-30, 05-31, 06-30, 07-31, 08-31, 09-30, 10-31, 11-30,
  12-31, 2028-01-31`. Clock restored to automatic afterwards.
- **Gate:** build green, lint **0 errors / 241 warnings**, suite **133 tests, 0 failures, 0 errors,
  0 skipped**.

---

## Step 2 — Period and day boundaries (F3, F21, F22)

### Change

1. **Make "now" injectable** for testing: package-private overloads
   `getPeriodStart(mode, offset, ctx, prefs, Calendar now)` / `getPeriodEnd(…, Calendar now)`; the
   existing signatures delegate with `Calendar.getInstance()`. Every inline `Calendar.getInstance()`
   inside the week branches (`:556`, `:567`, `:619`, `:626`) must use `now`, or the test cannot pin
   the date.
2. **Define the end as the next period's start minus one millisecond** for YEAR, MONTH, WEEK and DAY:
   `getPeriodEnd(mode, offset) = getPeriodStart(mode, offset + 1) − 1`. That makes tiling true by
   construction and deletes the second, divergent copy of the week arithmetic. ALL mode keeps its
   constant.
3. In `getPeriodStart`, set `DAY_OF_MONTH` to 1 (MONTH) or `DAY_OF_YEAR` to 1 (YEAR) **before**
   changing the month or year, so no intermediate date like 31 Feb is ever normalised.
4. **List bounds (F21):** `LogListFragment.getLogList` uses `>=` / `<=`, matching `BETWEEN`.
5. **Calendar day bounds (F22):** one helper (e.g. a static `dayBounds(year, month, day)` or reuse
   `getPeriodStart/End(KEY_RECORDS_MODE_DAY, …)` with an explicit date) used by `CalendarAdapter`
   and all three copies in `CalendarDay`, zeroing `MILLISECOND` and ending at `.999`. Delete the
   unused `GetEvents`/`GetDayLogsAsyncTask`/`updateDayView` classes if nothing calls them (grep
   first) rather than fixing dead copies.

### Tests — extend `PeriodBoundsTest`

With `TimeZone` `Europe/Berlin`, first day of week both at the default and at Monday (a
test-only preferences file, deleted in `@After`), for **every "now" from 1 Jan 2025 to 31 Dec 2028**
(covers every 29th–31st, 29 Feb 2028, every New Year and DST change), for YEAR/MONTH/WEEK/DAY and
offsets −2…+2:

- `end(offset) + 1 == start(offset + 1)` (tiling);
- `start(0) <= now <= end(0)` (the current page contains today);
- `start` is at 00:00:00.000; MONTH starts on day 1, YEAR on 1 Jan, WEEK on the configured weekday;
- MONTH page length equals that month's day count × 24 h (± 1 h on DST months).

Plus a calendar test: a record inserted (isolated DB) at 00:00:00.216 and one at 23:59:59.900 on the
same day are both in that day's `CalendarDay` events, whatever the current millisecond.

**Red run:** the pinned-date tiling test fails today for "now" on the 29th–31st (e.g. now = 30 Jan
2026: February ends 3 Mar). The calendar millisecond test fails when the wall clock's millisecond
is above 216 — make it deterministic by calling the bounds helper through the old code path in a
loop until a failure is observed or 2 s elapse, and record which happened.

### Emulator check

Fixture loaded, timezone Berlin. Set the emulator date to **30 Jan 2027** (`adb root`,
`settings put global auto_time 0`, `date 013012002027.00`). Open Records → Month, swipe to
February 2027, then March: no record appears on both pages, and the summary totals for Feb + Mar
equal a host-side recomputation from the fixture with Python `sqlite3` + `zoneinfo`. Open the
calendar for May 2022: 1 May shows its 00:00:00.216 record. Export a CSV of February 2027 and check
its row count against the host computation. Restore `auto_time 1` afterwards.

**Gate:** tests green (report how many date combinations ran), full suite green, lint 0 errors,
emulator observations recorded.

### Record

- **Test added as a new class, `PinnedPeriodBoundsTest`,** rather than inside `PeriodBoundsTest`,
  which stays as the unpinned check. Every day of 2026–2028 at 00:00:00.000, 12:34:56.789 and
  23:59:59.999, Berlin; YEAR/MONTH/DAY at the default week start and WEEK at **all seven** stored
  first-day-of-week values; offsets −2…+2. Three tests: tiling, current page contains today,
  starts on the boundary at midnight.
- **Red (before any fix, with only the behaviour-neutral "now" overloads added):** tiling failed
  **1 786 of 98 640 checks** — MONTH 186, WEEK with Saturday start 785, and **WEEK with the default
  Monday start 815**. The plan had only predicted the month case; the week pages overlapped as well,
  because `getPeriodEnd` had its own copy of the week arithmetic whose correction disagreed with
  `getPeriodStart`'s. The other two tests passed: starts were right, only ends were wrong.
- **First fix** (end = next start − 1 ms) exposed one more edge in the *start*: 157 checks, WEEK
  with Saturday start, `now` at exactly 00:00:00.000 on a Saturday — a strict `<` in the old
  correction put that instant into the previous week. Replaced the estimate-and-two-corrections
  with a direct "days back to the first day of week" computation (`add(DATE)`), and widened the
  test to every week-start value.
- **F22, red on the old calendar code** (`CalendarDayBoundsTest`, records at 00:00:00.000,
  00:00:00.216, 12:00, 23:59:59.900, 23:59:59.999 plus the two neighbouring milliseconds): at clock
  millisecond 886 the day held **1 of 5**. Fixed with `CalendarDay.dayStart/dayEnd`; the three
  copies of the bounds collapsed into one, and the two unused `AsyncTask` loaders
  (`GetEvents`, `GetDayLogsAsyncTask`) and `CalendarAdapter.updateDayView` deleted.
- **F21:** list selection is now `>=`/`<=`. No separate test — the tiling test fixes the bounds at
  the first and last millisecond, which is exactly where the exclusive comparison lost records.
- **Emulator, fixture, Berlin, clock 30 Jan 2027 12:00:** Month list, **Feb 2027 shows only the
  1 Feb record (−$4.99), Mar 2027 only the 1 Mar record** — before the fix, February ran to 3 Mar
  and would have included it. **Clock 15 May 2022:** the calendar's 1 May shows **5 records,
  $4,061.51**, matching the host recomputation (Step 0 showed 4 / $1,771.51). The CSV-export check
  is folded into Step 3's F15 work, since `ExportPeriod` calls the same, now exhaustively tested,
  functions. Clock restored.
- **Gate:** lint **0 errors / 238 warnings** (−3: the deleted `AsyncTask` inner classes), suite
  **137 tests, 0 failures, 0 errors, 0 skipped**.

---

## Step 3 — Small, independent correctness fixes (F5, F14, F15, F17, F18, F19)

Each is a separate commit inside the step so a problem can be reverted alone.

1. **F5 — auto-backup age:** use `fileMeta.getServerModified().getTime()` in the weekly branch (as
   the monthly branch already does) and delete the `SimpleDateFormat` parse. Test: extract the
   "is this backup due" arithmetic to a pure function (`static boolean isDue(long serverModified,
   long now, int days)`) and cover it under `Locale.GERMANY`; the red evidence is the review's JVM
   reproduction (`ParseException` in de_DE), since the old code path needs a network response.
2. **F15 — CSV failure:** `createSpreadsheet` returns early when writing throws: delete the partial
   file, do not record it as the pending export, toast `R.string.export_save_failed`. Test: a write
   into a destination that cannot be created (make the staged path a directory in the isolated
   `exports/`) reports failure and leaves no pending export. Red: today it records the path.
3. **F19 — end-date picker:** pass `c.get(Calendar.DAY_OF_MONTH)`. Emulator: open a repeating
   record's end date picker and dump the UI — the picker shows the stored end date.
4. **F18 — decimal comma (verify first):** in de-DE on the emulator, type `12,50` into the amount
   with `adb shell input text` and save. **If it crashes** (check logcat for
   `NumberFormatException`): parse through one helper that accepts `,` or `.` as the decimal
   separator and shows `R.string.error_saving` (or a new, specific string) instead of throwing on
   anything unparseable; test the helper. **If the field refuses the comma**, record that and make
   no change.
5. **F14 — cursors and category recency:** close every cursor named in F14 (try-with-resources, as
   `getTagLabel` already does). Replace the per-category loop in `initializeCategoryListByRecency`
   with one query — `SELECT categoryId, MAX(start_time) FROM mainLogs GROUP BY categoryId` — joined
   in memory with `getTagsOfType`, keeping today's ordering (most recent first, never-used
   categories after, alphabetical among themselves). Test: on generated isolated data, the new
   ordering equals the old per-category ordering.
6. **F17 — atomic deletes:** wrap the two statements in each of `deleteTag`, `deleteAccount`,
   `deleteRepeatingLogs` in a transaction. Test: each still removes exactly the parent row and its
   records (no red is possible for atomicity; say so).

### Emulator check

Covered per item above, plus: toggle Expense/Income 20× on New Record with the fixture loaded and
confirm logcat shows no `Finalizing a Cursor that has not been deactivated` / leaked-cursor
warnings (StrictMode is not enabled, so grep logcat for `Cursor`).

**Gate:** tests green, full suite green, lint 0 errors.

### Record

Committed as three commits rather than six: F14, F17 and F19 share `DBAdapter` and
`NewLogFragment` hunks, so they went together.

- **Before/after on the emulator** used a debug APK built from `main` in a git worktree
  (`../expense_log_main`), installed over the same fixture data (`install -r`), then the fixed
  build over it again. The emulator was switched to **de-DE** for this step (`settings put system
  system_locales de-DE` + zygote restart; `persist.sys.locale` alone did not take on API 33).
- **F5:** red on Android's runtime with a temporary test (deleted after): under `Locale.GERMANY`,
  `ParseException: Unparseable date: "Fri Sep 04 18:24:28 GMT+02:00 2026"`. Fixed with
  `DropboxAutoBackups.isDue` from `getServerModified().getTime()`; `DropboxAutoBackupsTest`, 3 tests.
  No emulator check — an aged Dropbox file cannot be produced on demand.
- **F15:** `SpreadsheetHelper.writeExport` returns false and deletes a partial file; the caller
  toasts `export_save_failed` and records and offers nothing. `SpreadsheetWriteTest`, 3 tests
  (written, unopenable destination, failure part-way). **Emulator:** a directory created at the
  staged name `Expense Log all records 2026-09-14 list.csv`, then Records → Export → Save. `main`
  **opened the document picker** (`documentsui … PickActivity` resumed) after the write had failed;
  the fixed build stayed on `MainActivity`.
- **F19:** default end date *Sept. 14, 2027*. `main`'s picker opened on **14 May 2028**; the fixed
  build's on **14 Sept 2027**.
- **F18 — did not reproduce; no change.** In de-DE, `adb shell input text 12,50` into the amount
  field produced **`1250`**: the `numberDecimal` field rejects the comma rather than passing it to
  `Double.parseDouble`, so there is no crash, while `12.50` is accepted. Recorded here because the
  typed-comma-becomes-thousands behaviour is visible on screen before saving but is worth Tim's
  eye; a locale-aware amount field is a separate, UI-level change.
- **F14:** `getTagIdsByRecency` (one `GROUP BY`) replaces two queries per category; every cursor
  named in F14 closed. `CategoryRecencyTest` compares it against the old per-category algorithm on
  413 generated records with ties, unused categories and a pre-1970 record. **Emulator:** the
  "Category (Latest)" dialog lists the same order on `main` and the fixed build for both expense
  (General, Aktien, Alkohol, Eating Out, …) and income (Salary, Gift, Verkauf, …). The planned
  logcat check for leaked cursors was dropped: `SQLiteCursor` only reports a leak through
  StrictMode, which the app does not enable, so silence would prove nothing.
- **F17:** `deleteTag`, `deleteAccount`, `deleteRepeatingLogs` in transactions; three tests that
  each removes exactly its rows (atomicity itself cannot be made red).
- **Gate:** lint **0 errors / 237 warnings**, suite **148 tests, 0 failures, 0 errors, 0 skipped**.

---

## Step 4 — One shared database connection (F10)

### Change

Keep `DBAdapter`'s public surface; change what it holds.

1. **One `DatabaseHelper` per database file, process-wide.** In the constructor, resolve the path
   with **the caller's context** — `context.getDatabasePath(DATABASE_NAME).getAbsolutePath()` — which
   is exactly what keeps `IsolatedDatabaseContext` working. Look the helper up in a static
   `Map<String, DatabaseHelper>` keyed by that absolute path (synchronised), creating it with
   `context.getApplicationContext()` and **the absolute path as the name** (`ContextImpl` accepts an
   absolute database name), so no activity is retained statically.
2. **No cached `SQLiteDatabase` field.** Every method gets `helper.getWritableDatabase()` through a
   private accessor. That call returns the already-open instance cheaply, and — unlike a cached
   field — reopens transparently after a deliberate close.
3. **The constructor stops opening the database.** The first query opens it. `open()` stays as a
   harmless call for existing callers.
4. **`close()` becomes a no-op** (javadoc says why: the connection is shared and lives for the
   process). Add `static void closeSharedConnection(Context)` which closes and removes the helper for
   that context's path — the only legitimate closer, used by import and restore in Step 5.
5. **Pool size stays 1** (WAL stays disabled, as `walIsDisabled_soUploadsCannotMissRecentWrites`
   requires): every thread's access serialises on the one connection, which Step 5 relies on.
6. Existing `new DBAdapter(...)` + `close()` call sites can stay; they now share the connection.
   Do not remove them in this step.

### Tests

- New `SharedConnectionTest`: two `DBAdapter`s on the same isolated context see each other's writes
  and share one `SQLiteDatabase` instance; a `DBAdapter` on `IsolatedDatabaseContext` opens a file
  whose name starts with `test_` (isolation still holds); `close()` on one does not break the other;
  after `closeSharedConnection`, a new query reopens and works; writes from two threads interleave
  without `SQLiteDatabaseLockedException`.
- **The whole existing suite** is the real test here — `EdbRoundTripTest`, `NewerSchemaImportTest`
  and `FilesystemIsolationTest` especially, since they close and reopen. A test that relied on
  `close()` closing must switch to `closeSharedConnection`, and the record says which ones did.

**Red run:** the "same instance" assertion fails today.

### Emulator check

Fixture loaded: open list, graph, calendar and summary, swipe each 10 pages, add, edit and delete a
record. Then `adb logcat -d | findstr /i "leaked SQLiteConnection"` (run the grep inside
`adb shell` per the memory notes) shows **no** leaked-connection warnings — the review noted one
such warning in Phase 3's open items; record whether it is gone.

**Gate:** full suite green, lint 0 errors, logcat observation recorded.

### Record

- **Red, against the old adapter** (`SharedConnectionTest`, the tests that compile against the old
  API): *same instance* failed as predicted, and **`twoThreadsWriting…` failed with
  `SQLiteDatabaseLockedException: database is locked (code 5 SQLITE_BUSY)`** — two adapters writing
  from two threads, the shape of the UI saving while a Dropbox task opens its own adapter. The plan
  had only expected the instance check to be red.
- **Change, and one deviation from the plan:** helpers keyed by absolute path in a static map, the
  path resolved through the caller's context and the helper built on the application context with
  the absolute path as its name; every method goes through `db()`; `close()` is a documented no-op;
  `closeSharedConnection(Context)` added. Transactions bind one `SQLiteDatabase` local. **The
  constructor still opens the database eagerly** rather than lazily as planned: existing callers
  (`FileHelper.stageDbForExport`, `fileSetup`, and tests such as `ExportStagingTest`) rely on a new
  adapter having created the file, and only the first adapter per process pays for the open.
- **Also pulled forward from Step 5:** `importProvidedFile` and `restoreBackupDbFromSd` call
  `closeSharedConnection` before replacing the file — with one long-lived connection, overwriting
  the file under it is no longer something to leave for later. `IsolatedDatabaseContext.deleteDatabase`
  closes the shared connection first, so each test still starts from a fresh file.
- **No existing test needed changing** — the whole previous suite passed unmodified.
- **Emulator** (fixture, identical scripted workout on `main` and the branch: List, Graph, Calendar,
  Summary each paged 5 months, then one saved record, then three forced GCs via `kill -10`, then
  `dumpsys dbinfo`): **`main` had 18 connection pools open to `expenseLog.edb`; the branch had 1.**
  The saved record is in the pulled database (2545 rows, integrity `ok`). No `leaked`,
  `database is locked` or `FATAL` lines in logcat on either — `SQLiteConnectionPool` only reports a
  leak when a pool is finalised, and these were still reachable.
- **Gate:** lint **0 errors / 237 warnings**, suite **154 tests, 0 failures, 0 errors, 0 skipped**.

---

## Step 5 — Consistent copies, safe overwrites (F8, F9, F16)

### Change

1. **One way to copy the live database:** `FileHelper.snapshotDatabaseTo(Context, File dest)`.
   On the shared connection, `beginTransactionNonExclusive()` (SQLite `BEGIN IMMEDIATE`, which
   blocks every other writer and — with one pooled connection — every other thread), copy the
   database file to `dest + ".tmp"`, `FileDescriptor.sync()`, rename over `dest`, then
   `endTransaction()` without marking success (nothing was written). Returns a boolean and logs;
   no toast (rule 3). **Must not be called with the connection closed.**
2. **Use it everywhere the live file is read as bytes:** `backupDbToSd`, `stageDbForExport`,
   `LocalBackupManager` (F16 — which also fixes truncate-before-copy, since the old backup is only
   replaced by a finished rename), and all three Dropbox uploaders, which snapshot into
   `getCacheDir()` first, upload the snapshot, and delete it in a `finally` (so no lock is held for
   the duration of a network call).
3. **Import refuses without a backup (F9):** if `backupDbToSd` fails, return a new
   `ImportResult.BACKUP_FAILED` (nothing touched) with a new English string in
   `res/values/strings.xml` — there are no other locales.
4. **Safe overwrite:** `importProvidedFile` and `restoreBackupDbFromSd` call
   `DBAdapter.closeSharedConnection(context)` first, delete `-journal` as well as `-wal`/`-shm`,
   write the incoming bytes to a temp file **in the database directory**, sync, and rename over the
   database. Rule 1 (stage → validate → overwrite) is unchanged: this only changes how the final
   overwrite is performed.
5. `getInternalDbFile` / `getInternalDbFolder` stay pure path getters (rule 2). The snapshot helper
   is a separate method precisely so they never gain a database open.

### Tests — new `DatabaseSnapshotTest` and additions to `EdbRoundTripTest`

- **Snapshot under concurrent writes:** a background thread inserts 3 000 rows; the test thread
  takes 50 snapshots meanwhile; every snapshot opens read-only, passes `PRAGMA integrity_check`,
  and its row count is non-decreasing across snapshots. Red: run the same loop with a raw
  `FileChannel` copy — record whether it produced a torn copy; if it did not in N runs, say so
  (the fix stands on the locking argument, not on a flaky red).
- **Backup failure refuses import:** in the isolated files dir, create a *directory* named
  `ExpenseLogBackup.edb`; importing a valid candidate returns `BACKUP_FAILED` and the live rows are
  unchanged. Red: today it returns `OK` and replaces the rows.
- **Stale hot journal:** produce a real `-journal` for the live database (hold a write transaction on
  a second raw connection, copy its journal file, roll back), place it beside the database, import
  a different valid candidate; afterwards the rows equal the candidate's and `integrity_check` is
  `ok`. Red if achievable; record the outcome either way.
- **Import still works end to end** after the connection was in use (query before import, query
  after import sees the new data without restarting).
- `ExportStagingTest` and the local-backup tests keep passing against the real backup names.

### Emulator check — and re-run Phase 3's import checks

Fixture loaded. Save a record (local backups are written), pull
`/sdcard/Android/data/de.timowa.expenselog/files/`, and check every backup on the host with Python:
`sqlite3.connect(p).execute('pragma integrity_check').fetchone()` is `('ok',)` and the row count
matches the fixture plus one. Export an
`.edb` via Save, pull it, verify it the same way and compare the row count with the fixture.
Import the fixture through the real `ACTION_OPEN_DOCUMENT` picker and confirm the list updates.
Then run the relevant checks from the ten-check device pass in `docs/history/RECOVERY_PLAN.md`
(import of an invalid file, a newer-schema file, rollback) and record each result.

**Gate:** tests green, full suite green, lint 0 errors, device-pass subset recorded.

### Record

- **A design constraint the plan had not stated:** the snapshot must never *open* the database.
  Opening a corrupt file through `SQLiteOpenHelper` makes Android's default error handler delete
  it, and the import safety backup exists precisely for the database that may not open. So
  `snapshotDatabaseTo` takes its immediate transaction only on a shared connection that is
  **already open** (`DBAdapter.peekSharedConnection`, which never opens one) and otherwise copies
  the file as it is. `DatabaseSnapshotTest.aSnapshotOfACorruptDatabase_copiesItRatherThanDestroyingIt`
  pins it. `backupDbToSd` now returns `BACKED_UP` / `NOTHING_TO_BACK_UP` / `FAILED`, and only
  `FAILED` refuses the import, so an import onto a device with no database still works.
- **Red, against Step 4's code:**
  - *Backup failure:* with the backup path blocked by a directory, the import returned **`OK`**
    and replaced the records.
  - *Stale journal:* a journal captured mid-transaction did **not** reproduce at first (it was not
    yet synced, so SQLite had nothing to replay). Forcing a cache spill (`PRAGMA cache_size = 2`,
    1 500 wide rows) produced a synced, hot journal, and then the old import reported **`OK` while
    `PRAGMA integrity_check` on the result failed** ("Page 9 is never used", …) — a corrupted
    database behind a success message.
  - *Torn copies:* a temporary test (deleted afterwards) ran the old raw `FileChannel` copy in a loop
    while another thread inserted 3 000 rows: **3 of 6 436 copies were `SQLITE_CORRUPT`**. The same
    loop through `snapshotDatabaseTo` passed with every copy intact.
- **Change:** `snapshotDatabaseTo` (temp + `fsync` + rename, immediate transaction when a
  connection is open) used by the import safety backup, `stageDbForExport`, `LocalBackupManager`
  and all three Dropbox uploaders (which upload a snapshot from the cache and delete it after — the
  upload streams are now closed, one of F24's items). `replaceDatabaseFile` for import and restore:
  incoming bytes to a synced temp beside the database, close the shared connection, delete
  `-journal`/`-wal`/`-shm`, rename. `ImportResult.BACKUP_FAILED` with its own string.
- **Tests:** `ImportSafetyTest` (3) and `DatabaseSnapshotTest` (4). A first full run failed four
  import tests with `BACKUP_FAILED` — the blocked-backup test's cleanup could not delete its
  non-empty directory and it leaked into later tests; fixed with a recursive cleanup.
- **Emulator, fixture, de-DE:**
  - A save wrote `expenseLog.edb` and `expenseLogDailyBackup.edb`; both pulled, integrity `ok`,
    2545 rows; no `.tmp` left behind.
  - Settings → Import Database through the real picker: **`future.edb`** (user_version 2) and
    **`junk.edb`** left the live database at 2545 rows, v1, integrity `ok`; **the fixture copy**
    replaced it — 2544 rows, integrity `ok` — and `ExpenseLogBackup.edb` holds the pre-import 2545,
    integrity `ok`. No `.incoming` file left in `databases/`.
  - Settings → Export Database → Save into Downloads: the saved `.edb` is integrity `ok`, 2544 rows;
    `exports/` empty afterwards.
  - Crash buffer: no `FATAL`. Toast wording is covered by the unit tests; the emulator's view dump
    cannot see toasts.
  - Not re-run from the ten-check pass: checks 4–7 and 10 do not touch what this step changed, or
    need Dropbox (Step 6–7) or a phone.
- **Gate:** lint **0 errors / 237 warnings**, suite **161 tests, 0 failures, 0 errors, 0 skipped**.

---

## Step 6 — Dropbox task ordering and hardening (F7, F20, F24)

### Change

1. **Serial execution:** `Executors.newSingleThreadExecutor()` in `DropboxTask`, restoring
   `AsyncTask`'s default ordering. Comment why.
2. **No process crash from a task:** wrap `doInBackground` in `try/catch (RuntimeException)`; log,
   and post a new `protected void onUncaught(RuntimeException e)` to the main thread instead of
   `onPostExecute`. Default: no-op. `DropboxDownload` overrides it to dismiss its dialog and show
   the generic download error.
3. **Path lookups, not search (F20):** `DropboxSyncCheck` and `DropboxAutoBackups` use
   `files().getMetadata("/expenseLog.edb")` (and `/weekly.edb`, `/monthly.edb`), treating
   `GetMetadataErrorException` whose error is `path().isNotFound()` as "does not exist" and any
   non-`FileMetadata` result as a failure. Remove the `get(0)` debug log. Leave
   `DropboxGetBackups`' listing alone unless it is equally simple to switch to `listFolder`.
4. **Streams and dialog (F24):** try-with-resources for every upload `FileInputStream` and for
   `mFos`; if `mFos` cannot be opened, fail the task with an error message instead of passing
   `null` to the SDK; dismiss the `ProgressDialog` only if it is showing and its window is still
   attached (catch `IllegalArgumentException` as the last resort, with a comment).

### Tests

- New `DropboxTaskTest`: tasks submitted A (sleeps 300 ms) then B run in that order and B's
  `onPostExecute` follows A's; a task throwing `IllegalStateException` delivers `onUncaught` on the
  main looper and the test process survives. Red: ordering fails today with the cached pool
  (B finishes first).
- The metadata "not found" mapping is extracted to a small pure function and tested with a
  constructed `GetMetadataErrorException` if the SDK allows construction; otherwise record that it
  is covered only by the emulator check.

### Emulator check (Dropbox — needs the test account)

`dropbox.appKey` must be present in `local.properties` (it is gitignored; if missing, stop and ask).
Log in with Tim's throwaway test account — credentials are in Claude's memory, **never** in this
file or any tracked file. If Dropbox asks for a code, stop and ask Tim. Then: enable sync, save two
records within a few seconds, and confirm (via the conflict-free next sync and, if needed, a fresh
download on a wiped app) that the remote database contains both. Create a manual Dropbox backup and
list backups. Turn networking off (`adb shell svc wifi disable; svc data disable`), save a record,
confirm no crash and a sensible error toast; turn it back on.

**Gate:** tests green, full suite green, lint 0 errors; Dropbox checks recorded or explicitly owed.

### Record

- **Red:** `DropboxTaskTest.tasksFinishInTheOrderTheyWereStarted` with the executor temporarily put
  back to `newCachedThreadPool` finished **`[fast, started second, slow, started first]`**. The
  crash-path test uses the new `onUncaught` API, so it has no red run.
- **Change:** single-thread executor; `onUncaught` on the main thread instead of a process crash
  (the progress-dialog tasks override it to dismiss). `DropboxPaths.fileAt` (`getMetadata`, only a
  genuine `NOT_FOUND` counts as missing, a folder is an error) replaces `searchV2` in the sync check
  and the auto-backups. `DropboxGetBackups` lists the folder directly, skips non-files (the old
  cast threw on a folder), and reports "No Backups" on an empty list. `DropboxDialogs.dismiss` /
  `windowGone` guard the progress dialogs and the conflict dialog against a destroyed activity.
  `DropboxDownload` closes its stream (try-with-resources), no longer passes a null stream to the
  SDK, honours Cancel after the download, and deletes its temp file after the import. (The upload
  streams were closed in Step 5.)
- **Tests:** `DropboxTaskTest` (2), `DropboxPathsTest` (2, with constructed
  `GetMetadataErrorException`s).
- **Emulator, with the test account.** The login page rendered this time, and the webview still
  held the account's session from Phase 3, so the flow needed only *Zulassen* — no password, no
  code. Then:
  - The first sync found the old remote from 3 Sept and raised the **conflict dialog**
    (Local 14 Sept, newer / Remote 3 Sept); *Keep local* → *Yes* uploaded; both stored versions
    equal the new remote version.
  - **Two saves a few seconds apart** (SyncOne, SyncTwo) with sync on: afterwards both versions
    matched again, and restoring the newest remote database from *View existing Dropbox backups*
    produced a live database of **2546 rows containing SyncOne and SyncTwo**, integrity `ok` —
    the remote held both. (The list shows only timestamps, so which remote file was restored is
    inferred from its time, 19:52:51, the second save's upload; re-uploading identical content
    kept the same Dropbox version.)
  - *Create a manual backup on Dropbox* completed and appeared in the list.
  - **Offline** (`svc wifi/data disable`): a save completed, no crash, and
    `pref_key_current_dropbox_db_version` stayed `localChange` for the next sync. Networking
    re-enabled.
  - Crash buffer empty throughout.
- **Gate:** lint **0 errors / 237 warnings**, suite **165 tests, 0 failures, 0 errors, 0 skipped**.

---

## Step 7 — Dropbox never overwrites what it has not seen (F4, F6, F23, F25)

Narrow by design: whole-file handover stays exactly as it is. What changes is that a write to
`/expenseLog.edb` only succeeds when the remote is still the version this device last saw, and a
download never lands on changes the check did not know about. The existing
`SyncConflictDialog` is the only UI for every conflict.

### Change

1. **Two upload intents.** `DropboxUpload` takes an explicit mode:
   - *conditional* — the save path (`NewLogFragment`), the sync check's `KEY_UPLOAD` and
     `KEY_NO_REMOTE`: `WriteMode.update(lastKnownRev)` when a last-known version exists,
     `WriteMode.ADD` when none does (with `autorename(false)`), so a remote that changed or appeared
     meanwhile makes the upload fail instead of replacing it;
   - *forced* — only where the user explicitly chose to replace the remote: conflict dialog
     "keep local", and the upload that follows a restore: `WriteMode.OVERWRITE`.
   Put the choice in one pure function (`static WriteMode writeModeFor(String lastKnownRev, boolean
   forced)`) so it is testable.
2. **A conditional upload that hits a conflict** (`UploadErrorException` →
   `getErrorValue().isPath()` → `getReason().isConflict()`) does not update either stored version,
   and runs a sync check that bypasses `syncLimitCheck` — which, because the local marker is
   `localChange` and the last-known version no longer matches, lands in the existing conflict
   dialog. No new UI.
3. **Import marks a local change (F6):** on `ImportResult.OK`, `importFileAndVerify` sets
   `pref_key_current_dropbox_db_version` to `DBAdapter.KEY_DATABASE_CHANGE` (a preference write, not
   a presentation, so rule 3 holds). `DropboxDownload` of the main file still overwrites the marker
   with the downloaded version afterwards, as today.
4. **Restore does not adopt the backup's version (F23):** when `DropboxDownload` runs with the
   restore flag, it does **not** store `downloadMetaData.getRev()` in either preference, leaving the
   `localChange` marker from step 3. The follow-up forced upload then records the main file's new
   version; if that upload fails, the next sync proposes an upload or a conflict rather than a
   download over the restore.
5. **A sync download re-checks before overwriting (F25):** a download started by the sync check
   (not by the user choosing "keep remote", not a restore) first reads the marker again; if it is
   `localChange`, it aborts without downloading and runs a sync check, which then shows the conflict
   dialog.
6. Extract the sync check's decision (local marker, last-known version, remote version or absent,
   local record count → UPLOAD / DOWNLOAD / NOTHING / CONFLICT) into a pure function **without
   changing its behaviour**, so steps 3–5 can be tested around it.

### Tests — new `DropboxSyncRulesTest`

- `writeModeFor`: last-known present → `update(rev)`; absent → `ADD`; forced → `OVERWRITE`.
- Decision table: every existing branch of `DropboxSyncCheck` reproduced (a characterisation test
  written **before** the extraction, against the extracted function fed today's inputs).
- Import (isolated context and preferences): `OK` sets the marker to `localChange`; every refusal
  (`NOT_A_DATABASE`, `NEWER_SCHEMA`, `UNREADABLE`, `BACKUP_FAILED`) leaves it untouched.
- Download guard: with the marker at `localChange`, a sync-initiated download reports "aborted";
  with a version string, it proceeds; user-chosen and restore downloads proceed regardless.

**Red run:** `writeModeFor` does not exist today, so red is the characterisation of today's upload
(always `OVERWRITE`) and today's import leaving the marker unchanged — record both.

### Emulator check (test account; one emulator simulating two devices)

1. **Stale device saves (F4):** save a record so the remote is at version R1, then save another so
   it is at R2 — note both versions from the shared-prefs XML (`run-as … cat
   shared_prefs/de.timowa.expenselog_preferences.xml`). Force-stop, write R1 back into
   `pref_key_last_known_dropbox_rev` — the device now believes, like a second phone would, that the
   remote is still R1. Save a record. Expected: the upload is refused, the conflict dialog appears
   (`uiautomator dump`), and the remote is still R2. Run the same script on `main` first and record
   that it uploads silently.
2. **Import then sync (F6):** import the fixture `.edb` through the picker, trigger a sync from
   Settings: the import is uploaded (or the conflict dialog appears if the remote changed).
3. **Restore (F23):** restore a Dropbox backup from the backup list; confirm the main file is
   replaced and both preferences hold the main file's version, not the backup's.
4. **Save during a sync check (F25):** throttle with `adb shell svc wifi` toggling or simply save
   immediately after launch with a pending remote change; confirm the saved record survives and the
   conflict dialog is offered.

If the account login is blocked, record every check here as **owed**, with the scripts, and keep the
step's unit tests as the gate.

**Gate:** tests green, full suite green, lint 0 errors; Dropbox scenarios recorded or owed.

### Record

- **Red, unit:** `ImportSyncMarkerTest.aSuccessfulImport_marksTheDatabaseAsChangedLocally` against
  Step 6's code: marker still **`0123456789abcdef`** (the old remote version) after a successful
  import. `writeModeFor` did not exist, so its red is the on-device run below.
- **Change:** `DropboxSyncRules` (`decide`, `writeModeFor`, `isConflict`, `mayOverwriteLocal`,
  `mayRecordUploadAsCurrent`); the sync check calls `decide` (behaviour unchanged, pinned by a
  characterisation table). `DropboxUpload(…, forced)`: conditional `update(lastKnown)` / `ADD` for
  saves and sync-check uploads, `OVERWRITE` only for *keep local* and the post-restore upload; a
  refused upload records nothing and sends `KEY_DROPBOX_SYNC_NOW` (a new sync check without the
  20-second limit), which lands in the existing conflict dialog. An upload records its version as
  the marker only if the marker did not change while it ran (a delete during an upload used to be
  declared synced). A download the sync check started carries the marker it decided on and stands
  down — then re-checks — if that marker changed, before and after the transfer. A restore no
  longer stores the backup file's version. A successful import sets the marker to `localChange`.
- **Found while testing:** `WriteMode.update` validates the version string and throws
  `IllegalArgumentException` on a malformed one; a stored value that is not a real version now
  falls back to `ADD` (which can only create, never replace). Also: the plan's one `commit()` for the
  import marker was unnecessary — `apply()` updates the in-memory value at once and queues disk
  writes in order — and it added a lint warning (238), so it is `apply()`; re-verified in Step 8's
  gate.
- **Tests:** `DropboxSyncRulesTest` (16), `ImportSyncMarkerTest` (2).
- **Emulator, test account, identical script on `main` and the branch** (`install -r` keeps the
  database, preferences and credential; a second device is simulated by writing an old version into
  `last_known_dropbox_rev` while the app is stopped):
  - **F4 on `main`:** save → R2; last-known set back to R1; relaunch; save again → **uploaded
    silently over R2**, both preferences moved to the new version, no dialog. **On the branch:**
    the first save's conditional upload succeeded (R1 → R2); with last-known set back, the second
    save was **refused — the conflict dialog appeared** ("Local and remote records both changed…"),
    the marker stayed `localChange` and last-known stayed at the stale version: Dropbox was not
    touched.
  - **Keep remote** (a user-chosen download, which must proceed): the local database became R2 —
    it holds `fixedFirst` but not the refused `fixedSecond` — and both preferences equal R2.
  - **F6:** importing the fixture through the picker set the marker to `localChange`; the next
    launch's sync **uploaded it** (both preferences → a new version R4). On `main` the marker would
    have stayed R2, which the sync check reads as "nothing to do".
  - **F23:** restoring the 19:53:57 Dropbox backup (2546 records with SyncOne/SyncTwo) left both
    preferences at a new version R5 after the forced upload; relaunching ran a sync check that
    **changed nothing** and the database stayed at 2546 — which it would not have if the stored
    version were the backup file's, since that mismatch triggers a download.
  - **F25: unit tests only.** Saving a record between the sync check's decision and its download
    needs a timing window the emulator script cannot open reliably; recorded as owed rather than
    claimed.
  - Crash buffer empty.
- **Gate (before the `apply()` change):** lint **0 errors / 238 warnings**, suite **182 tests,
  0 failures, 0 errors, 0 skipped**.

---

## Step 8 — Calendar performance (F11, and a conditional index)

### Change

1. **No database in `getView`.** A day's total is income minus expenses of the `LogItem`s
   `CalendarDay` already holds (loaded with the same filter and the same — now correct — day
   bounds), so `CalendarAdapter.getView` sums in memory. Build the `PrefManager` once per adapter.
2. **One query per month, not 31.** `refreshDays` loads the whole month once with
   `getLogsInRange(monthStart, monthEnd, KEY_LOG_TIME, filter)` and buckets records into days by
   their local date; `notifyDataSetChanged` once, after the loop.
3. Move `refreshDays`' query off the main thread if it is not already (it is called from the
   fragment's `onPostExecute` today): load on a background thread, populate on the main thread.
4. **F26:** capture everything the background work needs (application context, preferences,
   period start) on the main thread *before* starting it, so `doInBackground` never touches the
   fragment; `onPostExecute` keeps its `isAdded()` guard. Emulator check: page the calendar 30×
   with ~150 ms between taps on the neighbouring tab and confirm the crash buffer stays empty.
5. **Index — only if measurement justifies it.** Write a throwaway timing test on an isolated
   database with 10 000 generated records measuring a month `getLogsInRange` and a category
   `getSumForRange`. If either exceeds ~4 ms on the emulator, add
   `CREATE INDEX IF NOT EXISTS idx_mainLogs_time ON mainLogs(start_time)` in `DatabaseHelper.onOpen`
   (and `onCreate`). **No `DATABASE_VERSION` bump:** an index is not a column, older builds open and
   import a file carrying it unchanged, and `inspectCandidate` only reads `sqlite_master` for
   `tagTypes`. Verify that claim by importing an indexed `.edb` in `NewerSchemaImportTest`-style
   test. If the numbers are small, record them and add nothing.

### Tests

- New `CalendarTotalsTest`: for a generated month with expenses, incomes, a filter, and records at
  both day edges, the in-memory day totals equal `getTotalForRange` for every day, and each day's
  event count equals `countLogsInRange`.

### Emulator check

Repeat Step 0's `gfxinfo` measurement on the same pages with the fixture; record janky frames and
90th percentile before and after. Visually compare a calendar month screenshot before/after — the
day totals must be identical.

**Gate:** tests green, full suite green, lint 0 errors, numbers recorded.

### Record

- **Change:** `CalendarAdapter.loadMonth` — one `getLogsInRange` for the month, bucketed into days,
  safe on a background thread; `CalendarDay.getTotal()` sums the day's records; `getView` no longer
  touches the database and uses one `PrefManager` per adapter. **F26:** `LogsCalendarFragment`
  captures the application context, preferences and month start on the main thread, loads the
  month in `doInBackground` without touching the fragment, and builds the adapter in
  `onPostExecute` behind `isAdded()`. `refreshDays` (after a delete in the day dialog) uses the same
  one-query load.
- **Index: not added.** A temporary timing test (deleted after) on isolated databases:

  | records | index | month list | month category total | day |
  | --- | --- | --- | --- | --- |
  | 2 544 | no | 1.20 ms | 0.60 ms | 0.36 ms |
  | 2 544 | yes | 0.84 ms | 0.54 ms | 0.21 ms |
  | 10 000 | no | 1.50 ms | 1.50 ms | 0.82 ms |
  | 10 000 | yes | 0.96 ms | 0.46 ms | 0.20 ms |

  Every query is well under the plan's 4 ms threshold without one, so nothing about the schema
  changed. What cost time was the *number* of queries and connection opens, which this step removes.
- **Tests:** `CalendarTotalsTest` — for March 2026 (DST on the 29th), records at day edges and in
  the neighbouring months' edges, four filters and three first-day-of-week settings: every day's
  record count equals `getLogsInRange(...).getCount()` and its total equals `getTotalForRange`.
- **Emulator, same fixture, same script on `main` and the branch** (clock at 15 May 2022; the
  headless emulator renders in software, so compare, don't read absolutely):

  | build | frames | janky | 50th pct | 90th pct | slow UI thread |
  | --- | --- | --- | --- | --- | --- |
  | `main` | 192 | 88.0 % | 93 ms | 250 ms | 115 |
  | branch | 204 | 86.3 % | 89 ms | 150 ms | 135 |

  A modest, honest improvement: the 90th percentile frame drops by 100 ms, the median barely moves,
  and software rendering dominates both. (Step 0's baseline — 1000 ms median — was taken in a
  different screen configuration before the locale restart and is not comparable.) The same run
  shows 1 May 2022 as **4 records, 1.771,51 €** on `main` and **5 records, 4.061,51 €** on the branch.
- **F26 on the emulator:** quick tab taps did not reproduce it, nor did 40 single swipes; bursts of
  25 fast swipes each way (`input swipe … 60`, three rounds) did — **`main` crashed** with
  `IllegalStateException: Fragment LogsCalendarFragment … not attached to an activity` and fell back
  to the launcher. **The branch survived the identical burst twice**, crash buffer empty.
- **Gate:** lint **0 errors / 237 warnings**, suite **183 tests, 0 failures, 0 errors, 0 skipped**
  — including Step 7's `ImportSyncMarkerTest` after its `apply()` change.

---

## Step 9 — Final pass and documentation

1. Full suite (record counts), lint (0 errors; explain any change in the warning count).
2. **Re-run the ten-check device pass** from `docs/history/RECOVERY_PLAN.md` on the emulator — it
   exists for changes to import, backup and reminder paths, and Steps 4–5 changed the first two.
3. Fixture regression: September 2026 = 15 records, 2553.94 income / 88763.76 expense (Berlin); May
   2022 = 40 records including 1 May 00:00:00.216 on list **and** calendar; screenshots compared
   with Step 0.
4. **`CLAUDE.md`:** add the new load-bearing rules —
   - the process has one shared connection; only import/restore may call
     `closeSharedConnection`, and `close()` is intentionally a no-op;
   - every byte copy of the live database goes through `snapshotDatabaseTo`;
   - repeat occurrences are computed from the original time, never stepped;
   - period ends are defined as the next period's start minus 1 ms;
   - uploads to `/expenseLog.edb` are conditional unless the user explicitly chose to replace the
     remote; a restore does not adopt a backup's version;
   - `DropboxTask` is serial on purpose.
   Update the test count, and the note on `FileHelper`'s rules if a sixth rule emerged.
5. **`PROGRESS.md`:** a section for this pass, in the style of the existing ones, including what is
   owed to Tim (below).
6. Leave this file at the repo root with every *Record* filled in. It moves to `docs/history/` when
   Tim merges.

### Record

- **Suite:** Step 8's gate is the final code — **183 tests, 0 failures, 0 errors, 0 skipped**; lint
  **0 errors / 237 warnings** (241 at the start: −3 for the deleted calendar `AsyncTask`s, −1 net
  elsewhere). The documentation edits after it touch no code.
- **Ten-check pass, re-run on the final build (emulator):**
  - **1** newer-schema `.edb` through the picker: refused, database unchanged (2545 rows, v1, `ok`).
  - **2** valid `.edb`: imported, 2544 rows, `ok`, `ExpenseLogBackup.edb` written.
  - **3** junk file: refused, database unchanged.
  - **4** suite isolation: with a real `ExpenseLogBackup.edb` in place, `installDebugAndroidTest` +
    `am instrument` → **`OK (183 tests)`**; the backup's md5 (`4e4d5c16…`) and the live database's
    (`1aa97e2b…`) identical before and after; nothing but `test_sandbox` in the app's cache.
    This matters more than before, because Step 4 made the connection process-wide.
  - **9** crash buffer: empty after all of it.
  - 5, 6, 8 and 10 exercise code this pass did not change (the removed `file://` path, notification
    permission, reminders, the FileProvider fallback); 7 (Dropbox failure surfaces as a message) was
    exercised in Step 6's offline save.
- **Fixture regression (Berlin):** September 2026 month list **2.553,94 € / −88.763,76 €** and May
  2022 **4.593,00 € / −1.355,99 €**, both equal to the host recomputation (15 and 40 records); May
  2022's calendar shows 1 May as 5 records (Step 8).
- **Docs:** `CLAUDE.md` gains the load-bearing rules this pass created — one shared connection and
  `close()` as a no-op; repeat occurrences from the original; period ends as the next start − 1 ms;
  the calendar's one-query background load; `FileHelper` rule 6 (snapshots and replacement); a
  serial `DropboxTask`; and conditional Dropbox uploads — plus the new test and lint counts.
  `PROGRESS.md` has a section for this pass, the counts, PR #8 marked merged, the resolved
  `SQLiteConnection` leak removed from *open*, and F13 added there.
- **Emulator left as found:** clock on automatic, locale back to en-US, files pushed to Downloads
  removed. The test Dropbox account now holds the fixture database and a few test backups.

### Owed to Tim

- **The Dropbox changes on two real phones**, the way he uses sync: finish on phone A and sync, open
  phone B and save straight away — no data lost either way; and once with B's view deliberately
  stale (B offline while A saves, then B saves online) — B should get the conflict dialog, not
  overwrite.
- **F25** is unit-tested only (a record saved between the sync check's decision and its download).
- **F18** did not reproduce as a crash; the German amount field turns `12,50` into `1250` instead.
  Visible before saving, but a locale-aware amount field may be worth it.
- **Whether to repair monthly series that already drifted** in his real data (see *Deferred*).

---

## Follow-up code review of the branch

A code review of the whole branch against `main` (2026-09-14, after the PR was opened) found four
bugs in this pass's own changes, all fixed in one commit:

1. **An upload still marked a mid-upload delete as synced.** `mayRecordUploadAsCurrent` compared
   the marker, but a save sets it to "changed" before its own upload starts, and a delete during
   that upload writes the same value — so before and after always matched. `DBAdapter` now keeps an
   in-process change counter (`changeGeneration`, bumped by every change and by an import) and the
   upload compares that too. `ChangeGenerationTest`, plus rule tests. A narrow window remains: an
   edit counted just before the upload reads the counter, whose write lands after the snapshot.
2. **A sync download could overwrite a record saved while it ran**, when the check had seen
   "changed" with zero records — the marker cannot change further. It now also requires the record
   count to still be zero.
3. **An import could leave the app writing to the replaced file.** Between closing the shared
   connection and the rename, another thread could reopen the *old* file; the rename unlinked it,
   and every later read and write — through any adapter — went there until a restart. **Red:** a new
   `ImportSafetyTest` case with a reader thread running during 20 imports failed in round 0
   (imported records invisible afterwards). `DBAdapter.replaceWhileClosed` now closes and swaps under
   the same lock `db()` opens under.
4. **A deleted remote file could make upload and sync check bounce without end.** A conditional
   upload naming a version that no longer exists is refused; the sync check then decides UPLOAD
   again. A refused conditional upload now retries once as `ADD` when nothing exists at the path.
   Not verified against Dropbox.

**Gate:** lint **0 errors / 237 warnings**, suite **187 tests, 0 failures, 0 errors, 0 skipped**.
**Emulator:** import through the picker re-run on the fixed build — newer-schema and junk refused
(2545 rows, `ok`), the fixture imported (2544, `ok`), the records screen showed the imported totals
without a restart, crash buffer empty.

A **second review** found two more:

5. **An import closed the connection under a transaction on another thread.** Deletes and repeating
   series hold one connection object for their whole transaction; `replaceWhileClosed` closed it
   regardless. The review judged a fix too invasive (waiting for them inside the open-lock
   deadlocks with `db()`). **Red:** a writer thread creating and deleting series and categories
   during 20 imports threw `IllegalStateException: attempt to re-open an already-closed object`
   from `deleteTag` — not narrow at all. Fixed with a read/write lock taken *outside* the open-lock:
   transactions and the snapshot hold it for reading, the replacement takes it for writing before
   it closes anything. `ImportSafetyTest.transactionsOnAnotherThreadDuringImports_areNeverCutOff`.
6. **An impossible repeating series froze the save for seconds.** A frequency of `00` passes the
   screen's `"0"` check, and a 55-year daily series is over the cap; both inserted 20 000 rows on
   the main thread before rolling back. `createRepeatingSeries` now refuses them up front by
   computing the 20 001st occurrence (occurrences only move forward). Same result as before, without
   the work; the existing ceiling tests cover it.

**Gate:** lint **0 errors / 237 warnings**, suite **188 tests, 0 failures, 0 errors, 0 skipped**.

A **third review** (2026-09-15) found two more, and applied neither, judging both too wide:

7. **An import killed background reads.** Ordinary queries took no lock, and Android cursors query
   lazily without keeping the connection open, so an import closing the connection between a query
   and its cursor's first read threw `IllegalStateException` on that thread — the records list's and
   calendar's background loads. **Red:** a reader thread iterating cursors during 20 imports failed
   with *"Cannot perform this operation because the connection pool has been closed"* from
   `SQLiteCursor.getCount`. Fixed inside `DBAdapter` alone: all 30 accesses go through `read(…)` /
   `write(…)`, which hold the read lock and fill a returned cursor before releasing it.
8. **A delete that waited behind a snapshot could still be declared synced.** The change counter
   moved before the write, so an upload reading it between the bump and the delayed write saw it
   unchanged. **Red:** `ChangeGenerationTest.aWriteHeldBackByASnapshot_movesTheGenerationAfterTheSnapshot`.
   `write(…)` and every transaction now also count a change after the write lands.

**Gate:** lint **0 errors / 237 warnings**, suite **190 tests, 0 failures, 0 errors, 0 skipped**.
**Emulator smoke test** with the fixture: a save, the calendar, the summary, and an import through
the picker all behaved (totals moved by the saved 9.99, then back to the fixture's after the import);
crash buffer empty.

A **fourth review** found one:

9. **A huge repeat frequency could insert a record millions of years in the past.** The day count
   was `int` arithmetic, so a weekly frequency above ~306 million wrapped negative, below any end
   time. The review moved it to `long`; that still left yearly frequencies past ~292 million, which
   overflow the calendar's milliseconds instead. `repeatingOccurrence` now also treats any
   occurrence that is not after the original as past the end. **Red** on the committed code: daily
   400 000 000 × 20 001 came back as −97 118 092 248 808 000.
   `RepeatingScheduleTest.aFrequencyTooLargeForTheCalendar_isPastAnyEnd_neverInThePast` covers every
   period at three huge frequencies and checks no series row is written.

**Gate:** lint **0 errors / 237 warnings**, suite **191 tests, 0 failures, 0 errors, 0 skipped**.

## Dropbox re-test on the final code (2026-09-15)

Steps 6–7 were checked against the throwaway account before four rounds of review changed the upload,
download and database-locking code, so every Dropbox scenario was run again on `2aecb18`, on the
`Pixel_6` emulator with the test database. The login needed only the authorisation tap (the webview
kept the account's session). **The emulator's `network speed`/`delay` throttling had no visible effect
on this traffic**, so the two timing windows were opened a different way: an 80 MB padding table
added to the database (never read by the app) makes an upload or download take two to five minutes.

| # | Scenario | Result |
| --- | --- | --- |
| A | Login, first sync against an existing remote, *keep local* | Conflict dialog; upload; both versions equal the new remote |
| B | Two saves seconds apart | Both uploaded in order; versions synced |
| F | **A delete while a save's upload is running** (first review, finding 1) | Upload confirmed still running at the delete; afterwards last-known moved to the new version but **the marker stayed `localChange`** — the delete was not declared synced |
| H | **A save while a sync-check download is running** (F25 + second review) | Download dialog dismissed with Back, record saved at 45 s; the download **stood down** after the transfer, the conflict dialog appeared, the record is still there. **Found a bug: two identical conflict dialogs stacked** |
| C | A stale device saves | Upload refused; exactly one conflict dialog; Dropbox untouched |
| D | *Keep remote* | Local became R2 — has the accepted save, not the refused one |
| E | Import through the picker, then sync | Marker → `localChange`; next launch uploaded it |
| G | **The main file deleted on Dropbox** (first review, finding 4) | Recreated by the next sync; state stable for a minute, across a relaunch and after a further save — no loop. Whether Dropbox accepted the conditional write or the `ADD` retry did it is not visible without debug logging |
| — | Restore a backup; offline save | Main file's version recorded, stable after relaunch; offline save kept `localChange` and uploaded once back online |

Crash buffer empty throughout.

**The bug:** after H, the download's stand-down re-check and the save's refused upload each ran a
sync check, and each opened a conflict dialog — answering *keep local* on one and *keep remote* on
the other would have uploaded and downloaded over each other. `SyncConflictDialog.show` now does
nothing while one is already showing (`SyncConflictDialogTest.aSecondConflictWhileOneIsShowing_…`).
**Re-run of H on the fixed build: one dialog**, record kept. The test account was left holding the
small test database again.

**Gate:** lint **0 errors / 237 warnings**, suite **192 tests, 0 failures, 0 errors, 0 skipped**.

## The phone checklist, run on two emulators (2026-09-15)

The on-phone test list handed to Tim was run end to end on `d1a5e11`'s build with no human steps, on
two API 33 emulators: the existing `Pixel_6` (A) and a new `Pixel_6_B` (B, 2 GB, headless, created for
this) — both on the test database, both logged into the throwaway Dropbox account. B had no browser
session, so its login was driven through the webview: the email and password typed one character at a
time (a fast `input text` dropped characters), no confirmation code was asked for. The branch APK was
checked to contain the final fixes before starting, and `main`'s APK was rebuilt for the upgrade test.

| # | Test | Result |
| --- | --- | --- |
| 1 | Upgrade over existing data: `main` with the test data, pages captured, branch installed over it with `install -r`, same pages captured | ✅ identical: All Records totals and first row, this and last month, this week, month summary, calendar cells; 2544 rows, integrity `ok` |
| 2 | Screens under real data | ✅ Sep 2026 — all 30 calendar cells equal an independent host computation; the List's 3 Sep page equals its cell (−12,221.00); May 2022 — all 31 cells equal, 1 May 5 records / 4,061.51; graph opens; three rounds of 50 fast swipes stayed on the calendar with no crash |
| 3 | Repeating records through the UI | ✅ weekly from 21 Dec 2026: 53 rows, every gap 7 days across New Year, saved in under 2 s; monthly from 31 Jan 2027: 28 Feb, 31 Mar, 30 Apr…; frequency `00`: refused, nothing written; *Delete all* removed exactly the weekly series |
| 4 | Export and import | ✅ exported `.edb` holds all 2557 rows, integrity `ok`; importing it back restored that state and dropped a later record; importing a PNG was refused, data untouched |
| 5 | CSV export | ✅ UTF-8 BOM; September's 15 records match the database exactly, plus the Total line (−86,209.82 = the month's net) |
| opt | Suite doesn't touch the real backup | ✅ `am instrument`: `OK (192 tests)`; `ExpenseLogBackup.edb` and the live database md5 identical before and after |
| 6 | Normal handover | ✅ A's record reached B, B's reached A, no conflict dialog, same version on both |
| 7 | Save before the sync | ✅ A uploaded; B, which had not seen it, saved → upload refused, **one** conflict dialog, Dropbox still A's version, B kept its record. (Pure UI timing could not beat the 20-second sync limit — a first attempt synced normally and lost nothing — so B's last-sync time was set ahead to skip its launch check; A's upload and B's staleness were real) |
| 8 | Offline phone | ✅ B offline while A uploaded; B's offline save stayed `localChange`; back online, **one** conflict dialog, A's upload intact. *Keep local* on B then uploaded, and A received B's version without a prompt |
| 9 | Save during a download | ✅ A uploaded an 80 MB padded database; B saved 61 s into downloading it → download stood down, **one** conflict dialog, B's record kept, A's database not applied |
| 10 | Restore from Dropbox | ✅ A restored the 14 Sep backup (2546 rows, SyncOne/SyncTwo); it became the main file and B received exactly that, no conflict |
| 11 | Crash check | ✅ crash buffer empty on both emulators after every test |

Two things were found and are **not** app bugs: the export's extra CSV line is its Total row; and the
emulator's `network speed/delay` throttling does not slow this traffic (padding the database is what
opened the timing windows). What remains outside an emulator: Tim's real 2540 records, real phones, and
real mobile-network timing.

## Deferred, with reasons

- **F13 — pager rebuilt on every resume.** It is wasteful, but it is also the only thing refreshing
  the Records pages after Settings changes (currency format, first day of week) or a Dropbox
  download. Removing it needs an explicit refresh for each of those, which is a behaviour change
  wider than the finding. Measure it in Step 8's `gfxinfo` run and propose separately if it matters.
- **Repairing already-drifted repeating series.** Step 1 fixes new series only. Existing rows are
  the user's data; moving their dates would be a silent edit of financial records. Needs Tim's
  decision.
- **Moving `.edb` import off the main thread.** Allowed by `CLAUDE.md` rule 1, but not a finding
  of this review; separate piece of work.
- **Record-level sync, merging, or a Room migration.** Explicitly out of scope — see rule 7.
- **Predictive back / `androidx.activity` upgrade** — tracked in `CLAUDE.md`, unrelated.
