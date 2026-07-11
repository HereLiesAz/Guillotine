plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.nanohttpd)
    implementation(libs.okhttp)
    implementation(libs.json)

    // ONNX Runtime — API only, compile-time. The `ai.onnxruntime` Java API is identical across the
    // `onnxruntime` (JVM) and `onnxruntime-android` artifacts, so shared on-device ML (Spleeter) can
    // compile against it here. `compileOnly` keeps the actual runtime + bundled natives out of :shared
    // and off consumers' classpaths: each platform supplies its own — :app pulls `onnxruntime-android`,
    // :desktop pulls `onnxruntime` (JVM). Never make this `implementation`: the JVM jar's classes and
    // natives would collide with onnxruntime-android on the Android build.
    compileOnly(libs.onnxruntime.jvm)

    testImplementation(libs.junit)
    testCompileOnly(libs.onnxruntime.jvm)
}
