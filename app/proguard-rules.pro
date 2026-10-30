# Markwon / commonmark reference optional classes we don't use
-dontwarn org.commonmark.**
-dontwarn io.noties.markwon.**

# The ML Kit code scanner builds its internal telemetry/config objects reflectively; minifying them crashes
# the QR scanner on launch.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_code_scanner.** { *; }
-dontwarn com.google.mlkit.**
