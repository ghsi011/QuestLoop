# Keep kotlinx.serialization generated serializers for core model classes.
-keepclassmembers class com.questloop.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.questloop.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.questloop.core.model.**$$serializer { *; }
