# Play Field Portal — R8 / ProGuard rules for release builds.
# Most libraries (Hilt, WorkManager, kotlinx.serialization, Compose) ship their own
# consumer rules; only project-specific keeps and warning suppressions go here.

# slf4j ships an optional binder that isn't on the classpath — safe to ignore.
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.slf4j.**

# kotlinx.serialization — keep generated serializers for @Serializable model classes.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.playfieldportal.**$$serializer { *; }
-keepclassmembers class com.playfieldportal.** {
    *** Companion;
}
-keepclasseswithmembers class com.playfieldportal.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep enum values() / valueOf() used reflectively by serialization.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
