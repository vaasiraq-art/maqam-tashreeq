# Maqam Tashreeq — Android APK

Native Android MIDI bridge + the Maqam Tashreeq engine.

## Why this is not a plain WebView wrapper
Android WebView does **not** implement the Web MIDI API — only Chrome does.
A Capacitor/Cordova wrapper of the HTML would therefore detect zero devices.
This project bridges Android's native `android.media.midi` stack into the page
as `navigator.requestMIDIAccess`, so the same HTML works offline in the APK.

## Build (no Mac needed)

1. Install **Android Studio** (free) — https://developer.android.com/studio
2. `File > Open` → select this folder. Let Gradle sync (first sync downloads the SDK).
3. Plug in your phone with **USB debugging** enabled, or use an emulator.
4. Press **Run ▶** — it installs and launches.

### Producing an installable .apk file
`Build > Build Bundle(s)/APK(s) > Build APK(s)`
Output: `app/build/outputs/apk/debug/app-debug.apk`

For a shareable release build:
`Build > Generate Signed Bundle/APK > APK`, create a keystore, choose **release**.

## Connecting an instrument
- **USB:** phone → USB-OTG adapter → instrument's USB-to-Host port.
  The app is registered for USB_DEVICE_ATTACHED, so it can auto-launch on plug-in.
- **Bluetooth MIDI:** grant the Bluetooth permission when prompted.

## Requirements
- minSdk 23 (Android 6.0) — `android.media.midi` was added in API 23
- Device must report `android.software.midi` (nearly all modern phones do)

## Safety
`MainActivity.panicRestore()` re-sends **Local Control ON** plus All-Notes-Off and
Reset-All-Controllers on all 16 channels when the app is destroyed, so the
instrument is never left mute. The HTML keeps its own persistent safety flag and
auto-heals on reconnect.

## Updating the app content
Replace `app/src/main/assets/index.html` with a newer build of the engine and
rebuild. The bridge does not need to change.
