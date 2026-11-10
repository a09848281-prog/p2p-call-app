# Remote Device Control

iPad PWA + GAS + Android 13 companion APK project. This project is added as a separate folder so the existing P2P Call app at the repository root is not overwritten.

## Current stage

- iPad PWA: device list, pairing-code entry, connection status, and GAS API integration scaffold.
- GAS: pairing, device listing, command queue, and heartbeat endpoints backed by Google Sheets.
- Android: Java-only Android 13 project scaffold (no Kotlin) with a foreground-service foundation.
- Media transport: WebRTC is the planned media path. Camera/microphone streaming is not yet enabled in this initial scaffold.

## Important Android limitation

Android 13 restricts starting camera/microphone foreground services from the background. The user must launch the companion app and grant camera/microphone permissions, then explicitly start its foreground service while the app is visible. While that service remains active, screen-off operation can be tested on the target device. An iPad command cannot silently bypass Android's camera/microphone privacy restrictions or revive an app after Force stop / power-off.

## Setup

1. Create a Google Apps Script project and paste `gas/Code.gs`.
2. Run `setup()` once, authorize it, then deploy as a Web app.
3. Set the deployment access appropriately for personal use. Do not publish pairing tokens or API secrets.
4. Put the deployed GAS URL into `web/config.js` (copy `config.example.js` first).
5. Enable GitHub Pages for the `/remote-device-control/web` folder (or publish the folder using a Pages workflow).
6. Build the Android project with the included GitHub Actions workflow.

This is an initial foundation, not a finished remote camera product. Do not use it to monitor anyone without their informed consent.
