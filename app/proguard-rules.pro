# ── Sable Rewr Chat ProGuard Rules ──────────────────────────────────────────

# Keep all JavascriptInterface methods (called from JS in WebView)
-keepclassmembers class ca.rewr.sable.WebNotificationInterface {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep BroadcastReceivers referenced in AndroidManifest
-keep class ca.rewr.sable.BootReceiver
-keep class ca.rewr.sable.MarkReadReceiver
-keep class ca.rewr.sable.ReplyReceiver

# Keep Firebase Messaging Service
-keep class ca.rewr.sable.FcmPushService

# Keep SyncService$WatchdogWorker (referenced by WorkManager)
-keep class ca.rewr.sable.SyncService$WatchdogWorker

# Keep Config constants (referenced across the app)
-keep class ca.rewr.sable.Config { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# AndroidX Security (EncryptedSharedPreferences)
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# Tink (used by EncryptedSharedPreferences)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# AndroidX WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker

# Suppress warnings for missing annotations
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
