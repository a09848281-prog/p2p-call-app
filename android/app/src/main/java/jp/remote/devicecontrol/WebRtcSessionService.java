package jp.remote.devicecontrol;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.Manifest;
import android.content.pm.PackageManager;

import org.json.JSONObject;
import org.json.JSONArray;
import org.webrtc.*;
import org.webrtc.Camera1Enumerator;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class WebRtcSessionService extends Service {
    public static final String ACTION_START = "jp.remote.devicecontrol.START_MEDIA";
    public static final String ACTION_STOP = "jp.remote.devicecontrol.STOP_MEDIA";
    private static final String CHANNEL = "rdc_media";
    private static final int NOTIFICATION_ID = 42;

    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService startup = Executors.newSingleThreadExecutor();
    private static volatile boolean webRtcInitialized = false;
    private volatile String startupStage = "待機中";
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<IceCandidate> pendingLocalIce = Collections.synchronizedList(new ArrayList<>());
    private final List<IceCandidate> pendingRemoteIce = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean active = false;
    private volatile boolean offerQueued = false;
    private volatile boolean remoteDescriptionSet = false;
    private volatile long lastPollErrorNotice = 0L;

    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private VideoCapturer cameraCapturer;
    private SurfaceTextureHelper textureHelper;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private AudioTrack audioTrack;
    private VideoTrack videoTrack;
    private PowerManager.WakeLock wakeLock;
    private String sessionId = "";

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            sendByeAndStop();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(intent.getAction()) && !active) {
            beginSession();
        }
        return START_NOT_STICKY;
    }

    private void sendByeAndStop() {
        if (network.isShutdown()) {
            stopSelf();
            return;
        }
        try {
            network.execute(() -> {
                try {
                    SharedPreferences prefs = getSharedPreferences("rdc", MODE_PRIVATE);
                    JSONObject request = auth(prefs);
                    request.put("action", "sendSignal");
                    request.put("fromRole", "android");
                    request.put("type", "bye");
                    request.put("sessionId", sessionId);
                    request.put("payload", "");
                    MainActivity.post(prefs.getString("gasUrl", ""), request);
                } catch (Exception ignored) {
                } finally {
                    stopSelf();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            stopSelf();
        }
    }

    private void beginSession() {
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("カメラの権限がありません。");
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("マイクの権限がありません。");
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) throw new Exception("電源管理サービスを取得できません。");
            Notification notification = buildNotification("カメラ・マイクを準備しています。");
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA | ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            active = true;
            getSharedPreferences("rdc", MODE_PRIVATE).edit().remove("lastMediaError").apply();
            startupStage = "バックグラウンド初期化";
            startup.execute(this::startCameraMicrophoneSession);
        } catch (Exception error) {
            fail("開始準備に失敗: " + (error.getMessage() == null ? error.toString() : error.getMessage()));
        } catch (LinkageError error) {
            fail("Androidネイティブ機能の読み込みに失敗: " + error);
        }
    }

    private void startCameraMicrophoneSession() {
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("カメラの権限がありません。アプリを開いて許可してください。");
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("マイクの権限がありません。アプリを開いて許可してください。");

            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            if (power == null) throw new Exception("電源管理サービスを取得できません。");

            startupStage = "WakeLock準備";
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteDeviceControl:MediaSession");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6L * 60L * 60L * 1000L);

            SharedPreferences prefs = getSharedPreferences("rdc", MODE_PRIVATE);
            sessionId = UUID.randomUUID().toString();

            startupStage = "WebRTCネイティブ初期化";
            synchronized (WebRtcSessionService.class) {
                if (!webRtcInitialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(getApplicationContext()).createInitializationOptions()
                    );
                    webRtcInitialized = true;
                }
            }
            startupStage = "EGL初期化";
            egl = EglBase.create();
            factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext()))
                .createPeerConnectionFactory();

            startupStage = "PeerConnection作成";
            PeerConnection.RTCConfiguration configuration = new PeerConnection.RTCConfiguration(buildIceServers(prefs));
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            configuration.iceCandidatePoolSize = 4;

            peer = factory.createPeerConnection(configuration, new PeerConnection.Observer() {
                @Override public void onSignalingChange(PeerConnection.SignalingState state) {}
                @Override public void onIceConnectionChange(PeerConnection.IceConnectionState state) {
                    if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED)
                        notifyStatus("iPadと通信接続しました。映像・音声を送信中です。");
                    else if (state == PeerConnection.IceConnectionState.FAILED)
                        notifyStatus("直接P2P接続に失敗しました。STUNだけでは通らないネットワークの可能性があります。");
                    else if (state == PeerConnection.IceConnectionState.DISCONNECTED)
                        notifyStatus("通信が一時切断されました。再接続を試みています。");
                }
                @Override public void onIceConnectionReceivingChange(boolean receiving) {}
                @Override public void onIceGatheringChange(PeerConnection.IceGatheringState state) {}
                @Override public void onIceCandidate(IceCandidate candidate) {
                    if (!offerQueued) pendingLocalIce.add(candidate);
                    else sendIce(candidate);
                }
                @Override public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
                @Override public void onAddStream(MediaStream stream) {}
                @Override public void onRemoveStream(MediaStream stream) {}
                @Override public void onDataChannel(DataChannel channel) {}
                @Override public void onRenegotiationNeeded() {}
                @Override public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {}
            });
            if (peer == null) throw new Exception("WebRTC接続を作成できません。");

            startupStage = "マイク初期化";
            MediaConstraints audioConstraints = new MediaConstraints();
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googEchoCancellation", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googNoiseSuppression", "true"));
            audioConstraints.mandatory.add(new MediaConstraints.KeyValuePair("googAutoGainControl", "true"));
            audioSource = factory.createAudioSource(audioConstraints);
            audioTrack = factory.createAudioTrack("rdc_audio", audioSource);
            audioTrack.setEnabled(true);
            peer.addTrack(audioTrack, Collections.singletonList("rdc_stream"));

            startupStage = "カメラ選択";
            cameraCapturer = createCameraCapturer();
            if (cameraCapturer == null) throw new Exception("Camera2/Camera1のどちらでもカメラが見つかりません。");
            textureHelper = SurfaceTextureHelper.create("RDC-Camera", egl.getEglBaseContext());
            videoSource = factory.createVideoSource(false);
            cameraCapturer.initialize(textureHelper, getApplicationContext(), videoSource.getCapturerObserver());
            videoTrack = factory.createVideoTrack("rdc_video", videoSource);
            videoTrack.setEnabled(true);
            peer.addTrack(videoTrack, Collections.singletonList("rdc_stream"));
            startupStage = "カメラ映像取得開始";
            cameraCapturer.startCapture(640, 480, 15);

            startupStage = "接続情報作成";
            MediaConstraints offerConstraints = new MediaConstraints();
            peer.createOffer(new SdpObserver() {
                @Override public void onCreateSuccess(SessionDescription offer) {
                    peer.setLocalDescription(new SdpObserver() {
                        @Override public void onCreateSuccess(SessionDescription description) {}
                        @Override public void onSetSuccess() {
                            offerQueued = true;
                            sendSignal("offer", offer.description);
                            synchronized (pendingLocalIce) {
                                for (IceCandidate candidate : pendingLocalIce) sendIce(candidate);
                                pendingLocalIce.clear();
                            }
                            notifyStatus("接続待機中です。iPadで「映像・音声に接続」を押してください。");
                        }
                        @Override public void onCreateFailure(String error) { fail("ローカル接続情報の作成に失敗: " + error); }
                        @Override public void onSetFailure(String error) { fail("ローカル接続情報の設定に失敗: " + error); }
                    }, offer);
                }
                @Override public void onSetSuccess() {}
                @Override public void onCreateFailure(String error) { fail("接続情報の作成に失敗: " + error); }
                @Override public void onSetFailure(String error) { fail("接続情報の設定に失敗: " + error); }
            }, offerConstraints);

            io.scheduleWithFixedDelay(this::pollSignals, 1, 2, TimeUnit.SECONDS);
            notifyStatus("カメラ・マイクを開始しました。画面ロック後も継続を試みます。");
        } catch (Exception error) {
            fail("開始失敗（" + startupStage + "）: " + (error.getMessage() == null ? error.toString() : error.getMessage()));
        } catch (LinkageError error) {
            fail("ネイティブ処理失敗（" + startupStage + "）: " + error);
        }
    }

    private List<PeerConnection.IceServer> buildIceServers(SharedPreferences prefs) {
        ArrayList<PeerConnection.IceServer> servers = new ArrayList<>();
        String stored = prefs.getString("iceServers", "");
        try {
            JSONArray configured = new JSONArray(stored);
            for (int i = 0; i < configured.length(); i++) {
                JSONObject item = configured.getJSONObject(i);
                Object urlsValue = item.opt("urls");
                ArrayList<String> urls = new ArrayList<>();
                if (urlsValue instanceof JSONArray) {
                    JSONArray urlArray = (JSONArray) urlsValue;
                    for (int j = 0; j < urlArray.length(); j++) {
                        String url = urlArray.optString(j, "");
                        if (!url.isEmpty()) urls.add(url);
                    }
                } else if (urlsValue instanceof String && !((String) urlsValue).isEmpty()) {
                    urls.add((String) urlsValue);
                }
                String username = item.optString("username", "");
                String credential = item.optString("credential", "");
                for (String url : urls) {
                    String lowerUrl = url.toLowerCase(java.util.Locale.ROOT);
                    if (!lowerUrl.startsWith("stun:") && !lowerUrl.startsWith("stuns:")) continue;
                    PeerConnection.IceServer.Builder builder = PeerConnection.IceServer.builder(url);
                    if (!username.isEmpty() && !credential.isEmpty()) {
                        builder.setUsername(username);
                        builder.setPassword(credential);
                    }
                    servers.add(builder.createIceServer());
                }
            }
        } catch (Exception error) {
            notifyStatus("ICE設定を読み込めません。STUN設定で接続します。");
        }
        if (servers.isEmpty()) {
            servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
            servers.add(PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer());
        }
        return servers;
    }

    private VideoCapturer createCameraCapturer() {
        if (Camera2Enumerator.isSupported(this)) {
            try {
                VideoCapturer capturer = findCapturer(new Camera2Enumerator(this));
                if (capturer != null) return capturer;
            } catch (RuntimeException error) {
                notifyStatus("Camera2を利用できないためCamera1を試します。");
            }
        }
        try {
            return findCapturer(new Camera1Enumerator(true));
        } catch (RuntimeException error) {
            notifyStatus("Camera1初期化失敗: " + error.getMessage());
            return null;
        }
    }

    private VideoCapturer findCapturer(CameraEnumerator enumerator) {
        String[] names = enumerator.getDeviceNames();
        for (String name : names) {
            if (enumerator.isFrontFacing(name)) {
                try {
                    VideoCapturer capturer = enumerator.createCapturer(name, null);
                    if (capturer != null) return capturer;
                } catch (RuntimeException ignored) {}
            }
        }
        for (String name : names) {
            if (enumerator.isBackFacing(name)) {
                try {
                    VideoCapturer capturer = enumerator.createCapturer(name, null);
                    if (capturer != null) return capturer;
                } catch (RuntimeException ignored) {}
            }
        }
        return null;
    }

    private void sendIce(IceCandidate candidate) {
        try {
            JSONObject json = new JSONObject();
            json.put("sdpMid", candidate.sdpMid);
            json.put("sdpMLineIndex", candidate.sdpMLineIndex);
            json.put("candidate", candidate.sdp);
            sendSignal("ice", json.toString());
        } catch (Exception error) {
            notifyStatus("通信候補を送信できませんでした。");
        }
    }

    private void sendSignal(String type, String payload) {
        if (network.isShutdown()) return;
        try { network.execute(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("rdc", MODE_PRIVATE);
                JSONObject json = auth(prefs);
                json.put("action", "sendSignal");
                json.put("fromRole", "android");
                json.put("type", type);
                json.put("sessionId", sessionId);
                json.put("payload", payload);
                MainActivity.post(prefs.getString("gasUrl", ""), json);
            } catch (Exception error) {
                notifyStatus("GASへの通信に失敗しました。ネットワークを確認してください。");
            }
        }); } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void pollSignals() {
        if (!active || sessionId.isEmpty()) return;
        try {
            SharedPreferences prefs = getSharedPreferences("rdc", MODE_PRIVATE);
            JSONObject request = auth(prefs);
            request.put("action", "pollSignals");
            request.put("role", "android");
            request.put("sessionId", sessionId);
            JSONObject response = MainActivity.post(prefs.getString("gasUrl", ""), request);
            lastPollErrorNotice = 0L;
            org.json.JSONArray signals = response.optJSONArray("signals");
            if (signals == null) return;
            for (int i = 0; i < signals.length(); i++) {
                JSONObject signal = signals.getJSONObject(i);
                String type = signal.optString("type");
                String payload = signal.optString("payload");
                if ("answer".equals(type) && peer != null && !remoteDescriptionSet) {
                    peer.setRemoteDescription(new SdpObserver() {
                        @Override public void onSetSuccess() {
                            remoteDescriptionSet = true;
                            synchronized (pendingRemoteIce) {
                                for (IceCandidate candidate : pendingRemoteIce) peer.addIceCandidate(candidate);
                                pendingRemoteIce.clear();
                            }
                            notifyStatus("iPadからの応答を受信しました。映像・音声を接続しています。");
                        }
                        @Override public void onSetFailure(String error) { notifyStatus("応答情報を設定できません: " + error); }
                        @Override public void onCreateSuccess(SessionDescription description) {}
                        @Override public void onCreateFailure(String error) {}
                    }, new SessionDescription(SessionDescription.Type.ANSWER, payload));
                } else if ("ice".equals(type) && peer != null) {
                    JSONObject candidateJson = new JSONObject(payload);
                    IceCandidate candidate = new IceCandidate(
                        candidateJson.optString("sdpMid"),
                        candidateJson.optInt("sdpMLineIndex"),
                        candidateJson.optString("candidate")
                    );
                    if (remoteDescriptionSet) peer.addIceCandidate(candidate);
                    else pendingRemoteIce.add(candidate);
                } else if ("bye".equals(type)) {
                    stopSelf();
                    return;
                }
            }
        } catch (Exception error) {
            long now = System.currentTimeMillis();
            if (now - lastPollErrorNotice > 15000L) {
                lastPollErrorNotice = now;
                notifyStatus("接続情報の取得を再試行中です。");
            }
        }
    }

    private JSONObject auth(SharedPreferences prefs) throws Exception {
        JSONObject json = new JSONObject();
        json.put("deviceId", prefs.getString("deviceId", ""));
        json.put("deviceToken", prefs.getString("deviceToken", ""));
        return json;
    }

    private void fail(String message) {
        active = false;
        getSharedPreferences("rdc", MODE_PRIVATE).edit()
            .putString("lastMediaError", message)
            .putLong("lastMediaErrorAt", System.currentTimeMillis())
            .apply();
        notifyStatus(message);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "映像・音声セッション", NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent stopIntent = new Intent(this, WebRtcSessionService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
            this, 42, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Remote Device Control")
            .setContentText(text)
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "セッション停止", stopPendingIntent
            ).build())
            .setOngoing(true)
            .build();
    }

    private void notifyStatus(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override public void onDestroy() {
        active = false;
        startup.shutdownNow();
        io.shutdownNow();
        network.shutdownNow();
        if (cameraCapturer != null) {
            try { cameraCapturer.stopCapture(); } catch (Exception ignored) {}
            try { cameraCapturer.dispose(); } catch (Exception ignored) {}
        }
        if (peer != null) try { peer.dispose(); } catch (Exception ignored) {}
        if (audioTrack != null) try { audioTrack.dispose(); } catch (Exception ignored) {}
        if (videoTrack != null) try { videoTrack.dispose(); } catch (Exception ignored) {}
        if (videoSource != null) try { videoSource.dispose(); } catch (Exception ignored) {}
        if (audioSource != null) try { audioSource.dispose(); } catch (Exception ignored) {}
        if (textureHelper != null) try { textureHelper.dispose(); } catch (Exception ignored) {}
        if (factory != null) try { factory.dispose(); } catch (Exception ignored) {}
        if (egl != null) try { egl.release(); } catch (Exception ignored) {}
        if (wakeLock != null && wakeLock.isHeld()) try { wakeLock.release(); } catch (Exception ignored) {}
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
