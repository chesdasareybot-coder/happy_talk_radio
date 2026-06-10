# HappyTalk Radio 🎙️

HappyTalk Radio is a Push-to-Talk (PTT) walkie-talkie style Android application designed for both seamless online communication and robust offline local area network broadcasting.

## Features

- **Push-to-Talk (PTT) Interface:** A classic walkie-talkie experience—hold to record, release to send.
- **Online Mode (Appwrite Backend):** Uses Appwrite's Realtime capabilities and Storage to instantly sync high-quality audio messages across devices anywhere in the world.
- **Offline Mode (Local Network):** Uses UDP multicasting to broadcast audio over a local Wi-Fi network or mobile hotspot when internet access is unavailable.
- **High-Quality Audio:** Records in `MPEG-4` format with `AAC` encoding (44.1kHz, 64kbps) for crystal clear voice transmission.
- **In-App Auto-Updater:** Seamlessly checks for new releases on GitHub and downloads/installs updates securely directly from the app.

## Latest Updates (v1.01)

- **Audio Stabilization:** Transitioned away from unstable "micro-chunking" (which caused audio gaps and popping) to a standard Walkie-Talkie architectural model, ensuring perfectly continuous recording and playback.
- **Auto-Updater Fix:** Resolved a critical crash (`FileUriExposedException`) on Android 7.0+ during the APK installation process by transitioning from deprecated `COLUMN_LOCAL_URI` to Android's secure `content://` URI provider.

## Backend Setup (Appwrite)

This project relies on an Appwrite backend for the online mode. You can quickly bootstrap the necessary Databases, Collections, and Storage Buckets using the provided Node.js scripts:

```bash
npm install
node setup-appwrite.js
node fix-bucket.js
```

*Ensure your Appwrite credentials are appropriately configured in the scripts and the Android application (`AppwriteManager.kt`).*

## Building the App

To build a release APK using Gradle:

```bash
# Windows
.\gradlew assembleRelease

# macOS/Linux
./gradlew assembleRelease
```

The release build automatically signs the APK using the provided `happytalk.keystore` file. The resulting APK can be found in `app/build/outputs/apk/release/`.

## Distribution & Auto-Updater

To distribute an update that the app will automatically pick up:
1. Build the release APK (`assembleRelease`).
2. Navigate to your GitHub repository (`chesdasareybot-coder/happy_talk_radio`).
3. Create a new Release with a tag name corresponding to the new version (e.g., `v1.02`).
4. Upload the built `.apk` file as an Asset to the release. 
5. Users opening the app will automatically be prompted to download and install the new version.
