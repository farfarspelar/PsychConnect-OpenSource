import { createPuzzleState,distanceToPuzzleCheckpoint,puzzleTouchHitsWall } from './puzzle.js';

const START_RADIUS=.065;
const LEVEL_TIME_MS=60_000;
const STARTING_SCORE=1_000;
const HIT_PENALTY=200;
const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
const dist=(a,b)=>Math.hypot(a.x-b.x,a.y-b.y);
const pointSegmentDistance=(point,a,b)=>{
  const dx=b.x-a.x,dy=b.y-a.y,length=dx*dx+dy*dy;
  const t=length===0?0:clamp(((point.x-a.x)*dx+(point.y-a.y)*dy)/length,0,1);
  return Math.hypot(point.x-(a.x+t*dx),point.y-(a.y+t*dy));
};
const sendState=(room,send,type,extra={})=>room.players.forEach(player=>send(player.ws,{type,score:room.puzzle?.score||0,totalScore:room.puzzle?.totalScore||0,level:room.puzzle?.level||1,...extra}));

function publishLevel(room,send) {
  const puzzle=room.puzzle;
  room.players.forEach(player=>send(player.ws,{type:'puzzle_level',level:puzzle.level,cols:puzzle.cols,rows:puzzle.rows,start:puzzle.start,
    walls:puzzle.walls,checkpoints:puzzle.checkpoints,score:puzzle.score,totalScore:puzzle.totalScore,remainingMs:LEVEL_TIME_MS,needsStart:true}));
}

function finishPuzzle(room,send,reason) {
  const puzzle=room.puzzle;
  if(!puzzle||puzzle.completed) return;
  puzzle.completed=true;
  room.phase='finished';
  clearTimeout(puzzle.nextLevelTimer);
  sendState(room,send,'puzzle_game_over',{reason,levelScore:puzzle.score,totalScore:puzzle.totalScore,level:puzzle.level});
}

function resetHazards(puzzle,now) {
  puzzle.motionMs=0; puzzle.hazardLastAt=now; puzzle.nextProjectileAt=2400; puzzle.nextBlastAt=6200;
  puzzle.projectiles=[]; puzzle.blasts=[]; puzzle.hazardSequence=0; puzzle.hitHazards=new Map(); puzzle.lastHazardBroadcastAt=0;
}

export function startPuzzle(room,send) {
  room.puzzle={...createPuzzleState(1),score:STARTING_SCORE,totalScore:0,completed:false,levelStartedAt:0,sequence:0,remainingMs:LEVEL_TIME_MS,
    motionMs:0,hazardLastAt:0,nextProjectileAt:2400,nextBlastAt:6200,projectiles:[],blasts:[],hazardSequence:0,hitHazards:new Map(),lastHazardBroadcastAt:0};
  publishLevel(room,send);
}

function restart(room,send,reason,penalty=0,alsoPenaltyWhileRestarting=false) {
  const puzzle=room.puzzle;
  if(!puzzle||puzzle.completed||(puzzle.needsStart&&!alsoPenaltyWhileRestarting)) return;
  puzzle.score=Math.max(0,puzzle.score-penalty);
  if(penalty>0) puzzle.wasHit=true;
  if(puzzle.score===0) { finishPuzzle(room,send,'score'); return; }
  puzzle.needsStart=true; puzzle.holdStartedAt=0; puzzle.checkpointPlayers={}; puzzle.playerChoices=new Map();
  room.players.forEach(player=>{ player.puzzleAtStart=false; player.puzzleChoice=null; player.puzzleMotionFactor=0; });
  sendState(room,send,'puzzle_restart',{reason,penalty,holdMs:0,remainingMs:puzzle.remainingMs??LEVEL_TIME_MS});
}

function atStart(point,puzzle) { return Math.hypot(point.x-puzzle.start.x,point.y-puzzle.start.y)<=START_RADIUS; }
function generateProjectile(target,id,level) {
  const side=Math.floor(Math.random()*4);
  const point=side===0?{x:-.04,y:Math.random()}:side===1?{x:1.04,y:Math.random()}:side===2?{x:Math.random(),y:-.04}:{x:Math.random(),y:1.04};
  const dx=target.x-point.x,dy=target.y-point.y,length=Math.hypot(dx,dy)||1;
  const turn=(Math.random()-.5)*.5,ux=dx/length,uy=dy/length,speed=Math.min(.55,.25+level*.012);
  return {id,x:point.x,y:point.y,vx:(ux*Math.cos(turn)-uy*Math.sin(turn))*speed,vy:(ux*Math.sin(turn)+uy*Math.cos(turn))*speed};
}
function farBlast(points) {
  let best={x:.5,y:.5},bestDistance=-1;
  for(let index=0;index<12;index++) {
    const point={x:.12+Math.random()*.76,y:.12+Math.random()*.70};
    const nearest=Math.min(...points.map(other=>dist(point,other)));
    if(nearest>bestDistance) { best=point; bestDistance=nearest; }
    if(nearest>.18) return point;
  }
  return best;
}
function registerHazardHit(puzzle,player,hazardId) {
  let hits=puzzle.hitHazards.get(player.id);
  if(!hits) { hits=new Set(); puzzle.hitHazards.set(player.id,hits); }
  if(hits.has(hazardId)) return false;
  hits.add(hazardId);
  return true;
}
function advanceHazards(room,puzzle,now,send) {
  const dt=Math.max(0,now-puzzle.hazardLastAt); puzzle.hazardLastAt=now;
  const activeFactor=Math.max(...room.players.map(player=>now-(player.puzzleMotionAt||0)>320?0:(player.puzzleMotionFactor||0)),0);
  const previousMotion=puzzle.motionMs;
  puzzle.motionMs+=dt*activeFactor;
  const motionNow=puzzle.motionMs;
  const seconds=puzzle.motionMs/1000;
  const projectileInterval=Math.max(1300,3100/(1+seconds/35));
  const blastInterval=Math.max(4000,9000/(1+seconds/45));
  const speedFactor=Math.min(2.2,1+puzzle.level*.08+seconds/120);
  const points=room.players.map(player=>({x:player.touch.x,y:player.touch.y}));
  if(motionNow>=puzzle.nextProjectileAt) {
    const target=points[Math.floor(Math.random()*points.length)];
    puzzle.projectiles.push(generateProjectile(target,++puzzle.hazardSequence,puzzle.level));
    puzzle.nextProjectileAt=motionNow+projectileInterval;
  }
  if(motionNow>=puzzle.nextBlastAt) {
    const roll=Math.random(),count=roll<.025?3:roll<.19?2:1;
    for(let index=0;index<count;index++) puzzle.blasts.push({id:++puzzle.hazardSequence,...farBlast(points),detonatesAt:motionNow+900+index*220,durationMs:2500,maxRadius:.72});
    puzzle.nextBlastAt=motionNow+blastInterval;
  }
  for(const projectile of puzzle.projectiles) {
    const previous={x:projectile.x,y:projectile.y};
    projectile.x+=projectile.vx*speedFactor*activeFactor*dt/1000;
    projectile.y+=projectile.vy*speedFactor*activeFactor*dt/1000;
    room.players.forEach(player=>{
      if(player.touch.active&&pointSegmentDistance(player.touch,previous,{x:projectile.x,y:projectile.y})<.045&&registerHazardHit(puzzle,player,`p${projectile.id}`))
        restart(room,send,'projectile',HIT_PENALTY,true);
    });
  }
  puzzle.projectiles=puzzle.projectiles.filter(projectile=>projectile.x>=-.15&&projectile.x<=1.15&&projectile.y>=-.15&&projectile.y<=1.15);
  puzzle.blasts=puzzle.blasts.filter(blast=>motionNow<blast.detonatesAt+blast.durationMs);
  for(const blast of puzzle.blasts) {
    if(motionNow<blast.detonatesAt) continue;
    const radius=blast.maxRadius*clamp((motionNow-blast.detonatesAt)/blast.durationMs,0,1);
    const previousRadius=blast.maxRadius*clamp((previousMotion-blast.detonatesAt)/blast.durationMs,0,1);
    room.players.forEach(player=>{
      if(!player.touch.active) return;
      const radial=dist(player.touch,blast);
      if(radial>=previousRadius-.04&&radial<=radius+.04&&registerHazardHit(puzzle,player,`b${blast.id}`)) restart(room,send,'explosion',HIT_PENALTY,true);
    });
  }
  if(now-puzzle.lastHazardBroadcastAt>=100) {
    puzzle.lastHazardBroadcastAt=now;
    const remaining=Math.max(0,puzzle.deadlineAt-now);
    room.players.forEach(player=>send(player.ws,{type:'puzzle_hazards',level:puzzle.level,score:puzzle.score,totalScore:puzzle.totalScore,remainingMs:remaining,motionFactor:activeFactor,speedFactor,
      projectiles:puzzle.projectiles.map(item=>({...item,vx:item.vx*speedFactor*activeFactor,vy:item.vy*speedFactor*activeFactor})),
      blasts:puzzle.blasts.map(blast=>({...blast,radius:motionNow<blast.detonatesAt?0:blast.maxRadius*clamp((motionNow-blast.detonatesAt)/blast.durationMs,0,1),armed:motionNow<blast.detonatesAt}))}));
  }
}

export function recordPuzzleTouch(room,player,previous,next,active,now,send,movementFactor=0) {
  const puzzle=room.puzzle;
  if(!puzzle||puzzle.completed) return;
  player.puzzleMotionFactor=movementFactor;
  player.puzzleMotionAt=now;
  if(!active||!next) {
    if(puzzle.startedAt&&!puzzle.needsStart) restart(room,send,'lift',HIT_PENALTY);
    player.puzzleAtStart=false; player.puzzleChoice=null;
    return;
  }
  if(puzzle.needsStart) {
    player.puzzleAtStart=atStart(next,puzzle);
    if(room.players.every(candidate=>candidate.touch.active&&candidate.puzzleAtStart)) {
      puzzle.needsStart=false; puzzle.startedAt=now;
      if(!puzzle.levelStartedAt) {
        puzzle.levelStartedAt=now; puzzle.deadlineAt=now+LEVEL_TIME_MS; puzzle.remainingMs=LEVEL_TIME_MS; resetHazards(puzzle,now);
      }
      sendState(room,send,'puzzle_started',{serverTs:now,remainingMs:puzzle.remainingMs});
    }
    return;
  }
  if(puzzleTouchHitsWall(previous,next,puzzle.walls)) {
    player.puzzleAtStart=false; restart(room,send,'wall',HIT_PENALTY); return;
  }
  player.puzzleAtStart=false;
}

function refreshChoices(room,send,now) {
  const puzzle=room.puzzle;
  const checkpointRadius=Math.max(.02,.05-puzzle.level*.0025);
  if(!puzzle.playerChoices) puzzle.playerChoices=new Map();
  const oldHold=puzzle.holdStartedAt;
  const nearest=room.players.map(player=>{
    if(!player.touch.active) return null;
    const choices=puzzle.checkpoints.map(checkpoint=>distanceToPuzzleCheckpoint(player.touch,checkpoint));
    const index=choices.indexOf(Math.min(...choices));
    return choices[index]<=checkpointRadius?index:null;
  });
  const distinct=nearest[0]!=null&&nearest[1]!=null&&nearest[0]!==nearest[1];
  room.players.forEach((player,index)=>{
    const reached=nearest[index]!=null,accepted=distinct;
    const token=`${reached}:${accepted}:${nearest[index]??-1}`;
    if(puzzle.playerChoices.get(player.id)!==token) {
      puzzle.playerChoices.set(player.id,token);
      send(player.ws,{type:'puzzle_checkpoint',reached,accepted,checkpoint:reached?nearest[index]:-1});
    }
  });
  if(distinct) {
    if(!puzzle.holdStartedAt) { puzzle.holdStartedAt=now; sendState(room,send,'puzzle_hold',{holdMs:0,requiredMs:2500}); }
    else if(now-puzzle.holdStartedAt>=2500) completeLevel(room,send,now);
    else if(now-puzzle.lastProgressAt>=250) { puzzle.lastProgressAt=now; sendState(room,send,'puzzle_hold',{holdMs:now-puzzle.holdStartedAt,requiredMs:2500}); }
  } else if(oldHold) { puzzle.holdStartedAt=0; sendState(room,send,'puzzle_hold',{holdMs:0,requiredMs:2500}); }
}

function completeLevel(room,send,now) {
  const puzzle=room.puzzle;
  if(puzzle.completed) return;
  puzzle.completed=true;
  const remainingMs=Math.max(0,puzzle.deadlineAt-now),timePoints=Math.floor(remainingMs/1000)*100;
  const flawlessBonus=puzzle.wasHit?0:1_000;
  const levelPoints=puzzle.score+timePoints+flawlessBonus;
  puzzle.totalScore+=levelPoints;
  sendState(room,send,'puzzle_level_complete',{elapsedMs:Math.max(0,now-puzzle.levelStartedAt),levelBuffer:puzzle.score,timePoints,flawlessBonus,levelPoints,totalScore:puzzle.totalScore,remainingMs,holdMs:2500});
  const nextLevel=puzzle.level+1;
  clearTimeout(puzzle.nextLevelTimer);
  puzzle.nextLevelTimer=setTimeout(()=>{
    if(room.phase!=='active'||room.mode!=='PUZZLE') return;
    const totalScore=puzzle.totalScore;
    room.puzzle={...createPuzzleState(nextLevel),score:STARTING_SCORE,totalScore,wasHit:false,completed:false,levelStartedAt:0,sequence:puzzle.sequence+1,remainingMs:LEVEL_TIME_MS,
      motionMs:0,hazardLastAt:0,nextProjectileAt:2400,nextBlastAt:6200,projectiles:[],blasts:[],hazardSequence:0,hitHazards:new Map(),lastHazardBroadcastAt:0};
    room.players.forEach(player=>{player.puzzleAtStart=false;player.puzzleChoice=null;player.puzzleMotionFactor=0;});
    publishLevel(room,send);
  },2200);
}

function timeoutLevel(room,send,now) {
  finishPuzzle(room,send,'time');
}

export function updatePuzzle(room,now,send) {
  const puzzle=room.puzzle;
  if(!puzzle||puzzle.completed) return;
  if(puzzle.levelStartedAt) {
    puzzle.remainingMs=Math.max(0,puzzle.deadlineAt-now);
    if(!puzzle.remainingMs) { timeoutLevel(room,send,now); return; }
    if(room.players.every(player=>player.touch.active)) advanceHazards(room,puzzle,now,send);
    else puzzle.hazardLastAt=now;
  }
  if(puzzle.needsStart) return;
  if(room.players.some(player=>!player.touch.active)) { restart(room,send,'lift',HIT_PENALTY); return; }
  if(puzzle.needsStart) return;
  refreshChoices(room,send,now);
}
