package jp.remote.devicecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    public static final String ACTION_REQUEST_MEDIA = "jp.remote.devicecontrol.REQUEST_MEDIA";
    private static final int PERMISSION_REQUEST = 21;

    private EditText gasUrl, pairCode, deviceName;
    private TextView status;
    private Button pairButton;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("rdc", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 36, 28, 28);

        TextView title = new TextView(this);
        title.setText("Remote Device Control");
        title.setTextSize(23);
        root.addView(title);

        gasUrl = field("GAS WebアプリURL");
        gasUrl.setText(prefs.getString("gasUrl", ""));
        root.addView(gasUrl);

        deviceName = field("端末名");
        deviceName.setText(prefs.getString("deviceName", Build.MODEL));
        root.addView(deviceName);

        pairCode = field("iPadに表示された6桁の登録コード");
        pairCode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(pairCode);

        pairButton = new Button(this);
        pairButton.setText("iPadに登録");
        root.addView(pairButton);

        Button monitor = new Button(this);
        monitor.setText("バックグラウンド接続監視を開始");
        root.addView(monitor);

        Button media = new Button(this);
        media.setText("📷 この端末のカメラ・マイクを開始");
        root.addView(media);

        Button stop = new Button(this);
        stop.setText("映像・音声セッションを停止");
        root.addView(stop);

        status = new TextView(this);
        status.setText(prefs.contains("deviceToken") ? "登録済み端末" : "未登録");
        root.addView(status);

        TextView note = new TextView(this);
        note.setText("カメラ・マイクは、この画面または開始通知をタップして開始します。開始後は、Androidの継続通知を表示したまま画面ロック後も接続を維持します。iPadからの要求だけで新しい撮影・録音を無人開始することはできません。");
        root.addView(note);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        pairButton.setOnClickListener(v -> pairDevice());
        monitor.setOnClickListener(v -> startMonitor());
        media.setOnClickListener(v -> requestMediaPermissions());
        stop.setOnClickListener(v -> stopMedia());

        if (ACTION_REQUEST_MEDIA.equals(getIntent().getAction())) {
            root.post(this::requestMediaPermissions);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_REQUEST_MEDIA.equals(intent.getAction())) {
            requestMediaPermissions();
        }
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    private void pairDevice() {
        String endpoint = gasUrl.getText().toString().trim();
        String code = pairCode.getText().toString().replaceAll("\\s+", "");
        String name = deviceName.getText().toString().trim();
        if (!endpoint.matches("^https://script\\.google\\.com/macros/s/[^\\s]+/exec$")) {
            status.setText("GAS URLは https://script.google.com/macros/s/.../exec の形式で入力してください。");
            return;
        }
        if (!code.matches("[0-9]{6}")) {
            status.setText("iPadに表示された6桁の数字を入力してください。");
            return;
        }
        if (name.isEmpty()) name = Build.MODEL;

        pairButton.setEnabled(false);
        prefs.edit().putString("gasUrl", endpoint).apply();
        status.setText("GASに登録中…");
        final String finalName = name;
        worker.execute(() -> {
            try {
                JSONObject request = new JSONObject();
                request.put("action", "claimPairCode");
                request.put("pairCode", code);
                request.put("name", finalName);
                JSONObject response = post(endpoint, request);
                prefs.edit()
                    .putString("deviceId", response.getString("deviceId"))
                    .putString("deviceToken", response.getString("deviceToken"))
                    .putString("deviceName", finalName)
                    .apply();
                runOnUiThread(() -> status.setText("登録完了！次回から登録コードは不要です。"));
            } catch (Exception ex) {
                runOnUiThread(() -> status.setText("登録失敗: " + ex.getMessage()));
            } finally {
                runOnUiThread(() -> pairButton.setEnabled(true));
            }
        });
    }

    private void startMonitor() {
        if (!prefs.contains("deviceToken")) {
            status.setText("先にiPadで発行したコードを使って端末登録してください。");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
            return;
        }
        Intent intent = new Intent(this, DeviceMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        status.setText("接続監視を開始しました。通知が表示されます。");
    }

    private void requestMediaPermissions() {
        if (!prefs.contains("deviceToken")) {
            status.setText("先に端末登録を完了してください。");
            return;
        }
        ArrayList<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.CAMERA);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.POST_NOTIFICATIONS);

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        startMediaService();
    }

    private void startMediaService() {
        try {
            Intent intent = new Intent(this, WebRtcSessionService.class);
            intent.setAction(WebRtcSessionService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
            else startService(intent);
            status.setText("カメラ・マイクを開始しました。通知が表示されます。画面ロック後も継続します。");
        } catch (Exception e) {
            status.setText("開始できません: " + e.getMessage());
        }
    }

    private void stopMedia() {
        stopService(new Intent(this, WebRtcSessionService.class));
        status.setText("映像・音声セッションを停止しました。");
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 10) {
            if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
                status.setText("通知を許可してください。");
                return;
            }
            startMonitor();
        } else if (requestCode == PERMISSION_REQUEST) {
            if (results.length == 0) {
                status.setText("権限の確認が完了しませんでした。もう一度お試しください。");
                return;
            }
            for (int result : results) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    status.setText("カメラ・マイク・通知の許可が必要です。");
                    return;
                }
            }
            startMediaService();
        }
    }

    static JSONObject post(String endpoint, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(20000);
        connection.setDoOutput(true);
        connection.setUseCaches(false);
        connection.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        try {
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new Exception("GAS HTTPエラー: " + code);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = stream) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject response = new JSONObject(bytes.toString("UTF-8"));
            if (code < 200 || code >= 400) throw new Exception("GAS HTTP " + code + ": " + response.optString("error", "通信エラー"));
            if (!response.optBoolean("ok")) throw new Exception(response.optString("error", "GAS APIエラー"));
            return response;
        } finally {
            connection.disconnect();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
    }
}
