# Remote Device Control

iPad / iPhone browser PWA + Google Apps Script + Google Sheets + Android 13 companion APK (Java only).

## Current implementation
- PWA: administrator-key login, one-time pairing codes, device list, command requests, WebRTC video/audio viewer.
- GAS: device pairing, hashed Android tokens, heartbeat, command queue, authenticated WebRTC signaling.
- Android: Java-only pairing, foreground monitor, WebRTC camera + microphone session, persistent notification, and a partial CPU wake lock during an active media session.
- GitHub Actions: Android debug APK build and GitHub Pages deployment.

## Screen-off behavior (important)
1. Open the Android app while the device is unlocked.
2. Grant camera, microphone and notification permissions.
3. Tap **「この端末のカメラ・マイクを開始」**. Android displays an ongoing foreground-service notification and privacy indicators.
4. After the session has started, the screen can be turned off; the service keeps the camera/microphone session alive as Android and the device manufacturer permit. A partial wake lock is held for up to 8 hours to reduce CPU sleep interruptions.
5. From the iPad, select the device and tap **「映像・音声に接続」**.

**The iPad cannot silently start camera/microphone capture while Android is locked or in the background.** Android 13's while-in-use and foreground-service rules prohibit starting a camera/microphone foreground service solely from a remote background command. A remote start request therefore posts a notification; the Android owner must unlock/open the app and explicitly start capture. Force Stop, power-off, revoked permissions, low battery shutdown and manufacturer-specific restrictions cannot be bypassed. Screen-off continuity must be verified on the actual Android model.

## Network limitations
WebRTC media is sent directly between devices; GAS only carries small signaling messages. The included ICE configuration uses Google's public STUN server. Some NATs, mobile networks, corporate Wi-Fi or carrier-grade NAT require a TURN relay; in those networks, the call may fail until a TURN service is configured. This repository does not provide a TURN server or credentials.

## Setup
1. Create a Google Sheet, open Extensions → Apps Script, and paste `gas/Code.gs`.
2. Run `setup()` once.
3. In Apps Script → Project Settings → Script properties, add `ADMIN_KEY` with a random secret of at least 24 characters. Keep it private.
4. Deploy as a Web app and copy its `/exec` URL. The deployment must be accessible to the devices that use it; do not expose the ADMIN_KEY.
5. Open `web/config.js` and set `GAS_URL` to the deployment URL. Commit it.
6. In GitHub repository Settings → Pages, choose GitHub Actions.
7. Open Actions → **Build Android APK** and download the debug APK artifact after the workflow succeeds.
8. Open the Pages URL on the iPad, enter the GAS URL and ADMIN_KEY, save, and issue a pairing code.
9. On Android, install the APK, enter the same GAS URL and the one-time code, then start connection monitoring.
10. While Android is unlocked, grant permissions and start the camera/microphone session. Then connect from the iPad.

## Repository layout
- `web/`: PWA interface and WebRTC viewer
- `gas/Code.gs`: Apps Script API and signaling queue
- `android/`: Java Android app and foreground media service
- `.github/workflows/`: APK build and Pages deployment

Only use this software on devices you own or have explicit permission to manage. Camera and microphone activity must remain visible to the device owner.
