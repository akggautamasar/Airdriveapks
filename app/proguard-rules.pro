# TDLib communicates with its native library via JNI using reflection on
# these classes, so their names and members must never be obfuscated/removed.
-keep class org.drinkless.tdlib.** { *; }
-keepclassmembers class org.drinkless.tdlib.** { *; }
-dontwarn org.drinkless.tdlib.**

-keepattributes *Annotation*
-keep class com.airdrive.backup.data.db.** { *; }
