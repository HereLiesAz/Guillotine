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
//   • Patch / Build — AUTO-INCREMENTED on disk on EVERY Gradle configuration, i.e. every single
//     compile/build, with no exceptions. Persisted to version.properties so the bump survives across
//     builds and lands in git when committed. Build is the monotonic versionCode.
//
// The increment runs at configuration time below, so any `./gradlew` build/compile of `:app` bumps
// both counters and writes them back to disk. Build never regresses below the git-derived floor
// (100 + commit count), so uploads to Play stay strictly increasing even across machines/checkouts.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}
val verMajor = versionProps.getProperty("versionMajor", "0").trim().toIntOrNull() ?: 0
val verMinor = versionProps.getProperty("versionMinor", "0").trim().toIntOrNull() ?: 0

fun runGit(vararg args: String): String? = runCatching {
    providers.exec {
        commandLine("git", *args)
        workingDir = rootProject.rootDir
    }.standardOutput.asText.get().trim().takeIf { it.isNotEmpty() }
}.getOrNull()

// Monotonic floor for versionCode: never below 100 + commit count (the value earlier git-based and
// file-based flows would have produced), so Play never rejects an upload as non-monotonic.
val gitFloor = 100 + (runGit("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0)

// First-run seed for Patch only (when version.properties has no versionPatch yet): commits since
// versionMinor last changed, so Patch continues from today's value instead of restarting at 1.
val patchSeed: Int = run {
    val blame = runGit("blame", "-l", "-L", "/versionMinor=/,+1", "version.properties") ?: return@run 0
    val sha = blame.substringBefore(' ').trimStart('^').takeIf { it.length >= 7 } ?: return@run 0
    runGit("rev-list", "--count", "$sha..HEAD")?.toIntOrNull() ?: 0
}

// Increment BOTH counters on every configuration. Persisted + monotonic.
val verPatch = (versionProps.getProperty("versionPatch")?.trim()?.toIntOrNull() ?: patchSeed) + 1
val verBuild = maxOf(versionProps.getProperty("versionBuild")?.trim()?.toIntOrNull() ?: 0, gitFloor) + 1

// Persist the bumped values straight back to disk (keeping the human-edited Major/Minor + header).
versionPropsFile.writeText(
    buildString {
        appendLine("# Major and Minor are human-edited. Patch and Build AUTO-INCREMENT on every Gradle")
        appendLine("# build/compile (see app/build.gradle.kts) — do not hand-edit them.")
        appendLine("versionMajor=$verMajor")
        appendLine("versionMinor=$verMinor")
        appendLine("versionPatch=$verPatch")
        appendLine("versionBuild=$verBuild")
    },
)

// Android requires versionCode >= 1.
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


