(()=>{"use strict";
const $=id=>document.getElementById(id);
const urlInput=$("gasUrl"),keyInput=$("adminKey"),apiState=$("apiState"),devices=$("devices"),message=$("message"),video=$("remoteVideo");
const state={selected:null,pc:null,sessionId:null,pollTimer:null,statsTimer:null,connecting:false,remoteStream:null,seenTracks:new Set()};
const savedUrl=localStorage.getItem("rdc_gas_url")||window.RDC_CONFIG?.GAS_URL||"";
const savedKey=localStorage.getItem("rdc_admin_key")||"";
urlInput.value=savedUrl;keyInput.value=savedKey;
video.autoplay=true;video.playsInline=true;video.muted=false;

function config(){
 const url=urlInput.value.trim(),adminKey=keyInput.value.trim();
 if(!/^https:\/\/script\.google\.com\/macros\/s\/[^\s]+\/exec$/.test(url))throw Error("GAS WebアプリURLを正しく入力してください。");
 if(adminKey.length<24)throw Error("管理者キーは24文字以上にしてください。");
 return{url,adminKey};
}
async function api(action,data={}){
 const c=config(),randomBytes=new Uint8Array(16);crypto.getRandomValues(randomBytes);
 const requestId="rdc-"+Array.from(randomBytes,b=>b.toString(16).padStart(2,"0")).join("");
 const frameName=requestId.replace(/[^a-zA-Z0-9_-]/g,"");
 return new Promise((resolve,reject)=>{
  const frame=document.createElement("iframe"),form=document.createElement("form");
  frame.name=frameName;frame.title="GAS通信";frame.style.display="none";
  form.method="POST";form.action=c.url;form.target=frameName;form.style.display="none";
  const fields={rdcPayload:JSON.stringify({action,adminKey:c.adminKey,...data}),rdcRequestId:requestId};
  for(const [name,value] of Object.entries(fields)){const input=document.createElement("input");input.type="hidden";input.name=name;input.value=value;form.append(input);}
  let finished=false;
  const cleanup=()=>{window.removeEventListener("message",onMessage);clearTimeout(timer);form.remove();frame.remove();};
  const finish=(fn,value)=>{if(finished)return;finished=true;cleanup();fn(value);};
  const onMessage=event=>{
   const payload=event.data;
   if(!payload||payload.channel!=="rdc-api"||payload.requestId!==requestId)return;
   const result=payload.response;
   if(!result||typeof result!=="object")return finish(reject,new Error("GASから不正な応答が返りました。"));
   if(!result.ok)return finish(reject,new Error(result.error||"GAS処理に失敗しました。"));
   finish(resolve,result);
  };
  const timer=setTimeout(()=>finish(reject,new Error("GAS応答がタイムアウトしました。URL・公開設定・通信状態を確認してください。")),30000);
  window.addEventListener("message",onMessage);document.body.append(frame,form);form.submit();
 });
}
function msg(text,error=false){message.textContent=text;message.style.color=error?"#ff9aa8":"#8ee4d6";}
function setCommandStatus(text){$("commandStatus").textContent=text;}
function render(list){
 devices.replaceChildren();
 if(!list.length){const p=document.createElement("p");p.className="empty";p.textContent="登録済み端末はありません。";devices.append(p);return;}
 for(const device of list){
  const card=document.createElement("article"),info=document.createElement("div"),name=document.createElement("strong"),meta=document.createElement("small"),button=document.createElement("button");
  card.className="device";name.textContent=device.name||"Android";
  meta.textContent=(device.online?"🟢 オンライン":"⚫ オフライン")+" · 最終確認: "+(device.lastSeen||"不明");
  button.className="secondary";button.textContent="操作";
  button.addEventListener("click",()=>{state.selected=device;$("selectedName").textContent=device.name||"Android";$("controlPanel").hidden=false;setCommandStatus("Android側でカメラ・マイクを開始済みの場合、「映像・音声に接続」を押してください。");});
  info.append(name,meta);card.append(info,button);devices.append(card);
 }
}
async function refresh(){
 try{apiState.textContent="接続中";const response=await api("listDevices");render(response.devices||[]);apiState.textContent="GAS接続済み";}
 catch(error){apiState.textContent="未接続";msg(error.message,true);}
}
async function sendSignal(type,payload,sessionId=state.sessionId){
 if(!state.selected)throw Error("端末を選択してください。");
 return api("sendSignal",{fromRole:"web",deviceId:state.selected.deviceId,type,payload,sessionId});
}
async function pollSignals(){
 if(!state.pc||!state.selected||!state.sessionId)return;
 try{
  const response=await api("pollSignals",{role:"web",deviceId:state.selected.deviceId,sessionId:state.sessionId});
  for(const signal of response.signals||[]){
   if(signal.type==="ice"){
    const candidate=JSON.parse(signal.payload);
    await state.pc.addIceCandidate(new RTCIceCandidate(candidate));
   }else if(signal.type==="bye"){
    setCommandStatus("Android側でセッションが終了しました。");
    endView(false);
    return;
   }
  }
 }catch(error){setCommandStatus("シグナル受信を再試行中: "+error.message);}
}
function stopTimers(){
 if(state.pollTimer)clearInterval(state.pollTimer);
 if(state.statsTimer)clearInterval(state.statsTimer);
 state.pollTimer=null;state.statsTimer=null;
}
function endView(sendBye=true){
 const oldSession=state.sessionId;
 if(sendBye&&oldSession&&state.selected)sendSignal("bye","",oldSession).catch(()=>{});
 stopTimers();
 if(state.pc){state.pc.ontrack=null;state.pc.close();}
 state.pc=null;state.sessionId=null;state.remoteStream=null;state.seenTracks.clear();
 video.pause();video.srcObject=null;state.connecting=false;
}
function updateStats(pc){
 return pc.getStats().then(report=>{
  let videoBytes=0,audioBytes=0,frames=0;
  report.forEach(item=>{
   if(item.type==="inbound-rtp"&&!item.isRemote){
    if(item.kind==="video"||item.mediaType==="video"){videoBytes+=item.bytesReceived||0;frames+=item.framesDecoded||0;}
    if(item.kind==="audio"||item.mediaType==="audio")audioBytes+=item.bytesReceived||0;
   }
  });
  if(state.pc!==pc)return;
  const tracks=state.remoteStream?.getTracks()||[];
  const hasVideo=tracks.some(track=>track.kind==="video"&&track.readyState==="live");
  const hasAudio=tracks.some(track=>track.kind==="audio"&&track.readyState==="live");
  const parts=[];
  parts.push(hasVideo?"映像トラックあり":"映像トラックなし");
  parts.push(hasAudio?"音声トラックあり":"音声トラックなし");
  if(videoBytes>0)parts.push("映像データ受信中");
  if(audioBytes>0)parts.push("音声データ受信中");
  if(frames>0)parts.push("描画フレーム "+frames);
  if(pc.iceConnectionState==="connected"||pc.iceConnectionState==="completed")parts.push("通信接続済み");
  setCommandStatus(parts.join(" / "));
 }).catch(()=>{});
}
async function startView(){
 if(!state.selected)return;
 endView();
 state.connecting=true;
 setCommandStatus("Androidから接続情報を待っています。Android側でカメラ・マイクを開始してください。");
 try{
  const pc=new RTCPeerConnection({iceServers:[
   {urls:"stun:stun.l.google.com:19302"},
   {urls:"stun:stun1.l.google.com:19302"}
  ]});
  state.pc=pc;state.remoteStream=new MediaStream();
  let answerSent=false;const queuedIce=[];
  pc.ontrack=event=>{
   if(!state.remoteStream.getTracks().some(track=>track.id===event.track.id))state.remoteStream.addTrack(event.track);
   state.seenTracks.add(event.track.kind);
   video.srcObject=state.remoteStream;
   video.muted=false;video.playsInline=true;
   event.track.onended=()=>setCommandStatus(event.track.kind==="video"?"Androidの映像が終了しました。":"Androidの音声が終了しました。");
   video.play().then(()=>setCommandStatus("映像・音声トラックを受信しました。再生状態を確認中です。"))
    .catch(()=>setCommandStatus("トラックは届いています。動画の再生ボタンをタップして音声・映像を開始してください。"));
  };
  video.onplaying=()=>{if(state.pc===pc)setCommandStatus("映像を再生中です。音声が聞こえない場合はiPadの音量と動画のミュート状態を確認してください。");};
  pc.onicecandidate=event=>{
   if(!event.candidate||!state.sessionId)return;
   const candidate=JSON.stringify(event.candidate.toJSON());
   if(!answerSent){queuedIce.push(candidate);return;}
   sendSignal("ice",candidate).catch(error=>setCommandStatus("ICE送信失敗: "+error.message));
  };
  pc.onconnectionstatechange=()=>{
   if(state.pc!==pc)return;
   if(pc.connectionState==="failed")setCommandStatus("WebRTC接続失敗。ネットワークによってはTURN中継サーバーが必要です。");
   else if(pc.connectionState==="disconnected")setCommandStatus("通信が一時切断されました。再接続を試みています。");
   else if(pc.connectionState==="connected")setCommandStatus("WebRTC通信接続済み。映像・音声データを確認しています。");
  };
  pc.oniceconnectionstatechange=()=>{if(state.pc===pc&&pc.iceConnectionState==="failed")setCommandStatus("ICE接続失敗。STUNだけでは接続できないネットワークの可能性があります。");};
  const deadline=Date.now()+90000;
  while(Date.now()<deadline&&state.pc===pc&&!state.sessionId){
   const response=await api("pollSignals",{role:"web",deviceId:state.selected.deviceId});
   for(const signal of response.signals||[]){
    if(signal.type==="offer"&&!state.sessionId){
     state.sessionId=signal.sessionId;
     await pc.setRemoteDescription(new RTCSessionDescription({type:"offer",sdp:signal.payload}));
     const answer=await pc.createAnswer();
     await pc.setLocalDescription(answer);
     await sendSignal("answer",answer.sdp,state.sessionId);
     answerSent=true;
     for(const candidate of queuedIce)await sendSignal("ice",candidate,state.sessionId);
     queuedIce.length=0;
    }else if(signal.type==="ice"&&state.sessionId===signal.sessionId){
     await pc.addIceCandidate(new RTCIceCandidate(JSON.parse(signal.payload)));
    }else if(signal.type==="bye"){
     throw new Error("Android側でセッションが終了しました。");
    }
   }
   if(!state.sessionId)await new Promise(resolve=>setTimeout(resolve,1200));
  }
  if(!state.sessionId)throw new Error("90秒以内にAndroidから接続情報が届きませんでした。Androidでカメラ・マイクを開始してから再試行してください。");
  state.pollTimer=setInterval(pollSignals,1200);
  state.statsTimer=setInterval(()=>updateStats(pc),2500);
  state.connecting=false;
  setCommandStatus("接続情報を交換しました。映像・音声の到着を確認しています。");
 }catch(error){
  state.connecting=false;setCommandStatus(error.message);endView(false);
 }
}
$("saveUrl").addEventListener("click",async()=>{
 try{const c=config();localStorage.setItem("rdc_gas_url",c.url);localStorage.setItem("rdc_admin_key",c.adminKey);await api("ping");apiState.textContent="GAS接続済み";msg("GASへの接続を確認しました。");await refresh();}
 catch(error){apiState.textContent="エラー";msg(error.message,true);}
});
$("refresh").addEventListener("click",refresh);
$("createPair").addEventListener("click",async()=>{
 try{const result=await api("createPairCode");$("pairCodeText").textContent=result.pairCode;$("pairResult").hidden=false;msg("登録コードを発行しました。10分以内にAndroidで入力してください。");}
 catch(error){msg(error.message,true);}
});
$("closeControl").addEventListener("click",()=>{$("controlPanel").hidden=true;endView();});
$("viewBtn").addEventListener("click",startView);
$("endViewBtn").addEventListener("click",()=>endView());
document.querySelectorAll("[data-command]").forEach(button=>button.addEventListener("click",async()=>{
 if(!state.selected)return;
 try{
  await api("queueCommand",{deviceId:state.selected.deviceId,command:button.dataset.command});
  setCommandStatus(button.dataset.command==="start_session"?"Androidに開始通知を送りました。通知をタップして開始してください。":button.dataset.command==="stop_session"?"Androidに停止要求を送りました。":"状態確認を要求しました。");
 }catch(error){setCommandStatus(error.message);}
}));
if(savedUrl&&savedKey)refresh();
})();