# Building Guillotine

Guillotine ships two apps sharing an editor core:

- **`:app`** — Android application (Kotlin + Jetpack Compose).
- **`:desktop`** — Compose Multiplatform application producing native installers for macOS
  (`.dmg`), Windows (`.msi`), and Linux (`.deb`).
- **`:shared`** — pure-Kotlin editor model (`Document`, `EditorViewModel`, timeline math),
  reused by both.

## Requirements

- **JDK 17** (Android Studio bundles one). Desktop builds ALSO need JDK 21 available for the
  `jpackage` step (`gradle/actions/setup-gradle@v6` provisions it in CI).
- **Android Studio** (current stable) for the Android app.
- **Android SDK** API 37 (compile) / 36 (target); a device or emulator on **API 26+**.
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
(Gradle, AGP, Kotlin, the Compose UI artifacts, `material3`, compileSdk 37) **must be mutually
compatible**. If the first sync reports an AGP↔Gradle or Compose↔Kotlin mismatch, let Android
Studio's **AGP Upgrade Assistant** pick the matching versions — that is the source of truth.
`material3` is pinned to the **1.5.0-alpha** line so the Material 3 Expressive APIs resolve (the
Compose UI artifacts are pinned explicitly rather than via a BOM); see the notes in
`gradle/libs.versions.toml`.

## Distributions (product flavors)

The app ships in two flavors of the **same** app (same `applicationId` and signing key, so either can
update the other in place):

| Flavor   | Distributed as         | Ads          | Updates                        |
| -------- | ---------------------- | ------------ | ------------------------------ |
| `github` | direct-download `.apk` | **none**     | self-updates from GitHub Releases |
| `play`   | Play Store `.aab`      | AdMob        | Google Play                    |

The difference is a build-time `BuildConfig.ADS_ENABLED` / `UPDATER_ENABLED` flag; the ad and updater
code paths are gated on it. Variant tasks are named `…Github…` / `…Play…` (e.g. `assembleGithubDebug`,
`bundlePlayRelease`).

## Build & run

From Android Studio: select the `app` configuration, pick the **githubDebug** or **playDebug** build
variant, and Run (▶).

From the command line:
```
gradlew.bat :app:assembleGithubDebug   # build the ad-free (direct-download) debug APK
gradlew.bat :app:installGithubDebug    # install it on a connected device/emulator
gradlew.bat test                       # run the JVM unit tests (no device needed)
```

The debug APK lands in `app/build/outputs/apk/github/debug/`.

## Release build (signed AAB for Google Play)

The Play Store takes an **Android App Bundle** (`.aab`), not an APK. This is a single-module app,
so one bundle is all you need — Google generates the per-device density/ABI/language splits. Use the
`play` flavor for Play (AdMob enabled, no self-updater — Play delivers updates):

Build one locally (signed):
```
gradlew.bat bundlePlayRelease -PversionBuild=<code> ^
  -Pandroid.injected.signing.store.file=<path-to>.jks ^
  -Pandroid.injected.signing.store.password=*** ^
  -Pandroid.injected.signing.key.alias=*** ^
  -Pandroid.injected.signing.key.password=***
# -> app/build/outputs/bundle/playRelease/app-play-release.aab
```
or use Android Studio -> **Build -> Generate Signed App Bundle / APK -> Android App Bundle**.

The **ad-free** direct-download APK is the `github` flavor — build it with
`gradlew.bat assembleGithubRelease` (-> `app/build/outputs/apk/github/release/`).

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

## Desktop build (macOS / Windows / Linux)

Native installers can't be cross-compiled — jpackage bakes in the host's JDK image. So:

- `packageDmg` needs a **macOS** runner.
- `packageMsi` needs a **Windows** runner.
- `packageDeb` needs a **Linux** runner.

Locally, you only get the installer for the OS you're on. Build it with:

```
./gradlew :desktop:packageDeb          # or packageDmg / packageMsi
```

The installer lands under `desktop/build/compose/binaries/main/<format>/`. On Linux install with
`sudo apt install ./guillotine_*.deb`; on macOS drag the DMG contents to `/Applications`; on
Windows double-click the MSI.

Development run:

```
./gradlew :desktop:run
```

### Desktop dependency detail

The `:desktop` module drops the fat `javacv-platform` artifact (~600 MB installed with every
OS's natives) for per-host classifier deps auto-selected by `desktop/build.gradle.kts`:

- `org.bytedeco:javacv` (core, no natives)
- `org.bytedeco:javacpp:<version>:<host-classifier>`
- `org.bytedeco:ffmpeg:<version>:<host-classifier>`

`<host-classifier>` is one of `macosx-arm64`, `macosx-x86_64`, `linux-x86_64`, `linux-arm64`,
`windows-x86_64` — resolved at Gradle configuration time. Installers land ~150-200 MB.

### CI: build installers on every push

`.github/workflows/desktop-build.yml` fans out over `ubuntu-latest` / `macos-latest` /
`windows-latest` and uploads the produced installer as an artifact on every push.

### CI: attach installers to a release

`.github/workflows/release-desktop.yml` runs on every `v*` tag: same matrix, but uploads via
`softprops/action-gh-release@v2` to the GitHub Release matching the tag.

## Project layout

```
settings.gradle.kts, build.gradle.kts, gradle.properties   # build config
gradle/libs.versions.toml                                  # version catalog
shared/
  src/main/kotlin/com/hereliesaz/guillotine/               # editor core (Document, VM, math)
app/
  src/main/AndroidManifest.xml
  src/main/java/com/hereliesaz/guillotine/                 # Android sources
  src/main/res/                                            # resources, adaptive icon, splash
  src/test/java/com/hereliesaz/guillotine/                 # JVM unit tests (TimelineMath)
desktop/
  src/main/kotlin/com/hereliesaz/guillotine/desktop/       # Compose Desktop sources
  src/main/resources/icons/                                # icon.icns / icon.ico / icon.png
```
