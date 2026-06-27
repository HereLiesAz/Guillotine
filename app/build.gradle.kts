import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Load version properties
val versionPropsFile = project.rootProject.file("version.properties")
val versionProps = Properties().apply {
    if (versionPropsFile.exists()) {
        versionPropsFile.inputStream().use { load(it) }
    }
}

// versionCode: an explicit override wins (CI passes -PversionBuild=<git commit count>), giving a
// deterministic, monotonically-increasing code required for Play Store uploads. Without it, fall
// back to the local auto-increment in version.properties (handy for ad-hoc release builds).
val versionBuildOverride = (project.findProperty("versionBuild") as String?)?.toIntOrNull()
var currentVersionCode = versionBuildOverride ?: versionProps.getProperty("versionBuild", "1").toInt()

// Automatically increment versionCode for release builds (only when not explicitly overridden).
val isReleaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
if (versionBuildOverride == null && isReleaseBuild) {
    currentVersionCode++
    versionProps.setProperty("versionBuild", currentVersionCode.toString())
    versionPropsFile.outputStream().use {
        versionProps.store(it, "Auto-incremented by release build")
    }
}

val verMajor = versionProps.getProperty("versionMajor", "1")
val verMinor = versionProps.getProperty("versionMinor", "0")
val verPatch = versionProps.getProperty("versionPatch", "0")
val currentVersionName = "$verMajor.$verMinor.$verPatch"

android {
    namespace = "com.hereliesaz.guillotine"
    // compileSdk 37: material3 1.5.0-alpha21 / Compose 1.12.0-alpha03 require compiling against
    // API 37+. targetSdk stays at 36 (stable runtime behavior); the two are independent.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hereliesaz.guillotine"
        minSdk = 26
        targetSdk = 36 // runtime behavior target stays on stable API 36
        versionCode = currentVersionCode
        versionName = currentVersionName
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Use debug signing for local installs so updates don't require uninstall.
            // Replace with a release keystore before publishing to the Play Store.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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

// KGP 2.x: the old android.kotlinOptions DSL is deprecated; set the JVM target here.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // No Compose BOM: material3 1.5.0-alpha needs Compose 1.12.0-alpha03, which no stable BOM
    // ships, so the Compose UI artifacts are pinned via the version catalog (composeUi) instead.
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.aznavrail)

    // AdMob (app-open / banner / interstitial) + UMP consent + process lifecycle.
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)
    implementation(libs.androidx.lifecycle.process)

    // Jetpack Media3: playback (ExoPlayer) + on-device editing/export (Transformer).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    // On-device, no-key ML for the free vision analyzer (face + object/label detection)
    // and subject segmentation / background removal (bundled selfie model, offline).
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.face.detection)
    implementation(libs.mlkit.segmentation.selfie)

    // On-device speech-to-text (BYO model) for transcription/captions.
    implementation(libs.vosk.android)

    // On-device LLM (BYO .task model) — the offline brain for the in-app AI assistant.
    implementation(libs.mediapipe.tasks.genai)

    // On-device object detection (bundled EfficientDet COCO model) — reliable "cut frames with a <object>".
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Embedded MCP server — external AI tools interact with the editor over HTTP.
    implementation(libs.nanohttpd)
    // Outbound WebSocket client for the optional end-to-end-encrypted Cloudflare relay.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    // Real org.json on the unit-test classpath (the android.jar one is a throwing stub), so the
    // SegmentJson parser can be tested on the JVM.
    testImplementation(libs.json)

    debugImplementation(libs.androidx.ui.tooling)
}
