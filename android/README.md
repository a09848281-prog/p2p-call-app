# Android 13 companion APK (Java only)

The project contains Java source only; no Kotlin. Pair once using the iPad-generated six-digit code. The app stores the device token locally and can start a foreground connection monitor with a persistent notification.

The monitor polls GAS for status and queued commands. Camera/microphone capture and WebRTC streaming are **not yet implemented**. Android 13 does not allow a background command to silently start camera/microphone capture. The owner must open the app, grant permissions, and explicitly start a camera/microphone foreground session while the app is visible. Do not promise unattended camera activation or bypass system privacy controls.
