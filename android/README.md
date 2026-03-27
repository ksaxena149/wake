# WAKE Android

## Project Setup

- **Language:** Kotlin
- **UI:** Jetpack Compose
- **Package:** `com.wake.dtn`
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 36
- **Build system:** Gradle (Kotlin DSL)

## Prerequisites

- Android Studio (latest stable)
- Android SDK installed via Android Studio SDK Manager
- Physical device with USB debugging enabled, or AVD emulator

## Building

Open the `android/` folder in Android Studio. Gradle syncs automatically on open.

```bash
# From the android/ directory — command-line build
./gradlew assembleDebug
```

The APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Running on a Device

1. Enable **Developer Options** on your Android phone (`Settings → About Phone → tap Build Number 7 times`)
2. Enable **USB Debugging** (`Settings → Developer Options → USB Debugging`)
3. Plug in via USB and tap **Allow** on the trust dialog
4. In Android Studio, select your device from the device dropdown and press **Run**

## Current State

Phase 0 skeleton — blank Compose activity. No WAKE logic yet.

Upcoming work (Phase 2):
- Foreground Service
- Room DB (`bundles`, `seen_ids` tables)
- OkHttp client (POST `/request`, poll `/pending`)
- Jetpack Compose UI (search, status, result screens)
- Google Tink signature verification
