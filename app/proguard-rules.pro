-keep class com.termux.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*,JavascriptInterface
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.codehaus.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
