# Building Expense Log

You need JDK 21 and the Android SDK with platform 36. The Gradle wrapper does the rest:

```
./gradlew :mobile:assembleDebug     # mobile/build/outputs/apk/debug/
```

The build needs no credentials. Two things are optional and stay out of the repository:

- **Dropbox**: register an app at https://www.dropbox.com/developers/apps (Scoped App, App
  Folder, all four `files.*` scopes), then add `dropbox.appKey=<key>` to `local.properties`.
  Without it, everything except Dropbox works.
- **Release signing**: put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in a
  `keystore.properties` at the repository root, or set the matching `SIGNING_*` environment
  variables. Then `./gradlew :mobile:assembleRelease` builds a signed APK, and `bundleRelease` an
  App Bundle. Keep `mobile/build/outputs/mapping/release/mapping.txt` for each release.

If you fork this, change the `applicationId`. [CLAUDE.md](CLAUDE.md) covers the architecture and
the constraints that must hold when you change the code, and [docs/history/](docs/history/) records
how the app got here.
