# ── Sable Rewr Chat ProGuard Rules ──────────────────────────────────────────

# ── KEEP ALL APP CLASSES ────────────────────────────────────────────────────
# Nuclear option: keep every class in our package. The app is tiny (~15 classes)
# so there's no real size win from stripping them, and R8 will still optimise
# third-party library code. This eliminates any risk of stripping classes that
# Android resolves by name (services, receivers, activities from manifest,
# WorkManager workers, JS bridge methods, etc.).
-keep class ca.rewr.sable.** { *; }

# ── Firebase ────────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ── AndroidX Security (EncryptedSharedPreferences) ─────────────────────────
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Tink (used by EncryptedSharedPreferences) ──────────────────────────────
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ── AndroidX WorkManager ───────────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker

# ── AndroidX Core (NotificationCompat, Person, etc.) ──────────────────────
-keep class androidx.core.app.** { *; }
-dontwarn androidx.core.app.**

# ── Material Components ────────────────────────────────────────────────────
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── Splash Screen ──────────────────────────────────────────────────────────
-keep class androidx.core.splashscreen.** { *; }
-dontwarn androidx.core.splashscreen.**

# ── Suppress common warnings ──────────────────────────────────────────────
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn java.lang.invoke.**
