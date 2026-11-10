# Android companion APK (Java only)

This module contains the Android companion app. It uses Java; Kotlin is not required.

## Pairing and startup
1. Open the app while the phone is unlocked.
2. Enter the GAS Web App URL, device name, and the one-time six-digit pairing code created by the iPad page.
3. Grant camera, microphone, and notification permissions when requested.
4. Start the background connection monitor if you want the device heartbeat and iPad commands.
5. To stream media, keep the app visible and tap **「この端末のカメラ・マイクを開始」**.
6. The app starts a foreground media service and displays an ongoing notification. The iPad can then connect using WebRTC.

## Camera compatibility and diagnostics
- The app tries Camera2 first and falls back to Camera1 if Camera2 is unavailable or cannot create a capturer.
- WebRTC initialization and camera startup run on a background executor rather than blocking the service main thread.
- The last Java-side startup failure is saved in app preferences and shown when the app is opened again.
- Native process crashes (for example, a vendor camera driver crash) may not be catchable by Java code. A real-device test is still required.

## Privacy and Android background limits
Android requires the device owner to explicitly start camera/microphone use while the app is visible and permissions are granted. An iPad command can notify the Android device, but it cannot silently begin a new camera or microphone capture while the phone is locked. An already-running foreground session may continue after screen lock, subject to Android and Samsung power-management behavior.

## Network
GAS handles pairing, commands, and signaling only. Media is sent directly between Android and iPad over WebRTC. The current client configuration uses public STUN servers only; there is no TURN relay. Some networks therefore cannot establish a direct connection.
