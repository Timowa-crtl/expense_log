# CSV Export Clean-up — plan

**Status: implemented and verified on device.** On branch `formats`, in PR #5, alongside the
date-format work. This plan was written against `main` as of `fdea89a`; see the "What was built"
section at the end for where the implementation departed from it, and what remains open.

The goal is a *clean* CSV: one that opens correctly, parses correctly, and can be handed to Excel,
LibreOffice, Google Sheets or a script without anyone having to repair it first. Today it is none of
those things — the amount column is not a number, the file has no encoding marker, the time column
does not sort, half the fields are unquoted, and the exported file arrives with no `.csv` extension.

This document was the plan of record while the work was open; it is archived history now, the way
`REVIVAL_PLAN.md` and the others are.

---

## The evidence

A real export, `Expense Log Records from 2026-09-01 - 2026-09-30`, 13 records, September 2026:

```
Date,Time,Amount,Category,Notes
"2026-09-01",0:00,"-4,99 €",General,"Sim24"
"2026-09-03",18:21,"-6.666,00 €",Activities,"NeuJelly"
"2026-09-04",14:29,"-1,00 €",Alkohol,"ThridAirplane"
"2026-09-27",19:04,"2.539,45 €",Salary,""
,Total,"-86.207,31 €"
```

What is right about it: the dates are ISO, the rows sort chronologically, expenses are negative,
and the `Total` reconciles exactly (−86.207,31 €). The arithmetic and the record selection are
sound. Everything below is presentation.

What is wrong with it, in the order a user meets the problems:

1. The file has no `.csv` extension, so Windows does not associate it with Excel.
2. Opened anyway, `€` renders as `â‚¬` — the file is UTF-8 with no BOM.
3. The `Amount` column is a quoted string with a currency symbol and locale grouping, so no
   spreadsheet totals it.
4. `Time` reads `0:00` where it should read `00:00`, and does not sort.
5. The `Total` row has three fields against a five-column header, so `Total` lands under `Time`.

---

## The target format — decided

| Decision | Choice |
| --- | --- |
| Date / time layout | Two columns. `Date` always `yyyy-MM-dd`, `Time` always `HH:mm`, 24h, zero-padded |
| Amount | Bare signed number, plus a separate `Currency` column. Sign carries expense/income |
| Numbers & delimiter | International: `.` decimal, **no** thousands grouping, `,` delimiter (RFC 4180) |
| Date source | **Always ISO in the export**, independent of the Date Format setting |
| Encoding | UTF-8 **with** BOM |
| Quoting | RFC 4180 on every field: wrap in `"`, escape an internal `"` as `""` |
| Headers | Stay localised — this is a user-facing export, not an API |

A row becomes:

```
Date,Time,Amount,Currency,Category,Notes
2026-09-03,18:21,-6666.00,EUR,Activities,NeuJelly
2026-09-27,19:04,2539.45,EUR,Salary,
```

Two consequences worth stating plainly before the work starts:

- **German Excel will no longer parse the amounts on a double-click.** A dot decimal reads as text
  in a `de-DE` locale. `Data > From Text/CSV` with UTF-8 and "English (United States)" gives real
  numbers. This is the deliberate cost of a portable file, chosen over a German-Excel-native one
  (`;` delimiter, `,` decimal), which would have been worse for scripts and every other tool.
- **The export stops following the Date Format setting.** This turned out to matter immediately:
  with the export decoupled, the on-screen default was moved back to `Jan 25, 2016`, which reads
  better in the app. The CSV stayed ISO throughout. Before this change those were one decision and
  you could not have had both.

---

## Findings

Line numbers are against `mobile/src/main/java/de/timowa/expenselog/SpreadsheetHelper.java` unless
stated otherwise.

| # | Finding | Where | Severity |
| --- | --- | --- | --- |
| A | No UTF-8 BOM; `FileWriter` cannot even be given a charset | `:103` | High — mojibake on the main path |
| B | `Amount` is a quoted, currency-formatted, locale-grouped string | `PrefManager:208`, `:167` | High — the column is unusable as data |
| C | `Time` follows the display preference; unpadded, unquoted | `:297`, `:305` | High |
| D | `Date` follows the display preference | `:296`, `:303` | Medium |
| E | No RFC 4180 quoting on category, account, or headers; notes "escape" by **deleting** quotes | `:271-285`, `:322`, `:326`, `:333` | High — silent corruption and data loss |
| F | `KEY_NOTES` is nullable `text`; `.replace()` is called on it unguarded | `:333`, `DBAdapter:61` | Medium — NPE on a null note |
| G | Total row writes 3 fields against a 5–6 column header | `:340-347` | Medium |
| H | Combined export puts a 2-column summary and a 6-column list in one file | `:114-121` | Medium — not a single-table CSV by construction |
| I | `isMultipleAccounts` never closes its cursor and runs **twice per row** | `:381`, called `:276`, `:318`, `:320` | Medium — ~5000 leaked cursors at 2540 records |
| J | Writer is not closed on an `IOException` | `:103-128` | Low |
| K | On-disk name is the constant `"Expense Log.csv"`, so exports overwrite; shared name comes from the subject and has no extension | `FileHelper:39`, `:100`, `:355` | High — the first thing the user hits |
| L | No trailing newline; LF rather than the RFC's CRLF | `:347` | Low |

### On finding B

`formatMoneyForSpreadsheet` is `"\"" + formatMoney(amount) + "\""` — it quotes a string that
`formatMoney` has already built as *display* currency: symbol, non-breaking space (`U+00A0`),
locale grouping separator, locale decimal separator. The exported bytes for `-4,99 €` are
`2d 34 2c 39 39 c2a0 e282ac`. There is no path by which a spreadsheet reads that as a number.

It is called from **eight** places — the list rows, the list total, every summary row, the summary
expense/income/total rows, and both budget rows. All of them have to move together, or the file
ends up with two different amount conventions in it.

### On finding K

The on-disk file is always `FileHelper.csvFileName`, the constant `"Expense Log.csv"`. The name the
user actually received came from `EXTRA_SUBJECT` — `"Expense Log Records from 2026-09-01 - 2026-09-30"`
— because the share target used the subject rather than the content URI's display name. That is why
there is no extension.

**Chosen scheme.** The on-disk file gets a real per-export name, and `EXTRA_SUBJECT` and
`EXTRA_TITLE` are set to that same string. The subject is then slightly literal as an email subject,
but it guarantees a correct `.csv` extension whichever naming rule the share target follows — and a
wrong extension is a worse outcome than a plain subject.

```
Expense Log 2026-09-01 to 2026-09-30 list.csv
Expense Log 2026-09-01 to 2026-09-30 summary.csv
Expense Log all records 2026-09-06 list.csv
```

Three things earn their place in it:

- **ISO dates**, for the same reason the columns use them.
- **`to` between them**, not a hyphen. The dates already contain hyphens, so
  `2026-09-01 - 2026-09-30` hides its own boundary.
- **The export type.** This was a second defect found while choosing the scheme: a list and a
  summary of the same range produced *identical* file names, so exporting one after the other
  silently overwrote. The three types are now distinct files.

The constant app-name prefix means a folder listing still sorts these chronologically.

---

## Steps

Ordered so each step leaves the build green and the export working. Steps 1–2 are the foundation;
nothing else is safe to judge until the writer and the quoting are right.

### 1 — Replace the writer, add the BOM

Swap `new FileWriter(createdFile)` for an explicitly-UTF-8 writer in a try-with-resources, and emit
`'\uFEFF'` as the first character.

```java
try (Writer writer = new BufferedWriter(
        new OutputStreamWriter(new FileOutputStream(createdFile), StandardCharsets.UTF_8))) {
    writer.write('\uFEFF');
    ...
}
```

Closes A and J. `appendList`, `appendSummary`, `addBudgetInfo` and `appendBudgetInfo` change
signature from `FileWriter` to `Writer`.

### 2 — One quoting helper, applied to every field

Add a private `csvField(String)`: return `"` + value with each `"` doubled + `"`, and treat null as
empty. Route **every** written field through it — headers included — and delete the two ad-hoc
`"\"" + x.replace("\"", "") + "\""` constructions.

Closes E and F. This is the step that stops a category named `Food, drink` from silently shifting
every column after it.

### 3 — Fixed date and time in the export

Two `SimpleDateFormat` constants local to the export, `yyyy-MM-dd` and `HH:mm`, both `Locale.US`,
replacing the `prefManager.getDateFormat()` / `getTimeFormat()` calls at `:296-297`.

The email subject moves to ISO too, and for a reason finding K explains: the subject *is* the file
name at some share targets, so it cannot carry a `01/25/16`. Closes C and D.

### 4 — Numeric amount and a Currency column

- Add `PrefManager.formatAmountForCsv(double)` → `String.format(Locale.US, "%.2f", amount)`. No
  grouping, no symbol, dot decimal, always two decimals.
- Add `PrefManager.getCurrencyCodeForCsv()` → the ISO 4217 code (`EUR`, `USD`, …) from the existing
  currency preference, reusing `getCurrencyCode(...)`.
- Insert a `Currency` column after `Amount` in the list export, header included.
- Replace all **eight** `formatMoneyForSpreadsheet` call sites.
- Delete `formatMoneyForSpreadsheet` once nothing calls it — leaving it is an invitation to
  reintroduce the old format.

Closes B.

### 5 — Make every row the same width

Pad the total row to the full column count so `Total` sits under `Category` (or its own label
column) rather than under `Time`. Do the same for the summary block's rows.

Closes G. For H, the recommendation is to **keep** the combined export but separate the two tables
with a blank line and repeat a header for the second — a spreadsheet handles that far better than
two different column counts with no marker. Splitting into two files is the alternative if you would
rather; say so and it changes this step only.

### 6 — Fix the cursor leak

Call `isMultipleAccounts(db)` **once** in `appendList`, hold the boolean, and close the cursor
inside the helper. Currently it opens a cursor, never closes it, and runs once for the header plus
twice for every row.

Closes I. Worth doing here because this file is already open, and it is a genuine leak — Android
logs `Finalizing a Cursor that has not been deactivated or closed` for each one.

### 7 — Name the exported file properly

The scheme under finding K: a per-export filename carrying the ISO range and the export type,
ending in `.csv`, used for the on-disk file, `EXTRA_SUBJECT` and `EXTRA_TITLE`. Assembled by
`CsvFormatter.fileName`, which appends the extension itself so no call site can omit it.

`FileHelper.csvFileName` stops being a constant and becomes a function of the range. It has exactly
one other reader — `SpreadsheetHelper:102`, which builds the `File` — so this is contained.

Closes K. Also ends the silent overwrite of the previous export.

### 8 — Trailing newline

End the file with a newline. Leave line endings as LF — every target in play accepts it, and CRLF
buys nothing. Closes L.

---

## Tests

**The formatting must be extracted to be testable.** `SpreadsheetHelper` takes an `Activity`, and
the whole export runs behind `createSpreadsheet`, which is private and reached through an
`AlertDialog`. Testing the current shape means driving a dialog.

Extract the pure parts — `csvField`, `formatAmountForCsv`, the date/time formatters, the header and
row builders — into a `CsvFormatter` with no Android dependencies beyond a `Context` for strings.
Then:

- **`CsvFormatterTest`** (JVM where possible, instrumentation if it needs resources) — quoting a
  field containing a comma, a quote, a newline, a null; amount formatting for negative, zero,
  thousands, and a value needing rounding; date and time formatting including the `00:00` case that
  currently emits `0:00`.
- **`SpreadsheetCsvTest`** (instrumentation) — build a database of known records, run the export,
  parse the file back, and assert: BOM present, header width equals every row's width, `Amount`
  parses as a `double` under `Locale.US`, dates match `\d{4}-\d{2}-\d{2}`, times match `\d{2}:\d{2}`,
  and a record whose category contains a comma and whose note contains a quote survives a
  round-trip intact.

**`IsolatedDatabaseContext` is mandatory** for the second one. The export writes through
`FileHelper.fileSetup` and `getAppFilesDir` into `Android/data/<package>/files`, which is the same
directory holding `ExpenseLogBackup.edb`. That is exactly the path that destroyed the maintainer's
backup once already (finding 2 of `docs/history/REVIEW_FINDINGS.md`). A test given the real context
writes there again.

---

## Device verification — PASSED

**Confirmed on device by the maintainer, 2026-09-06: "this looks perfect on device testing."**

That is the confirmation as given — an overall pass, not a per-step log. The checklist below is what
the pass was against; it is recorded here as the list that was exercised rather than as seven
independently reported results.

This is the part that mattered most. The instrumentation suite cannot reach it: what fails or
succeeds here is what a *receiving application* does with the file, and the original defect — a file
arriving with no extension at all — lived entirely on that side.

The checklist:

1. Export a date range from the device.
2. Confirm the received file is named `Expense Log 2026-09-01 to 2026-09-30 list.csv`, extension
   intact, and that exporting the same range as a summary produces a second file rather than
   overwriting the first.
3. Open by double-click in Windows Excel — `€` and any umlauts render correctly (the BOM).
4. Confirm `Amount` is right-aligned after a `Data > From Text/CSV` import with UTF-8 and an
   en-US locale, and that `SUM` over the column matches the `Total` row.
5. Open the same file in LibreOffice and Google Sheets — both should parse it with no dialog.
6. Export with a category renamed to contain a comma, and a note containing a `"`, and confirm the
   columns do not shift.
7. Export the combined list-and-summary type and confirm both tables are readable.

---

## Out of scope

- The summary and budget blocks keep their current *content*; only their formatting and column
  width change.
- No new export options, no XLSX, no column picker.
- `formatMoney` itself is untouched — it is the on-screen formatter and is correct for that job.
  Only the spreadsheet variant goes.
- The email subject is now the file name (finding K), so it no longer follows the Date Format
  setting. That is the fix for the missing extension, not a side effect.

## Risks

- **Eight call sites for the amount** (verified: nine references, one declaration plus eight uses).
  Missing one leaves two conventions in a single file. The compiler catches it if
  `formatMoneyForSpreadsheet` is deleted rather than deprecated — which is why step 4 deletes it.
- **The dot decimal is a real trade-off for a German Excel user.** It is the right default for a
  portable file, but if the double-click path matters more than portability, the alternative is a
  `;`-delimited, comma-decimal file, and that decision belongs before step 4, not after.


---

## What was built

Implemented on `formats`. Three departures from the plan as written, all deliberate:

1. **`CsvFormatter` owns amount formatting, not `PrefManager`.** Step 4 put `formatAmountForCsv` on
   `PrefManager`. It has no dependency on preferences, so it belongs with the other pure formatting
   and is directly testable there. `PrefManager` keeps only `getCurrencyCodeForCsv()`, which does
   need the currency preference.
2. **Step 3 and finding K contradicted each other** about the share subject; finding K won, and the
   text above is corrected. The subject is now the file name, because at some share targets the
   subject *is* the file name — which is the whole reason the exported file had no extension.
3. **The end-to-end `SpreadsheetCsvTest` was not written.** `SpreadsheetHelper` takes an `Activity`,
   reaches its export through a private method behind an `AlertDialog`, and reads the static
   `MainActivity.mFilterArray` for the budget block. Driving a real export from a test means
   restructuring those dependencies — a larger refactor than the CSV fix itself, and one that would
   have made this change much harder to review. `CsvFormatterTest` covers every formatting rule
   directly; the structural findings (G, H, I, J, K) are covered by reading and by the device pass
   below, which is the part that actually failed for the user and which no instrumentation test can
   reach anyway.

Findings A–L are all closed, and the device verification above **passed** — confirmed by the
maintainer on 2026-09-06, against a real export to a real share target, which is the one thing the
emulator cannot stand in for.

Two things remain open, neither blocking:

- **The end-to-end `SpreadsheetCsvTest`** (item 3 above) is still unwritten. The device pass covers
  the same ground for now, but it is a human step rather than a regression guard: nothing currently
  fails automatically if a future change reintroduces one of these findings at the file level.
  `CsvFormatterTest` guards the formatting rules; the assembly is unguarded.
- **`PROGRESS.md`** still reports 49 instrumentation tests and 241 lint warnings. Correct for `main`,
  wrong the moment this merges. *(Done on the `tweaks` branch, PR #6, along with archiving this
  document to `docs/history/`.)*
