# androidx.security:security-crypto bundles Google Tink, whose classes reference
# compile-only error-prone annotations (@CanIgnoreReturnValue, @Immutable, …)
# that aren't on the Android runtime classpath. They carry no runtime behaviour,
# so silence R8's missing-class errors rather than dragging the annotations in.
-dontwarn com.google.errorprone.annotations.**

# Keep file/line info so obfuscated release stack traces can be retraced with
# the R8 mapping.txt (attached to each signed GitHub release by release.yml).
# Rename the source-file attribute so kept names don't leak beyond "SourceFile".
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep kotlinx.serialization generated serializers for core model classes.
-keepclassmembers class com.questloop.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.questloop.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.questloop.core.model.**$$serializer { *; }
