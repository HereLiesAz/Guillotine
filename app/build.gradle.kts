import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// ---- Four-part version: Major.Minor.Patch.Build ----
//   • Major / Minor — hand-edited in version.properties.
//   • Patch — the number of commits on this branch since the last time versionMinor changed
//             (auto-derived from git; resets to 0 the first commit after a Minor bump).
//   • Build — the total commit count on this branch, plus a fixed offset that absorbs the gap
//             between where the old file-based counter left off and where git-count is today
//             (see [VERSION_CODE_OFFSET]). This is the monotonic versionCode.
//
// Nothing is auto-incremented on disk. Every commit advances the counter automatically because
// `git rev-list --count HEAD` is monotonic; a failed CI run can no longer strand a bump the way
// the old "increment the file → persist back after Play accepts" flow could.
//
// The counter comes entirely from `git`, so a shallow checkout (fetch-depth: 1) would break it —
// every workflow that builds does a full-depth checkout (`fetch-depth: 0`).

// Bridge from the old file-based counter (which last landed at versionBuild=291) to git rev-count
// (which is currently 228 for this branch). 100 + 228 = 328 keeps the versionCode strictly above
// the last value Play saw (291). This offset is a constant — DO NOT change it after the first
// build that uses it, or Play will reject the next upload as non-monotonic.
val VERSION_CODE_OFFSET = 100

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

// Total commit count on HEAD — monotonic per push. On a shallow checkout returns something small
// but consistent for that checkout; CI does full-depth so this is the real number in real builds.
val gitCommitCount = runGit("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0

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
val verBuild = VERSION_CODE_OFFSET + gitCommitCount

// Android requires versionCode >= 1; a shallow/broken git checkout could yield 0.
val computedVersionCode = maxOf(1, verBuild)
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
    implementation(libs.sherpa.onnx)
    implementation(libs.commons.compress)
    implementation(libs.vosk.android)
    implementation(libs.aznavrail)
    implementation(libs.play.services.ads)
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
// offline from a single source of truth: TUTORIAL.md / FAQ.md at the repo root. The task copies them
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
    docs.from(rootProject.file("TUTORIAL.md"), rootProject.file("FAQ.md"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyHelpDocs, CopyHelpDocsTask::outputDir)
    }
}


