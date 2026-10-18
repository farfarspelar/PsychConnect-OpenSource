import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { DataType,newDb } from 'pg-mem';
import { migrateDatabase } from '../db/database.js';
import { createRepository } from '../db/repository.js';
import { createRecoveryCode,normalizeNickname,normalizeRecoveryCode,verifySecret } from '../domain/identity.js';
import { eloPair,utcWeekStart } from '../domain/rating.js';
import { nextRivalState,achievementsForResult,rivalCategories } from '../domain/rivalries.js';
import { canExpireChallengeRoom } from '../domain/challenges.js';

async function testRepository() {
  const mem=newDb({autoCreateForeignKeyIndices:true});
  mem.public.registerFunction({name:'least',args:[DataType.uuid,DataType.uuid],returns:DataType.uuid,implementation:(a,b)=>String(a)<=String(b)?a:b});
  mem.public.registerFunction({name:'greatest',args:[DataType.uuid,DataType.uuid],returns:DataType.uuid,implementation:(a,b)=>String(a)>=String(b)?a:b});
  const {Pool}=mem.adapters.createPg();
  const pool=new Pool();
  await migrateDatabase(pool);
  return {pool,repo:createRepository(pool,{clock:()=>new Date('2026-10-17T12:00:00.000Z')})};
}

test('nickname validation, secure secret checks, Elo and UTC week boundaries',async()=>{
  assert.equal(normalizeNickname('  Åsa   Lind '),'Åsa Lind');
  assert.throws(()=>normalizeNickname('<admin>'));
  const elo=eloPair(1000,1000,1); assert.equal(elo.ratingA,1016); assert.equal(elo.ratingB,984);
  assert.equal(eloPair(1000,1000,.5).ratingA,1000);
  assert.equal(utcWeekStart(new Date('2026-10-18T23:00:00Z')).toISOString(),'2026-10-12T00:00:00.000Z');
  assert.equal(await verifySecret('wrong','salt','not-a-hash'),false);
  for(let i=0;i<32;i++) {
    const recovery=createRecoveryCode();
    assert.match(recovery,/^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){3}$/);
    assert.equal(normalizeRecoveryCode(recovery).length,16);
  }
});

test('profile creation, token authentication, recovery rotation, privacy and deletion',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const created=await repo.createProfile('Player One',3);
  assert.match(created.profile.profileCode,/^[A-Z2-9]{4}-[A-Z2-9]{4}$/);
  assert.equal((await repo.authenticate(created.profile.id,created.credentials.token)).nickname,'Player One');
  await assert.rejects(repo.authenticate(created.profile.id,'incorrect'),{status:401});
  const restored=await repo.recover(created.profile.profileCode,created.credentials.recoveryCode.toLowerCase());
  assert.equal(restored.profile.id,created.profile.id);
  await assert.rejects(repo.authenticate(created.profile.id,created.credentials.token),{status:401});
  assert.equal((await repo.authenticate(created.profile.id,restored.credentials.token)).nickname,'Player One');
  await assert.rejects(repo.recover(created.profile.profileCode,created.credentials.recoveryCode),{status:401});
  assert.equal((await repo.updateProfile(created.profile.id,{nickname:'New Name',avatarId:4})).nickname,'New Name');
  await repo.updatePrivacy(created.profile.id,{presenceHidden:true,challengesEnabled:false});
  await repo.updatePrivacy(created.profile.id,{presenceHidden:false});
  assert.equal((await repo.profile(created.profile.id)).presenceHidden,false);
  assert.equal((await repo.profile(created.profile.id)).challengesEnabled,false);
  await repo.updatePrivacy(created.profile.id,{challengesEnabled:true});
  await assert.rejects(repo.updatePrivacy(created.profile.id,{challengesEnabled:'false'}),{status:400});
  assert.equal((await repo.profile(created.profile.id)).challengesEnabled,true);
  await repo.deleteProfile(created.profile.id);
  await assert.rejects(repo.profile(created.profile.id),{status:404});
});

test('recovery code can rotate credentials only once when two restores race',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const created=await repo.createProfile('Recovery Race');
  const attempts=await Promise.allSettled([
    repo.recover(created.profile.profileCode,created.credentials.recoveryCode),
    repo.recover(created.profile.profileCode,created.credentials.recoveryCode)
  ]);
  const succeeded=attempts.filter(attempt=>attempt.status==='fulfilled');
  const failed=attempts.filter(attempt=>attempt.status==='rejected');
  assert.equal(succeeded.length,1);
  assert.equal(failed.length,1);
  assert.equal(failed[0].reason.status,401);
  assert.equal((await repo.authenticate(created.profile.id,succeeded[0].value.credentials.token)).id,created.profile.id);
});

test('verified match storage is idempotent, updates Elo, rivals, achievements and leaderboards',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const a=await repo.createProfile('Alpha'),b=await repo.createProfile('Beta');
  const run=async(winner,index)=>{
    const id=randomUUID();
    await repo.beginMatch({id,mode:'DEATHMATCH',startedAt:new Date('2026-10-17T11:00:00Z'),durationSeconds:60,halves:2,participants:[{profileId:a.profile.id,role:'LEADER'},{profileId:b.profile.id,role:'FOLLOWER'}]});
    const result=await repo.completeMatch({id,mode:'DEATHMATCH',winnerProfileId:winner,participants:[{profileId:a.profile.id,role:'LEADER',score:winner===a.profile.id?90:10},{profileId:b.profile.id,role:'FOLLOWER',score:winner===b.profile.id?90:10}],syncSeconds:index,longestSyncSeconds:1});
    assert.equal(result.recorded,true);
    assert.equal((await repo.completeMatch({id,mode:'DEATHMATCH',winnerProfileId:winner,participants:[]})).recorded,false);
    return result;
  };
  const first=await run(a.profile.id,1); assert.ok(first.unlocks.some(unlock=>unlock.code==='first_encounter'));
  for(let i=0;i<4;i++) await run(i%2===0?b.profile.id:a.profile.id,i+2);
  const afterFive=await repo.rivals(a.profile.id); assert.equal(afterFive.entries[0].matches,5);
  const tail=[b.profile.id,b.profile.id,b.profile.id,a.profile.id,a.profile.id,b.profile.id,a.profile.id,b.profile.id,a.profile.id];
  for(let i=0;i<tail.length;i++) await run(tail[i],i+6);
  const rivalData=await repo.rivals(a.profile.id); assert.equal(rivalData.entries[0].matches,14);
  assert.ok(rivalData.entries[0].myWins>0); assert.ok(rivalData.categories.mostPlayed);
  const own=await repo.profile(a.profile.id); assert.equal(own.stats.totalMatches,14); assert.ok(own.stats.rating>1000);
  const unlocked=await repo.achievements(a.profile.id); assert.ok(unlocked.some(item=>item.code==='nemesis')); assert.ok(unlocked.some(item=>item.code==='comeback'));
  const allTime=await repo.leaderboard('DEATHMATCH','all_time',a.profile.id); assert.equal(allTime.me.id,a.profile.id);
  const weekly=await repo.leaderboard('DEATHMATCH','weekly',a.profile.id); assert.equal(weekly.weekStartsAt,'2026-10-12T00:00:00.000Z');
});

test('Friends grouping uses only completed Together and Harmony matches',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const a=await repo.createProfile('Together Player'),b=await repo.createProfile('Play Partner'),c=await repo.createProfile('Versus Only');
  const add=async(mode,other,index)=>{
    const id=randomUUID(),at=new Date(Date.UTC(2026,9,17,12,index));
    await pool.query("INSERT INTO matches(id,mode,status,started_at,completed_at) VALUES($1,$2,'completed',$3,$3)",[id,mode,at]);
    await pool.query("INSERT INTO match_participants(match_id,player_id,role,result) VALUES($1,$2,'PLAYER','coop'),($1,$3,'PLAYER','coop')",[id,a.profile.id,other.profile.id]);
  };
  await add('CONNECTION',b,1); await add('CONNECTION',b,2); await add('HARMONY',b,3);
  await add('DEATHMATCH',c,4);
  const {friends}=await repo.relationships(a.profile.id);
  assert.equal(friends.length,1);
  assert.equal(friends[0].nickname,'Play Partner');
  assert.equal(friends[0].togetherMatches,2);
  assert.equal(friends[0].harmonySessions,1);
  assert.equal(friends[0].lastPlayedAt.toISOString(),'2026-10-17T12:03:00.000Z');
});

test('leaderboard returns nearby positions for a player outside the top 100',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  let targetId='';
  for(let index=1;index<=108;index++) {
    const id=`00000000-0000-0000-0000-${String(index).padStart(12,'0')}`;
    const rating=index===105?1095:1200-index;
    await pool.query('INSERT INTO players(id,public_code,nickname) VALUES($1,$2,$3)',[id,`TEST${String(index).padStart(4,'0')}`,`Player ${index}`]);
    await pool.query('INSERT INTO player_stats(player_id,rating,versus_wins) VALUES($1,$2,1)',[id,rating]);
    if(index===105) targetId=id;
  }
  const result=await repo.leaderboard('DEATHMATCH','all_time',targetId);
  assert.equal(result.me.rank,105);
  assert.deepEqual(result.nearby.map(entry=>entry.rank),[102,103,104,105,106,107,108]);
  assert.equal(result.nearby.find(entry=>entry.id===targetId).nickname,'Player 105');
  assert.equal(result.entries.length,100);
});

test('weekly leaderboard ranks only players with a verified match this UTC week',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const winner=await repo.createProfile('WeeklyWinner'),loser=await repo.createProfile('WeeklyLoser'),inactive=await repo.createProfile('InactiveHighRating');
  await pool.query('UPDATE player_stats SET rating=2200 WHERE player_id=$1',[inactive.profile.id]);
  const id=randomUUID();
  await repo.beginMatch({id,mode:'DEATHMATCH',startedAt:new Date('2026-10-17T11:00:00Z'),durationSeconds:60,halves:2,participants:[{profileId:winner.profile.id},{profileId:loser.profile.id}]});
  await repo.completeMatch({id,mode:'DEATHMATCH',winnerProfileId:winner.profile.id,participants:[{profileId:winner.profile.id,score:20},{profileId:loser.profile.id,score:10}],syncSeconds:0,longestSyncSeconds:0,durationSeconds:60});

  const activeBoard=await repo.leaderboard('DEATHMATCH','weekly',loser.profile.id);
  assert.equal(activeBoard.me.rank,2);
  assert.deepEqual(activeBoard.entries.map(entry=>entry.id),[winner.profile.id,loser.profile.id]);
  const inactiveBoard=await repo.leaderboard('DEATHMATCH','weekly',inactive.profile.id);
  assert.equal(inactiveBoard.me,null);
  assert.equal(inactiveBoard.entries.length,2);
});

test('all-time Versus leaderboard excludes profiles without a completed Versus match',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const fresh=await repo.createProfile('Fresh Profile');
  await pool.query('UPDATE player_stats SET rating=1800 WHERE player_id=$1',[fresh.profile.id]);
  const board=await repo.leaderboard('DEATHMATCH','all_time',fresh.profile.id);
  assert.equal(board.me,null);
  assert.equal(board.entries.length,0);
  assert.equal(board.nearby.length,0);
});

test('match persistence rejects participants, mode, or winner that do not match the active record',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const a=await repo.createProfile('MatchOwner'),b=await repo.createProfile('Opponent'),other=await repo.createProfile('OtherPlayer');
  const start=async id=>repo.beginMatch({id,mode:'DEATHMATCH',startedAt:new Date(),durationSeconds:60,halves:2,participants:[{profileId:a.profile.id},{profileId:b.profile.id}]});
  const finish=overrides=>repo.completeMatch({id:overrides.id,mode:'DEATHMATCH',winnerProfileId:a.profile.id,participants:[{profileId:a.profile.id,score:10},{profileId:b.profile.id,score:0}],syncSeconds:0,longestSyncSeconds:0,durationSeconds:60,...overrides});

  const wrongPlayers=randomUUID(); await start(wrongPlayers);
  assert.equal((await finish({id:wrongPlayers,participants:[{profileId:a.profile.id,score:10},{profileId:other.profile.id,score:0}]})).recorded,false);
  const wrongMode=randomUUID(); await start(wrongMode);
  assert.equal((await finish({id:wrongMode,mode:'CONNECTION'})).recorded,false);
  const wrongWinner=randomUUID(); await start(wrongWinner);
  assert.equal((await finish({id:wrongWinner,winnerProfileId:b.profile.id})).recorded,false);

  assert.equal((await repo.profile(a.profile.id)).stats.totalMatches,0);
  assert.equal((await repo.profile(b.profile.id)).stats.totalMatches,0);
  assert.equal((await repo.profile(other.profile.id)).stats.totalMatches,0);
  const statuses=await pool.query('SELECT status,abandoned_reason FROM matches ORDER BY id');
  assert.equal(statuses.rows.length,3);
  assert.ok(statuses.rows.every(row=>row.status==='abandoned'&&row.abandoned_reason==='invalid_result'));
});

test('rival streak achievements use deterministic per-pair context',()=>{
  const low='00000000-0000-0000-0000-000000000001',high='00000000-0000-0000-0000-000000000002';
  let state=null;
  for(let i=0;i<3;i++) state=nextRivalState(state,high,low,high);
  const next=nextRivalState(state,low,low,high);
  assert.ok(achievementsForResult({playerId:low,rivalId:high,previous:state,next,winnerId:low}).includes('comeback'));
});

test('rival categories ignore one-off nemesis results and include the active leader streak',()=>{
  const oneOff={id:'a',nickname:'OneOff',matches:1,myWins:0,theirWins:1,currentStreak:-1,lastEncounterAt:'2026-10-01T00:00:00Z'};
  assert.equal(rivalCategories([oneOff]).biggestNemesis,null);
  assert.equal(rivalCategories([oneOff]).longestCurrentStreak,null);
  const threeMatch={...oneOff,matches:3,myWins:1,theirWins:2,currentStreak:-2};
  const active={id:'b',nickname:'Active',matches:8,myWins:4,theirWins:4,currentStreak:3,lastEncounterAt:'2026-10-02T00:00:00Z'};
  const categories=rivalCategories([threeMatch,active]);
  assert.equal(categories.biggestNemesis.id,'a');
  assert.equal(categories.longestCurrentStreak.id,'b');
  assert.equal(categories.mostRecent.id,'b');
});

test('accepted challenge room expires only while waiting for its second player',()=>{
  assert.equal(canExpireChallengeRoom({challengeId:'challenge',phase:'waiting',players:[]}),true);
  assert.equal(canExpireChallengeRoom({challengeId:'challenge',phase:'waiting',players:[{}]}),true);
  assert.equal(canExpireChallengeRoom({challengeId:'challenge',phase:'waiting',players:[{},{}]}),false);
  assert.equal(canExpireChallengeRoom({challengeId:'challenge',phase:'active',players:[{}]}),false);
  assert.equal(canExpireChallengeRoom({challengeId:null,phase:'waiting',players:[]}),false);
});

test('rival challenges require a real rivalry, are single-active, and can be accepted once',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const a=await repo.createProfile('Challenger'),b=await repo.createProfile('Opponent');
  const matchId=randomUUID();
  await repo.beginMatch({id:matchId,mode:'DEATHMATCH',startedAt:new Date(),durationSeconds:60,halves:2,participants:[{profileId:a.profile.id},{profileId:b.profile.id}]});
  await repo.completeMatch({id:matchId,mode:'DEATHMATCH',winnerProfileId:a.profile.id,participants:[{profileId:a.profile.id,score:10},{profileId:b.profile.id,score:4}]});
  await assert.rejects(repo.createChallenge(a.profile.id,b.profile.id,{durationSeconds:20}),{status:400});
  const simultaneous=await Promise.allSettled([
    repo.createChallenge(b.profile.id,a.profile.id,{mode:'DEATHMATCH',durationSeconds:30,halves:4}),
    repo.createChallenge(a.profile.id,b.profile.id,{mode:'DEATHMATCH',durationSeconds:30,halves:4})
  ]);
  assert.equal(simultaneous.filter(result=>result.status==='fulfilled').length,1);
  assert.ok(simultaneous.filter(result=>result.status==='rejected').every(result=>result.reason.status===409));
  const challenge=simultaneous.find(result=>result.status==='fulfilled').value;
  assert.equal(challenge.mode,'DEATHMATCH'); assert.equal(challenge.durationSeconds,30); assert.equal(challenge.halves,4);
  assert.equal((await repo.challengeInbox(challenge.toPlayerId)).length,1);
  await assert.rejects(repo.respondChallenge(challenge.id,challenge.toPlayerId,'false','ZZZZ99'),{status:400});
  assert.equal((await repo.challengeInbox(challenge.toPlayerId)).length,1);
  const accepted=await repo.respondChallenge(challenge.id,challenge.toPlayerId,true,'ABCD12');
  assert.equal(accepted.status,'accepted'); assert.equal(accepted.roomCode,'ABCD12');
  await assert.rejects(repo.respondChallenge(challenge.id,challenge.toPlayerId,true,'ZZZZ99'),{status:404});
});

test('accepting an expired rival challenge marks it expired and returns an explicit expiry error',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const a=await repo.createProfile('ExpirySender'),b=await repo.createProfile('ExpiryTarget');
  const matchId=randomUUID();
  await repo.beginMatch({id:matchId,mode:'DEATHMATCH',startedAt:new Date(),durationSeconds:60,halves:2,participants:[{profileId:a.profile.id},{profileId:b.profile.id}]});
  await repo.completeMatch({id:matchId,mode:'DEATHMATCH',winnerProfileId:a.profile.id,participants:[{profileId:a.profile.id,score:4},{profileId:b.profile.id,score:2}]});
  const challenge=await repo.createChallenge(a.profile.id,b.profile.id);
  await pool.query('UPDATE challenges SET expires_at=$2 WHERE id=$1',[challenge.id,new Date('2026-10-17T11:59:59.000Z')]);
  await assert.rejects(repo.pendingChallenge(challenge.id,b.profile.id),{status:410,message:'Challenge expired.'});
  assert.equal((await pool.query('SELECT status FROM challenges WHERE id=$1',[challenge.id])).rows[0].status,'expired');
  assert.equal((await repo.challengeInbox(b.profile.id)).length,0);
});

test('Together pair ranking is host-order independent and uses shared score',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const a=await repo.createProfile('MoverOne'),b=await repo.createProfile('MoverTwo');
  const id=randomUUID(); await repo.beginMatch({id,mode:'CONNECTION',startedAt:new Date(),durationSeconds:60,halves:1,participants:[{profileId:b.profile.id,role:'LEADER'},{profileId:a.profile.id,role:'FOLLOWER'}]});
  await repo.completeMatch({id,mode:'CONNECTION',participants:[{profileId:b.profile.id,score:0},{profileId:a.profile.id,score:0}],sharedScore:73,alignmentPercent:91,syncSeconds:24,longestSyncSeconds:12});
  const ranking=await repo.togetherLeaderboard();
  assert.equal(ranking.entries.length,1); assert.equal(ranking.entries[0].bestScore,73); assert.equal(ranking.entries[0].sessions,1);
  assert.deepEqual(new Set([ranking.entries[0].playerA.id,ranking.entries[0].playerB.id]),new Set([a.profile.id,b.profile.id]));
  assert.equal((await repo.matchHistory(a.profile.id)).length,1);
});

test('Together persistence requires an integer shared score within the game range',async t=>{
  const {pool,repo}=await testRepository(); t.after(()=>pool.end());
  const a=await repo.createProfile('ScoreMoverA'),b=await repo.createProfile('ScoreMoverB');
  const id=randomUUID();
  await repo.beginMatch({id,mode:'CONNECTION',startedAt:new Date(),durationSeconds:60,halves:1,participants:[{profileId:a.profile.id},{profileId:b.profile.id}]});
  const result=await repo.completeMatch({id,mode:'CONNECTION',participants:[{profileId:a.profile.id,score:0},{profileId:b.profile.id,score:0}],sharedScore:47.3,alignmentPercent:85,syncSeconds:10,longestSyncSeconds:4});
  assert.equal(result.recorded,false);
  assert.equal((await repo.profile(a.profile.id)).stats.togetherSessions,0);
  assert.equal((await pool.query('SELECT status FROM matches WHERE id=$1',[id])).rows[0].status,'abandoned');
});
