import { randomUUID } from 'node:crypto';
import { SERVER_CONFIG } from '../config.js';
import { createPlayerId,createPublicCode,createSecret,createRecoveryCode,hashSecret,verifySecret,normalizeNickname,normalizeRecoveryCode } from '../domain/identity.js';
import { INITIAL_RATING,eloPair,utcWeekStart } from '../domain/rating.js';
import { rivalryPair,nextRivalState,achievementsForResult,rivalCategories } from '../domain/rivalries.js';

const publicPlayer=row=>row?({id:row.id,profileCode:row.public_code,nickname:row.nickname,avatarId:row.avatar_id,createdAt:row.created_at,presenceHidden:row.presence_hidden,challengesEnabled:row.challenges_enabled}):null;
const canonicalCode=value=>{const raw=String(value||'').toUpperCase().replace(/[^A-Z0-9]/g,'');return raw.length===8?`${raw.slice(0,4)}-${raw.slice(4)}`:'';};
const isPlayerId=value=>typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
const isProfileToken=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{43}$/.test(value);
const fail=(message,status)=>Object.assign(new Error(message),{status});
async function transaction(pool,work) {
  const client=await pool.connect();
  try { await client.query('BEGIN'); const result=await work(client); await client.query('COMMIT'); return result; }
  catch(error) { await client.query('ROLLBACK'); throw error; }
  finally { client.release(); }
}
function rateFor(stats,opponent,score) { return eloPair(Number(stats.rating),Number(opponent.rating),score); }
const finiteNonNegative=value=>Number.isFinite(Number(value))&&Number(value)>=0;
const sameIds=(left,right)=>left.length===right.length&&left.every((id,index)=>id===right[index]);

export function createRepository(pool,{clock=()=>new Date()}={}) {
  async function createProfile(nicknameValue,avatarValue=0) {
    const nickname=normalizeNickname(nicknameValue),avatarId=Number(avatarValue);
    if(!Number.isInteger(avatarId)||avatarId<0||avatarId>11) throw fail('Choose one of the available avatars.',400);
    for(let attempt=0;attempt<4;attempt++) {
      const id=createPlayerId(),code=createPublicCode(),token=createSecret(),recovery=createRecoveryCode();
      const [tokenCredential,recoveryCredential]=await Promise.all([hashSecret(token),hashSecret(normalizeRecoveryCode(recovery))]);
      try {
        await transaction(pool,async client=>{
          await client.query('INSERT INTO players(id,public_code,nickname,avatar_id) VALUES($1,$2,$3,$4)',[id,code,nickname,avatarId]);
          await client.query('INSERT INTO player_credentials(player_id,token_salt,token_hash) VALUES($1,$2,$3)',[id,tokenCredential.salt,tokenCredential.hash]);
          await client.query('INSERT INTO recovery_credentials(player_id,recovery_salt,recovery_hash) VALUES($1,$2,$3)',[id,recoveryCredential.salt,recoveryCredential.hash]);
          await client.query('INSERT INTO player_stats(player_id,rating,highest_rating) VALUES($1,$2,$2)',[id,INITIAL_RATING]);
        });
        return {profile:{id,profileCode:code,nickname,avatarId,createdAt:clock().toISOString(),presenceHidden:false,challengesEnabled:true},credentials:{token,recoveryCode:recovery}};
      } catch(error) { if(error.code!=='23505'||attempt===3) throw error; }
    }
    throw fail('Could not create a unique profile. Please try again.',503);
  }

  async function authenticate(id,token) {
    if(!isPlayerId(id)||!isProfileToken(token)) throw fail('Profile authentication failed.',401);
    const {rows}=await pool.query('SELECT c.token_salt,c.token_hash,p.* FROM player_credentials c JOIN players p ON p.id=c.player_id WHERE p.id=$1',[id]);
    const row=rows[0];
    if(!row||!await verifySecret(token,row.token_salt,row.token_hash)) throw fail('Profile authentication failed.',401);
    return publicPlayer(row);
  }

  async function recover(publicCode,codeValue) {
    const code=normalizeRecoveryCode(codeValue);
    if(code.length<12||code.length>16||!/^[A-Z0-9]+$/.test(code)) throw fail('Profile code or recovery code is incorrect.',401);
    const found=await pool.query('SELECT p.id,p.public_code,p.nickname,p.avatar_id,p.created_at,p.presence_hidden,p.challenges_enabled,r.recovery_salt,r.recovery_hash FROM players p JOIN recovery_credentials r ON r.player_id=p.id WHERE p.public_code=$1',[canonicalCode(publicCode)]);
    const row=found.rows[0];
    if(!row||!await verifySecret(code,row.recovery_salt,row.recovery_hash)) throw fail('Profile code or recovery code is incorrect.',401);
    const token=createSecret(),recovery=createRecoveryCode();
    const [tokenCredential,recoveryCredential]=await Promise.all([hashSecret(token),hashSecret(normalizeRecoveryCode(recovery))]);
    return transaction(pool,async client=>{
      const rotated=await client.query('UPDATE recovery_credentials SET recovery_salt=$2,recovery_hash=$3,updated_at=$4 WHERE player_id=$1 AND recovery_salt=$5 AND recovery_hash=$6 RETURNING player_id',[row.id,recoveryCredential.salt,recoveryCredential.hash,clock(),row.recovery_salt,row.recovery_hash]);
      if(!rotated.rowCount) throw fail('Profile code or recovery code is incorrect.',401);
      await client.query('UPDATE player_credentials SET token_salt=$2,token_hash=$3,updated_at=$4 WHERE player_id=$1',[row.id,tokenCredential.salt,tokenCredential.hash,clock()]);
      return {profile:publicPlayer(row),credentials:{token,recoveryCode:recovery}};
    });
  }

  async function profile(id) {
    const result=await pool.query(`SELECT p.*,s.total_matches,s.versus_wins,s.versus_losses,s.versus_draws,s.current_win_streak,s.longest_win_streak,s.rating,s.highest_rating,s.total_sync_seconds,s.together_best_score,s.together_sessions,s.harmony_sessions,s.harmony_seconds,s.favorite_mode
      FROM players p JOIN player_stats s ON s.player_id=p.id WHERE p.id=$1`,[id]);
    if(!result.rows[0]) throw fail('Profile not found.',404);
    const row=result.rows[0];
    const rivals=await pool.query('SELECT count(*)::int AS n FROM rival_stats WHERE (player_low=$1 OR player_high=$1) AND matches_played>0',[id]);
    return {...publicPlayer(row),stats:{totalMatches:Number(row.total_matches),versusWins:Number(row.versus_wins),versusLosses:Number(row.versus_losses),versusDraws:Number(row.versus_draws),winRate:row.versus_wins+row.versus_losses?Math.round(100*row.versus_wins/(row.versus_wins+row.versus_losses)):0,currentWinStreak:Number(row.current_win_streak),longestWinStreak:Number(row.longest_win_streak),rating:Number(row.rating),highestRating:Number(row.highest_rating),totalSyncSeconds:Number(row.total_sync_seconds),togetherBestScore:Number(row.together_best_score),togetherSessions:Number(row.together_sessions),harmonySessions:Number(row.harmony_sessions),harmonySeconds:Number(row.harmony_seconds),favoriteMode:row.favorite_mode||'—',rivalsEncountered:Number(rivals.rows[0]?.n||0)}};
  }

  async function matchHistory(playerId,limit=50) {
    const result=await pool.query(`SELECT m.id,m.mode,m.status,m.started_at,m.completed_at,m.duration_seconds,m.shared_score,m.alignment_percent,m.sync_seconds,m.longest_sync_seconds,m.winner_player_id,
      self.score,self.result,self.rating_before,self.rating_after,other_p.nickname AS opponent_nickname,other_p.avatar_id AS opponent_avatar
      FROM match_participants self JOIN matches m ON m.id=self.match_id LEFT JOIN match_participants other ON other.match_id=m.id AND other.player_id<>self.player_id
      LEFT JOIN players other_p ON other_p.id=other.player_id WHERE self.player_id=$1 AND m.status='completed'
      ORDER BY m.completed_at DESC,m.id DESC LIMIT $2`,[playerId,Math.min(SERVER_CONFIG.matches.historyMaxLimit,Math.max(1,Number(limit)||SERVER_CONFIG.matches.historyDefaultLimit))]);
    return result.rows;
  }

  async function updateProfile(id,{nickname,avatarId}) {
    const old=await pool.query('SELECT nickname,avatar_id FROM players WHERE id=$1',[id]);
    if(!old.rows[0]) throw fail('Profile not found.',404);
    const clean=nickname===undefined?old.rows[0].nickname:normalizeNickname(nickname),avatar=avatarId===undefined?Number(old.rows[0].avatar_id):Number(avatarId);
    if(!Number.isInteger(avatar)||avatar<0||avatar>11) throw fail('Choose one of the available avatars.',400);
    const result=await pool.query('UPDATE players SET nickname=$2,avatar_id=$3 WHERE id=$1 RETURNING *',[id,clean,avatar]);
    if(!result.rows[0]) throw fail('Profile not found.',404);
    return publicPlayer(result.rows[0]);
  }

  async function updatePrivacy(id,{presenceHidden,challengesEnabled}) {
    if(presenceHidden!==undefined&&typeof presenceHidden!=='boolean') throw fail('Presence visibility must be true or false.',400);
    if(challengesEnabled!==undefined&&typeof challengesEnabled!=='boolean') throw fail('Challenge preference must be true or false.',400);
    const result=await pool.query('UPDATE players SET presence_hidden=COALESCE($2,presence_hidden),challenges_enabled=COALESCE($3,challenges_enabled) WHERE id=$1 RETURNING *',[id,presenceHidden??null,challengesEnabled??null]);
    if(!result.rows[0]) throw fail('Profile not found.',404);
    return publicPlayer(result.rows[0]);
  }

  async function deleteProfile(id) {
    await pool.query('DELETE FROM players WHERE id=$1',[id]);
    return {deleted:true};
  }

  async function beginMatch(match) {
    const ids=[...new Set(match.participants.map(p=>p.profileId).filter(Boolean))];
    if(ids.length!==2) return false;
    return transaction(pool,async client=>{
      await client.query(`INSERT INTO matches(id,mode,status,started_at,duration_seconds,halves)
        VALUES($1,$2,'active',$3,$4,$5) ON CONFLICT(id) DO NOTHING`,[match.id,match.mode,match.startedAt,match.durationSeconds||0,match.halves||1]);
      const check=await client.query('SELECT status FROM matches WHERE id=$1',[match.id]);
      if(check.rows[0]?.status!=='active') return false;
      for(const participant of match.participants) if(participant.profileId) await client.query('INSERT INTO match_participants(match_id,player_id,role,score,result) VALUES($1,$2,$3,$4,$5) ON CONFLICT(match_id,player_id) DO NOTHING',[match.id,participant.profileId,participant.role||'PLAYER',0,'coop']);
      return true;
    });
  }

  async function abandonMatch(matchId,reason='disconnect') {
    await pool.query("UPDATE matches SET status='abandoned',completed_at=$2,abandoned_reason=$3 WHERE id=$1 AND status='active'",[matchId,clock(),String(reason).slice(0,32)]);
  }
  async function abandonInterruptedMatches() {
    const result=await pool.query("UPDATE matches SET status='abandoned',completed_at=now(),abandoned_reason='server_restart' WHERE status='active'");
    return result.rowCount;
  }

  async function unlock(client,playerId,rivalId,codes) {
    const rows=[];
    for(const code of codes) {
      const result=await client.query('INSERT INTO player_achievements(player_id,code,rival_id) VALUES($1,$2,$3) ON CONFLICT(player_id,code,rival_id) DO NOTHING RETURNING code',[playerId,code,rivalId]);
      if(result.rowCount) rows.push(code);
    }
    return rows;
  }

  async function completeMatch(match) {
    return transaction(pool,async client=>{
      const state=await client.query('SELECT status,mode FROM matches WHERE id=$1 FOR UPDATE',[match.id]);
      if(!state.rows[0]||state.rows[0].status!=='active') return {recorded:false,unlocks:[]};
      const participants=Array.isArray(match.participants)?match.participants.filter(p=>p?.profileId):[];
      const submittedIds=participants.map(p=>p.profileId).sort();
      const stored=await client.query('SELECT player_id FROM match_participants WHERE match_id=$1 ORDER BY player_id FOR UPDATE',[match.id]);
      const storedIds=stored.rows.map(row=>row.player_id);
      const mode=state.rows[0].mode;
      const scoresValid=participants.length===2&&participants.every(p=>Number.isSafeInteger(Number(p.score))&&Number(p.score)>=0);
      const metricsValid=['syncSeconds','longestSyncSeconds','durationSeconds'].every(key=>finiteNonNegative(match[key]??0))&&
        (match.alignmentPercent==null||(finiteNonNegative(match.alignmentPercent)&&Number(match.alignmentPercent)<=100))&&
        (match.sharedScore==null||finiteNonNegative(match.sharedScore));
      const sharedScoreValid=mode==='CONNECTION'
        ?Number.isSafeInteger(match.sharedScore)&&match.sharedScore>=0&&match.sharedScore<=100
        :match.sharedScore==null;
      const calculatedWinner=mode==='DEATHMATCH'&&scoresValid
        ?(Number(participants[0].score)===Number(participants[1].score)?null:Number(participants[0].score)>Number(participants[1].score)?participants[0].profileId:participants[1].profileId)
        :null;
      const resultValid=mode===match.mode&&participants.length===2&&submittedIds[0]!==submittedIds[1]&&
        sameIds(submittedIds,storedIds)&&scoresValid&&metricsValid&&sharedScoreValid&&
        (mode==='DEATHMATCH'?((match.winnerProfileId||null)===calculatedWinner):(match.winnerProfileId==null));
      if(!resultValid) {
        await client.query("UPDATE matches SET status='abandoned',completed_at=$2,abandoned_reason='invalid_result' WHERE id=$1",[match.id,clock()]);
        return {recorded:false,unlocks:[]};
      }
      await client.query('SELECT id FROM players WHERE id=$1 OR id=$2 ORDER BY id FOR UPDATE',[participants[0].profileId,participants[1].profileId]);
      const winnerId=calculatedWinner;
      await client.query(`UPDATE matches SET status='completed',completed_at=$2,winner_player_id=$3,shared_score=$4,alignment_percent=$5,sync_seconds=$6,longest_sync_seconds=$7,duration_seconds=$8
        WHERE id=$1`,[match.id,clock(),winnerId,match.sharedScore??null,match.alignmentPercent??null,match.syncSeconds||0,match.longestSyncSeconds||0,match.durationSeconds||0]);
      const unlocks=[];
      if(mode==='DEATHMATCH') {
        const pair=participants;
        const farmCheck=await client.query(`SELECT count(*)::int AS n FROM matches m
          JOIN match_participants a ON a.match_id=m.id AND a.player_id=$1
          JOIN match_participants b ON b.match_id=m.id AND b.player_id=$2
          WHERE m.mode='DEATHMATCH' AND m.status='completed' AND m.id<>$3 AND m.completed_at>$4`,[pair[0].profileId,pair[1].profileId,match.id,new Date(clock().getTime()-SERVER_CONFIG.rating.repeatPairCooldownMs)]);
        const ranked=Number(farmCheck.rows[0]?.n||0)===0;
        const ratingRows=await client.query('SELECT player_id,rating FROM player_stats WHERE player_id=$1 OR player_id=$2 ORDER BY player_id FOR UPDATE',[pair[0].profileId,pair[1].profileId]);
        const ratings=new Map(ratingRows.rows.map(row=>[row.player_id,Number(row.rating)]));
        const scoreA=pair[0].score,scoreB=pair[1].score;
        const outcomeA=scoreA===scoreB ? 0.5 : scoreA>scoreB ? 1 : 0;
        const elo=ranked?rateFor({rating:ratings.get(pair[0].profileId)??INITIAL_RATING},{rating:ratings.get(pair[1].profileId)??INITIAL_RATING},outcomeA):{ratingA:ratings.get(pair[0].profileId)??INITIAL_RATING,ratingB:ratings.get(pair[1].profileId)??INITIAL_RATING,deltaA:0,deltaB:0};
        const eloMap=new Map([[pair[0].profileId,{before:ratings.get(pair[0].profileId)??INITIAL_RATING,after:elo.ratingA,delta:elo.deltaA}],[pair[1].profileId,{before:ratings.get(pair[1].profileId)??INITIAL_RATING,after:elo.ratingB,delta:elo.deltaB}]]);
        for(const p of pair) {
          const won=winnerId===p.profileId,draw=winnerId===null,result=draw?'draw':won?'win':'loss';
          const current=await client.query('SELECT current_win_streak,longest_win_streak FROM player_stats WHERE player_id=$1',[p.profileId]);
          const currentStreak=won?Number(current.rows[0]?.current_win_streak||0)+1:0;
          const eloResult=eloMap.get(p.profileId);
          await client.query(`UPDATE player_stats SET total_matches=total_matches+1,versus_wins=versus_wins+$2,versus_losses=versus_losses+$3,versus_draws=versus_draws+$4,
            current_win_streak=$5,longest_win_streak=GREATEST(longest_win_streak,$5),rating=$6,highest_rating=GREATEST(highest_rating,$6),total_sync_seconds=total_sync_seconds+$7 WHERE player_id=$1`,[p.profileId,won?1:0,!won&&!draw?1:0,draw?1:0,currentStreak,eloResult.after,match.syncSeconds||0]);
          await client.query('UPDATE match_participants SET score=$3,result=$4,rating_before=$5,rating_after=$6 WHERE match_id=$1 AND player_id=$2',[match.id,p.profileId,p.score,result,eloResult.before,eloResult.after]);
          await client.query('INSERT INTO rating_events(match_id,player_id,rating_delta,rating_after,created_at) VALUES($1,$2,$3,$4,$5) ON CONFLICT(match_id,player_id) DO NOTHING',[match.id,p.profileId,eloResult.delta,eloResult.after,clock()]);
        }
        const rivalUnlocks=await updateRivalship(client,pair[0].profileId,pair[1].profileId,winnerId,clock());
        unlocks.push(...rivalUnlocks);
      } else {
        for(const p of participants) {
          await client.query('UPDATE player_stats SET total_matches=total_matches+1,total_sync_seconds=total_sync_seconds+$2,together_sessions=together_sessions+$3,together_best_score=GREATEST(together_best_score,$4),harmony_sessions=harmony_sessions+$5,harmony_seconds=harmony_seconds+$6 WHERE player_id=$1',[p.profileId,match.syncSeconds||0,mode==='CONNECTION'?1:0,mode==='CONNECTION'?(match.sharedScore||0):0,mode==='HARMONY'?1:0,mode==='HARMONY'?(match.durationSeconds||0):0]);
          await client.query('UPDATE match_participants SET score=$3,result=$4 WHERE match_id=$1 AND player_id=$2',[match.id,p.profileId,mode==='CONNECTION'?(match.sharedScore||0):0,'coop']);
        }
      }
      for(const p of participants) {
        const favorite=await client.query('SELECT mode,count(*)::int AS n FROM matches m JOIN match_participants mp ON mp.match_id=m.id WHERE mp.player_id=$1 AND m.status=\'completed\' GROUP BY mode ORDER BY n DESC,mode ASC LIMIT 1',[p.profileId]);
        if(favorite.rows[0]) await client.query('UPDATE player_stats SET favorite_mode=$2 WHERE player_id=$1',[p.profileId,favorite.rows[0].mode]);
      }
      return {recorded:true,unlocks};
    });
  }

  async function updateRivalship(client,a,b,winnerId,now) {
    const [low,high]=rivalryPair(a,b);
    const result=await client.query('SELECT * FROM rival_stats WHERE player_low=$1 AND player_high=$2 FOR UPDATE',[low,high]);
    const previous=result.rows[0]||null;
    const next=nextRivalState(previous,winnerId,low,high);
    await client.query(`INSERT INTO rival_stats(player_low,player_high,matches_played,low_wins,high_wins,draws,streak_winner,current_streak,longest_streak,last_encounter_at,last_winner)
      VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
      ON CONFLICT(player_low,player_high) DO UPDATE SET matches_played=EXCLUDED.matches_played,low_wins=EXCLUDED.low_wins,high_wins=EXCLUDED.high_wins,draws=EXCLUDED.draws,streak_winner=EXCLUDED.streak_winner,current_streak=EXCLUDED.current_streak,longest_streak=EXCLUDED.longest_streak,last_encounter_at=EXCLUDED.last_encounter_at,last_winner=EXCLUDED.last_winner`,[low,high,next.matches_played,next.low_wins,next.high_wins,next.draws,next.streak_winner,next.current_streak,next.longest_streak,now,winnerId]);
    const unlocked=[];
    for(const playerId of [a,b]) {
      const rivalId=playerId===a?b:a;
      const newCodes=await unlock(client,playerId,rivalId,achievementsForResult({playerId,rivalId,previous,next,winnerId}));
      unlocked.push(...newCodes.map(code=>({playerId,code,rivalId})));
    }
    return unlocked;
  }

  async function leaderboard(mode,range,playerId=null) {
    if(mode==='CONNECTION') return togetherLeaderboard();
    if(mode==='COOP_SURVIVAL'||mode==='PUZZLE') return arcadePairLeaderboard(mode);
    if(mode==='SOLO_SURVIVAL') return soloSurvivalLeaderboard();
    const weekly=range==='weekly';
    const week=utcWeekStart(clock());
    const nextWeek=new Date(week.getTime()+7*24*60*60*1000);
    const toEntry=(row,rank)=>({rank,id:row.id,profileCode:row.public_code,nickname:row.nickname,avatarId:row.avatar_id,rating:Number(row.rating),points:Number(row.points),wins:Number(row.versus_wins),losses:Number(row.versus_losses),winRate:row.versus_wins+row.versus_losses?Math.round(100*row.versus_wins/(row.versus_wins+row.versus_losses)):0});
    const top=weekly?await pool.query(`SELECT p.id,p.public_code,p.nickname,p.avatar_id,s.rating,s.versus_wins,s.versus_losses,e.points
      FROM players p JOIN player_stats s ON s.player_id=p.id
      JOIN (SELECT player_id,sum(rating_delta)::int AS points FROM rating_events WHERE created_at >= $1 AND created_at < $2 GROUP BY player_id) e ON e.player_id=p.id
      ORDER BY points DESC,s.rating DESC,p.id ASC LIMIT ${SERVER_CONFIG.leaderboard.topLimit}`,[week,nextWeek]):await pool.query(`SELECT p.id,p.public_code,p.nickname,p.avatar_id,s.rating,s.versus_wins,s.versus_losses,s.rating AS points
      FROM players p JOIN player_stats s ON s.player_id=p.id
      WHERE s.versus_wins+s.versus_losses+s.versus_draws>0
      ORDER BY s.rating DESC,s.versus_wins DESC,p.id ASC LIMIT ${SERVER_CONFIG.leaderboard.topLimit}`);
    let entries=top.rows.map((row,index)=>toEntry(row,index+1));
    let own=null;
    let nearby=[];
    if(playerId) {
      const mine=weekly?await pool.query(`SELECT p.id,p.public_code,p.nickname,p.avatar_id,s.rating,s.versus_wins,s.versus_losses,e.points
        FROM players p JOIN player_stats s ON s.player_id=p.id
        JOIN (SELECT player_id,sum(rating_delta)::int AS points FROM rating_events WHERE created_at >= $1 AND created_at < $2 GROUP BY player_id) e ON e.player_id=p.id
        WHERE p.id=$3`,[week,nextWeek,playerId]):await pool.query(`SELECT p.id,p.public_code,p.nickname,p.avatar_id,s.rating,s.versus_wins,s.versus_losses,s.rating AS points
        FROM players p JOIN player_stats s ON s.player_id=p.id WHERE p.id=$1 AND s.versus_wins+s.versus_losses+s.versus_draws>0`,[playerId]);
      if(mine.rows[0]) {
        const row=mine.rows[0];
        const ahead=weekly?await pool.query(`SELECT count(*)::int AS n FROM (
          SELECT p.id,s.rating,e.points FROM players p JOIN player_stats s ON s.player_id=p.id
          JOIN (SELECT player_id,sum(rating_delta)::int AS points FROM rating_events WHERE created_at >= $1 AND created_at < $2 GROUP BY player_id) e ON e.player_id=p.id
        ) ranked WHERE points>$3 OR (points=$3 AND rating>$4) OR (points=$3 AND rating=$4 AND id<$5)`,[week,nextWeek,row.points,row.rating,row.id]):await pool.query(`SELECT count(*)::int AS n FROM player_stats
          WHERE versus_wins+versus_losses+versus_draws>0 AND (rating>$1 OR (rating=$1 AND versus_wins>$3) OR (rating=$1 AND versus_wins=$3 AND player_id<$2))`,[row.rating,row.id,row.versus_wins]);
        own=toEntry(row,Number(ahead.rows[0]?.n||0)+1);
        const nearbyRadius=Math.floor(SERVER_CONFIG.leaderboard.nearbyCount/2);
        if(own.rank<=SERVER_CONFIG.leaderboard.topLimit) {
          nearby=entries.filter(entry=>Math.abs(entry.rank-own.rank)<=nearbyRadius);
        } else {
          const offset=Math.max(0,own.rank-nearbyRadius-1);
          const around=weekly?await pool.query(`SELECT p.id,p.public_code,p.nickname,p.avatar_id,s.rating,s.versus_wins,s.versus_losses,e.points
            FROM players p JOIN player_stats s ON s.player_id=p.id
            JOIN (SELECT player_id,sum(rating_delta)::int AS points FROM rating_events WHERE created_at >= $1 AND created_at < $2 GROUP BY player_id) e ON e.player_id=p.id
          ORDER BY points DESC,s.rating DESC,p.id ASC LIMIT ${SERVER_CONFIG.leaderboard.nearbyCount} OFFSET $3`,[week,nextWeek,offset]):await pool.query(`SELECT p.id,p.public_code,p.nickname,p.avatar_id,s.rating,s.versus_wins,s.versus_losses,s.rating AS points
            FROM players p JOIN player_stats s ON s.player_id=p.id WHERE s.versus_wins+s.versus_losses+s.versus_draws>0
            ORDER BY s.rating DESC,s.versus_wins DESC,p.id ASC LIMIT ${SERVER_CONFIG.leaderboard.nearbyCount} OFFSET $1`,[offset]);
          nearby=around.rows.map((nearRow,index)=>toEntry(nearRow,offset+index+1));
        }
        if(!entries.some(entry=>entry.id===playerId)&&(own.rank<=SERVER_CONFIG.leaderboard.topLimit||entries.some(entry=>Math.abs(entry.rank-own.rank)<=nearbyRadius))) entries.push(own);
        entries.sort((a,b)=>a.rank-b.rank);
      }
    }
    return {range:weekly?'weekly':'all_time',weekStartsAt:weekly?week.toISOString():null,entries,me:own,nearby};
  }

  async function recordSoloSurvival({id,playerId,score}) {
    if(!isPlayerId(playerId)||typeof id!=='string'||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)||
      !Number.isSafeInteger(Number(score))||Number(score)<0||Number(score)>86_400_000) return false;
    const result=await pool.query(`INSERT INTO solo_survival_runs(id,player_id,score_ms,completed_at)
      VALUES($1,$2,$3,$4) ON CONFLICT(id) DO NOTHING`,[id,playerId,Number(score),clock()]);
    return result.rowCount===1;
  }

  async function soloSurvivalLeaderboard() {
    const result=await pool.query(`WITH best AS (
      SELECT DISTINCT ON(player_id) player_id,score_ms,completed_at
      FROM solo_survival_runs ORDER BY player_id,score_ms DESC,completed_at ASC
    ), totals AS (SELECT player_id,count(*)::int AS runs FROM solo_survival_runs GROUP BY player_id)
    SELECT p.id,p.nickname,p.avatar_id,b.score_ms,t.runs
    FROM best b JOIN totals t USING(player_id) JOIN players p ON p.id=b.player_id
    ORDER BY b.score_ms DESC,b.completed_at ASC,p.id ASC LIMIT 5`);
    return {mode:'SOLO_SURVIVAL',range:'all_time',entries:result.rows.map((row,index)=>({rank:index+1,id:row.id,nickname:row.nickname,avatarId:row.avatar_id,bestScore:Number(row.score_ms),sessions:Number(row.runs)}))};
  }

  async function togetherLeaderboard() {
    const result=await pool.query(`SELECT LEAST(a.player_id,b.player_id) AS player_a,GREATEST(a.player_id,b.player_id) AS player_b,
      pa.nickname AS nickname_a,pa.avatar_id AS avatar_a,pb.nickname AS nickname_b,pb.avatar_id AS avatar_b,
      count(DISTINCT m.id)::int AS sessions,max(m.shared_score)::int AS best_score,max(m.longest_sync_seconds) AS longest_sync,max(m.sync_seconds) AS best_sync
      FROM matches m JOIN match_participants a ON a.match_id=m.id JOIN match_participants b ON b.match_id=m.id AND a.player_id<>b.player_id
      JOIN players pa ON pa.id=LEAST(a.player_id,b.player_id) JOIN players pb ON pb.id=GREATEST(a.player_id,b.player_id)
      WHERE m.mode='CONNECTION' AND m.status='completed' GROUP BY LEAST(a.player_id,b.player_id),GREATEST(a.player_id,b.player_id),pa.nickname,pa.avatar_id,pb.nickname,pb.avatar_id
      ORDER BY best_score DESC,longest_sync DESC,sessions DESC,player_a ASC LIMIT ${SERVER_CONFIG.leaderboard.topLimit}`);
    return {mode:'CONNECTION',entries:result.rows.map((r,i)=>({rank:i+1,playerA:{id:r.player_a,nickname:r.nickname_a,avatarId:r.avatar_a},playerB:{id:r.player_b,nickname:r.nickname_b,avatarId:r.avatar_b},sessions:r.sessions,bestScore:r.best_score,longestSyncSeconds:r.longest_sync,bestSyncSeconds:r.best_sync}))};
  }

  async function recordArcadePairRun({id,mode,participants,score,level=0}) {
    if(!['COOP_SURVIVAL','PUZZLE'].includes(mode)||!Array.isArray(participants)||participants.length!==2||
      participants.some(player=>!isPlayerId(player.profileId))||participants[0].profileId===participants[1].profileId||
      !Number.isSafeInteger(Number(score))||Number(score)<0||!Number.isSafeInteger(Number(level))||Number(level)<0) return false;
    const [playerA,playerB]=participants.map(player=>player.profileId).sort();
    const result=await pool.query(`INSERT INTO arcade_pair_runs(id,mode,player_a,player_b,score,level,completed_at)
      VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(id) DO NOTHING`,[id,mode,playerA,playerB,Number(score),Number(level),clock()]);
    return result.rowCount===1;
  }

  async function arcadePairLeaderboard(mode) {
    const result=await pool.query(`WITH pair_totals AS (
      SELECT player_a,player_b,count(*)::int AS sessions FROM arcade_pair_runs WHERE mode=$1 GROUP BY player_a,player_b
    ), best_runs AS (
      SELECT DISTINCT ON(player_a,player_b) player_a,player_b,score,level,completed_at
      FROM arcade_pair_runs WHERE mode=$1 ORDER BY player_a,player_b,score DESC,completed_at DESC
    )
    SELECT t.player_a,t.player_b,t.sessions,b.score AS best_score,b.level AS best_level,b.completed_at AS last_played,
      pa.nickname AS nickname_a,pa.avatar_id AS avatar_a,pb.nickname AS nickname_b,pb.avatar_id AS avatar_b
    FROM pair_totals t JOIN best_runs b USING(player_a,player_b)
    JOIN players pa ON pa.id=t.player_a JOIN players pb ON pb.id=t.player_b
    ORDER BY b.score DESC,t.sessions DESC,t.player_a ASC,t.player_b ASC LIMIT ${SERVER_CONFIG.leaderboard.topLimit}`,[mode]);
    return {mode,range:'all_time',entries:result.rows.map((row,index)=>({rank:index+1,
      playerA:{id:row.player_a,nickname:row.nickname_a,avatarId:row.avatar_a},
      playerB:{id:row.player_b,nickname:row.nickname_b,avatarId:row.avatar_b},
      sessions:Number(row.sessions),bestScore:Number(row.best_score),bestLevel:Number(row.best_level),lastPlayed:row.last_played}))};
  }

  async function publicProfile(publicCode) {
    const result=await pool.query('SELECT id FROM players WHERE public_code=$1',[canonicalCode(publicCode)]);
    return result.rows[0]?profile(result.rows[0].id):null;
  }

  async function rivals(playerId) {
    const result=await pool.query(`SELECT r.*,CASE WHEN r.player_low=$1 THEN r.player_high ELSE r.player_low END AS rival_id,p.public_code,p.nickname,p.avatar_id,s.rating
      FROM rival_stats r JOIN players p ON p.id=CASE WHEN r.player_low=$1 THEN r.player_high ELSE r.player_low END JOIN player_stats s ON s.player_id=p.id
      WHERE (r.player_low=$1 OR r.player_high=$1) AND r.matches_played>0 ORDER BY r.matches_played DESC,r.last_encounter_at DESC,p.id ASC`,[playerId]);
    const list=result.rows.map(r=>{const mine=r.player_low===playerId?Number(r.low_wins):Number(r.high_wins),theirs=r.player_low===playerId?Number(r.high_wins):Number(r.low_wins);return {id:r.rival_id,profileCode:r.public_code,nickname:r.nickname,avatarId:r.avatar_id,rating:Number(r.rating),matches:Number(r.matches_played),myWins:mine,theirWins:theirs,draws:Number(r.draws),currentStreak:r.streak_winner===playerId?Number(r.current_streak):r.streak_winner===r.rival_id?-Number(r.current_streak):0,longestStreak:Number(r.longest_streak),lastEncounterAt:r.last_encounter_at,lastWinner:r.last_winner===playerId?'you':r.last_winner===r.rival_id?'rival':'draw'};});
    return {entries:list,categories:rivalCategories(list)};
  }

  async function relationships(playerId) {
    const result=await pool.query(`SELECT other.id,other.public_code AS "profileCode",other.nickname,other.avatar_id AS "avatarId",
      sum(CASE WHEN m.mode='CONNECTION' THEN 1 ELSE 0 END)::int AS "togetherMatches",
      sum(CASE WHEN m.mode='HARMONY' THEN 1 ELSE 0 END)::int AS "harmonySessions",
      max(m.completed_at) AS "lastPlayedAt"
      FROM match_participants own
      JOIN matches m ON m.id=own.match_id AND m.status='completed' AND m.mode IN ('CONNECTION','HARMONY')
      JOIN match_participants peer ON peer.match_id=m.id AND peer.player_id<>own.player_id
      JOIN players other ON other.id=peer.player_id
      WHERE own.player_id=$1 GROUP BY other.id,other.public_code,other.nickname,other.avatar_id
      ORDER BY count(*) DESC,max(m.completed_at) DESC,other.id LIMIT 100`,[playerId]);
    return {friends:result.rows};
  }

  async function achievements(playerId) {
    const result=await pool.query('SELECT a.code,a.title,a.description,pa.rival_id,p.nickname AS rival_nickname,pa.unlocked_at FROM player_achievements pa JOIN achievements a ON a.code=pa.code JOIN players p ON p.id=pa.rival_id WHERE pa.player_id=$1 ORDER BY pa.unlocked_at DESC',[playerId]);
    return result.rows;
  }

  async function createChallenge(fromId,toId,settings={}) {
    if(fromId===toId) throw fail('You cannot challenge yourself.',400);
    const duration=settings.durationSeconds===undefined?SERVER_CONFIG.challenges.defaultDurationSeconds:Number(settings.durationSeconds);
    const mode=settings.mode===undefined?'DEATHMATCH':settings.mode;
    const halves=settings.halves===undefined?SERVER_CONFIG.challenges.defaultHalves:Number(settings.halves);
    if(!SERVER_CONFIG.challenges.allowedDurationsSeconds.includes(duration)) throw fail('Choose a 30, 45, or 60 second round.',400);
    if(!['DEATHMATCH','CONNECTION','HARMONY'].includes(mode)) throw fail('Choose a supported game mode.',400);
    if(!SERVER_CONFIG.challenges.allowedHalves.includes(halves)) throw fail('Choose a 2- or 4-half match.',400);
    try { return await transaction(pool,async client=>{
      const rivalry=await client.query('SELECT 1 FROM rival_stats WHERE ((player_low=$1 AND player_high=$2) OR (player_low=$2 AND player_high=$1)) AND matches_played>0',[fromId,toId]);
      if(!rivalry.rowCount) throw fail('Challenges are available after you have completed a Versus match together.',403);
      const target=await client.query('SELECT challenges_enabled FROM players WHERE id=$1',[toId]);
      if(!target.rows[0]) throw fail('Rival not found.',404);
      if(!target.rows[0].challenges_enabled) throw fail('This player has disabled challenges.',403);
      const pair=rivalryPair(fromId,toId).join(':');
      const now=clock(),id=randomUUID(),expiry=new Date(now.getTime()+SERVER_CONFIG.challenges.lifetimeMs);
      await client.query("UPDATE challenges SET status='expired',responded_at=$2 WHERE status='pending' AND expires_at<=$2",[now,now]);
      const active=await client.query("SELECT id FROM challenges WHERE pair_key=$1 AND status='pending' AND expires_at>$2",[pair,now]);
      if(active.rowCount) throw fail('An invitation for this rivalry is already active.',409);
      await client.query(`INSERT INTO challenges(id,from_player_id,to_player_id,pair_key,mode,duration_seconds,halves,status,created_at,expires_at)
        VALUES($1,$2,$3,$4,$5,$6,$7,'pending',$8,$9)`,[id,fromId,toId,pair,mode,duration,halves,now,expiry]);
      return {id,fromPlayerId:fromId,toPlayerId:toId,mode,durationSeconds:duration,halves,expiresAt:expiry.toISOString()};
    }); } catch(error) {
      // The partial unique index is the final guard if two requests pass the
      // active-invitation read at the same time.
      const duplicatePair=error.constraint==='challenges_pending_pair_unique'||error.detail?.includes('(pair_key)')||/Key \(pair_key\)=/.test(error.message||'');
      if(error.code==='23505'&&duplicatePair) throw fail('An invitation for this rivalry is already active.',409);
      throw error;
    }
  }

  async function respondChallenge(id,playerId,accept,roomCode=null) {
    if(typeof accept!=='boolean') throw fail('Accept must be true or false.',400);
    const response=await transaction(pool,async client=>{
      const result=await client.query("SELECT * FROM challenges WHERE id=$1 AND to_player_id=$2 AND status='pending' FOR UPDATE",[id,playerId]);
      const challenge=result.rows[0];
      if(!challenge) throw fail('Challenge is no longer available.',404);
      if(new Date(challenge.expires_at)<=clock()) {
        await client.query("UPDATE challenges SET status='expired',responded_at=$2 WHERE id=$1",[id,clock()]);
        return {expired:true};
      }
      const status=accept?'accepted':'declined';
      await client.query('UPDATE challenges SET status=$2,responded_at=$3,session_code=$4 WHERE id=$1',[id,status,clock(),accept?roomCode:null]);
      return {id,fromPlayerId:challenge.from_player_id,toPlayerId:challenge.to_player_id,mode:challenge.mode,durationSeconds:challenge.duration_seconds,halves:challenge.halves,status,roomCode:accept?roomCode:null};
    });
    if(response.expired) throw fail('Challenge expired.',410);
    return response;
  }

  async function pendingChallenge(id,playerId) {
    const challenge=await transaction(pool,async client=>{
      const result=await client.query("SELECT id,from_player_id,to_player_id,mode,duration_seconds,halves,expires_at FROM challenges WHERE id=$1 AND to_player_id=$2 AND status='pending' FOR UPDATE",[id,playerId]);
      const row=result.rows[0];
      if(!row) throw fail('Challenge is no longer available.',404);
      if(new Date(row.expires_at)<=clock()) {
        await client.query("UPDATE challenges SET status='expired',responded_at=$2 WHERE id=$1 AND status='pending'",[id,clock()]);
        return {expired:true};
      }
      return {id:row.id,fromPlayerId:row.from_player_id,toPlayerId:row.to_player_id,mode:row.mode,durationSeconds:Number(row.duration_seconds),halves:Number(row.halves),expiresAt:row.expires_at};
    });
    if(challenge.expired) throw fail('Challenge expired.',410);
    return challenge;
  }

  async function cancelPendingChallenges(playerId) {
    await pool.query("UPDATE challenges SET status='expired',responded_at=$2 WHERE status='pending' AND (from_player_id=$1 OR to_player_id=$1)",[playerId,clock()]);
  }

  async function challengeInbox(playerId) {
    await pool.query("UPDATE challenges SET status='expired',responded_at=$2 WHERE to_player_id=$1 AND status='pending' AND expires_at<=$2",[playerId,clock()]);
    const result=await pool.query("SELECT c.id,c.mode,c.duration_seconds,c.halves,c.created_at,c.expires_at,p.nickname,p.avatar_id,p.public_code FROM challenges c JOIN players p ON p.id=c.from_player_id WHERE c.to_player_id=$1 AND c.status='pending' AND c.expires_at>$2 ORDER BY c.created_at DESC",[playerId,clock()]);
    return result.rows;
  }

  return {createProfile,authenticate,recover,profile,matchHistory,updateProfile,updatePrivacy,deleteProfile,beginMatch,completeMatch,abandonMatch,abandonInterruptedMatches,leaderboard,togetherLeaderboard,recordArcadePairRun,recordSoloSurvival,publicProfile,rivals,relationships,achievements,createChallenge,respondChallenge,pendingChallenge,cancelPendingChallenges,challengeInbox};
}
