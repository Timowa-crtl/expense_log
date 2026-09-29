# Expense Log — Phase 3: Recovery

> **Status: complete and merged.** Opened off `main` at `f05eaf6`, merged as PR #3 (`6af3bd4`) on
> 2026-09-05. This document is the record of it.
>
> **All nine findings in `docs/history/REVIEW_FINDINGS.md` are closed.** Steps 1–7 are done, the suite is at
> **49 tests, 0 failures, 0 errors, 0 skipped**, and lint is at 0 errors.
>
> **The device pass is complete.** Every check passed on a **Pixel 4a (Android 14)** and a
> **Unihertz Jelly Star (Android 13)**, against the maintainer's own **2540 records** — including
> both data-loss findings, which are now closed against the data they threatened.
>
> It merged on the same standard of evidence Phase 2 did: a two-phone record, not a green suite.
>
> Running the checks rather than only writing them turned up two things reading the code had not:
> the `file://` import branch is unreachable under scoped storage whatever `openImportSource` does,
> because the app holds no storage permission — deleted in **Step 7**, which is why there is a
> seventh step; and a `SQLiteConnection` leak warning, recorded under *Open items*.
>
> **What this branch is.** It closes all nine findings in `docs/history/REVIEW_FINDINGS.md`, the code review of
> PR #2. Phase 2 was merged as tested rather than edited, on the reasoning that a branch carrying a
> two-phone verification record should not be disturbed by a round of unverified fixes. This is the
> branch where that debt is paid.
>
> | Step | Findings | State |
> | --- | --- | --- |
> | 1 — isolate the test suite's filesystem | 2 | **Done** — red proved, 37 tests green |
> | 2 — the unopenable database | 1 | **Done** — red proved, 41 tests green |
> | 3 — import reports a reason, not a boolean | 6, and finding 1's silent refusal | **Done** — 42 tests green |
> | 4 — the two dead file paths | 3, 4 | **Done** — 44 tests green |
> | 5 — ask for notifications once | 5, 8 | **Done** — 47 tests green |
> | 6 — cheap cleanups | 7, 9 | **Done** — 49 tests green |
> | 7 — delete the dead `file://` path | — | **Done** — added after the emulator pass |
> | Final device pass | — | **Owed** — the only thing left |

## The goal

**No path through import, restore, or the test suite can leave the user without a readable database
or an intact backup — and every failure that does happen says which failure it was.**

That is one sentence with two halves, and the second half is not decoration. Both critical findings
reached a device precisely because the failing path was silent: an empty `else` in
`restoreBackupDbFromSd`, and a `return false` from `importFileAndVerify` that means five different
things. Fixing the data loss without fixing the silence leaves the next one just as hard to see.

The branch is done when `docs/history/REVIEW_FINDINGS.md` has no open items, and each of findings 1–4 has an
instrumentation test that fails against the code as it stands today.

## Why the order is what it is

**Step 1 is first, and it is not negotiable.** Finding 2 is that `IsolatedDatabaseContext` does not
override `getExternalFilesDir`, so `EdbRoundTripTest` — which calls `importFileAndVerify` twice —
lets `backupDbToSd` write through to the real `Android/data/de.timowa.expenselog/files/` and
overwrite the maintainer's actual `ExpenseLogBackup.edb`. Every other step in this plan is verified
by running the instrumentation suite on a phone. So until the suite is sandboxed, **testing the
fixes is itself the thing most likely to destroy the data the fixes exist to protect.**

Step 2 comes second because it is the one that bricks the app. Steps 3–6 descend by severity, which
is the order `docs/history/REVIEW_FINDINGS.md` already ranks them in.

## The two critical findings are one bug wearing two hats

Worth stating before the steps, because it explains why Step 2's fix has three parts rather than
one. Re-read from the source on 2026-09-05:

`getInternalDbFile` (`FileHelper.java:274`) needs a *path*. On API 28+ it pays for one by
constructing a `DBAdapter`, calling `getDB()`, disabling WAL, reading `database.getPath()` and
closing. That is a working database open, performed to learn a string that
`context.getDatabasePath(DATABASE_NAME)` returns for free without touching the file.

Which means `restoreBackupDbFromSd` → `fileSetup` → `getInternalDbFile` **cannot restore a database
that will not open**. That is the only situation a restore is ever called for. The rollback in
`importFileAndVerify` re-throws inside the recovery it was supposed to perform, `fileSetup` swallows
it into an "error 87" toast, `restoreBackupDbFromSd`'s `else` branch is empty, and the app is left
with an unreadable live database and an untouched backup sitting on disk beside it.

Combine that with finding 2 and the failure is complete: the user runs the tests, the backup is
overwritten with test data, and then the one path that would have restored it does not work anyway.

## Step 1 — Isolate the test suite's filesystem

`IsolatedDatabaseContext` overrides `getDatabasePath`, both `openOrCreateDatabase` forms,
`deleteDatabase` and `getSharedPreferences`. It does not override `getExternalFilesDir` or
`getFilesDir`, which is what `FileHelper.getAppFilesDir` resolves through.

- Override both, returning directories under the test's own cache.
- Rewrite the class javadoc to state the actual rule: **every filesystem accessor `FileHelper` can
  reach has to be isolated, not just the database ones.** The current doc implies the database is
  the whole risk surface, which is how this was missed.
- Add an assertion to the suite that the real app files directory is untouched — the cheapest
  guard against the next accessor someone forgets.

**Device check:** the sentinel-file check this step was planned with does not work, and the reason
is worth recording. Gradle uninstalls the app at the end of `connectedDebugAndroidTest`, which
deletes `Android/data/de.timowa.expenselog/` with it — so a sentinel placed beforehand is gone
afterwards whether isolation holds or not, and an `adb shell ls` of that directory comes back empty
either way. The check cannot tell success from the uninstall.

What replaces it is stronger: `FilesystemIsolationTest` snapshots the real directory *inside the
test process*, before and after a full import, and asserts it is unchanged. That runs on every
suite execution rather than once by hand, and it names the escaping files when it fails.

### ✅ Step 1 — done 2026-09-05

Red first, on the `Pixel_6` emulator, with the harness reverted to its `f05eaf6` state — all three
new tests failed, and the middle one caught the bug in the act:

```
the real app files dir must be exactly what it was
expected: {expenseLog.edb=32768@…, expenseLogDailyBackup.edb=32768@…}
but was:  {ExpenseLogBackup.edb=32768@…, expenseLog.edb=32768@…,
           expenseLogDailyBackup.edb=32768@…, isolation.edb=32768@…}
```

`ExpenseLogBackup.edb` written into the real directory by a test run — finding 2, reproduced rather
than argued. With the fix: **37 tests, 0 failures, 0 errors, 0 skipped** (the existing 34 plus these
three).

The test is deliberately written against the raw application context rather than against helper
methods on the wrapper, so the same source compiles and runs against both the fixed and the unfixed
harness. A regression test that only compiles after its fix cannot prove the fix did anything.

`getCacheDir` was redirected as well as `getExternalFilesDir` and `getFilesDir` — it is where
`importFileAndVerify` stages its candidate, so it is a write path even though nothing user-visible
lands there.

**Found while doing it, and it changes Step 2:** `DropBoxHelper` uploads the `File` that
`getInternalDbFile` returns, at five call sites (`:214`, `:255`, `:274`, `:307`, `:343`), and
`DropboxSyncCheck:188` reads its `lastModified()`. So the `disableWriteAheadLogging()` side effect
that Step 2 plans to delete has been silently guaranteeing those uploads are checkpointed. Ranked
failure point 2 is now confirmed rather than suspected: the checkpoint has to move to the upload
paths explicitly, not simply disappear with the path getter.

## Step 2 — The unopenable database

Three parts, all of them wanted. Any one alone leaves a hole.

1. **`getInternalDbFile` stops opening the database.** Use `context.getDatabasePath(DATABASE_NAME)`
   on both branches. Check first whether anything depended on the WAL-disabling side effect — the
   Dropbox upload path reads the file directly, so if WAL is on, `-wal` and `-shm` siblings exist
   and a copied `.db` alone may be stale. If it does matter, disable WAL somewhere it is honest
   about doing so, not inside a path getter.
2. **Reject a newer-schema file up front.** `isValidExpenseLogDatabase` already opens the staged
   candidate read-only and asks `sqlite_master` for `tagTypes`. Read `PRAGMA user_version` on that
   same probe and refuse anything above `DATABASE_VERSION` **before** `importProvidedFile`
   overwrites anything. Rejecting up front beats rolling back correctly.
3. **Fill the empty `else`** in `restoreBackupDbFromSd` (`FileHelper.java:181`). If the restore
   cannot run, that has to be logged and surfaced — via Step 3's result type — not skipped in
   silence.

**Instrumentation test:** craft an `.edb` whose `user_version` exceeds `DATABASE_VERSION`, import it
through `importFileAndVerify`, assert the refusal, assert the live database still opens, and assert
its records are unchanged. This test must fail against today's code — confirm that before fixing.

**Device check:** export an `.edb` from the phone, raise its `user_version` with `sqlite3`, import
it. Expect a refusal naming the reason, records intact, and the app still opening after a restart.

### ✅ Step 2 — done 2026-09-05

Red first again, with the source reverted to its Step 1 state. Two of the four new tests failed, and
between them they describe the whole finding:

```
importingANewerSchema_isRefusedAndChangesNothing
  java.lang.NullPointerException: Can't toast on a thread that has not called Looper.prepare()

internalDbFile_resolvesWithoutTouchingTheDatabase
  resolving a path must not delete and recreate the database  expected:<23> but was:<32768>
```

The first is findings 1 and 6 in one trace: the newer-schema file overwrote the live database, the
rollback threw inside `getInternalDbFile`, `fileSetup` caught it and tried to toast from a thread
with no Looper. The second is the path getter deleting a 23-byte corrupt file and standing a fresh
32KB database up in its place — Android's `DefaultDatabaseErrorHandler` doing exactly what it
documents, invoked by a method whose entire job is to return a string.

**41 tests, 0 failures, 0 errors, 0 skipped. Lint 0 errors.**

**A weak test caught before it was trusted.** `internalDbFile_…` passed against the unfixed code on
its first run. It compared the file's size after the call against the same file's size, also after
the call — the old getter deleted and recreated the file, so both sides moved together and the
assertion held while the bug was live. Recording the size *before* the call is what makes it fail,
and it states the real requirement rather than an approximation of it: resolving a path must not
touch the file. A green regression test is worth nothing until it has been seen red for the right
reason.

**The WAL risk was smaller than ranked, and is now pinned rather than assumed.** `DBAdapter`'s
constructor *and* its `onOpen` both call `disableWriteAheadLogging()`, and the journal mode lives in
the file header rather than in a connection — so the database is never in WAL mode for the Dropbox
paths to copy. `walIsDisabled_soUploadsCannotMissRecentWrites` asserts both the pragma and the
absence of a `-wal` sidecar, so a future change that re-enables WAL fails there instead of silently
uploading an incomplete database. Ranked failure point 2 is retired.

**Two silent branches, not one.** `restoreBackupDbFromSd` had an empty `catch` as well as the empty
`else` the review found. Both now log, and the method returns whether the restore actually happened;
both call sites in `importFileAndVerify` log loudly when a failed import is followed by a failed
rollback. The user-facing half of that message waits for Step 3, which is the next commit on this
branch.

**`getInternalDbFolder` had the identical trap** and got the identical fix — the review found the
file getter, not its sibling. Removing the API-level branch from both also closed a hole in the test
harness: the sub-28 path built the database location as a string from
`Environment.getDataDirectory()`, which no `ContextWrapper` can redirect.

## Step 3 — Import reports a reason, not a boolean

Finding 6 is that `importFileAndVerify` toasts its failures, and `DropboxDownload.doInBackground`
calls it from a `DropboxTask` pool thread with no Looper, so the toast throws instead of appearing.
This predates the Step 6 rewrite — `AsyncTask`'s pool had the same constraint.

`FileHelper` should not toast at all. Return a result and let the caller, which knows what thread it
is on, present it:

```
OK
NOT_A_DATABASE
NEWER_SCHEMA     <- Step 2's refusal, which today is a silent `return false`
UNREADABLE
RESTORE_FAILED   <- Step 2's filled-in else
```

Call sites, all of them:

| Where | Thread | Presents how |
| --- | --- | --- |
| `MainActivity.java:220` / `:223` | main | Toast, as today, but with the specific message |
| `FileHelper.java:135` | main | Toast |
| `DropboxDownload.java:145` | pool | Carry the result to `onPostExecute` and toast there |
| `EdbRoundTripTest.java:105` / `:143` | test | Assert on the specific result, not on `false` |

The two tests get sharper for free: `import_ofAnInvalidFile_isRejected` currently cannot tell a
rejected junk file from a failed read.

Keep the existing `R.string` messages where they fit; add strings for the new distinctions. The
numbered error codes (87, 131, 288, 333) stay as they are — they are the vocabulary the maintainer
already recognises, and renumbering them helps nobody.

**Device check:** airplane mode mid-download from Dropbox, and a corrupted remote `.edb`. Both
should produce a message on screen rather than a crash or nothing at all.

### ✅ Step 3 — done 2026-09-05

`ImportResult` has six values, not the five sketched above. `ROLLED_BACK` and `RESTORE_FAILED` were
one value in the plan, and they should not be: an import that did not happen and a database the user
must now act on are different messages and different situations. Collapsing them is a smaller
version of the mistake this whole step exists to undo.

**Four toast sites had to go, not one.** The review named `importFileAndVerify`, but the reachability
is what matters, and three more methods are reachable from a `DropboxTask` pool thread through it:

| Method | Was | Now |
| --- | --- | --- |
| `importFileAndVerify` | toast, error 131 | returns `UNREADABLE` |
| `importProvidedFile` | toast, error 131 | logs; caller returns `ROLLED_BACK`/`RESTORE_FAILED` |
| `backupDbToSd` | toasts, errors 288 and 333 | logs, and returns whether the backup was written |
| `fileSetup` | toasts, error 87 and "no database" | logs |

`exportDB` keeps its toasts (errors 282, 287, 333) and `confirmAndImport` keeps its one. Both are
dialog callbacks on the main thread, and neither is reachable from a worker. The numbered error
codes survive in the log messages — they are the vocabulary the maintainer recognises, and
renumbering them helps nobody.

**The tests got sharper for free, as expected.** `import_ofAnInvalidFile_isRejected` asserted
`false`, which a rejected file and an unreadable one both satisfied; it now asserts
`NOT_A_DATABASE`. `NewerSchemaImportTest` asserts `NEWER_SCHEMA` rather than "not OK", so the test
now pins the message the user sees rather than merely the refusal. One test added:
`import_ofAMissingFile_isUnreadable`.

**42 tests, 0 failures, 0 errors, 0 skipped. Lint 0 errors.** No red run for this step: it is a
refactor of how an already-tested outcome is reported, and the failing behaviour it removes was
already demonstrated red under Step 2 — the `Can't toast on a thread that has not called
Looper.prepare()` trace is finding 6 firing.

## Step 4 — The two dead file paths

**Finding 3 — CSV export crashes on the fallback path.** `getAppFilesDir` falls back to
`context.getFilesDir()` when external storage is unavailable, but `provider_paths.xml` declares only
`<external-files-path>`, so `FileProvider.getUriForFile` throws `Failed to find configured root`.
`exportDB` catches it into an "error 333" toast; `SpreadsheetHelper.sendFile` does not, and the app
crashes. Add `<files-path name="internal_files" path="." />`. One line, and it makes the fallback
that the comment already promises actually work.

**Finding 4 — the `file://` import branch always fails.** `openImportSource` branches on
`contentResolver != null`, not on the URI scheme, and `MainActivity.java:223` passes
`uri.getPath()` — a bare path, no scheme — together with a non-null resolver, so
`openInputStream(Uri.parse("/storage/…/backup.edb"))` throws `FileNotFoundException: Unknown URL`
every time. Pass `null` as the resolver in that branch; `openImportSource` then takes its
`FileInputStream` path, which is what the branch was written for. Deleting the branch is also
defensible, but the fix is one word and keeps a path that costs nothing to have.

**Device check:** finding 3 is hard to trigger on a modern phone — verify by pointing
`getAppFilesDir` at `getFilesDir()` temporarily and exporting a CSV. Finding 4:
`adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/x.edb`.

### ✅ Step 4 — done 2026-09-05

Finding 3 is the one-line `<files-path>` the review specified, with a comment saying what the entry
is for so the next person does not read it as redundant with `<external-files-path>`.

**Finding 4 was fixed in the callee as well as the caller.** The review's fix — pass `null` from
`MainActivity`'s `file://` branch — is right and is applied. But `openImportSource` branching on
`contentResolver != null` meant the caller's two arguments had to agree with each other, and any
future caller could get it wrong the same way. It now branches on the URI scheme, which is the
question it was always asking. A bare path is read from disk whatever else it is handed.

**The red run corrected the finding.** `import_ofAPlainPath_succeedsEvenWhenGivenAResolver` failed
as expected (`expected:<OK> but was:<UNREADABLE>`), but `import_ofAFileUri_succeeds` **passed
against the unfixed code** — and that is the precise shape of the bug.
`ContentResolver.openInputStream` accepts `file://` URIs perfectly well, so the branch was never
broken because a resolver cannot read a file URI. It was broken because `MainActivity` called
`uri.getPath()` and threw the scheme away, leaving a bare path to be parsed as a URI. Handing over
the whole URI always worked. The review's description — "the dead branch is for older senders" — is
right about the effect and imprecise about the cause; the test is kept and relabelled as
characterisation so the distinction is not lost again.

**44 tests, 0 failures, 0 errors, 0 skipped. Lint 0 errors.**

## Step 5 — Ask for notifications once

Findings 5 and 8 are the same area and should land in one commit.

`MainActivity.onCreate:157` calls `NotificationPermission.requestIfRemindersEnabled(this)` on every
launch. Once `POST_NOTIFICATIONS` has been denied twice, Android returns an immediate denial without
a dialog, so the "Reminders will not appear…" toast fires on every launch and every rotation.

The toast is right — the Step 5 reasoning that a silent failure is worse than a message still holds.
Firing it forever is the bug. Ask once per install unless the user changes the reminder setting;
`shouldShowRequestPermissionRationale` distinguishes "not asked yet" from "permanently denied", and
a `PrefManager` flag records that the request was made.

`warnIfUngranted` (`NotificationPermission.java:116`) has no callers and a javadoc describing call
sites that do not exist. Make it the single place that decides whether to warn — which resolves
finding 5 in the same edit — or delete it. Do not leave the javadoc standing as it is.

**Device check:** deny twice, then rotate and relaunch several times. No toast. Then turn reminders
off and on again and confirm the app asks once more.

### ✅ Step 5 — done 2026-09-05

The two halves of the fix are a stored flag and a division of labour between the two entry points:

- `requestIfRemindersEnabled` — the launch-time call, from `MainActivity.onCreate` — now asks at
  most once per install. It consults `shouldShowRequestPermissionRationale` *and* a stored flag,
  because the rationale signal alone cannot separate the two cases that matter: it is false both
  before the first ask and after a permanent denial.
- `request` — the deliberate-user-action call, from the reminder switch in Settings — ignores the
  flag entirely and always asks. Turning the switch on is the user asking a question that deserves
  an answer, even if they refused the same permission months ago. After two denials Android
  auto-denies without a dialog, which routes straight to the callback and puts the message in front
  of the user at the one moment it is useful: immediately after they asked for a feature that cannot
  work.

**Finding 8 is resolved by finding 5's fix, as the review suggested it could be.** `warnIfUngranted`
is now the single place that decides whether to warn, called from `onRequestPermissionsResult` on
both paths that reach it, and the duplicate toast that had been inlined in the callback is gone. It
also gained the reminders-enabled condition it needed to be that single place — a user with
reminders off has lost nothing and should hear nothing. Its javadoc describes the call sites it
actually has.

**What is tested, and what is not.** The stored flag is pinned by three tests, including one
asserting it lands under the declared key: a typo there reads as "never asked" forever, which
restores the bug silently while every other test in the suite still passes. The dialog behaviour is
*not* tested — driving the real permission prompt needs UiAutomator and a device whose permission
state the test controls, which is disproportionate here. **The device check is this step's real
gate**, and it is owed at the final pass.

`IsolatedDatabaseContext` is public now so the new test can use it from the `reminders` test
package. Widening a test helper is the right trade against widening production API.

**47 tests, 0 failures, 0 errors, 0 skipped. Lint 0 errors.**

## Step 6 — Cheap cleanups

**Finding 7 — encrypted prefs on the main thread.** `DropBoxHelper.hasStoredCredential` (`:158`)
builds a `MasterKey` and opens `EncryptedSharedPreferences` on every `onResume`/`onCreate` that
checks for a credential, and `initializeDropboxV2` immediately repeats it. Keystore work on the main
thread, twice per resume. No jank was observed on either phone, so this is frame-time cost rather
than a bug. Cache the instance in a field; `EncryptedSharedPreferences` is designed to be held.

**Finding 9 — the `<queries>` CSV filter never matches.** `AndroidManifest.xml:31` declares an
`ACTION_SEND` + `text/csv` filter, but `SpreadsheetHelper.java:370` calls `queryIntentActivities`
with the **chooser** intent, whose action is `ACTION_CHOOSER`. The filter does not apply, so the
`grantUriPermission` loop grants to nothing. Export works regardless, because `createChooser`
propagates `FLAG_GRANT_READ_URI_PERMISSION` to whichever target the user picks. Delete both the loop
and the `<queries>` block and rely on the chooser's own grant — fewer moving parts, and it is what
is already working.

### ✅ Step 6 — done 2026-09-05

**Finding 7:** the `EncryptedSharedPreferences` instance is cached in a field on `DropBoxHelper`,
which is per-activity, so the `MasterKey` build and the Keystore work behind it happen once instead
of twice per resume. A *failure* is deliberately not cached: `null` means the open failed and the
next call retries. Nothing measurable was expected here and nothing was measured — it was ranked
"defer" precisely because no jank was ever observed on either phone. This is a latent cost removed,
not a bug fixed, and it should not be described as one.

**Finding 9:** both halves deleted, as the review recommended. The `grantUriPermission` loop in
`SpreadsheetHelper.sendFile` is gone along with the `<queries>` element it was aimed at, and two
imports went with them. `Intent.createChooser` propagates `FLAG_GRANT_READ_URI_PERMISSION` to
whichever target the user picks, which is the mechanism that has been doing the work all along, so
it is now the only mechanism. Verified in the merged manifest rather than the source: the only
`<queries>` left in `mobile/build/intermediates/merged_manifest/` is `com.dropbox.android`, which
the Dropbox SDK contributes.

**Finding 3 got its test after all.** The step-4 write-up left it as device-check-only, on the
grounds that the fallback needs external storage to be unavailable. That was too quick:
`FileProvider.getUriForFile` maps a path against the declared roots and does not require the file to
exist, so the declaration can be asserted directly. `FileProviderPathsTest` does that against the
real application context, creating nothing, and fails without the `<files-path>` entry with the
exact production error:

```
java.lang.IllegalArgumentException: Failed to find configured root that contains
/data/data/de.timowa.expenselog/files/probe.csv
```

**49 tests, 0 failures, 0 errors, 0 skipped. Lint 0 errors.**

## The device pass — what to run, and what a pass looks like

**Run and passed on 2026-09-05** — see *Device pass — COMPLETE* below for the results. The procedure
is kept because it is the checklist to re-run after any future change to the import, backup or
reminder paths.

Everything below was verified on the `Pixel_6` emulator with 49 instrumentation tests. What an
emulator cannot supply is real records, a real Dropbox account, a real permission history, and a
system that has been told "no" twice. Four of the nine findings turn on exactly those.

**Target: a Pixel 4a (Android 14) and a Unihertz Jelly Star (Android 13)** — the two phones Phase 2
was gated on. Tests 1–4 need only one phone; tests 7–9 want both.

### Before you start — this is not optional

1. **Export an `.edb` and get it off the phone.** Settings → Export Database → send it to yourself.
   Several tests below deliberately attempt bad imports. The app's own automatic backup lives in
   `Android/data/de.timowa.expenselog/files/` and **does not survive an uninstall**, so it is not a
   safety net for this session.
2. **Note what is there now**, so "records intact" is a comparison and not an impression:
   ```
   adb shell ls -l /sdcard/Android/data/de.timowa.expenselog/files/
   ```
   Write down the total record count the app shows, and one specific old entry's amount and note.
3. **Install the branch build**:
   ```
   gradlew.bat :mobile:installDebug
   ```
   Installing over an existing debug build keeps the database — same key, same application id.
4. **Open the app once** before expecting any reminder. Replacing a package cancels its pending
   alarms; `MainActivity.onCreate` re-arms them.

### The checks

One row per check, and each row is meant to be workable on its own. Escaped pipes in the commands
are table syntax — type them as a single `|`.

| # | What to test | How — step by step | Pass |
| --- | --- | --- | --- |
| **1** | **Finding 1** — a newer-schema `.edb` is refused *before* anything is overwritten. The one that matters most, and the only file here that cannot occur naturally: `DATABASE_VERSION` has only ever been 1 | 0. **Settings → Export Database first** — that writes `expenseLog.edb` here. Do *not* use `ExpenseLogBackup.edb`: it does not exist until an import has run.<br>1. `adb pull /sdcard/Android/data/de.timowa.expenselog/files/expenseLog.edb future.edb`<br>2. `sqlite3 future.edb "PRAGMA user_version = 2;"`<br>3. `sqlite3 future.edb "PRAGMA user_version;"` — must print `2`<br>4. `adb push future.edb /sdcard/Download/future.edb`<br>5. App → Settings → **Import Database** → pick `future.edb` → confirm<br>6. Settings → Apps → Expense Log → **Force stop**, then reopen the app<br>7. `adb shell ls -l /sdcard/Android/data/de.timowa.expenselog/files/` | Toast reads **"This backup was made by a newer version of Expense Log. Update the app, then import it again."** — *not* "Error Importing".<br><br>Record count unchanged and the entry you noted still correct; app reopens normally at step 6; database size and timestamp unchanged.<br><br>**Fail:** any message about restoring, an empty record list, or a crash on reopening. Recover with the `.edb` from setup |
| **2** | **Finding 1** — a valid `.edb` still imports. The guard must reject the future, not everything | 1. Settings → **Import Database**<br>2. Pick the untouched export from setup step 1<br>3. Confirm | "Import successful", and the records are the ones in that file |
| **3** | **Findings 1, 6** — junk is refused with the *right* message rather than a generic one | 1. `printf 'this is definitely not a database' > junk.edb`<br>2. `adb push junk.edb /sdcard/Download/junk.edb`<br>3. Settings → **Import Database** → pick it → confirm | **"Database import stopped because it is not a valid Expense Log database"** — the specific message. Records untouched.<br><br>**Up to ~20 s is expected** if picked from a cloud provider; see the note below the table |
| **4** | **Finding 2** — the instrumentation suite does not overwrite the real backup. **Do not use `connectedDebugAndroidTest`**: Gradle uninstalls the app afterwards and deletes `Android/data/<package>` with it, destroying the file under test either way | 1. `adb shell md5sum /sdcard/Android/data/de.timowa.expenselog/files/ExpenseLogBackup.edb`<br>2. `gradlew.bat :mobile:installDebug :mobile:installDebugAndroidTest`<br>3. `adb shell am instrument -w de.timowa.expenselog.test/androidx.test.runner.AndroidJUnitRunner`<br>4. Repeat the `md5sum` from step 1<br>5. `adb shell ls -l /sdcard/Android/data/de.timowa.expenselog/files/` | The two checksums are identical; the run reports `OK (49 tests)`; and step 5 shows no `roundtrip.edb`, `not-a-database.edb`, `isolation.edb`, `from-the-future.edb` or `probe.tmp` |
| **5** | **Finding 4** — the `file://` import path works at all. It never has, so there is no "worked before" to compare with | 1. `adb push future.edb /sdcard/Download/plain.edb` — any valid `.edb`<br>2. `adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/plain.edb -t application/octet-stream` | The app opens its import confirmation dialog and the import completes.<br><br>**Fail:** a refusal means a caller is still pairing a bare path with a resolver — before the fix this threw `FileNotFoundException: Unknown URL` every time |
| **6** | **Findings 5, 8** — the denial message appears once, not on every launch. **Destroys the database by design** — run it last, or on the second phone | 1. Settings → Apps → Expense Log → **Clear storage**<br>2. Open the app; reminders default to on, so the prompt appears — **deny it**<br>3. Force-stop, reopen, **deny again**. Android now auto-denies with no dialog<br>4. **Relaunch three times and rotate the screen twice**<br>5. Settings → General → Reminders → switch **off**, then **on** | No "Reminders will not appear…" toast at any point in step 4 — before the fix it appeared every single time.<br><br>Exactly **one** toast at step 5, immediately after switching on. That is deliberate: turning the switch on is a fresh question |
| **7** | **Finding 6** — a Dropbox failure surfaces as a message rather than a crash or silence | 1. Configure `dropbox.appKey` and connect an account<br>2. Settings → Data & sync → **restore from Dropbox**<br>3. Turn on **airplane mode while it is running** | A toast naming the failure, and no crash.<br><br>Before Step 3 this called `Toast.makeText` from a pool thread with no Looper and threw `Can't toast on a thread that has not called Looper.prepare()` instead of showing anything |
| **8** | The Phase 2 gate, re-run — nothing here broke anything that already worked. **Both phones** | 1. Add a record<br>2. Records → ⋮ → **Export** (CSV)<br>3. Settings → **Export Database** (`.edb`)<br>4. Import that `.edb` on the *other* phone<br>5. Set a reminder **~10 minutes** ahead; wait for it<br>6. Tap the notification's **Settings** action<br>7. Connect Dropbox and sync | All as recorded in the Phase 2 pass: CSV sends, the `.edb` round trip carries every record across, the reminder fires, the Settings action clears the notification and opens Settings, and records travel between phones.<br><br>Not less than 10 minutes — see the note below the table |
| **9** | Nothing crashed during any of the above | 1. `adb logcat -c` **before starting test 1**<br>2. After everything: `adb logcat -d -b crash`<br>3. `adb logcat -d \| grep -E "AndroidRuntime\|FATAL"` | Both empty.<br><br>Read them **before rebooting** the phone: the buffers are RAM-backed and a reboot clears them |
| **10** | **Finding 3** — the internal-storage fallback is a shareable FileProvider root. **Optional**, and needs a temporary code edit: it cannot be triggered on a phone with working external storage, which is all of them | 1. In `FileHelper.getAppFilesDir`, temporarily `return context.getFilesDir();`<br>2. `gradlew.bat :mobile:installDebug`<br>3. Records → ⋮ → **Export** (CSV)<br>4. **Revert the edit** | The chooser appears and the file sends. Before the fix this threw `Failed to find configured root` and crashed the app.<br><br>Confirmation only — `FileProviderPathsTest` already asserts the declaration itself |

### Three notes the table cannot hold

**Test 3's ~20-second delay is expected, and is not this branch's doing.**
`FileHelper.importFileAndVerify` stages the *entire* picked document into the cache before it
inspects a byte, on the main thread, straight from the confirm dialog's click handler — so a file on
Google Drive is downloaded inside that copy. It is listed under *Deferred on purpose* in
`docs/history/REHABILITATION_PLAN.md`. **The ordering must not be "fixed"** by validating before staging: staging
first is exactly the Step 4 fix that stopped an invalid import destroying the database.

**Test 8's reminder can now be set a minute or two out** — changed 2026-09-05, at the maintainer's
request, precisely to make this pass quicker to run. `setupReminder` used to push the first reminder
to *tomorrow* whenever the configured time was under five minutes away; it now only does so when the
time has already gone by today. See *A note on the reminder window* below.

**The alarm is still deliberately inexact**, so a one-minute setting does not arrive in exactly one
minute — measured on a phone, **+1 to +4 minutes** after the configured time. Saving the time re-arms
immediately, so repeat it for each check that needs a fresh notification.

**Test 4's procedure was rehearsed on the emulator on 2026-09-05**, so the commands are known to
work rather than merely to look right. `am instrument` reported `OK (49 tests)` and left the real
directory empty:

```
$ adb shell ls -la /sdcard/Android/data/de.timowa.expenselog/files/
total 16
drwxrws--- 2 u0_a163 ext_data_rw 4096 .
drwxrws--- 3 u0_a163 ext_data_rw 4096 ..

$ adb shell run-as de.timowa.expenselog ls -R cache/test_sandbox
cache/test_sandbox/external_files:
ExpenseLogBackup.edb  as-file-uri.edb  current.edb  from-the-future.edb
```

Every file the suite writes is in the sandbox and none of it in the real directory. What the
emulator cannot supply is that directory holding the maintainer's actual `ExpenseLogBackup.edb`,
which is the only thing the checksum comparison adds — so run it on the phone anyway.

### ⚠️ Emulator pass — run 2026-09-05, 9 of 10 closed

**This is not the device pass.** It was run on the `Pixel_6` emulator (API 33, AOSP, x86_64) by
driving the real UI through `adb` and reading results from screenshots, `logcat`, and the database
file itself. It closes what an emulator can close and says plainly what it cannot. **The two-phone
pass is still owed**; test 8 is the one essentially untouched by this.

Test 7 was closed in a second sitting, after the maintainer supplied a throwaway Dropbox account and
completed the browser login by hand — the login page would not render in this image's only browser,
`webview_shell` (Chromium 101). Everything after that point was driven from `adb`.

| # | Finding | Result |
| --- | --- | --- |
| 1 | 1 | ✅ **Pass**, with better evidence than the check asked for — see below |
| 2 | 1 | ✅ Pass — "Import successful", records restored from the file |
| 3 | 1, 6 | ✅ Pass — "Database import stopped because it is not a valid Expense Log database" |
| 4 | 2 | ✅ Pass — md5 identical across a full 49-test run, no stray files |
| 5 | 4 | ⚠️ **Fix confirmed, branch still dead** — a new finding, see below |
| 6 | 5, 8 | ✅ Pass — exactly two toasts in the whole sequence, neither on a relaunch |
| 7 | 6 | ✅ **Pass** — closed 2026-09-05 once a test account was available |
| 8 | — | ⚠️ Partial — CSV and `.edb` verified; no second phone, no reminder waited for |
| 9 | — | ✅ Pass — crash buffer empty, no `AndroidRuntime`, no app-level errors |
| 10 | 3 | ✅ Pass — fallback forced by a temporary edit, CSV written, no crash |

#### Test 1 — the filesystem proved more than the screen did

The toast read **"This backup was made by a newer version of Expense Log. Update the ap…"** —
`import_refused_newer_schema`, not a generic error. Then, from disk rather than the screen:

- the live database's mtime was **unchanged** across the attempt — never overwritten;
- `PRAGMA user_version` still 1, both records (`12.34`, `0.0`) still present;
- **`ExpenseLogBackup.edb` did not exist afterwards.** It is written by `backupDbToSd`, which runs
  only *after* a candidate passes inspection. Its absence is filesystem-level proof that the refusal
  happened before the backup-and-overwrite sequence began — the reject-up-front ordering observed
  rather than argued. It duly appeared later, on the *successful* import in test 2.

The app also reopened normally after a force-stop, which is the half of finding 1 that used to leave
it unopenable for good.

#### Test 5 — the fix works and the branch is still unreachable

Finding 4 is genuinely fixed: the failure moved from `FileNotFoundException: Unknown URL` (a bare
path parsed as a URI) to a real filesystem error reached through `FileInputStream`, exactly as
intended:

```
error 131: could not read the import source
java.io.FileNotFoundException: /sdcard/Download/valid.edb: open failed: EACCES (Permission denied)
```

**But the import still fails, and not because of this branch.** Phase 2 Step 4 removed every storage
permission, so the app cannot read an arbitrary `/sdcard` path at all — and a `file://` URI from
another app points at exactly such a path. The branch is **unreachable by design on API 29+**, and no
fix inside `openImportSource` can change that.

The review's alternative — *"accept that this path is vestigial and delete the branch"* — is
therefore the better answer, and that was not visible from reading the code. Two cheap options:

- delete the branch, and let a `file://` intent say only `content://` sources are supported; or
- keep it, since it costs nothing and would work if a sender ever passed a readable path.

**Left open deliberately.** It is a behaviour change beyond the nine findings and the maintainer
should choose. Worth noting that Step 3 is what made this legible at all: before it, the same failure
was a bare `false`.

#### Test 7 — closed, and it exercised more than the check asked for

Blocked on the first sitting; closed on 2026-09-05 once a throwaway Dropbox account was available.
The maintainer completed the browser login by hand, because this image's only browser is
`webview_shell` (Chromium 101) and Dropbox's login page renders blank in it. Everything either side
of that was driven from `adb`.

**Three things fell out of it, and two were not what the check was for.**

**The PKCE round trip works on real hardware paths.** `docs/history/REHABILITATION_PLAN.md` records the redirect
out to a real browser and back as the one thing the emulator gate could not exercise. It now has
been — the log shows the custom-scheme hop landing back inside the app:

```
START u0 {act=android.intent.action.VIEW cat=[BROWSABLE] dat=db-…://1/...
     cmp=de.timowa.expenselog/com.dropbox.core.android.AuthActivity}
```

**The credential really is encrypted at rest.** `dropbox_secure_prefs.xml` was read straight off the
device with `run-as`. It contains a Tink AES-SIV keyset and no readable token, refresh token or
account address — the Phase 2 `EncryptedSharedPreferences` work, checked against a real credential
for the first time rather than against the code that writes it.

**And the check itself.** A manual backup succeeded first ("Successful Backup" — a real upload).
Airplane mode was then enabled and the same operation retried:

```
java.net.UnknownHostException: Unable to resolve host "api.dropboxapi.com"
    at …DropboxTask$$ExternalSyntheticLambda0.run
```

— a failure raised on a `DropboxTask` pool thread, surfacing on screen as **"Error Backing Up"**,
with the crash buffer empty, the process still alive, and **zero** `Looper.prepare` exceptions. That
last count is the point: it is the exact exception finding 6 produced, and it did not occur.

**Two honest qualifications.**
- The procedure says to interrupt a download mid-flight. Airplane mode was enabled *first* instead:
  the backup file is 32 KB and completes too fast to interrupt reliably, and enabling it first
  exercises the same failure path without a race.
- The network failed *before* `importFileAndVerify` was reached, so the specific finding-6 line —
  a failed *import* inside a download — was not itself driven on device. It is covered by
  instrumentation, and what the device proves is the surrounding claim: a worker-thread failure now
  reaches the user as a message instead of throwing.

#### What the emulator could not do

- **Test 7 was blocked and is now closed** — see below. What remains genuinely out of reach here is
  test 8's second phone.
- **Test 8 is partial.** CSV export produced correct content — header, both records, total — and the
  `.edb` export → import round trip carried the records back. Not done: a second phone, and waiting
  for a reminder to fire. The CSV chooser reported *"No apps can perform this action"*, because this
  AOSP image registers nothing for `text/csv`; the chooser opened and nothing crashed, so that is an
  emulator gap rather than a regression.
- **No real records were ever at risk**, which is precisely why this is not the device pass. Test 6
  needs `pm clear`, which on a phone destroys the database.

#### Also observed in passing

Settings, the General preference screen and the Reminders screen all opened without a crash — the
reflection-resolved XML that a package rename silently breaks, still resolving. `LocalBackupManager`
had written its daily, weekly and monthly copies.

### A note on the reminder window — changed 2026-09-05

`ReminderManager.setupReminder` (`ReminderManager.java:82`) had a five-minute guard: if the
configured time was less than five minutes away, the first reminder was pushed to the next day. That
made every reminder check in this pass a ten-minute wait. It is now narrowed so that only a time
which has **already gone by today** is pushed to tomorrow.

**The guard could not simply be deleted, and the reason is worth recording.**
`firstReminderCalendar` is *today's* date at the configured hour and minute, so a time earlier in
the day is in the past — and `AlarmManager.setRepeating` on a past `RTC_WAKEUP` time fires
immediately. Removing the check outright would mean that setting 08:00 at 17:00 delivered a reminder
the instant it was saved, and again every day after. The guard's real job is catching that, not
catching "too soon"; the five minutes was incidental.

Also deleted while there: the original author's commented-out
`firstReminderCalendar.add(Calendar.SECOND, 10)` shortcut, marked `// todo remove … after testing`.
It existed to make exactly this kind of testing bearable, and is superseded.

This is the one code change made after the emulator pass and before the device pass. It is
deliberate and was asked for; it is recorded here so that a reminder firing a minute out on the
phones is not mistaken for a regression. 49 tests green, lint 0 errors.

**✅ Confirmed on a phone, 2026-09-05** — the first real-device evidence on this branch. A reminder
set one minute ahead fires the same day, **typically +1 to +4 minutes** after the configured time.

That number is worth keeping, because it corrects the assumption the pass was planned around. The
existing data point was the Jelly Star delivering an 11-minute setting at about 15, which looks like
a proportional overshoot and led to the "never less than ten minutes" advice. It is not proportional:
the inexact window is a **bounded slack of a few minutes**, so a short setting is late by the same
small amount as a long one. Every remaining reminder check can therefore be run on a one- or
two-minute setting rather than a ten-minute one.

### ✅ Device pass — COMPLETE, 2026-09-05

Run by the maintainer on a **Pixel 4a (Android 14)** and a **Unihertz Jelly Star (Android 13)**,
against **2540 real records and 24 tags**. The agent did not drive these devices at any point;
commands were handed over and their output read back. **Every check passed.**

| # | Finding | Result |
| --- | --- | --- |
| 1 | 1 | ✅ **Pass** — see below |
| 4 | 2 | ✅ **Pass** — real backup byte-identical across a full suite run |
| 7 | 6 | ✅ **Pass** — an offline Dropbox backup errors on screen, no crash |
| 8 | — | ✅ **Pass in full**, both phones — see below |
| 6 | 5, 8 | ✅ **Pass** — no toast across three relaunches and two rotations |
| 9 | — | ✅ **Pass** — crash buffer empty on both phones |

**Both data-loss findings are closed on real hardware, and the Phase 2 gate passes again.**

#### Test 1 — a newer-schema `.edb` refused, against real records

`user_version` was raised to 2 on a copy of a real export (byte 63 of the SQLite header, patched with
PowerShell — the machine has neither `sqlite3` nor Python). Importing it produced exactly
*"This backup was made by a newer version of Expense Log. Update the app, then import it again."*

What makes this conclusive is not the toast but what did **not** move:

```
ExpenseLogBackup.edb   20:15   md5 09e7e9a8084118bd86ef0c8def8c4a01   (identical to baseline)
databases/expenseLog.edb   131072 bytes, 20:15                        (older than the attempt)
adb logcat -d -b crash                                                 (empty)
```

`backupDbToSd` runs only *after* a candidate passes inspection. An unchanged backup checksum
therefore proves the refusal happened **before** the backup-and-overwrite sequence started — not
that a rollback worked, but that there was nothing to roll back. The app opened normally after a
force stop with all records present.

This is the check the whole branch existed for, and the emulator could only ever approximate it: on
the emulator the "records" were two rows this session invented. Here it is 2540 of the maintainer's
own.

#### Test 4 — the suite does not eat the real backup

`md5sum` either side of a full `adb shell am instrument` run, with the maintainer's genuine
`ExpenseLogBackup.edb` in place: **unchanged**, `OK (49 tests)`, and no `roundtrip.edb`,
`isolation.edb`, `from-the-future.edb` or `probe.tmp` left in the directory.

Run through `am instrument` rather than `connectedDebugAndroidTest`, for the reason recorded under
Step 1: Gradle uninstalls the app afterwards and deletes `Android/data/<package>` with it, so the
file under test would be gone either way and the result would mean nothing.

This is the check the emulator could not really make. There, the directory was empty and the suite
had nothing of value to destroy. Here the file it would have overwritten — on the branch as it stood
at `f05eaf6` — was the maintainer's only local backup, and the one finding 1 would send him to.

#### Test 8 — the Phase 2 gate, re-run on both phones

Pixel 4a (Android 14) and Unihertz Jelly Star (Android 13). Every step passed.

| Step | Result |
| --- | --- |
| Add a record, export CSV | ✅ Share sheet opens, the new record is in the file |
| Export `.edb`, send it off the phone | ✅ |
| Reminder set 2 minutes out | ✅ Fires, +1 to +4 minutes late |
| Tap the notification's **Settings** action | ✅ Notification clears *and* Settings opens |
| Import that `.edb` on the **other** phone | ✅ "Import successful", the new record present |
| Reminder on the second phone | ✅ Fires on Android 13 too |
| Dropbox manual backup | ✅ "Successful Backup" |
| View existing Dropbox backups | ✅ List loads, the new backup in it |
| Manual backup with **airplane mode on** | ✅ Error on screen, no crash |

Two of these carry more weight than the rest. **The notification's Settings action** is the
trampoline fix on hardware — before Phase 2 Step 5 that tap did nothing at all while looking
identical, so only a device can tell the difference. And **the `.edb` crossing between two real
phones** is the migration path itself: export on one, import on the other, records intact.

The airplane-mode step also closes test 7 on real hardware rather than only on the emulator, which
matters because a phone's network stack fails differently from an emulated one. It is the finding-6
path — a failure raised on a `DropboxTask` pool thread — reaching the user as a message instead of
throwing.

#### Test 6 — the notification prompt asks once

Run on the Jelly Star, last, because it needs *Clear storage* and so destroys the database — on the
Pixel that would have meant 2540 records.

Denied twice, then **three relaunches and two screen rotations with no toast**. Before the fix each
of those five events produced "Reminders will not appear…", because Android auto-denies after two
refusals and delivers that denial straight to the callback. Toggling reminders off and on then asked
again and produced exactly one message — the deliberate asymmetry between `requestIfRemindersEnabled`
(asks once per install) and `request` (a fresh question every time the user flips the switch).

#### Test 9 — logcat

Crash buffer empty on **both** phones, read before any reboot.

---

**The device pass is complete. Every check in the table above passed, on two handsets, against the
maintainer's own records.** The branch now has the evidence Phase 2 was merged on, and the two
data-loss findings that prompted Phase 3 are closed against the data they threatened.

### Recording the result

Add a `### ✅ Device testing — passed <date>` block under this section in the same shape as the ones
in `docs/history/REHABILITATION_PLAN.md`: the phones and OS versions, the table with a result per row, and a
plain statement of anything that was **not** run. A check that was skipped is a fact about the
branch, not an embarrassment — the Phase 2 record says which checks it skipped and why, and that is
the reason its evidence can be trusted.

## Step 7 — Delete the `file://` import path

Not in the original plan. It exists because the emulator pass turned up something reading the code
could not: finding 4's fix is correct and the branch is *still* dead, because the app may not read
shared storage at all. The maintainer's decision was to delete it.

**Three changes, and the third is the one that matters most.**

1. `MainActivity.checkForFileImport` loses the `else` branch, and gains a guard that refuses any
   non-`content://` scheme **before** the confirmation dialog. Offering to "overwrite ALL existing
   records" for something that cannot be read either way is the worst possible ordering.
2. A new string, `import_unsupported_source`, that says what to do instead — use Settings → Import
   Database, or open the backup from an attachment — rather than reporting a failure.
3. **The manifest stops advertising `android:scheme="file"`.** Deleting the branch without this
   would leave the app in a file explorer's "open with" list for `.edb` files and then turning them
   away. Claiming a capability and refusing it is worse than not appearing at all.

`openImportSource` keeps its scheme check. That is not vestigial: `DropboxDownload` still passes the
bare path of a file it has just written into the cache, with no resolver, and the rule that a path is
a path belongs in the callee.

**Verified on the emulator, 2026-09-05:**

```
$ adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/plain.edb ...
Error: Activity not started, unable to resolve Intent
```

The app no longer claims the intent at all. The `content://` path is unaffected — a full export →
import round trip through the SAF picker still reports "Import successful" with the record intact.
**49 tests green, lint 0 errors, crash buffer empty.**

**What this leaves.** Two `<intent-filter>` blocks still advertise `http` and `https` for `.edb`
paths. They route into the same guard and now produce a clear message instead of a bogus overwrite
prompt — but they are dead in the same way, since `ContentResolver` cannot open an `http` URL either.
Removing them is a user-visible change to what the app appears in, so it is **flagged, not taken**.

## Open items this branch leaves

All nine review findings are closed. These are things the work turned up and did **not** resolve —
recorded so they are decisions rather than oversights.

**1. ~~The `file://` import branch is unreachable~~ — resolved 2026-09-05: deleted.** Found by
running it rather than reading it. Finding 4's fix was correct and confirmed, but the app holds no
storage permission (Phase 2 Step 4), so it cannot read the `/sdcard` path a `file://` URI names — the
failure was `EACCES` rather than `Unknown URL`. The maintainer's call was to delete it. See *Step 7*
below.

**2. A leaked `SQLiteConnection` was observed once.** During the emulator pass, while Settings was
open:

```
W SQLiteConnectionPool: A SQLiteConnection object for database
'/data/user/0/de.timowa.expenselog/databases/expenseLog.edb' was leaked!
```

`DBAdapter`'s constructor opens the database immediately, so any `new DBAdapter(...)` whose
`close()` is missed leaks a connection — there are 14 construction sites. **The site was not
identified and is not attributed here**, and this is pre-existing rather than caused by this branch;
if anything Step 2 improved it, by deleting two constructions that existed only to read a file path.
Worth a pass with StrictMode's `detectLeakedSqlLiteObjects` some day. Not urgent: it is a warning,
and no leak was observed to affect behaviour.

**3. One device check remains genuinely unrun** — test 8's two-phone regression: a second handset,
and a reminder actually waited for. Test 7 closed on 2026-09-05 once a throwaway Dropbox account was
available. See the emulator-pass table for exactly what was and was not covered.

## Verification

Phase 3 is done when all of the following hold.

1. ☑ `docs/history/REVIEW_FINDINGS.md` has no open findings, each marked with the step that closed it.
2. ☑ New instrumentation tests for findings 1, 2, 3 and 4, each demonstrated to fail before its fix
   landed. Suite green at 49, lint at 0 errors.
3. ☑ ~~A sentinel `ExpenseLogBackup.edb` survives a full run on a phone~~ — **replaced**, and the
   reason is under Step 1: Gradle uninstalls the app after `connectedDebugAndroidTest` and deletes
   that directory, so a sentinel cannot survive the run whether isolation holds or not. The
   before/after snapshot inside `FilesystemIsolationTest` is the check that actually distinguishes,
   and it runs every time.
4. ☑ On a phone with real records: a newer-schema `.edb` is refused with a message, the records are
   intact afterwards, and the app reopens after a restart. **Passed on the Pixel 4a against 2540
   records**; `ExpenseLogBackup.edb` byte-identical afterwards, which proves the refusal preceded the
   overwrite sequence rather than rolling it back.
5. ☑ A Dropbox failure surfaces as a message rather than a crash or silence — **on the emulator
   against a real account, and again on the phone with airplane mode on.**
6. ☑ Deny notifications twice, relaunch three times, rotate: no toast. Then toggle reminders off and
   on and it asks once more. **Passed on the Jelly Star.**
7. ☑ Full regression pass on both phones — export CSV, export `.edb`, import it, set a reminder and
   see it fire, Dropbox connect and sync. The Phase 2 gate, re-run and passing.
8. ☑ Logcat clean of `AndroidRuntime` across the pass — crash buffer empty on **both** phones.

**Every item is ticked.** Items 4–8 were the device pass; it completed on 2026-09-05 across two
phones, and the branch merged as PR #3 (`6af3bd4`).

**The gate standard for this branch:** an instrumentation test per bug, run on the emulator as each
step lands, then **one consolidated device pass on both phones at the end** — not a device gate per
step. Findings 1 and 2 are the exceptions that need real hardware and real data, and they are
covered by items 3 and 4 above.

## Ranked failure points

1. **The Step 2 fix is tested against a crafted file, not a real future schema.** `DATABASE_VERSION`
   has only ever been 1, so a newer-schema `.edb` can only be manufactured by raising
   `user_version` on a real export. The test proves the guard, not that a genuine v2 file behaves as
   a genuine v2 file would. It is the best available evidence and it should be labelled as such.
**Retired by the device pass, 2026-09-05:**

- ~~Step 3 touches every import call site, and `DropboxDownload`'s result now crosses a thread
  boundary it did not before~~ — the Dropbox happy path, the list, and an offline failure were all
  exercised on the phone; the failure reached the user as a message with no crash.
- ~~Step 5 can silence a real problem: asking once and then never surfacing that reminders are
  broken~~ — the flag does reset when the switch is flipped. Verified on the Jelly Star: silent
  across three relaunches and two rotations, then exactly one message when reminders were turned
  back on.
