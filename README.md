# Sable Android

A native Android WebView wrapper for [Sable](https://chat.rewr.ca) — the Matrix client for [rewr.ca](https://rewr.ca).

## Features

- Full-screen WebView with no browser chrome
- Back button navigation
- Camera & microphone permissions for calls and file uploads
- File/image picker support
- External links open in the system browser

## Building

GitHub Actions builds a debug APK automatically on every push to `main`.  
Download from the **Actions** tab → latest run → **sable-android-debug** artifact.

### Manual build

```bash
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Android SDK.
