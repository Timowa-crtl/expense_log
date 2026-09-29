# Expense Log — Phase 2: Rehabilitation

> **Status as of 2026-09-04** — complete. The `rehabilitation` branch was merged into `main` as
> PR #2 (`a9c7317`); the work below is what shipped, and this document is the record of it.
> **Testing is complete.** Every Verification item that a device could close is closed, on two
> phones; the only one left open is the JVM unit-test source set, which nothing depends on.
>
> **The code review is recorded in `docs/history/REVIEW_FINDINGS.md` and is not addressed here.** PR #2 was
> reviewed before merge and produced nine findings, every one re-checked by hand against the
> source. The decision was to merge Phase 2 as tested rather than edit a branch that carries a
> two-phone verification record, so **two data-loss bugs are open and are the first work after the
> merge**: a newer-schema `.edb` import defeats its own rollback and leaves the app unopenable, and
> the instrumentation suite overwrites the real `ExpenseLogBackup.edb`. Read that file before
> running `connectedDebugAndroidTest` on a phone holding real records.
>
> | Step | State |
> | --- | --- |
> | 1 — PendingIntent, `<queries>`, `allowBackup` | **Done** (`61f10a8`), verified on device |
> | — lint fixes | **Done** (`7f36094`) |
> | 2 — instrumentation test source set | **Done** (`d782580`) |
> | 3 — migration scaffolding | **Done** (`1abc30a`) |
> | 4 — scoped storage | **Done** (`dba40f4`) — **device testing passed 2026-09-02**, see below |
> | 4b — image feature removed | **Done** — maintainer's call after that testing, see below |
> | 5 — targetSdk 35 + notifications | **Done** (`ea67d62`) — **device testing passed 2026-09-03** on two phones, see below |
> | 6 — Dropbox auth and transport | **Done** (`125c44c`) — emulator gate 2026-09-03, **device testing passed 2026-09-04** on two phones, see below |
> | 7 — identity: package rename and branding | **Done** — `de.timowa.expenselog` v2.0, see below |
>
> **`targetSdk` is 35.** The switch this whole phase existed to flip is flipped. Both lint checks
> that were suppressed for it are re-enabled and nothing is suppressed in `mobile/build.gradle` any
> more.
>
> **The Dropbox app key is not in version control.** It is a PKCE client identifier rather than a
> secret — there is no app secret anywhere in this project, and the key ships in every APK — but it
> is kept out of the repository so a fork cannot silently authenticate as this app's Dropbox
> identity, and so it has exactly one home instead of the two hand-edited copies that produced the
> `1`/`l` bug. Set `dropbox.appKey` in `local.properties`; `mobile/build.gradle` generates both
> forms. A build with no key configured still succeeds, with only Dropbox inoperative — verified.
>
> **The last two Verification items closed 2026-09-04.** A reminder survives a reboot — proven
> with `dumpsys alarm` on either side of one, showing a freshly re-armed alarm rather than a
> survivor, with the notification then arriving on both phones — and a logcat was finally read on
> a phone, with an empty crash buffer. Separately, the packaged APK took 5,700 fuzzed events on the
> emulator, 1,800 of them against a seeded 901-record database, with no crashes. See *Final device
> pass*.
>
> **34 instrumentation tests green, lint 0 errors.** Run them with
> `:mobile:connectedDebugAndroidTest` — an emulator AVD named `Pixel_6` (API 33) exists. `CLAUDE.md`
> explains how to drive the Windows toolchain from WSL, including the `cmd.exe` quoting trap.
>
> **Step 4 is verified on real data.** All eleven device checks passed on a Pixel 4a running
> Android 14 (PixelBuilds), including the full export → wipe → import round trip and the
> junk-file-refused guard. The one check that was expected to fail did fail, and the response was
> to delete the feature rather than migrate it — Step 4b.
>
> **Step 5 is verified on real devices.** All twelve checks passed on a Pixel 4a (Android 14) and,
> for the reminder half, again on a Unihertz Jelly Star (Android 13). A reminder fired eleven
> minutes out *and* at 08:00 the following morning; the notification's Settings action opened
> Settings and cleared the notification, which is the trampoline fix proven on hardware. An `.edb`
> exported on one phone imported cleanly on the other. Two cosmetic observations came out of it and
> are listed as open items, neither blocking.
>
> **Step 7 is done and confirmed on a phone: the app is `de.timowa.expenselog`, v2.0.** 78 files
> moved off the original author's namespace, the authorship javadoc headers are gone, and `NOTICE`
> carries both copyrights. Build, lint and all 29 tests are green under the new ID; Settings, the
> General screen and the reminder time picker all opened on the emulator with no crash — the
> reflection-resolved XML that a rename silently breaks. **The one item left for the maintainer —
> export an `.edb` from the old install and import it into the new one — passed on device on
> 2026-09-03.** That was the data migration itself, and it is also an end-to-end test of the Step 4
> work: nothing in Step 7 is outstanding.
>
> **Dropbox app registration is complete as of 2026-09-03.** The app key is recorded in
> `local.properties`, not here and not in the repository — see Step 6. Access
> type Scoped App (App Folder) — the one the existing code assumes — and all four permission scopes
> (the plan's original list was missing one) enabled and submitted. Nothing written into the
> codebase yet.
>
> **Checking the SDK upgrade path before writing code turned up two things worth knowing.** The
> current `dropbox-core-sdk:3.1.5` cannot simply be bumped: the newest major version needs Java 21,
> which this project's Java-17 pin rules out, so the target is the newest 7.x release instead. That
> jump pulls in a second artifact (`dropbox-android-sdk`) and, with it, a Kotlin runtime dependency
> — `CLAUDE.md` calls this repository "no Kotlin," so that is flagged rather than done quietly.
> Neither is a real choice — Dropbox is deprecating the token flow `3.1.5` uses, so there is no
> version that avoids it. **The one genuine decision — `ExecutorService` + `Handler` over
> `WorkManager` for the 7 `AsyncTask` rewrites — is made**, 2026-09-03.
>
> **Step 6's emulator gate passed 2026-09-03.** Implemented, pinned to `dropbox-core-sdk`/
> `dropbox-android-sdk` 7.0.0 (not 7.1.x — see below for why), and exercised end to end — connect,
> upload, auto-backup, list, download, restore — against a real (throwaway) Dropbox account on the
> `Pixel_6` emulator. The app key recorded above had a `1`/`l` transcription error, caught and fixed
> during that testing.
>
> **Step 6 is verified on real devices, 2026-09-04.** Nine of the ten device checks passed on two
> phones — including the one the gate existed for, PKCE's redirect out to a real browser and back
> into the app via `db-…://` — and records genuinely synced from one phone to the other. The tenth
> (a logcat read) was not run in that session, and was done later the same day — see *Final device
> pass*. One small bug turned up — the "Dropbox Authentication Complete"
> toast, and the sync and auto-backup behind it, repeating on every return to Data & sync until the
> app was closed — and it is **fixed**, same day; see the Step 6 section below. A second finding
> came in with it: the sync-conflict dialog offered no way to *confirm* a choice on a small screen,
> because the two timestamps were unstyled list rows rather than buttons. Also fixed, and the fix
> measured with screenshots at three font scales on a 320dp screen. **Both re-checked on device
> 2026-09-04, both phones (Jelly Star included): the toast is gone and login is still remembered,
> and the conflict dialog renders cleanly with all three buttons reachable.**

## Context

Phase 1 is complete and merged (PR #1, `5fa21ec`). The app builds, installs, and launches;
billing, ads, and telemetry are gone; premium features are unconditional. `docs/history/REVIVAL_PLAN.md` is
the record of that work and remains authoritative for the toolchain and for the dependency
upgrades that are deliberately deferred.

Phase 1's success criterion was *it compiles and starts*. That proves the build is alive. It does
not prove the features work, because **none of the remaining defects fail at build time.** They
compile cleanly and fail on a real device, at the moment a user taps Export or a reminder is due.

Phase 2 has a single, testable finish line:

> **`targetSdk` is 35 and every feature still works on a modern device.**

Everything below is either something that breaks when that switch is flipped, or something that
must be fixed before real people trust the app with their financial records.

## Why targetSdk 29 was load-bearing — all five now retired

**Resolved as of Step 5; kept as the record of what the switch actually cost.**

`targetSdk 29` was a declaration to the OS: *apply the Android 10 rules to me.* Android honoured it,
and that leniency was the only reason several subsystems still worked:

- `requestLegacyExternalStorage="true"` was **honoured only below targetSdk 30**. At 30+ it is
  ignored outright and scoped storage applies unconditionally. ✅ *Retired by Step 4 — the flag and
  both storage permissions are gone from the manifest.*
- `PendingIntent` without an explicit mutability flag is legal below targetSdk 31. At 31+ it is an
  immediate `IllegalArgumentException`. ✅ *Retired by Step 1 — all 6 sites carry `FLAG_IMMUTABLE`.*
- Notifications post without runtime permission below targetSdk 33. ✅ *Retired by Step 5 —
  `POST_NOTIFICATIONS` is declared and requested, with a real `onRequestPermissionsResult`.*
- `queryIntentActivities` returns an empty list at 30+ without a `<queries>` declaration. ✅
  *Retired by Step 1.*
- A notification action may not broadcast into a receiver that then starts an activity, at 31+.
  ✅ *Retired by Step 5 — the Settings action is a direct `getActivity`, and `ReminderReceiver` no
  longer starts activities at all.*

So the storage rewrite, the reminder fixes, and the notification permission were not three
independent chores — they were the price of one switch, and all of them had to land before it could
be flipped. **All five are now retired, and `targetSdk` is 35.**

## Sync model: handover, not concurrent writes — keep it

**Settled 2026-08-25, on the maintainer's evidence: the existing sync design stays.** It has been
in daily use across multiple devices for years and works. Recorded here because the code invites a
rewrite that the usage pattern does not justify.

**What it is.** `DropboxSyncCheck` stores Dropbox's `rev` string locally and compares it to the
remote. Matching revs mean nothing to do; a changed local database whose last-known rev still
matches the remote uploads; a remote that moved independently raises a dialog showing both
modification dates and asks the user to choose. Transfer is whole-file — the `.edb` is replaced,
not merged.

**Why that is the right design here.** The devices are used one at a time — finish on the phone,
sync, pick up the tablet later, sync. It is a baton pass, and whole-file replacement implements a
baton pass exactly. The divergence branch is reached only when both devices are edited between
syncs, which in this pattern is rare, and even then it prompts rather than discarding silently.

**Do not "fix" the following.** They are consequences of the model, not defects in it:

- Row IDs are device-local autoincrement (`COLUMN_LOG_ID = 0`). Fine — nothing merges on identity.
- Deletes are hard deletes with no tombstones (7 sites in `DBAdapter`, two of them cascading).
  Fine — a deleted record is absent from the uploaded file and therefore absent after download.
  Resurrection is a *merge* failure mode and there is no merge.
- No per-record modification timestamps. Fine — conflict resolution is per-file and manual.

**The known limit, stated so nobody is surprised.** Records added on two devices *between* syncs
cannot both survive; the dialog picks a whole database. If usage ever shifts to genuine concurrent
editing, the upgrade path is record-level merge — stable UUIDs, per-record timestamps, tombstones,
and a local merge step so the transport stays get-file/put-file. That is a **later phase**, and it
brings its own hazard worth noting in advance: if two devices independently migrate the same legacy
`.edb` and generate UUIDs separately, the first merge duplicates the entire history.

## Provider: stay on Dropbox, do not convert to Google Drive

Evaluated and rejected 2026-08-25. The reasons are about Android integration cost, not about the
existing code:

- **Drive normally reintroduces Play Services.** The standard Google Sign-In + Drive API path pulls
  `com.google.android.gms:play-services-auth`. Phase 1 concatenated all 8 dex files specifically to
  verify zero `com/google/android/gms`. Restoring it reverses a deliberate decision and breaks
  de-Googled devices. AppAuth + Drive REST avoids it, but costs more work than Dropbox's SDK.
- **Per-builder setup is far heavier.** Dropbox is a pasted string. Google needs a Cloud project, an
  OAuth consent screen, and the SHA-1 of the signing key registered per build type — for every
  person who clones the repo.
- **Backups become invisible.** Drive's `appDataFolder` is hidden from the user; `drive.file` is
  visible but app-created-only. Dropbox's App folder is scoped *and* visible in one choice.

Recorded so it is not re-litigated: the sunk cost of the existing Dropbox code is **not** the
reason. Most of it is provider-agnostic orchestration and the auth layer is being rewritten anyway;
Drive's `version` / `headRevisionId` would port the rev model cleanly. Dropbox wins on its own.

## Ordering: why Dropbox is last

Settled up front, because the instinct is to fix the most visibly broken thing first.

**Dropbox is the largest, riskiest, and least testable item — and nothing else depends on it.**

*The table below is the assessment as it stood before Step 6, kept as the record of why the
ordering was chosen. All three of its factors have since been retired — the app key arrived,
the SDK is 7.0.0, and the whole thing was exercised on an emulator and two phones. See Step 6.*

| Factor | Detail |
| --- | --- |
| Size | ~1,840 LOC across 9 files (`DropBoxHelper` + 8 in `dropbox/`), 7 of them `AsyncTask` subclasses |
| Testability | **Zero.** `dropbox_key` is still `db-REPLACE_WITH_YOUR_DROPBOX_APP_KEY`. Nothing can be run or verified until a real app key exists |
| Risk | `dropbox-core-sdk:3.1.5` → current 7.x is a major jump, *and* `Auth.getOAuth2Token()` + long-lived token storage is a deprecated model. Auth is a redesign, not a bump |
| Coupling | Nothing in Steps 1–5 depends on it, and it depends on nothing in them: `dropbox/` uses `getCacheDir()` and internal storage, already scoped-storage-safe. It can move last without blocking anything |

Phase 1 succeeded because every step had a gate that passed or failed. Opening Phase 2 with the
one item that has no gate discards exactly that discipline.

**Correction to a claim in `docs/history/REVIVAL_PLAN.md`'s Phase 2 list:** `DropboxDownload` is named there
among the files rooted at `Environment.getExternalStorageDirectory()`. That grep hit is on a
**commented-out line** (`DropboxDownload.java:127`); the live code uses `mContext.getCacheDir()`
and internal storage, and is already scoped-storage-safe. Dropbox's coupling to the storage
rewrite is to `FileHelper`'s *API* — `importFileAndVerify`, `getInternalDbFile`,
`database_extension`, used in 6 of 8 files — which is churn risk, not breakage.

**Two Dropbox actions belong at the front anyway, and neither is code:**

1. **Register the Dropbox app now** (dropbox.com/developers) and obtain a real key. This is a
   human action with lead time; starting it at Step 6 makes it the blocker instead of a
   prerequisite already satisfied.
2. `allowBackup="false"` lands in Step 1 — see below.

**Step 7 sits outside this argument.** It is numbered after Dropbox but ordered independently of
it: the package rename touches no data path, gates on a decision rather than on a key, and is
better done *before* Step 6 than after, since Step 6 rewrites every file the rename would move.

## A note on references in this document

File and line references were accurate when written and **drift as the code changes** — Step 4 alone
moved most of `FileHelper`. Symbol names (`FileHelper.getAppFilesDir`,
`DBAdapter.applyMigrationStep`) are stable; line numbers are not. Grep for the symbol rather than
trusting a line number, and treat any `file.java:NN` here as a hint about where to start looking.

## Guiding principle: a net before the trapeze

Phase 1's rule was *change only what blocks the build*. Phase 2's is different, because the work
is now genuinely destructive refactoring of the data path:

> **Nothing that can silently corrupt or lose user data gets refactored before there is a test
> that would catch it.**

The storage rewrite touches every path by which records enter and leave the app. Doing it with
zero test coverage — the current state — means the only verification is one person tapping around
once, which is precisely how a revival ships a silent data-loss bug.

---

## Step 1 — Cheap security and correctness fixes

**Done** (`61f10a8`), and verified on a Pixel 4a: a reminder fired, and CSV export reached the share
sheet. Small, independent fixes that close live exposure and pay for part of Step 5 in advance.

- **`allowBackup="false"`**. This swept the app's `SharedPreferences` —
  including the plaintext Dropbox OAuth token — into Google's cloud backup. Currently dormant
  (no key ⇒ no token can exist), but it costs one line and it must not be forgotten before
  Dropbox works again. Alternatively a `dataExtractionRules` file excluding the token key; the
  blunt version is preferable while there is no considered backup story.
- **`PendingIntent` mutability flags** — 6 sites, all in `reminders/`: three in `ReminderReceiver`
  (snooze action, settings action, content intent) and three in `ReminderManager`
  (`disableReminder`, `setupReminderNotification`, `snoozeReminder`). All were
  `FLAG_CANCEL_CURRENT` with no mutability bit. Add `| PendingIntent.FLAG_IMMUTABLE` — none of the
  six needs a mutable intent (all are self-addressed broadcasts and one activity launch with fixed
  extras). This is a guaranteed `IllegalArgumentException` at targetSdk 31+ and is the single
  cheapest item in Phase 2.
- **`<queries>` element**. Two real package-visibility call sites — `ImageChooser`
  (`queryIntentActivities` for camera apps) and `SpreadsheetHelper.sendFile`
  (`queryIntentActivities` for the CSV share chooser). Both return empty lists at targetSdk 30+
  without a `<queries>` declaration, silently breaking receipt photos and CSV sharing. The other
  three `getPackageManager()` hits (`Navigator:254`, `UpdateManager:40,42`) query the app's own
  package and are unaffected. *Step 4b deleted `ImageChooser`; only the CSV `<intent>` remains.*

**Gate:** build, install, set a reminder, confirm it fires. Take a receipt photo. Share a CSV.

## Step 2 — A real test source set

**Done.** `src/androidTest/` restored with `AndroidJUnitRunner`; 17 instrumentation tests across
two classes cover the database layer. `src/test/` keeps its JVM stub.

**The isolation harness is the load-bearing part.** `DBAdapter.DATABASE_NAME` is a fixed constant
and the constructor calls `getWritableDatabase()` immediately, so a test given the real application
context opens the **live database on the device** and writes into it — real financial records, on a
phone in daily use. `IsolatedDatabaseContext` is a `ContextWrapper` that prefixes every database
and `SharedPreferences` name; preferences are covered too because `DBAdapter.databaseChange()`
writes the Dropbox revision key on every mutation. `harness_redirectsAwayFromTheLiveDatabase` asserts
the redirection before anything depends on it. **Passing a raw context to `DBAdapter` in a test is a
data-loss bug, not a style problem.**

- `DBAdapterSchemaTest` locks the positional `COLUMN_*` constants against real column order for all
  four tables, plus column counts. Needs no data — an empty cursor still carries metadata. This is
  the guard that makes the Step 3 migration safe to attempt, because reordering a column is
  otherwise silent: it shifts every later field and the app reads an amount where it expects a
  category id.
- `DBAdapterRoundTripTest` writes through `DBAdapter`'s own API and reads every field back by
  constant, for all four tables, with distinguishable values per field so a transposition of two
  adjacent integer columns cannot pass.

**Sign convention, discovered here and now locked by test.** `amount` is stored as a **positive
magnitude**; the `expenseIncome` flag carries the sign (0 = expense, 1 = income). Nothing in the
schema enforces it — `amount` is a plain `real` — so the convention lives only in the callers.
`getTotalForRange` returns `income - expenses`, and `SpreadsheetHelper.appendSummary` multiplies the
expense sum by -1 for display. Writing a negative amount against an expense flag double-negates and
corrupts every total that touches the row.

**Deferred: the `.edb` round trip.** `FileHelper.importFileAndVerify` **overwrites the live
database** via `importProvidedFile`, and both it and `backupDbToSd` root their paths at
`Environment.getExternalStorageDirectory()`. `getInternalDbFile` resolves through
`DBAdapter`, so the isolation wrapper would cover the database half — but the external-storage half
is exactly what Step 5 replaces with SAF. Writing this test now means writing it twice. It belongs
in Step 5, against the new API, and is listed there as part of that step's gate.

**Gate:** `.\gradlew.bat :mobile:connectedDebugAndroidTest` green on a connected device, and
`:mobile:testDebugUnitTest` green. The instrumentation run needs a device or an API 26+ emulator.

## Step 3 — Database migration scaffolding

**Done.** `DATABASE_VERSION` is still **1** and no schema changed — this step builds the mechanism
so the first real migration is not also the first migration ever attempted. 21 tests green.

**What was wrong.** `onUpgrade` was `if (oldVersion < 1) { }` — empty. Raising `DATABASE_VERSION`
without writing a migration would have left the *old* schema in place while the app believed it had
the new one. With positional column reads that is silent corruption of every existing `.edb`, not a
crash.

- `DBAdapter.migrate(db, old, new)` chains steps registered one version apart, so v1→v3 runs 1→2
  then 2→3 rather than needing an N×M matrix. **An unregistered step throws.** Failing to open is
  recoverable; quiet corruption is not. Safe to throw because `SQLiteOpenHelper` runs `onUpgrade`
  inside a transaction and calls `setVersion` only on success — a failed migration rolls back and
  the database keeps its original version.
- **`onDowngrade` now refuses.** It was empty, which *suppressed* `SQLiteOpenHelper`'s own guard and
  let the app open a database written by a newer schema as though it were current. The realistic
  trigger is importing an `.edb` from a future build.
- **`FileHelper.importFileAndVerify` now recovers from both.** It constructed `DBAdapter` outside
  any `try`, so once opening could throw, the restore would have been skipped — leaving the user on
  an unreadable database with their backup untouched on disk. It now restores on any failure, not
  only on `verifyDatabase()` returning false.
- The append-only column rule is documented at `MAIN_LOG_TABLE_STRUCTURE`, where someone about to
  add a column will actually read it, and enforced by `DBAdapterSchemaTest`.

**Adding a migration later, in one commit:** add the `case` in `applyMigrationStep`, raise
`DATABASE_VERSION`, update `databaseVersion_isStillOneAndHasNoMigrationYet`, and extend
`DBAdapterMigrationTest` with a fixture at the old version. Append columns to the *end* of a
`CREATE TABLE`, never insert.

**SQL audit: no change needed.** The plan flagged concatenated SQL at `DBAdapter.java:173,230,283`.
Traced end to end: every text write goes through `ContentValues`, the one text-valued query
(`doesTagExist`) already binds parameters, the records filter is `ArrayList<ArrayList<Integer>>`,
and every concatenated `WHERE` carries only ints, longs, or compile-time constants. No
user-supplied text reaches SQL by concatenation. Recorded so it is not re-audited.

**Gate met:** `DBAdapterMigrationTest` covers the unregistered-step throw, the chain failing at its
first missing step, the no-op when versions match, a version-1 database reopening with every field
intact, and a v99 database being refused.

## Step 4 — Scoped storage

**Done.** 24 instrumentation tests green, lint clean, no storage permissions left in the manifest.

**The model:** the app writes only to its own directory, and reads user-chosen files through the
system picker. Neither needs a permission at any API level, and neither is restricted by scoped
storage.

- `FileHelper.getAppFilesDir` returns `Android/data/<package>/files`, replacing
  `Environment.getExternalStorageDirectory()` everywhere the app writes. Exports, CSVs and automatic
  backups all live there and are shared onward as `FileProvider` URIs, so the share-sheet flow is
  unchanged. (A sibling `getAppImagesDir` served record images until Step 4b deleted that feature.)
- **Import is now `ACTION_OPEN_DOCUMENT`.** The old hand-rolled dialog listed `.edb` files in a
  shared `/Expense Log/` folder — unreadable under scoped storage, so it would have silently shown
  an empty list. The picker also reaches Drive, Dropbox, and anywhere else a backup lives.
- `WRITE_EXTERNAL_STORAGE` / `READ_EXTERNAL_STORAGE` and `requestLegacyExternalStorage` are gone
  from the manifest. `PermissionHelper` and `activity_permissions.xml` are deleted — the class had
  no `onRequestPermissionsResult` anywhere in the codebase, so it asked and never listened.
- `provider_paths.xml` now maps only the app's own directory, not the whole SD card root.

**Three pre-existing bugs found and fixed here.** All would have survived a pure "make it compile at
targetSdk 35" rewrite:

1. **Import silently corrupted the database.** `importProvidedFile`'s content-URI branch called
   `f.write(buffer)` on a fixed 1024-byte array instead of `write(buffer, 0, len)`, so every short
   read padded the output with stale bytes from the previous iteration. Any `.edb` imported from an
   email attachment or a Dropbox download was damaged, and it only showed up later as an unreadable
   database. `EdbRoundTripTest` streams with a 777-byte buffer specifically to force short reads.
2. **Importing a non-database file wiped the records and reported success.** The order was
   overwrite-then-validate, which cannot work: when `SQLiteOpenHelper` opens a corrupt file,
   `DefaultDatabaseErrorHandler` *deletes it and creates a fresh empty database*, so verification
   passed against the new empty schema and the restore never ran. Now the candidate is staged in the
   cache directory, opened **read-only** with a no-op error handler, and checked for the app's
   tables before anything touches the live database. Caught by a test, not by reading.
3. **Automatic local backups never ran** for anyone who declined the storage permission
   (`NewLogFragment`), a silent skip of the app's own safety net — gated on a permission the backup
   path no longer needs.

Plus one that made receipt photos fail outright: `ImageChooser` passed `Uri.fromFile()` in
`EXTRA_OUTPUT`, which throws `FileUriExposedException` for any app targeting API 24+. It was
rewritten to hand the camera a `FileProvider` URI, and ~70 lines of
`MediaStore.Images.Media.DATA` path resolution gave way to streaming through `ContentResolver`.
Device tests 8 and 9 confirmed both halves worked. **Step 4b then deleted the feature outright**
on the maintainer's call, so this fix no longer ships — recorded because it explains what those two
device tests were exercising, and why the decision was made on the feature's usefulness rather than
on it being broken.

**Known trade-off, accepted deliberately — and it has two halves, not one.**

1. *The directory is deleted on uninstall*, so automatic backups no longer survive one.
2. *The directory cannot be browsed.* Since Android 11 a file manager — Google Files included —
   may not enumerate anything under `Android/data/`, for any app. The folder appears, and it
   appears empty. This tripped device test 10 (see below): the backups were there, and no tool the
   user had could show them.

Together these mean **an automatic backup protects against mistakes made inside the app, and
nothing else.** Surviving an uninstall, or being retrievable by hand, is what the manual `.edb`
export and Dropbox are for, and that is the story to tell users.

Restoring a user-visible location means one of: `ACTION_OPEN_DOCUMENT_TREE` with a persisted grant
(no manifest permission at any API level, user picks the folder, one setup prompt); or MediaStore
`Documents`/`Downloads` (no setup, but `MediaStore.Downloads` is API 29+, so `minSdk 26` would need
`WRITE_EXTERNAL_STORAGE` back with `maxSdkVersion="28"`). **Weighed on 2026-09-02 and declined** —
the manual export already covers retrieval, and neither option is worth its cost right now.
Documented rather than built; revisit if the manual export proves too easy to forget.

### ✅ Device testing for Step 4 — passed 2026-09-02

Run by the maintainer on a **Pixel 4a, PixelBuilds Android 14**, against a phone holding years of
real records. The 24 instrumentation tests run against a throwaway emulator database created
seconds earlier; they prove the code is self-consistent and nothing more. This is the evidence that
Step 4 works on real data.

| # | Action | Result |
| --- | --- | --- |
| 1 | Settings → Data & Sync → **Export Database** | ✅ Confirm dialog, then the system writer; wrote `expenseLog.edb` to Google Drive |
| 2 | Settings → Data & Sync → **Import Database** | ✅ The **system file picker** opens, not the old in-app list |
| 3 | Import the file from 1 **after deleting all local data** | ✅ Fully restored — every record, tag and account |
| 4 | Import a **junk file** renamed to `.edb` | ✅ "Error Importing", **all data still present** — the validate-before-overwrite guard holds |
| 5 | Import an `.edb` produced by the **original app version** | ✅ Succeeds |
| 6 | Records → ⋮ → **Export** (CSV) | ✅ Exported, rows correct |
| 7 | Open an **old record with an image** | ❌ "Picture not found" — expected; see Step 4b |
| 8 | New record → attach image → **camera** | ✅ Captures and displays |
| 9 | New record → attach image → **gallery** | ✅ Copies in and displays |
| 10 | Add a record, then check the automatic backup | ⚠️ Appeared to fail; **it did not** — see below |
| 11 | Reminders → daily at 10:00 | ✅ Fired daily for more than 7 days |

**Test 3 and test 4 are the two that mattered.** Both bugs Step 4 fixed were data-destroying and
neither was visible by reading the code: the short-read import corruption and the
overwrite-then-validate ordering that let a junk file wipe the database while reporting success.
Test 3 wiped the phone deliberately and restored from a file that had made a round trip through
Google Drive. Test 4 fed it garbage. Both behaved.

**Test 10 was a false alarm, and the reason is worth keeping.** The report was that
`Android/data/` looked completely empty in Google Files, while `Local Storage/Expense Log` still
held old Daily/Weekly/Monthly `.edb` files but nothing recent. Every part of that is what *success*
looks like from a file manager on Android 14:

- Since Android 11, a file manager may not enumerate `Android/data/<pkg>/`. The restriction is
  enforced against Google Files, not against Expense Log. The folder shows, and it shows empty.
- The old files in `/Expense Log/` are pre-Step-4 leftovers. Step 4 stopped writing there and
  deletes nothing, so "old ones present, no recent one" is the signature of the move itself.

**Do not diagnose this from a file manager.** `adb shell` is not subject to the restriction:

```
adb shell ls -l /sdcard/Android/data/de.timowa.expenselog/files/
```

The real finding underneath the false alarm is not a bug but a limitation, and it is written up in
the trade-off note at the end of Step 4 above: automatic backups are now both invisible and
uninstall-fatal. That was weighed on 2026-09-02 and left as is.

**Test 7 failed as predicted, and the response was to delete the feature.** See Step 4b.

## Step 4b — the image feature is removed

**Decided by the maintainer on 2026-09-02, after device test 7:** *"We disable the image feature,
it is buggy and not useful."* Tests 8 and 9 showed that attaching a *new* image worked fine after
Step 4; the feature was dropped on its merits, not because it was broken.

This **retires the image path migration** that Step 5 was blocked on. There is no longer a deadline
to copy files out of `/Expense Log/Images/` before targetSdk rises.

**Scope: the UI is gone, the data is untouched.**

Removed — `ImageChooser` (the class, its manifest entry, its `<queries>` `IMAGE_CAPTURE` intent,
and its `ActivityComponent.injectActivity` method); the attach row, preview, and tap-to-zoom in
`NewLogFragment`; the camera badge and zoom in `LogListFragment` and `LogsCalendarFragment`; the
`Log Images` preference and its key, default and strings; `FileHelper.getAppImagesDir`;
`Utility.isImage`; `PrefManager.isImageLoggingEnabled`; `res/drawable/no_image.jpg`; the
`Theme.Transparent` style; and the now-orphaned `ImageView`s in five layouts.

Untouched, deliberately:

- **`DBAdapter.COLUMN_LOG_IMAGE = 5` stays in the schema.** Callers read cursors by *positional*
  int constants, so dropping the column would shift every constant after it and silently corrupt
  every read — and it would need a migration. Removing a feature is not worth touching stored data.
- **Existing rows keep their stored paths**, and the `.jpg` files stay on the SD card. Nothing was
  deleted; anyone who wants those receipts can still find them.
- `LogItem.getImageUri`/`setImageUri` survive to carry the column through the DB round trip.

**The one trap this created, and how it is handled.** `NewLogFragment` used to read the image path
out of the (now deleted) `EditText` when building a `LogItem` to save. Left alone, opening an old
record with an image and pressing save would have **blanked its stored path** — quiet data loss, in
a change whose whole point was not to touch data. The path is now loaded from the cursor into an
`existingImageUri` field and written straight back out. Look there first if that column ever starts
emptying itself.

**Verified:** `assembleDebug` + `lintDebug` green (lint 0 errors), `assembleDebugAndroidTest`
compiles, and the concatenated dex contains no `ImageChooser`, `getAppImagesDir`,
`isImageLoggingEnabled`, `zoomImageFromThumb` or `no_image`.

**Do not read anything into APK size here.** A clean build of this commit produces 6.13 MB and an
incremental build of the next one produces 6.68 MB, but unzipping both shows the same entries and
2,836 bytes of difference in uncompressed content — the gap is entirely how the packager compressed
it, not payload. Removing the feature did not meaningfully shrink the app, and it was never going to:
the deleted code is small and `no_image.jpg` was 25 KB. Use the dex evidence above, not the file
size, to prove something is gone.

## Step 5 — targetSdk 35 and notifications

**Done, and verified on two real devices on 2026-09-03.** The switch this whole phase existed to
flip is flipped: `targetSdk 29` → `35`.

### What changed

- **`targetSdk 35`** in `mobile/build.gradle`. Everything else in this step is a consequence.
- **`POST_NOTIFICATIONS`**, declared in the manifest and requested at runtime by the new
  `reminders/NotificationPermission`. From API 33 a reminder cannot post without it, and `notify()`
  fails *silently* — no exception, no log line, nothing to look at. Asked in two places: on launch
  from `MainActivity`, but only when reminders are actually switched on, and at the moment the user
  turns the reminder toggle on in `SettingsActivity`.
- **A real `onRequestPermissionsResult`**, in both `MainActivity` and `SettingsActivity`. This is
  the app's first one ever. `PermissionHelper` was deleted in Step 4 precisely because it asked for
  a permission and never implemented the callback, so a denial produced silence; a denial here
  produces a message saying reminders will not appear.
- **The notification trampoline is gone.** The Settings action was a `getBroadcast` into
  `ReminderReceiver`, which then called `startActivity`. At targetSdk 31+ the system drops that
  call, so the button did nothing at all. It is now a direct `getActivity` into `MainActivity`,
  whose existing `checkForNotificationFlags` already knew how to route `ACTION_SETTINGS`.
- **Both suppressed lint checks are re-enabled** and the `lint { disable ... }` block is gone.
  `ExpiredTargetSdkVersion` resolved itself with the bump; `NotificationTrampoline` was a real
  defect and is fixed above.

### Three things worth knowing, found while doing it

**Exact alarms needed nothing.** The plan carried "check exact-alarm policy for `reminders/`" as an
open question. The answer is that there is no work: every alarm the app schedules is inexact
(`setRepeating`, and `set` in `snoozeReminder`), and `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` gate
only `setExact`, `setExactAndAllowWhileIdle` and `setAlarmClock`, none of which appear anywhere.
The app must **not** gate on `canScheduleExactAlarms`. Written into `ReminderManager` as a comment
so the question is not reopened.

**Removing the trampoline was not enough on its own; the receiver had to stop starting activities
at all.** The first attempt kept the old `ACTION_SETTINGS` branch, so an already-posted notification
carrying a stale `PendingIntent` would still do something. Lint promptly failed the build — on the
**snooze** action, which is a perfectly legal background broadcast. Lint's rule is about the
receiver, not the intent: any `startActivity` inside `ReminderReceiver` makes *every* broadcast
`PendingIntent` into it a trampoline. The branch was also dead on its own terms, since the system
drops that `startActivity` at targetSdk 31+ regardless. It now only dismisses the notification.

**The Settings action and the content intent nearly collapsed into each other.** Both target
`MainActivity` and differ only in an extra — and `PendingIntent` identity ignores extras. Sharing
request code `0`, as the original code did, would have made the Settings button open whatever the
other one pointed at. They now have distinct request codes, and a test pins it.

### The five new instrumentation tests

`ReminderNotificationTest` posts a real reminder and asserts on the notification the app produced.
It has to assert on the *shape* of the `PendingIntent`s rather than on behaviour, because the
behaviour being guarded against is nothing happening at all.

| Test | Guards against |
| --- | --- |
| `settingsAction_launchesAnActivityDirectly` | the trampoline coming back |
| `snoozeAction_staysABroadcast` | a "fix everything to getActivity" sweep turning background work into a launched screen |
| `settingsActionAndContentIntent_areDistinctPendingIntents` | the request-code collapse above |
| `reminder_postsANotificationWithBothActions` | the notification silently not posting |
| `permissionHelper_reportsTheGrantItWasGiven` | `NotificationPermission` disagreeing with the system |

**The trampoline test was mutation-checked**: reintroducing `getBroadcast` fails it with the
intended message, so it is not vacuously green. Worth repeating for any test written to pin a
silent failure.

### What the emulator proved, and what it cannot

Verified on the `Pixel_6` AVD (API 33 — the level where `POST_NOTIFICATIONS` starts to apply):
the permission prompt appears on first launch; denying it is handled without a crash; granting it
lets the reminder post with both actions; the Settings path lands on the settings screen; `Export
Database` writes a valid 32 KB `.edb` containing the expected rows under *enforced* scoped storage;
every fragment loads with zero `FATAL` in logcat.

It cannot prove the things that need real time, real providers, or a real phone — which is why the
step had a device gate. That gate is now closed; see below.

### ✅ Device testing for Step 5 — passed 2026-09-03

Run by the maintainer on a **Pixel 4a, PixelBuilds Android 14**, and the reminder half again on a
**Unihertz Jelly Star, Android 13**. All twelve checks passed. This is the evidence the step was
gated on, because everything Step 5 can get wrong, it gets wrong silently: a missing notification
permission makes `notify()` a no-op, and a dead notification action still posts a notification that
looks perfect. The 29 instrumentation tests prove the app builds the right objects and the emulator
proved the mechanisms; neither can watch a clock overnight.

**The Pixel 4a was wiped first** — old app uninstalled, all prior data cleared — so this was a
*fresh* install restored from an `.edb`, not an in-place upgrade. That was the maintainer's
deliberate choice: what matters is that the current app works, and update stability is not a
property anyone here depends on. It changes what test 1 proves; see the note below the table.

| # | Action | Result |
| --- | --- | --- |
| 1 | Install, open the app once | ✅ Opens normally. Notification prompt appeared and was granted — the fresh-install branch, see below. All records present after importing the `.edb` |
| 2 | Settings → General → Reminders | ✅ A new reminder could be set as before |
| 3 | **Wait for the reminder to fire** | ✅ Fired at +11 min, **and again at 08:00 the next morning**. Both also on the Jelly Star |
| 4 | Tap the notification **body** | ✅ Opens the app as expected |
| 5 | Tap the notification's **Settings** action | ✅ Notification cleared, Settings opened |
| 6 / 6b | Tap **Snooze**, then wait | ✅ Notification cleared; the reminder came back an hour later |
| 7 | Turn reminders **off**, then **on** again | ✅ No crash, and the next reminder fired on both phones — at ~15 min for an 11-minute setting on the Jelly Star, which is the inexact alarm behaving as designed |
| 8 | **Export Database** → send it somewhere | ✅ Exported on the Pixel 4a and **imported on the Jelly Star**, all data present |
| 9 | **Import Database**, pick that file | ✅ System picker opens; imported a backup held on Google Drive |
| 10 | Import a **junk file** renamed to `.edb` | ✅ "Error Importing", records untouched — after about 20 seconds, see below |
| 11 | Records → ⋮ → **Export** (CSV) | ✅ Exported; filename mangled by the receiving app, see below |
| 12 | Walk List, Graph, Calendar and Summary | ✅ No issues |

**Test 3 and test 5 are the two that mattered**, and both are the reason a device gate existed.
Test 3 is the only thing that can prove `POST_NOTIFICATIONS` is actually held and an inexact alarm
survives a night of doze — an emulator run over ten minutes cannot. Test 5 is the trampoline fix on
hardware: the same tap did nothing at all before the fix, and looked identical while doing it.

**Test 1 took the fresh-install branch, so the upgrade branch stays emulator-only.** The prompt the
maintainer saw is correct behaviour for a clean install. The documented no-prompt-on-upgrade result
above was measured on the emulator and remains untested on a phone — deliberately, per the scope
note. Anyone updating an existing install should still expect no prompt, and should still open the
app once afterwards to re-arm the alarms.

**The deliberate-denial check was not run.** Turning notifications off in system settings and
watching a reminder fail to arrive, then seeing "Reminders will not appear until notifications are
allowed for Expense Log" on the next in-app toggle, is still only verified on the emulator. It is
the difference between a feature that is off and a feature that is broken, and it is worth doing
once — but nothing depends on it and Step 5 is not held open for it.

#### Two observations that are not failures

**The junk file took ~20 seconds to be refused.** The guard did its job — records untouched — but
the delay is real and has a cause worth writing down. `FileHelper.importFileAndVerify`
(`FileHelper.java:344`) stages the *entire* picked document into `getCacheDir()` before it inspects
a single byte, and it does so on the main thread, called straight from the confirm dialog's click
handler. When the picked file lives on Google Drive, the SAF download happens inside that copy.
There is no progress indicator, and a large enough file would ANR. **The ordering must not change**
— staging and validating before touching the live database is exactly the fix Step 4 made after
that ordering destroyed a database. What should change is where it runs: background thread, spinner,
and optionally a cheap header check on the first bytes so obvious junk is rejected before the whole
file is fetched. Listed under *Deferred on purpose*.

**The exported CSV arrived as `All Expense Log Records Sept. 3, 2026. 3, 2026.csv`.** The file the
app writes is always `Expense Log.csv` (`FileHelper.csvFileName`); that string is the share
`EXTRA_SUBJECT` built in `SpreadsheetHelper.createSpreadsheet`, and the receiving app derived a
filename from it. The subject contains `.` and `,`, and the receiver mistook part of it for an
extension. Cosmetic — the contents are correct and no data path is involved. The fix is to make the
subject filename-safe, an ISO date rather than a localised one with dots in it. Also under
*Deferred on purpose*.

#### How it was run, kept for whoever repeats it

**Build the APK** (see `CLAUDE.md` for the WSL→Windows invocation):

```
gradlew.bat :mobile:assembleDebug
```
→ `mobile/build/outputs/apk/debug/mobile-debug.apk`, ~6.7 MB.

Installing over an existing debug build preserves the database — same signing key, same application
id. **Export an `.edb` and get it off the phone first** either way: several of the tests exercise
import.

**Two things that look like failures on an in-place upgrade and are not.** Both were checked on the
emulator by installing a targetSdk 29 build and upgrading it, so they are measured, not assumed:

1. **No permission prompt appears on upgrade.** `POST_NOTIFICATIONS` is auto-granted when an app
   whose notifications already worked is updated to target 33+. The prompt appears only on a fresh
   install, after clearing app data, or if notifications had been switched off.
2. **Open the app once after installing, before expecting any reminder.** Replacing a package
   cancels its pending alarms. `MainActivity.onCreate` → `LaunchManager.appStartup` →
   `ReminderManager.updateReminder` re-arms them, so one launch is all it takes — but a phone that
   is updated and never opened will not remind anyone of anything.

**How to avoid a three-day test.** Tests 3, 5 and 6 each need a reminder to fire, and the reminder
is daily. Set the reminder time about **10 minutes ahead** in Settings → General → Reminders → time;
saving it calls `ReminderManager.updateReminder`, which re-arms immediately. Do not cut it finer:
`setupReminder` pushes the first reminder to *tomorrow* if the configured time is under five minutes
away, and the alarm is inexact, so it may land late — the Jelly Star's 11-minute setting arrived at
about 15 minutes. Repeat for each test that needs a fresh notification, and set the time back
afterwards.

**Test 5 needs a notification posted by the *updated* build.** One already sitting in the shade from
before the update carries the old `PendingIntent`, routes through `ReminderReceiver`, and is dead.
That is expected and is not what is being tested — swipe stale notifications away first.

## Step 6 — Dropbox

**The app key arrived 2026-09-03.** It is set as `dropbox.appKey` in `local.properties`, which is
gitignored; `mobile/build.gradle` generates both forms the code needs from it, and the literal key
appears nowhere in this repository. App name "Expense Log 2" ("Expense Log" was
taken by the original author's listing), app folder `expense_log`, access type confirmed as
**Scoped App (App Folder)** — the correct one; every path in `dropbox/` is scope-relative
(`listFolder("")`, uploads to `"/" + filename`) and assumes exactly this. Not yet written into the
codebase — recorded here only, per the maintainer's instruction not to start on the code yet. This
is a client identifier for a PKCE mobile flow, not a secret; no app secret is used anywhere in this
step.

**Permissions not yet set. Enable four scopes, not three — the plan undercounted by one.**
Auditing every `dbxClientV2.files().…` call actually made across all 7 classes in `dropbox/` (there
is no other Dropbox surface — no `sharing()`, no `users()`):

| Scope | Why | Called from |
| --- | --- | --- |
| `files.metadata.read` | `list_folder`, `search_v2` | `DropboxGetBackups`, `DropboxSyncCheck`, `DropboxAutoBackups` |
| `files.metadata.write` | `delete` | `DropboxDelete` — **missing from the original three-scope list below; delete is a metadata-only endpoint under Dropbox's own scope model, not covered by `files.content.write`** |
| `files.content.read` | `download` | `DropboxDownload` |
| `files.content.write` | `upload` | `DropboxUpload`, `DropboxNewBackup`, `DropboxAutoBackups` |

Enable all four on the Permissions tab and click **Submit** — Dropbox requires that explicit step;
checking boxes without submitting leaves the app without the scopes at authorization time, and the
first API call then fails with a scope error rather than an auth error, which is a confusing thing
to debug after the fact.

✅ **All four submitted 2026-09-03.** App registration is complete: key, access type and
permissions all confirmed. Implementation has not started.

### What checking the SDK upgrade path turned up, before writing any code

**The current dependency, `dropbox-core-sdk:3.1.5`, is nine major versions behind, and simply
bumping the version number is not available — the newest line requires a Java jump this project
is not making.** `com.dropbox.core:dropbox-core-sdk` is at major version 8 (8.0.x), and 8.0.0
requires **Java 21+**. `sourceCompatibility`/`targetCompatibility` are pinned to 17 in
`mobile/build.gradle`, deliberately, per `docs/history/REVIVAL_PLAN.md`'s toolchain table — this step does not
touch that. **The version to target is the newest 7.x release** (the last line built for Java 8–20),
checked for its actual latest patch at implementation time rather than pinned here from memory.

**Upgrading past `3.1.5` adds a Kotlin runtime dependency, which is worth the maintainer knowing
about before it happens rather than after — `CLAUDE.md` currently states "no Kotlin" as a fact
about this repository.** Two changes, both consequences of the version jump rather than a choice
between alternatives:

1. **`com.dropbox.core.android.Auth`** — the class `DropBoxHelper` already calls for
   `startOAuth2Authentication` / `getOAuth2Token`, and will call for `startOAuth2PKCE` after the
   rewrite — now ships in a **separate artifact**, `dropbox-android-sdk`, versioned alongside
   `dropbox-core-sdk`. Both need declaring in `mobile/build.gradle`; one alone will not resolve.
2. **That artifact's Android code has been Kotlin since 5.4.x**, and Kotlin is a *runtime*
   dependency of it — `org.jetbrains.kotlin:kotlin-stdlib` has to be declared explicitly if nothing
   else in the project already pulls it in, or the app throws at runtime the first time that code
   path runs. No Kotlin source is added to this repository; the dependency graph gains a Kotlin
   library it did not have before.

There is no version of the current SDK line that avoids this — Dropbox is deprecating the
long-lived tokens `3.1.5`'s flow depends on, so staying on `3.1.5` is not a real alternative either.
Noted so it is a known consequence rather than a surprise mid-implementation; no decision is needed
unless the maintainer objects to the dependency.

**One decision that had real alternatives: what replaces the 7 `AsyncTask` subclasses — decided
2026-09-03.** Nothing else in the codebase uses `ExecutorService`, `Thread`, or `WorkManager`, so
there was no existing convention to follow. ✅ **`ExecutorService` + `Handler(Looper.getMainLooper())`**,
over `WorkManager` — matches how minimal the rest of the app's dependency footprint is, needs no new
library, and a foreground upload triggered from a visible Activity does not need the process-death
survival `WorkManager` would add on top.

- Replace the app key placeholder, which lives in **two places in different formats** and is easy
  to half-update. `settings_keys.xml` (`dropbox_key`) holds
  `db-replace_with_your_dropbox_app_key` **with** the `db-` prefix — it is the OAuth redirect
  scheme, consumed by the `AuthActivity` intent-filter's `<data android:scheme="@string/dropbox_key" />`
  in the manifest. `DropBoxHelper` (`KEY_DB_KEY`) holds the same placeholder **bare** and passes it
  to `Auth.startOAuth2Authentication`. Updating one and not the other fails as a browser handoff
  that never returns, or an app Dropbox does not recognise. **Both must be lower-case** — lint's
  `AppLinkUrlError` rejects an upper-case URI scheme, which is why the placeholder was lowered in
  `7f36094`.
- ✅ Registered with **App folder** access, not Full Dropbox: every path in `dropbox/` is
  scope-relative (`files().listFolder("")`, uploads to `"/" + filename`, `searchV2` on the
  extension), so the code works unchanged under the narrower scope. **Scopes needed: four, not
  three** — see the corrected table above, which adds `files.metadata.write` for `DropboxDelete`.
  All four must be granted *before* an account is linked.
- SDK 3.1.5 → **the newest 7.x release, not the current 8.x** — see the corrected note above for
  why (Java 21 vs. this project's Java-17 pin) and for the two consequences that come with it (a
  second artifact, `dropbox-android-sdk`, and a new Kotlin runtime dependency). Migrate
  `Auth.getOAuth2Token()` to PKCE with short-lived tokens plus refresh
  (`DropBoxHelper.initializeDropboxV2` / `completeDropboxV2Init`).
- Store the refresh token in `EncryptedSharedPreferences`, not plain prefs — today it goes to the
  default `SharedPreferences` under `pref_key_dropbox_oath_token` in clear text.
- The 7 `AsyncTask` subclasses in `dropbox/` get rewritten as part of this, since `AsyncTask` is
  deprecated and the auth rewrite touches all of them anyway. ✅ Onto `ExecutorService` +
  `Handler(Looper.getMainLooper())` — see the decision above.

**Scope limit: auth and transport only.** `DropboxSyncCheck`'s rev comparison, the collision
dialog, and the backup rotation in `DropboxAutoBackups` are working logic and stay as they are —
see the sync-model decision above. This step swaps out how the app authenticates and how bytes
move, and changes nothing about when or what it decides to sync.

**Gate:** connect an account, upload, list backups, download, restore, and auto-backup — on device.

### ✅ Implementation and emulator gate — passed 2026-09-03

**Two problems turned up only once real code hit a real Dropbox app, both fixed the same day.**

1. **The recorded app key had a transcription error** — a `1`/`l` misread, which is exactly the
   character pair a human copying a key by eye gets wrong. Dropbox's server returned "This app is
   not valid" until the maintainer pasted the key directly from the App Console URL. Fixing it
   meant editing *three* places that each held their own copy: this document,
   `settings_keys.xml` and `DropBoxHelper.java`. That is why the key now has a single home in
   `local.properties` with both forms generated from it — see the note at the top of
   `mobile/build.gradle`.
2. **`dropbox-core-sdk`/`dropbox-android-sdk` 7.1.x, not just any 7.x, requires `compileSdk 36`**
   (`minCompileSdk=36` in their AAR metadata, enforced by AGP at build time) — a requirement this
   project doesn't meet, per `docs/history/REVIVAL_PLAN.md`'s pinned toolchain. **Pinned to 7.0.0 instead**,
   confirmed by decompiling both jars that `Auth`/`DbxCredential`/`DbxClientV2`'s API is identical
   between 7.0.0 and 7.1.1 — nothing in this step depends on the newer patch. 7.0.0 depends on the
   older split `kotlin-stdlib-jdk7`/`-jdk8` artifacts, which duplicate classes already merged into
   `kotlin-stdlib` 1.8.22 (pulled in transitively via `androidx.collection`), so those two are
   excluded on the `dropbox-android-sdk` dependency in `mobile/build.gradle`.

**Gate, run on the `Pixel_6` emulator (API 33) with a throwaway Dropbox account created for this
testing session:**

- ✅ **Connect an account** — toggling Dropbox Synchronization in Data & sync launched
  `Auth.startOAuth2PKCE`, which correctly assembled `code_challenge`, `code_challenge_method=S256`,
  `token_access_type=offline`, `response_type=code`, and all four scopes. Login (including an email
  verification code, since this was a brand-new account) completed with the app's own "Dropbox
  Authentication Complete" toast. `dropbox_secure_prefs.xml` (the `EncryptedSharedPreferences` file)
  was written, confirming the `DbxCredential` was persisted off the plain-text token store.
- ✅ **Upload** — `DropboxSyncCheck`'s first-run "no remote" branch fired automatically post-auth
  and uploaded the main database.
- ✅ **Auto-backup** — `autoBackup()`, also triggered automatically post-auth, created a second
  ("weekly") file in the same run.
- ✅ **List backups** — "View existing Dropbox backups" showed both files.
- ✅ **Download and restore** — "Import" on a listed backup completed with no exception in logcat;
  `pref_key_current_dropbox_db_version` and `last_known_dropbox_rev` in the app's own prefs updated
  to the real Dropbox file rev returned by the download.
- No `FATAL EXCEPTION`, no `AndroidRuntime` crash, anywhere in logcat across the whole session.

`DropboxDelete` (used by the list screen's Delete action) was not separately exercised — it isn't
named in the gate above, and its scope (`files.metadata.write`) was already confirmed correct at
registration time.

### ✅ Device testing for Step 6 — passed 2026-09-04, one small bug found

**Why this was a separate gate from the emulator pass above, matching Steps 4 and 5.** The emulator
proved the code path — request shape, credential storage, the upload/list/download/restore round
trip. What it could not prove: the emulator's browser is `WebView Browser Tester`, not the Chrome
(or Dropbox app) a real user has, and the PKCE flow's redirect back into the app via the `db-…://`
custom scheme — the exact mechanism `AuthActivity`'s intent-filter exists for — is precisely the
kind of thing that behaves differently outside an emulator's synthetic browser.

Run by the maintainer **on two real phones**, against a phone holding real records — an in-place
run, not a fresh install — and with the second device used to prove that records actually travel.
That two-device run is worth more than the checklist asked for: it exercises the handover sync
model end to end rather than just the API calls.

| # | Action | Result |
| --- | --- | --- |
| 1 | Settings → Data & sync → toggle **Dropbox Synchronization** on, with **real existing records** on the phone (not a fresh install) | ✅ `.edb` files are written, and entries synced **from device 1 to device 2** |
| 2 | Complete login in the real browser or the Dropbox app if installed, including 2FA if the account has it | ✅ Login worked on both devices |
| 3 | Confirm the redirect lands back in Expense Log (the `db-<app key>://` scheme), not stuck in the browser | ✅ |
| 4 | Confirm the **first upload** happens automatically post-auth (no remote yet → upload) | ✅ |
| 5 | Settings → Data & sync → **Create a manual backup on Dropbox** | ✅ |
| 6 | Settings → Data & sync → **View existing Dropbox backups**, confirm the list is correct | ✅ List is correct, **including with two devices creating backups** |
| 7 | Import (restore) one of the listed backups, confirm the warning dialog and that local records end up correct afterward | ✅ |
| 8 | **Force-stop the app and relaunch it.** Confirm it does *not* ask you to log in again — `hasStoredCredential()` should find the `EncryptedSharedPreferences` entry and `initializeDropboxV2()` should rebuild the client from it | ✅ No re-login |
| 9 | Turn **Dropbox Synchronization** off, then back on. Confirm it asks you to log in again — `clearStoredCredential()` should have removed the stored credential | ✅ Re-login asked for, as expected. **But**: a repeating "Dropbox Authentication Complete" toast — see below |
| 10 | `adb logcat` clean of `FATAL EXCEPTION` / `AndroidRuntime` crash throughout | ⏭️ **Not run** — the maintainer was not comfortable attaching `adb` to these phones during this session |

**Test 1 is the one that mattered, and it went further than the checklist.** The redirect through a
real browser (test 3) is what the whole gate existed for, and it is also the thing most likely to
be broken by a device's default-browser or Dropbox-app configuration. Two different phones took the
same path, and the records themselves made the trip.

**Test 10 was not run in this session and the crash evidence was therefore weaker than in Steps 4
and 5.** Nothing observed suggested a crash — every action completed and the app stayed up across a
two-device session — but "no `FATAL EXCEPTION` in logcat" was not on record for hardware. The
emulator gate above did have it. *Closed later the same day*: the maintainer attached `adb` to the
Pixel 4a and the crash buffer was empty. See **Final device pass**; Verification item 6 is ticked.

#### The bug test 9 found: the auth-complete toast repeats, and so does the work behind it — fixed

**Observed:** after a fresh Dropbox login, the "Dropbox Authentication Complete" toast reappears
*every* time the maintainer returns to **Data & sync** — after locking and unlocking the screen,
after going into "View existing Dropbox backups" and back. It stops after the app is closed once.

**Cause, confirmed by decompiling `dropbox-android-sdk` 7.0.0.** `Auth.getDbxCredential()` does not
consume anything: it reads the static field `AuthActivity.result` (an `Intent`) and rebuilds a
`DbxCredential` from its extras on every call. That static lives as long as the *process*, which is
exactly why closing the app clears it and a screen-off does not. `SettingsActivity`'s sync-fragment
`onResume` (`SettingsActivity.java:460`) then calls `completeDropboxV2Init()` **unconditionally**,
so every resume re-enters the whole post-auth branch.

**The toast is the visible part, and it is the least of it.** Each repeat also re-stores the
credential into `EncryptedSharedPreferences`, rebuilds `DbxClientV2`, re-runs a full
`onDropboxAction(KEY_DROPBOX_SYNC)`, and re-runs `autoBackup()` — network work, on every return to
a settings screen, for the rest of the process's life. No data-loss path: the sync is the same
handover logic that runs anywhere else, and `autoBackup()` still consults its own
"already backed up recently" guard. It is wasted work and a confusing toast, not corruption.

**`MainActivity` did not have this bug** — its call at `MainActivity.java:275` is already guarded
by `!dropBoxHelper.hasStoredCredential()`. The settings fragment was the one place that called it
bare.

**Fixed 2026-09-04 by making the method itself idempotent**, rather than by copying `MainActivity`'s
guard into the second call site, so a third caller cannot reintroduce it. `DropBoxHelper` now
carries a static `authCredentialConsumed`, set when a credential is picked up and cleared **only**
where a new auth flow is started, in `initializeDropboxV2()`. `completeDropboxV2Init()` returns
early when it is set.

**Clearing it in `clearStoredCredential()` would have been the wrong place, and that is the
interesting part.** Turning sync off removes the credential from `EncryptedSharedPreferences` but
cannot remove the SDK's static copy — so a flag reset there would let the *next* resume of Data &
sync read the stale `AuthActivity.result` and quietly re-store a credential the user had just
revoked. Nobody hit that in testing because the off→on retest happened without leaving the screen,
so no `onResume` ran in between. Leaving the flag set until a real new auth starts closes both the
repeat and that resurrection path.

`:mobile:assembleDebug` and `:mobile:lintDebug` are both green with the fix in
(2026-09-04); there is no automated test over `DropBoxHelper` — the whole class is untestable
without a Dropbox account — so a compile and the device re-check below are the whole verification.

**✅ Re-checked on device 2026-09-04, both phones.** The toast is gone: returning to Data & sync,
going into the backups list and back, and locking/unlocking the screen no longer re-shows
"Dropbox Authentication Complete" or re-fires a sync. Login is still remembered across the same
scenarios — `authCredentialConsumed` does its job without resurrecting a credential that turning
sync off had just cleared.

#### A second device finding: the sync-conflict dialog could not be confirmed — fixed

**Reported from the Jelly Star.** When local and remote have both changed since the last sync, the
app asks which side wins; on that phone only *Cancel* could be pressed. Nothing was clipped — the
two timestamps **were** the controls. `remoteToLocalComparisonDialog()` built them with `setItems`
(a two-row list, tapping a row was the choice) plus `setView` for the explanation, which
`AlertDialog` always renders *below* the list. So the sentence saying "these are choices" came
after them, and the rows carried no button styling.

The odd construction had a real cause, worth knowing before anyone reverts it: `AlertDialog` cannot
show a message **and** a list — `AlertController.setupContent` installs the list only in the `else`
branch of `if (mMessage != null)` — so text alongside a list has to be a custom view, which lands
in the wrong place.

**Fixed** by making the choices ordinary buttons — *Keep local* / *Keep remote* / *Cancel* — with
both timestamps in the message and the newer side marked `(newer)`, in a new `SyncConflictDialog`
class. Sync logic unchanged: both choices still route through `KEY_DROPBOX_SYNC_CONFLICT_RESOLVE`
to the second dialog that names the consequence. `dialog_layout_conflict_resolution.xml` deleted.

**The first draft reproduced the bug, and only a screenshot caught it.** A stacked `AlertDialog`
button bar that outgrows the window is clipped, not scrolled — the plan's claim that the framework
always reserves room for it was wrong. Measured on an emulator squeezed to that phone's geometry
(`wm size 480x854`, `wm density 240`, so 320dp): at 1.3x the draft lost half of "Keep remote" and
all of "Cancel". Trimming the message from seven lines to four fixed it. **1.0x and 1.3x pass; 2.0x
(the accessibility maximum) is still clipped** — a documented limit, in *Deferred on purpose*.
Keep the message short; every line of it comes out of the button bar.

**Five tests** (`SyncConflictDialogTest`, suite now 34) cover the controls and that each routes to
the side it names — the mapping used to be a list index, and reversing it would send data the wrong
way. They assert each button is *wholly* on screen, not `isShown()`, which stays true for a clipped
button and is why the broken draft passed. At 2.0x the suite fails with `Keep remote button is
clipped: 38 of 85px on screen`.

**Preview hook**, `src/debug/` only, since staging a real conflict needs two devices — it also
hosts the dialog for the tests:

```
adb shell am start -n de.timowa.expenselog/.dropbox.DialogHostActivity --ez show_conflict true
```

**✅ Confirmed on device 2026-09-04.** Staged a real conflict across both phones (see the recipe
above) and the dialog rendered cleanly on both — Jelly Star included, the phone the original bug
was found on. All three buttons reachable, nothing clipped.

**Out of scope for a single test session:** the weekly/monthly auto-backup rotation in
`DropboxAutoBackups` genuinely needs days to elapse to exercise its "already backed up recently"
branch: nothing to do here beyond noting it, same as the reminder-after-reboot item already open in
Verification.

## Step 7 — Identity: off the original author's namespace

**Done on 2026-09-03.** The app is `de.timowa.expenselog`, v2.0 (versionCode 1). The Java package,
the manifest, the reflection-resolved XML, the two hardcoded literals, the 28 authorship javadoc
headers, the About dialog's developer link and the version numbering all moved together; 78 files
changed. `NOTICE` keeps the original author's copyright, which is not optional — see *Attribution*
below. Ran before Step 6 as planned, so the rename did not have to land on top of a rewritten
`dropbox/`.

**What was decided, and by whom.** The application ID was the maintainer's call, and so were the
three that follow from it:

| Decision | Choice | Why |
| --- | --- | --- |
| `applicationId` | `de.timowa.expenselog` | Two vendor segments replaced, `expenselog` leaf kept — see below |
| Display name | unchanged, *Expense Log* | Descriptive, not the original author's branding. "AR Productions" was the identity that had to go |
| Version | `versionCode 1`, `versionName "2.0"` | A new listing has no release history; 23 / 1.4 referred to releases this listing never made |
| About link | the GitHub repo | Correct from day one, unlike a store page that does not exist yet |

### Decide the ID first

Everything downstream depends on it. **Keep `expenselog` as the leaf and replace only the two
vendor segments** — e.g. `de.timowa.expenselog`. That is what makes this cheap: `expenseLog` is the
*app name*, not the vendor identity, and leaving it alone keeps `DBAdapter.DATABASE_NAME`
(`"expenseLog.edb"`), the `.edb` validation in `FileHelper.isValidExpenseLogDatabase`,
`LocalBackupManager.BACKUP_NAME_PREFIX`, and both backup filenames untouched — so every `.edb` file
already in the wild still imports. Renaming the leaf too would buy nothing and put all of that in
scope.

### What the refactor handles, and the four things it misses

**All four were handled; kept as the record of where a rename hides.** The move itself was done
with `git mv` on the three source roots and a scripted replace across `.java` / `.xml` / `.gradle`,
which is what *Refactor > Rename* would have done — it covers the Java tree (65 files, plus 6 in
`androidTest/` and the `test/` stub) and the manifest's 6 fully-qualified references
(`MyLogApplication`, four `parentActivityName`, `ReminderReceiver`). **The compiler proves nothing
here** — the package name appears in XML that is resolved by reflection at runtime and in two
hardcoded string literals. In rough order of how badly each fails:

1. **`res/xml/pref_headers.xml`** — three live `android:fragment="…SettingsActivity$…Fragment"`
   attributes (and one commented out). Instantiated **by reflection**. A miss compiles green, lints
   green, and crashes the moment Settings opens. This is the highest-risk item in the step.
2. **`res/xml/pref_general.xml`** — the custom `<…reminders.TimePreference>` element, also
   reflection, crashing at inflate rather than at build.
3. **`DependencyInjection/AppModule`** — a hardcoded prefs *file name* under the old namespace,
   carrying the original author's `testlog` typo. **The reasoning written here before the step ran
   was wrong and is worth correcting**, because it argued for leaving it alone: renaming the file,
   it said, would reset every setting on a device already running the old build. It would not. A
   new `applicationId` is a new app with its own empty preferences store, so the old settings are
   unreachable either way and the rename costs nothing.

   Underneath that was a real defect. `provideSharedPrefs()` returned
   `getSharedPreferences(<that name>)` while **every class that actually reads a preference** goes
   through `PreferenceManager.getDefaultSharedPreferences` — a different file, which nothing had
   ever written to. Nothing injects `SharedPreferences` today, which is the only reason the
   mismatch was invisible; the first class to inject it would have been handed an empty store and
   silently seen no settings at all. The provider now returns the default store.
4. **`FileHelper.packageName`** — a `private final static String` holding the old ID, used twice to
   build `"//data//" + packageName + "//databases//"`. That branch is only taken below API 28, and
   `minSdk` is 26, so **it is reachable**. Fixed as prescribed: the constant is deleted and both
   sites call `context.getPackageName()`, which cannot drift out of sync with a future rename.
   The two commented-out lines that also used it went with it.

Four `tools:context` attributes in `res/layout/` also named the old package; IDE-only and harmless
either way, but they counted toward the grep gate below and were moved with everything else.

Nothing else needs touching. The `FileProvider` authority is already `${applicationId}.provider`
and both call sites derive it from `getPackageName()`; the Play share link in `MainActivity` and
both `market://` links in `RatingManager` build their URLs the same way, so all of them follow the
new ID on their own. There is no signing config or keystore tracked in the repo, and all 34 commits
are the maintainer's, so there is no history to rewrite.

### Consequence: the maintainer's own install becomes a different app

A new `applicationId` is a new app to Android. The existing build and its **real financial data**
stay behind under the old ID; the renamed debug build installs alongside it, empty. Before doing
anything else on that phone: **export a `.edb` from the old app, then import it into the new one**
— that is the migration path, and it is also a free end-to-end test of the Step 4 work.

The upside is that `README.md`'s warning about the debug build colliding with the Play Store
release stops being true, and that paragraph can go.

### Branding, and what to do with the store links

- `settings_keys.xml` → `developer_link` was the `YOUR+DEVELOPER+NAME` placeholder Phase 1 put
  there, read by `Navigator` for the About dialog. ✅ It now points at the GitHub repository, which
  is right from day one — a store page would be a dead link until the app is published.
- `MainActivity`'s share message and `RatingManager`'s two rate prompts point at a Play listing
  that will not exist until the app is published. They build their URLs from `getPackageName()`,
  so they follow the new ID on their own and will resolve once a listing exists; until then they
  open a dead page. **Left as is** — a decision, not a defect.
- `versionCode 23` / `versionName "1.4"` were the old listing's numbering. ✅ Reset to `1` / `2.0`.
  A new listing has no release history, and inheriting 23 would have invited later confusion about
  which releases ever shipped.
- The app's **display name stays *Expense Log***. It is descriptive rather than the original
  author's branding — "AR Productions" was the identity that had to go — and keeping it costs
  nothing, since `DBAdapter.DATABASE_NAME` and the `.edb` validation never depended on it anyway.

### Attribution — remove the branding, keep the copyright

Apache-2.0 §4 requires retaining the copyright notice and the `NOTICE` file in derivative works.
**`NOTICE`'s `Copyright 2021 Andrew Rodrigues` line stays.** Add a copyright line for the revival
work *alongside* it; do not replace it.

The 29 `Created by …` javadoc headers were authorship markers rather than copyright notices, and
removable on that basis — git history and `NOTICE` carry provenance either way. **28 were removed,
uniformly.** The 29th is `DependencyInjection/HasComponent`, which says `Created by gak on 9/25/14`
and is third-party; it stays.

Removing them was not a blanket delete. Several of those blocks carried a real one-line description
under the authorship line (`Log item object`, `manages the reminders for notifications`), so the
authorship line and the `<p/>` separator left dangling behind it were stripped and the description
kept; where nothing survived, the whole block went. A regex sweep that took the comment out
wholesale would have deleted documentation along with the byline.

This is the position `README.md` already takes: the name and the "AR Productions" identity are not
part of the licence and should not ship. Removing the *branding* while keeping the *copyright* is
exactly that distinction.

### Documentation

11 mentions across 5 markdown files. `README.md` and `CLAUDE.md` move to the new ID. The plan
documents are historical records and should not be rewritten to pretend the old ID never existed —
but `docs/history/REHABILITATION_PLAN.md`'s `adb shell ls` path and the two in `README.md` are *instructions*,
and become wrong the moment the rename lands. Update those specific lines.

### ✅ The gate, and what it caught

`grep -ri "arproductions\|andrew" --exclude-dir=.git --exclude-dir=build .` returns `NOTICE`, which
is required, and this document plus `docs/history/REVIVAL_PLAN.md` and `docs/history/PR_REVIEW_REVIVAL.md`, which are
historical records and are deliberately not rewritten to pretend the old ID never existed. No
source file, resource, manifest or build file mentions it.

| Check | Result |
| --- | --- |
| `:mobile:assembleDebug` | ✅ `applicationId de.timowa.expenselog`, versionCode 1, versionName 2.0, confirmed in `output-metadata.json` rather than assumed |
| `:mobile:lintDebug` | ✅ 0 errors, 241 warnings — unchanged, and nothing suppressed |
| `:mobile:connectedDebugAndroidTest` | ✅ 29/29 — but not on the first attempt, see below |
| Settings opens (`pref_headers.xml` reflection) | ✅ on the emulator, via the notification's own `ACTION_SETTINGS` route, then tapping the General header |
| Reminder time picker (`pref_general.xml` reflection) | ✅ on the emulator — the clock dialog opens, no crash |
| Import an `.edb` from the old install | ✅ passed on device 2026-09-03 — this is also the data migration |

**The test suite failed the first time it ran, and it was not the rename.** Two of the 29 —
`settingsAction_launchesAnActivityDirectly` and `snoozeAction_staysABroadcast` — failed with "the
reminder notification was not posted", while three other tests in the same class that also post a
notification passed. Re-running that class alone was 5/5; re-running the whole suite was 29/29 on
the same emulator, unchanged. The distinguishing feature of the failing run is that it was the
first one after installing a **brand-new application id**, so the app was being granted
`POST_NOTIFICATIONS` for the first time.

`ReminderNotificationTest` now posts through a `postAndAwaitReminder()` helper that waits for the
notification and re-posts once if it does not appear. **The retry is the part that matters, not the
wait** — a post the system dropped will never turn up however long you look. It does not weaken
anything: a notification the app genuinely fails to build still never appears, and the caller's
assertion still fails. This was worth fixing rather than shrugging at, because the run that hits it
is exactly the one a person makes after checking this branch out.

**Both reflection sites were driven on the emulator rather than left to the maintainer**, because
they are the part of a rename that no compiler, lint run or test suite can reach, and driving them
took one `adb` session. `MainActivity` was started with the notification's own `ACTION_SETTINGS`
extra, which is how the app itself opens Settings; then the **General** header was tapped, which is
what instantiates `SettingsActivity$GeneralPreferenceFragment` by name; then **Reminder Time**,
which opens `TimePreference`'s clock dialog. Both screens rendered, and `logcat` shows no
`FATAL EXCEPTION`, `ClassNotFoundException` or `InflateException` throughout.

**The last gate item — passed 2026-09-03.** The maintainer installed the renamed debug build
alongside the old app (different `applicationId`, so both coexist), exported an `.edb` from the old
install, and imported it into the new one. It worked. This was the data migration itself as well as
an end-to-end test of the Step 4 rewrite under the new package name — scoped storage, the
validate-before-overwrite guard, and the `ACTION_OPEN_DOCUMENT` picker all had to hold for an import
across two differently-named apps to succeed. Step 7 has no outstanding items.

**The old app was not touched by any of this.** It keeps its records under the old application id
until it is uninstalled, so nothing here was a one-shot chance — the export could have been repeated
if the first import had not been right.

## Deferred on purpose

Cosmetic modernisation that **no user can observe**. Each is a real improvement and none belongs
on the critical path; a Phase 2 that stalls will stall because someone started here.

- ButterKnife → viewBinding (`LogTabsFragment` and `SummaryFragment` already show the pattern).
- `android.preference` → `androidx.preference`, 17 files. Deprecated but still present in
  `android.jar`.
- The remaining 4 non-Dropbox `AsyncTask` subclasses.
- Renaming `UpgradeHelper`, which now does nothing but bootstrap Dropbox.
- Tidying the vestigial `Object mFirebaseAnalytics` parameter out of `AnalyticsHelper.logAnalytic`
  and its ~70 call sites.

Two more added after the Step 5 device testing. Both are real and neither is urgent:

- **Move the import staging copy off the main thread**, with a progress indicator. Today
  `importFileAndVerify` copies the whole picked document into the cache on the UI thread before
  validating it, which took ~20 s for a file on Google Drive and would ANR on a large enough one.
  Do **not** reorder the validation to run before the copy — that ordering is the Step 4 data-loss
  fix. A cheap SQLite-header check on the first bytes could reject obvious junk early, in addition.
- **Give the sync-conflict dialog its own `ScrollView`.** `SyncConflictDialog` keeps its message
  short because a stacked `AlertDialog` button bar that outgrows the window is clipped, not
  scrolled; at a 2.0x font scale on a 320dp screen it is clipped anyway. Moving the two choices
  into a scrolling view of our own, leaving only a single-row Cancel pinned, removes the ceiling.
  Measured and documented under Step 6; only matters at large font scales.

- **Make the CSV share subject filename-safe.** `SpreadsheetHelper.createSpreadsheet` builds
  `EXTRA_SUBJECT` from a localised date containing `.` and `,`, and receiving apps turn it into
  names like `All Expense Log Records Sept. 3, 2026. 3, 2026.csv`. An ISO date fixes it.

## Final device pass — reboot, and a logcat read at last, 2026-09-04

**The two Verification items nobody had ever closed are closed.** Both were device-only by nature:
one needed a phone rebooted and left alone, the other needed `adb` attached to a phone the
maintainer had until now preferred not to attach.

### The reboot: a reminder survives it, on both phones

`AlarmManager` does not persist alarms across a reboot. Everything the reminder feature does after
a restart depends on `ReminderReceiver` receiving `BOOT_COMPLETED` and `ReminderManager.updateReminder`
re-arming from the stored preference. That path had existed, unexercised, since before the revival.

The test was run with `dumpsys alarm` on either side of the reboot rather than by waiting and
hoping, which is what makes the result evidence instead of an anecdote:

| | Before reboot | After reboot + unlock |
| --- | --- | --- |
| Alarm object | `Alarm{47c2ae4}` | `Alarm{a6db8cd}` — **a new one** |
| `origWhen` | `2026-09-04 16:45:17.127` | `2026-09-04 16:45:39.672` |
| `tag` | `*walarm*:de.timowa.expenselog/.reminders.ReminderReceiver` | unchanged |
| `repeatInterval` | `86400000` | unchanged |

**The seconds are the proof.** `setupReminder` restores only `HOUR_OF_DAY` and `MINUTE` from
preferences and takes everything finer from `Calendar.getInstance()`, so `:17.127` becoming
`:39.672` dates the re-arm itself — the receiver ran 39.672 seconds past the minute, after boot.
A stale alarm that had somehow survived would have kept the old seconds. The alarm object and
`PendingIntentRecord` identities changed for the same reason.

`origWhen` also stayed on **09-04** rather than moving to the 5th, which says the five-minute guard
in `setupReminder` did not push the first fire to tomorrow: the reboot completed with more than
five minutes to spare. **The notification then arrived on both phones.**

Three things anyone repeating this needs to know, all of them capable of producing a false failure:

- **The phone must be unlocked after boot.** Nothing in the manifest is `directBootAware`, so the
  app is credential-encrypted and `BOOT_COMPLETED` is not delivered until the first unlock. Leaving
  the phone on the lock screen tests nothing. Opening the app is not required, and should be
  avoided — that would re-arm the alarm by a path other than the one under test.
- **Reboot promptly.** The five-minute guard is evaluated *at boot*, not when the reminder was set,
  so what matters is the margin left when the receiver runs. Under five minutes and the alarm
  silently moves to tomorrow; `origWhen` showing the next day is the tell, and it is not a defect.
- **Do not force-stop the app first.** A stopped-state app receives no `BOOT_COMPLETED` at all.
  Swiping it from recents is fine and is what was done here.

One more trap, specific to reading the output: the maintainer's phone also runs
`arproductions.andrew.moodlog`, another app by this one's original author, whose alarms carry the
near-identical tag `arproductions.andrew.moodlog/.ReminderReceiver` and the same `+18h` window.
Grepping for `ReminderReceiver` returns both. Match on `de.timowa.expenselog`.

### What `window=+18h0m0s0ms` means, now that it has been seen

Both before and after the reboot the alarm reports an eighteen-hour window and a `maxWhenElapsed`
that far out. That is `setRepeating`'s inexactness made visible, and it is the deliberate trade-off
recorded at the top of `ReminderManager`: the system is *permitted* to defer the alarm that far.
In practice it fired on time on both phones, helped by `flags=0x8`
(`ALLOW_WHILE_IDLE_COMPAT`) and no app-standby penalty. The practical consequence for anyone
testing: a reminder arriving a few minutes late is the documented behaviour, not a regression.

### Logcat

Read on the Pixel 4a over `adb`: `logcat -b crash -d` empty, no crash observed while using the app.
The caveats are recorded against Verification item 6 rather than repeated here.

### Emulator crash hunt, same day

Separately from the phones, the packaged debug APK was fuzzed on the `Pixel_6` emulator (API 33) —
not because the emulator can replace a device, but because `monkey` reaches UI paths the 34
instrumentation tests never touch. **5,700 events across five seeds: no crashes, and the crash
buffer and `/data/anr/` both empty afterwards.** 1,800 of those events ran against a seeded
database of 901 records spanning two years, three accounts, and notes containing commas, quotes
and non-ASCII, so the list, graph, calendar and summary screens were exercised with real content
rather than the empty install a fresh APK gives you. The seeded data was intact afterwards.

Two artefacts of the method, noted so they are not mistaken for findings. The first run produced
one ANR, `Input dispatching timed out (Application does not have a focused window)`; the trace
shows the main thread parked in `Looper.loop` with no app frames and load 0.0, because `monkey`
began injecting during a window handoff. It did not recur once each run waited for
`mCurrentFocus` first. And a sixth seed was interrupted before completing, so its 900 events are
not counted above.

Loading data into a debuggable app's database from outside is worth recording: `/data/local/tmp`
is shell-owned and not traversable by the app uid, so `adb push` followed by `run-as cat` fails.
Feeding the SQL over stdin — `adb shell run-as <pkg> sqlite3 databases/expenseLog.edb < seed.sql`
— works.

## Verification

Phase 2 is done when all of the following hold. Ticked items are already satisfied.

1. ☑ No `requestLegacyExternalStorage`, no storage permissions in the manifest *(Step 4)*.
   ☑ `targetSdk 35` *(Step 5)*.
2. ☑ Instrumentation tests green — 34 across 6 classes, plus lint at 0 errors. ☐ The JVM unit-test
   source set is still only the `ExampleUnitTest` stub; nothing depends on it.
3. ☑ On an Android 13+ device: add a record; export CSV; export `.edb`; clear data; import that
   `.edb` and confirm every record, tag, and account returns; set a reminder and confirm it fires.
   *(Done across the Step 4 and Step 5 device passes; the `.edb` half was additionally proven
   across two phones. The receipt-photo clause is void — the feature was removed in Step 4b.)*
   ☑ Reboot and confirm a reminder still fires — **passed 2026-09-04 on both phones**, the last
   thing `ReminderReceiver`'s `BOOT_COMPLETED` path had never been held to. See *Final device
   pass* below for the `dumpsys alarm` evidence that the alarm is genuinely re-armed rather than
   merely surviving.
4. ☑ Cycle List / Graph / Calendar / Summary — walked at Step 5 test 12, nothing crashed.
5. ☑ Dropbox: full connect → sync → restore cycle against a real (throwaway) account — proven on
   the `Pixel_6` emulator 2026-09-03 and **on two real phones 2026-09-04**, including the real
   browser's PKCE redirect the emulator could not exercise and a record actually travelling from
   one phone to the other. One non-blocking bug found; see Step 6.
6. ☑ Logcat clean of `AndroidRuntime` — **read on a phone 2026-09-04**, the Pixel 4a over `adb`:
   the crash buffer is empty and no crash was seen while exercising the app. Two limits worth
   keeping in view. The logcat buffers are RAM-backed and cleared by a reboot, so this covers the
   current boot rather than the whole test campaign; and `/data/anr/` holds three traces dated
   **2026-08-31**, four days before this build and before the package rename reached the phone,
   which were not attributed to a process. They are almost certainly another app — nothing in
   this one has produced an ANR on any device or emulator run since — but "almost certainly" is
   the honest word. `dumpsys dropbox --print data_app_anr` attributes them without root if anyone
   wants to close that off.
7. ☑ No trace of the original author's namespace or branding outside `NOTICE` and the historical
   plan documents *(Step 7)*, and the renamed build opens Settings, the General preference screen
   and the reminder time picker without crashing — both reflection sites, driven on the emulator.
   ☑ The old-`applicationId` install exports an `.edb` that imports cleanly into the new one —
   confirmed on device 2026-09-03, the one item Step 7 had left.

## Ranked failure points

Live risks first; retired ones kept at the bottom so they are not re-litigated.

1. **An automatic backup is not a recovery story.** It is invisible to every file manager and dies
   with an uninstall (Step 4 trade-off). Nothing is broken; the risk is a user who believes they
   are covered and is not. Mitigated only by saying so in `README.md`. **Sharper now that Step 7
   has landed:** the renamed app is a different app, so the old install's automatic backups do not
   come across either. Only the manual `.edb` export migrates the data.

**Retired:**

- ~~The Dropbox rewrite is proven on an emulator, not yet on a real device~~ — retired 2026-09-04.
  Nine of ten device checks passed on two real phones, including the real browser's PKCE redirect
  and a two-device sync; both bugs it turned up are fixed and re-verified on both phones, the Jelly
  Star included. The tenth check, the logcat read, was finally done on 2026-09-04 as well, so
  "no crash" no longer rests on observation alone.
- ~~A reminder does not survive a reboot~~ — retired 2026-09-04. `dumpsys alarm` on either side of
  a reboot shows a freshly re-armed alarm, and the notification arrived on both phones. See
  *Final device pass*.
- ~~Reminders silently stop at Step 5~~ — `POST_NOTIFICATIONS` is declared and requested, a denial
  now produces a message instead of silence, and a test asserts the notification posts.
- ~~The notification Settings action silently stops working at Step 5~~ — the trampoline is gone,
  lint's check is re-enabled and passing, and the fix is pinned by a mutation-checked test.
- ~~Old record images are silently unreachable~~ — the feature is deleted (Step 4b) and the files
  were never touched. The deadline it carried into Step 5 is gone with it.
- ~~Something in Step 4 breaks on real data the emulator did not show~~ — eleven device checks on a
  phone with years of records, including a deliberate wipe-and-restore. Passed.
- ~~A reminder does not actually arrive tomorrow morning~~ — it did, at 08:00, on a Pixel 4a running
  Android 14, and the shorter reminders repeated on a second phone running Android 13. The whole
  reason Step 5 had a device gate; closed 2026-09-03. What is *not* closed is the reboot case, which
  is now item 3 of the Verification list.

- ~~Silent data loss in the Step 4 rewrite~~ — mitigated as designed. The Step 2 tests preceded the
  rewrite and caught a genuine data-destroying bug in it (invalid imports wiping the database while
  reporting success). The approach worked; keep it for Step 5.
- ~~`onRequestPermissionsResult` absent~~ — `PermissionHelper` is deleted; no storage permission is
  requested anywhere. Returns as a Step 5 concern for `POST_NOTIFICATIONS`, listed above.
- ~~The package rename crashes somewhere the build cannot see~~ — retired the only way it could be.
  The grep gate is clean, and **both** reflection sites were driven on the emulator: the General
  header resolves `SettingsActivity$GeneralPreferenceFragment` by name, and Reminder Time opens
  `TimePreference`'s dialog. The manifest's six fully-qualified names are right, and logcat is
  clean of `FATAL`, `ClassNotFoundException` and `InflateException` across all of it.
- ~~A migration written after the fact~~ — Step 3's scaffolding throws on an unregistered version
  step, so this now fails loudly instead of corrupting silently.
