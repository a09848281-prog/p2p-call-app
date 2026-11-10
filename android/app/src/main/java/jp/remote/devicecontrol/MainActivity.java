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
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.util.Scanner;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private EditText gasUrl,pairCode,deviceName;
    private TextView status;
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private static final int PERMISSION_REQUEST=21;
    private static final int SCREEN_PERMISSION_REQUEST=22;
    private static final int SCREEN_CAPTURE_REQUEST=23;
    public static final String ACTION_REQUEST_SCREEN="jp.remote.devicecontrol.REQUEST_SCREEN";
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("rdc",MODE_PRIVATE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(28,36,28,28);
        TextView title=new TextView(this);title.setText("Remote Device Control");title.setTextSize(23);root.addView(title);
        gasUrl=field("GAS WebアプリURL");gasUrl.setText(prefs.getString("gasUrl",""));root.addView(gasUrl);
        deviceName=field("端末名");deviceName.setText(Build.MODEL);root.addView(deviceName);
        pairCode=field("iPadに表示された登録コード");root.addView(pairCode);
        Button pair=new Button(this);pair.setText("iPadに登録");root.addView(pair);
        Button start=new Button(this);start.setText("バックグラウンド接続監視を開始");root.addView(start);
        Button media=new Button(this);media.setText("📷 この端末のカメラ・マイクを開始");root.addView(media);
        Button screen=new Button(this);screen.setText("🖥️ この端末の画面を共有（画面点灯中のみ）");root.addView(screen);
        Button stopMedia=new Button(this);stopMedia.setText("映像・音声セッションを停止");root.addView(stopMedia);
        status=new TextView(this);status.setText(prefs.contains("deviceToken")?"登録済み端末":"未登録");root.addView(status);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.setFillViewport(true);scroll.addView(root);setContentView(scroll);
        pair.setOnClickListener(v->pairDevice());start.setOnClickListener(v->startMonitor());media.setOnClickListener(v->requestMediaPermissions());screen.setOnClickListener(v->requestScreenCapture());stopMedia.setOnClickListener(v->stopMedia());
        if(ACTION_REQUEST_SCREEN.equals(getIntent().getAction()))root.post(this::requestScreenCapture);
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent!=null&&ACTION_REQUEST_SCREEN.equals(intent.getAction()))requestScreenCapture();}
    private EditText field(String hint){EditText e=new EditText(this);e.setHint(hint);e.setSingleLine(true);return e;}
    private void pairDevice(){String endpoint=gasUrl.getText().toString().trim(),code=pairCode.getText().toString().trim(),name=deviceName.getText().toString().trim();
        if(!endpoint.startsWith("https://script.google.com/macros/s/")||!endpoint.endsWith("/exec")||code.length()!=6){status.setText("GAS URLと6桁コードを確認してください");return;}
        prefs.edit().putString("gasUrl",endpoint).apply();status.setText("登録中…");worker.execute(()->{try{JSONObject p=new JSONObject();p.put("action","claimPairCode");p.put("pairCode",code);p.put("name",name);JSONObject r=post(endpoint,p);
            prefs.edit().putString("deviceId",r.getString("deviceId")).putString("deviceToken",r.getString("deviceToken")).putString("deviceName",name).apply();runOnUiThread(()->status.setText("登録完了！次回からコード不要です。"));
        }catch(Exception ex){runOnUiThread(()->status.setText("登録失敗: "+ex.getMessage()));}});
    }
    private void startMonitor(){if(!prefs.contains("deviceToken")){status.setText("先にiPadで発行したコードを使って登録してください");return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},10);return;}
        Intent i=new Intent(this,DeviceMonitorService.class);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);status.setText("監視サービスを開始しました。通知から停止できます。");
    }
    private void requestMediaPermissions(){if(!prefs.contains("deviceToken")){status.setText("先に端末登録を完了してください");return;}
        java.util.ArrayList<String> needed=new java.util.ArrayList<>();if(checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.CAMERA);if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),PERMISSION_REQUEST);return;}startMediaService();
    }
    private void startMediaService(){try{Intent i=new Intent(this,WebRtcSessionService.class);i.setAction(WebRtcSessionService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);status.setText("カメラ・マイクのセッションを開始しました。通知から停止できます。");}catch(Exception e){status.setText("開始できません: "+e.getMessage());}}
    private void requestScreenCapture(){
        if(!prefs.contains("deviceToken")){status.setText("先に端末登録を完了してください");return;}
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        android.app.KeyguardManager keyguard=(android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(power==null||!power.isInteractive()||(keyguard!=null&&keyguard.isKeyguardLocked())){
            status.setText("画面共有は画面点灯中・ロック解除中に開始できます。画面を点灯してロックを解除してください。");return;
        }
        java.util.ArrayList<String> needed=new java.util.ArrayList<>();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.RECORD_AUDIO);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if(!needed.isEmpty()){requestPermissions(needed.toArray(new String[0]),SCREEN_PERMISSION_REQUEST);return;}
        launchScreenCaptureConsent();
    }
    private void launchScreenCaptureConsent(){
        stopService(new Intent(this,WebRtcSessionService.class));
        android.media.projection.MediaProjectionManager manager=(android.media.projection.MediaProjectionManager)getSystemService(MEDIA_PROJECTION_SERVICE);
        if(manager==null){status.setText("画面共有機能を利用できません");return;}
        status.setText("Androidの画面共有確認を待っています…");
        startActivityForResult(manager.createScreenCaptureIntent(),SCREEN_CAPTURE_REQUEST);
    }
    private void startScreenService(int resultCode,Intent resultData){
        android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
        android.app.KeyguardManager keyguard=(android.app.KeyguardManager)getSystemService(KEYGUARD_SERVICE);
        if(power==null||!power.isInteractive()||(keyguard!=null&&keyguard.isKeyguardLocked())){
            status.setText("画面が消灯またはロックされたため開始しませんでした");return;
        }
        Intent i=new Intent(this,WebRtcSessionService.class);i.setAction(WebRtcSessionService.ACTION_START_SCREEN);
        i.putExtra(WebRtcSessionService.EXTRA_RESULT_CODE,resultCode);i.putExtra(WebRtcSessionService.EXTRA_RESULT_DATA,resultData);
        try{if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);status.setText("画面共有を開始しました。画面消灯・ロックで自動停止します。");}
        catch(Exception e){status.setText("画面共有を開始できません: "+e.getMessage());}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==SCREEN_CAPTURE_REQUEST){
            if(resultCode!=RESULT_OK||data==null){status.setText("画面共有はキャンセルされました");return;}
            startScreenService(resultCode,data);
        }
    }
    private void stopMedia(){stopService(new Intent(this,WebRtcSessionService.class));status.setText("映像・音声セッションを停止しました。");}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==10){if(results.length==0||results[0]!=PackageManager.PERMISSION_GRANTED){status.setText("通知を許可してください。");return;}startMonitor();}
        if(requestCode==PERMISSION_REQUEST){if(results.length==0){status.setText("権限の確認が完了しませんでした。もう一度お試しください。");return;}for(int result:results)if(result!=PackageManager.PERMISSION_GRANTED){status.setText("カメラ・マイク・通知の許可が必要です。");return;}startMediaService();}
        if(requestCode==SCREEN_PERMISSION_REQUEST){
            if(results.length==0){status.setText("権限の確認が完了しませんでした。もう一度お試しください。");return;}
            for(int result:results)if(result!=PackageManager.PERMISSION_GRANTED){status.setText("画面共有にはマイクと通知の許可が必要です。");return;}
            launchScreenCaptureConsent();
        }
    }
    static JSONObject post(String endpoint,JSONObject body)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();c.setRequestMethod("POST");c.setConnectTimeout(15000);c.setReadTimeout(15000);c.setDoOutput(true);c.setRequestProperty("Content-Type","text/plain; charset=utf-8");
        try(OutputStream o=c.getOutputStream()){o.write(body.toString().getBytes("UTF-8"));}
        try(Scanner s=new Scanner(c.getInputStream(),"UTF-8").useDelimiter("\\A")){JSONObject r=new JSONObject(s.hasNext()?s.next():"{}");if(!r.optBoolean("ok"))throw new Exception(r.optString("error","GAS API error"));return r;}finally{c.disconnect();}
    }
}