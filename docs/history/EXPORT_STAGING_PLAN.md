# Export staging — stop a database export deleting the current local backup

Branch `export-staging`, from `main` at `da976b7` (PR #7 merged). Raised by the code review posted
on PR #7; the bug itself arrived with PR #6.

## The bug, as verified

A database export is staged at the same path as `LocalBackupManager`'s "current" backup, and since
PR #6 the staged path is deleted after use.

- `FileHelper.stageDbForExport` calls `fileSetup(baseContext, externalPath)`. `externalPath` is
  `"Expense Log/expenseLog.edb"`, and `fileSetup` keeps only the name, so the staged file is
  **`getAppFilesDir()/expenseLog.edb`**.
- `LocalBackupManager.CURRENT_BACKUP_NAME` is `"expenseLog" + ".edb"` in the same directory.
  `checkBackups` rewrites it after every record save (`NewLogFragment.java:777`).
- `ec7f87a` (PR #6) records that path as the pending export and deletes it:
  - **Save** — `copyStagedFileTo` deletes it once the copy succeeds.
  - **Cancel** — `discardPendingExport` deletes it when the picker comes back empty.
  - **Share** — the path stays recorded, and the **next export of either kind** deletes it, however
    many saves have refreshed that backup in between. A CSV export days later is enough.

The current backup is then missing until the next record save. The daily, weekly and monthly copies
and `ExpenseLogBackup.edb` are not affected. Before PR #6 the export only *overwrote* that file with
the same bytes, which was harmless.

## Why nothing caught it

`discardPendingExport`'s javadoc and `ExportDestinationTest.discardPendingExport_touchesOnlyTheFileItRecorded`
both state the right rule — cleanup deletes only the one path the app recorded, never a sibling. The
rule holds. What was never checked is its **precondition: that the recorded path is not also a file
the app keeps.** The test stages a file of its own under a test name in `getFilesDir()`, so the real
staging path was never exercised against the real backup names.

So the fix has two halves: give staged exports a place of their own, and make cleanup refuse to
delete anything outside it, so the precondition is enforced rather than assumed.

## Decisions

| | Recommended | Alternatives |
| --- | --- | --- |
| **Where exports are staged** | **`getAppFilesDir()/exports/`**, one subfolder, both `.edb` and CSV | Dated name in the same directory (what the review suggested): fixes this collision, but staging and backups still share a folder, so the next name clash is one constant away. `getCacheDir()`: the system may evict it while a Save picker is open, and it needs a new `<cache-path>` in `provider_paths.xml`. |
| **The staged `.edb`'s name** | **`exportDbFileName`** (`Expense Log 2026-09-14.edb`) | Keep `expenseLog.edb` inside `exports/`. The dated name also fixes what a Share target sees: today its content URI's display name is `expenseLog.edb`, and only the subject carries the date. |
| **Cleanup guard** | **Delete only files whose parent is `exports/`**, in both `discardPendingExport` and `copyStagedFileTo`; anything else is logged and forgotten | Rely on the new location alone. The guard also covers a path recorded by the current build before upgrading, which still points at `expenseLog.edb` — the first export after the update would otherwise delete the backup one last time. |
| **Old CSVs left in the files root** | **Leave them** — small, one per range, and already listed under What is open | Delete `*.csv` from the files root once. It is a name-pattern deletion in the backup directory, which is exactly the pattern the cleanup rule exists to avoid. |

`provider_paths.xml` needs no change: `<external-files-path path=".">` and `<files-path path=".">`
already cover subdirectories. `FileProviderPathsTest` is extended to prove it, not to change it.

## Steps

Each step is one commit (Step 1 rides with Step 2). Build and `:mobile:lintDebug` after each; lint must stay at 0 errors.

### Step 1 — Prove the bug with a failing test

`ExportStagingTest` (new, `IsolatedDatabaseContext` throughout — the backups live in the directory
it redirects):

1. Create a database in the isolated context and run `LocalBackupManager.checkBackups` so
   `expenseLog.edb` exists as the current backup.
2. `stageDbForExport`, `rememberPendingExport`, `discardPendingExport`.
3. Assert the current backup still exists, with its original length.

It **must fail on `main`**. A test that has never been seen to fail proves nothing; Phase 3 had one
that passed against the very bug it was written for. Run it on the emulator against unchanged
production code and record the failure message in this plan. It is committed together with Step 2,
so no commit on the branch has a red suite.

**Observed 2026-09-14**, `Pixel_6` API 33, production code at `235d365`, the test run on its own:
`ExportStagingTest > discardingAnExport_leavesTheCurrentBackupAlone FAILED —
java.lang.AssertionError: discarding an export deleted LocalBackupManager's current backup`.
`checkBackups` had written the file (the precondition assertion before it passed); the export's
cleanup is what removed it.

`checkBackups` raises a `Toast` if a copy fails, which throws on the test thread. Call it on the
main thread (`InstrumentationRegistry.getInstrumentation().runOnMainSync`), or a copy failure shows
up as a confusing crash instead of an assertion.

### Step 2 — A staging directory of its own

- `FileHelper.getExportStagingDir(Context)`: `new File(getAppFilesDir(context), "exports")`,
  created on demand. Like the other path getters it must not open the database (rule 2 in
  `CLAUDE.md`).
- `stageDbForExport` writes to `getExportStagingDir()/exportDbFileName()` and stops going through
  `fileSetup` and the static `externalFile`. `internalFile` comes from `getInternalDbFile`.
- `SpreadsheetHelper.createSpreadsheet` writes the CSV into `getExportStagingDir()` instead of
  `FileHelper.externalFileFolder`; its `fileSetup(externalPath)` call existed only to set that
  folder and goes.
- `externalPath` is then unused and is deleted.
- Step 1's test passes. Add: the staged `.edb` is inside `exports/` and its name is none of
  `LocalBackupManager`'s four names or `ExpenseLogBackup.edb`.

### Step 3 — Cleanup refuses anything outside `exports/`

- A private `isStagedExport(Context, File)`: canonical parent equals the canonical staging dir.
- `discardPendingExport` and `copyStagedFileTo` delete only when it returns true; otherwise
  `Log.w` and leave the file. `copyStagedFileTo` still reports the copy as a success.
- Tests:
  - A recorded path at `getAppFilesDir()/expenseLog.edb` (the pre-upgrade case) survives
    `discardPendingExport` and is forgotten.
  - The same file passed to `copyStagedFileTo` is copied and survives.
  - `ExportDestinationTest`'s existing staging file moves from `getFilesDir()` into the isolated
    staging dir, so its delete-after-copy and delete-on-discard assertions keep meaning what they
    say. `discardPendingExport_touchesOnlyTheFileItRecorded` keeps its sibling, now inside
    `exports/`.
- `FileProviderPathsTest`: a file in `exports/` gets a `content://` URI under both roots.

### Step 4 — Say it where the next person will read it

- `discardPendingExport` javadoc: replace "cannot reach a file the app did not itself stage" with
  the two conditions that actually make that true — a recorded path, inside `exports/`.
- `exportDbFileName` javadoc: it no longer describes a staged file named `DATABASE_NAME`.
- `CLAUDE.md`, the `FileHelper` rules: add **rule 5 — exports are staged only in `exports/`, and
  cleanup deletes nothing outside it**, bought with this bug, pointing at `ExportStagingTest`.
- `PROGRESS.md`: a short section for this branch, and the stale-CSV item under What is open
  updated to say old leftovers in the files root are no longer overwritten either.

## Verification

1. **Full suite** — `:mobile:connectedDebugAndroidTest`, 0 failures, 0 skipped; the count rises
   from 116 by the new tests.
2. **Emulator, with the realistic test `.edb` loaded.** Before each check,
   `adb shell ls -l /sdcard/Android/data/de.timowa.expenselog/files /sdcard/Android/data/de.timowa.expenselog/files/exports`:
   - Save a record, so `expenseLog.edb` is fresh. Note its size and time.
   - Export Database → **Save**, pick a destination. `expenseLog.edb` unchanged; `exports/` empty.
   - Export Database → **Cancel** the picker. Same.
   - Export Database → **Share** (AOSP: "No apps can perform this action", press back).
     `exports/Expense Log <date>.edb` present; `expenseLog.edb` unchanged.
   - Then export a **CSV** with Save. The shared `.edb` in `exports/` is gone; **`expenseLog.edb`
     is still there** — this is the reported scenario, and on `main` it deletes the backup.
   - The saved `.edb` imports back and shows 2544 records; the saved CSV opens with its BOM.
3. **The pre-upgrade case.** On `main`'s build, Share an `.edb` so the old path is recorded; install
   this branch over it; export a CSV. `expenseLog.edb` survives.
4. **The Recovery device pass** (`docs/history/RECOVERY_PLAN.md`), the backup and import rows at
   least: this touches the directory those paths share.

## Ranked failure points

1. **Step 1 passing on `main`.** Then the test does not reproduce the bug — most likely
   `checkBackups` writing somewhere the isolated context does not redirect — and Steps 2–3 would be
   verified by nothing. Stop and fix the test.
2. **Canonical-path comparison in the guard.** `getExternalFilesDir` can come back through a
   symlinked `/sdcard` on some devices; comparing raw `getAbsolutePath()` strings could refuse every
   legitimate delete and strand every staged file. Compare canonical paths, and the emulator check
   in 2 shows `exports/` emptying.
3. **Share still works from a subfolder.** Pinned by `FileProviderPathsTest` in Step 3 and
   checked on the emulator in 2.

## Results — 2026-09-14, `Pixel_6` emulator (API 33), 2544-record test database

1. **Full suite**: 122 tests in 19 classes, 0 failures, 0 errors, 0 skipped (116 + 5 in
   `ExportStagingTest` + 1 in `FileProviderPathsTest`). Lint 0 errors, 241 warnings, unchanged.
2. **Emulator.** One record saved first (so 2545 from here on), `expenseLog.edb` 131072 B at 15:53:37.
   - Save: the staged file appeared as `exports/Expense Log 2026-09-14.edb` while the picker was
     open, reached Downloads, and `exports/` emptied — the canonical-path guard strands nothing.
   - Cancel: `exports/` empty. Share: the dated `.edb` stays in `exports/`.
   - CSV Save after that Share: the shared `.edb` was cleared, **`expenseLog.edb` still there**,
     unchanged in size and time throughout.
   - The saved `.edb` imported back through the picker: 2545 records live, `ExpenseLogBackup.edb`
     written. The saved CSV starts with the BOM and has a header and 2545 rows.
3. **Pre-upgrade case**, run for real: `235d365` built in a temporary worktree and installed; Share
   recorded `pref_pending_export_path` = `…/files/expenseLog.edb` (and overwrote the backup with the
   same bytes, as it always did). This branch installed over it; the next CSV export logged
   `not deleting …/files/expenseLog.edb: it is outside the export staging folder` and the file
   survived.
4. **Recovery device pass**: not run as a ten-check pass. Its backup and import rows are covered by
   2 above (backups intact through every export, an import round trip with its rollback copy); the
   reminder rows are untouched by this change.
