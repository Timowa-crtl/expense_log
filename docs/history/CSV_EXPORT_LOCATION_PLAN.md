# Let exports choose where they go

**Status: implemented and verified on device.** On branch `tweaks`, in PR #6. What shipped differs
from this plan in one deliberate way: Save did *not* replace Share — the export dialog offers both,
because each answers a different question. See the "Offer both destinations for every export" commit
for that reasoning, and for the cleanup rule, which the following commit replaced with deletion by
recorded path rather than by directory listing.

Export currently asks *"who wants to receive this file?"* when the user is asking *"where should
I put it?"*. Both exports move to the Storage Access Framework, so the destination is the system
"Save to…" picker: local storage, Drive, Dropbox, anywhere a document provider reaches.

## The cause

`SpreadsheetHelper.sendFile` builds `Intent.ACTION_SEND` and wraps it in `createChooser`. That is
the **share sheet**, which lists apps that declared an `<intent-filter>` for `ACTION_SEND` with a
matching MIME type — Drive, Signal, Gmail. Saving a file to the device is not something an app
registers for; it is the Storage Access Framework's job, reached with `ACTION_CREATE_DOCUMENT`, a
different intent entirely. Stock Android ships no built-in "save to device" share target, so local
storage can never appear in that list. Nothing to do with permissions or scoped storage.

`FileHelper.exportDB` has the same shape, and one extra fault of its own: `share.setType("file/db")`
is not a MIME type. Nothing registers for it, so the `.edb` export's target list is narrowed to
whatever happens to accept anything at all.

The **import** side already does this properly — `FileHelper.createImportIntent` uses
`ACTION_OPEN_DOCUMENT`. Export just never got the mirror image, and this change is that mirror.

## Decisions taken

- **Save replaces share.** Export goes straight to the location picker; the share sheet goes away.
  Cloud destinations are *not* lost — Drive and Dropbox are document providers and appear in the
  picker. What is lost is one-tap "send to Signal / Gmail", which is a deliberate trade.
- **CSV and `.edb` together.** Same mechanism, same fix, one device pass. A backup is the thing you
  most want on your own storage, so leaving `exportDB` on a share sheet would be the odder half.
- **Stage, copy, then clean up.** The file is still built in the app's own directory first, and the
  staging copy is deleted once the bytes land at the chosen URI. Today's copies accumulate in
  `Android/data/<package>/files`, which no file manager can reach since Android 11, so they can
  only be cleared by clearing app data.

## The constraint that shapes it

Checked against the resolved dependency tree, not assumed: this app has
**`androidx.activity:1.0.0`** and `androidx.fragment:1.1.0`. `registerForActivityResult` /
`ActivityResultLauncher` arrived in activity 1.2.0, so **the modern result API is unavailable**.
This uses classic `startActivityForResult` + `onActivityResult`, exactly as `IMPORT_REQUEST_CODE`
already does. That 1.0.0 pin is the same one forcing the predictive-back opt-out in `CLAUDE.md`;
raising it is a separate job with its own back-handling work and does not belong here.

The round trip is the real work: the picker is another activity, so the export's state has to
survive it, and `SpreadsheetHelper` is `@Inject`-constructed per activity component with no
lifecycle of its own.

## Steps

### 1. Shared plumbing in `FileHelper`

Mirroring `createImportIntent`, and obeying the rule that **`FileHelper` reports, it does not
present** — the copy helper returns a result and logs; only the main-thread caller may toast.

```java
static Intent createExportIntent(String fileName, String mimeType)   // ACTION_CREATE_DOCUMENT
                                                                     // + CATEGORY_OPENABLE
                                                                     // + EXTRA_TITLE
static boolean copyStagedFileTo(Context ctx, File staged, Uri target) // then staged.delete()
```

`copyStagedFileTo` writes through `ContentResolver.openOutputStream`, deletes the staging file only
on success, and returns whether it worked. New request codes sit next to `IMPORT_REQUEST_CODE`:
`EXPORT_CSV_REQUEST_CODE`, `EXPORT_DB_REQUEST_CODE`.

MIME types: `text/csv` for the CSV, `application/octet-stream` for the `.edb` — replacing the
malformed `file/db`, and for the same reason the import filter is `*/*`: `.edb` has no registered
type.

### 2. CSV export

`createSpreadsheet` builds the file exactly as it does now, then instead of calling `sendFile`
launches `createExportIntent(fileName, "text/csv")` with `EXPORT_CSV_REQUEST_CODE`.

The staged path must survive the picker. A `static` field would not survive process death — the
system picker is foreground and the app can be killed behind it, and the failure mode is an export
that silently does nothing. So the pending path goes into the app's `SharedPreferences` under a new
non-user-facing key in `settings_keys.xml`, alongside the existing internal keys such as the
notification-asked flag. Cleared once the copy completes or the picker is cancelled.

The result lands in `MainActivity.onActivityResult`, since `LogTabsFragment` is hosted there and
`SpreadsheetHelper` holds that activity. `sendFile` is deleted.

### 3. `.edb` export

`exportDB` is `static` and takes a bare `Context`, which cannot start an activity for result. Split
it the way the import side is already split:

- `FileHelper.stageDbForExport(Context)` — the `FileChannel` copy, returning the staged `File` or
  `null`. No intent, no toast.
- `SettingsActivity.DataSyncPreferenceFragment` launches the picker and handles the result in the
  `onActivityResult` it already has for `IMPORT_REQUEST_CODE`.

Worth doing while here: the staged file is named `expenseLog.edb` (`DATABASE_NAME`), so every
backup has the same name and the date lives only in the share subject, which is about to stop
existing. `EXTRA_TITLE` is where the name comes from now, so give it a dated one the way the CSV
export was given one in the `formats` work.

### 4. Tests

The picker itself cannot be driven without Espresso-Intents, which this module does not have. Pin
the two halves that can be:

- `createExportIntent` — action, category, type and `EXTRA_TITLE`, for both MIME types.
- `copyStagedFileTo` — bytes arrive intact at the target URI, and the staging file is gone
  afterwards; plus the failure case, where a failed copy leaves the staging file alone.

File-level tests only, so no `IsolatedDatabaseContext` is needed — except any test that stages a
real database, which must use it.

### 5. Verify

- `assembleDebug`, `lintDebug` (0 errors), `connectedDebugAndroidTest`.
- On the emulator: export a CSV from Records list and from Summary, confirm the picker offers local
  storage, save to Downloads, and read the file back to confirm the bytes and the `.csv` name.
- Export the database the same way, then **import the saved `.edb` back**, which is the check that
  matters — an export nobody can restore is worse than no export.
- Confirm cancelling the picker leaves no staging file behind and no pending-path key set.
- Re-run the import/backup checks in `docs/history/RECOVERY_PLAN.md`, since this touches the export
  half of that path.

## Effort

**Medium** — bigger than it looks from the one-line symptom. Two call paths, a round trip through
another activity with state that must survive it, a split of a static helper, new tests, and a
device pass that has to include a restore.

## Deliberate non-goals

- The record **Share** action in `MainActivity` (line 348) stays a share: it sends text, not a file.
- `androidx.activity` is not upgraded here.
