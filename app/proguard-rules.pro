# Keep JavascriptInterface methods used by WebView AndroidBridge
-keepclassmembers class com.michael.cinematracker.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
