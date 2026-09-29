# Expense Log

An offline-first Android expense tracker: local SQLite storage, category and
account management, charts, budgets, reminders, CSV/`.edb` import-export, and
optional Dropbox backup and sync.

This is a continuation of an unmaintained Android app, released by its original
author under the Apache License 2.0 and revived from a January 2021 source
snapshot. It now ships under its own identity — `de.timowa.expenselog`, v2.0 —
and is a separate app from the original release, not an update to it. The
original copyright is retained in [NOTICE](NOTICE), as the licence requires.

**Provided as-is, with no support and no warranty.** See [LICENSE](LICENSE)
and [NOTICE](NOTICE).

**[PROGRESS.md](PROGRESS.md) is where the project stands** — what is done, what is open, and how to
test it. This file covers the handover terms and what you must supply to build. The phase plans are
archived under [docs/history/](docs/history/).

---

## Note: two files are still missing from this copy

This repository began life as an email attachment, so `.jar` and `.bat` files were stripped — mail
providers block those types even inside a zip. The Gradle wrapper (`gradle/wrapper/gradle-wrapper.jar`
and `gradlew.bat`) has since been regenerated and is committed. Still absent:

- `mobile/libs/httpmime-4.0.3.jar` and `mobile/libs/json_simple-1.1.jar` — both were already
  unreferenced; the `fileTree(dir: 'libs')` line in `mobile/build.gradle` is gone. If you ever need
  them, take current versions from Maven Central (`org.apache.httpcomponents:httpmime`,
  `com.googlecode.json-simple:json-simple`) as normal Gradle dependencies rather than checked-in jars.

No source file was ever affected.

## Status: it builds and runs

The January 2021 snapshot did not build — it failed at Gradle *configuration* time, before any
source compiled. That has been fixed. `:mobile:assembleDebug` is green, and the resulting debug APK
has been installed on a physical device, used to import an existing `.edb` database, and exercised
against real records.

Verified toolchain — these versions were tested together, and the choices are not arbitrary:

| | |
| --- | --- |
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.4.0 |
| JDK | 21 |
| compileSdk / Build-Tools | 36 / 36.0.0 |
| minSdk | 26 |
| targetSdk | 36 |
| Java source/target | 17 |

What was removed outright, rather than ported: **Firebase Analytics and Crashlytics**, **AdMob and
the MoPub mediation adapter**, **Play Services**, and **the entire in-app billing stack** (the 8
`util/Iab*` classes and the `IInAppBillingService` AIDL). Premium is unconditional as a result —
multiple accounts, CSV export, and Dropbox upload are simply available. A developer backdoor that
toggled premium when you typed `1111111` as an amount, persisting the flag under the misleadingly
named `round_numbers` preference key, is gone too.

**No data leaves the device.** This was verified against the built APK rather than the dependency
tree: all 8 dex files were scanned and contain zero references to Firebase, Crashlytics, Play
Services, MoPub, billing, or ads.

`docs/history/REVIVAL_PLAN.md` documents that first effort — every version decision and why, the
step order, what each build gate produced, and what it left for the phases after it.

### What is still outstanding

Most of what the revival left open has since been closed. **Phase 2** scoped the storage, fixed
`PendingIntent` mutability, moved Dropbox to a current SDK with the PKCE auth flow and its
credential in `EncryptedSharedPreferences`, added a real instrumentation suite, and took `targetSdk`
to 35. **Phase 3** closed the nine findings Phase 2's own code review raised, including two
data-loss bugs in the import and backup paths. **The SDK 36 work** then took `compileSdk` and
`targetSdk` to 36, which is what Google Play requires of a new listing. Each has its plan in
[docs/history/](docs/history/), and [PROGRESS.md](PROGRESS.md) is the live list. What is still open:

| Area | Problem |
| --- | --- |
| In-app billing | Removed, not reimplemented. If you want paid features, write them against the Play Billing Library. |
| `DBAdapter` | `DATABASE_VERSION = 1` and positional column constants. Step 3 added migration scaffolding that fails loudly on an unregistered version step, but any schema change still needs its migration written by hand. |
| Automatic local backups | App-private since Phase 2: no file manager can show them and they do not survive an uninstall. See **Data model** below. |

## Before you can build or publish

Firebase, AdMob, and Play billing are no longer part of this project, so there is nothing to supply
for any of them — no `google-services.json`, no ad unit IDs, no licensing key. The app builds and
runs with no credentials at all.

One credential is still needed, and one thing to know if you fork this:

1. **Dropbox** — register an app at https://www.dropbox.com/developers/apps with **Scoped App
   (App Folder)** access, then put its app key in `local.properties`, which is gitignored:

   ```properties
   dropbox.appKey=your_app_key_here
   ```

   `mobile/build.gradle` generates both forms from it — `@string/dropbox_key` with the `db-`
   prefix, which `AndroidManifest.xml` uses as the OAuth redirect scheme, and
   `@string/dropbox_app_key` bare for `Auth.startOAuth2PKCE`. A CI build can set `DROPBOX_APP_KEY`
   in the environment instead. Enable all four scopes: `files.metadata.read`/`.write` and
   `files.content.read`/`.write`.

   **Dropbox sync and backup cannot work until you do** — everything else in the app does; the
   build succeeds without a key and only the Dropbox screens fail. There is no app secret: the
   flow is PKCE, so the key is a client identifier that ships in the APK, not a credential. It is
   kept out of the repository so a fork cannot silently authenticate as this app's Dropbox
   identity.

2. **If you fork this, change the application ID again.** `applicationId` and `namespace` in
   `mobile/build.gradle` are `de.timowa.expenselog` and the Java package matches. Two IDs cannot
   share a Play listing. Android Studio's *Refactor > Rename* on the package handles the source
   tree, but four things it does not reach, all of which compile and lint green while broken:
   the three `android:fragment` attributes in `res/xml/pref_headers.xml` and the custom element in
   `res/xml/pref_reminders.xml`, both resolved by **reflection** and both crashing only when
   Settings is opened; the five fully-qualified names in `AndroidManifest.xml`; and the About
   dialog's `developer_link` in `settings_keys.xml`, which is a name and a URL rather than a
   package.

No signing keystore is tracked in this repository, and none of the original app's are available.
Publish under your own key: create one, then put it in `keystore.properties` at the repository
root, which is gitignored.

```properties
storeFile=/absolute/path/to/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

A CI build can set `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and
`SIGNING_KEY_PASSWORD` instead. **With neither, the release signing config is absent rather than
broken** — `assembleDebug` and the test suite still work, and only `assembleRelease` produces an
unsigned APK.

```
./gradlew :mobile:bundleRelease     # the AAB Play wants -> mobile/build/outputs/bundle/release/
./gradlew :mobile:assembleRelease   # an APK, for installing on a device yourself
```

The release build runs R8 with the keep rules in `mobile/proguard-rules.pro`; each rule there says
what breaks without it. Keep `mobile/build/outputs/mapping/release/mapping.txt` for every build you
publish, or its crash reports are unreadable.

## Building

Requires **JDK 21** and **Android SDK Platform 36** with **Build-Tools 36.0.0**. Nothing else needs
installing — the Gradle wrapper fetches Gradle 9.7.1 itself.

```
./gradlew :mobile:assembleDebug      # APK lands in mobile/build/outputs/apk/debug/
./gradlew :mobile:installDebug       # install to a connected device or emulator
```

In Android Studio, set the Gradle JDK under *Settings > Build, Execution, Deployment > Build Tools >
Gradle > Gradle JDK* (a JetBrains Runtime 21 is fine), install platform 36 and Build-Tools 36.0.0
from the SDK Manager, then *Build > Assemble 'mobile' Run Configuration*. Older Studio versions call
that menu item *Make Project*.

`minSdk` is 26, so any device or emulator on Android 8.0 or newer will run it.

To confirm for yourself that no telemetry survives:

```
./gradlew :mobile:dependencies --configuration debugRuntimeClasspath
```

and check for `firebase`, `play-services`, and `mopub`. Scanning the built APK's `classes*.dex` for
the same names is the stronger check, since it tests the artifact rather than the declaration.

## Data model

Everything is local. `DBAdapter.java` owns the SQLite schema; there is no
backend server and no user account system. Backups are written to the user's
own Dropbox via their own OAuth credential, held in `EncryptedSharedPreferences`. The
`.edb` file format is the app's own SQLite database, exported directly —
`FileHelper.java` handles import/export and the manifest registers intent
filters so `.edb` files open in the app.

**What an automatic backup is, and is not** (Phase 2 storage behaviour).
`LocalBackupManager` keeps rotating current/daily/weekly/monthly copies of
the database in the app's own directory,
`Android/data/de.timowa.expenselog/files/`. That location needs no
permission and is unaffected by scoped storage, but it comes with two limits
worth stating plainly:

- **It is deleted when the app is uninstalled or its data is cleared.**
- **No file manager can show it.** Since Android 11 the system forbids
  browsing `Android/data/`, so the folder appears empty in Google Files and
  everything like it. Use `adb shell ls -l
  /sdcard/Android/data/de.timowa.expenselog/files/` to look.

So automatic backups protect against mistakes made *inside* the app. **The
manual `.edb` export is what survives losing the app**, and it is the one to
take before reinstalling, switching phones, or trying an import. Dropbox sync
covers the same ground once an app key is configured (see above).
