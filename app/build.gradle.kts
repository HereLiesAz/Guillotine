
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- Four-part version: Major.Minor.Patch.Build ----
// Computed centrally in the root build.gradle.kts (single source of truth: version.properties),
// which auto-increments Patch/Build on EVERY Gradle build of any module and persists them to disk.
// Here we only READ the already-bumped values — never recompute or rewrite them. (A duplicate local
// reimplementation lived here for a while, computing versionCode from a "build" property that was
// never actually written to version.properties — every Play upload silently got versionCode 1,
// which Play accepts exactly once and then rejects forever after: "Version code 1 has already been
// used." Restored to reading rootProject.extra, per the root build script's own documented contract.)
val computedVersionCode = rootProject.extra["versionCode"] as Int
val computedVersionName = rootProject.extra["versionName"] as String

android {
    namespace = "com.hereliesaz.guillotine"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.guillotine"
        minSdk = 26
        targetSdk = 37
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // Crash auto-reporting: CrashUploadWorker files a GitHub issue containing the crash log, using
        // this token. It is read at BUILD time from the GH_TOKEN env var (the same one
        // settings.gradle.kts uses for the GitHub Packages maven repo), with a gradle-property
        // fallback. When neither is present (typical local dev) it stays empty and CrashUploadWorker
        // no-ops — so nothing breaks locally and no token is ever committed to Git.
        //
        // SECURITY: a non-empty token here is embedded in the shipped APK's BuildConfig and CAN be
        // extracted by anyone who decompiles the app. Use a FINE-GRAINED token scoped to ONLY
        // "Issues: write" on this single repo (HereLiesAz/Guillotine) — never a broad/classic PAT —
        // so that a leaked token can, at worst, open issues on this one repo.
        // GitHub tokens are [A-Za-z0-9_] only (ghp_* / github_pat_*), so no string escaping is needed.
        val crashReportToken = System.getenv("GH_TOKEN")
            ?: (project.findProperty("GH_TOKEN") as String?)
            ?: ""
        buildConfigField("String", "GH_TOKEN", "\"$crashReportToken\"")

        // Default relay endpoint for the Report button — a Cloudflare Worker holding a
        // GitHub PAT that files issues on our behalf. Set via a gradle property so end users
        // don't need a GH account (or any config) to file a bug from the app. Empty in
        // source, set at build time via `guillotine.crashRelayUrl=https://...` in
        // ~/.gradle/gradle.properties or as a CI secret. Runtime override still lives in
        // Settings → Crash reporting for anyone who wants their own relay.
        buildConfigField(
            "String",
            "DEFAULT_CRASH_RELAY_URL",
            "\"${project.findProperty("guillotine.crashRelayUrl") ?: ""}\"",
        )
    }

    // Release signing is a property of the project, not of each CI invocation. The keystore and
    // credentials come from the environment: CI decodes the base64 `KEYSTORE_RAW` secret to
    // app/keystore.jks and exports KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD (see the
    // release-apk / release-aab / merged-build workflows). KEYSTORE_FILE may override the path.
    //
    // When no keystore is present (local dev without the secrets) the "release" config is simply
    // not created — `findByName` then returns null below, so release builds stay unsigned and debug
    // builds keep the default debug key. Nothing breaks, and no plaintext credentials live in Git.
    val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val envKeyAlias = System.getenv("KEY_ALIAS")
    val envKeyPassword = System.getenv("KEY_PASSWORD")
    // Resolve KEYSTORE_FILE against the repo root so a relative CI path can't become app/app/...;
    // default to this module's keystore.jks. Enable signing only when the keystore AND all three
    // credentials are present, so a stray local keystore without env credentials still falls back
    // gracefully (configuration succeeds; the build doesn't blow up at execution on missing creds).
    val releaseKeystore = (System.getenv("KEYSTORE_FILE")?.let { rootProject.file(it) } ?: file("keystore.jks"))
        .takeIf {
            it.exists() &&
                !envKeystorePassword.isNullOrEmpty() &&
                !envKeyAlias.isNullOrEmpty() &&
                !envKeyPassword.isNullOrEmpty()
        }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = envKeystorePassword
                keyAlias = envKeyAlias
                keyPassword = envKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Null (unsigned) only when no keystore was supplied — e.g. a local build without secrets.
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            // The auto-published CI build (merged-build.yml) assembles the debug variant. Sign it with
            // the RELEASE key when available so its signature stays stable across builds (in-place
            // updates keep working); fall back to the default debug key for local development.
            signingConfig = signingConfigs.findByName("release") ?: signingConfig
        }
    }

    // Two distributions of the SAME app (same applicationId + signing key, so either can update the
    // other in place): `play` ships to Google Play with AdMob; `github` is the direct-download build
    // — no ads, and it self-updates from GitHub Releases (Play forbids self-updating APKs, so that
    // path is github-only). The distinction is a build-time BuildConfig flag; the ad and updater code
    // paths are gated on it. CI builds the AAB from `play` (bundlePlayRelease) and the APK from
    // `github` (assembleGithubRelease). The `github` flavor's manifest additions (self-update install
    // permission + FileProvider paths) live in app/src/github/.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "ADS_ENABLED", "true")
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
        create("github") {
            dimension = "distribution"
            buildConfigField("boolean", "ADS_ENABLED", "false")
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21

    }

    buildFeatures {
        compose = true
        buildConfig = true
    }


    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // TensorFlow-Lite and MediaPipe can each ship a TFLite native lib; keep the first so the
        // build doesn't fail on a duplicate .so.
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            pickFirsts += "**/libtensorflowlite_jni.so"
            pickFirsts += "**/libtensorflowlite_gpu_jni.so"
            // onnxruntime-android AND the sherpa-onnx AAR each bundle libonnxruntime.so (+ its JNI
            // shim). Keep the first — onnxruntime-android is declared before sherpa in `dependencies`
            // so its newer runtime wins (sherpa's ORT 1.17.1 can't load the Spleeter models' newer IR).
            // The two libs are a matched pair, so both come from onnxruntime-android.
            pickFirsts += "**/libonnxruntime.so"
            pickFirsts += "**/libonnxruntime4j_jni.so"
        }
    }

    // App Bundle configuration. These splits are ON by default for an AAB; we set
    // them explicitly so the modular-delivery intent is documented in the build.
    // Play generates optimized per-device APKs from `bundleRelease`, so the large
    // per-ABI native payload (:core:nativebridge / OpenCV / LiteRT NPU runtimes)
    // is only downloaded for the device's actual ABI — no separate artifacts to
    // build. See docs/RELEASE.md.
    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        language { enableSplit = true }
    }
}


tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val version = variant.outputs.first().versionName.get()
            val code = variant.outputs.first().versionCode.get()
            val apkName = "Guillotine-${variant.name}-$version.$code.apk"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(apkName)
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.segmentation.selfie)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.tensorflow.lite)
    // Declared BEFORE sherpa-onnx so its libonnxruntime.so (newer) wins the jniLibs pickFirst.
    implementation(libs.onnxruntime.android)
    implementation(libs.sherpa.onnx)
    implementation(libs.commons.compress)
    implementation(libs.vosk.android)
    implementation(libs.aznavrail)
    implementation(libs.play.services.ads)
    // play-services-ads pulls in an old androidx.work:work-runtime:2.7.0 transitively (bundling
    // Room 2.2.5), which nothing else in the graph outbids — that combo is known to crash on
    // startup on modern Android ("Failed to create an instance of androidx.work.impl.WorkDatabase").
    // Declared explicitly so it wins dependency resolution over the ads SDK's stale transitive pin.
    implementation(libs.androidx.work.runtime)
    implementation(libs.billing.ktx)
    implementation(libs.user.messaging.platform)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.nanohttpd)
    implementation(libs.okhttp)
    implementation(libs.json)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.ui.tooling)
}

// Bundle the repo's help docs into the APK at BUILD time, so the in-app Tutorial/FAQ read them
// offline from a single source of truth: docs/TUTORIAL.md / docs/FAQ.md. The task copies them
// into an AGP-managed generated assets dir (under build/, not committed) registered via the Variant
// API — no hand-maintained duplicate under src/main/assets. Edit the root .md and the next build picks
// it up. They land at asset path `help/TUTORIAL.md` / `help/FAQ.md`.
abstract class CopyHelpDocsTask : DefaultTask() {
    @get:InputFiles abstract val docs: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @TaskAction fun run() {
        val help = outputDir.get().asFile.resolve("help").apply { mkdirs() }
        docs.files.forEach { it.copyTo(help.resolve(it.name), overwrite = true) }
    }
}
val copyHelpDocs = tasks.register<CopyHelpDocsTask>("copyHelpDocs") {
    docs.from(rootProject.file("docs/TUTORIAL.md"), rootProject.file("docs/FAQ.md"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyHelpDocs, CopyHelpDocsTask::outputDir)
    }
}


