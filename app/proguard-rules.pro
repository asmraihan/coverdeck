# The privileged half of CoverDeck is loaded by Shizuku into a separate shell-UID
# process by class name, so it must survive shrinking verbatim.
-keep class com.raihan.coverdeck.privileged.** { *; }
-keep class com.raihan.coverdeck.IPrivilegedService { *; }
-keep class com.raihan.coverdeck.IPrivilegedService$** { *; }
-keep class com.raihan.coverdeck.model.** { *; }
-keep class rikka.shizuku.** { *; }
-dontwarn org.lsposed.hiddenapibypass.**
