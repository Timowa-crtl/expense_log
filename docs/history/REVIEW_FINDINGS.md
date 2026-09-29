# Phase 2 — code review findings

> **Status: all nine closed on the `recovery` branch, and verified on hardware** — see
> `docs/history/RECOVERY_PLAN.md` for how each was fixed and what it cost. 49 instrumentation tests, lint at 0
> errors, and a **device pass completed 2026-09-05 on two phones against 2540 real records**. The two
> data-loss findings are closed against the data they threatened.
>
> Originally recorded and not acted on. These came out of an automated review of PR #2
> (`rehabilitation` → `main`) at `7994598`, and are posted as inline comments on that PR. The
> decision was to **merge Phase 2 as tested and fix from this list afterwards**, because the branch
> has a device-verification record on two phones that a round of pre-merge edits would invalidate.
>
> **Every one of the nine was re-checked by hand against the source before it was written down
> here.** None is a machine's guess taken on trust. Where a claim in the original comment was
> imprecise, this document states what the code actually does.
>
> Nothing here was found by running the app. These are read from the code, so each carries a
> device check to close it — the same standard the rest of Phase 2 was held to.

## Ordering

Two of these can destroy data. They are not Phase 3 material to be scheduled behind a
modernisation backlog; they should be the first commits after the merge.

| # | Where | What | When |
| --- | --- | --- | --- |
| 1 | `FileHelper.java:395` | A bad import leaves the app unopenable | ✅ **Closed** |
| 2 | `IsolatedDatabaseContext.java` | The test suite overwrites the real backup | ✅ **Closed** |
| 3 | `FileHelper.java:264` | CSV export crashes on the fallback path | ✅ **Closed** |
| 4 | `MainActivity.java:221` | `file://` import always fails | ✅ **Closed** |
| 5 | `NotificationPermission` | Denial toast on every launch | ✅ **Closed** |
| 6 | `FileHelper.java:357` | Toast from a Dropbox worker thread throws | ✅ **Closed** |
| 7 | `DropBoxHelper.java:158` | Crypto on the main thread, twice per resume | ✅ **Closed** |
| 8 | `NotificationPermission.java:116` | Dead method with a javadoc that lies | ✅ **Closed** |
| 9 | `AndroidManifest.xml:31` | `<queries>` filter that never matches | ✅ **Closed** |

---

## 1. A newer-schema `.edb` leaves the app unopenable

`FileHelper.importFileAndVerify` — the rollback never runs, and the failure is silent.

The chain, verified line by line:

1. `isValidExpenseLogDatabase` (`FileHelper.java:412`) only checks that a `tagTypes` table exists.
   A file written by a **newer** schema passes it.
2. `importProvidedFile` overwrites the live database with that file.
3. The verify step does `new DBAdapter(context)` → `getDB()`, and `DBAdapter.onDowngrade`
   (`DBAdapter.java:150`) throws `SQLiteException` by design. Caught at `FileHelper.java:385`,
   `success = false`. Correct so far.
4. `restoreBackupDbFromSd(context)` is called to roll back — and calls `fileSetup`, which calls
   `getInternalDbFile`, which on API 28+ does `new DBAdapter(context).getDB()` **on the database
   that was just overwritten**. It throws again.
5. `fileSetup`'s `catch (Exception)` (`FileHelper.java:88`) swallows it into an "error 87" toast and
   returns `false`.
6. `restoreBackupDbFromSd`'s `else` branch (`FileHelper.java:181`) is **empty**. The restore is
   skipped and nothing is logged.

Net effect: the live database is left unreadable and the backup that exists on disk is never put
back. Every subsequent `DBAdapter.open()` throws, so the app cannot be used again — reinstalling
is the only exit, and that deletes the app's files directory with the backup in it.

Reachable from Settings → Import and from the Dropbox "keep remote" path (`DropboxDownload:145`).

**Why it was not caught:** the Step 4 device testing exercised a *valid* `.edb` and a junk file.
There was never a well-formed database from a future schema version to import — and there could not
have been, because `DATABASE_VERSION` has only ever been 1. The guard added in Step 3 is correct;
the recovery path around it is what fails.

**The fix has two independent halves, and both are worth doing:**
- Make `getInternalDbFile` stop opening the database. It needs a *path*, and on API 28+ it pays for
  one by constructing a `DBAdapter`, opening it, disabling WAL and closing it. `getDatabasePath(DATABASE_NAME)`
  returns the same path without touching the file. That alone breaks the trap.
- Check the schema version in `isValidExpenseLogDatabase`, on the read-only probe it already opens
  (`PRAGMA user_version`), and refuse a newer file **before** anything is overwritten. Rejecting up
  front is better than rolling back correctly.

Fill in the empty `else` while there — a silent branch is what let this reach a device unnoticed.

**Closed on `recovery`, Step 2**, all three parts. `getInternalDbFile` and its sibling
`getInternalDbFolder` — which had the same trap and was not in this review — now return
`getDatabasePath` without opening anything; `inspectCandidate` reads `PRAGMA user_version` on the
read-only probe and refuses a newer file before any overwrite; and both silent branches in
`restoreBackupDbFromSd` (the `else` **and** an empty `catch` this review did not mention) log, with
the method now returning whether the restore happened.

Reproduced red first: the newer-schema import failed with `Can't toast on a thread that has not
called Looper.prepare()` — findings 1 and 6 in one trace — and the path getter was caught deleting a
23-byte corrupt file and replacing it with a fresh 32KB database. 41 tests green afterwards, lint 0
errors. `NewerSchemaImportTest` pins all of it.

**✅ Device check done, 2026-09-05**, on a Pixel 4a against 2540 real records. The refusal message
appeared verbatim, the live database and `ExpenseLogBackup.edb` were both byte-identical afterwards —
the latter proving the refusal preceded the backup-and-overwrite sequence rather than rolling it
back — the crash buffer was empty, and the app reopened with every record present.

## 2. The instrumentation suite overwrites the maintainer's real backup

`IsolatedDatabaseContext` overrides `getDatabasePath`, both `openOrCreateDatabase` forms,
`deleteDatabase` and `getSharedPreferences` — and **not** `getExternalFilesDir`.

`EdbRoundTripTest` calls `FileHelper.importFileAndVerify` (lines 105 and 143). That calls
`backupDbToSd` at `FileHelper.java:372`, which resolves its destination through
`getAppFilesDir` → `context.getExternalFilesDir(null)` — unwrapped, so the **real**
`Android/data/de.timowa.expenselog/files`. The isolated test database is copied over the user's
actual `ExpenseLogBackup.edb`. The test also leaves `roundtrip.edb`, `not-a-database.edb` and
`probe.tmp` there.

This is exactly the data-loss class `IsolatedDatabaseContext` exists to prevent, and that `CLAUDE.md`
warns about — it just prevents it on one axis and not the other. On a phone with real financial
records, running `connectedDebugAndroidTest` destroys the backup that finding 1 would need.

**Fix:** override `getExternalFilesDir` (and `getFilesDir`, which `getAppFilesDir` falls back to) to
return a directory under the test's own cache, and say in the class javadoc that *every* filesystem
accessor `FileHelper` can reach has to be isolated, not just the database ones.

**✅ Device check done, 2026-09-05**, on a Pixel 4a with the maintainer's real `ExpenseLogBackup.edb`
present: byte-identical md5 either side of a full 49-test run via `am instrument`, and no test files
left behind.

**Closed on `recovery`, Step 1.** All three directory accessors are redirected into a sandbox under
the app's cache, and `FilesystemIsolationTest` snapshots the real directory before and after a full
import. Reproduced red first — the run wrote `ExpenseLogBackup.edb` into the real directory — then
37 tests green. The sentinel-file check originally proposed here cannot work: Gradle uninstalls the
app after the run and takes that directory with it. See `docs/history/RECOVERY_PLAN.md`.

## 3. CSV export crashes when external storage is unavailable

`getAppFilesDir` (`FileHelper.java:264`) falls back to `context.getFilesDir()` when
`getExternalFilesDir` returns null. `provider_paths.xml` declares only `<external-files-path>`, so
`FileProvider.getUriForFile` on a file in that fallback throws
`IllegalArgumentException: Failed to find configured root`.

`exportDB` catches it into an "error 333" toast. `SpreadsheetHelper.sendFile` does not — CSV export
crashes the app.

**Closed on `recovery`, Step 4.** `<files-path name="internal_files" path="." />` added to
`provider_paths.xml`, with a comment recording what it is for.

**Device check:** hard to trigger on a modern phone, where external storage is effectively always
mounted. Verify by pointing `getAppFilesDir` at `getFilesDir()` temporarily and exporting a CSV.

## 4. The `file://` import path always fails

`openImportSource` (`FileHelper.java:497`) branches on `contentResolver != null`, **not** on the
URI scheme. `MainActivity.java:221` — the `else` branch that exists precisely to handle a `file://`
URI — passes `uri.getPath()`, a bare filesystem path with no scheme, together with a non-null
`ContentResolver`. So `openInputStream(Uri.parse("/storage/…/backup.edb"))` runs and always throws
`FileNotFoundException: Unknown URL`.

The `content://` branch two lines up is correct, which is why device testing passed: every modern
sender hands over a `content://` URI. The dead branch is for older senders.

**Closed on `recovery`, Step 4**, in the callee as well as the caller. `MainActivity` passes `null`
as specified here, and `openImportSource` now branches on the URI scheme rather than on whether it
was given a resolver — so no future caller can reproduce this by pairing the arguments wrongly.

**One correction from the red run.** A whole `file://` URI passed *with* a resolver worked before the
fix and works after: `ContentResolver.openInputStream` accepts `file://` fine. The branch was broken
because `MainActivity` passed `uri.getPath()`, discarding the scheme and leaving a bare path to be
parsed as a URI — not because a resolver cannot read a file URI.

**A second correction, from running it (2026-09-05).** The fix is confirmed — the failure moved from
`FileNotFoundException: Unknown URL` to a real filesystem error reached through `FileInputStream`:

```
error 131: could not read the import source
java.io.FileNotFoundException: /sdcard/Download/valid.edb: open failed: EACCES (Permission denied)
```

**But the import still fails, and this finding cannot fix it.** Phase 2 Step 4 removed every storage
permission, so the app may not read an arbitrary `/sdcard` path — which is exactly what a `file://`
URI from another app points at. The branch is **unreachable by design on API 29+**, and no change
inside `openImportSource` alters that.

So the review's own alternative — *"accept that this path is vestigial and delete the branch"* — is
the better answer, which was not visible from reading the code. **Resolved 2026-09-05: the branch is deleted**, on the
maintainer's call. `MainActivity` now refuses any non-`content://` scheme before the overwrite prompt
rather than after it, and the manifest stops advertising `android:scheme="file"` — claiming the
capability and then refusing it would be worse than not appearing in the chooser. See Step 7 of
`docs/history/RECOVERY_PLAN.md`.

**Device check:** `adb shell am start -a android.intent.action.VIEW -d file:///sdcard/Download/x.edb`
— or accept that this path is vestigial and delete the branch instead, which is also a defensible
answer.

## 5. The permission-denied toast fires on every launch

`MainActivity.onCreate:157` calls `NotificationPermission.requestIfRemindersEnabled(this)` every
time. Once the user has denied `POST_NOTIFICATIONS` twice, Android stops showing a dialog and
delivers an immediate denial to the callback, so `onRequestPermissionsResult`
(`NotificationPermission.java:91`) shows "Reminders will not appear…" on every launch and every
configuration change.

The toast itself is right — the Step 5 reasoning that a silent failure here is worse than a message
still holds. Firing it forever is the bug.

**✅ Device check done, 2026-09-05** on the Jelly Star: silent across three relaunches and two
rotations after two denials, then exactly one message when reminders were toggled back on.

**Closed on `recovery`, Step 5.** `requestIfRemindersEnabled` asks at most once per install, using
`shouldShowRequestPermissionRationale` *and* a stored flag — the rationale signal alone is false both
before the first ask and after a permanent denial, so it cannot separate them. `request`, the call
behind the reminder switch, still always asks: turning the switch on is a fresh question. Device
check owed at the final pass.

**Device check:** deny twice, then rotate the screen and relaunch a few times. No toast.

## 6. Toasts from a Dropbox worker thread

`importFileAndVerify` reports failure with `Toast.makeText(...).show()` (and via `fileSetup`'s
"error 87" and `exportDB`'s "error 333"). `DropboxDownload.doInBackground` calls it on a
`DropboxTask` pool thread, which has no Looper, so the toast throws rather than appearing.

Step 6 replaced `AsyncTask` with `ExecutorService` + `Handler` and kept the same
`onPreExecute`/`doInBackground`/`onPostExecute` shape — but `AsyncTask`'s pool had the same
constraint, so this predates the rewrite rather than being introduced by it.

**Closed on `recovery`, Step 3.** `importFileAndVerify` returns an `ImportResult` — `OK`,
`NOT_A_DATABASE`, `NEWER_SCHEMA`, `UNREADABLE`, `ROLLED_BACK` or `RESTORE_FAILED` — each carrying its
own string resource, and the caller presents it. `MainActivity` and `confirmAndImport` toast on the
main thread; `DropboxDownload` carries the message to `onPostExecute`.

Four methods had to stop toasting, not the one named here: `importFileAndVerify`,
`importProvidedFile`, `backupDbToSd` and `fileSetup` are all reachable from a `DropboxTask` pool
thread. `exportDB` keeps its toasts — it is a main-thread dialog callback and nothing calls it from a
worker.

**Confirmed on device 2026-09-05**, against a real Dropbox account. With airplane mode on, a
`UnknownHostException` raised on a `DropboxTask` pool thread surfaced on screen as "Error Backing Up"
— crash buffer empty, process alive, and **zero** `Looper.prepare` exceptions, which is the exact
throw this finding described. The network failed before `importFileAndVerify` was reached, so that
specific line is covered by instrumentation rather than by the device; what the device proves is the
surrounding claim.

**Device check:** turn on airplane mode mid-download, or corrupt the remote `.edb`, and confirm the
failure surfaces as a message instead of a crash.

## 7. Encrypted prefs opened on the main thread, twice per resume

`DropBoxHelper.hasStoredCredential` (`:158`) → `getStoredCredential` → `getEncryptedPrefs`, which
builds a `MasterKey` and opens `EncryptedSharedPreferences`. That is Keystore work, on the main
thread, on every `onResume`/`onCreate` that checks for a credential — and `initializeDropboxV2`
immediately repeats it.

No observed jank on either test phone. It is a latent frame-time cost, not a bug.

**Closed on `recovery`, Step 6.** Cached in a field on `DropBoxHelper`, which is per-activity. A
failed open is not cached — `null` retries next call. Nothing was measured before or after: no jank
was ever observed, which is why this was ranked *Defer*. A latent cost removed, not a bug fixed.

## 8. `warnIfUngranted` has no callers

`NotificationPermission.java:116`. Its javadoc says it is "called after a request the user declined,
and on the path where the system no longer shows a prompt at all". Neither call site exists —
`grep` across `mobile/src` returns only the declaration.

The declined-request case is handled by the toast in `onRequestPermissionsResult` (finding 5). The
auto-denied case is the one finding 5 says fires too often. So the method is a duplicate of live
behaviour, with documentation describing a design that was not wired up.

**Closed on `recovery`, Step 5**, by the second option. `warnIfUngranted` is now the single place
that decides whether to warn, called from `onRequestPermissionsResult` on both paths that reach it,
and the duplicate toast inlined in that callback is gone. It gained the reminders-enabled condition
it needed to be that single place, and its javadoc now describes the call sites it has.

## 9. The `<queries>` CSV filter never matches

`AndroidManifest.xml:31` declares an `<intent>` filter for `ACTION_SEND` + `text/csv` so
`SpreadsheetHelper` can enumerate share targets. But `sendFile` calls `queryIntentActivities` with
the **chooser** intent (`SpreadsheetHelper.java:370`), whose action is `ACTION_CHOOSER`. The filter
does not apply to it, and the `grantUriPermission` loop below grants to nothing real.

Export works anyway, because `createChooser` propagates `FLAG_GRANT_READ_URI_PERMISSION` to whichever
target the user picks. So the loop is dead code and the `<queries>` entry is unused — the Step 1
package-visibility work was correct in principle and aimed at the wrong intent.

**Closed on `recovery`, Step 6**, by the second option. The `grantUriPermission` loop and the
`<queries>` element are both gone, along with two now-unused imports. Confirmed against the merged
manifest rather than the source: the only `<queries>` remaining there is `com.dropbox.android`,
contributed by the Dropbox SDK.

---

## What the review checked and found clean

Worth recording, because these are the parts of Phase 2 most likely to have gone wrong:

- The positional `COLUMN_*` constants against the `CREATE TABLE` column order — including
  `COLUMN_LOG_IMAGE = 5` surviving the Step 4b removal.
- The migration chaining in `onUpgrade` and its throw-on-unregistered-step semantics.
- `PendingIntent` request-code separation and the `FLAG_IMMUTABLE` additions.
- The trampoline removal in `ReminderReceiver`.
- `DropboxTask`'s executor/handler shape against the `AsyncTask` contract it replaced.
- The `authCredentialConsumed` guard added for the repeating-toast fix.
- Every resource deleted with the image feature — no dangling `R.` references.

## What this review did not cover

- It read the diff, not the app. Nothing here is a device observation.
- The package rename is most of the 94 files and was skimmed, not audited. The Step 7 gate — Settings,
  General, and the reminder time picker opening without a crash — remains the evidence that the
  reflection-resolved XML survived.
- No coverage of the items already listed under *Deferred on purpose* in `docs/history/REHABILITATION_PLAN.md`;
  the review was not asked to re-derive them, and findings 7–9 are additions to that list rather than
  duplicates of it.
