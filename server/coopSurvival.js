const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
const distance=(a,b)=>Math.hypot(a.x-b.x,a.y-b.y);
const segmentDistance=(p,a,b)=>{
  const dx=b.x-a.x,dy=b.y-a.y,length=dx*dx+dy*dy;
  const t=length===0?0:clamp(((p.x-a.x)*dx+(p.y-a.y)*dy)/length,0,1);
  return Math.hypot(p.x-(a.x+t*dx),p.y-(a.y+t*dy));
};

function createProjectile(target,now,id) {
  const side=Math.floor(Math.random()*4);
  const point=side===0?{x:-.04,y:Math.random()}:side===1?{x:1.04,y:Math.random()}:side===2?{x:Math.random(),y:-.04}:{x:Math.random(),y:1.04};
  const dx=target.x-point.x,dy=target.y-point.y,length=Math.hypot(dx,dy)||1,speed=.28;
  const angle=Math.random()*.6-.3,ux=dx/length,uy=dy/length;
  return {id,x:point.x,y:point.y,vx:(ux*Math.cos(angle)-uy*Math.sin(angle))*speed,vy:(ux*Math.sin(angle)+uy*Math.cos(angle))*speed,createdAt:now};
}

function farBlastCenter(points) {
  let best={x:.5,y:.5},bestDistance=-1;
  for(let i=0;i<16;i++) {
    const candidate={x:.14+Math.random()*.72,y:.13+Math.random()*.74};
    const nearest=Math.min(...points.map(point=>distance(candidate,point)));
    if(nearest>bestDistance) { best=candidate; bestDistance=nearest; }
    if(nearest>=.2) return candidate;
  }
  return best;
}

export function resetCoopSurvival(room,now) {
  room.coopSurvival={startedAt:0,lastAt:now,hazardTimeMs:0,nextProjectileAt:1700,nextBlastAt:3500,
    projectiles:[],blasts:[],sequence:0,finished:false,lastBroadcastAt:0,lastWaitingReadyCount:-1};
}

/** Server-authoritative shared survival simulation. Either player's collision ends the run. */
export function updateCoopSurvival(room,now,broadcast,send) {
  const state=room.coopSurvival;
  if(!state||state.finished||room.players.length!==2) return;
  const points=room.players.map(player=>player.lastPoint||{x:.5,y:.76});
  if(!state.startedAt) {
    if(!room.players.every(player=>player.touch.active)) {
      const readyCount=room.players.filter(player=>player.touch.active).length;
      if(readyCount!==state.lastWaitingReadyCount) { state.lastWaitingReadyCount=readyCount; broadcast(room,{type:'survival_duo_state',started:false,readyCount,projectiles:[],blasts:[]}); }
      return;
    }
    state.startedAt=now; state.lastAt=now; state.hazardTimeMs=0;
    broadcast(room,{type:'survival_duo_start',serverTs:now});
  }
  const elapsed=(now-state.startedAt)/1000;
  const activeFactor=Math.max(...room.players.map(player=>now-(player.survivalMotionAt||0)>320?0:(player.survivalMotionFactor||0)),0);
  const previousHazardTime=state.hazardTimeMs;
  const dtWall=Math.min(.35,Math.max(0,(now-state.lastAt)/1000));
  state.hazardTimeMs+=dtWall*activeFactor*1000;
  const hazardNow=state.hazardTimeMs;
  const projectileInterval=Math.max(520,2600/(1+elapsed/32));
  const blastInterval=Math.max(2200,7800/(1+elapsed/38));
  const speedFactor=Math.min(2.35,1+elapsed/68);
  if(hazardNow>=state.nextProjectileAt) {
    const target=points[Math.floor(Math.random()*points.length)];
    state.projectiles.push(createProjectile(target,now,++state.sequence));
    state.nextProjectileAt=hazardNow+projectileInterval;
  }
  if(hazardNow>=state.nextBlastAt) {
    const roll=Math.random(),count=roll<.025?3:roll<.185?2:1;
    for(let i=0;i<count;i++) state.blasts.push({id:++state.sequence,...farBlastCenter(points),detonatesAt:hazardNow+850+i*180,durationMs:2300,maxRadius:.78});
    state.nextBlastAt=hazardNow+blastInterval;
  }
  const dt=(hazardNow-previousHazardTime)/1000;
  state.lastAt=now;
  let hit=false;
  for(const projectile of state.projectiles) {
    const previous={x:projectile.x,y:projectile.y};
    projectile.x+=projectile.vx*speedFactor*dt; projectile.y+=projectile.vy*speedFactor*dt;
    if(points.some(point=>segmentDistance(point,previous,{x:projectile.x,y:projectile.y})<.052)) hit=true;
  }
  state.projectiles=state.projectiles.filter(projectile=>projectile.x>=-.15&&projectile.x<=1.15&&projectile.y>=-.15&&projectile.y<=1.15);
  state.blasts=state.blasts.filter(blast=>hazardNow<blast.detonatesAt+blast.durationMs);
  for(const blast of state.blasts) {
    if(hazardNow<blast.detonatesAt) continue;
    const current=blast.maxRadius*clamp((hazardNow-blast.detonatesAt)/blast.durationMs,0,1);
    const previous=blast.maxRadius*clamp((previousHazardTime-blast.detonatesAt)/blast.durationMs,0,1);
    if(points.some(point=>{ const radial=distance(point,blast); return radial>=previous-.045&&radial<=current+.045; })) hit=true;
  }
  if(hit) {
    state.finished=true;
    const elapsedMs=Math.max(0,Math.round(now-state.startedAt));
    broadcast(room,{type:'survival_duo_over',elapsedMs,level:1+Math.floor(elapsed/20)});
    return;
  }
  if(now-state.lastBroadcastAt>=90) {
    state.lastBroadcastAt=now;
    broadcast(room,{type:'survival_duo_state',started:true,elapsedMs:Math.round(now-state.startedAt),level:1+Math.floor(elapsed/20),
      speedFactor,motionFactor:activeFactor,serverTs:now,projectiles:state.projectiles,
      blasts:state.blasts.map(blast=>({...blast,remainingMs:Math.max(0,blast.detonatesAt-hazardNow),
        radius:hazardNow<blast.detonatesAt?0:blast.maxRadius*clamp((hazardNow-blast.detonatesAt)/blast.durationMs,0,1),armed:hazardNow<blast.detonatesAt}))});
  }
}
