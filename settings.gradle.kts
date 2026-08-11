pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

buildscript {
    configurations.all {
        resolutionStrategy {
            force("org.jdom:jdom2:2.0.6.1")
            force("org.apache.httpcomponents:httpclient:4.5.14")
            force("com.google.protobuf:protobuf-javalite:4.35.1")
            force("com.google.protobuf:protobuf-java:4.35.1")
            force("io.netty:netty-codec-http2:4.2.17.Final")
            force("io.netty:netty-handler:4.2.17.Final")
            force("io.netty:netty-codec-http:4.2.17.Final")
            force("io.netty:netty-codec:4.2.17.Final")
            force("org.bouncycastle:bcprov-jdk18on:1.85")
            force("org.bouncycastle:bcpkix-jdk18on:1.85")
            force("org.apache.commons:commons-lang3:3.20.0")
            force("org.bitbucket.b_c:jose4j:0.9.6")
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // AzNavRail (com.github.HereLiesAz)
    }
}

rootProject.name = "Guillotine"
include(":shared")
include(":app")
include(":desktop")
