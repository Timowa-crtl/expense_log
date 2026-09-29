# Bumping to SDK 36

**Goal: move the app from compileSdk/targetSdk 35 to 36, without losing anything the first three
phases bought.** The bump only — everything else Play submission needs is out of scope here.

Google Play's target API requirement moved on **31 August 2026**: new apps and app updates must
target **Android 16 (API 36)** or higher to be submitted at all. That date has passed. Existing apps
may stay at 35 to remain available, but this app has no Play listing — `versionCode 1` under a new
`applicationId` is a *new app*, so 36 is not optional. Google will grant an extension to
1 November 2026 on request; treat that as a safety net, not the plan.

This is a **compatibility** change, not a modernisation one. Everything the previous phases pinned
deliberately — ButterKnife 7, `android.preference`, AppCompat 1.2.0, Material 1.2.1, Dropbox 7.0.0,
`android.nonFinalResIds=false` — stays pinned. See *Deliberately not in scope* at the end.

---

## What the bump actually costs

Four things were checked before writing this, and three of them came back cheaper than expected.

### AGP 8.13.2 already supports API 36 — no AGP 9

This was the pivotal question, because `REVIVAL_PLAN.md` establishes that **AGP 9
breaks all 60 ButterKnife/`case R.id.…` sites at once** by removing `android.nonFinalResIds=false`.
If compileSdk 36 required AGP 9, this bump would have swallowed the ButterKnife migration whole.

It does not. AGP 8.13's release notes state the maximum supported API level is **36.1**. The
toolchain stays exactly where it is: Gradle 8.14.3, AGP 8.13.2, JDK 21, Java 17 source/target. Only
`compileSdk` and `targetSdk` move, and the SDK packages behind them get installed.

### The one real breakage: predictive back

For apps targeting SDK 36 on Android 16+, predictive back animations are **on by default**, and as a
direct consequence `Activity.onBackPressed()` is no longer called and `KEYCODE_BACK` is no longer
dispatched.

`MainActivity` overrides `onBackPressed()` (`MainActivity.java:288`) and that override is
load-bearing — it closes the nav drawer on back, and it runs the `backPressCount` logic that decides
between popping the fragment back stack and finishing the activity. It is the only override in the
codebase; the three other `onBackPressed` references (`SettingsActivity:177`,
`AccountPreferencesActivity:65`, `NewCategoryFragment:261`) are *calls*, which still execute
normally.

Normally AndroidX absorbs this: `ComponentActivity` registers an `OnBackInvokedCallback` and routes
it to `OnBackPressedDispatcher`. **This app gets no such absorption.** `:mobile:dependencies` on
`debugRuntimeClasspath` resolves `androidx.activity:activity:` **`1.0.0`** — dragged in transitively
by `fragment:1.1.0` under `appcompat:1.2.0`. `OnBackInvokedCallback` support landed in
`androidx.activity` **1.6.0**. So on an Android 16 device at targetSdk 36, nothing anywhere in the
process registers a back callback, and the observable result is:

- back does not close the drawer;
- back does not pop the fragment back stack — from any sub-screen it leaves the app.

**Fix for this bump: `android:enableOnBackInvokedCallback="false"` on `<application>`.** It is one
line, it restores the current behaviour exactly, and Google documents it as the supported temporary
opt-out for precisely this migration. It is explicitly temporary — a future API level removes it —
so it ships with a manifest comment saying what it defers and what pays it off. The real fix
(`androidx.activity` ≥ 1.6 plus an `OnBackPressedCallback`) is a dependency bump against the
AppCompat/Material pins and is scoped as a follow-up below, not smuggled into the bump.

### Edge-to-edge is not new here, but the escape hatch disappears

Edge-to-edge enforcement arrived at **targetSdk 35**, which this app already targets, and the
manifest never carried `windowOptOutEdgeToEdgeEnforcement`. So the app is *already* edge-to-edge on
Android 15+ and targetSdk 36 changes nothing about that — except that at 36 the opt-out attribute is
disabled on Android 16 devices, so the escape hatch that exists today at 35 will be gone.

The catch is that **this has never been observed**: `PROGRESS.md` records verification on Pixel 4a
(Android 14) and Unihertz Jelly Star (Android 13). Neither enforces edge-to-edge. The app's
behaviour on Android 15 and 16 is currently unknown, not known-good.

Reading the layouts, the top edge should be fine — `activity_main.xml` sets
`fitsSystemWindows="true"` on the `DrawerLayout` and the `NavigationView`, and `app_bar_main.xml`
sets it on the `CoordinatorLayout` above the `AppBarLayout`. The **bottom** edge is the exposure:

- the `FloatingActionButton` at `layout_gravity="bottom|end"` in `app_bar_main.xml` — the classic
  casualty, it lands behind the gesture/navigation bar;
- `content_main.xml`'s `mainContentFragment` container, and therefore the bottom of every scrolling
  list, graph, calendar and summary inside `LogTabsFragment`.

This is a *look at it on an Android 16 screen* problem, not a *read the XML harder* problem. Step 3
is where it gets answered, and any fix is bottom-inset padding on the specific view that turns out
to be clipped — not a layout rewrite.

### Everything else on the API 36 list is a no-op here, and here is why

| Android 16 change for targetSdk 36 | Applies? | Why |
| --- | --- | --- |
| Orientation / resizability ignored on ≥600dp screens | No | No `screenOrientation`, `resizableActivity`, aspect-ratio attribute or `setRequestedOrientation()` call anywhere in the manifest or source. |
| `android:elegantTextHeight` ignored | No | Not used. |
| `scheduleAtFixedRate` runs one missed execution, not all | No | No `ScheduledExecutorService` in the codebase. `DropboxTask` uses a plain `ExecutorService` + `Handler`. |
| Health/fitness permission granularisation | No | No `BODY_SENSORS`, no sensors. |
| Bluetooth bond-loss intents | No | No Bluetooth. |
| `MediaStore.getVersion()` per-app | No | The image feature was deleted in Phase 2 Step 4b. |
| Local network permission | No | Dropbox is internet, not LAN. No mDNS/NSD. |
| Stricter intent matching (`intentMatchingFlags`) | No | Opt-in only, and not opted in. |
| Mali GPU syscall filtering | No | No native code, no GPU work. |
| 16 KB page-size alignment (Play, targetSdk 35+) | **Already satisfied** | The debug APK was unzipped and scanned: **zero `.so` entries**. No native libraries to align. |

Alarms are untouched by API 36, and the reminders remain inexact, so the app still needs no
exact-alarm permission — the `ReminderManager` comment stands.

---

## Steps

Each step is one commit and ends at a gate. Do not start the next step until the gate is green.
Builds run per `CLAUDE.md` — a `.bat` written to a Windows-visible path, executed via `cmd.exe`,
`run_in_background: true`.

### Step 0 — Install the SDK 36 packages

None of API 36 is on this machine. `Sdk/platforms/` holds `android-33`, `android-33-ext5`,
`android-34`, `android-35`; `build-tools/` holds `30.0.3`, `33.0.2`, `35.0.0`; `system-images/`
holds only `android-33`.

Install via `sdkmanager.bat` — and remember the `;` quoting trap from `CLAUDE.md`: write a `.bat`
file, do not pass the package name through a `cmd.exe /c` string, or the package name splits at the
semicolons and the pipeline still exits 0 while lying to you.

- `platforms;android-36`
- `build-tools;36.0.0` — AGP 8.13 defaults to 35.0.0, which will likely work, but pinning the
  matching build-tools removes a variable from every later step.
- `system-images;android-36;google_apis;x86_64`, then an AVD named `Pixel_6_API_36` beside the
  existing `Pixel_6`. **Keep `Pixel_6` (API 33).** It is the regression baseline, and it is where the
  49-test suite is known green.

**Gate:** `sdkmanager.bat --list_installed` shows all three, and the new AVD boots.

### Step 1 — compileSdk 36 alone, targetSdk still 35

Change exactly one line in `mobile/build.gradle`: `compileSdk 35` → `compileSdk 36`. Leave
`targetSdk 35`.

This is deliberately a separate commit from Step 2. It separates *"does this toolchain, this
jetifier setup, this ButterKnife annotation processor and this AppCompat 1.2.0 still compile against
android-36"* from *"does the app still behave correctly under API 36 semantics"*. If something
breaks here it is a build problem; if something breaks in Step 2 it is a behaviour problem. Debugging
them together is how a two-hour job becomes a two-day one.

Expect no source changes. `compileSdk` raises the `android.jar` compiled against; the deprecated
`android.preference` classes are still present in android-36, and nothing in the codebase uses an
API removed since 35.

**Gate:** `:mobile:assembleDebug` green; `:mobile:lintDebug` still **0 errors, nothing suppressed**
(a new lint check firing here is a finding to fix or justify in `mobile/build.gradle`, not to
suppress silently); `:mobile:connectedDebugAndroidTest` against the **existing `Pixel_6` (API 33)**
AVD — all 49 tests, and read the XML in
`mobile/build/outputs/androidTest-results/connected/debug/` for `failures`/`errors`/`skipped` rather
than trusting BUILD SUCCESSFUL.

### Step 2 — targetSdk 36, plus the predictive-back opt-out

Two changes, one commit, because shipping the first without the second is shipping the back-button
regression:

1. `mobile/build.gradle`: `targetSdk 35` → `targetSdk 36`.
2. `AndroidManifest.xml`, on `<application>`: `android:enableOnBackInvokedCallback="false"`, with a
   comment in the manifest's established style recording that `MainActivity.onBackPressed()` is the
   override at stake, that `androidx.activity` resolves to 1.0.0 and so registers no
   `OnBackInvokedCallback`, that the attribute is a documented **temporary** opt-out, and that the
   payoff is the follow-up below.

Also in this commit, correct the **now-false comment** on the Dropbox dependency in
`mobile/build.gradle`. It currently says 7.1.x is out of reach because those AARs declare
`minCompileSdk=36` "and this project is pinned to compileSdk 35". After Step 1 that reason no longer
exists. The pin itself is still right — 7.0.0's API is identical to 7.1.1's and there is nothing to
gain — but the stated reason must become the true one (Java 21 rules out 8.x; 7.0.0 is sufficient and
tested), or the next reader acts on a false constraint.

**Gate:** `:mobile:assembleDebug` green. `:mobile:lintDebug` **0 errors** — `ExpiredTargetSdkVersion`
should now be quiet, and if anything new fires, it fires here where it is attributable.
Confirm in `mobile/build/intermediates/merged_manifest/` that the merged manifest really carries
`targetSdkVersion="36"` and `enableOnBackInvokedCallback="false"` — the merger, not the source file,
is what ships. `:mobile:connectedDebugAndroidTest` on `Pixel_6` (API 33) still 49/49.

### Step 3 — The Android 16 emulator pass

The first three steps prove it builds and that API 33 behaviour is unchanged. This step is the only
one that answers *what does it do on Android 16*, and it is the reason this is not two commits
long. Run on `Pixel_6_API_36`, with **gesture navigation on** (the FAB question is meaningless under
3-button nav) and then again with 3-button nav.

1. **Instrumentation suite on API 36.** `:mobile:connectedDebugAndroidTest`, 49/49. This is the first
   time these tests have run on anything above API 33.
2. **Back navigation** — the Step 2 opt-out working, checked directly: back closes an open drawer;
   back from List → Graph → Summary walks the stack instead of exiting; back from the root screen
   exits; `backPressCount` still behaves.
3. **Edge-to-edge, bottom edge.** Is the FAB fully tappable, or does the gesture bar overlap it? Does
   the last row of a `LogListFragment` list scroll clear of the bar? Same for the graph, the
   calendar, and the summary. Any fix is bottom-inset padding on the offending view.
4. **Edge-to-edge, top edge.** Toolbar and the nav drawer header clear of the status bar in both
   orientations.
5. **Reminders.** Set a time later today, confirm delivery, and confirm the "time already passed
   today" guard still holds — set a time *earlier* than now and confirm nothing fires immediately.
   That guard is documented as load-bearing in `CLAUDE.md`.
6. **Notification permission.** Fresh install on API 36: asked once at launch; denied then re-asked
   via the reminder switch; the denial message does not reappear on every launch.
7. **Import / export / backup.** `.edb` export, then import it back through the document picker;
   confirm the stage-validate-overwrite ordering still reports correctly; CSV export to a share
   target.
8. **Dropbox.** PKCE auth against the throwaway test account, upload, backup list, download.

**Gate:** every one of the eight either passes, or has a fix committed and re-verified. Record the
result of each in this file as it is run — including anything that contradicted this plan's
predictions, in the style of Phase 3's "running the checks corrected three things".

#### Results, run on `Pixel_6_API_36` (Android 16, API 36, gesture navigation)

| # | Check | Result |
| --- | --- | --- |
| 1 | Instrumentation suite | **Pass** — 49/49, 0 failures/errors/skipped, read from the results XML. Re-run after the check-3 fix: still 49/49. |
| 2 | Back navigation | **Pass** — back closes an open drawer and the app keeps focus; New Record → List → Graph then back walks Graph → List → New Record; back at the root exits to the launcher. |
| 3 | Edge-to-edge, bottom | **Pass** — the gesture inset is 63px, so the bar occupies y≥2337. `mainContentFragment` ends at exactly 2337 and the FAB at 2295, 42px clear. List, graph, calendar and summary all render clear of it. |
| 4 | Edge-to-edge, top | **Failed, fixed, re-verified** — see below. |
| 5 | Reminders | **Pass** — the alarm is `RTC_WAKEUP` with `window=+18h` and `repeatInterval=86400000`, i.e. still inexact and still needing no exact-alarm permission. The guard holds: with the clock at 22:37 a reminder set to 08:34 scheduled `origWhen=2026-09-06 08:34`, the *next* day, and no notification was posted. |
| 6 | Notification permission | **Pass** — a fresh install prompts once at launch (observed), and `NotificationPermissionTest`'s 3 tests pass on API 36. |
| 7 | Import / export / backup | **Pass** — export wrote `expenseLog.edb` plus the daily, weekly and monthly rotation into the app-private directory, and CSV export produced `Expense Log.csv` and opened the share sheet with the FileProvider grant intact. Import is covered by `EdbRoundTripTest` and `NewerSchemaImportTest`, green on API 36. |
| 8 | Dropbox | **Not run.** Credentials were available and authorised, and the image is `google_apis` so Chrome is present, but the emulator could not be brought up again to run it: with Android Studio open (~1.7 GB) the Windows host thrashes, and a 4 GB AVD crawled for ~40 minutes without reaching the launcher. Nothing here suggests an app fault; it is a host memory limit. Re-run it with Studio closed. |

**What running the checks corrected.**

- **The top edge was broken, and had been for a year.** In `SettingsActivity` the first header row,
  "General", was drawn *underneath* the toolbar and could not be tapped, and the strip behind the
  status bar went unpainted, leaving white status-bar icons on a near-white background. This plan
  predicted the *bottom* edge as the exposure and called the top edge "should be fine" on the
  strength of `fitsSystemWindows` in `activity_main.xml`. That reasoning was right about
  `MainActivity` and wrong about the two preference screens, which have no layout of their own to
  annotate because their view tree comes from the framework `PreferenceActivity`.
- **It is not caused by this bump.** The platform's own compat change ids settle it:
  `ENFORCE_EDGE_TO_EDGE` has `enableSinceTargetSdk=35`, which the app already had, while only
  `DISABLE_OPT_OUT_EDGE_TO_EDGE` is new at 36. Disabling `ENFORCE_EDGE_TO_EDGE` for the package
  moved the header row from `[147,181]` back to `[147,309]`, below the toolbar. So this was a
  live defect on Android 15 and 16 that went unseen only because both verification phones run
  Android 13 and 14. What 36 changes is that the opt-out which could have hidden it is gone.
- **The first fix did nothing, for an instructive reason.** Registering the inset listener on
  `android.R.id.content` never fires: AppCompat's sub-decor sits between the decor view and the
  content view and consumes the insets on the way down. The listener has to go on the decor view.
- **`Window.setStatusBarColor` is ignored at targetSdk 35+**, so the app has to paint the status bar
  strip itself; the decor background now carries `colorPrimaryDark`.
- **That fix then broke both phones, and the maintainer caught it on the device the same day.**
  Two mistakes, both from doing edge-to-edge work unconditionally rather than only where the
  platform runs the window edge to edge:
  - *The preference screens turned teal.* The comment claimed the preference list "draws its own
    opaque background" and so would cover the decor. It does not — the list, its
    `ContentFrameLayout` and AppCompat's sub-decor are all transparent, and the decor background
    **was** the theme's `android:windowBackground`, the only thing painting those screens white.
    Fixed by putting `android:windowBackground` back on the content root, which starts below the
    status bar, so the teal is left showing only in the strip.
  - *A blank strip appeared below the toolbar on Android 13 and 14.* `ENFORCE_EDGE_TO_EDGE` exists
    only on Android 15 and above; below that the framework still insets the window itself, so the
    padding was applied a second time on top of an inset already there. `applySystemBarInsets`
    now returns immediately below API 35 and those devices see exactly the pre-bump appearance.
  The lesson is narrower than "test on a device": **targetSdk 36 changes nothing on an OS that
  predates the behaviour**, and any compensation for a new platform behaviour has to be gated on
  the platform that has it, not applied everywhere.
- **The AVD `avdmanager` creates is not fit for API 36.** It defaulted to 1536 MB RAM, 2 cores and
  `hw.gpu.enabled=no`. Android 16 under software rendering ANR'd `system_server` and SystemUI
  repeatedly, which cost most of an hour and looked like an app fault until logcat showed every ANR
  was the platform's `Watchdog` during boot-time `dexopt` and `vold#commitChanges`, with **no ANR
  and no exception attributed to `de.timowa.expenselog`**. 4 GB, 4 cores, GPU on, and running the
  emulator headless made it stable. Anyone repeating this pass should set that first.

### Step 4 — Regression on the real phones

The emulator does not settle it. `PROGRESS.md`'s verification record is two physical phones against
2540 real records, and that record is what makes the app trustworthy.

**These runs are the maintainer's to make, on Windows, not this session's** — nothing here attaches
`adb` to a phone holding real financial data.

Install the Step 3 APK on the Pixel 4a (Android 14) and the Jelly Star (Android 13) and re-run the
**ten-check device pass in `RECOVERY_PLAN.md`**. Both phones predate edge-to-edge
enforcement and predictive back, so the expectation is *no visible change whatsoever*; anything that
did change is a regression introduced by this bump, on hardware where API 36 semantics do not even
apply.

Use `adb shell am instrument -w de.timowa.expenselog.test/androidx.test.runner.AndroidJUnitRunner`
for the suite on those phones — **not** `connectedDebugAndroidTest`, which uninstalls the app
afterwards and takes `Android/data/de.timowa.expenselog/` and the backup with it.

**Gate:** ten checks pass on both phones.

### Step 5 — Documentation

`targetSdk 35` is asserted in several places that all become wrong at once:

- `PROGRESS.md` — the toolchain row, the Phase 2 summary line, and the verified-on row (add the
  Android 16 emulator).
- `CLAUDE.md` — the toolchain paragraph (**targetSdk 35** appears twice, once bolded), and the lint
  paragraph's account of `ExpiredTargetSdkVersion`.
- `README.md` — whatever it states about the toolchain a builder needs, plus the new SDK packages.
- Any constraint this work establishes that still binds — the predictive-back opt-out above all —
  gets **lifted into `CLAUDE.md`**, per that file's own rule that the plans are history and not
  instructions. A future contributor who deletes `enableOnBackInvokedCallback="false"` without
  migrating must be stopped by `CLAUDE.md`, not by finding this document.
- This file moves to `docs/history/` when the work merges, alongside the other three plans.

---

## What actually happened

Written after the fact, when this file was archived. The plan above is what was intended; this is
what the work produced, and where it went further than the plan.

**Steps 0–2 went as written.** The toolchain did not move — AGP 8.13.2 supports API 36.1, so the
pinned dependencies were never at risk. Predictive back was the one anticipated breakage, and it is
handled by the documented `android:enableOnBackInvokedCallback="false"` opt-out, with the reasoning
lifted into `CLAUDE.md` as Step 5 required.

**Step 3 found one real bug, and it predated the bump.** The preference screens were not inset for
edge-to-edge, which is enforced from targetSdk **35**, not 36 — so it had been live on Android 15
and 16 since Phase 2 and was invisible only because both verification phones run Android 13 and 14.
Fixed in the shared `AppCompatPreferenceActivity`. The suite ran 49/49 on the API 36 emulator and
lint stayed at 0 errors / 241 warnings.

**Step 4 corrected that fix.** On the maintainer's phones the first version was wrong in both
halves: painting the decor view replaced the theme's `android:windowBackground` — the only thing
drawing those screens white — and the inset padding was added on top of an inset the framework had
already applied. `applySystemBarInsets` now returns immediately below API 35, and the window
background is restored on the content root where it does run. This is exactly what Step 4 existed
to catch: a fix written against API 36 semantics, on hardware where they do not apply.

**Three more fixes came out of using the build, none of them in the plan, and two of them older
than this branch.**

- **A declined Dropbox login relaunched itself**, with no way off the screen. A `SwitchPreference`
  persists the moment it is tapped, so sync read as enabled before an account was connected — the
  exact condition `initializeDropboxV2()` treats as "start an auth". `handleDeclinedAuth()` closes
  it from both resume paths that can receive a decline.
- **The reminder notification's Settings action opened the settings header list**, not the reminder
  settings. Never a regression: `openSettings` was byte-identical to the January 2021 snapshot and
  had never passed a target fragment.
- **The reminder settings were a nested `<PreferenceScreen>`**, which the legacy framework renders
  as a bare `Dialog` — no toolbar, no title, no insets, so under edge-to-edge the switch sat behind
  the status-bar icons. Found only by sending the notification action to it. They are now
  `SettingsActivity.RemindersPreferenceFragment` over `pref_reminders.xml`, reached identically from
  both entry points. The rule against nesting is in `CLAUDE.md`.

**What the maintainer verified on device**, beyond the Step 4 pass: the build as a whole by ordinary
use; the Dropbox login both ways, completing it and declining it, against the fix above; and the
reminder notification's Settings action, which opens the reminder settings as intended.

---

## The follow-up this bump defers, explicitly

**Migrate off `onBackPressed()` properly.** The opt-out attribute buys time, it does not buy a fix,
and a future API level will remove it. The real work: add an explicit
`implementation 'androidx.activity:activity:<≥1.6>'` — which pulls `androidx.core`,
`androidx.lifecycle` and `androidx.fragment` up with it — replace `MainActivity`'s override with an
`OnBackPressedCallback` on the dispatcher, and drop the manifest attribute.

It is deferred because that dependency bump is exactly the kind the revival plan flags as
app-breaking: AppCompat 1.2.0 and Material 1.2.1 are pinned because Material 1.5+ **crashes at launch**
against a `Theme.AppCompat` app theme, and dragging AndroidX forward under an unchanged AppCompat is
the same family of risk. It needs its own device-tested step and it must not be the thing standing
between this app and a Play listing. Doing it later costs nothing extra; doing it now risks the
release.

---

## Deliberately not in scope

Unchanged, and not to be "tidied" while the diff is open: ButterKnife 7.0.1, the 17
`android.preference` files, the 4 remaining `AsyncTask` subclasses, `UpgradeHelper`'s name,
`AnalyticsHelper.logAnalytic`'s vestigial parameter, the JVM test stub, `DATABASE_VERSION` (the
schema has not changed — bumping it makes every backup from this build un-importable on the old one
for nothing), and the four load-bearing `FileHelper` rules.

The known-and-unresolved items in `PROGRESS.md` — the `SQLiteConnection` leak warning, the dead
`http`/`https` intent filters, main-thread import staging, app-private automatic backups — stay open.
None is affected by the target level.

---

## One note about what comes after

targetSdk 36 unblocks Play submission; it does not complete it. Noted here only so it is not
discovered at upload time, and deliberately **not** part of the steps above: `mobile/build.gradle`
has no `signingConfig` and `assembleRelease` has apparently never been run, while Play wants a signed
**AAB** (`:mobile:bundleRelease`). That is its own piece of work, with its own plan.

---

## Sources

- [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- [Behavior changes: Apps targeting Android 16 or higher](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Add support for the predictive back gesture](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
- [Android Gradle plugin 8.13 release notes](https://developer.android.com/build/releases/past-releases/agp-8-13-0-release-notes)
