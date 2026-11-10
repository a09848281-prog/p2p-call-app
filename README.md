# Remote Device Control

iPad / iPhone PWA + GitHub Pages + Google Apps Script + Google Sheets + Android companion APK (Java only).

## Features
- One-time pairing codes (10-minute expiry) and hashed Android device tokens.
- Authenticated device list, heartbeat, command queue, and WebRTC signaling.
- Camera/microphone streaming using WebRTC direct P2P; media does not pass through GAS.
- STUN-only connectivity discovery. No TURN relay is bundled or configured.
- iPad playback diagnostics, explicit playback recovery button, and ICE/track/frame status.
- Android foreground services with persistent notifications and explicit local camera/microphone start/stop.
- PWA shell caching through a service worker; live signaling still requires an internet connection.

## Important Android privacy and background limits
1. Install and open the Android app while the phone is unlocked.
2. Register the phone using the one-time code.
3. Grant camera, microphone, and notification permissions.
4. Start **「バックグラウンド接続監視」**.
5. While the app is visible and the phone is unlocked, tap **「この端末のカメラ・マイクを開始」**.
6. Android displays an ongoing notification and system privacy indicators while capture is active. An already-started foreground session may continue after the screen is locked, depending on Android version, device manufacturer, battery management, and network conditions.
7. An iPad request while Android is locked can only notify the device owner. The owner must open the app and explicitly start/approve camera and microphone use. The app does not silently start new capture from a remote command.

Force Stop, revoked permissions, power-off, privacy toggles, OEM power management, camera use by another app, and network changes can interrupt the session. Screen-off continuity must be tested on the actual phone. No software can guarantee uninterrupted capture on every Android device.

## Network architecture
- Signaling/control: iPad ↔ GAS ↔ Android.
- Media: WebRTC direct connection between iPad and Android when the network allows it.
- STUN: helps peers discover network routes; it does not relay media.
- TURN is not configured. Some NAT, carrier, Wi-Fi, or firewall combinations will fail with STUN-only connectivity.
- GAS is used for pairing, commands, and signaling only. It is not a media relay.

## Setup
1. Create a Google Sheet and open Extensions → Apps Script.
2. Paste `gas/Code.gs` into the Apps Script project and save.
3. Run `setup()` once and authorize the requested Sheets permissions.
4. In Script Properties, add `ADMIN_KEY` with a random secret of at least 24 characters. Keep it private. Add TURN properties only if you have a TURN provider/server.
5. Deploy as a Web app, execute as the script owner, allow access for the intended clients, and copy the `/exec` URL.
6. If Apps Script code changes later, deploy a new version (Manage deployments → Edit → New version).
7. In GitHub repository Settings → Pages, select GitHub Actions.
8. Wait for **Deploy Remote Device Control PWA** and **Build Android APK** workflows to finish successfully.
9. Open the GitHub Pages URL on iPad, enter the GAS URL and ADMIN_KEY, save, and issue a pairing code.
10. Install the latest debug APK on Android, enter the GAS URL and pairing code, and complete registration.
11. Start connection monitoring, then locally start camera/microphone while Android is unlocked.
12. On iPad, select the device and tap **「映像・音声に接続」**. If autoplay is blocked, tap **「再生を有効化」**.

## Build output
GitHub Actions → **Build Android APK** → latest successful run → Artifacts → `remote-device-control-debug-apk`.

## Repository layout
- `web/`: PWA and WebRTC viewer
- `gas/Code.gs`: Apps Script API, signaling queue, and ICE credential generation
- `android/`: Java Android app and foreground media service
- `.github/workflows/`: APK build and GitHub Pages deployment

Use only on devices you own or have explicit permission to manage. Camera and microphone use must remain visible to and explicitly controlled by the Android device owner.
