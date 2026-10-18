import http from 'node:http';
import { URL } from 'node:url';
import { SERVER_CONFIG } from '../config.js';
import { createRequestRateLimiter } from './rateLimit.js';
import { createTtlCache } from './ttlCache.js';

const json=(response,status,payload)=>{
  response.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store','x-content-type-options':'nosniff'});
  response.end(JSON.stringify(payload));
};
const input=async request=>{
  const chunks=[]; let size=0;
  for await(const chunk of request) {
    const bytes=Buffer.isBuffer(chunk)?chunk:Buffer.from(chunk);
    size+=bytes.length;
    if(size>SERVER_CONFIG.api.requestBodyLimitBytes) throw Object.assign(new Error('Request body is too large.'),{status:413});
    chunks.push(bytes);
  }
  const data=Buffer.concat(chunks).toString('utf8');
  if(!data) return {};
  let parsed;
  try { parsed=JSON.parse(data); } catch { throw Object.assign(new Error('Invalid JSON body.'),{status:400}); }
  if(!parsed||typeof parsed!=='object'||Array.isArray(parsed)) throw Object.assign(new Error('Request body must be a JSON object.'),{status:400});
  return parsed;
};
export function createApiServer({repository,presence,onChallengeAccepted,onChallengeCancelled,isDatabaseReady=()=>Boolean(repository)}) {
  const limiter=createRequestRateLimiter(SERVER_CONFIG.api.rateLimit);
  const leaderboardCache=createTtlCache({ttlMs:15_000,maxEntries:500});
  async function handler(request,response) {
    const url=new URL(request.url,'http://localhost');
    if(request.method==='GET'&&url.pathname==='/health') return json(response,200,{status:'ok',profilesEnabled:Boolean(repository)&&isDatabaseReady()});
    if(!url.pathname.startsWith('/api/')) return json(response,404,{error:'Not found.'});
    const profileCreate=request.method==='POST'&&url.pathname==='/api/profiles';
    const profileAuth=request.method==='POST'&&['/api/profile/auth','/api/profile/recover'].includes(url.pathname);
    const rateBucket=profileCreate?'profileCreate':profileAuth?'profileAuth':'general';
    if(limiter.limited(request,rateBucket)) return json(response,429,{error:'Too many requests. Please wait and try again.'});
    if(!repository||!isDatabaseReady()) return json(response,503,{error:'Online profiles require a configured and migrated DATABASE_URL on the game server.'});
    try {
      const body=['POST','PATCH','PUT'].includes(request.method)?await input(request):{};
      if(request.method==='POST'&&url.pathname==='/api/profiles') return json(response,201,await repository.createProfile(body.nickname,body.avatarId??0));
      if(request.method==='POST'&&url.pathname==='/api/profile/auth') return json(response,200,{profile:await repository.authenticate(body.profileId,body.token)});
      if(request.method==='POST'&&url.pathname==='/api/profile/recover') return json(response,200,await repository.recover(body.profileCode,body.recoveryCode));
      const publicProfile=url.pathname.match(/^\/api\/profiles\/([A-Z0-9-]{5,16})$/i);
      if(request.method==='GET'&&publicProfile) {
        const profile=await repository.publicProfile(publicProfile[1]);
        if(!profile) return json(response,404,{error:'Profile not found.'});
        delete profile.presenceHidden; delete profile.challengesEnabled;
        return json(response,200,{profile});
      }
      const identity=await authenticateRequest(request,repository);
      if(request.method==='GET'&&url.pathname==='/api/profile') return json(response,200,{profile:await repository.profile(identity.id)});
      if(request.method==='GET'&&url.pathname==='/api/matches') return json(response,200,{matches:await repository.matchHistory(identity.id,Number(url.searchParams.get('limit')||50))});
      if(request.method==='PATCH'&&url.pathname==='/api/profile') {
        const profile=await repository.updateProfile(identity.id,body); presence.updateProfile(profile); leaderboardCache.clear(); return json(response,200,{profile});
      }
      if(request.method==='PATCH'&&url.pathname==='/api/profile/privacy') {
        const profile=await repository.updatePrivacy(identity.id,body); presence.updateProfile(profile); return json(response,200,{profile});
      }
      if(request.method==='DELETE'&&url.pathname==='/api/profile') {
        // Keep the live profile intact if the database deletion fails. Dependent
        // challenge rows are removed by the profile foreign-key cascades.
        const result=await repository.deleteProfile(identity.id);
        presence.removeProfile(identity.id); await onChallengeCancelled?.(identity.id);
        leaderboardCache.clear(); return json(response,200,result);
      }
      if(request.method==='GET'&&url.pathname==='/api/leaderboards') {
        const mode=url.searchParams.get('mode')||'DEATHMATCH',range=url.searchParams.get('range')||'all_time';
        if(!['DEATHMATCH','CONNECTION','COOP_SURVIVAL','PUZZLE','SOLO_SURVIVAL'].includes(mode)||!['all_time','weekly'].includes(range)) return json(response,400,{error:'Unsupported leaderboard.'});
        if(mode!=='DEATHMATCH'&&range==='weekly') return json(response,400,{error:'This leaderboard is all-time.'});
        const key=`${mode}:${range}:${identity.id}`;
        const cached=url.searchParams.get('refresh')==='true'?undefined:leaderboardCache.get(key);
        if(cached) return json(response,200,cached);
        const result=await repository.leaderboard(mode,range,identity.id);
        leaderboardCache.set(key,result);
        return json(response,200,result);
      }
      if(request.method==='POST'&&url.pathname==='/api/leaderboards/solo-survival') {
        const score=Number(body.scoreMs);
        if(!Number.isSafeInteger(score)||score<0||score>86_400_000) return json(response,400,{error:'Survival time is invalid.'});
        const saved=await repository.recordSoloSurvival({id:body.runId,playerId:identity.id,score});
        if(!saved) return json(response,409,{error:'This run was already submitted or is invalid.'});
        leaderboardCache.clear();
        return json(response,201,{saved:true});
      }
      if(request.method==='GET'&&url.pathname==='/api/rivals') {
        const data=await repository.rivals(identity.id);
        data.entries=data.entries.map(rival=>({...rival,presence:presence.stateFor(rival.id)}));
        return json(response,200,data);
      }
      if(request.method==='GET'&&url.pathname==='/api/relationships') {
        const data=await repository.relationships(identity.id);
        data.friends=data.friends.map(player=>({...player,presence:presence.stateFor(player.id)}));
        return json(response,200,data);
      }
      const rivalRoute=url.pathname.match(/^\/api\/rivals\/([0-9a-f-]{36})$/i);
      if(request.method==='GET'&&rivalRoute) {
        const data=await repository.rivals(identity.id),rival=data.entries.find(entry=>entry.id===rivalRoute[1]);
        return rival?json(response,200,{rival:{...rival,presence:presence.stateFor(rival.id)}}):json(response,404,{error:'Rival not found.'});
      }
      if(request.method==='GET'&&url.pathname==='/api/achievements') return json(response,200,{achievements:await repository.achievements(identity.id)});
      if(request.method==='GET'&&url.pathname==='/api/challenges') return json(response,200,{challenges:await repository.challengeInbox(identity.id)});
      if(request.method==='POST'&&url.pathname==='/api/challenges') {
        const rivalId=String(body.rivalId||'');
        const state=presence.challengeStateFor(rivalId);
        if(state==='Offline') throw Object.assign(new Error('This rival is offline.'),{status:409});
        if(state==='In Match') throw Object.assign(new Error('This rival is already in a match.'),{status:409});
        if(state==='Unavailable') throw Object.assign(new Error('This rival is not accepting challenges.'),{status:403});
        const challenge=await repository.createChallenge(identity.id,rivalId,body);
        const profile=await repository.profile(identity.id);
        presence.sendToProfile(rivalId,{type:'challenge_received',challenge:{id:challenge.id,mode:challenge.mode,durationSeconds:challenge.durationSeconds,halves:challenge.halves,expiresAt:challenge.expiresAt,challenger:{id:identity.id,nickname:profile.nickname,avatarId:profile.avatarId}}});
        return json(response,201,{challenge});
      }
      const responseChallenge=url.pathname.match(/^\/api\/challenges\/([0-9a-f-]{36})\/respond$/i);
      if(request.method==='POST'&&responseChallenge) {
        if(typeof body.accept!=='boolean') throw Object.assign(new Error('Accept must be true or false.'),{status:400});
        const accept=body.accept;
        let roomInfo=null;
        if(accept) {
          const challenge=await repository.pendingChallenge(responseChallenge[1],identity.id);
          if(!presence.isOnline(identity.id)) throw Object.assign(new Error('Open PsychConnect to accept this challenge.'),{status:409});
          if(presence.isInMatch(identity.id)) throw Object.assign(new Error('Finish your current match before accepting a challenge.'),{status:409});
          if(!presence.isOnline(challenge.fromPlayerId)) throw Object.assign(new Error('The challenger is offline. Ask them to send another invitation when they are online.'),{status:409});
          if(presence.isInMatch(challenge.fromPlayerId)) throw Object.assign(new Error('The challenger is already in a match.'),{status:409});
          roomInfo=await onChallengeAccepted({...challenge,profileId:identity.id});
        }
        try {
          const result=await repository.respondChallenge(responseChallenge[1],identity.id,accept,roomInfo?.code);
          if(accept) {
            presence.sendToProfile(result.fromPlayerId,{type:'challenge_accepted',challengeId:result.id,code:result.roomCode,mode:result.mode,durationSec:result.durationSeconds,matchHalves:result.halves});
            return json(response,200,{challenge:result,role:'LEADER'});
          }
          presence.sendToProfile(result.fromPlayerId,{type:'challenge_declined',challengeId:result.id});
          return json(response,200,{challenge:result});
        } catch(error) { if(roomInfo) onChallengeCancelled?.(roomInfo.code); throw error; }
      }
      return json(response,404,{error:'Not found.'});
    } catch(error) { return json(response,error.status||500,{error:error.status?error.message:'The request could not be completed.'}); }
  }
  return http.createServer(handler);
}

async function authenticateRequest(request,repository) {
  const [scheme,token]=String(request.headers.authorization||'').split(' ');
  const id=String(request.headers['x-profile-id']||'');
  if(scheme!=='Bearer'||!token||!id) throw Object.assign(new Error('Sign in to your profile first.'),{status:401});
  return repository.authenticate(id,token);
}
