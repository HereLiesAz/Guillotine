# Building Guillotine (Android)

## Requirements

- **Android Studio** Koala (2024.1) or newer — bundles a compatible JDK 17 and Android SDK.
- **Android SDK** API 34 (compile/target) installed; a device or emulator on **API 26+**.
- First sync downloads Gradle 8.9, AGP 8.5.2, Kotlin 2.0.20, and the Media3 1.4.1 libraries.

## ⚠️ Do not build inside Google Drive / OneDrive

This repo currently lives in a Google-Drive-synced folder (`G:\My Drive\Guillotine`). Gradle and
the Android build create large, rapidly-changing `build/`, `.gradle/`, and `.cxx/` trees. A
file-syncing service will fight the build (file locks, partial syncs, massive upload churn) and can
corrupt builds.

**Before building, do one of:**
- Copy/clone the project to a local, non-synced path (e.g. `C:\dev\Guillotine`), **or**
- Pause Drive sync for this folder while developing.

The `.gitignore` already excludes `build/`, `.gradle/`, `node_modules/`, etc. from git, but that
does not stop Drive from syncing them — only the moves above do.

## Gradle wrapper jar

`gradle/wrapper/gradle-wrapper.properties` is included, but the binary
`gradle/wrapper/gradle-wrapper.jar` is **not** (it can't be authored as text). To generate it:

- **Easiest:** open the project in Android Studio — it provisions the wrapper on first sync.
- **CLI (needs a system Gradle 8.x installed):**
  ```
  gradle wrapper --gradle-version 8.9
  ```
  After that, `./gradlew` (or `gradlew.bat` on Windows) works normally.

## Build & run

From Android Studio: select the `app` configuration and Run (▶).

From the command line (after the wrapper jar exists):
```
gradlew.bat :app:assembleDebug      # build a debug APK
gradlew.bat :app:installDebug       # install on a connected device/emulator
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## Project layout

```
settings.gradle.kts, build.gradle.kts, gradle.properties   # build config
gradle/libs.versions.toml                                  # version catalog
app/                                                       # the Android module
  src/main/AndroidManifest.xml
  src/main/java/com/hereliesaz/guillotine/                 # Kotlin sources
  src/main/res/                                            # resources, adaptive icon
```
