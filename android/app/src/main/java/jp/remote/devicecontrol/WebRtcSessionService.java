package jp.remote.devicecontrol;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.projection.MediaProjection;
import android.os.*;
import org.json.JSONObject;
import org.webrtc.*;
import java.util.*;
import java.util.concurrent.*;

public class WebRtcSessionService extends Service {
    public static final String ACTION_START="jp.remote.devicecontrol.START_MEDIA";
    public static final String ACTION_START_SCREEN="jp.remote.devicecontrol.START_SCREEN";
    public static final String ACTION_STOP="jp.remote.devicecontrol.STOP_MEDIA";
    public static final String EXTRA_RESULT_CODE="projectionResultCode";
    public static final String EXTRA_RESULT_DATA="projectionResultData";
    private static final String CHANNEL="rdc_media";
    private final ScheduledExecutorService io=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final List<IceCandidate> pendingIce=Collections.synchronizedList(new ArrayList<>());
    private final List<IceCandidate> pendingRemoteIce=Collections.synchronizedList(new ArrayList<>());
    private volatile boolean offerQueued=false,remoteDescriptionSet=false,active=false,screenMode=false;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private BroadcastReceiver screenStateReceiver;
    private Runnable screenGuard;
    private EglBase egl;
    private PeerConnectionFactory factory;
    private PeerConnection peer;
    private VideoCapturer capturer;
    private SurfaceTextureHelper texture;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private PowerManager.WakeLock wakeLock;
    private String sessionId;

    @Override public void onCreate(){super.onCreate();createChannel();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&ACTION_STOP.equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(!active&&ACTION_START.equals(intent.getAction()))startMedia(false,Activity.RESULT_CANCELED,null);
        else if(!active&&ACTION_START_SCREEN.equals(intent.getAction())){
            int resultCode=intent.getIntExtra(EXTRA_RESULT_CODE,Activity.RESULT_CANCELED);
            Intent resultData=(Intent)intent.getParcelableExtra(EXTRA_RESULT_DATA);
            startMedia(true,resultCode,resultData);
        }else if(!ACTION_START.equals(intent.getAction())&&!ACTION_START_SCREEN.equals(intent.getAction()))stopSelf();
        return START_NOT_STICKY;
    }
    private void startMedia(boolean useScreen,int projectionResultCode,Intent projectionData){
        try{
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("マイクの権限が必要です");
            if(!useScreen&&checkSelfPermission(android.Manifest.permission.CAMERA)!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw new SecurityException("カメラの権限が必要です");
            if(useScreen&&(projectionResultCode!=Activity.RESULT_OK||projectionData==null))
                throw new SecurityException("画面共有の許可が確認できません");
            if(useScreen&&(!pm.isInteractive()||((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()))
                throw new SecurityException("画面共有は画面点灯中・ロック解除中のみ開始できます");
            int foregroundTypes=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            String foregroundText;
            if(useScreen){
                foregroundTypes|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
                foregroundText="画面を共有中です（画面消灯・ロックで自動停止）";
            }else{
                foregroundTypes|=ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
                foregroundText="カメラ・マイクを使用中です";
            }
            if(Build.VERSION.SDK_INT>=29)startForeground(42,notification(foregroundText),foregroundTypes);
            else startForeground(42,notification(foregroundText));
            active=true;
            screenMode=useScreen;
            wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RemoteDeviceControl:MediaSession");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(8L*60L*60L*1000L);
            SharedPreferences prefs=getSharedPreferences("rdc",MODE_PRIVATE);
            sessionId=UUID.randomUUID().toString();
            egl=EglBase.create();
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions());
            factory=PeerConnectionFactory.builder().setVideoEncoderFactory(new DefaultVideoEncoderFactory(egl.getEglBaseContext(),true,true)).setVideoDecoderFactory(new DefaultVideoDecoderFactory(egl.getEglBaseContext())).createPeerConnectionFactory();
            List<PeerConnection.IceServer> ice=Collections.singletonList(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
            peer=factory.createPeerConnection(new PeerConnection.RTCConfiguration(ice),new PeerConnection.Observer(){
                public void onSignalingChange(PeerConnection.SignalingState s){}
                public void onIceConnectionChange(PeerConnection.IceConnectionState s){notifyStatus("WebRTC: "+s.name());}
                public void onIceConnectionReceivingChange(boolean b){}
                public void onIceGatheringChange(PeerConnection.IceGatheringState s){}
                public void onIceCandidate(IceCandidate c){if(!offerQueued){pendingIce.add(c);return;}sendIce(c);}
                public void onIceCandidatesRemoved(IceCandidate[] c){}
                public void onAddStream(MediaStream s){}
                public void onRemoveStream(MediaStream s){}
                public void onDataChannel(DataChannel c){}
                public void onRenegotiationNeeded(){}
                public void onAddTrack(RtpReceiver r,MediaStream[] s){}
            });
            if(peer==null)throw new Exception("PeerConnectionを作成できません");
            audioSource=factory.createAudioSource(new MediaConstraints());
            AudioTrack audio=factory.createAudioTrack("rdc_audio",audioSource);audio.setEnabled(true);
            peer.addTrack(audio,Collections.singletonList("rdc_stream"));
            if(useScreen){
                capturer=new ScreenCapturerAndroid(projectionData,new MediaProjection.Callback(){
                    @Override public void onStop(){notifyStatus("画面共有が終了しました");sendSignal("bye","");stopSelf();}
                });
            }else{
                capturer=createCameraCapturer();
                if(capturer==null)throw new Exception("利用可能なカメラがありません");
            }
            texture=SurfaceTextureHelper.create(useScreen?"RDC-Screen":"RDC-Camera",egl.getEglBaseContext());
            videoSource=factory.createVideoSource(useScreen);
            capturer.initialize(texture,getApplicationContext(),videoSource.getCapturerObserver());
            if(useScreen){
                android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
                capturer.startCapture(Math.max(2,metrics.widthPixels),Math.max(2,metrics.heightPixels),15);
                registerScreenGuards();
            }else{
                capturer.startCapture(640,480,20);
            }
            VideoTrack video=factory.createVideoTrack("rdc_video",videoSource);video.setEnabled(true);
            peer.addTrack(video,Collections.singletonList("rdc_stream"));
            peer.createOffer(new SdpObserver(){
                public void onCreateSuccess(SessionDescription desc){
                    peer.setLocalDescription(new SdpObserver(){
                        public void onCreateSuccess(SessionDescription d){}
                        public void onSetSuccess(){
                            sendSignal("offer",desc.description);offerQueued=true;
                            synchronized(pendingIce){for(IceCandidate c:pendingIce)sendIce(c);pendingIce.clear();}
                        }
                        public void onCreateFailure(String e){notifyStatus("Offer作成失敗: "+e);stopSelf();}
                        public void onSetFailure(String e){notifyStatus("SDP設定失敗: "+e);stopSelf();}
                    },desc);
                }
                public void onSetSuccess(){}
                public void onCreateFailure(String e){notifyStatus("Offer作成失敗: "+e);stopSelf();}
                public void onSetFailure(String e){}
            },new MediaConstraints());
            io.scheduleWithFixedDelay(this::pollSignals,1,2,TimeUnit.SECONDS);
            notifyStatus("映像・音声セッションを開始しました。画面消灯中も接続維持を試みます");
        }catch(Exception e){notifyStatus("開始失敗: "+e.getMessage());stopSelf();}
    }
    private void registerScreenGuards(){
        screenStateReceiver=new BroadcastReceiver(){
            @Override public void onReceive(Context context,Intent intent){
                if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))stopScreenShare("画面が消灯したため画面共有を停止しました");
            }
        };
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenStateReceiver,filter,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenStateReceiver,filter);
        screenGuard=new Runnable(){
            @Override public void run(){
                if(!screenMode||!active)return;
                PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
                KeyguardManager keyguard=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);
                if(power==null||!power.isInteractive()||(keyguard!=null&&keyguard.isKeyguardLocked())){
                    stopScreenShare("画面消灯またはロックを検知したため画面共有を停止しました");
                    return;
                }
                mainHandler.postDelayed(this,1000);
            }
        };
        mainHandler.postDelayed(screenGuard,1000);
    }
    private void stopScreenShare(String reason){
        if(!screenMode||!active)return;
        notifyStatus(reason);
        sendSignal("bye","");
        mainHandler.postDelayed(this::stopSelf,1200);
    }
    private VideoCapturer createCameraCapturer(){
        Camera2Enumerator en=new Camera2Enumerator(this);
        for(String name:en.getDeviceNames())if(en.isFrontFacing(name)){VideoCapturer c=en.createCapturer(name,null);if(c!=null)return c;}
        for(String name:en.getDeviceNames())if(en.isBackFacing(name)){VideoCapturer c=en.createCapturer(name,null);if(c!=null)return c;}
        return null;
    }
    private void sendIce(IceCandidate c){try{JSONObject j=new JSONObject();j.put("sdpMid",c.sdpMid);j.put("sdpMLineIndex",c.sdpMLineIndex);j.put("candidate",c.sdp);sendSignal("ice",j.toString());}catch(Exception e){notifyStatus("ICE候補の作成に失敗しました");}}
    private void sendSignal(String type,String payload){network.execute(()->{try{SharedPreferences p=getSharedPreferences("rdc",MODE_PRIVATE);JSONObject j=auth(p);j.put("action","sendSignal");j.put("fromRole","android");j.put("type",type);j.put("sessionId",sessionId);j.put("payload",payload);MainActivity.post(p.getString("gasUrl",""),j);}catch(Exception e){notifyStatus("シグナル送信失敗: "+e.getMessage());}});}
    private void pollSignals(){try{SharedPreferences p=getSharedPreferences("rdc",MODE_PRIVATE);JSONObject j=auth(p);j.put("action","pollSignals");j.put("role","android");j.put("sessionId",sessionId);JSONObject r=MainActivity.post(p.getString("gasUrl",""),j);org.json.JSONArray a=r.optJSONArray("signals");if(a==null)return;
        for(int i=0;i<a.length();i++){JSONObject s=a.getJSONObject(i);String type=s.optString("type"),payload=s.optString("payload");
            if("answer".equals(type)&&peer!=null){peer.setRemoteDescription(new SdpObserver(){public void onSetSuccess(){remoteDescriptionSet=true;synchronized(pendingRemoteIce){for(IceCandidate candidate:pendingRemoteIce)peer.addIceCandidate(candidate);pendingRemoteIce.clear();}notifyStatus("iPadと接続しました");}public void onSetFailure(String e){notifyStatus("Answer設定失敗: "+e);}public void onCreateSuccess(SessionDescription d){}public void onCreateFailure(String e){}},new SessionDescription(SessionDescription.Type.ANSWER,payload));}
            else if("ice".equals(type)&&peer!=null){JSONObject c=new JSONObject(payload);IceCandidate candidate=new IceCandidate(c.optString("sdpMid"),c.optInt("sdpMLineIndex"),c.optString("candidate"));if(remoteDescriptionSet)peer.addIceCandidate(candidate);else pendingRemoteIce.add(candidate);}
            else if("bye".equals(type)){stopSelf();return;}
        }
    }catch(Exception e){notifyStatus("接続処理を再試行中");}}
    private JSONObject auth(SharedPreferences p)throws Exception{JSONObject j=new JSONObject();j.put("deviceId",p.getString("deviceId",""));j.put("deviceToken",p.getString("deviceToken",""));return j;}
    private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"映像・音声セッション",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(c);}}
    private Notification notification(String text){
        Intent stop=new Intent(this,WebRtcSessionService.class);stop.setAction(ACTION_STOP);
        PendingIntent pi=PendingIntent.getService(this,42,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Remote Device Control").setContentText(text)
            .addAction(new Notification.Action.Builder(Icon.createWithResource(this,android.R.drawable.ic_media_pause),"セッション停止",pi).build()).setOngoing(true).build();
    }
    private void notifyStatus(String text){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).notify(42,notification(text));}
    @Override public void onDestroy(){active=false;
        if(screenGuard!=null)mainHandler.removeCallbacks(screenGuard);
        if(screenStateReceiver!=null){try{unregisterReceiver(screenStateReceiver);}catch(Exception ignored){}screenStateReceiver=null;}
        io.shutdownNow();network.shutdownNow();
        if(capturer!=null){try{capturer.stopCapture();}catch(Exception ignored){}try{capturer.dispose();}catch(Exception ignored){}}
        if(peer!=null)try{peer.dispose();}catch(Exception ignored){}
        if(videoSource!=null)try{videoSource.dispose();}catch(Exception ignored){}
        if(audioSource!=null)try{audioSource.dispose();}catch(Exception ignored){}
        if(texture!=null)try{texture.dispose();}catch(Exception ignored){}
        if(factory!=null)try{factory.dispose();}catch(Exception ignored){}
        if(egl!=null)try{egl.release();}catch(Exception ignored){}
        if(wakeLock!=null&&wakeLock.isHeld())try{wakeLock.release();}catch(Exception ignored){}
        stopForeground(true);super.onDestroy();
    }
    @Override public IBinder onBind(Intent i){return null;}
}