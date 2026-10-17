# UniFFI's generated bindings reach the Rust library through JNA reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-keep class com.syed.magpie.** { *; }

# JNA ships desktop-only code paths that reference AWT. Android has no AWT and
# never reaches them, so R8 only needs to stop warning about the dangling refs.
-dontwarn java.awt.**
-dontwarn javax.swing.**

# youtubedl-android reads its update metadata with Jackson and unpacks Python
# with commons-compress; both reach their classes reflectively.
-keep class com.yausername.** { *; }
-keep class org.apache.commons.compress.archivers.zip.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }
-dontwarn com.fasterxml.jackson.databind.**
-dontwarn java.beans.**
