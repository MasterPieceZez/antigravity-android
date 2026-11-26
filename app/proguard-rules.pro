-keep class com.termux.shared.** { *; }
-keep class com.termux.app.fragments.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*,JavascriptInterface
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.codehaus.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
