# Expense Log — Phase 1: Get it to build and run

## Context

`expense_log` is a January 2021 snapshot of a Google Play app (`arproductions.andrew.expenselog`
v1.4) handed over by its original author under Apache-2.0 and unmaintained since. It is 68 Java
files / ~16.5k LOC in a single Gradle module, and **it cannot build in its current state** — not
"has warnings", but fails at Gradle configuration time, before a single source file compiles.

The long-term goal is a free, open-source, telemetry-free expense tracker on GitHub. This plan
covers **only Phase 1: reach a `:mobile:assembleDebug` that installs and launches on a device.**
Runtime correctness of storage, Dropbox, and reminders on modern Android is deferred to Phase 2.

Four decisions are settled and this plan assumes them:

1. **Minimum viable build.** Compile → install → launch.
2. **Ads, billing, and Firebase removed outright; premium hardcoded on.** Every paywalled feature
   becomes free. This also clears ~1,300 LOC of dead weight.
3. **minSdk 19 → 26.** Deletes multidex entirely; notification channels become unconditional.
4. **Package stays `arproductions.andrew.expenselog`.** Renaming is a later commit.

Work proceeds as **one small commit per step** on a branch.

## Guiding principle: change only what blocks the build

The instinct to modernise everything at once is what makes revivals fail. Several tempting
upgrades are actively harmful right now and are called out explicitly below — bumping Material,
jumping to AGP 9, and migrating ButterKnife are all **deferred on purpose**, not overlooked.

## Why the build is currently dead

| # | Failure | Where |
| --- | --- | --- |
| 1 | `com.google.gms.google-services` applied with no `google-services.json` → **configuration-time failure** | `mobile/build.gradle:2-3` |
| 2 | `jcenter()` first in both repository blocks — decommissioned | `build.gradle:5,26` |
| 3 | AGP 4.1.1 / Gradle 6.5 cannot run on any modern JDK | `build.gradle:14`, `gradle-wrapper.properties` |
| 4 | `com.google.ads.mediation:mopub:5.15.0.0` — shut down 2022, unresolvable | `mobile/build.gradle:40` |
| 5 | `com.androidplot:androidplot-core:1.5.7` → `com.halfhp.fig:figlib:1.0.7`, **which exists only on jcenter** | `mobile/build.gradle:66` |
| 6 | `gradle-wrapper.jar` and `gradlew.bat` stripped for email delivery | `gradle/wrapper/` |

Blocker 5 is not in the README's list and is fatal: Maven Central publishes exactly one `figlib`
version, `1.0.11`. Verified — `androidplot-core:1.5.11` is the only version that resolves.

## Build environment

WSL has no JDK, Gradle, or Android SDK — all builds run Windows-side through Android Studio
2026.1.3. The SDK at `C:\Users\Tim\AppData\Local\Android\Sdk` has only platforms android-33/34
and build-tools 30.0.3/33.0.2, so **new SDK components must be installed first**. There is also
**no usable JDK on this machine**: the old Studio bundles JBR 11 (too old for AGP 8), the new one
bundles JBR 25 (too new for Gradle 8), and `~/.gradle/jdks` is empty.

## Toolchain (verified against dl.google.com, services.gradle.org, Maven Central)

| Component | From | To | Why |
| --- | --- | --- | --- |
| Gradle wrapper | 6.5 | **8.14.3** | AGP 8.13's minimum and default is Gradle 8.13 |
| Android Gradle Plugin | 4.1.1 | **8.13.2** | Last stable 8.x — see below for why not 9.3.2 |
| Gradle JDK | *(none usable)* | **JDK 21, downloaded via Studio** | AGP 8.13 needs JDK 17+; Gradle 8.14 tops out at JDK 24, so JBR 25 cannot run it |
| compileSdk | 29 | **35** | Build-tools 35.0.0 is AGP 8.13's *default*, so no `buildToolsVersion` line is ever needed |
| targetSdk | 29 | **29 (unchanged)** | See below |
| minSdk | 19 | **26** | Deletes multidex |
| Java source/target | 1.8 | **17** | AGP 8 standard |

### Why AGP 8.13.2 and not 9.3.2

AGP 9 flips `android.enableAppCompileTimeRClass` to `true`, making R fields non-final —
**"Cannot use R fields in switch statements."** This codebase has **31 `case R.id.…` labels across
5 files** (`FilterDialogFragment`, `GroupingDialogFragment`, `Navigator`, `NewLogFragment`,
`SortingDialogFragment`) plus 29 `@Bind(R.id.…)` annotations. AGP 9 breaks all 60 sites at once,
and separately mandates a new DSL, enables built-in Kotlin (this project has zero Kotlin), and
makes `getDefaultProguardFile('proguard-android.txt')` — which `mobile/build.gradle` uses — illegal.

Belt and braces at zero cost: set **`android.nonFinalResIds=false`** in `gradle.properties`. AGP
8.0 flipped that default; pinning it back guarantees constant R fields and protects both the
`@Bind` sites and the 31 `case` labels in one line.

### Why targetSdk stays at 29

Counterintuitive but deliberate, and it is what makes "minimum viable" achievable:
- `android:exported` is only a *hard manifest-merger error* at targetSdk ≥ 31.
- `requestLegacyExternalStorage="true"` is still honoured for apps targeting ≤ 29, even on Android
  11+. Keeping 29 **preserves the app's existing storage behaviour**, so import/export and Dropbox
  may well keep working rather than silently failing.
- targetSdk 29 ≥ 24, so it still installs on Android 14/15/16.
- Caveat: lint's `ExpiredTargetSdkVersion` is fatal severity and blocks `assembleRelease` via
  `lintVital`. It does not run for `assembleDebug`, so it is a non-issue on this path.

Add `android:exported` anyway — it is a two-line, zero-risk edit that makes the later bump free.

### Dependencies: what to bump and what to leave alone

**Bump (mandatory):**
- `androidplot-core` 1.5.7 → **1.5.11** — 1.5.7's `figlib:1.0.7` no longer exists anywhere.
  Verified 2026-08-25: `androidplot-core:1.5.11`'s POM resolves `com.halfhp.fig:figlib:1.0.11`.
- `dagger` / `dagger-compiler` 2.27 → **2.57.2** — 2.27 dates from mid-2020, predates JDK 17
  and AGP 8, and ships `kotlinx-metadata-jvm:0.1.0`.

  **Correction (verified 2026-08-25).** An earlier draft of this plan justified the bump by
  claiming 2.27 pulls `google-java-format:1.5` and that JEP 396 turns its reflection into
  `com.sun.tools.javac.*` into an `IllegalAccessError`. Both halves are wrong. The POMs for
  `dagger-compiler` **2.27 and 2.57.2 both declare `com.google.googlejavaformat:google-java-format:1.5`**
  at compile scope — the bump does not drop it. And Dagger never invokes the formatter unless
  `-Adagger.formatGeneratedSource=enabled` is passed; it is **disabled by default** for build
  performance. So this failure mode should not occur on either version. Bump anyway, for the
  JDK 17 / AGP 8 support.

**Delete:**
- `dagger-android-support` — grepped all 68 files for `dagger.android`, `AndroidInject`,
  `DaggerApplication`, `HasAndroidInjector`: **zero hits**. Pure dead weight.
- `compileOnly org.glassfish:javax.annotation` — only needed so `@Generated` resolves under
  `-source 8`. At Java 17 the JDK provides it.
- `com.android.support:multidex`, firebase ×2, play-services-ads, mopub.
- `junit:junit` moves off `implementation` (it is currently shipped **inside the APK**) to
  `testImplementation 'junit:junit:4.13.2'`.

**Leave alone — bumping these breaks the app:**
- `androidx.appcompat:appcompat:1.2.0` and `com.google.android.material:material:1.2.1`. The app
  theme is `Theme.AppCompat.Light.DarkActionBar` (`values/styles.xml:4`). Material Components
  1.5+ throws *"The style on this component requires your app theme to be
  Theme.MaterialComponents (or a descendant)"* **at runtime, on launch**, against an AppCompat
  theme. Bumping Material is a launch crash that buys nothing in Phase 1.
- `com.joanzapata.iconify:*:2.2.2` — 2.2.2 is the newest that exists (last published 2016).
- `com.dropbox.core:dropbox-core-sdk:3.1.5` — only needs to *compile* in Phase 1.

**Keep `android.enableJetifier=true`.** Verified: `android-iconify:2.2.2`'s only compile
dependency is `com.android.support:support-v4:22.2.1`, and there is no newer Iconify to escape
to. That artifact does resolve from `google()`, so dropping jcenter is safe — but jetifier must
stay. (It is scheduled for removal in AGP 10, not AGP 9.)

## Steps

### Step 0 — There is no wrapper chicken-and-egg problem

`gradlew` itself is present and intact; only `gradle-wrapper.jar` and `gradlew.bat` are missing.
More importantly, **Android Studio does not use them** — it parses `distributionUrl` from
`gradle-wrapper.properties` and runs that distribution itself via the Tooling API. (Evidence it
already does: `~/.gradle/wrapper/dists/` holds `gradle-7.5-bin` and `gradle-7.6.3-all`, neither of
which this project ever referenced.)

So: **edit the text files first, open Studio second, regenerate the wrapper jar last** via the
Gradle tool window → Tasks → build setup → `wrapper`. If you want the jar beforehand, it is a
tiny version-agnostic bootstrap at
`https://raw.githubusercontent.com/gradle/gradle/v8.14.3/gradle/wrapper/gradle-wrapper.jar`.

### Step 1 — `gradle/wrapper/gradle-wrapper.properties`
`distributionUrl` → `gradle-8.14.3-bin.zip`.

### Step 2 — `gradle.properties`
Keep `useAndroidX` and `enableJetifier` as-is. Add `android.nonFinalResIds=false` and
`org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`. Note the file has **no trailing newline** —
append carefully.

### Step 3 — root `build.gradle`
Both repository blocks → `google()`, `mavenCentral()`; drop `jcenter()` and the redundant explicit
`maven.google.com`. Classpath → `com.android.tools.build:gradle:8.13.2` only; delete the
`google-services` and `firebase-crashlytics-gradle` classpaths. Replace the legacy
`task clean(type: Delete)` block, which is invalid under Gradle 8.

### Step 4 — `mobile/build.gradle`
Drop both Google plugin lines (**this alone unblocks configuration** — the highest-value edit in
the plan). Add `namespace`, set compileSdk 35 / minSdk 26 / targetSdk 29 / Java 17, delete
`multiDexEnabled` and `multiDexKeepFile`, and apply the dependency changes above.

### Step 5 — Open in Studio, set Gradle JDK 21, install SDK, **sync only**

*File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK →
Download JDK… → version 21.* Then *Tools → SDK Manager* (tick "Show Package Details") → install
**Android SDK Platform 35** and **Build-Tools 35.0.0**.

This is the first real gate — everything above is resolution, nothing has compiled yet. Fix
resolution errors here before touching any Java.

**Result (2026-08-25): passed, clean.** Gradle JDK is `~/.jdks/jbr-21.0.11`; Studio synced under
Gradle 8.14.3 with both modules registered. `build/reports/problems/problems-report.html` holds
exactly two diagnostics, both the same WARNING: Groovy space-assignment (`namespace 'x'` should be
`namespace = 'x'`), removed in Gradle 10 — cosmetic, deferred to Step 9+. Nothing compiled, as
intended: there is no `mobile/build/`. Two checks landed early — `com.halfhp.fig` resolved (so the
androidplot 1.5.11 bump did its job), and the Gradle module cache contains **no** `firebase`,
`play-services`, or `mopub` artifacts, which pre-confirms Verification step 3 at the resolution
level. Step 5 produced no tracked file changes, so it has no commit of its own.

**Ordering note for Step 6–8.** `gradlew.bat` and `gradle-wrapper.jar` are still missing (Step 0
explains why that is fine, Step 8 regenerates them). Until then the `.\gradlew.bat` commands in
Verification cannot run — drive the first `assembleDebug` from Studio's Gradle tool window or
*Build → Make Project*, and switch to the command line only after the `wrapper` task has run.

### Step 6 — Resources and manifest (the AAPT2 gate)

`processDebugResources` runs *before* `compileDebugJavaWithJavac`, so these must precede the Java
work or you will simply re-fail.

- **Delete `res/layout/fragment_ad.xml`** — it uses `ads:adSize`/`ads:adUnitId`, custom attributes
  owned by the ads AAR. Once that dependency is gone this is a **hard AAPT2 failure**, not dead code.
- `res/layout/app_bar_main.xml`: delete the trailing `ad_container` `LinearLayout` **and** the
  `android:layout_above="@+id/ad_container"` on the `CoordinatorLayout`. Because it is `@+id/`,
  leaving the attribute would not break the build — it would silently collapse the layout.
- `AndroidManifest.xml`: **remove `package="…"`** (under AGP 8 this is an error, not a warning —
  the `namespace` in Step 4 replaces it); remove **both** meta-data blocks —
  `com.google.android.gms.ads.APPLICATION_ID` *and* `com.google.android.gms.version`, whose
  `@integer/google_play_services_version` is supplied by play-services and is **not** defined in
  `values/integers.xml` (verified); remove `com.android.vending.BILLING`; remove the duplicate
  `ACCESS_NETWORK_STATE` (lines 5 and 9); add `android:exported="true"` to `.MainActivity` and to
  `com.dropbox.core.android.AuthActivity`.
- **Keep the Dropbox `AuthActivity` declaration.** `dropbox-core-sdk:3.1.5` ships as a **jar, not
  an aar** (verified: no `.aar` artifact exists), so it has no manifest and nothing merges an
  `AuthActivity` in. The local declaration is the only one.
- ~~Fix `ReminderReceiver`, currently `exported="false"` while listening for `BOOT_COMPLETED` — it
  can never fire, so reminders have never survived a reboot.~~ **This premise was wrong** (found in
  the PR review, see `docs/history/PR_REVIEW_REVIVAL.md`): `BOOT_COMPLETED` is a system broadcast and is
  delivered to non-exported receivers, so the original config worked and reminders did survive
  reboots. The flip to `exported="true"` made in Step 6 on this advice was reverted — it needlessly
  exposed the extras-dispatched receiver to every co-installed app. `exported="false"` stays, now
  explicit, which is all the Android 12 requirement actually demands.
- Optional tidy: drop the ad IDs from `settings_keys.xml`; fix the stray `<resources>>` in
  `values-v21/styles.xml`.

### Step 7 — Java (the javac gate)

- Delete `util/` (8 billing files), `src/main/aidl/`, `AdManager.java`, `multidex-config.txt`,
  and `src/androidTest/`.
- `MyLogApplication`: `MultiDexApplication` → `android.app.Application`.
- **`UpgradeHelper` cannot simply be deleted** — its premium branch (lines 111–133) is the only
  place Dropbox is bootstrapped at startup. Reduce it to a shell: `getIsPremium()` → `true`,
  `getShowAds()` → `false`, and move the Dropbox bootstrap into `initialize()`. Delete
  `isIABHelperNull()`, `purchaseFlowResult()`, `showUpgradeDialog()`, and both listeners.
- `MainActivity.onActivityResult`: delete the `isIABHelperNull()` / `purchaseFlowResult()` block
  (~lines 251–254). This also fixes a real bug — that early return **silently swallows every
  non-billing activity result**.
- `Navigator`: delete `updateAd()`, `showAds()`, `removeAds()` and their two call sites.
- Unwrap the premium gates in `SettingsActivity` (4), `AccountListFragment` (multiple accounts),
  `LogTabsFragment` (CSV export), `NewLogFragment` (Dropbox upload), `RatingManager` (wording),
  and the `MENU_ITEM_UPGRADE` overflow item in `MainActivity`.
- Delete the dev backdoor: typing amount `1111111` in `NewLogFragment` (lines 1091–1100) toggles
  premium via `PrefManager.setDevOverride`, stored under the *misleadingly named* key
  `round_numbers`. Remove it and both `PrefManager` methods.
- **Telemetry, the cheap way.** Rather than rewriting ~70 call sites, change
  `AnalyticsHelper.logAnalytic`'s second parameter type from `FirebaseAnalytics` to `Object`,
  empty both bodies, and delete the two private `logFirebaseAnalytic` helpers. Then in each of
  the other 11 files the only edits are: drop the import, and change the
  `private FirebaseAnalytics mFirebaseAnalytics;` field plus its `getInstance(...)` assignment to
  `private final Object mFirebaseAnalytics = null;`. Every call site stays byte-identical — about
  3 lines per file. Tidy the signature in a later commit. Nothing to do for Crashlytics: grepping
  all 68 files for `Crashlytics` returns **zero hits**; it was plugin-only.
- Also remove the now-meaningless analytics toggle from `res/xml/pref_general.xml`.
  **After this step no data leaves the device.**
- Remove the `upgrade` / `upgrade_prompt_msg` / `upgraded_msg` strings and clause 1 of `NOTICE`.

### Step 8 — `assembleDebug`, install, launch. Then run the `wrapper` task.

**Result (2026-08-25): green, first attempt.** Assembled from Studio via *Build > Assemble 'mobile'
Run Configuration*; no error surfaced at any gate. Output:
`mobile/build/outputs/apk/debug/mobile-debug.apk`, 6,174,880 bytes, versionCode 23 / versionName
1.4, `minSdkVersionForDexing 26`.

Installed on a physical **Pixel 4a** and exercised. Confirmed working: **creating a second
account** (the clearest single signal the paywall removal took effect), importing an existing
`.edb`, and working with the imported records in-app. `requestLegacyExternalStorage` is therefore
still doing its job at targetSdk 29 — the item the plan said to verify rather than assume.

Retires ranked failure points 5, 7, 8, and 10: no AAPT2 attribute or missing-resource error, no
`element value must be a constant expression` (so `android.nonFinalResIds=false` carried both
ButterKnife's `@Bind` annotations and the 31 `case R.id.` labels), no missing `FirebaseAnalytics`
symbol, no `MultiDexApplication`. Point 6, the Dagger `JavacTool` failure, never appeared — the
correction recorded above was right, and no `--add-exports` was needed.

**Verification step 3 was done against the artifact rather than the dependency tree**, which is
the stronger check: all 8 dex files were concatenated (13,193,016 bytes) and scanned. Zero matches
for `com/google/firebase`, `crashlytics`, `com/google/android/gms`, `mopub`, `MoPub`,
`com/android/vending/billing`, `IInAppBillingService`, `IabHelper`, `AdView`, `AdRequest`, and
`expenselog/util/`. Dropbox, AndroidPlot, ButterKnife, Dagger and Iconify are all present. The
only `Firebase` string left in the APK is the field name `mFirebaseAnalytics` and its synthetic
accessor — the vestigial `Object`. `fragment_ad` is absent and `lib/` is empty.

The merged manifest carries no gms meta-data, no `BILLING`, no duplicate `ACCESS_NETWORK_STATE`,
and `exported="true"` on `MainActivity`, `ReminderReceiver`, and the Dropbox `AuthActivity`. AGP
re-injects `package=` into the *merged* output from the `namespace`; that is expected and is not
the attribute Step 6 removed from the source manifest.

Not yet exercised: the graph, calendar and summary tabs; reminders; CSV export; local backup.
Dropbox cannot be tested at all until a real app key replaces the `dropbox_key` placeholder.

### Step 9+ (separate commits)
ButterKnife → viewBinding; targetSdk 35 + scoped storage; test source set; package rename.

**ButterKnife is deliberately off the critical path.** Under AGP 8.13.2 app-module R fields stay
final, so `@Bind(R.id.x)` still compiles; 7.0.1's processor is a stock `AbstractProcessor` with no
`com.sun.tools.javac` surface, so on JDK 21 it emits a *warning*, not an error. When you do
migrate, `LogTabsFragment` and `SummaryFragment` are already on viewBinding — copy that pattern.

## Verification

1. **Sync succeeds** (Step 5 gate) — resolution before compilation.
2. **Build** — `.\gradlew.bat :mobile:assembleDebug`. First green build is the success criterion.
3. **Prove the dependency tree is clean** —
   `.\gradlew.bat :mobile:dependencies --configuration debugRuntimeClasspath`, then confirm zero
   matches for `firebase`, `play-services`, and `mopub`. This is the objective check that the
   no-telemetry goal holds, rather than trusting the source edits.
4. **Install and launch** — `.\gradlew.bat :mobile:installDebug` on a device or API 26+ emulator.
5. **Smoke test the un-gated features**: add a record; create a category; **create a second
   account** (previously premium-gated — the clearest signal the paywall removal worked); cycle
   the List / Graph / Calendar / Summary tabs (exercises AndroidPlot and the calendar adapter);
   open Settings; confirm no ad space and no "Upgrade" overflow item.
6. **Check logcat** for `AndroidRuntime` and for any Firebase initialisation line — expect none.

Because targetSdk stays at 29, storage may well still work. Do not assume it does — verify.

## Ranked failure points at first build

1. ~~`Could not find com.halfhp.fig:figlib:1.0.7` — androidplot not bumped → 1.5.11.~~
   **Retired at Step 5** — figlib resolved.
2. `Namespace not specified` / `package` in manifest is an error — must do **both** halves.
3. ~~`Gradle requires JVM 17+` or `Unsupported class file major version 69` — Gradle JDK left on
   JBR 11 or JBR 25 → download JDK 21.~~ **Retired at Step 5** — running on JBR 21.0.11.
4. ~~Missing platform 35 / `Build Tools revision 33.0.2 is corrupted` → SDK Manager.~~
   **Retired at Step 5** — platform 35 installed.
5. AAPT2 `attribute adSize not found` or `resource integer/google_play_services_version not found`
   — `fragment_ad.xml` or the gms meta-data survived.
6. `IllegalAccessError … com.sun.tools.javac.api.JavacTool` — **unlikely; see the Dagger
   correction above.** `google-java-format:1.5` is a declared dependency of `dagger-compiler` at
   both 2.27 and 2.57.2, but Dagger does not invoke it unless
   `-Adagger.formatGeneratedSource=enabled` is set, and it is off by default. If this does appear,
   a version bump is *not* the fix — check nothing enables that flag, then add the JEP 396
   `--add-exports jdk.compiler/com.sun.tools.javac.*=ALL-UNNAMED` args.
7. `element value must be a constant expression` on `@Bind` / `case R.id.` →
   `android.nonFinalResIds=false`.
8. `cannot find symbol: class FirebaseAnalytics` — missed a file →
   `grep -rln FirebaseAnalytics mobile/src`.
9. `Duplicate class android.support.v4.…` — jetifier switched off "because AndroidX" → it stays on.
10. `cannot find symbol: MultiDexApplication` — dropped the dep but left the superclass.

## What Phase 2 will need to cover

Recorded so it is not rediscovered: scoped storage / SAF across `FileHelper`, `SpreadsheetHelper`,
and `LocalBackupManager` (every path is rooted at `Environment.getExternalStorageDirectory()` and
gated on `sd.canWrite()`); `PendingIntent` mutability flags in `reminders/` (6 sites, all
`IllegalArgumentException` at targetSdk 31+); `POST_NOTIFICATIONS`; the Dropbox PKCE rewrite plus
a token stored in plaintext SharedPreferences *and* swept into cloud backup by
`allowBackup="true"`; `android.preference` → `androidx.preference` across 17 files (not urgent —
verified `android/preference/` is still present in `android.jar`, deprecated but never removed);
12 `AsyncTask` subclasses; a `<queries>` element for 4 package-visibility call sites; and a real
`onRequestPermissionsResult`, which does not exist anywhere today.

Also: `DBAdapter` sits at `DATABASE_VERSION = 1` with an **empty `onUpgrade`**, and its column
constants are positional — any schema change needs migrations written from scratch. Several
queries build SQL by string concatenation. Worth fixing before the app holds real user data.
