import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Desktop installer version, derived from the single source of truth (version.properties, bumped in
// the root build). jpackage (MSI/DMG/DEB) accepts only MAJOR.MINOR.PATCH with MAJOR >= 1, so the
// four-part app version is projected onto three fields and the major is floored at 1.
val desktopPackageVersion: String = run {
    val extra = rootProject.extra
    fun v(key: String, default: Int) = if (extra.has(key)) extra[key] as Int else default
    val major = v("verMajor", 1).coerceAtLeast(1)
    val minor = v("verMinor", 0)
    val patch = v("verPatch", 0)
    "$major.$minor.$patch"
}

// Bytedeco publishes one artifact per (module, os, arch). javacv-platform pulls every
// combination (~600 MB); we only need the host's — the installer only runs on that host.
val javacppClassifier: String = run {
    val os = OperatingSystem.current()
    val arch = System.getProperty("os.arch").lowercase()
    val isArm = arch == "aarch64" || arch == "arm64"
    when {
        os.isMacOsX -> if (isArm) "macosx-arm64" else "macosx-x86_64"
        os.isWindows -> "windows-x86_64"
        os.isLinux -> if (isArm) "linux-arm64" else "linux-x86_64"
        else -> error("Unsupported host OS: ${os.name}")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.nanohttpd)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.json)

    // ONNX Runtime for the JVM (bundles win/linux/mac natives, extracted to a temp dir via JNI at
    // runtime) — powers on-device ML tools on desktop. See jlink note in nativeDistributions below.
    implementation(libs.onnxruntime.jvm)

    // Vosk (offline speech-to-text) for the JVM — powers on-device transcription/captions. Bundles
    // JNA-loaded natives (linux-x86_64 / windows-x86_64 / macOS), extracted from the jar at runtime
    // like ORT. Apple-Silicon (osx-arm64) coverage should be confirmed on hardware (see libs toml).
    implementation(libs.vosk.jvm)

    // Core Java wrappers (no natives).
    implementation(libs.javacv)
    // Host-only natives — matches the runner's OS/arch, keeps the installer ~4× smaller than
    // javacv-platform. Producers of installers for a different OS must run on that OS.
    implementation(variantOf(libs.javacpp) { classifier(javacppClassifier) })
    implementation(variantOf(libs.ffmpeg) { classifier(javacppClassifier) })
}

// Bake the full four-part version (single source of truth: version.properties) into a resource so the
// self-updater can compare the running build against the latest GitHub Release at runtime. jpackage's
// three-part packageVersion isn't enough — we want the exact build. Read from the classpath root as
// `/guillotine-version.properties`.
val versionResourceDir = layout.buildDirectory.dir("generated/versionResource")
val writeVersionResource = tasks.register("writeVersionResource") {
    val versionName = rootProject.extra["versionName"] as String
    val outFile = versionResourceDir.map { it.file("guillotine-version.properties") }
    inputs.property("versionName", versionName)
    outputs.file(outFile)
    doLast {
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("version=$versionName\n")
        }
    }
}
sourceSets.named("main") { resources.srcDir(versionResourceDir) }
tasks.named("processResources") { dependsOn(writeVersionResource) }

compose.desktop {
    application {
        mainClass = "com.hereliesaz.guillotine.desktop.MainKt"
        nativeDistributions {
            // Only the format matching the current runner runs; the others are skipped with
            // "task is not compatible with the current OS" — that's how jpackage matrix builds work.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Guillotine"
            packageVersion = desktopPackageVersion
            vendor = "HereLiesAz"
            description = "An AI-powered non-linear video editor."
            copyright = "© 2026 HereLiesAz"

            // jlink strips the JDK to only the modules Compose + our code need. If a runtime
            // NoClassDefFoundError points at java.foo, add "java.foo" here.
            //  - java.sql: kotlinx.serialization uses java.sql.Date reflectively.
            //  - jdk.unsupported: JavaCPP uses sun.misc.Unsafe.
            //  - java.naming: Compose Desktop / logging uses javax.naming.
            // ONNX Runtime needs NO extra module here: it loads its natives via JNI by extracting
            // the bundled libs to java.io.tmpdir + System.load (filesystem, not a JPMS module), and
            // its only notable JDK dep — java.util.logging (OrtEnvironment's Logger) — is already in
            // the image via java.sql's `requires transitive java.logging`. If a runtime
            // NoClassDefFoundError ever points at java.util.logging, add "java.logging" explicitly.
            modules("java.sql", "jdk.unsupported", "java.naming")

            macOS {
                bundleID = "com.hereliesaz.guillotine"
                iconFile.set(project.file("src/main/resources/icons/icon.icns"))
                dockName = "Guillotine"
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/icon.ico"))
                menuGroup = "Guillotine"
                // upgradeUuid must remain constant across releases so MSI upgrades replace
                // in place. Generated once; never regenerate — a new UUID means side-by-side
                // installs instead of upgrades.
                upgradeUuid = "8f4c1e3a-2b7f-4d1a-9e0b-3c6f8d5a2b90"
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/icon.png"))
                menuGroup = "Video"
                appCategory = "AudioVideo"
                debMaintainer = "hereliesaz@gmail.com"
                rpmLicenseType = "MIT"
            }
        }
    }
}
