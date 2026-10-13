# Keep this file intentionally small.
# Add rules only when R8 reports a real requirement.
# Do not broadly keep packages to hide shrinker problems.

# On-device speech recognition (ADR-0026): Vosk loads its native library through JNA, which finds
# classes and fields by name, so neither can be renamed or stripped.
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**
