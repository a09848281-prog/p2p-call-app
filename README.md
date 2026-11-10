# Remote Device Control

iPad PWA + Google Apps Script + Google Sheets + Android 13 companion APK (Java only).

## Current implementation
- iPad PWA: GAS URL setup, one-time pairing code generation, registered-device list and status.
- GAS: one-time pairing codes (10-minute expiry), token-hash authentication for Android heartbeat and command polling.
- Android APK: Java-only pairing UI and persistent foreground connection-monitor service.
- GitHub Actions: build a debug APK and deploy the PWA to GitHub Pages.

## Important status
This is a working foundation, **not yet a finished remote camera/microphone streaming product**. WebRTC signaling and media rendering are not implemented in this version. The current remote start command only produces a notification on Android; it does not secretly activate the camera or microphone.

Android 13 prevents an ordinary background command from silently starting camera/microphone capture. The device owner must open the Android app, grant camera/microphone permissions and explicitly start a visible foreground capture session. While an already-started, permitted session remains alive, screen-off behavior can be tested on the actual device. Force Stop, power-off, permission revocation and manufacturer battery restrictions cannot be bypassed.

## Setup
1. Create a Google Sheet and open Extensions → Apps Script.
2. Paste `gas/Code.gs`, save, and run `setup()` once.
3. In Apps Script → Project Settings → Script properties, add ADMIN_KEY with a random secret (at least 24 characters). Do not put this key in GitHub.\n4. Deploy Apps Script as a Web app and copy its /exec URL.
5. Open `web/` through the deployed GitHub Pages site, then enter the GAS URL and ADMIN_KEY in the settings.
6. In GitHub repository Settings → Pages, select GitHub Actions as the deployment source.
7. Run the **Build Android APK** workflow. Download the debug APK from its workflow artifact.
7. Install the APK on the Android device, enter the same GAS URL and the one-time code displayed by the iPad PWA, then start connection monitoring.

## Project layout
- `web/`: iPad PWA
- `gas/Code.gs`: Google Apps Script API
- `android/`: Android Java project
- `.github/workflows/`: build and deploy automation

Only use this on devices you own or have explicit permission to manage.
