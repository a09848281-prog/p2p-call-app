# Remote Device Control

iPad PWA + Google Apps Script + Google Sheets + Android 13 companion APK (Java only).

## Implemented
- iPad PWA: GAS URL and administrator-key settings, registered-device list, one-time pairing-code creation, and command queue UI.
- GAS: one-time pairing codes (10-minute expiry), device-token hash storage, Android heartbeat/command polling, and administrator-key protection for management operations.
- Android: Java-only pairing screen and foreground connection-monitor service.
- GitHub Actions: Android debug APK build and GitHub Pages deployment.

## Not yet implemented
WebRTC signaling, live camera/microphone capture and streaming, remote screen control, and actual remote camera activation are not implemented. The current start command only sends a notification to Android. This is not yet a completed remote-camera product.

Android 13 restricts starting camera/microphone foreground services from the background. The device owner must open the app, grant permissions, and explicitly start a visible capture session. Screen-off continuation can only be tested after a permitted session is already active and is not guaranteed on every device. Force Stop, power-off, permission revocation and manufacturer restrictions cannot be bypassed.

## Setup
1. Create a Google Sheet, open Extensions → Apps Script, and paste `gas/Code.gs`.
2. Run `setup()` once.
3. In Apps Script → Project Settings → Script properties, add `ADMIN_KEY` with a random secret of at least 24 characters. Keep it private.
4. Deploy Apps Script as a Web app and copy the /exec URL.
5. Open `web/config.js` and set `GAS_URL` to the deployment URL. Commit it.
6. In GitHub repository Settings → Pages, choose GitHub Actions.
7. Open Actions → **Build Android APK** and download the debug APK artifact after the workflow succeeds.
8. On iPad, open the Pages URL, enter the GAS URL and ADMIN_KEY, and issue a pairing code. On Android, enter the same GAS URL and code once, then start connection monitoring.

Do not publish the ADMIN_KEY or share device tokens. Use this only on devices you own or have explicit permission to manage.
