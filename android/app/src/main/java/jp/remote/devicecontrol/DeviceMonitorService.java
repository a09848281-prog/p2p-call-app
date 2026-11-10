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
    @Override public void onCreate(){super.onCreate();createChannel();startForeground(41,notification("端末接続を監視しています"));scheduler=Executors.newSingleThreadScheduledExecutor();scheduler.scheduleWithFixedDelay(this::poll,0,25,TimeUnit.SECONDS);}
    private void poll(){try{android.content.SharedPreferences p=getSharedPreferences("rdc",MODE_PRIVATE);String url=p.getString("gasUrl",""),id=p.getString("deviceId",""),token=p.getString("deviceToken","");
        if(url.isEmpty()||id.isEmpty()||token.isEmpty())return;JSONObject auth=new JSONObject();auth.put("deviceId",id);auth.put("deviceToken",token);
        JSONObject hb=new JSONObject(auth.toString());hb.put("action","heartbeat");MainActivity.post(url,hb);
        JSONObject q=new JSONObject(auth.toString());q.put("action","pollCommand");JSONObject result=MainActivity.post(url,q);String cmd=result.optString("command","");
        if("stop_session".equals(cmd)){stopSelf();}else if("start_session".equals(cmd)){update("iPadから接続要求。通知をタップしてアプリを開き、カメラ・マイクを開始してください");}else if("request_status".equals(cmd)){update("端末はオンラインです");}
    }catch(Exception e){update("接続確認に失敗しました。再試行します");}}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"端末接続監視",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification notification(String text){Intent stop=new Intent(this,StopReceiver.class);PendingIntent pi=PendingIntent.getBroadcast(this,0,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);Intent open=new Intent(this,MainActivity.class);open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);PendingIntent openPi=PendingIntent.getActivity(this,1,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(openPi).setContentTitle("Remote Device Control").setContentText(text).addAction(new Notification.Action.Builder(Icon.createWithResource(this,android.R.drawable.ic_media_pause),"停止",pi).build()).setOngoing(true).build();}
    private void update(String text){getSystemService(NotificationManager.class).notify(41,notification(text));}
    @Override public int onStartCommand(Intent i,int flags,int startId){return START_STICKY;}
    @Override public void onDestroy(){if(scheduler!=null)scheduler.shutdownNow();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}