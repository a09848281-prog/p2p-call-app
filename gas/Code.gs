const RDC_SHEET = "Devices";
const RDC_SIGNAL_SHEET = "Signals";
function doGet(){return json_({ok:true,service:"Remote Device Control GAS",version:3});}
function doPost(e){
  const formMode=!!(e&&e.parameter&&e.parameter.rdcPayload);
  const requestId=formMode?String(e.parameter.rdcRequestId||""):"";
  try{
    const body=formMode?e.parameter.rdcPayload:(e?.postData?.contents||"{}");
    const p=JSON.parse(body);
    const result=route_(p);
    return formMode?iframeResponse_(result,requestId):json_(result);
  }catch(err){
    const result={ok:false,error:String(err.message||err)};
    return formMode?iframeResponse_(result,requestId):json_(result);
  }
}
function iframeResponse_(result,requestId){
  const message=JSON.stringify({channel:"rdc-api",requestId:String(requestId||""),response:result}).replace(/</g,"\\u003c");
  const html='<!doctype html><meta charset="utf-8"><script>window.parent.postMessage('+message+',"*");</script>';
  return HtmlService.createHtmlOutput(html).setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}
function setup(){const ss=SpreadsheetApp.getActiveSpreadsheet();if(!ss)throw new Error("このスクリプトをGoogle Sheetsから開いてください。");let sh=ss.getSheetByName(RDC_SHEET);if(!sh)sh=ss.insertSheet(RDC_SHEET);if(sh.getLastRow()===0)sh.appendRow(["deviceId","name","tokenHash","pairCode","paired","lastSeen","status","pendingCommand","pairExpiresAt"]);if(sh.getLastColumn()<9)sh.getRange(1,9).setValue("pairExpiresAt");let sig=ss.getSheetByName(RDC_SIGNAL_SHEET);if(!sig)sig=ss.insertSheet(RDC_SIGNAL_SHEET);if(sig.getLastRow()===0)sig.appendRow(["signalId","deviceId","sessionId","fromRole","toRole","type","payload","createdAt","consumed"]);return "セットアップ完了。ADMIN_KEYをスクリプトプロパティに設定してください。";}
function route_(p){const sh=sheet_();switch(p.action){case "ping":requireAdmin_(p);return {ok:true};case "createPairCode":requireAdmin_(p);return createPairCode_(sh);case "claimPairCode":return claimPairCode_(sh,p);case "listDevices":requireAdmin_(p);return list_(sh);case "heartbeat":return heartbeat_(sh,p);case "pollCommand":return poll_(sh,p);case "queueCommand":requireAdmin_(p);return queue_(sh,p);case "sendSignal":return sendSignal_(p);case "pollSignals":return pollSignals_(p);default:throw new Error("未対応の操作です。");}}
function requireAdmin_(p){const key=PropertiesService.getScriptProperties().getProperty("ADMIN_KEY");if(!key||!constantTimeEqual_(String(p.adminKey||""),key))throw new Error("管理者キーが違います。");}
function createPairCode_(sh){
  const lock=LockService.getScriptLock();
  lock.waitLock(10000);
  try{
    const rows=sh.getDataRange().getValues();
    let code="";
    do{
      const randomHex=Utilities.getUuid().replace(/-/g,"").substring(0,12);
      code=String(100000+(parseInt(randomHex,16)%900000));
    }while(rows.some((row,index)=>index>0&&String(row[3])===code&&!row[4]&&row[8]&&new Date(row[8]).getTime()>Date.now()));
    const id=Utilities.getUuid();
    sh.appendRow([id,"Android","",""+code,false,"","pairing","",new Date(Date.now()+10*60*1000)]);
    return {ok:true,pairCode:code,expiresInMinutes:10};
  }finally{lock.releaseLock();}
}
function claimPairCode_(sh,p){
  const lock=LockService.getScriptLock();
  lock.waitLock(10000);
  try{
    const props=PropertiesService.getScriptProperties();
    const now=Date.now();
    let windowStart=Number(props.getProperty("RDC_PAIR_WINDOW_START")||0);
    let attempts=Number(props.getProperty("RDC_PAIR_ATTEMPT_COUNT")||0);
    if(!windowStart||now-windowStart>=5*60*1000){windowStart=now;attempts=0;}
    if(attempts>=20)throw new Error("登録コードの試行回数が上限に達しました。5分ほど待ってから再試行してください。");
    props.setProperties({RDC_PAIR_WINDOW_START:String(windowStart),RDC_PAIR_ATTEMPT_COUNT:String(attempts+1)});
    const code=String(p.pairCode||"");
    if(!/^\d{6}$/.test(code))throw new Error("登録コードは6桁で入力してください。")
    const v=sh.getDataRange().getValues();
    for(let i=1;i<v.length;i++){
      if(String(v[i][3])===code&&!v[i][4]&&v[i][6]==="pairing"&&v[i][8]&&new Date(v[i][8]).getTime()>Date.now()){
        const token=Utilities.getUuid()+Utilities.getUuid();
        const name=String(p.name||"Android").slice(0,40);
        sh.getRange(i+1,2).setValue(name);sh.getRange(i+1,3).setValue(sha_(token));
        sh.getRange(i+1,4).clearContent();sh.getRange(i+1,5).setValue(true);
        sh.getRange(i+1,9).clearContent();sh.getRange(i+1,6).setValue(new Date());sh.getRange(i+1,7).setValue("online");
        return {ok:true,deviceId:String(v[i][0]),deviceToken:token,name:name};
      }
    }
    throw new Error("登録コードが無効か、使用済みです。");
  }finally{lock.releaseLock();}
}
function list_(sh){const v=sh.getDataRange().getValues(),now=Date.now(),out=[];for(let i=1;i<v.length;i++){if(!v[i][4])continue;const seen=v[i][5]?new Date(v[i][5]).getTime():0;out.push({deviceId:String(v[i][0]),name:String(v[i][1]),online:!!seen&&now-seen<90000,lastSeen:v[i][5]?new Date(v[i][5]).toLocaleString("ja-JP"):"未接続"});}return {ok:true,devices:out};}
function heartbeat_(sh,p){const row=authRow_(sh,p);sh.getRange(row,6).setValue(new Date());sh.getRange(row,7).setValue("online");return {ok:true};}
function poll_(sh,p){
  const row=authRow_(sh,p),lock=LockService.getScriptLock();
  lock.waitLock(10000);
  try{const cmd=sh.getRange(row,8).getValue();if(cmd)sh.getRange(row,8).clearContent();return {ok:true,command:cmd||null};}
  finally{lock.releaseLock();}
}
function queue_(sh,p){
  const id=String(p.deviceId||""),command=String(p.command||"");
  const allowed=["start_session","stop_session","start_screen","stop_screen","request_status"];
  if(!allowed.includes(command))throw new Error("許可されていない操作です。");
  const lock=LockService.getScriptLock();
  lock.waitLock(10000);
  try{
    const v=sh.getDataRange().getValues();
    for(let i=1;i<v.length;i++)if(String(v[i][0])===id&&v[i][4]){
      sh.getRange(i+1,8).setValue(command);
      return {ok:true};
    }
    throw new Error("端末が見つかりません。");
  }finally{lock.releaseLock();}
}
function sendSignal_(p){const role=String(p.fromRole||"");if(role==="android")authRow_(sheet_(),p);else if(role==="web")requireAdmin_(p);else throw new Error("送信元が不正です。");const to=role==="android"?"web":"android";const type=String(p.type||"");if(!["offer","answer","ice","bye"].includes(type))throw new Error("シグナル種別が不正です。");const payload=String(p.payload||"");if(payload.length>30000)throw new Error("シグナルが大きすぎます。");const sig=signalSheet_(),lock=LockService.getScriptLock();lock.waitLock(10000);try{if(type==="offer"){const rows=sig.getDataRange().getValues();for(let i=1;i<rows.length;i++)if(String(rows[i][1])===String(p.deviceId)&&!rows[i][8])sig.getRange(i+1,9).setValue(true);}sig.appendRow([Utilities.getUuid(),String(p.deviceId||""),String(p.sessionId||""),role,to,type,payload,new Date(),false]);return {ok:true};}finally{lock.releaseLock();}}
function pollSignals_(p){const role=String(p.role||"");if(role==="android")authRow_(sheet_(),p);else if(role==="web")requireAdmin_(p);else throw new Error("受信側が不正です。");const sig=signalSheet_(),lock=LockService.getScriptLock();lock.waitLock(10000);try{const v=sig.getDataRange().getValues(),out=[];for(let i=1;i<v.length;i++){if(String(v[i][1])===String(p.deviceId)&&String(v[i][4])===role&&!v[i][8]&&(!p.sessionId||String(v[i][2])===String(p.sessionId))){out.push({sessionId:String(v[i][2]),type:String(v[i][5]),payload:String(v[i][6])});sig.getRange(i+1,9).setValue(true);if(out.length>=30)break;}}return {ok:true,signals:out};}finally{lock.releaseLock();}}
function authRow_(sh,p){const id=String(p.deviceId||""),hash=sha_(String(p.deviceToken||""));const v=sh.getDataRange().getValues();for(let i=1;i<v.length;i++)if(String(v[i][0])===id&&v[i][4]&&String(v[i][2])===hash)return i+1;throw new Error("端末認証に失敗しました。");}
function sheet_(){const ss=SpreadsheetApp.getActiveSpreadsheet();if(!ss)throw new Error("スプレッドシートに紐付けてください。");const sh=ss.getSheetByName(RDC_SHEET);if(!sh)throw new Error("先にsetup()を実行してください。");return sh;}
function signalSheet_(){const ss=SpreadsheetApp.getActiveSpreadsheet();let sh=ss.getSheetByName(RDC_SIGNAL_SHEET);if(!sh){sh=ss.insertSheet(RDC_SIGNAL_SHEET);sh.appendRow(["signalId","deviceId","sessionId","fromRole","toRole","type","payload","createdAt","consumed"]);}return sh;}
function sha_(s){return Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256,s).map(b=>(b<0?b+256:b).toString(16).padStart(2,"0")).join("");}
function constantTimeEqual_(a,b){if(a.length!==b.length)return false;let n=0;for(let i=0;i<a.length;i++)n|=a.charCodeAt(i)^b.charCodeAt(i);return n===0;}
function json_(v){return ContentService.createTextOutput(JSON.stringify(v)).setMimeType(ContentService.MimeType.JSON);}