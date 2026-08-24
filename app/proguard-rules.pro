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
