# Modernization and hardening — the plan for finishing the revival

> **Executed, on the `modernize` branch, 2026-09-18/19.** All ten steps are done and each is its
> own commit. What each produced is in `PROGRESS.md`; this file is the plan as written, kept for
> *why* each decision was made. Four things went differently from the plan and are worth knowing:
>
> 1. **`androidx.core` and AndroidPlot could not take their newest versions.** Both now require
>    compileSdk 37, which this project does not use. They are pinned with the reason in
>    `mobile/build.gradle`, and raising compileSdk is left as its own change.
> 2. **The API 36 UI pass could not be run.** That emulator image renders black and takes no touch
>    input on this host. What *was* verified there: the system log shows the app registering an
>    `OnBackInvokedCallback`, which is precisely what Step 4 existed to make possible, and four
>    test classes run green. The rest of that pass belongs with the maintainer's phones.
> 3. **Three real bugs were caught by running the app**, none of which any test would have found:
>    a back callback that was written but never registered, a background loader that shut its
>    executor down for good and crashed on the way back from the editor, and two settings
>    activities that kept a theme with an action bar while supplying their own toolbar.
> 4. **AGP 9 needed two things the notes did not mention**: `resValues` must be enabled explicitly,
>    and `proguard-android.txt` is refused outright.

Written 2026-09-18 against `main` at `bdb7699` (PR #15 merged). The `launch` branch is stashed
work and is out of scope here, by the maintainer's instruction.

Three decisions from the maintainer frame everything below, and seven open points were settled
with him on the same day (marked **Decided** in the steps):

| Question | Answer |
| --- | --- |
| How far does modernization go? | **All the way**: ButterKnife, Iconify, `android.preference`, the AndroidX and Material pins, the vestigial analytics parameters, `UpgradeHelper`, and the remaining deprecated APIs. |
| What is "finished"? | **A Google Play listing.** So a signed, shrunk App Bundle, keep rules that survive R8, and a manifest that says only what the app does. |
| Tests and automation? | **As they are**: the instrumentation suite on the `Pixel_6` emulator before each merge, lint at 0 errors. No CI, no JVM tests. |

Nothing in this plan changes what the app does for the user. Every step is a like-for-like
replacement, checked on the emulator against the realistic test database, and each one is a PR of
its own so a regression is one revert away.

---

## Where the code stands

18 028 lines of Java in 68 files; 251 instrumentation tests in 40 classes; lint 0 errors and
222 warnings. The app is correct and verified. What is *old* is the scaffolding around it, and it
is old in a way that has now started to cost:

| Legacy piece | Extent | Why it must go |
| --- | --- | --- |
| **ButterKnife 7.0.1** (2015) | 34 `@Bind`/`bind` uses in 7 files, plus a dead import in `LogTabsFragment`; 30 `case R.id.…` labels in 5 files | Both need final `R` fields, which is the only reason for `android.nonFinalResIds=false`. AGP 9 makes the compile-time `R` class the default and its notes say "refactor switch cases using R class fields"; the flag is expected to go with jetifier in AGP 10. |
| **Iconify 2.2.2** (2016) | 31 `IconDrawable` constructions in 13 files; category icons are stored in the database as font keys (`fa-tags`, `md-local-dining`, `mdi-cat`); 96 keys in `icon_list.xml` | Its only dependency is `com.android.support:support-v4:22.2.1`, which is the only reason `android.enableJetifier=true` exists. Jetifier is removed in AGP 10. |
| **AppCompat 1.2.0 / Material 1.2.1** (2020) | Pinned because Material 1.5+ crashes at launch against `Theme.AppCompat` | Drags every AndroidX library down with it: `activity` 1.0.0, `fragment` 1.1.0, `core` 1.3.0, `lifecycle` 2.1.0, `recyclerview` 1.1.0. `activity` 1.0.0 predates back callbacks, which is why the manifest carries `enableOnBackInvokedCallback="false"`, and that attribute has an expiry date. |
| **`android.preference`** | 21 files import it. Four *are* the settings framework (`SettingsActivity`, `AccountPreferencesActivity`, `AppCompatPreferenceActivity`, `TimePreference`); the other 17 only call `PreferenceManager.getDefaultSharedPreferences`, 31 times | Deprecated since API 29. Still in `android.jar`, so not a deadline, but the `PreferenceActivity` header machinery is what made the nested-screen bug and the edge-to-edge bug possible, and it is why every settings screen needs its own inset fix. |
| **`androidx.security:security-crypto 1.1.0`** | Holds the Dropbox `DbxCredential` in `EncryptedSharedPreferences` (`DropBoxHelper`) | **Deprecated in 1.1.0, all APIs, no further releases**: "in favour of existing platform APIs and direct use of Android Keystore". It keeps working, but a Play listing should not ship a library its own author has abandoned. |
| **Deprecated platform APIs** | `AsyncTask` ×2 (`LogListFragment`, `LogsCalendarFragment`); `ProgressDialog` ×3 (Dropbox download, backup list, new backup); `startActivityForResult` ×4; `Bundle.getSerializable` ×5; `Fragment.onAttach(Activity)` ×2; `onActivityCreated` ×28; `setHasOptionsMenu` ×15; `NetworkInfo` ×1; `ListActivity` ×1; `android.app.AlertDialog` ×8 imports; `FragmentStatePagerAdapter` ×1; `@TargetApi(HONEYCOMB)` ×5 | None is broken. Each is one of the reasons the AndroidX bump was "risky": the replacements did not exist at the pinned versions. Once Step 4 lands they do. |
| **Dead code and resources** | `TestFragment` + `fragment_test.xml` + its `inject`; `analytics_tracker.xml` (a Google Analytics tracking id, referenced by nothing); `drawables.xml` (six aliases, unused); `drawable-v21/` and `values-v21/` (minSdk is 26); `ic_action_done`, `ic_menu_add`, `ic_menu_categories` PNGs at five densities, unreferenced; `WAKE_LOCK` permission (nothing takes a wake lock; `RTC_WAKEUP` alarms do not need it); `UpdateManager` (a "what's new" dialog fed by `update_msg`, which still describes the 2020 release) | Each is a question a Play reviewer or a future maintainer would have to ask. |
| **Vestigial** | `AnalyticsHelper.logAnalytic(…, Object mFirebaseAnalytics, …)` at ~70 call sites in 11 files; `UpgradeHelper` (bootstraps Dropbox, nothing else); `MainActivity.KEY_DEBUG` gating 132 log calls, 14 of them tagged `GGG` | Named for what they were. |
| **Build** | `proguard-android.txt` (AGP 9 refuses it: `android.r8.proguardAndroidTxt.disallowed`); no `signingConfig`; `minifyEnabled false`; `assembleRelease` never run; Gradle 8.14.3 / AGP 8.13.2 (AGP 9 needs Gradle 9.1) | Play wants a signed AAB. |
| **Manifest** | `allowBackup="false"` without `dataExtractionRules`; `http`/`https` `VIEW` filters for `.edb` that `MainActivity` refuses on arrival (only `content://` is accepted) | The filters advertise something the app cannot do. |

### What is deliberately *not* in this plan

Each of these was considered and is left out on purpose. Reopen only with a reason.

- **Kotlin, Room, Compose, Jetpack Navigation, DataStore, WorkManager.** Rewrites, not modernization.
  The Java, the raw `DBAdapter` and the hand-wired Dagger are all current and tested; the
  `DropboxTask` executor is deliberately single-threaded and its ordering guarantees are load-bearing.
- **A Material 3 restyle, or any visual change.** Step 4 moves the theme to the MaterialComponents
  *Bridge* variant, which is designed to change nothing on screen. Whether the app should then look
  different is a product decision the maintainer makes separately, with mockups, as PR #10 and
  PR #15 were done.
- **ViewPager → ViewPager2.** `androidx.viewpager` 1.0.0 is stable, not deprecated; only
  `FragmentStatePagerAdapter` carries a deprecation. The Records pager has a 1000-page window
  centred on `MID_PAGE` and is rebuilt on resume (F13, still open) — moving it is a behaviour change
  with no payoff until F13 is decided.
- **`DATABASE_VERSION`, the positional column constants, `COLUMN_LOG_IMAGE`.** Schema stays at 1;
  `CLAUDE.md` says why.
- **Record-level Dropbox sync, repairing drifted series, the amount field's locale (F18), the
  main-thread import staging.** Real, tracked in `PROGRESS.md`, and none is modernization.
- **RTL (`RtlHardcoded`, 57 warnings).** The app is English-only by decision; `supportsRtl` is
  true but no RTL locale can reach it. Left as warnings.
- **JVM tests, CI.** The maintainer's call.
- **Java 21 bytecode and Dropbox SDK 8.x.** 8.x needs Java 21; the project compiles for 17 and
  7.0.0's API is identical for everything the app uses. Revisit when 7.x stops receiving fixes.

---

## Rules for executing this plan

1. **One step, one branch, one PR**, from `main`, in the order below. Step 4 is the pivot: nothing
   after it works at the pinned versions, and it is the step most likely to need a revert.
2. **Every PR is gated on**: `:mobile:assembleDebug`, `:mobile:lintDebug` at **0 errors**, the
   full instrumentation suite at 0 failures on `Pixel_6` (API 33), and the emulator checks listed
   for the step. Steps 4, 5 and 9 also run on an **API 36 emulator** (create one beside `Pixel_6`;
   predictive back, edge-to-edge and the release build all behave differently there).
3. **Decided: emulators only until the release build.** No step goes to a physical phone;
   the one phone pass is the maintainer's, on the signed build of Step 9, on both phones. That
   makes the screenshot comparisons and the API 36 emulator runs below the only guard against a
   look-and-feel regression for eight steps, so they are not optional.
4. **Screens must look the same.** For every step that touches a layout, theme or icon, take a
   screenshot of each affected screen on `main`'s build and on the branch, and compare. A
   difference is a finding: fix it or take it to the maintainer, never wave it through.
5. **Warnings only go down.** Record the lint warning count in the PR; the expected drops are
   noted per step. A new warning category is a finding.
6. **The load-bearing rules in `CLAUDE.md` are not up for review here** — the six `FileHelper`
   rules, the shared connection and its locks, the period arithmetic, the series origin recovery,
   `setupReminder`'s guard, the Back cap, the sync rules. A modernization step that needs to touch
   one of those files touches only the API being replaced.
7. **Upgrade in place, every time.** Install the `main` APK on the emulator with the test database
   and a Dropbox login, then install the branch over it. Data, preferences and the credential must
   all survive. Step 7 is the one step that migrates stored data, and it says how.
8. **Update `CLAUDE.md` in the same PR** that makes a constraint obsolete (the ButterKnife,
   `nonFinalResIds`, jetifier, predictive-back and `android.preference` paragraphs each have a step
   that retires them) and add the constraint that replaces it. `PROGRESS.md` gets a section per
   merged PR, as before.

### Stop and ask the maintainer when

- A screen changes appearance and the cause is a library default, not a bug (Step 4).
- Anything a release build does differently from the debug build that a keep rule does not explain.

---

## Order, and why

```
0  Branch, baseline, API 36 emulator
1  Dead code, dead resources, vestigial names        — small, safe, shrinks everything after it
2  ButterKnife → viewBinding; switch → if            — no library moves; retires nonFinalResIds
3  Iconify → vendored icon fonts                     — no library moves; retires jetifier
4  Theme bridge; AndroidX and Material forward;      — THE PIVOT; everything else waits for it
   OnBackPressedCallback; manifest opt-out removed
5  android.preference → AndroidX Preference          — needs Step 4's AppCompat
6  Remaining deprecated APIs                         — needs Step 4's activity/fragment
7  Dropbox credential off security-crypto           — independent, but after 4 so it is tested once
8  Gradle 9 / AGP 9, dependency bumps, flags removed — needs 2 and 3 done
9  Release build: signing, R8, AAB, manifest, rules  — needs everything above, tested last
10 Docs, archive
```

Steps 1–3 move no library and are the cheap wins; if Step 4 has to be reverted, they still stand.
Step 8 is placed late because AGP 9 is a build-system change and should not be mixed with a
dependency change, so that a failure is attributable. Step 9 is last because R8 must be tested
against the final code, not code that Steps 5–7 will still rewrite. **Decided: the Play listing
waits for the whole plan.** The first upload is the modernized, shrunk app; no early signed
bundle, no internal-testing track before Step 9.

---

## Step 0 — Branch, baseline, API 36 emulator

- Build `main` and keep its debug APK as the *before* for every upgrade-in-place check.
- Record: suite 251/0, lint 0/222, the per-category lint counts (in "Where the code stands").
- Create `Pixel_6_API36` (Android 16, x86_64) beside `Pixel_6`; confirm the suite runs on it too.

## Step 1 — Dead code, dead resources, vestigial names

**Change**

- Delete `TestFragment`, `fragment_test.xml`, and `ActivityComponent.inject(TestFragment)`.
- Delete `xml/analytics_tracker.xml` and `values/drawables.xml`.
- Merge `drawable-v21/` into `drawable/` and `values-v21/styles.xml` into `values/styles.xml`
  (the v21 `AppTheme.NoActionBar` is the real one; the base version becomes it).
- Delete the three unreferenced PNG sets. The twelve referenced PNGs stay for now: Step 3 decides
  icon by icon whether a vector replaces them.
- Remove `WAKE_LOCK` from the manifest.
- **Decided: delete `AnalyticsHelper` outright** — the class, its ten `TAG_ANALYTICS_*`
  constants, every `logAnalytic` call (~70 sites in 11 files) and every `mFirebaseAnalytics`
  field. Nothing has recorded anything since Phase 1, and the calls only add noise. Where a call
  was the only statement in a branch, keep the branch's behaviour and drop the branch.
- Rename `UpgradeHelper` → `DropboxBootstrap`; replace its `NetworkInfo` check with
  `NetworkCapabilities` (`NET_CAPABILITY_INTERNET` + `VALIDATED`).
- `ReminderReceiver.onReceive`: check `intent.getAction()` for `BOOT_COMPLETED` before treating a
  missing extra as the boot case (lint `UnsafeProtectedBroadcastReceiver`). The receiver is not
  exported, so this is hygiene, not a hole.
- `MainActivity.KEY_DEBUG` → `BuildConfig.DEBUG`, and the `GGG` tags → the class name.
- **Decided: delete `UpdateManager`**, the `update_msg` and `continue_msg` strings if nothing
  else uses them, its `LaunchManager` field and call, and the `prefs_key_saved_version` preference
  it wrote. Play shows release notes; a string nobody remembers to rewrite would otherwise show
  the 2020 notes after every update.

**Check**: build, lint (expect `ObsoleteSdkInt` −2, `UnsafeProtectedBroadcastReceiver` −1), suite,
a launch on the emulator, a fired reminder, a reboot of the emulator with reminders on (boot path).

## Step 2 — ButterKnife → view binding; `switch` on ids → `if`

**Change**

- The 7 files: `NewLogFragment` (18), `LogListFragment` (4), `MainActivity` (4), and 2 each in
  `AccountListFragment`, `LogsCalendarFragment`, `NewCategoryFragment`, `TagListFragment`. Copy the
  pattern `LogTabsFragment` and `SummaryFragment` already use: a `binding` field set in
  `onCreateView`, nulled in `onDestroyView`. `MainActivity` gets `ActivityMainBinding`.
- Remove the dead import in `LogTabsFragment`.
- The 30 `case R.id.…` labels in `FilterDialogFragment`, `GroupingDialogFragment`,
  `NewLogFragment`, `Navigator`, `SortingDialogFragment` → `if`/`else if` chains.
- Remove both ButterKnife lines from `mobile/build.gradle` and `android.nonFinalResIds=false` from
  `gradle.properties`. Prove the APK is clean: unzip, grep the dex for `butterknife`.

**Check**: build, lint (expect `NonConstantResourceId` 56 → 0), suite, and a walk of each of the 7
screens on the emulator: New Record with repeat on and off, a category edit, an account edit, the
calendar, the list's selection bar, the drawer. `CLAUDE.md`: retire the ButterKnife and
`nonFinalResIds` paragraphs.

## Step 3 — Iconify → vendored icon fonts

**Decision.** Category icons live in the database as font keys, and the 39 real series and 2544
test records reference keys that any replacement must resolve. Two routes:

| | Recommended | Alternative |
| --- | --- | --- |
| **Vendor the fonts and a ~150-line `IconDrawable`** into `de.timowa.expenselog.icons`: the three `.ttf` files (FontAwesome 4.4, Material Icons, Material Design Icons 1.2.65) in `assets/`, a generated `enum` per font mapping key → code point (Iconify's own enums, Apache 2.0), and a `Drawable` that paints one glyph with `Paint.setTypeface`. Same keys, same rendering, every existing database keeps its icons. | Convert the 96 listed keys to vector drawables and map key → resource. Rejected: a key in an old database that is not in `icon_list.xml` (the default arrays in `settings_keys.xml` are a separate list) would render nothing, and 96 hand-checked vectors is a week of work for no user-visible gain. |

**Change**

- Add the package, the fonts and their licences (FontAwesome SIL OFL 1.1 for the font, Material
  Icons Apache 2.0, MDI SIL OFL 1.1); extend `NOTICE` (Iconify is already named there).
- Replace the 31 `IconDrawable` sites and the three `Iconify.with(...)` registrations; keep the
  builder methods actually used (`sizeDp`, `colorRes`, `color`, `actionBarSize`, `key()`).
- Remove the three Iconify dependencies and `android.enableJetifier=true`. Prove it: the
  dependency tree has no `com.android.support` entry, the dex has no `joanzapata`.
- Decide the twelve remaining PNG icons: replace each with a vector where a Material glyph exists
  (most are `ic_action_*`/`ic_menu_*` from the 2015 icon pack), keep any that has no equivalent.

**Check**: build, lint, suite, and an `EnglishTextTest`-style instrumentation test that resolves
every key in `icon_list.xml` and both default arrays to a glyph (fails on any missing key). On the
emulator: the category grid in New Category (all 96), the drawer, the toolbar icons, the selection
bar, the series dialogs' three coloured icons, a record row for each font family. Screenshot
compare against `main`. `CLAUDE.md`: retire the jetifier note.

## Step 4 — Theme bridge, AndroidX and Material forward, real back handling

This is the step the SDK 36 plan deferred and the reason the manifest opt-out exists.

**Change**

1. `AppTheme` parent → `Theme.MaterialComponents.Light.DarkActionBar.Bridge`;
   `AppTheme.AppBarOverlay` → `ThemeOverlay.MaterialComponents.Dark.ActionBar`;
   `AppTheme.PopupOverlay` → `ThemeOverlay.MaterialComponents.Light`;
   `ExpenseIncomeSwitch` → the Bridge light theme. The Bridge variants exist precisely so an
   AppCompat-themed app can take current Material without restyling; the Material 1.5+ launch
   crash is the Bridge's reason to exist.
2. `appcompat` 1.2.0 → 1.8.0, `material` 1.2.1 → 1.14.0, and **explicit** `activity`,
   `fragment`, `core`, `lifecycle-runtime`, `recyclerview`, `coordinatorlayout`, `viewpager` at
   their current stable versions, so nothing resolves by accident again.
3. `MainActivity.onBackPressed()` → an `OnBackPressedCallback` registered in `onCreate` that closes
   the drawer if open, else runs `backPressCount`/`backLeavesApp`. Remove
   `android:enableOnBackInvokedCallback="false"` and the manifest comment. The selection bar's
   action mode: AppCompat 1.6+ closes an action mode through the dispatcher itself; confirm Back
   still ends the selection *without* reaching the callback (the exit cap must not count it).
4. `Fragment.onAttach(Activity)` → `onAttach(Context)` in the two picker fragments (the old
   overload is gone from current `fragment`).
5. `ChipGroup`/`Chip` become available; `WrapRowLayout` may be retired for the filter chips **only
   if** the screenshot is identical. Otherwise leave it; it is 80 lines and works.

**Check**: the whole suite on API 33 *and* API 36 (`BackNavigationTest` on both);
edge-to-edge on API 36 (`AppCompatPreferenceActivity`'s inset fix is still in place until Step 5);
predictive-back gesture on API 36 from a sub-screen, from a drawer screen, with the drawer open,
with a selection active; rotation everywhere; the `SyncConflictDialog`, the series dialogs, the
export dialog, the date and time pickers (`AppLocale.datePicker` builds them against the theme);
screenshot compare of every screen. Upgrade in place. `CLAUDE.md`: retire the predictive-back
paragraph; add "the theme is a MaterialComponents Bridge — do not move to a non-Bridge parent
without a screenshot pass".

**If it has to be reverted**: revert the whole PR. Do not keep the dependency bump without the
theme, or the theme without the bump.

## Step 5 — `android.preference` → AndroidX Preference

**Change**

- `SettingsActivity` becomes an `AppCompatActivity` with a `Toolbar`, hosting
  `PreferenceFragmentCompat`s: a root fragment built from `pref_headers.xml` rewritten as a
  `PreferenceScreen` of `<Preference app:fragment="…">` rows with the same titles and icons, and
  `onPreferenceStartFragment` to open each. The five fragments (Preferences, Reminders, Data & sync,
  Accounts, About) keep their names and their `pref_*.xml`, with `SwitchPreference` →
  `SwitchPreferenceCompat`. `AccountPreferencesActivity` follows the same shape; its
  `EXTRA_SHOW_FRAGMENT` intent becomes a fragment argument.
- `TimePreference` → `DialogPreference` + a `PreferenceDialogFragmentCompat` showing the
  `TimePicker`; same key, same stored format, so `ReminderManager` reads what it read before.
- Delete `AppCompatPreferenceActivity`. Its edge-to-edge fix is no longer needed: an
  `AppCompatActivity` with a `CoordinatorLayout`/`AppBarLayout` root, as `MainActivity` has, is
  inset by the framework; verify on API 36 rather than assume.
- The 17 `PreferenceManager.getDefaultSharedPreferences` callers switch to
  `androidx.preference.PreferenceManager`. **No data migration**: both resolve to
  `<package>_preferences` in `MODE_PRIVATE`; pin that with a test that writes through one and reads
  through the other.
- `Navigator.openSettings(activity, fragmentName, titleRes)` and the reminder notification's
  Settings action keep landing on the reminder fragment. `handleDeclinedAuth()` keeps being called
  from the Data & sync fragment's `onResume` after `completeDropboxV2Init()`.
- `SettingsHeadersTest` is rewritten against the root `PreferenceScreen` (every `app:fragment`
  names a real `PreferenceFragmentCompat`).

**Check**: every settings screen and sub-screen on API 33 and API 36 (insets!), the reminder
ten-check pass from `RECOVERY_PLAN.md`, the notification's Settings action, the Dropbox login both
ways (complete, decline — the switch must turn itself off), Accounts add/edit/delete, About, and
every preference value read back after an upgrade in place. `CLAUDE.md`: retire the nested
`<PreferenceScreen>` and `isValidFragment` paragraphs; add the new shape.

## Step 6 — Remaining deprecated APIs

All mechanical once Step 4 is in; group them in one PR, one commit each.

- The 2 `AsyncTask`s → a small `BackgroundLoad` (an `Executor` + main `Handler`, the shape
  `DropboxTask` has, but a fresh single-thread executor per screen so it does not queue behind an
  upload). `LogsCalendarFragment`'s rule stands: capture on the main thread, touch no fragment
  from the background.
- 3 `ProgressDialog`s → an `AlertDialog` with a `ProgressBar` built by `DropboxDialogs`, dismissed
  through it as now.
- 4 `startActivityForResult` sites → `ActivityResultLauncher`s registered at construction
  (`DropBoxHelper` and `SpreadsheetHelper` are `@Inject`-constructed with the activity, so register
  in the activity and hand the launcher in). The staged-export path in `SharedPreferences` stays.
- 5 `getSerializable` → `BundleCompat.getSerializable` with the type.
- `BackupListActivity` off `ListActivity` onto `AppCompatActivity` + `RecyclerView`.
- 28 `onActivityCreated` → `onViewCreated`; 15 `setHasOptionsMenu` → `MenuProvider`.
- 8 `android.app.AlertDialog` imports → `androidx.appcompat.app.AlertDialog`, so every dialog takes
  the app theme.
- Remove the 5 `@TargetApi(HONEYCOMB)` and the `SDK_INT >= O` check.

**Check**: lint (expect `UseRequiresApi` −5, `ObsoleteSdkInt` → 0, `StaticFieldLeak` −2), the
suite, and on the emulator: the Dropbox backup list and a restore from it, a new backup with its
progress dialog, both exports with Save and Cancel, an `.edb` import from Settings, filter/sort/
grouping dialogs, the list and calendar loading on a fresh open.

## Step 7 — The Dropbox credential off `security-crypto`

**Decided: require one re-login.** The library goes in this step, with no migration path.

- A `KeystoreCipher` of ~100 lines: an AES-256-GCM key in `AndroidKeyStore`, a random IV per
  write, `Base64` into plain `SharedPreferences` under a **new** name (`dropbox_credential`), so
  the old `dropbox_secure_prefs` file is never read again. Delete that file and its Keystore
  master key alias (`_androidx_security_master_key_`) on first open, so nothing decryptable is
  left behind.
- With no credential, sync stays off until the user logs in again: `hasStoredCredential()` is
  false, and `DropboxBootstrap.initialize()` only starts an auth when the sync preference is on.
  On the maintainer's phones that preference *is* on, so the first launch after the update opens
  the Dropbox login once, as it does on a fresh install. Declining it turns the preference off
  (`handleDeclinedAuth`), which is the existing behaviour and must be re-verified here.
- The release notes and `PROGRESS.md` say plainly that this update asks for one Dropbox login.

Either way the `DropboxSyncRulesTest`/`SyncConflictDialogTest` do not change; add a test that a
credential round-trips through the new store and that a tampered ciphertext reads as *no credential*
rather than throwing (which would relaunch the auth — the `handleDeclinedAuth` lesson).

**Check**: upgrade in place with a logged-in `main` build; the login must be asked exactly once,
and after it the old preference file and key alias must be gone (`adb shell run-as … ls
shared_prefs`). Login from scratch; decline (preference off, no relaunch loop); a kill and
relaunch keeps the session; two-device handover once. Confirm the dex has no `security.crypto`.

## Step 8 — Build system: Gradle 9, AGP 9, bumps, flags

- `proguard-android.txt` → `proguard-android-optimize.txt` first (AGP 9 refuses the former).
- Gradle 8.14.3 → 9.1+; AGP 8.13.2 → the current 9.x. Built-in Kotlin is on by default and harms
  nothing here (no Kotlin source; the Dropbox Android SDK is a compiled Kotlin artifact). Read the
  AGP 9 notes' "Gradle properties with new defaults" table at the time and check each against
  `gradle.properties`; `android.enableAppCompileTimeRClass` is the one that matters and Step 2 has
  already paid for it.
- Dagger 2.57.2 → 2.60.1; AndroidPlot 1.5.11 → 1.6.0 (check the graph's XML attributes still
  apply); `androidx.test` to current.
- Remove `android.nonFinalResIds` (Step 2) and `android.enableJetifier` (Step 3) if not already
  gone; keep `android.useAndroidX`.
- `README.md`'s toolchain table.

**Check**: clean build, lint (expect `GradleDependency`/`NewerVersionAvailable`/
`AndroidGradlePluginVersion` → 0), the suite, the graph screen. Nothing else should move.

## Step 9 — The release build

- **Signing**: `keystore.properties` (already gitignored) read the way `dropbox.appKey` is, with
  `SIGNING_*` environment fallbacks; a `signingConfigs.release` that is *absent*, not failing,
  when the file is missing, so a contributor can still `assembleDebug`. The upload key is the
  maintainer's; Play App Signing holds the app key.
- **R8**: `minifyEnabled true`, `shrinkResources true`, and keep rules with a reason each:
  AndroidPlot's XML configurator sets attributes by reflection (`-keep class com.androidplot.** { *; }`);
  the preference fragments are named in XML (`app:fragment`) and resolved by name; `TimePreference`
  is inflated by name; the Dropbox SDK's consumer rules (check the AAR carries them; add
  `-dontwarn` for what it does not); Dagger needs none; `Serializable` bundles are in-process only.
  Keep the mapping file: the AAB carries it to Play for de-obfuscated crash reports.
- **`bundleRelease`** is the artefact; `assembleRelease` only to install on the emulator.
- **Manifest. Decided: backups stay off.** `dataExtractionRules` + `fullBackupContent` that
  exclude everything, beside `allowBackup="false"`, so Android 12+ and older agree that no cloud
  backup or device transfer copies the database or the credential (the Keystore key would not
  transfer anyway, which is a second reason to be explicit). Android Auto Backup of the database
  was offered and declined: nothing leaves the device unless the user chooses; the app-private
  local backups stay a known limitation in `PROGRESS.md`. **Decided: remove both `http`/`https` `.edb` filters.** `MainActivity` refuses
  every non-`content://` URI before it offers to import, so they only put the app in choosers it
  then backs out of. The `content://` filters and the Gmail `application/octet-stream` one stay,
  and the manifest comment about the retired `file://` branch grows a line about these two.
  Done in **Step 1**, with the other manifest hygiene; listed here because it is release-facing.
- `versionCode` policy: an integer incremented per upload, `versionName` per release; both in
  `build.gradle`, not derived from git.
- Debug-only things must be debug-only: `BackNavigationTest`'s host activity is already
  `src/debug`; confirm nothing else leaks (`grep` the release manifest).

**Check — the release APK on API 33 and API 36 (upgrade in place over `main`'s debug build is
not possible, the signature differs, so from scratch and then the `.edb` import), and then the one
phone pass, by the maintainer, on the Pixel 4a and the Jelly Star**: the full suite cannot run
against a release build, so this is the manual pass, on the emulators first and then the phones: every drawer screen, New Record with a
series, the three-choice dialogs, Undo, both exports, import, the local backup rotation, Dropbox
login/sync/backup list/restore, the reminder and both notification actions, Settings end to end,
About. Read the merged release manifest. Unzip the AAB and confirm the dex has no `butterknife`,
`joanzapata`, `support-v4`, `security.crypto`.

**Play-side, not code**: privacy policy URL (the app collects nothing; Dropbox is the user's own
account), Data safety answers, the 512×512 icon already in `fastlane/`, the About links that
become live on publication.

## Step 10 — Documents

- `CLAUDE.md`: the paragraphs retired by Steps 2, 3, 4, 5 and the new constraints they leave
  (Bridge theme; `OnBackPressedCallback` is the only back handler; the preference fragment shape;
  the icon package and its key format; the keep rules and why each exists; the signing file).
- `README.md`: toolchain table, the "still outstanding" table (ButterKnife and
  `android.preference` rows go), the signing paragraph.
- `PROGRESS.md`: one section per merged PR, the "What is open" table, and the lint/suite numbers.
- Move this file to `docs/history/MODERNIZATION_PLAN.md` with a note of what each step actually
  produced, as the earlier plans were.

---

## What "done" looks like

| | Now | After |
| --- | --- | --- |
| Libraries older than 2020 | ButterKnife 7, Iconify 2.2.2, AppCompat 1.2.0, Material 1.2.1 | none |
| Deprecated libraries | `security-crypto` | none |
| `gradle.properties` flags for old libraries | `nonFinalResIds`, `enableJetifier` | none |
| Manifest opt-outs with an expiry | `enableOnBackInvokedCallback="false"` | none |
| `android.preference`, `AsyncTask`, `ProgressDialog`, `startActivityForResult`, `ListActivity` | 21 + 2 + 3 + 4 + 1 files | 0 |
| Lint warnings | 222 | under 100, all of them RTL or accessibility hints |
| Release artefact | none ever built | a signed, shrunk AAB, exercised on two API levels |
| Tests | 251, 0 failures | 251 + the icon-key, preference-file and credential tests, 0 failures |
| Decided on 2026-09-18 | | `UpdateManager` and `AnalyticsHelper` deleted; `http`/`https` filters removed; the credential store replaced with one re-login, no migration; backups stay off; the listing waits for the whole plan; phones see only the release build |
