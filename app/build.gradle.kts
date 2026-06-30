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

// ---- Four-part version: Major.Minor.Patch.Build (from version.properties) ----
//   • Major  — bumped by hand (edit versionMajor).
//   • Minor  — bumped by hand (edit versionMinor).
//   • Patch  — auto-increments on every artifact build; RESETS to 0 the first build after Minor changes.
//   • Build  — auto-increments on every artifact build and NEVER resets ⇒ the monotonic versionCode.
// version.properties is the ONE source of truth and is auto-incremented on EVERY build — no override,
// no opt-out, no way to skip it. There is deliberately no -PversionBuild/-PversionPatch property.
val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) versionPropsFile.inputStream().use { load(it) }
}
// trim().toIntOrNull(): Major/Minor are hand-edited, so a stray space/typo must not crash the build.
val verMajor = versionProps.getProperty("versionMajor", "1").trim().toIntOrNull() ?: 1
val verMinor = versionProps.getProperty("versionMinor", "0").trim().toIntOrNull() ?: 0
var verPatch = versionProps.getProperty("versionPatch", "0").trim().toIntOrNull() ?: 0
var verBuild = versionProps.getProperty("versionBuild", "0").trim().toIntOrNull() ?: 0
val patchBaseMinor = versionProps.getProperty("versionPatchBaseMinor", verMinor.toString()).trim().toIntOrNull() ?: verMinor
// "Every build" — any task that compiles/assembles/bundles/installs/builds (apk or aab, debug or
// release, an explicit compile task, or the aggregate build). Pure config/sync tasks (clean, tasks, IDE
// sync) are excluded so merely opening the project doesn't churn the file.
val isBuildTask = gradle.startParameter.taskNames.any { name ->
    listOf("assemble", "bundle", "install", "compile", "build").any { name.contains(it, ignoreCase = true) }
}
// MANDATORY: increment on every build. Patch resets to 0 the first build after Minor changes; Build
// never resets (so it is a monotonic versionCode). The write runs in the configuration phase because
// versionCode/Name must resolve before any task runs.
if (isBuildTask) {
    verPatch = if (patchBaseMinor != verMinor) 0 else verPatch + 1 // reset on minor bump, else ++
    verBuild += 1 // never resets
    versionProps.setProperty("versionPatch", verPatch.toString())
    versionProps.setProperty("versionBuild", verBuild.toString())
    versionProps.setProperty("versionPatchBaseMinor", verMinor.toString())
    versionPropsFile.outputStream().use {
        versionProps.store(it, "Auto-incremented by build (patch resets on minor, build never)")
    }
}
// Android requires versionCode >= 1; a non-build evaluation (verBuild could be 0) must not yield 0.
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
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


