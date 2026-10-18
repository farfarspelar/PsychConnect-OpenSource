import { SERVER_CONFIG } from './config.js';

const RULES=SERVER_CONFIG.gameplay;
const TAU=Math.PI*2;

/** Generates occasional, pastel-friendly wave hazards for one shared Together round. */
export function createTogetherWaveGroup(sequence,nowMs,random=Math.random) {
  const roll=random();
  const count=roll<0.02?3:roll<0.22?2:1;
  const waves=Array.from({length:count},(_,index)=>{
    const angle=random()*TAU;
    return {
      id:`${sequence}-${index+1}`,
      x:0.2+random()*0.6,
      y:0.2+random()*0.6,
      gaps:[angle,angle+Math.PI],
      startAt:nowMs+index*850,
      durationMs:RULES.togetherWaveDurationMs,
      maxRadius:0.76
    };
  });
  return {id:sequence,waves};
}

const angularDistance=(a,b)=>Math.abs(Math.atan2(Math.sin(a-b),Math.cos(a-b)));

/** Returns a passage only when the expanding front actually reaches the player's touch. */
export function classifyTogetherWaveContact(wave,point,nowMs) {
  const elapsed=nowMs-wave.startAt;
  if(elapsed<0||elapsed>wave.durationMs||!point) return null;
  const radius=wave.maxRadius*(elapsed/wave.durationMs);
  const radial=Math.hypot(point.x-wave.x,point.y-wave.y);
  if(radial<0.07||Math.abs(radial-radius)>RULES.togetherWaveCollisionBand) return null;
  const angle=Math.atan2(point.y-wave.y,point.x-wave.x);
  let closest=null;
  wave.gaps.forEach((gap,index)=>{
    const distance=angularDistance(angle,gap);
    if(!closest||distance<closest.distance) closest={index,distance};
  });
  const safe=closest.distance<=RULES.togetherWaveGapHalfAngle;
  return {outcome:safe?'safe':'bumped',gapIndex:safe?closest.index:-1,angle};
}

/** Small deterministic cooperative reward; matching safe gaps earns the largest bonus. */
export function togetherWaveResult(first,second) {
  const safeA=first?.outcome==='safe',safeB=second?.outcome==='safe';
  if(safeA&&safeB) {
    const sameGap=first.gapIndex===second.gapIndex;
    const closePath=sameGap&&angularDistance(first.angle,second.angle)<=RULES.togetherWaveGapHalfAngle;
    return {delta:closePath?RULES.togetherWaveSameGapBonus: RULES.togetherWaveDifferentGapBonus,
      cue:closePath?'shared_passage':'safe_passage'};
  }
  if(safeA||safeB) return {delta:RULES.togetherWaveSafePoints,cue:'one_safe'};
  if(first?.outcome==='bumped'||second?.outcome==='bumped') return {delta:-RULES.togetherWaveHitPenalty,cue:'soft_bump'};
  return {delta:0,cue:'wave_passed'};
}

