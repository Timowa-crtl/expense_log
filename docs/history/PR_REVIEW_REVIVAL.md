# PR Review — #1 "Phase 1: revive the build"

- **PR**: https://github.com/Timowa-crtl/expense_log/pull/1 (`revival` → `main`, 16 commits, 47 files, +1,006 / −2,632)
- **Reviewed**: 2026-08-25, at commit `3dc8516`
- **Method**: two passes by a review agent, findings independently re-verified against the working
  tree before being recorded here. Pass 1 covered the head commit (`3dc8516`, wrapper + docs) in
  depth. Pass 2 covered the full `main...HEAD` diff — the Step 3/4 build changes, the Step 6
  manifest/resource edits, and the Step 7a–7e Java surgery.
- **Result**: **3 findings** (1 medium, 2 low). None block the merge; all three have small,
  local fixes. The bulk of the diff — the un-gating, the deletions, the telemetry neutering —
  verified faithful.

---

## Findings

### 1. `ReminderReceiver` flipped to `exported="true"` on a false premise — MEDIUM

**`mobile/src/main/AndroidManifest.xml:100`** (introduced in `f06eccf`, Step 6)

Step 6 changed `ReminderReceiver` from `exported="false"` to `exported="true"`, and the commit
message presented this as fixing a real bug: *"reminders have never survived a reboot."* **That
premise is wrong.** `BOOT_COMPLETED` is a system broadcast; the system server delivers it to
non-exported receivers regardless, because `android:exported` only gates senders outside the app
and the system. `RECEIVE_BOOT_COMPLETED` was already declared (manifest line 14), so the original
configuration worked, and reminders did survive reboots in shipped v1.4. The same applies to the
notification-action `PendingIntent`s — they are same-package explicit sends, delivered to a
non-exported receiver just fine.

So the change bought nothing — and it opens an unnecessary attack surface.
`ReminderReceiver.onReceive` validates nothing about the sender and dispatches purely on the
`ACTION_KEY` int extra. With `exported="true"`, any co-installed app can send an explicit Intent to
`arproductions.andrew.expenselog/.reminders.ReminderReceiver` and:

- spoof the reminder notification (`ACTION_NOTIFY`),
- dismiss it and force-launch `MainActivity`, plus broadcast `ACTION_CLOSE_SYSTEM_DIALOGS`
  (`ACTION_SETTINGS` path), or
- silently snooze or reschedule the user's reminder alarms (`ACTION_SNOOZE`, and the default
  `ACTION_UPDATE_REMINDER` path).

**Fix**: revert to an explicit `android:exported="false"`. That loses nothing (boot broadcasts and
same-package PendingIntents still arrive) and already satisfies the Android 12
explicit-`exported` requirement that motivated touching the attribute at all.

**Follow-on corrections**: the false premise originates in `docs/history/REVIVAL_PLAN.md` (Step 6, line 208 —
it predates the revival work and was carried out as written), was repeated in the `f06eccf` commit
message, and appears in the PR description under "Two real bugs fixed along the way." One of those
two claims is wrong; the other (`onActivityResult` swallowing non-billing results) stands. The plan
text and PR body should be corrected alongside the manifest fix. The commit message cannot be
amended without a rewrite of pushed history — this document and the plan correction serve as the
record instead.

### 2. Rating prompt now thanks every user for an upgrade that no longer exists — LOW

**`mobile/src/main/java/arproductions/andrew/expenselog/RatingManager.java:97`** (and the same
pattern at ~line 137; introduced in `c9e14eb`, Step 7b)

The premium un-gating collapsed each gate to its premium branch — mechanically correct everywhere
else, but for the rating prompt the premium branch is the wrong survivor. Both
`showUserRatingPrompt()` and `showRatingPrompt()` now unconditionally build their message from
`rating_prompt_msg_pro_part1..3`, which opens **"Thanks for upgrading!"** — wording addressed to
users who had just purchased premium. Billing is deleted; a fresh install that hits the rating
prompt is thanked for a purchase that cannot occur.

The formerly-default strings `rating_prompt_msg_part1/part2` (*"A positive review helps support …
and keep more features and improvements coming. Rate now?"*, `strings.xml:236–237`) read correctly
for everyone and are the natural survivor. They are currently dead resources.

**Fix**: build the message from `rating_prompt_msg_part1/part2` in both methods; delete the three
`_pro_` strings.

### 3. README's application-ID rename checklist dropped the manifest step — LOW

**`README.md:97`** (introduced in `3dc8516`)

The pre-PR README's rename instructions said to "also check `AndroidManifest.xml`". The rewrite
replaced that with "also check the `namespace` in `mobile/build.gradle`" — but the manifest still
hardcodes the original package in **6 fully-qualified references**: `MyLogApplication` (line 17),
`parentActivityName` ×4 (lines 85–94), and `ReminderReceiver` (line 99). A maintainer renaming the
package outside Studio's refactor — or whose refactor misses XML string attributes like
`parentActivityName` — would ship a manifest pointing at nonexistent classes:
`ClassNotFoundException` for the `Application` class at launch, or broken up-navigation and
reminders.

**Fix**: restore the manifest to the checklist (both the namespace *and* the fully-qualified
component names).

---

## Verified clean

Recorded so the next reviewer doesn't re-derive it.

**Head commit (`3dc8516`), pass 1:**

- `gradle-wrapper.jar` is **byte-identical** to the official Gradle 8.14.3 wrapper jar — SHA-256
  `7d3a4ac4de1c…` matches the checksum published by services.gradle.org.
- `gradlew` / `gradlew.bat` match the exact output of the 8.14.3 `wrapper` task (checked against
  `WrapperGenerator` in the distribution); the only delta vs upstream's own scripts is upstream's
  local customization, not part of the generated template.
- `.gitattributes` is safe against the actual repo state: no CRLF or mixed-ending blobs exist,
  `gradlew.bat` is LF-in-index / CRLF-on-checkout as intended, no `.gitignore` conflict with the jar.
- README factual claims cross-checked against `mobile/build.gradle` (SDK/Java versions, `fileTree`
  removal, `namespace`), the manifest (`${applicationId}.provider` makes the
  `applicationIdSuffix ".debug"` advice sound), the sources (exactly 6 `PendingIntent` sites, all
  in `reminders/`), and `docs/history/REVIVAL_PLAN.md` (Build-Tools 35.0.0 is AGP 8.13's default).

**Full diff (`main...HEAD`), pass 2:**

- The brace-level unwrapping in `SettingsActivity`, `LogTabsFragment`, and `AccountListFragment`
  preserves the premium branches verbatim, including listener return values.
- `UpgradeHelper.initialize()` reproduces the old premium-branch Dropbox bootstrap exactly: same
  `isOnline()` gate, same syncEnabled-or-token-sentinel condition, same call order
  (`initializeDropboxV2` → sync → `autoBackup`), same callers (`MainActivity`, `SettingsActivity`).
  The dropped `navigator.updateOptionsMenu()` call is moot — the Upgrade menu item it refreshed no
  longer exists.
- The Dagger graph never referenced the deleted classes; `ActivityComponent` needed no changes.
- No dangling code or resource references to anything removed (grep-verified, consistent with the
  green build).
- No `logAnalytic` call site had side-effectful arguments — the one that did (the dev-override
  toggle) was deleted whole in Step 7c, so emptying the helper bodies changed no behavior.
- The title-string-keyed navigation and `DBAdapter` — the two load-bearing traps documented in
  `CLAUDE.md` — are untouched by this PR.

---

## Disposition

| # | Finding | Severity | Suggested action |
|---|---|---|---|
| 1 | `ReminderReceiver` exported | Medium | Revert to `exported="false"`; correct plan + PR body |
| 2 | Rating prompt wording | Low | Swap to the generic strings; delete `_pro_` strings |
| 3 | README rename checklist | Low | Re-add the manifest to the checklist |

All three were applied on `revival` in the commit that adds this document, together with the
`docs/history/REVIVAL_PLAN.md` correction for finding 1's false premise. The PR description's "Two real bugs
fixed" section was corrected to one.
