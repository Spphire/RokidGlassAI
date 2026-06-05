# Phone app R8 rules for the simplified photo-AI bridge.

-keep class com.example.rokidcommon.** { *; }

# OkHttp is the only network stack used by the Codex++ relay client.
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-dontwarn okio.**

# Coroutines are used by the foreground bridge service, Bluetooth receiver, and AI client.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
