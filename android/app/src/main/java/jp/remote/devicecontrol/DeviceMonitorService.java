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
    private static final String CHANNEL="rdc_monitor";
    private ScheduledExecutorService scheduler;
    private android.os.PowerManager.WakeLock wakeLock;
    @Override public void onCreate(){super.onCreate();createChannel();startForeground(41,notification("端末接続を監視しています",false));android.os.PowerManager pm=(android.os.PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"RemoteDeviceControl:Monitor");wakeLock.setReferenceCounted(false);wakeLock.acquire(8L*60L*60L*1000L);scheduler=Executors.newSingleThreadScheduledExecutor();scheduler.scheduleWithFixedDelay(this::poll,0,25,TimeUnit.SECONDS);}
    private void poll(){try{android.content.SharedPreferences p=getSharedPreferences("rdc",MODE_PRIVATE);String url=p.getString("gasUrl",""),id=p.getString("deviceId",""),token=p.getString("deviceToken","");
        if(url.isEmpty()||id.isEmpty()||token.isEmpty())return;JSONObject auth=new JSONObject();auth.put("deviceId",id);auth.put("deviceToken",token);
        JSONObject hb=new JSONObject(auth.toString());hb.put("action","heartbeat");MainActivity.post(url,hb);
        JSONObject q=new JSONObject(auth.toString());q.put("action","pollCommand");JSONObject result=MainActivity.post(url,q);String cmd=result.optString("command","");
        if("stop_session".equals(cmd)||"stop_screen".equals(cmd)){stopService(new Intent(this,WebRtcSessionService.class));update("映像・音声・画面共有を停止しました",false);}
        else if("start_session".equals(cmd)){update("iPadから接続要求。通知をタップしてアプリを開き、カメラ・マイクを開始してください",false);}
        else if("start_screen".equals(cmd)){stopService(new Intent(this,WebRtcSessionService.class));update("iPadから画面共有要求。画面を点灯してロックを解除し、この通知をタップして画面共有を許可してください",true);}
        else if("request_status".equals(cmd)){update("端末はオンラインです",false);}
    }catch(Exception e){update("接続確認に失敗しました。再試行します");}}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"端末接続監視",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification notification(String text,boolean requestScreen){
        Intent stop=new Intent(this,StopReceiver.class);
        PendingIntent pi=PendingIntent.getBroadcast(this,0,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent open=new Intent(this,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(requestScreen)open.setAction(MainActivity.ACTION_REQUEST_SCREEN);
        PendingIntent openPi=PendingIntent.getActivity(this,requestScreen?2:1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(openPi).setContentTitle("Remote Device Control").setContentText(text).addAction(new Notification.Action.Builder(Icon.createWithResource(this,android.R.drawable.ic_media_pause),"監視停止",pi).build()).setOngoing(true).build();
    }
    private void update(String text){update(text,false);}
    private void update(String text,boolean requestScreen){getSystemService(NotificationManager.class).notify(41,notification(text,requestScreen));}
    @Override public int onStartCommand(Intent i,int flags,int startId){return START_STICKY;}
    @Override public void onDestroy(){if(scheduler!=null)scheduler.shutdownNow();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}