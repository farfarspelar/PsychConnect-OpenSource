import { SERVER_CONFIG } from './config.js';

const RULES=SERVER_CONFIG.gameplay;

export function roundResult(samples, syncDistance = RULES.syncDistance) {
  if (!samples.length) return { score: 0, alignment: 0, syncSeconds: 0, longestSyncSeconds: 0 };
  const distances=samples.map(s=>s.distance);
  const mean=distances.reduce((a,b)=>a+b,0)/distances.length;
  const alignmentCount=distances.filter(d=>d<=syncDistance).length;
  const positional=distances.reduce((sum,d)=>sum+1-Math.min(1,d/RULES.positionRange),0)/distances.length;
  const alignment=alignmentCount/distances.length;
  const deviation=Math.sqrt(distances.reduce((sum,d)=>sum+(d-mean)**2,0)/distances.length);
  const stability=1-Math.min(1,deviation/RULES.stabilityDeviationRange);
  const movement=[];
  for(let i=1;i<samples.length;i++) {
    const a=samples[i-1],b=samples[i];
    const lx=b.leaderX-a.leaderX, ly=b.leaderY-a.leaderY;
    const fx=b.followerX-a.followerX, fy=b.followerY-a.followerY;
    const ls=Math.hypot(lx,ly), fs=Math.hypot(fx,fy);
    if(ls<RULES.leaderMovementNoise) continue;
    const cosine=fs===0 ? 0 : (lx*fx+ly*fy)/(ls*fs);
    const direction=(Math.max(-1,Math.min(1,cosine))+1)/2;
    const magnitude=1-Math.min(1,Math.abs(ls-fs)/Math.max(RULES.movementDistanceFloor,ls));
    movement.push(direction*RULES.movementDirectionWeight+magnitude*RULES.movementMagnitudeWeight);
  }
  const motion=movement.length ? movement.reduce((a,b)=>a+b,0)/movement.length : 0.5;
  const weights=RULES.scoreWeights;
  const score=Math.round((positional*weights.position+alignment*weights.alignment+stability*weights.stability+motion*weights.motion)*100);
  let syncMs=0, currentStreakMs=0, longestSyncMs=0;
  for(let i=0;i<samples.length;i++) {
    const next=samples[i+1]; const interval=next ? Math.max(0,Math.min(RULES.scoreSampleMaxIntervalMs,next.time-samples[i].time)) : RULES.finalScoreSampleIntervalMs;
    if(samples[i].distance<=syncDistance) { syncMs+=interval; currentStreakMs+=interval; longestSyncMs=Math.max(longestSyncMs,currentStreakMs); }
    else currentStreakMs=0;
  }
  return { score:Math.max(0,Math.min(100,score)), alignment:Math.round(alignment*100), syncSeconds:Math.round(syncMs)/1000, longestSyncSeconds:Math.round(longestSyncMs)/1000 };
}

/** Deathmatch gives the current Leader points for distance and the Follower points for closeness. */
export function deathmatchRoundResult(samples, role, syncDistance = RULES.syncDistance) {
  if (!samples.length) return { ...roundResult([]), role, score: 0 };
  const distanceQuality=samples.reduce((sum,s)=>sum+Math.min(1,s.distance/RULES.positionRange),0)/samples.length;
  const base=roundResult(samples,syncDistance);
  return { ...base, role, score:Math.round((role==='LEADER' ? distanceQuality : 1-distanceQuality)*100) };
}

/** Shared connection points require both players to keep moving, and decay while either rests. */
export function connectionScoreStep(score, distance, leaderMoving, followerMoving, elapsedSeconds) {
  const dt=Math.max(0,Math.min(RULES.connectionMaxStepSeconds,elapsedSeconds));
  const closeness=Math.max(0,1-Math.max(0,distance)/RULES.positionRange);
  const next=score+(leaderMoving && followerMoving ? dt*RULES.connectionGainPerSecond*closeness*closeness : -dt*RULES.connectionDecayPerSecond);
  return Math.max(0,Math.min(100,next));
}

export function clampLeaderPoint(previous, target, elapsedMs, maxUnitsPerSecond=RULES.leaderMaxUnitsPerSecond) {
  const x=Math.max(0,Math.min(1,target.x)), y=Math.max(0,Math.min(1,target.y));
  if(!previous) return {x,y,limited:false};
  const dx=x-previous.x,dy=y-previous.y,distance=Math.hypot(dx,dy);
  const allowed=maxUnitsPerSecond*Math.max(RULES.minMovementElapsedMs/1000,Math.min(RULES.maxMovementElapsedMs/1000,elapsedMs/1000));
  if(distance<=allowed) return {x,y,limited:false};
  return {x:previous.x+dx*allowed/distance,y:previous.y+dy*allowed/distance,limited:true};
}
