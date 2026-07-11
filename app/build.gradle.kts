import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- Four-part version: Major.Minor.Patch.Build ----
//   • Major / Minor — hand-edited in version.properties.
//   • Patch — the number of commits on this branch since the last time versionMinor changed
//             (auto-derived from git; resets to 0 the first commit after a Minor bump).
//   • Build — seconds since a fixed epoch, recomputed on EVERY build. This is the versionCode.
//
// Why time-based: the versionCode MUST strictly increase on every build, successful or not. A
// git-commit-count code repeats whenever the same commit is built more than once (CI re-runs,
// retries, local rebuilds), and Play then rejects the upload ("You cannot rollout this release
// because it does not allow any existing users to upgrade to the newly added APKs"). Wall-clock
// seconds always advance, so each build gets a fresh, higher code with no on-disk state to strand
// and nothing to persist back after a publish.
//
// Range: seconds-since-2020 is ~1.9e8 today — far above every git-count code we ever shipped (low
// hundreds), so the switchover stays strictly monotonic for Play, and it stays under Android's
// 2,100,000,000 versionCode cap until ~2086.
//
// NOTE: intentionally non-reproducible. Do NOT enable Gradle's configuration cache without wrapping
// the timestamp read in a ValueSource, or the cached value would stop incrementing between builds.

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}
val verMajor = versionProps.getProperty("versionMajor", "1").trim().toIntOrNull() ?: 1
val verMinor = versionProps.getProperty("versionMinor", "0").trim().toIntOrNull() ?: 0

fun runGit(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootProject.rootDir
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

// Commits since the commit that most recently touched `versionMinor=` in version.properties.
// This is what makes Patch reset to 0 on a Minor bump: bumping Minor is a fresh commit, so the
// range HEAD..that-commit is 0 immediately after. `git blame` fingers the commit; `rev-list
// --count` counts commits from it to HEAD (exclusive of the commit itself).
val patchCommits: Int = run {
    val blame = runGit("blame", "-l", "-L", "/versionMinor=/,+1", "version.properties")
        ?: return@run 0
    val sha = blame.substringBefore(' ').trimStart('^').takeIf { it.length >= 7 } ?: return@run 0
    runGit("rev-list", "--count", "$sha..HEAD")?.toIntOrNull() ?: 0
}

val verPatch = patchCommits

// Monotonic per-build code: seconds since 2020-01-01T00:00:00Z. Always advances between builds
// (successful or not), so Play never sees a repeated versionCode. Read once here at configuration
// time, so a single build uses one consistent value everywhere.
val VERSION_EPOCH_SECONDS = 1_577_836_800L // 2020-01-01T00:00:00Z
val verBuild = ((System.currentTimeMillis() / 1000L) - VERSION_EPOCH_SECONDS).coerceAtLeast(1L)

// Android requires versionCode in 1..2_100_000_000; verBuild stays well inside that range.
val computedVersionCode = verBuild.toInt()
val computedVersionName = "$verMajor.$verMinor.$verPatch.$verBuild"

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

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
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


