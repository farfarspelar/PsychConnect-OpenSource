import { SERVER_CONFIG } from '../config.js';

const thresholds=SERVER_CONFIG.rivalry;
export const RIVAL_ACHIEVEMENTS=Object.freeze([
  {code:'first_encounter',title:'First Encounter',description:'Complete your first Versus match against this rival.'},
  {code:'sweet_revenge',title:'Sweet Revenge',description:'Defeat a rival who won your previous encounter.'},
  {code:'unfinished_business',title:'Unfinished Business',description:`Complete ${thresholds.unfinishedBusinessMatches} Versus matches against this rival.`},
  {code:'nemesis',title:'Nemesis',description:`Complete ${thresholds.nemesisMatches} Versus matches against this rival.`},
  {code:'perfect_balance',title:'Perfect Balance',description:`Reach exactly ${thresholds.perfectBalanceWinsPerPlayer} wins each against this rival.`},
  {code:'comeback',title:'Comeback',description:`Win after this rival wins ${thresholds.comebackAfterConsecutiveLosses} consecutive encounters.`}
]);
export function rivalryPair(a,b) { return a.localeCompare(b)<0?[a,b]:[b,a]; }
export function rivalCategories(list) {
  const byId=(a,b)=>String(a.id).localeCompare(String(b.id));
  const closest=list.filter(r=>r.matches>=2)
    .sort((a,b)=>Math.abs(a.myWins-a.theirWins)-Math.abs(b.myWins-b.theirWins)||b.matches-a.matches||byId(a,b))[0]||null;
  const nemesis=list.filter(r=>r.matches>=thresholds.nemesisCategoryMinMatches&&r.theirWins>r.myWins)
    .sort((a,b)=>(b.theirWins-b.myWins)-(a.theirWins-a.myWins)||b.theirWins-a.theirWins||b.matches-a.matches||byId(a,b))[0]||null;
  const streak=list.filter(r=>Math.abs(r.currentStreak)>=thresholds.streakCategoryMinLength)
    .sort((a,b)=>Math.abs(b.currentStreak)-Math.abs(a.currentStreak)||b.matches-a.matches||byId(a,b))[0]||null;
  const mostRecent=[...list].sort((a,b)=>new Date(b.lastEncounterAt)-new Date(a.lastEncounterAt)||byId(a,b))[0]||null;
  return {mostPlayed:list[0]||null,closest,biggestNemesis:nemesis,longestCurrentStreak:streak,mostRecent};
}
export function nextRivalState(previous,winnerId,lowId,highId) {
  const old=previous||{matches_played:0,low_wins:0,high_wins:0,draws:0,current_streak:0,streak_winner:null,longest_streak:0,last_winner:null};
  const lowWon=winnerId===lowId,highWon=winnerId===highId;
  const streakWinner=winnerId===null?null:winnerId;
  const streak=winnerId===null?0:(old.streak_winner===winnerId?Number(old.current_streak)+1:1);
  return {
    matches_played:Number(old.matches_played)+1,
    low_wins:Number(old.low_wins)+(lowWon?1:0),
    high_wins:Number(old.high_wins)+(highWon?1:0),
    draws:Number(old.draws)+(winnerId===null?1:0),
    streak_winner:streakWinner,current_streak:streak,
    longest_streak:Math.max(Number(old.longest_streak),streak),last_winner:winnerId
  };
}
export function achievementsForResult({playerId,rivalId,previous,next,winnerId}) {
  const unlocked=[];
  if(!previous||Number(previous.matches_played)===0) unlocked.push('first_encounter');
  if(winnerId===playerId && previous?.last_winner===rivalId) unlocked.push('sweet_revenge');
  if(next.matches_played===thresholds.unfinishedBusinessMatches) unlocked.push('unfinished_business');
  if(next.matches_played===thresholds.nemesisMatches) unlocked.push('nemesis');
  const [winsA,winsB]=playerId.localeCompare(rivalId)<0?[next.low_wins,next.high_wins]:[next.high_wins,next.low_wins];
  if(winsA===thresholds.perfectBalanceWinsPerPlayer&&winsB===thresholds.perfectBalanceWinsPerPlayer) unlocked.push('perfect_balance');
  if(winnerId===playerId&&previous?.streak_winner===rivalId&&Number(previous.current_streak)>=thresholds.comebackAfterConsecutiveLosses) unlocked.push('comeback');
  return unlocked;
}
