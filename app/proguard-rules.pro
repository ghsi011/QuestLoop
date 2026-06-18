# androidx.security:security-crypto bundles Google Tink, whose classes reference
# compile-only error-prone annotations (@CanIgnoreReturnValue, @Immutable, …)
# that aren't on the Android runtime classpath. They carry no runtime behaviour,
# so silence R8's missing-class errors rather than dragging the annotations in.
-dontwarn com.google.errorprone.annotations.**

# Keep kotlinx.serialization generated serializers for core model classes.
-keepclassmembers class com.questloop.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.questloop.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.questloop.core.model.**$$serializer { *; }
