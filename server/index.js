import { WebSocketServer } from 'ws';
import { SERVER_CONFIG } from './config.js';
import { randomBytes,randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { roundResult, deathmatchRoundResult, connectionScoreStep, clampLeaderPoint } from './game.js';
import { canExpireChallengeRoom } from './domain/challenges.js';
import { databaseFromEnv,migrateDatabase } from './db/database.js';
import { createRepository } from './db/repository.js';
import { bindAuthenticatedProfile,createPresenceRegistry } from './presence.js';
import { createApiServer } from './api/http.js';
import { createRequestRateLimiter } from './api/rateLimit.js';
import { prepareRound } from './domain/matchStart.js';
import { persistCompletedMatch } from './domain/matchPersistence.js';
import { createTogetherWaveGroup,classifyTogetherWaveContact,togetherWaveResult } from './togetherWaves.js';
import { recordScoringPosition,sampleScoringPosition } from './scoringTimeline.js';
import { startPuzzle,recordPuzzleTouch,updatePuzzle } from './puzzleSession.js';
import { resetCoopSurvival,updateCoopSurvival } from './coopSurvival.js';

const port = Number(process.env.PORT || 8787);
const GAME=SERVER_CONFIG.gameplay;
const configuredTouchStaleMs=Number(process.env.TOUCH_STALE_MS || GAME.touchStaleMs);
const touchStaleMs=Number.isFinite(configuredTouchStaleMs) ? Math.max(GAME.minTouchStaleMs,configuredTouchStaleMs) : GAME.touchStaleMs;
const configuredReconnectGraceMs=Number(process.env.RECONNECT_GRACE_MS || GAME.reconnectGraceMs);
const reconnectGraceMs=Number.isFinite(configuredReconnectGraceMs) ? Math.max(500,Math.min(60_000,configuredReconnectGraceMs)) : GAME.reconnectGraceMs;
const configuredHarmonyConsentTimeoutMs=Number(process.env.HARMONY_CONSENT_TIMEOUT_MS || GAME.harmonyConsentTimeoutMs);
const harmonyConsentTimeoutMs=Number.isFinite(configuredHarmonyConsentTimeoutMs) ? Math.max(250,Math.min(60_000,configuredHarmonyConsentTimeoutMs)) : GAME.harmonyConsentTimeoutMs;
const wss = new WebSocketServer({ noServer:true,maxPayload:SERVER_CONFIG.networking.webSocketMaxPayloadBytes });
const pool = databaseFromEnv();
const repository = pool ? createRepository(pool) : null;
let databaseReady=false;
const presence = createPresenceRegistry();
const rooms = new Map();
const websocketAuthLimiter=createRequestRateLimiter(SERVER_CONFIG.api.rateLimit);
const code = () => randomBytes(3).toString('hex').toUpperCase();
const send = (peer, payload) => { if (peer?.readyState === 1) peer.send(JSON.stringify(payload)); };
const broadcast = (room, payload) => room.players.forEach(p => send(p.ws, payload));
async function saveArcadePairRun(room,mode,score,level=0) {
  if(!repository||!databaseReady||room.arcadeRunRecorded||room.players.length!==2) return;
  const participants=room.players.map(player=>({profileId:player.profileId}));
  if(participants.some(player=>!player.profileId)) return;
  room.arcadeRunRecorded=true;
  try {
    const recorded=await repository.recordArcadePairRun({id:randomUUID(),mode,participants,score,level});
    if(recorded) broadcast(room,{type:'arcade_leaderboard_saved',mode});
    else broadcast(room,{type:'error',message:'This run could not be added to the online leaderboard.'});
  } catch(error) {
    console.error(`Could not save ${mode} leaderboard result:`,error.message);
    broadcast(room,{type:'error',message:'This run ended, but its leaderboard result could not be saved.'});
  }
}
function allowWebSocketAuth(ws) {
  return !websocketAuthLimiter.limited({headers:{},socket:ws._socket},'websocketAuth');
}
function resolveHarmonyHapticsConsent(room,consent={}) {
  if(!room.harmonyConsentPending) return false;
  clearTimeout(room.harmonyConsentTimer);
  room.harmonyConsentTimer=null;
  room.harmonyConsentPending=false;
  const accepted=typeof consent==='boolean'?consent:consent||{};
  room.harmonyHapticsEnabled=room.harmonyHostHaptics===true && (accepted===true||accepted.haptics===true||accepted.accepted===true);
  room.harmonyTrailsEnabled=room.harmonyHostTrails===true && accepted!==true && accepted.trails===true;
  broadcast(room,{type:'harmony_haptics_state',enabled:room.harmonyHapticsEnabled});
  broadcast(room,{type:'harmony_trails_state',enabled:room.harmonyTrailsEnabled});
  broadcast(room,{type:'ready'});
  return true;
}
function updateTogetherWaves(room,now) {
  if(room.mode!=='CONNECTION') return;
  if(now>=room.nextTogetherWaveAt) {
    const group=createTogetherWaveGroup(room.togetherWaveSequence=(room.togetherWaveSequence||0)+1,now);
    room.togetherWaves.push(...group.waves.map(wave=>({...wave,contacts:{},scored:false})));
    broadcast(room,{type:'together_waves',id:group.id,waves:group.waves.map(({id,x,y,gaps,startAt,durationMs,maxRadius})=>({id,x,y,gaps,startDelayMs:startAt-now,durationMs,maxRadius}))});
    room.nextTogetherWaveAt=now+GAME.togetherWaveIntervalMinMs+Math.random()*(GAME.togetherWaveIntervalMaxMs-GAME.togetherWaveIntervalMinMs);
  }
  room.togetherWaves=room.togetherWaves.filter(wave=>now<=wave.startAt+wave.durationMs+500);
  for(const wave of room.togetherWaves) {
    for(const player of room.players) {
      if(wave.contacts[player.id]||!player.touch.active) continue;
      wave.contacts[player.id]=classifyTogetherWaveContact(wave,{x:player.touch.x,y:player.touch.y},now);
    }
    const expired=now>=wave.startAt+wave.durationMs;
    if(wave.scored||(!expired&&!room.players.every(player=>wave.contacts[player.id]))) continue;
    const first=room.players[0],second=room.players[1];
    const result=togetherWaveResult(wave.contacts[first.id],wave.contacts[second.id]);
    room.sharedScore=Math.max(0,Math.min(100,room.sharedScore+result.delta));
    wave.scored=true;
    for(const player of room.players) send(player.ws,{type:'together_wave_result',waveId:wave.id,score:Math.round(room.sharedScore),delta:result.delta,cue:result.cue,outcome:wave.contacts[player.id]?.outcome||'miss'});
  }
}
const expireStaleTouches = (room, now) => {
  for(const player of room.players) {
    if(player.touch.active && now-player.touch.receivedAt>touchStaleMs) {
      player.touch={active:false}; recordScoringPosition(player.touchHistory,{timeMs:now,active:false},GAME.scoringTimelineMaxSamples); player.relaySeq++;
      broadcast(room,{type:'touch',playerId:player.id,active:false,serverTs:now,seq:player.relaySeq,stale:true});
    }
    if(room.mode==='HARMONY'&&(player.harmonyPoints?.length||player.harmonyPalm)&&now-player.harmonyReceivedAt>touchStaleMs) {
      player.harmonyPoints=[]; player.harmonyPalm=false; player.harmonyReceivedAt=0;
      room.players.filter(other=>other!==player).forEach(other=>send(other.ws,{type:'harmony_touches',playerId:player.id,seq:player.harmonySeq,serverTs:now,points:[],palm:false,trails:room.harmonyTrailsEnabled,stale:true}));
    }
  }
};
const score = (room) => {
  const now=performance.now(); expireStaleTouches(room,now);
  if(room.mode==='HARMONY') return;
  if(room.mode==='PUZZLE') {
    const wasActive=room.phase==='active';
    updatePuzzle(room,now,send);
    if(wasActive&&room.phase==='finished') {
      room.players.forEach(player=>presence.setInMatch(player.ws,false));
      void saveArcadePairRun(room,'PUZZLE',room.puzzle?.totalScore||0,room.puzzle?.level||1);
    }
    return;
  }
  if(room.mode==='COOP_SURVIVAL') {
    const finished=room.coopSurvival?.finished===true;
    updateCoopSurvival(room,now,broadcast,send);
    if(!finished&&room.coopSurvival?.finished) {
      room.phase='finished'; room.players.forEach(player=>presence.setInMatch(player.ws,false));
      const elapsedMs=Math.max(0,Math.round(now-(room.coopSurvival.startedAt||now)));
      void saveArcadePairRun(room,'COOP_SURVIVAL',elapsedMs,1+Math.floor(elapsedMs/20_000));
    }
    return;
  }
  updateTogetherWaves(room,now);
  const leader=room.players.find(p=>p.role==='LEADER'), follower=room.players.find(p=>p.role==='FOLLOWER');
  const scoringTime=now-GAME.scoringTimelineDelayMs;
  const leaderPoint=leader?sampleScoringPosition(leader.touchHistory,scoringTime,GAME.scoringTimelineMaxHoldMs):null;
  const followerPoint=follower?sampleScoringPosition(follower.touchHistory,scoringTime,GAME.scoringTimelineMaxHoldMs):null;
  const elapsed=Math.max(0,Math.min(GAME.connectionMaxStepSeconds,(now-(room.lastScoringAt||now))/1000));
  room.lastScoringAt=now;
  let distance=null;
  if (leaderPoint && followerPoint) {
    distance=Math.hypot(leaderPoint.x-followerPoint.x,leaderPoint.y-followerPoint.y);
    const previous=room.lastPositions;
    const leaderMove=previous ? Math.hypot(leaderPoint.x-previous.leaderX,leaderPoint.y-previous.leaderY) : 0;
    const followerMove=previous ? Math.hypot(followerPoint.x-previous.followerX,followerPoint.y-previous.followerY) : 0;
    const bothMoving=leaderMove>=GAME.connectionMovementThreshold && followerMove>=GAME.connectionMovementThreshold;
    if(room.mode==='CONNECTION') room.sharedScore=connectionScoreStep(room.sharedScore,distance,leaderMove>=GAME.connectionMovementThreshold,followerMove>=GAME.connectionMovementThreshold,elapsed);
    const sample={time:scoringTime,distance,leaderX:leaderPoint.x,leaderY:leaderPoint.y,followerX:followerPoint.x,followerY:followerPoint.y,bothMoving};
    room.samples.push(sample); if(room.matchSamples) room.matchSamples.push(sample);
    room.lastPositions={leaderX:leaderPoint.x,leaderY:leaderPoint.y,followerX:followerPoint.x,followerY:followerPoint.y};
  } else {
    if(room.mode==='CONNECTION') room.sharedScore=connectionScoreStep(room.sharedScore,1,false,false,elapsed);
    room.lastPositions=null;
  }
  if(room.mode!=='HARMONY' && now-room.lastMetricsAt>=GAME.scoreUpdateIntervalMs) {
    const generic=room.mode==='CONNECTION' ? roundResult(room.samples) : null;
    const duelResults=room.mode==='DEATHMATCH'?{
      LEADER:deathmatchRoundResult(room.samples,'LEADER'),
      FOLLOWER:deathmatchRoundResult(room.samples,'FOLLOWER')
    }:null;
    const liveScores=duelResults?Object.fromEntries(room.players.map(player=>[
      player.id,(room.scores[player.id]||0)+duelResults[player.role].score
    ])):null;
    for(const player of room.players) {
      const result=room.mode==='DEATHMATCH' ? duelResults[player.role] : generic;
      send(player.ws,{type:'live_score',round:room.round,mode:room.mode,playerId:player.id,role:player.role,
        score:room.mode==='CONNECTION'?Math.round(room.sharedScore):result.score,
        ...(liveScores?{scores:liveScores}:{})});
    }
    room.lastMetricsAt=now;
  }
};
function scheduleRoundEnd(room,delayMs) {
  clearTimeout(room.timer);
  room.roundDeadline=performance.now()+Math.max(0,delayMs);
  room.timer=setTimeout(()=>{ void finishTimedRound(room); },Math.max(0,delayMs));
}
async function finishTimedRound(room) {
  if(room.phase!=='active') return;
  if(room.mode==='DEATHMATCH') {
    const results=room.players.map(player=>({player,result:deathmatchRoundResult(room.samples,player.role)}));
    for(const {player,result} of results) room.scores[player.id]=(room.scores[player.id]||0)+result.score;
    for(const {player,result} of results) {
      send(player.ws,{type:'round_end',round:room.round,mode:room.mode,role:player.role,result:{score:result.score},followerId:room.players.find(p=>p.role==='FOLLOWER')?.id,scores:{...room.scores}});
    }
  } else {
    const result={...roundResult(room.samples),score:Math.round(room.sharedScore)};
    broadcast(room,{type:'round_end',round:room.round,mode:room.mode,result:{score:result.score}});
  }
  if(room.round>=room.matchHalves) {
    const [first,second]=room.players;
    const winnerId=room.mode==='DEATHMATCH' ? (room.scores[first.id]===room.scores[second.id] ? null : room.scores[first.id]>room.scores[second.id] ? first.id : second.id) : null;
    room.phase='finished';
    if(room.matchId && repository) {
      room.persistencePending=true;
      const result=roundResult(room.matchSamples||room.samples);
      await persistCompletedMatch({repository,match:{id:room.matchId,mode:room.mode,winnerProfileId:room.players.find(p=>p.id===winnerId)?.profileId||null,
          participants:room.players.map(p=>({profileId:p.profileId,score:room.scores[p.id]||0})),sharedScore:room.mode==='CONNECTION'?Math.round(room.sharedScore):undefined,
          alignmentPercent:result.alignment,syncSeconds:result.syncSeconds||0,longestSyncSeconds:result.longestSyncSeconds||0,durationSeconds:room.duration*room.matchHalves},
        onUnlocks:unlocks=>unlocks.forEach(unlocked=>presence.sendToProfile(unlocked.playerId,{type:'achievement_unlocked',code:unlocked.code,rivalId:unlocked.rivalId})),
        onError:error=>console.error('Could not persist completed match:',error.message),
        onFailure:reason=>broadcast(room,{type:'error',message:reason==='not_recorded'?'The match ended, but its profile result was not recorded.':'The match ended, but its profile result could not be saved.'})});
      room.persistencePending=false;
      room.players.forEach(p=>presence.setInMatch(p.ws,false));
    }
    if(room.mode==='DEATHMATCH') broadcast(room,{type:'match_end',mode:room.mode,scores:room.scores,winnerId});
  } else { room.players.reverse(); room.players.forEach((p,i)=>p.role=i===0?'LEADER':'FOLLOWER'); room.phase='ready'; }
}
function startRound(room) {
  room.round += 1;
  room.arcadeRunRecorded=false;
  room.samples=[]; room.matchSamples ||= []; room.sharedScore=0; room.lastPositions=null; room.ready.clear();
  room.lastMetricsAt=performance.now(); room.lastScoringAt=room.lastMetricsAt;
  room.togetherWaves=[]; room.nextTogetherWaveAt=room.lastScoringAt+8_000;
  room.decoys=[];
  room.players.forEach(player=>{
    player.touch={active:false}; player.touchHistory=[];
    player.harmonyPoints=[]; player.harmonyPalm=false; player.harmonyReceivedAt=0;
    player.abilitiesUsed={sonar:0,decoy:0};
  });
  room.roundStarted=performance.now();
  broadcast(room,{ type:'round_start', round:room.round, durationSec:room.mode==='HARMONY'?0:room.duration, matchHalves:room.matchHalves, mode:room.mode, roles: room.players.map(p=>({id:p.id,role:p.role})), scores:room.scores });
  if(room.mode==='PUZZLE') { startPuzzle(room,send); return; }
  if(room.mode==='COOP_SURVIVAL') { resetCoopSurvival(room,performance.now()); return; }
  if(room.mode==='HARMONY') return;
  scheduleRoundEnd(room,room.duration*1000);
}
function pauseRoomForReconnect(room,disconnectedPlayer) {
  const now=performance.now();
  if(room.phase!=='reconnecting') {
    room.reconnectReturnPhase=room.phase;
    const wasActive=room.phase==='active';
    room.phase='reconnecting';
    if(wasActive) {
          room.roundRemainingMs=['HARMONY','PUZZLE','COOP_SURVIVAL'].includes(room.mode)?0:Math.max(0,room.roundDeadline-now);
      clearTimeout(room.timer);
      room.lastPositions=null; room.lastScoringAt=now;
      room.togetherWaves=[]; room.nextTogetherWaveAt=now+8_000;
      for(const player of room.players) {
        player.touch={active:false}; player.touchHistory=[];
        player.harmonyPoints=[]; player.harmonyPalm=false; player.harmonyReceivedAt=0;
        if(player.ws) {
          player.relaySeq++;
          send(player.ws,{type:'touch',playerId:player.id,active:false,seq:player.relaySeq,serverTs:now});
          if(room.mode==='HARMONY') send(player.ws,{type:'harmony_touches',playerId:player.id,seq:player.harmonySeq,serverTs:now,points:[],palm:false,trails:room.harmonyTrailsEnabled,stale:true});
        }
      }
    }
  }
  disconnectedPlayer.connected=false;
  disconnectedPlayer.ws=null;
  const expiresAt=Date.now()+reconnectGraceMs;
  broadcast(room,{type:'peer_reconnecting',playerId:disconnectedPlayer.id,graceMs:reconnectGraceMs,remainingMs:room.roundRemainingMs,expiresAt});
  clearTimeout(disconnectedPlayer.reconnectTimer);
  disconnectedPlayer.reconnectTimer=setTimeout(()=>removeSessionPlayer(room,disconnectedPlayer,'disconnect'),reconnectGraceMs);
}
function removeSessionPlayer(room,player,reason='disconnect') {
  if(!room.players.includes(player)) return;
  if(room.harmonyConsentTimer) clearTimeout(room.harmonyConsentTimer);
  if(room.puzzle?.nextLevelTimer) clearTimeout(room.puzzle.nextLevelTimer);
  clearTimeout(player.reconnectTimer);
  const wasRoomHost=room.hostId===player.id;
  if(room.matchId&&repository&&room.phase!=='finished') {
    const matchId=room.matchId,pending=room.matchPersistencePromise||Promise.resolve();
    pending.catch(()=>{}).then(()=>repository.abandonMatch(matchId,reason)).catch(()=>{});
  }
  room.players=room.players.filter(candidate=>candidate!==player);
  broadcast(room,{type:'disconnect'});
  if(room.players.length===0) { clearTimeout(room.timer); clearTimeout(room.challengeTimer); rooms.delete(room.code); return; }
  clearTimeout(room.timer); room.harmonyConsentPending=false; room.harmonyHapticsEnabled=false; room.harmonyTrailsEnabled=false;
  if(wasRoomHost) { room.harmonyHostHaptics=false; room.harmonyHostTrails=false; }
  room.players.forEach(candidate=>{ clearTimeout(candidate.reconnectTimer); candidate.connected=true; presence.setInMatch(candidate.ws,false); });
  room.phase='waiting'; room.round=0; room.ready.clear(); room.rematch.clear(); room.scores={}; room.matchId=null; room.roundRemainingMs=0;
  room.hostId=room.players[0].id; room.players[0].role='LEADER';
}
wss.on('connection', ws => {
  ws.isAlive=true;
  ws.on('pong',()=>{ws.isAlive=true;});
  let room=null, player=null;
  ws.on('message', async bytes => {
    let m; try { m=JSON.parse(bytes.toString()); } catch { return send(ws,{type:'error',message:'Invalid message'}); }
    if(m.type==='profile_auth' && repository) {
      if(!databaseReady) return send(ws,{type:'error',message:'Online profiles are not available until the server database is ready.'});
      if(!allowWebSocketAuth(ws)) return send(ws,{type:'error',message:'Too many profile authentication attempts. Try again shortly.'});
      try { const profile=await repository.authenticate(m.profileId,m.token); bindAuthenticatedProfile(ws,profile,presence); send(ws,{type:'profile_authenticated',profileId:profile.id}); }
      catch { send(ws,{type:'profile_auth_failed'}); }
    } else if(m.type==='ping') {
      if(Number.isSafeInteger(m.clientTs)) send(ws,{type:'pong',clientTs:m.clientTs,serverTs:performance.now()});
    } else if(m.type==='resume') {
      const requestedRoom=rooms.get(String(m.code||'').toUpperCase());
      const requestedPlayer=requestedRoom?.players.find(candidate=>candidate.id===m.playerId);
      if(!requestedRoom||requestedRoom.phase!=='reconnecting'||!requestedPlayer||requestedPlayer.connected||!requestedPlayer.resumeToken||m.resumeToken!==requestedPlayer.resumeToken) {
        return send(ws,{type:'resume_rejected',message:'This session can no longer be resumed. Rejoin from the mode screen.'});
      }
      if(m.profileId&&m.token&&repository) {
        try {
          const profile=await repository.authenticate(m.profileId,m.token);
          if(profile.id!==requestedPlayer.profileId) return send(ws,{type:'resume_rejected',message:'Profile credentials do not match this session.'});
          bindAuthenticatedProfile(ws,profile,presence);
        } catch { return send(ws,{type:'resume_rejected',message:'Profile authentication failed while reconnecting.'}); }
      }
      room=requestedRoom; player=requestedPlayer; player.ws=ws; player.connected=true;
      clearTimeout(player.reconnectTimer); player.reconnectTimer=null;
      player.resumeToken=randomBytes(32).toString('hex');
      const waitingForPeer=room.players.some(candidate=>!candidate.connected);
      if(!waitingForPeer&&room.phase==='reconnecting') {
        const resumePhase=room.reconnectReturnPhase||'active';
        room.phase=resumePhase; room.reconnectReturnPhase=null;
        if(resumePhase==='active') {
          room.lastScoringAt=performance.now(); room.lastMetricsAt=room.lastScoringAt; room.lastPositions=null;
          room.players.forEach(candidate=>{candidate.touch={active:false};candidate.touchHistory=[];candidate.harmonyPoints=[];candidate.harmonyPalm=false;});
          if(room.mode==='CONNECTION') { room.togetherWaves=[]; room.nextTogetherWaveAt=room.lastScoringAt+8_000; }
          if(!['HARMONY','PUZZLE','COOP_SURVIVAL'].includes(room.mode)) scheduleRoundEnd(room,room.roundRemainingMs);
          room.players.forEach(candidate=>presence.setInMatch(candidate.ws,true));
        }
      }
      const remainingMs=room.mode==='HARMONY'||room.phase!=='active'?0:waitingForPeer?room.roundRemainingMs:Math.max(0,room.roundDeadline-performance.now());
      send(ws,{type:'session_resumed',code:room.code,playerId:player.id,role:player.role,mode:room.mode,round:room.round,phase:room.phase,
        durationSec:room.duration,matchHalves:room.matchHalves,scores:room.scores,remainingMs,waitingForPeer,
        abilitiesUsed:player.abilitiesUsed||{},resumeToken:player.resumeToken});
      room.players.filter(candidate=>candidate!==player&&candidate.connected).forEach(candidate=>send(candidate.ws,
        {type:'peer_reconnected',playerId:player.id,remainingMs,waitingForPeer}));
    } else if(m.type==='leave_session'&&room&&player) {
      player.voluntaryLeave=true; ws.close(1000,'left session');
    } else if (m.type==='host') {
      if(room) return send(ws,{type:'error',message:'This connection is already in a session.'});
      let profileId=null;
      if(m.profileId&&(!repository||!databaseReady)) return send(ws,{type:'error',message:'Online profiles are not enabled on this server. The game can still be played without a profile.'});
      if(m.profileId && m.token && repository) { if(!allowWebSocketAuth(ws)) return send(ws,{type:'error',message:'Too many profile authentication attempts. Try again shortly.'}); try { const profile=await repository.authenticate(m.profileId,m.token); profileId=profile.id; bindAuthenticatedProfile(ws,profile,presence); } catch { return send(ws,{type:'error',message:'Profile authentication failed. Update or sign in again.'}); } }
      const mode=({DUEL:'DEATHMATCH',DEATHMATCH:'DEATHMATCH',CONNECTION:'CONNECTION',HARMONY:'HARMONY',PUZZLE:'PUZZLE',COOP_SURVIVAL:'COOP_SURVIVAL'})[m.mode]||'DEATHMATCH';
      const requestedCode=String(m.code||'').toUpperCase(),reserved=rooms.get(requestedCode);
      if(reserved?.challengeId) {
        if(reserved.players.length||reserved.hostProfileId!==profileId) return send(ws,{type:'error',message:'This challenge session is reserved for another player.'});
        room=reserved;
      } else {
        let id; do id=code(); while(rooms.has(id));
        room={code:id,players:[],ready:new Set(),rematch:new Set(),scores:{},hostId:null,round:0,mode,matchHalves:mode==='DEATHMATCH'?(SERVER_CONFIG.challenges.allowedHalves.includes(Number(m.matchHalves))?Number(m.matchHalves):SERVER_CONFIG.challenges.defaultHalves):1,duration:SERVER_CONFIG.challenges.allowedDurationsSeconds.includes(Number(m.durationSec))?Number(m.durationSec):SERVER_CONFIG.challenges.defaultDurationSeconds,phase:'waiting',samples:[],lastMetricsAt:0,sharedScore:0,togetherWaves:[],togetherWaveSequence:0,nextTogetherWaveAt:Infinity,challengeId:null,harmonyHostHaptics:mode==='HARMONY'&&m.allowHarmonyHaptics===true,harmonyHostTrails:mode==='HARMONY'&&m.allowHarmonyTrails===true,harmonyConsentPending:false,harmonyHapticsEnabled:false,harmonyTrailsEnabled:false}; rooms.set(id,room);
      }
      room.harmonyHostHaptics=room.mode==='HARMONY'&&m.allowHarmonyHaptics===true;
      room.harmonyHostTrails=room.mode==='HARMONY'&&m.allowHarmonyTrails===true;
      room.harmonyConsentPending=false; room.harmonyHapticsEnabled=false; room.harmonyTrailsEnabled=false;
      player={ws,id:randomBytes(8).toString('hex'),profileId,role:'LEADER',seq:-1,harmonySeq:-1,harmonyPoints:[],harmonyPalm:false,harmonyReceivedAt:0,harmonyGestureSeq:-1,lastHarmonyGestureAt:0,relaySeq:0,touch:{active:false},touchHistory:[],connected:true,resumeToken:randomBytes(32).toString('hex'),abilitiesUsed:{sonar:0,decoy:0}}; room.players.push(player);
      if(room.challengeTimer&&!room.challengeId) clearTimeout(room.challengeTimer);
      room.hostId=player.id;
      send(ws,{type:'hosted',code:room.code,playerId:player.id,role:player.role,mode:room.mode,durationSec:room.duration,matchHalves:room.matchHalves,resumeToken:player.resumeToken});
    } else if (m.type==='join') {
      if(room) return send(ws,{type:'error',message:'This connection is already in a session.'});
      let profileId=null;
      if(m.profileId&&(!repository||!databaseReady)) return send(ws,{type:'error',message:'Online profiles are not enabled on this server. The game can still be played without a profile.'});
      if(m.profileId && m.token && repository) { if(!allowWebSocketAuth(ws)) return send(ws,{type:'error',message:'Too many profile authentication attempts. Try again shortly.'}); try { const profile=await repository.authenticate(m.profileId,m.token); profileId=profile.id; bindAuthenticatedProfile(ws,profile,presence); } catch { return send(ws,{type:'error',message:'Profile authentication failed. Update or sign in again.'}); } }
      room=rooms.get(String(m.code||'').toUpperCase());
      if(!room || room.players.length!==1) return send(ws,{type:'error',message:'Session not found or full'});
      if(profileId&&room.players.some(p=>p.profileId===profileId)) return send(ws,{type:'error',message:'A profile cannot occupy both seats in one session.'});
      if(room.challengeId && room.joinProfileId!==profileId) return send(ws,{type:'error',message:'This challenge session is reserved for its invited player.'});
      player={ws,id:randomBytes(8).toString('hex'),profileId,role:'FOLLOWER',seq:-1,harmonySeq:-1,harmonyPoints:[],harmonyPalm:false,harmonyReceivedAt:0,harmonyGestureSeq:-1,lastHarmonyGestureAt:0,relaySeq:0,touch:{active:false},touchHistory:[],connected:true,resumeToken:randomBytes(32).toString('hex'),abilitiesUsed:{sonar:0,decoy:0}}; room.players.push(player);
      if(room.challengeTimer) clearTimeout(room.challengeTimer);
      send(ws,{type:'joined',code:room.code,playerId:player.id,role:player.role,mode:room.mode,durationSec:room.duration,matchHalves:room.matchHalves,resumeToken:player.resumeToken});
      if(room.mode==='HARMONY'&&(room.harmonyHostHaptics||room.harmonyHostTrails)) {
        room.harmonyConsentPending=true;
        send(ws,{type:'harmony_haptics_consent_required',haptics:room.harmonyHostHaptics,trails:room.harmonyHostTrails});
        room.players.filter(other=>other!==player).forEach(other=>send(other.ws,{type:'harmony_haptics_consent_waiting'}));
        room.harmonyConsentTimer=setTimeout(()=>resolveHarmonyHapticsConsent(room,{}),harmonyConsentTimeoutMs);
      } else {
        if(room.mode==='HARMONY') { broadcast(room,{type:'harmony_haptics_state',enabled:false}); broadcast(room,{type:'harmony_trails_state',enabled:false}); }
        broadcast(room,{type:'ready'});
      }
    } else if(m.type==='harmony_haptics_consent'&&room&&player&&room.mode==='HARMONY'&&room.harmonyConsentPending&&player.role==='FOLLOWER') {
      resolveHarmonyHapticsConsent(room,{accepted:m.accepted===true,haptics:m.haptics===true,trails:m.trails===true});
    } else if(m.type==='harmony_haptics_revoke'&&room&&player&&room.mode==='HARMONY'&&room.phase==='active'&&room.harmonyHapticsEnabled) {
      room.harmonyHapticsEnabled=false; room.harmonyHostHaptics=false;
      room.players.forEach(p=>{ p.harmonyPalm=false; });
      broadcast(room,{type:'harmony_haptics_state',enabled:false,revoked:true});
    } else if(m.type==='harmony_trails_revoke'&&room&&player&&room.mode==='HARMONY'&&room.phase==='active'&&room.harmonyTrailsEnabled) {
      room.harmonyTrailsEnabled=false; room.harmonyHostTrails=false;
      broadcast(room,{type:'harmony_trails_state',enabled:false,revoked:true});
    } else if(m.type==='harmony_gesture'&&room&&player&&room.mode==='HARMONY'&&room.phase==='active'&&room.harmonyHapticsEnabled) {
      const seq=Number(m.seq),fingers=Number(m.fingers),now=performance.now();
      if(!['tap','anchor_start','anchor_end'].includes(m.gesture)||!Number.isSafeInteger(seq)||seq<=player.harmonyGestureSeq||![1,2].includes(fingers)||now-player.lastHarmonyGestureAt<GAME.harmonyGestureCooldownMs) return;
      player.harmonyGestureSeq=seq; player.lastHarmonyGestureAt=now;
      room.players.filter(other=>other!==player).forEach(other=>send(other.ws,{type:'harmony_haptic_event',playerId:player.id,gesture:m.gesture,fingers,serverTs:now}));
    } else if(m.type==='ready' && room && player && (room.phase==='waiting' || room.phase==='ready')) {
      if(room.harmonyConsentPending) return send(player.ws,{type:'error',message:'Choose whether to allow shared Harmony effects before continuing.'});
      room.ready.add(player.id); broadcast(room,{type:'waiting',ready:room.ready.size});
      if(room.ready.size===2) {
        room.phase='active';
        await prepareRound(room,{repository,presence,startRound,createMatchId:()=>randomBytes(16).toString('hex').replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/,'$1-$2-$3-$4-$5'),reportError:(session,error)=>{
          console.error('Could not persist match start:',error.message);
          broadcast(session,{type:'error',message:'Could not save this profile match. Please press Ready again to retry.'});
        }});
      }
    } else if(m.type==='rematch' && room && player && room.phase==='finished' && room.persistencePending) {
      send(player.ws,{type:'error',message:'The result is still being saved. Try rematching again in a moment.'});
    } else if(m.type==='rematch' && room && player && room.phase==='finished') {
      room.rematch.add(player.id);
      if(room.rematch.size<2) send(player.ws,{type:'rematch_waiting'});
      else {
        room.players.sort((a,b)=>a.id===room.hostId?-1:b.id===room.hostId?1:0);
        room.players.forEach((p,i)=>{p.role=i===0?'LEADER':'FOLLOWER';p.touch={active:false};});
        room.round=0; room.scores={}; room.samples=[]; room.ready.clear(); room.rematch.clear(); room.phase='waiting'; room.matchId=null;
        broadcast(room,{type:'rematch_ready'});
      }
    } else if(m.type==='sandbox_exit' && room && player && room.mode==='HARMONY' && room.phase==='active') {
      room.phase='finished';
      if(repository&&room.matchId) {
        room.persistencePending=true;
        const elapsed=Math.max(0,Math.round((Date.now()-(room.matchStartedAt?.getTime()||Date.now()))/1000));
        await persistCompletedMatch({repository,match:{id:room.matchId,mode:'HARMONY',participants:room.players.map(p=>({profileId:p.profileId,score:0})),durationSeconds:elapsed,syncSeconds:0,longestSyncSeconds:0},
          onError:error=>console.error('Could not persist Harmony session:',error.message),
          onFailure:reason=>broadcast(room,{type:'error',message:reason==='not_recorded'?'Harmony ended, but its profile session was not recorded.':'Harmony ended, but its profile session could not be saved.'})});
        room.persistencePending=false;
      }
      room.players.forEach(p=>presence.setInMatch(p.ws,false)); broadcast(room,{type:'sandbox_end'});
    } else if(m.type==='touch' && room && player && room.phase==='active') {
      const seq=Number(m.seq); if(!Number.isSafeInteger(seq) || seq<=player.seq) return;
      player.seq=seq;
      const now=performance.now(); let x=Math.max(0,Math.min(1,Number(m.x)||0)), y=Math.max(0,Math.min(1,Number(m.y)||0)), speedLimited=false;
      if(m.active && Number.isFinite(m.x) && Number.isFinite(m.y)) {
        const old=player.touch;
        if(room.mode==='DEATHMATCH' && player.role==='LEADER' && old.active) {
          const limited=clampLeaderPoint(old,{x,y},now-old.receivedAt);
          x=limited.x; y=limited.y; speedLimited=limited.limited;
        }
        if(room.mode==='PUZZLE'&&old.active) {
          const limited=clampLeaderPoint(old,{x,y},now-old.receivedAt,.9);
          x=limited.x; y=limited.y; speedLimited=limited.limited;
        }
        if(room.mode==='COOP_SURVIVAL'&&old.active) {
          const limited=clampLeaderPoint(old,{x,y},now-old.receivedAt,2.2);
          x=limited.x; y=limited.y; speedLimited=limited.limited;
        }
        if(room.mode==='COOP_SURVIVAL') {
          const elapsedMs=old.active?Math.max(1,now-old.receivedAt):1;
          const speed=old.active?Math.hypot(x-old.x,y-old.y)*1000/elapsedMs:0;
          player.survivalMotionFactor=speed<.015?0:Math.max(0,Math.min(1,(speed-.015)/.12));
          player.survivalMotionAt=now;
        }
        player.touch={active:true,x,y,receivedAt:now};
        player.lastPoint={x,y};
        recordScoringPosition(player.touchHistory,{timeMs:now,active:true,x,y},GAME.scoringTimelineMaxSamples);
        if(room.mode==='PUZZLE') {
          const deltaMs=old.active?Math.max(1,now-old.receivedAt):1;
          const speed=old.active?Math.hypot(x-old.x,y-old.y)*1000/deltaMs:0;
          const movementFactor=speed<.02?0:Math.max(0,Math.min(1,(speed-.02)/.08));
          recordPuzzleTouch(room,player,old.active?{x:old.x,y:old.y}:null,{x,y},true,now,send,movementFactor);
        }
      } else {
        const old=player.touch; player.touch={active:false}; recordScoringPosition(player.touchHistory,{timeMs:now,active:false},GAME.scoringTimelineMaxSamples);
        if(room.mode==='PUZZLE') recordPuzzleTouch(room,player,old.active?{x:old.x,y:old.y}:null,null,false,now,send);
      }
      player.relaySeq++;
      const update={type:'touch',playerId:player.id,active:player.touch.active,x:player.touch.x,y:player.touch.y,serverTs:now,seq:player.relaySeq,speedLimited};
      send(player.ws,update);
      if(!['PUZZLE','COOP_SURVIVAL'].includes(room.mode)) room.players.filter(p=>p!==player).forEach(p=>send(p.ws,update));
    } else if(m.type==='harmony_touches' && room && player && room.phase==='active' && room.mode==='HARMONY') {
      const seq=Number(m.seq),points=m.points;
      if(!Number.isSafeInteger(seq)||seq<=player.harmonySeq||!Array.isArray(points)||points.length>GAME.maxHarmonyTouches) return;
      const ids=new Set();
      const valid=points.every(point=>point&&Number.isInteger(point.id)&&point.id>=0&&point.id<=31&&!ids.has(point.id)&&
        Number.isFinite(point.x)&&point.x>=0&&point.x<=1&&Number.isFinite(point.y)&&point.y>=0&&point.y<=1&&
        (point.sizeClass===undefined||(Number.isInteger(point.sizeClass)&&point.sizeClass>=0&&point.sizeClass<=2))&&ids.add(point.id));
      if(!valid) return;
      player.harmonySeq=seq;
      player.harmonyPoints=points.map(({id,x,y,sizeClass=1})=>({id,x,y,sizeClass})); player.harmonyPalm=room.harmonyHapticsEnabled&&player.harmonyPoints.length>0&&m.palm===true; player.harmonyReceivedAt=performance.now();
      const update={type:'harmony_touches',playerId:player.id,seq,serverTs:player.harmonyReceivedAt,points:player.harmonyPoints,palm:room.harmonyHapticsEnabled&&player.harmonyPalm,trails:room.harmonyTrailsEnabled};
      room.players.filter(p=>p!==player).forEach(p=>send(p.ws,update));
    } else if(m.type==='ability' && room && player && room.phase==='active' && room.mode==='DEATHMATCH') {
      const ability=String(m.ability||'');
      const expected=player.role==='FOLLOWER'?'sonar':'decoy';
      const uses=player.abilitiesUsed || (player.abilitiesUsed={sonar:0,decoy:0});
      const remaining=GAME.abilityUsesPerRound-(uses[expected]||0);
      const reject=reason=>send(player.ws,{type:'ability_result',ability,accepted:false,remaining:Math.max(0,remaining),reason});
      if(ability!==expected) return reject('role');
      if(remaining<=0) return reject('depleted');
      if(ability==='sonar') {
        const leader=room.players.find(p=>p.role==='LEADER');
        if(!leader?.touch.active) return reject('no_contact');
        uses.sonar++;
        const decoy=room.decoys?.shift();
        const revealDurationMs=Math.max(GAME.sonarDurationMs,GAME.decoyDurationMs);
        send(player.ws,{type:'ability_result',ability,accepted:true,remaining:GAME.abilityUsesPerRound-uses.sonar,x:leader.touch.x,y:leader.touch.y,
          ...(decoy?{decoyX:decoy.x,decoyY:decoy.y}:{}),durationMs:revealDurationMs});
      } else {
        if(!Number.isFinite(m.x)||!Number.isFinite(m.y)) return reject('invalid_point');
        const x=Math.max(0,Math.min(1,m.x)), y=Math.max(0,Math.min(1,m.y));
        uses.decoy++;
        room.decoys ||= [];
        room.decoys.push({x,y});
        send(player.ws,{type:'ability_result',ability,accepted:true,remaining:GAME.abilityUsesPerRound-uses.decoy});
      }
    }
  });
  ws.on('close',()=>{
    if(ws.profileId) presence.remove(ws);
    if(room && player) {
      if(player.voluntaryLeave) return removeSessionPlayer(room,player,'disconnect');
      if(room.phase==='active'||room.phase==='reconnecting'||room.phase==='waiting'||room.phase==='ready') return pauseRoomForReconnect(room,player);
      removeSessionPlayer(room,player,'disconnect');
    }
  });
});
setInterval(()=>rooms.forEach(room=>{ if(room.phase==='active') score(room); }),100);
const websocketHeartbeat=setInterval(()=>wss.clients.forEach(ws=>{
  if(ws.readyState!==1) return;
  if(!ws.isAlive) { ws.terminate(); return; }
  ws.isAlive=false; ws.ping();
}),30_000);
websocketHeartbeat.unref();

const api=createApiServer({repository,presence,isDatabaseReady:()=>databaseReady,
  onChallengeAccepted:async challenge=>{
    let id; do id=code(); while(rooms.has(id));
    const room={code:id,players:[],ready:new Set(),rematch:new Set(),scores:{},hostId:null,round:0,mode:challenge.mode,matchHalves:challenge.mode==='DEATHMATCH'?challenge.halves:1,duration:challenge.durationSeconds,phase:'waiting',samples:[],lastMetricsAt:0,sharedScore:0,togetherWaves:[],togetherWaveSequence:0,nextTogetherWaveAt:Infinity,challengeId:challenge.id,hostProfileId:challenge.toPlayerId,joinProfileId:challenge.fromPlayerId};
    room.challengeTimer=setTimeout(()=>{
      if(!canExpireChallengeRoom(room)) return;
      broadcast(room,{type:'challenge_expired'});
      for(const player of room.players) player.ws.close(4002,'Challenge room expired');
      rooms.delete(id);
    },SERVER_CONFIG.challenges.lifetimeMs);
    rooms.set(id,room); return {code:id};
  },
  onChallengeCancelled:codeOrPlayer=>{
    const room=rooms.get(codeOrPlayer); if(room&&!room.players.length) rooms.delete(codeOrPlayer);
    for(const room of rooms.values()) if((room.challengeId===codeOrPlayer||room.hostProfileId===codeOrPlayer||room.joinProfileId===codeOrPlayer)&&room.phase==='waiting'&&!room.players.length) rooms.delete(room.code);
  }
});
const server=api;
server.on('upgrade',(request,socket,head)=>{
  if(!['/','/ws'].includes(new URL(request.url,'http://localhost').pathname)) { socket.destroy(); return; }
  wss.handleUpgrade(request,socket,head,ws=>wss.emit('connection',ws,request));
});
async function start() {
  if(pool) {
    try { await migrateDatabase(pool); await repository.abandonInterruptedMatches(); databaseReady=true; console.log('PsychConnect profile database migrations ready'); }
    catch(error) { console.error('Profile database unavailable; multiplayer will start without profile persistence:',error.message); }
  }
  server.listen(port,()=>console.log(`PsychConnect server listening on :${port}`));
}
start().catch(error=>{ console.error('PsychConnect server startup failed:',error.message); process.exitCode=1; });
