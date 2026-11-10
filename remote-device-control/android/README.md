# Android 13 companion APK (Java, no Kotlin)

The Android project is being built with Java only. The first implementation stage establishes pairing and an explicitly started foreground service. Camera/microphone WebRTC capture will be added after pairing and lifecycle validation.

Android 13 requirements:
- Request CAMERA and RECORD_AUDIO at runtime.
- Start the camera/microphone foreground service while the Activity is visible and permissions are granted.
- Display a persistent notification with a Stop action while capture is active.
- Do not claim that a remote GAS command can bypass Android's background camera/microphone start restrictions.
- Boot-time restart may restore a non-capturing connection monitor only where permitted; camera/microphone capture must require an explicit user-started session.
