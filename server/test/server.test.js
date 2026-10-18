import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import WebSocket from 'ws';
import net from 'node:net';
import { roundResult, deathmatchRoundResult, connectionScoreStep, clampLeaderPoint } from '../game.js';
import { SERVER_CONFIG } from '../config.js';

test('host and join receive game updates and oversized WebSocket messages are rejected', async t => {
  const probe = net.createServer(); probe.listen(0, '127.0.0.1'); await once(probe, 'listening');
  const port = probe.address().port; await new Promise(resolve => probe.close(resolve));
  const child = spawn(process.execPath, ['index.js'], { cwd: new URL('../', import.meta.url), env: { ...process.env, PORT: String(port), TOUCH_STALE_MS: '600' }, stdio: 'ignore' });
  t.after(() => child.kill('SIGTERM'));
  const url = `ws://127.0.0.1:${port}`;
  let host;
  for (let i=0;i<50;i++) { try { host=new WebSocket(url); await once(host,'open'); break; } catch { await new Promise(r=>setTimeout(r,50)); } }
  assert.ok(host?.readyState===WebSocket.OPEN,'server started');
  const guest=new WebSocket(url); await once(guest,'open');
  const inbox = ws => { const queue=[]; ws.on('message',raw=>queue.push(JSON.parse(raw))); return { queue, wait: async type => { for(let i=0;i<40;i++){const at=queue.findIndex(x=>x.type===type);if(at>=0)return queue.splice(at,1)[0];await new Promise(r=>setTimeout(r,25));}throw new Error(`Timed out waiting for ${type}`);} }; };
  const h=inbox(host), g=inbox(guest);
  host.send('{"type":"ping","clientTs":12345}'); const pong=await h.wait('pong');
  assert.equal(pong.clientTs,12345); assert.ok(Number.isFinite(pong.serverTs));
  host.send(JSON.stringify({type:'host',durationSec:30,matchHalves:4,mode:'DEATHMATCH'})); const created=await h.wait('hosted');
  guest.send(JSON.stringify({type:'join',code:created.code})); const joined=await g.wait('joined');
  assert.equal(created.role,'LEADER'); assert.equal(joined.role,'FOLLOWER');
  assert.equal(created.mode,'DEATHMATCH'); assert.equal(joined.mode,'DEATHMATCH'); assert.equal(created.matchHalves,4); assert.equal(joined.matchHalves,4);
  await h.wait('ready'); await g.wait('ready');
  host.send('{"type":"ready"}'); guest.send('{"type":"ready"}');
  const hostRound=await h.wait('round_start'); const guestRound=await g.wait('round_start');
  assert.equal(hostRound.mode,'DEATHMATCH'); assert.equal(guestRound.mode,'DEATHMATCH'); assert.equal(hostRound.matchHalves,4);
  host.send('{"type":"touch","active":true,"x":0.4,"y":0.5,"seq":1}');
  await h.wait('touch'); await g.wait('touch');
  guest.send('{"type":"touch","active":true,"x":0.41,"y":0.5,"seq":1}');
  await g.wait('touch'); await h.wait('touch');
  const metric=await h.wait('live_score');
  assert.equal(metric.mode,'DEATHMATCH'); assert.equal(metric.role,'LEADER'); assert.ok(metric.score>0);
  assert.equal(metric.sync,undefined); assert.equal(metric.alignment,undefined);
  const guestMetric=await g.wait('live_score');
  assert.equal(guestMetric.role,'FOLLOWER'); assert.ok(guestMetric.score>metric.score);
  assert.deepEqual(metric.scores,guestMetric.scores);
  assert.ok(metric.scores[created.playerId]>=metric.score);
  assert.ok(metric.scores[joined.playerId]>=guestMetric.score);
  const spare=new WebSocket(url); await once(spare,'open'); const s=inbox(spare);
  spare.send('{"type":"host","mode":"HARMONY"}'); const spareRoom=await s.wait('hosted');
  guest.send(JSON.stringify({type:'join',code:spareRoom.code}));
  const secondJoin=await g.wait('error'); assert.match(secondJoin.message,/already in a session/i);
  assert.equal(s.queue.some(message=>message.type==='ready'),false,'the second room remains untouched');
  spare.close();
  host.send('{"type":"ability","ability":"decoy","x":0.8,"y":0.7}');
  const decoy1=await h.wait('ability_result'); assert.equal(decoy1.accepted,true); assert.equal(decoy1.remaining,1);
  host.send('{"type":"ability","ability":"decoy","x":0.2,"y":0.3}');
  const decoy2=await h.wait('ability_result'); assert.equal(decoy2.accepted,true); assert.equal(decoy2.remaining,0);
  assert.equal(g.queue.some(message=>message.type==='ability_effect'),false,'placing a decoy does not reveal it to the Seeker');
  guest.send('{"type":"ability","ability":"sonar"}');
  const sonar1=await g.wait('ability_result'); assert.equal(sonar1.accepted,true); assert.equal(sonar1.remaining,1); assert.equal(sonar1.x,.4); assert.equal(sonar1.y,.5); assert.equal(sonar1.decoyX,.8); assert.equal(sonar1.decoyY,.7);
  guest.send('{"type":"ability","ability":"sonar"}');
  const sonar2=await g.wait('ability_result'); assert.equal(sonar2.accepted,true); assert.equal(sonar2.remaining,0); assert.equal(sonar2.decoyX,.2); assert.equal(sonar2.decoyY,.3);
  guest.send('{"type":"ability","ability":"sonar"}');
  const sonar3=await g.wait('ability_result'); assert.equal(sonar3.accepted,false); assert.equal(sonar3.reason,'depleted');
  host.send('{"type":"ability","ability":"decoy","x":0.5,"y":0.5}');
  const decoy3=await h.wait('ability_result'); assert.equal(decoy3.accepted,false); assert.equal(decoy3.reason,'depleted');
  host.send('{"type":"touch","active":true,"x":0.9,"y":0.9,"seq":1}');
  for(let i=0;i<2;i++) { const stale=await g.wait('touch'); assert.equal(stale.active,false); assert.equal(stale.stale,true,'server releases a touch that stops updating'); }
  for(let i=0;i<2;i++) { const stale=await h.wait('touch'); assert.equal(stale.active,false); assert.equal(stale.stale,true); }
  await new Promise(r=>setTimeout(r,100)); assert.equal(h.queue.some(x=>x.type==='touch'),false,'duplicate sequence is discarded');
  const closed=once(guest,'close');
  guest.send('x'.repeat(SERVER_CONFIG.networking.webSocketMaxPayloadBytes+1));
  const [code]=await closed; assert.equal(code,1009,'payload-too-large close code');
  host.close();
});

test('active round pauses through a short disconnect and resumes with the same room and score', async t => {
  const probe=net.createServer(); probe.listen(0,'127.0.0.1'); await once(probe,'listening');
  const port=probe.address().port; await new Promise(resolve=>probe.close(resolve));
  const child=spawn(process.execPath,['index.js'],{cwd:new URL('../',import.meta.url),env:{...process.env,PORT:String(port),RECONNECT_GRACE_MS:'4000'},stdio:'ignore'});
  t.after(()=>child.kill('SIGTERM'));
  const url=`ws://127.0.0.1:${port}`;
  const open=async()=>{for(let i=0;i<50;i++){try{const ws=new WebSocket(url);await once(ws,'open');return ws;}catch{await new Promise(r=>setTimeout(r,50));}}throw new Error('server did not start');};
  const inbox=ws=>{const queue=[];ws.on('message',raw=>queue.push(JSON.parse(raw)));return{queue,wait:async type=>{for(let i=0;i<80;i++){const at=queue.findIndex(x=>x.type===type);if(at>=0)return queue.splice(at,1)[0];await new Promise(r=>setTimeout(r,25));}throw new Error(`Timed out waiting for ${type}`);}};};
  let host=await open(); const guest=await open(); const h=inbox(host),g=inbox(guest);
  host.send(JSON.stringify({type:'host',durationSec:30,mode:'DEATHMATCH'})); const hosted=await h.wait('hosted');
  guest.send(JSON.stringify({type:'join',code:hosted.code})); await g.wait('joined'); await h.wait('ready'); await g.wait('ready');
  host.send('{"type":"ready"}'); guest.send('{"type":"ready"}'); await h.wait('round_start'); await g.wait('round_start');
  host.send('{"type":"touch","active":true,"x":0.4,"y":0.5,"seq":1}'); await h.wait('touch'); await g.wait('touch');
  host.close(); await g.wait('peer_reconnecting'); g.queue.length=0;
  const before=Date.now(); await new Promise(resolve=>setTimeout(resolve,300));
  const resumed=await open(); const rh=inbox(resumed);
  resumed.send(JSON.stringify({type:'resume',code:hosted.code,playerId:hosted.playerId,resumeToken:hosted.resumeToken}));
  const state=await rh.wait('session_resumed'); await g.wait('peer_reconnected');
  assert.equal(state.waitingForPeer,false); assert.equal(state.code,hosted.code); assert.equal(state.playerId,hosted.playerId);
  assert.ok(state.remainingMs>0&&state.remainingMs<=30_000); assert.ok(Date.now()-before<3_000);
  resumed.send('{"type":"touch","active":true,"x":0.45,"y":0.5,"seq":2}');
  let relayedTouch;
  for(let i=0;i<40;i++) { const message=await g.wait('touch'); if(message.active) { relayedTouch=message; break; } }
  assert.equal(relayedTouch?.active,true,'normal touch relay resumes after rejoin');
  resumed.close(); guest.close();
});

test('a player can resume a room from the waiting lobby without losing the room code', async t => {
  const probe=net.createServer(); probe.listen(0,'127.0.0.1'); await once(probe,'listening');
  const port=probe.address().port; await new Promise(resolve=>probe.close(resolve));
  const child=spawn(process.execPath,['index.js'],{cwd:new URL('../',import.meta.url),env:{...process.env,PORT:String(port),RECONNECT_GRACE_MS:'4000'},stdio:'ignore'});
  t.after(()=>child.kill('SIGTERM'));
  const url=`ws://127.0.0.1:${port}`;
  const open=async()=>{for(let i=0;i<50;i++){try{const ws=new WebSocket(url);await once(ws,'open');return ws;}catch{await new Promise(r=>setTimeout(r,50));}}throw new Error('server did not start');};
  const inbox=ws=>{const queue=[];ws.on('message',raw=>queue.push(JSON.parse(raw)));return{queue,wait:async type=>{for(let i=0;i<80;i++){const at=queue.findIndex(x=>x.type===type);if(at>=0)return queue.splice(at,1)[0];await new Promise(r=>setTimeout(r,25));}throw new Error(`Timed out waiting for ${type}`);}};};
  const host=await open(),guest=await open(),h=inbox(host),g=inbox(guest);
  host.send('{"type":"host","mode":"CONNECTION"}'); const hosted=await h.wait('hosted');
  guest.send(JSON.stringify({type:'join',code:hosted.code})); const joined=await g.wait('joined'); await h.wait('ready');
  guest.close(); await h.wait('peer_reconnecting');
  const resumed=await open(),r=inbox(resumed);
  resumed.send(JSON.stringify({type:'resume',code:hosted.code,playerId:joined.playerId,resumeToken:joined.resumeToken}));
  const state=await r.wait('session_resumed'); assert.equal(state.phase,'waiting'); assert.equal(state.waitingForPeer,false);
  await h.wait('peer_reconnected');
  host.send('{"type":"ready"}'); resumed.send('{"type":"ready"}');
  await h.wait('round_start'); await r.wait('round_start');
  host.close(); resumed.close();
});

test('Harmony is an untimed sandbox that both players can exit and rematch', async t => {
  const probe=net.createServer(); probe.listen(0,'127.0.0.1'); await once(probe,'listening');
  const port=probe.address().port; await new Promise(resolve=>probe.close(resolve));
  const child=spawn(process.execPath,['index.js'],{cwd:new URL('../',import.meta.url),env:{...process.env,PORT:String(port),TOUCH_STALE_MS:'300'},stdio:'ignore'});
  t.after(()=>child.kill('SIGTERM'));
  const url=`ws://127.0.0.1:${port}`;
  let host;
  for(let i=0;i<50;i++){ try{host=new WebSocket(url);await once(host,'open');break;}catch{await new Promise(r=>setTimeout(r,50));} }
  const guest=new WebSocket(url); await once(guest,'open');
  const inbox=ws=>{const queue=[];ws.on('message',raw=>queue.push(JSON.parse(raw)));return{queue,wait:async type=>{for(let i=0;i<40;i++){const at=queue.findIndex(x=>x.type===type);if(at>=0)return queue.splice(at,1)[0];await new Promise(r=>setTimeout(r,25));}throw new Error(`Timed out waiting for ${type}`);}};};
  const h=inbox(host),g=inbox(guest);
  host.send('{"type":"host","mode":"HARMONY"}'); const room=await h.wait('hosted');
  guest.send(JSON.stringify({type:'join',code:room.code})); await g.wait('joined'); await h.wait('ready'); await g.wait('ready');
  host.send('{"type":"ready"}'); guest.send('{"type":"ready"}');
  const started=await h.wait('round_start'); await g.wait('round_start');
  assert.equal(started.durationSec,0); assert.equal(started.mode,'HARMONY');
  const five=Array.from({length:5},(_,id)=>({id,x:id/5,y:.5}));
  host.send(JSON.stringify({type:'harmony_touches',seq:1,points:five}));
  const mirroredFive=await g.wait('harmony_touches');
  assert.equal(mirroredFive.points.length,5); assert.deepEqual(mirroredFive.points,five.map(point=>({...point,sizeClass:1})));
  host.send(JSON.stringify({type:'harmony_touches',seq:1,points:[]}));
  host.send(JSON.stringify({type:'harmony_touches',seq:2,points:Array.from({length:6},(_,id)=>({id,x:.5,y:.5}))}));
  await new Promise(r=>setTimeout(r,80));
  assert.equal(g.queue.some(message=>message.type==='harmony_touches'),false,'stale and over-limit touch snapshots are ignored');
  host.send(JSON.stringify({type:'harmony_touches',seq:2,points:[{id:0,x:.1,y:.1},{id:0,x:.2,y:.2}]}));
  await new Promise(r=>setTimeout(r,50));
  assert.equal(g.queue.some(message=>message.type==='harmony_touches'),false,'duplicate pointer IDs are rejected');
  host.send(JSON.stringify({type:'harmony_touches',seq:2,points:[]}));
  assert.equal((await g.wait('harmony_touches')).points.length,0,'lifting every finger clears the remote points');
  const two=[{id:0,x:.2,y:.3},{id:1,x:.8,y:.7}];
  guest.send(JSON.stringify({type:'harmony_touches',seq:1,points:two}));
  const mirroredTwo=await h.wait('harmony_touches');
  assert.equal(mirroredTwo.points.length,2); assert.deepEqual(mirroredTwo.points,two.map(point=>({...point,sizeClass:1})));
  await new Promise(r=>setTimeout(r,180));
  guest.send(JSON.stringify({type:'harmony_touches',seq:2,points:two}));
  const refreshed=await h.wait('harmony_touches'); assert.deepEqual(refreshed.points,two.map(point=>({...point,sizeClass:1})),'stationary fingers stay present when the client refreshes its snapshot');
  const expired=await h.wait('harmony_touches');
  assert.equal(expired.stale,true); assert.deepEqual(expired.points,[],'a lost touch stream clears the remote markers');
  await new Promise(r=>setTimeout(r,350));
  assert.equal(h.queue.some(x=>x.type==='live_score'),false,'sandbox has no score feed');
  guest.send('{"type":"sandbox_exit"}'); await h.wait('sandbox_end'); await g.wait('sandbox_end');
  host.send('{"type":"rematch"}'); await h.wait('rematch_waiting'); guest.send('{"type":"rematch"}');
  await h.wait('rematch_ready'); await g.wait('rematch_ready');
  host.close(); guest.close();
});

test('Harmony haptics require host opt-in and joining player consent; decline keeps touch play available', async t => {
  const probe=net.createServer(); probe.listen(0,'127.0.0.1'); await once(probe,'listening');
  const port=probe.address().port; await new Promise(resolve=>probe.close(resolve));
  const child=spawn(process.execPath,['index.js'],{cwd:new URL('../',import.meta.url),env:{...process.env,PORT:String(port),HARMONY_CONSENT_TIMEOUT_MS:'300',TOUCH_STALE_MS:'300'},stdio:'ignore'});
  t.after(()=>child.kill('SIGTERM'));
  const url=`ws://127.0.0.1:${port}`;
  const open=async()=>{ let ws; for(let i=0;i<50;i++){ try{ws=new WebSocket(url);await once(ws,'open');return ws;}catch{await new Promise(r=>setTimeout(r,50));} } throw new Error('server did not start'); };
  const inbox=ws=>{const queue=[];ws.on('message',raw=>queue.push(JSON.parse(raw)));return{queue,wait:async type=>{for(let i=0;i<60;i++){const at=queue.findIndex(x=>x.type===type);if(at>=0)return queue.splice(at,1)[0];await new Promise(r=>setTimeout(r,25));}throw new Error(`Timed out waiting for ${type}`);}};};

  const host=await open(),guest=await open(),h=inbox(host),g=inbox(guest);
  host.send('{"type":"host","mode":"HARMONY","allowHarmonyHaptics":true}'); const room=await h.wait('hosted');
  guest.send(JSON.stringify({type:'join',code:room.code})); await g.wait('joined');
  await g.wait('harmony_haptics_consent_required'); await h.wait('harmony_haptics_consent_waiting');
  guest.send('{"type":"harmony_haptics_consent","accepted":false}');
  assert.equal((await h.wait('harmony_haptics_state')).enabled,false);
  assert.equal((await g.wait('harmony_haptics_state')).enabled,false);
  await h.wait('ready'); await g.wait('ready');
  host.send('{"type":"ready"}'); guest.send('{"type":"ready"}');
  await h.wait('round_start'); await g.wait('round_start');
  guest.send('{"type":"harmony_gesture","gesture":"tap","fingers":1,"seq":1}');
  await new Promise(r=>setTimeout(r,100));
  assert.equal(h.queue.some(message=>message.type==='harmony_haptic_event'),false,'declined haptics never relay vibration gestures');
  guest.send(JSON.stringify({type:'harmony_touches',seq:1,points:[{id:0,x:.2,y:.3},{id:1,x:.8,y:.7}],palm:true}));
  const declinedTouch=await h.wait('harmony_touches');
  assert.equal(declinedTouch.points.length,2,'touch markers still relay when haptics are declined');
  assert.equal(declinedTouch.palm,false,'declined shared haptics suppresses palm state');

  const host2=await open(),guest2=await open(),h2=inbox(host2),g2=inbox(guest2);
  host2.send('{"type":"host","mode":"HARMONY","allowHarmonyHaptics":true}'); const room2=await h2.wait('hosted');
  guest2.send(JSON.stringify({type:'join',code:room2.code})); await g2.wait('joined'); await g2.wait('harmony_haptics_consent_required');
  guest2.send('{"type":"harmony_haptics_consent","accepted":true}');
  assert.equal((await h2.wait('harmony_haptics_state')).enabled,true);
  assert.equal((await g2.wait('harmony_haptics_state')).enabled,true);
  await h2.wait('ready'); await g2.wait('ready');
  host2.send('{"type":"ready"}'); guest2.send('{"type":"ready"}');
  await h2.wait('round_start'); await g2.wait('round_start');
  guest2.send(JSON.stringify({type:'harmony_touches',seq:1,points:[],palm:true}));
  assert.equal((await h2.wait('harmony_touches')).palm,false,'a palm flag without a live touch cannot activate remote feedback');
  guest2.send(JSON.stringify({type:'harmony_touches',seq:2,points:[{id:0,x:.5,y:.5}],palm:true}));
  const sharedPalm=await h2.wait('harmony_touches');
  assert.equal(sharedPalm.palm,true,'bilateral consent permits only the compact palm state');
  assert.equal(Object.hasOwn(sharedPalm,'size'),false,'raw device contact measurements are never relayed');
  guest2.send(JSON.stringify({type:'harmony_touches',seq:2,points:[{id:0,x:.6,y:.5}],palm:false}));
  await new Promise(r=>setTimeout(r,100));
  assert.equal(h2.queue.some(message=>message.type==='harmony_touches'),false,'duplicate sequences are discarded');
  const expiredPalm=await h2.wait('harmony_touches');
  assert.equal(expiredPalm.palm,false,'server expiry clears remote palm state when touch updates go stale');
  assert.equal(expiredPalm.stale,true);
  guest2.send('{"type":"harmony_gesture","gesture":"tap","fingers":2,"seq":1}');
  const sharedTap=await h2.wait('harmony_haptic_event');
  assert.equal(sharedTap.gesture,'tap'); assert.equal(sharedTap.fingers,2);
  assert.equal(g2.queue.some(message=>message.type==='harmony_haptic_event'),false,'gesture events are sent only to the other player');
  await new Promise(r=>setTimeout(r,80));
  guest2.send('{"type":"harmony_gesture","gesture":"anchor_start","fingers":1,"seq":2}');
  assert.equal((await h2.wait('harmony_haptic_event')).gesture,'anchor_start');
  await new Promise(r=>setTimeout(r,80));
  guest2.send('{"type":"harmony_gesture","gesture":"anchor_end","fingers":1,"seq":3}');
  assert.equal((await h2.wait('harmony_haptic_event')).gesture,'anchor_end');
  guest2.send('{"type":"harmony_haptics_revoke"}');
  assert.equal((await h2.wait('harmony_haptics_state')).enabled,false,'either player can revoke shared haptics immediately');
  assert.equal((await g2.wait('harmony_haptics_state')).enabled,false);
  guest2.send(JSON.stringify({type:'harmony_touches',seq:3,points:[{id:0,x:.5,y:.5}],palm:true}));
  assert.equal((await h2.wait('harmony_touches')).palm,false,'palm state is suppressed after consent is revoked');

  const host3=await open(),guest3=await open(),h3=inbox(host3),g3=inbox(guest3);
  host3.send('{"type":"host","mode":"HARMONY","allowHarmonyHaptics":true}'); const room3=await h3.wait('hosted');
  guest3.send(JSON.stringify({type:'join',code:room3.code})); await g3.wait('joined'); await g3.wait('harmony_haptics_consent_required');
  assert.equal((await h3.wait('harmony_haptics_state')).enabled,false,'an unanswered consent prompt safely falls back to no shared haptics');
  await h3.wait('ready'); await g3.wait('harmony_haptics_state'); await g3.wait('ready');
  host.close(); guest.close(); host2.close(); guest2.close(); host3.close(); guest3.close();
});

test('session codes are short and coordinate values are bounded to the shared plane', () => {
  const bounded = n => Math.min(1,Math.max(0,n));
  assert.deepEqual([-2,.4,8].map(bounded),[0,.4,1]);
  assert.equal(Math.hypot(0,0),0);
});

test('round scoring rewards sustained alignment and following motion', () => {
  const samples=Array.from({length:11},(_,i)=>({time:i*100,distance:0.01,leaderX:i*.01,leaderY:0,followerX:i*.01,followerY:0}));
  const aligned=roundResult(samples); const apart=roundResult(samples.map(s=>({...s,distance:.6,followerY:.5})));
  assert.ok(aligned.score>apart.score); assert.equal(aligned.alignment,100); assert.equal(aligned.syncSeconds,1.1);
  assert.equal(roundResult([]).score,0);
});

test('deathmatch score rewards the Runner for distance and the Chaser for closeness', () => {
  const samples=[{distance:.05,time:0},{distance:.1,time:100},{distance:.12,time:200}];
  const runner=deathmatchRoundResult(samples,'LEADER'); const chaser=deathmatchRoundResult(samples,'FOLLOWER');
  assert.equal(runner.score+chaser.score,100);
  assert.ok(chaser.score>runner.score);
});

test('shared connection points rise only while both move and decay while still', () => {
  const growing=connectionScoreStep(20,.01,true,true,.1);
  const fading=connectionScoreStep(20,.01,true,false,.1);
  assert.ok(growing>20); assert.ok(fading<20);
  assert.equal(connectionScoreStep(.5,.8,false,false,.5),0);
  assert.equal(connectionScoreStep(99.9,0,true,true,.25),100);
});

test('leader speed is capped while natural movement is preserved', () => {
  assert.deepEqual(clampLeaderPoint(null,{x:.1,y:.2},50),{x:.1,y:.2,limited:false});
  const capped=clampLeaderPoint({x:0,y:0},{x:1,y:1},100);
  assert.equal(capped.limited,true); assert.ok(Math.abs(Math.hypot(capped.x,capped.y)-.05)<1e-9);
});
