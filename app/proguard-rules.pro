# WatchWallet release shrinking rules.
# Libraries used here (kotlinx.serialization, Koin DSL, Room, Compose) ship their own
# consumer rules; add project-specific rules below only when a release build needs them.

# Keep line numbers for readable crash stack traces (no crash reporting SDK is used;
# users can share a stack trace manually).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The chart WebView calls these methods from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
