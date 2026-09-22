# kotlinx.serialization: keep generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.hereliesaz.guillotine.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.hereliesaz.guillotine.**$$serializer { *; }

# protobuf-javalite (bundled inside MediaPipe Tasks, used for BaseOptions/LlmInferenceOptions
# etc.) parses messages via reflection over field names at runtime, not via getters/setters R8
# can see. Without this rule R8 renames/strips those fields and MediaPipe throws at runtime, e.g.
# "Field modelPath_ for <ObfuscatedClass> not found" from LlmInference.createFromOptions /
# BaseOptions.builder().setModelAssetPath(...) — the AAR's own consumer rules don't cover this.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# ML Kit uses reflection and ServiceLoader to discover its internal components at runtime.
# R8 can strip or rename these classes, causing NullPointerException inside SegmenterImpl
# (and similar internal factories) on release builds — observed in Play Console vitals on
# Android 9 / SDK 28. Keep the public API surface and the GMS-internal glue classes.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_** { *; }
