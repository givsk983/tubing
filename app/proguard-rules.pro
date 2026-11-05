# JNA uses reflection and JNI to bind libc for the PTY terminal.
-keep class com.sun.jna.** { *; }
-keep interface com.stealth.rootexecutor.PtySession$LibC { *; }
# Desktop-only JNA helpers are not used by the Android PTY backend.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window

# LiquidGlass guards these Android 16 APIs at runtime; compileSdk is still 35.
-dontwarn android.graphics.RuntimeColorFilter
-dontwarn android.graphics.RuntimeXfermode

# Native entry points are resolved by their original JVM class and method names.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# xterm.js calls these annotated methods through Android's WebView bridge.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
