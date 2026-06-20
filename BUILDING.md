# Building Guillotine (Android)

## Requirements

- **Android Studio** (current stable) — bundles a compatible JDK 17.
- **Android SDK** API 35 (compile/target) installed; a device or emulator on **API 26+**.
- The Gradle wrapper (`gradlew` / `gradlew.bat` + `gradle/wrapper/gradle-wrapper.jar`) is committed,
  so no separate Gradle install is needed.

## ⚠️ Do not build inside Google Drive / OneDrive

Gradle and the Android build create large, rapidly-changing `build/`, `.gradle/`, and `.cxx/`
trees. A file-syncing service will fight the build (file locks, partial syncs, upload churn) and can
corrupt builds. Before building:

- Copy/clone the project to a local, non-synced path (e.g. `C:\dev\Guillotine`), **or**
- Pause Drive sync for this folder while developing.

`.gitignore` excludes the build trees from git, but only the moves above stop Drive from syncing them.

## ⚠️ Version reconciliation on first sync

The toolchain versions in `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`
(Gradle, AGP, Kotlin, Compose BOM, `material3`, compileSdk 35) were set while authoring offline and
**must be mutually compatible**. If the first sync reports an AGP↔Gradle or Compose↔Kotlin mismatch,
let Android Studio's **AGP Upgrade Assistant** pick the matching versions — that is the source of
truth. `material3` is pinned to 1.4.x so the Material 3 Expressive APIs resolve.

## Build & run

From Android Studio: select the `app` configuration and Run (▶).

From the command line:
```
gradlew.bat :app:assembleDebug      # build a debug APK
gradlew.bat :app:installDebug       # install on a connected device/emulator
gradlew.bat test                    # run the JVM unit tests (no device needed)
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## Release build (signed AAB for Google Play)

The Play Store takes an **Android App Bundle** (`.aab`), not an APK. This is a single-module app,
so one bundle is all you need — Google generates the per-device density/ABI/language splits.

Build one locally (signed):
```
gradlew.bat bundleRelease -PversionBuild=<code> ^
  -Pandroid.injected.signing.store.file=<path-to>.jks ^
  -Pandroid.injected.signing.store.password=*** ^
  -Pandroid.injected.signing.key.alias=*** ^
  -Pandroid.injected.signing.key.password=***
# -> app/build/outputs/bundle/release/app-release.aab
```
or use Android Studio -> **Build -> Generate Signed App Bundle / APK -> Android App Bundle**.

`-PversionBuild=<code>` sets `versionCode` explicitly (CI uses the git commit count so it always
increases — Play rejects a duplicate or lower `versionCode`). Without it, `versionCode` falls back
to the auto-incrementing value in `version.properties`.

### CI: build + publish to Play

The **Release AAB to Play** workflow (`.github/workflows/release-aab.yml`, run via *Actions ->
Run workflow*) builds the signed bundle and can publish it to a Play track. It reuses the signing
secrets from the APK workflow and needs one more: `PLAY_SERVICE_ACCOUNT_JSON` (a Google Cloud
service account granted release access in the Play Console). The workflow header documents the
one-time setup — note that **the very first release for a new app must be uploaded manually** in the
Play Console before the API can publish subsequent builds.

## Project layout

```
settings.gradle.kts, build.gradle.kts, gradle.properties   # build config
gradle/libs.versions.toml                                  # version catalog
app/
  src/main/AndroidManifest.xml
  src/main/java/com/hereliesaz/guillotine/                 # Kotlin sources
  src/main/res/                                            # resources, adaptive icon, splash
  src/test/java/com/hereliesaz/guillotine/                 # JVM unit tests (TimelineMath)
```
