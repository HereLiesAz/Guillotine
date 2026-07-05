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

dependencies {
    implementation(project(":shared"))
    implementation(libs.nanohttpd)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.json)
    implementation(libs.javacv.platform)
}

compose.desktop {
    application {
        mainClass = "com.hereliesaz.guillotine.desktop.MainKt"
        nativeDistributions {
            packageName = "Guillotine"
            packageVersion = "1.0.0"
        }
    }
}
