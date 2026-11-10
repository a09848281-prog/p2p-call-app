package jp.remote.devicecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DeviceMonitorService extends Service {
    private static final String CHANNEL = "rdc_monitor";
    private static final int NOTIFICATION_ID = 41;
    private ScheduledExecutorService scheduler;
    private android.os.PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("端末接続を監視しています", false));
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RemoteDeviceControl:Monitor");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(6L * 60L * 60L * 1000L);
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::poll, 0, 25, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            android.content.SharedPreferences prefs = getSharedPreferences("rdc", MODE_PRIVATE);
            String url = prefs.getString("gasUrl", "");
            String id = prefs.getString("deviceId", "");
            String token = prefs.getString("deviceToken", "");
            if (url.isEmpty() || id.isEmpty() || token.isEmpty()) return;

            JSONObject auth = new JSONObject();
            auth.put("deviceId", id);
            auth.put("deviceToken", token);

            JSONObject heartbeat = new JSONObject(auth.toString());
            heartbeat.put("action", "heartbeat");
            MainActivity.post(url, heartbeat);

            long iceFetchedAt = prefs.getLong("iceConfigFetchedAt", 0L);
            if (System.currentTimeMillis() - iceFetchedAt > 15L * 60L * 1000L) {
                JSONObject iceRequest = new JSONObject(auth.toString());
                iceRequest.put("action", "getIceConfig");
                iceRequest.put("role", "android");
                JSONObject iceConfig = MainActivity.post(url, iceRequest);
                prefs.edit()
                    .putString("iceServers", iceConfig.optJSONArray("iceServers") == null ? "" : iceConfig.optJSONArray("iceServers").toString())
                    .putLong("iceConfigFetchedAt", System.currentTimeMillis())
                    .apply();
            }

            JSONObject poll = new JSONObject(auth.toString());
            poll.put("action", "pollCommand");
            JSONObject response = MainActivity.post(url, poll);
            String command = response.optString("command", "");

            if ("stop_session".equals(command)) {
                stopService(new Intent(this, WebRtcSessionService.class));
                update("iPadから停止要求を受信しました。映像・音声を停止しました。", false);
            } else if ("start_session".equals(command)) {
                update("iPadから開始要求。通知をタップしてアプリを開き、カメラ・マイクを開始してください。", true);
            } else if ("request_status".equals(command)) {
                update("端末はオンラインです。カメラ・マイクの使用状態はAndroidの継続通知で確認してください。", false);
            }
        } catch (Exception error) {
            update("GASへの接続に失敗しました。自動で再試行します。", false);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "端末接続監視", NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text, boolean showStartAction) {
        Intent stopIntent = new Intent(this, StopReceiver.class);
        PendingIntent stopPending = PendingIntent.getBroadcast(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setAction(MainActivity.ACTION_REQUEST_MEDIA);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
            this, 51, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Remote Device Control")
            .setContentText(text)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                "アプリを開いて開始", openPending
            ).build())
            .addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_media_pause),
                "監視停止", stopPending
            ).build());
        if (showStartAction) builder.setContentIntent(openPending);
        return builder.build();
    }

    private void update(String text, boolean showStartAction) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text, showStartAction));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (scheduler != null) scheduler.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
