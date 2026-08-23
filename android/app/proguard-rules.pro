# Keep Chaquopy Python runtime intact
-keep class com.chaquo.** { *; }
-keep class com.chaquo.python.** { *; }

# Keep our JS interface methods (called reflectively from WebView)
-keepclassmembers class com.adabala.naada.MainActivity$NaadaBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep all media session / service classes
-keep class com.adabala.naada.NaadaMediaService { *; }
-keep class com.adabala.naada.FlaskServerService { *; }
-keep class com.adabala.naada.NaadaApplication { *; }

# AndroidX Media
-keep class androidx.media.** { *; }
-keep class android.support.v4.media.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }

# Keep line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
