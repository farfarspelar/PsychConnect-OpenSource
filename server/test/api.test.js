import test from 'node:test';
import assert from 'node:assert/strict';
import { newDb } from 'pg-mem';
import { migrateDatabase } from '../db/database.js';
import { createRepository } from '../db/repository.js';
import { bindAuthenticatedProfile,createPresenceRegistry } from '../presence.js';
import { createApiServer } from '../api/http.js';
import { createRequestRateLimiter } from '../api/rateLimit.js';
import { SERVER_CONFIG } from '../config.js';
import { prepareRound } from '../domain/matchStart.js';
import { persistCompletedMatch } from '../domain/matchPersistence.js';
import { DataType } from 'pg-mem';

test('request rate-limit buckets stay within their configured memory bound',()=>{
  let now=0;
  const limiter=createRequestRateLimiter({
    general:{maximum:1,windowMs:60_000},maxBuckets:2,staleBucketMs:60_000
  },()=>now);
  const request=address=>({headers:{'x-forwarded-for':address},socket:{remoteAddress:'127.0.0.1'}});
  assert.equal(limiter.limited(request('10.0.0.1')),false);
  assert.equal(limiter.limited(request('10.0.0.1')),true);
  assert.equal(limiter.limited(request('10.0.0.2')),false);
  assert.equal(limiter.limited(request('10.0.0.3')),false);
  assert.equal(limiter.size,2);
  now=60_001;
  assert.equal(limiter.limited(request('10.0.0.4')),false);
  assert.equal(limiter.size,1);
});

test('websocket profile authentication uses the configured limit and remote socket address',()=>{
  let now=0;
  const limiter=createRequestRateLimiter({...SERVER_CONFIG.api.rateLimit,maxBuckets:2},()=>now);
  const socket=address=>({headers:{},socket:{remoteAddress:address}});
  assert.equal(limiter.limited(socket('192.0.2.1'),'websocketAuth'),false);
  for(let attempt=1;attempt<SERVER_CONFIG.api.rateLimit.websocketAuth.maximum;attempt++)
    assert.equal(limiter.limited(socket('192.0.2.1'),'websocketAuth'),false);
  assert.equal(limiter.limited(socket('192.0.2.1'),'websocketAuth'),true);
  assert.equal(limiter.limited(socket('192.0.2.2'),'websocketAuth'),false);
  assert.equal(limiter.size,2);
  now=60_001;
  assert.equal(limiter.limited(socket('192.0.2.3'),'websocketAuth'),false);
  assert.equal(limiter.size,2);
  assert.equal(limiter.limited(socket('192.0.2.2'),'websocketAuth'),false);
});

test('profile API authenticates private routes, exposes pseudonymous share profiles, and deletes only the owner',async t=>{
  const db=newDb({autoCreateForeignKeyIndices:true}); const {Pool}=db.adapters.createPg(); const pool=new Pool();
  await migrateDatabase(pool); const repository=createRepository(pool); const presence=createPresenceRegistry();
  const api=createApiServer({repository,presence}); await new Promise(resolve=>api.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${api.address().port}`;
  t.after(async()=>{ await new Promise(resolve=>api.close(resolve)); await pool.end(); });
  const created=await fetch(base+'/api/profiles',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({nickname:'Api Player',avatarId:2})});
  assert.equal(created.status,201); const data=await created.json();
  for(const invalidBody of ['null','[]','"text"']) {
    const invalid=await fetch(base+'/api/profiles',{method:'POST',headers:{'content-type':'application/json'},body:invalidBody});
    assert.equal(invalid.status,400);
    assert.equal((await invalid.json()).error,'Request body must be a JSON object.');
  }
  const oversized=await fetch(base+'/api/profiles',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({nickname:'é'.repeat(9000)})});
  assert.equal(oversized.status,413);
  const unauthorized=await fetch(base+'/api/profile'); assert.equal(unauthorized.status,401);
  const auth={authorization:`Bearer ${data.credentials.token}`,'x-profile-id':data.profile.id};
  const malformedAuth=await fetch(base+'/api/profile/auth',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({profileId:'not-a-uuid',token:'short'})});
  assert.equal(malformedAuth.status,401);
  const malformedRecovery=await fetch(base+'/api/profile/recover',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({profileCode:data.profile.profileCode,recoveryCode:'x'.repeat(16000)})});
  assert.equal(malformedRecovery.status,401);
  const own=await fetch(base+'/api/profile',{headers:auth}); assert.equal(own.status,200); assert.equal((await own.json()).profile.stats.rating,1000);
  const shared=await fetch(base+`/api/profiles/${data.profile.profileCode}`); assert.equal(shared.status,200); assert.equal((await shared.json()).profile.nickname,'Api Player');
  const removed=await fetch(base+'/api/profile',{method:'DELETE',headers:auth}); assert.equal(removed.status,200);
  const missing=await fetch(base+'/api/profile',{headers:auth}); assert.equal(missing.status,401);
});

test('profile presence transitions and private delivery follow authenticated socket lifecycle',()=>{
  const presence=createPresenceRegistry(),sent=[];
  const socket={readyState:1,send:value=>sent.push(JSON.parse(value)),close(){this.readyState=3;}};
  const profile={id:'player',presenceHidden:false,challengesEnabled:true};
  assert.equal(presence.stateFor('player'),'Offline');
  presence.register(profile,socket); assert.equal(presence.stateFor('player'),'Online');
  assert.equal(presence.sendToProfile('player',{type:'challenge_received'}),true); assert.equal(sent.length,1);
  presence.setInMatch(socket,true); presence.register(profile,socket);
  assert.equal(presence.stateFor('player'),'In Match','re-authentication preserves active match presence');
  assert.throws(()=>presence.register({...profile,id:'another-player'},socket),{code:'PROFILE_SOCKET_IDENTITY_LOCKED'});
  assert.equal(presence.stateFor('another-player'),'Offline');
  presence.updateProfile({...profile,challengesEnabled:false}); assert.equal(presence.stateFor('player'),'Unavailable');
  socket.readyState=3;
  assert.equal(presence.stateFor('player'),'Offline','a closed socket is not considered online while its close event is pending');
  assert.equal(presence.isOnline('player'),false);
  assert.equal(presence.sendToProfile('player',{type:'challenge_accepted'}),false);
  presence.remove(socket); assert.equal(presence.stateFor('player'),'Offline');
});

test('private delivery removes a socket that closes between presence check and send',()=>{
  const presence=createPresenceRegistry();
  const socket={readyState:1,send(){throw new Error('socket is closing');},close(){this.readyState=3;}};
  presence.register({id:'rival',presenceHidden:false,challengesEnabled:true},socket);
  assert.equal(presence.stateFor('rival'),'Online');
  assert.equal(presence.sendToProfile('rival',{type:'challenge_received'}),false);
  assert.equal(presence.stateFor('rival'),'Offline');
});

test('hidden presence is separate from challenge availability and match state',()=>{
  const presence=createPresenceRegistry();
  const socket={readyState:1,send(){},close(){this.readyState=3;}};
  presence.register({id:'hidden',presenceHidden:true,challengesEnabled:true},socket);
  assert.equal(presence.stateFor('hidden'),'Unavailable','public presence remains hidden');
  assert.equal(presence.challengeStateFor('hidden'),'Online','the separate challenge preference still applies');
  assert.equal(presence.isInMatch('hidden'),false);
  presence.setInMatch(socket,true);
  assert.equal(presence.stateFor('hidden'),'Unavailable');
  assert.equal(presence.challengeStateFor('hidden'),'In Match');
  assert.equal(presence.isInMatch('hidden'),true,'privacy settings do not hide match state from enforcement');
  presence.updateProfile({id:'hidden',presenceHidden:true,challengesEnabled:false});
  assert.equal(presence.challengeStateFor('hidden'),'In Match');
  presence.setInMatch(socket,false);
  assert.equal(presence.challengeStateFor('hidden'),'Unavailable');
});

test('challenge sends respect challenge preference while allowing hidden profiles',async t=>{
  const presence=createPresenceRegistry(),sent=[];
  const socket={readyState:1,send:value=>sent.push(JSON.parse(value)),close(){this.readyState=3;}};
  presence.register({id:'target',presenceHidden:true,challengesEnabled:true},socket);
  let created=0;
  const repository={authenticate:async id=>({id}),profile:async()=>({nickname:'Sender',avatarId:0}),createChallenge:async(fromId,toId)=>{created++;return {id:'challenge',fromPlayerId:fromId,toPlayerId:toId,mode:'DEATHMATCH',durationSeconds:60,halves:2,expiresAt:'later'};}};
  const api=createApiServer({repository,presence});
  await new Promise(resolve=>api.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise(resolve=>api.close(resolve)));
  const response=await fetch(`http://127.0.0.1:${api.address().port}/api/challenges`,{method:'POST',headers:{authorization:'Bearer test','x-profile-id':'sender','content-type':'application/json'},body:JSON.stringify({rivalId:'target'})});
  assert.equal(response.status,201); assert.equal(created,1); assert.equal(sent.length,1);
});

test('failed presence binding leaves the websocket identity unchanged',()=>{
  const ws={profileId:'existing'};
  assert.throws(()=>bindAuthenticatedProfile(ws,{id:'replacement'},{register(){throw Object.assign(new Error('identity locked'),{code:'PROFILE_SOCKET_IDENTITY_LOCKED'});}}),{code:'PROFILE_SOCKET_IDENTITY_LOCKED'});
  assert.equal(ws.profileId,'existing');
});

test('a disconnect during match persistence cannot start a ghost round or leave an active result',async()=>{
  let finishInsert,abandoned=0,started=0;
  const pending=new Promise(resolve=>{finishInsert=resolve;});
  const room={phase:'active',players:[{profileId:'a',role:'LEADER',ws:{}},{profileId:'b',role:'FOLLOWER',ws:{}}],mode:'DEATHMATCH',duration:30,matchHalves:2,ready:new Set(['a','b'])};
  const repository={beginMatch:()=>pending,abandonMatch:async(id,reason)=>{assert.equal(id,'match-id');assert.equal(reason,'disconnect');abandoned++;}};
  const task=prepareRound(room,{repository,presence:{setInMatch(){assert.fail('disconnected players must not become in-match');}},startRound:()=>started++,createMatchId:()=>'match-id'});
  await new Promise(resolve=>setImmediate(resolve));
  room.players.pop(); room.phase='waiting'; room.matchId=null;
  finishInsert();
  assert.equal(await task,false);
  assert.equal(abandoned,1);
  assert.equal(started,0);
});

test('a failed profile match write returns the room to ready and can be retried',async()=>{
  const room={phase:'active',players:[{profileId:'a',role:'LEADER',ws:{}},{profileId:'b',role:'FOLLOWER',ws:{}}],mode:'DEATHMATCH',duration:30,matchHalves:2,ready:new Set(['a','b'])};
  let reported=0,started=0;
  const ok=await prepareRound(room,{repository:{beginMatch:async()=>{throw new Error('database unavailable');}},presence:{setInMatch(){}},startRound:()=>started++,createMatchId:()=>'match-id',reportError:()=>reported++});
  assert.equal(ok,false); assert.equal(room.phase,'ready'); assert.equal(room.matchId,null); assert.equal(room.ready.size,0);
  assert.equal(reported,1); assert.equal(started,0);
});

test('match persistence distinguishes stored, missing, and failed results',async()=>{
  let unlockCount=0,failureCount=0,errorCount=0;
  const options={match:{id:'match'},onUnlocks:items=>unlockCount+=items.length,onFailure:()=>failureCount++,onError:()=>errorCount++};
  assert.equal(await persistCompletedMatch({...options,repository:{completeMatch:async()=>({recorded:true,unlocks:[{},{}]})}}),true);
  assert.equal(unlockCount,2); assert.equal(failureCount,0);
  assert.equal(await persistCompletedMatch({...options,repository:{completeMatch:async()=>({recorded:false})}}),false);
  assert.equal(failureCount,1); assert.equal(errorCount,0);
  assert.equal(await persistCompletedMatch({...options,repository:{completeMatch:async()=>{throw new Error('database down');}}}),false);
  assert.equal(failureCount,2); assert.equal(errorCount,1);
  assert.equal(await persistCompletedMatch({...options,repository:{completeMatch:async()=>({recorded:true,unlocks:[{}]})},onUnlocks:()=>{throw new Error('socket closed');}}),true);
  assert.equal(failureCount,2); assert.equal(errorCount,2);
});

test('accepting a rival invitation requires the inviter to remain online',async t=>{
  const db=newDb({autoCreateForeignKeyIndices:true});
  db.public.registerFunction({name:'least',args:[DataType.uuid,DataType.uuid],returns:DataType.uuid,implementation:(a,b)=>String(a)<=String(b)?a:b});
  db.public.registerFunction({name:'greatest',args:[DataType.uuid,DataType.uuid],returns:DataType.uuid,implementation:(a,b)=>String(a)>=String(b)?a:b});
  const {Pool}=db.adapters.createPg(),pool=new Pool(); await migrateDatabase(pool);
  const repository=createRepository(pool),presence=createPresenceRegistry();
  let sessionsCreated=0;
  const api=createApiServer({repository,presence,onChallengeAccepted:async()=>{sessionsCreated++;return {code:'AB12CD'};}});
  await new Promise(resolve=>api.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${api.address().port}`;
  t.after(async()=>{await new Promise(resolve=>api.close(resolve));await pool.end();});

  const inviter=await repository.createProfile('Inviter'),recipient=await repository.createProfile('Recipient');
  const matchId='00000000-0000-4000-8000-000000000001';
  await repository.beginMatch({id:matchId,mode:'DEATHMATCH',startedAt:new Date(),durationSeconds:60,halves:2,participants:[{profileId:inviter.profile.id},{profileId:recipient.profile.id}]});
  await repository.completeMatch({id:matchId,mode:'DEATHMATCH',winnerProfileId:inviter.profile.id,participants:[{profileId:inviter.profile.id,score:10},{profileId:recipient.profile.id,score:0}],durationSeconds:60,syncSeconds:0,longestSyncSeconds:0});
  const challenge=await repository.createChallenge(inviter.profile.id,recipient.profile.id);
  const inviterSocket={readyState:1,send(){},close(){this.readyState=3;}};
  const recipientSocket={readyState:1,send(){},close(){this.readyState=3;}};
  presence.register({id:recipient.profile.id,presenceHidden:false,challengesEnabled:true},recipientSocket);
  const auth={authorization:`Bearer ${recipient.credentials.token}`,'x-profile-id':recipient.profile.id,'content-type':'application/json'};

  const malformed=await fetch(`${base}/api/challenges/${challenge.id}/respond`,{method:'POST',headers:auth,body:JSON.stringify({accept:'false'})});
  assert.equal(malformed.status,400);
  assert.equal(sessionsCreated,0);
  assert.equal((await repository.challengeInbox(recipient.profile.id)).length,1);

  const offline=await fetch(`${base}/api/challenges/${challenge.id}/respond`,{method:'POST',headers:auth,body:JSON.stringify({accept:true})});
  assert.equal(offline.status,409);
  assert.equal(sessionsCreated,0);
  assert.equal((await repository.challengeInbox(recipient.profile.id)).length,1);

  presence.register({id:inviter.profile.id,presenceHidden:false,challengesEnabled:true},inviterSocket);
  inviterSocket.readyState=3;
  const staleSocket=await fetch(`${base}/api/challenges/${challenge.id}/respond`,{method:'POST',headers:auth,body:JSON.stringify({accept:true})});
  assert.equal(staleSocket.status,409,'a socket waiting for its close callback cannot receive the reserved room code');
  assert.equal(sessionsCreated,0);
  inviterSocket.readyState=1;
  const accepted=await fetch(`${base}/api/challenges/${challenge.id}/respond`,{method:'POST',headers:auth,body:JSON.stringify({accept:true})});
  assert.equal(accepted.status,200);
  assert.equal((await accepted.json()).challenge.roomCode,'AB12CD');
  assert.equal(sessionsCreated,1);
});

test('multiplayer server can report profile persistence disabled without blocking legacy play',async t=>{
  const api=createApiServer({repository:null,presence:createPresenceRegistry()});
  await new Promise(resolve=>api.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${api.address().port}`;
  t.after(()=>new Promise(resolve=>api.close(resolve)));
  const health=await (await fetch(base+'/health')).json(); assert.equal(health.profilesEnabled,false);
  const profile=await fetch(base+'/api/profiles',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({nickname:'Legacy'})});
  assert.equal(profile.status,503); assert.match((await profile.json()).error,/DATABASE_URL/);
});

test('leaderboard API caches per profile and refresh bypasses the cache',async t=>{
  let queries=0;
  const repository={authenticate:async id=>({id}),leaderboard:async(mode,range,id)=>({mode,range,playerId:id,entries:[],query:++queries})};
  const api=createApiServer({repository,presence:{}});
  await new Promise(resolve=>api.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${api.address().port}`;
  t.after(()=>new Promise(resolve=>api.close(resolve)));
  const get=(id,path='')=>fetch(`${base}/api/leaderboards?mode=DEATHMATCH&range=all_time${path}`,{headers:{authorization:'Bearer test','x-profile-id':id}});
  const first=await (await get('player-a')).json();
  const cached=await (await get('player-a')).json();
  assert.equal(first.query,1); assert.equal(cached.query,1); assert.equal(queries,1);
  assert.equal((await (await get('player-a','&refresh=true')).json()).query,2);
  assert.equal((await (await get('player-b')).json()).query,3);
});

test('profile changes and deletion immediately invalidate cached public leaderboard rows',async t=>{
  let queries=0,deleted=false;
  const repository={
    authenticate:async id=>({id}),
    leaderboard:async(mode,range,id)=>({mode,range,playerId:id,query:++queries}),
    updateProfile:async(id,body)=>({id,nickname:body.nickname,avatarId:0}),
    cancelPendingChallenges:async()=>{},
    deleteProfile:async()=>{deleted=true;return {deleted:true};}
  };
  const presence={updateProfile(){},removeProfile(){}};
  const api=createApiServer({repository,presence});
  await new Promise(resolve=>api.listen(0,'127.0.0.1',resolve));
  const base=`http://127.0.0.1:${api.address().port}`;
  t.after(()=>new Promise(resolve=>api.close(resolve)));
  const headers=id=>({authorization:'Bearer test','x-profile-id':id,'content-type':'application/json'});
  const board=id=>fetch(`${base}/api/leaderboards?mode=DEATHMATCH&range=all_time`,{headers:headers(id)});
  assert.equal((await (await board('owner')).json()).query,1);
  assert.equal((await (await board('viewer')).json()).query,2);
  const renamed=await fetch(`${base}/api/profile`,{method:'PATCH',headers:headers('owner'),body:JSON.stringify({nickname:'New name'})});
  assert.equal(renamed.status,200);
  assert.equal((await (await board('viewer')).json()).query,3,'a changed nickname is visible on the next leaderboard fetch');
  const removed=await fetch(`${base}/api/profile`,{method:'DELETE',headers:headers('owner')});
  assert.equal(removed.status,200); assert.equal(deleted,true);
  assert.equal((await (await board('viewer')).json()).query,4,'deleted profiles are removed from cached leaderboards immediately');
});

test('a failed profile deletion leaves its live presence intact',async t=>{
  let presenceRemoved=0,challengeCleanup=0;
  const repository={authenticate:async id=>({id}),deleteProfile:async()=>{throw new Error('database unavailable');}};
  const presence={removeProfile(){presenceRemoved++;}};
  const api=createApiServer({repository,presence,onChallengeCancelled:async()=>{challengeCleanup++;}});
  await new Promise(resolve=>api.listen(0,'127.0.0.1',resolve));
  t.after(()=>new Promise(resolve=>api.close(resolve)));
  const response=await fetch(`http://127.0.0.1:${api.address().port}/api/profile`,{method:'DELETE',headers:{authorization:'Bearer test','x-profile-id':'owner'}});
  assert.equal(response.status,500);
  assert.equal(presenceRemoved,0);
  assert.equal(challengeCleanup,0);
});
