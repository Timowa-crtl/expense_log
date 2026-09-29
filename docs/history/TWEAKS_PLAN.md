# Expense Log — tweaks follow-ups

> **Status: complete.** A follow-up to PR #18 (`tweaks` branch), made on that branch before it
> merges; archived here once done. A second item was reserved and not needed.
>
> | # | Item | State |
> | --- | --- | --- |
> | 1 | The combined series save's snackbar says RESTORE, not UNDO | **Done** (`e7b3fb2`) — verified on the emulator, full suite green |
>
> **Considered and left as is:** the export dialog's period does not remember the month across a
> round trip through a period holding today (Jun → Year → Month shows September), unlike the
> records views since PR #18. It predates the PR, the label and preview show the period plainly,
> and ‹ › step back. The maintainer's call: keep it.

---

## 1 — The combined series save's snackbar says RESTORE, not UNDO

### The case

A monthly series at 100, Jan–Dec. You open the March entry, change the amount to 120, move Ending
to Sep 30, and choose **The whole series**. One transaction:

1. sets every entry to 120, then
2. deletes the entries after the new end (Oct, Nov, Dec).

The snackbar reads **"Records updated · 3 records deleted · UNDO"**. The button reverses only step 2:
Oct–Dec come back, at **120**, and the end is Dec 31 again. Jan–Sep keep the raise.

### The decision: keep the behaviour, fix the wording

Undo reverses exactly the end change — the result is as if the raise had been saved alone. The
alternatives were weighed and rejected:

- **Restore the deleted entries at their old values (100).** One series would hold 120 and 100
  while its stored amount is 120. The Recurring overview shows a series by its latest entry, so a
  series just raised would read 100, and a later extension copies the latest entry, silently
  undoing the raise for every new entry.
- **Undo the whole save, values included.** No value edit in the app has ever had Undo; it would
  need every changed record kept and a split series re-joined. Too much for a rare case.
- **Refuse new values together with an earlier end.** Friction for a legitimate edit ("rent rises
  in March, I move out in September").

What is wrong is only the word. **UNDO** after "Records updated" reads as "take back the save".
For this case the button says what it does: **RESTORE**.

### The change

- **`DeleteUndo.offer`**: the overload that takes a message also takes the action's label.
- **`NewLogFragment.saveSeriesRecord`**: the combined case ("Records updated · N records
  deleted") offers **RESTORE**. Every save that only deleted — a new end alone — keeps **UNDO**,
  as do all record deletes.
- **`strings.xml`**: a `restore` string.
- **PR #18**: the real-phone checklist gets a step that presses RESTORE and checks Oct–Dec come
  back at the new amount, with the old end.

### Verification

- The snackbar on the emulator, for the combined case (RESTORE) and for an end alone (UNDO).
- Pressing RESTORE brings the entries back at the new amount, with the old end.
- The affected test classes, lint, then the full suite; the APK on Drive refreshed.

### Result

- Emulator, "Sim24" series at 20 entries ending Nov 15: amount 6.99 → 7.99 and Ending → Oct 15,
  The whole series → **"Records updated · 1 record deleted · RESTORE"**. RESTORE → "1 record
  restored": 20 entries again, Ending Nov 15 again, and the restored November entry at **7.99**.
- An end alone still offers **UNDO** (the one-argument `DeleteUndo.offer`, unchanged).
- `RecordDeleteUndoTest` 18/18, `EnglishTextTest` 3/3; lint 0 errors, 87 warnings.
- Full suite: **295 tests in 46 classes, 0 failures, 0 skipped** (API 33 emulator).
