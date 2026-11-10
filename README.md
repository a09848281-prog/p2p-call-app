# Remote Device Control

iPad / iPhone browser PWA + Google Apps Script + Google Sheets + Android companion APK (Java only).

## Current implementation
- PWA: administrator-key setup, one-time pairing codes, device list, explicit start/stop requests, and WebRTC camera/microphone viewer.
- GAS: pairing, hashed Android device tokens, heartbeat, command queue, and authenticated WebRTC signaling.
- Android: Java-only pairing, foreground connection monitor, locally initiated camera/microphone foreground service, persistent notification, and a partial CPU wake lock while streaming.
- GitHub Actions: Android debug APK build and GitHub Pages deployment.

## Camera and microphone behavior
1. Install and open the Android app while the phone is unlocked.
2. Register the phone with the one-time code.
3. Grant camera, microphone, and notification permissions.
4. Start **「バックグラウンド接続監視」**.
5. While the app is visible and the phone is unlocked, tap **「この端末のカメラ・マイクを開始」**. Android shows an ongoing notification and the system privacy indicators while capture is active.
6. After this owner-initiated session is running, the screen may be locked while the foreground service continues trying to maintain the camera/microphone stream. The iPad can connect to this already-running session.
7. To stop, use the Android notification or the iPad stop request.

An iPad request while the phone is locked only creates a notification. The user must tap it and start/approve capture on the Android device. The app does not silently activate a camera or microphone from a remote command. Android restrictions prohibit starting camera/microphone foreground services from the background in the ordinary case. Force Stop, revoked permissions, power-off, camera/microphone privacy toggles, OEM power management, and network changes can interrupt the session. Screen-off continuity must be tested on the actual handset.

## Stability and network notes
- Media capture is started only after the Android activity has requested permissions and started the foreground service.
- Camera track is attached before capture starts; capture runs at 640x480 / 15 fps as a conservative baseline.
- Audio processing requests echo cancellation, noise suppression, and automatic gain control.
- WebRTC viewer distinguishes receiving media tracks from actual playback and reports ICE/connection failures.
- The included ICE configuration uses public STUN servers only. Some mobile networks and NATs require a TURN relay; no TURN server or credentials are included.

## Setup
1. Create a Google Sheet, open Extensions → Apps Script, and paste `gas/Code.gs`.
2. Run `setup()` once.
3. In Apps Script → Project Settings → Script properties, add `ADMIN_KEY` with a random secret of at least 24 characters. Keep it private.
4. Deploy as a Web app and copy its `/exec` URL.
5. Set `GAS_URL` in `web/config.js` if desired, or enter the URL in the PWA.
6. In GitHub repository Settings → Pages, choose GitHub Actions.
7. Run **Build Android APK** and download the debug APK artifact after the workflow succeeds.
8. Open the Pages URL on the iPad, enter the GAS URL and ADMIN_KEY, save, and issue a pairing code.
9. On Android, install the APK, enter the GAS URL and one-time code, then start connection monitoring.
10. With Android unlocked, start the camera/microphone session. Then use the iPad to connect.

## Repository layout
- `web/`: PWA interface and WebRTC viewer
- `gas/Code.gs`: Apps Script API and signaling queue
- `android/`: Java Android app and foreground media service
- `.github/workflows/`: APK build and GitHub Pages deployment

Only use this software on devices you own or have explicit permission to manage. Camera and microphone use must remain visible to and explicitly controlled by the Android device owner.
