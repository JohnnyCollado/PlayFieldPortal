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

# lifecycle-compose 2.8.x resolves its LocalLifecycleOwner by REFLECTING into
# compose-ui's AndroidCompositionLocals_androidKt.getLocalLifecycleOwner() (backward
# compatibility with compose-ui 1.6). The library's own consumer rule has a typo
# (matches a ProvidableCompositionLocal[] array return type), so it never fires, R8
# renames the method, the reflection fails, and ANY read of the lifecycle-compose
# local crashes release builds with "CompositionLocal LocalLifecycleOwner not
# present" (debug is unaffected — nothing is renamed there). Seen on the Discord QR
# login screen. Keep the real signature so the bridge works.
-keep public class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt {
    public static androidx.compose.runtime.ProvidableCompositionLocal getLocalLifecycleOwner();
}

# RetroAchievements api-kotlin deserializes its response POJOs with GSON REFLECTION, and the
# JitPack build ships no consumer rules. Without these keeps, R8 renames/strips the model
# fields and constructors, Gson throws JsonIOException on every RA response, and release
# builds report "Couldn't load the RetroAchievements game list" (debug builds are unaffected
# because nothing is renamed). The NetworkResponseAdapter reflects over generic response
# types, so it needs its classes and the Signature attribute intact too.
-keep class org.retroachivements.api.data.** { *; }
-keep class com.haroldadmin.cnradapter.** { *; }
-keepattributes Signature, EnclosingMethod
# api.core holds Gson plumbing the POJOs reference REFLECTIVELY: BooleanJsonDeserializer is
# named in @JsonAdapter field annotations and instantiated by Gson's ConstructorConstructor.
# R8 full mode sees no direct construction, strips its constructor and marks it abstract, and
# adapter creation for any type using it (e.g. GetGameInfoAndUserProgress.Response.isFinal)
# throws "Abstract class can't be instantiated" -> Retrofit's "Unable to create converter".
# GetGameList's types don't use it, which is why matching worked while every sync failed.
# Root-caused via a desktop R8 repro (same rules, same jars) and its retrace mapping.
-keep class org.retroachivements.api.core.** { *; }
