import java.util.Properties

buildscript {
    configurations.all {
        resolutionStrategy {
            force("org.jdom:jdom2:2.0.6.1")
            force("org.apache.httpcomponents:httpclient:4.5.14")
            force("com.google.protobuf:protobuf-javalite:4.28.3")
            force("com.google.protobuf:protobuf-java:4.28.3")
            force("io.netty:netty-codec-http2:4.2.16.Final")
            force("io.netty:netty-handler:4.2.16.Final")
            force("io.netty:netty-codec-http:4.2.16.Final")
            force("io.netty:netty-codec:4.2.16.Final")
            force("org.bouncycastle:bcprov-jdk18on:1.84")
            force("org.bouncycastle:bcpkix-jdk18on:1.84")
            force("org.apache.commons:commons-lang3:3.18.0")
            force("org.bitbucket.b_c:jose4j:0.9.6")
        }
    }
}

// Top-level build file. Plugins are declared here with `apply false` and applied
// in module build files. Versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
}

allprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jdom:jdom2:2.0.6.1")
            force("org.apache.httpcomponents:httpclient:4.5.14")
            force("com.google.protobuf:protobuf-javalite:4.28.3")
            force("com.google.protobuf:protobuf-java:4.28.3")
            force("io.netty:netty-codec-http2:4.2.16.Final")
            force("io.netty:netty-handler:4.2.16.Final")
            force("io.netty:netty-codec-http:4.2.16.Final")
            force("io.netty:netty-codec:4.2.16.Final")
            force("org.bouncycastle:bcprov-jdk18on:1.84")
            force("org.bouncycastle:bcpkix-jdk18on:1.84")
            force("org.apache.commons:commons-lang3:3.18.0")
            force("org.bitbucket.b_c:jose4j:0.9.6")
        }
    }
}

// ============================================================================
// Security: pin patched versions of TRANSITIVE dependencies flagged by Dependabot.
// ============================================================================
// None of these are direct dependencies (they arrive transitively via the ML / crypto
// stack), so there is nothing to bump in libs.versions.toml — a resolution force is the
// only lever. Versions below are the latest patched releases verified to exist on Maven
// Central. Applied to every project's configurations so it covers :app, :shared and :desktop.
allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            val g = requested.group
            val n = requested.name
            when {
                // Netty — ~30 CVEs across codec/http/http2/handler/common/proxy (smuggling, DoS, request
                // smuggling, the late-2025 HttpProxyHandler injection). netty-tcnative is on its own 2.0.x
                // line, so never rewrite it.
                g == "io.netty" && n.startsWith("netty-") && !n.contains("tcnative") ->
                    useVersion("4.2.16.Final")
                // Bouncy Castle — GOST CTR keystream reuse (critical), bcpkix broken algorithm, LDAP injection.
                // Keep all *-jdk18on modules on one version (BC requires them to match).
                g == "org.bouncycastle" && n.endsWith("-jdk18on") -> useVersion("1.85")
                g == "org.apache.httpcomponents" && n == "httpclient" -> useVersion("4.5.14")   // XSS
                g == "org.apache.commons" && n == "commons-lang3" -> useVersion("3.20.0")       // uncontrolled recursion
                g == "org.bitbucket.b_c" && n == "jose4j" -> useVersion("0.9.6")                // JWE decompression DoS
                g == "org.jdom" && n == "jdom2" -> useVersion("2.0.6.1")                        // XXE
                // protobuf-javalite DoS (fixed in 3.25.5 / 4.27.5+). Only nudge a 3.x line up to the patched
                // 3.25.5; leave any 4.x (already ≥ patched) alone to avoid a major downgrade that could break
                // the generated code it parses.
                g == "com.google.protobuf" && n == "protobuf-javalite" &&
                    requested.version?.startsWith("3.") == true -> useVersion("3.25.5")
            }
        }
    }
}

// ============================================================================
// Versioning — version.properties is the single source of truth.
// ============================================================================
//
// Four-part version: Major.Minor.Patch.Build.
//   • Major / Minor — hand-edited in version.properties.
//   • Patch / Build — AUTO-INCREMENTED on disk on EVERY Gradle configuration, i.e. every single
//     compile/build of ANY module (:app, :desktop, :shared) with no exceptions — whether the
//     subsequent compilation succeeds or fails (the write happens at configuration time, before any
//     task runs). Persisted to version.properties so the bump survives across builds and lands in
//     git when committed. Build is the monotonic Android versionCode.
//
// This lives in the ROOT project on purpose: the root is configured on every `./gradlew`
// invocation regardless of which module or task is requested, so a :desktop-only or :shared-only
// build bumps the version exactly like an :app build. The computed values are exposed via
// rootProject.extra for the module build scripts to read (they no longer compute their own).
run {
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

    // Monotonic floor for versionCode: never below 205920500 + commit count (the value earlier git-based
    // and file-based flows would have produced), so Play never rejects an upload as non-monotonic.
    val gitFloor = 205920500 + (runGit("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0)

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
            appendLine("# build/compile of any module (see the root build.gradle.kts) — do not hand-edit them.")
            appendLine("versionMajor=$verMajor")
            appendLine("versionMinor=$verMinor")
            appendLine("versionPatch=$verPatch")
            appendLine("versionBuild=$verBuild")
        },
    )

    // Expose for the module build scripts (single source of truth — they read, never recompute).
    rootProject.extra["verMajor"] = verMajor
    rootProject.extra["verMinor"] = verMinor
    rootProject.extra["verPatch"] = verPatch
    rootProject.extra["verBuild"] = verBuild
    // Android requires versionCode >= 1.
    rootProject.extra["versionCode"] = maxOf(1, verBuild)
    rootProject.extra["versionName"] = "$verMajor.$verMinor.$verPatch.$verBuild"
}
