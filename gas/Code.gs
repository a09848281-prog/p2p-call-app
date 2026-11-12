const PREFIX='WRTC_ROOM_';
const ROOM_TTL_MS=10*60*1000;
const MAX_MESSAGES=120;
function doPost(e){
  const formMode=!!(e&&e.parameter&&e.parameter.rdcPayload);
  const requestId=formMode?String(e.parameter.rdcRequestId||''):'';
  try{
    const raw=formMode?e.parameter.rdcPayload:(e&&e.postData&&e.postData.contents||'{}');
    const result=route_(JSON.parse(raw));
    return formMode?iframeResponse_(result,requestId):json_({ok:true,...result});
  }catch(err){
    const result={ok:false,error:String(err&&err.message||err)};
    return formMode?iframeResponse_(result,requestId):json_(result);
  }
}
function doGet(){return json_({ok:true,service:'WebRTC signaling prototype',version:'0.1'});}
function iframeResponse_(result,requestId){
  const message=JSON.stringify({channel:'rdc-api',requestId:String(requestId||''),response:result}).replace(/</g,'\\u003c');
  const html='<!doctype html><meta charset="utf-8"><script>(function(){var response='+message+';try{window.top.postMessage(response,"*");}catch(e){window.parent.postMessage(response,"*");}})();</script>';
  return HtmlService.createHtmlOutput(html).setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL);
}
function route_(b){
  switch(String(b.action||'')){
    case 'createRoom':return createRoom_(b);
    case 'joinRoom':return joinRoom_(b);
    case 'sendSignal':return sendSignal_(b);
    case 'poll':return poll_(b);
    default:throw new Error('未対応の操作です');
  }
}
function createRoom_(b){
  const room=validRoom_(b.room),host=String(b.hostToken||''),offer=b.offer;
  if(!/^[a-f0-9]{48}$/.test(host))throw new Error('ホスト認証情報が不正です');
  if(!offer||offer.type!=='offer'||!offer.sdp||offer.sdp.length>50000)throw new Error('Offerが不正です');
  const lock=LockService.getScriptLock();lock.waitLock(5000);
  try{
    const p=PropertiesService.getScriptProperties(),key=PREFIX+room;
    if(p.getProperty(key))throw new Error('このコードは使用中です。もう一度開始してください');
    p.setProperty(key,JSON.stringify({room:room,host:host,guest:'',offer:offer,created:Date.now(),expires:Date.now()+ROOM_TTL_MS,messages:[]}));
  }finally{lock.releaseLock()}
  return {room:room,expiresIn:ROOM_TTL_MS};
}
function joinRoom_(b){
  const room=validRoom_(b.room),lock=LockService.getScriptLock();lock.waitLock(5000);
  try{
    const p=PropertiesService.getScriptProperties(),key=PREFIX+room,s=readRoom_(p,key);
    if(s.guest)throw new Error('この接続コードはすでに使用されています');
    const guest=token_();s.guest=guest;p.setProperty(key,JSON.stringify(s));
    return {guestToken:guest,offer:s.offer,expiresIn:Math.max(0,s.expires-Date.now())};
  }finally{lock.releaseLock()}
}
function sendSignal_(b){
  const room=validRoom_(b.room),token=String(b.token||''),signal=b.signal;
  if(!signal||JSON.stringify(signal).length>55000)throw new Error('通信データが不正です');
  const lock=LockService.getScriptLock();lock.waitLock(5000);
  try{
    const p=PropertiesService.getScriptProperties(),key=PREFIX+room,s=readRoom_(p,key);
    const from=token===s.host?'host':(s.guest&&token===s.guest?'guest':'');
    if(!from)throw new Error('認証に失敗しました');
    if(['answer','ice','close'].indexOf(signal.type)<0)throw new Error('許可されていない信号です');
    if(signal.type==='answer'&&(from!=='guest'||!signal.description||signal.description.type!=='answer'))throw new Error('Answerが不正です');
    if(signal.type==='ice'&&(!signal.candidate||typeof signal.candidate.candidate!=='string'))throw new Error('ICE候補が不正です');
    s.messages=s.messages||[];if(s.messages.length>=MAX_MESSAGES)s.messages=s.messages.slice(-MAX_MESSAGES+1);
    s.messages.push({id:Utilities.getUuid(),to:from==='host'?'guest':'host',signal:signal});
    p.setProperty(key,JSON.stringify(s));return {sent:true};
  }finally{lock.releaseLock()}
}
function poll_(b){
  const room=validRoom_(b.room),token=String(b.token||''),p=PropertiesService.getScriptProperties(),key=PREFIX+room,s=readRoom_(p,key);
  const who=token===s.host?'host':(s.guest&&token===s.guest?'guest':'');
  if(!who)throw new Error('認証に失敗しました');
  const messages=(s.messages||[]).filter(m=>m.to===who);
  s.messages=(s.messages||[]).filter(m=>m.to!==who);
  p.setProperty(key,JSON.stringify(s));return {messages:messages};
}
function readRoom_(p,key){
  const raw=p.getProperty(key);if(!raw)throw new Error('接続コードが無効か期限切れです');
  const s=JSON.parse(raw);if(Date.now()>s.expires){p.deleteProperty(key);throw new Error('接続コードの有効期限が切れました')}
  return s;
}
function validRoom_(v){const s=String(v||'');if(!/^\\d{6}$/.test(s))throw new Error('接続コードは6桁で入力してください');return s}
function token_(){return Utilities.getUuid().replace(/-/g,'')+Utilities.getUuid().replace(/-/g,'').slice(0,16)}
function json_(o){return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON)}
function cleanupExpiredRooms(){
  const p=PropertiesService.getScriptProperties(),all=p.getProperties(),now=Date.now();
  Object.keys(all).filter(k=>k.indexOf(PREFIX)===0).forEach(k=>{try{if(JSON.parse(all[k]).expires<now)p.deleteProperty(k)}catch(e){p.deleteProperty(k)}});
}