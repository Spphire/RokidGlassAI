# Glasses app R8 rules for capture, Bluetooth transfer, and result display.

-keep class com.example.rokidcommon.** { *; }

# Rokid CXR-S SDK is used for phone connection status hints.
-keep class com.rokid.cxr.** { *; }
-dontwarn com.rokid.cxr.**

# Coroutines drive camera capture, Bluetooth connection monitoring, and photo transfer.
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
