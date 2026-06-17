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
