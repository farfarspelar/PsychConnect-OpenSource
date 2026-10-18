const clamp=(value,min,max)=>Math.max(min,Math.min(max,value));
const key=(x,y)=>`${x},${y}`;

/** Small growing perfect maze. Geometry is normalized so both clients render the same level. */
export function createPuzzleLevel(level,random=Math.random) {
  const cols=Math.min(5+Math.floor((level-1)/2),10);
  const rows=Math.min(9+level,14);
  const startCell={x:Math.floor(cols/2),y:rows-1};
  const passages=new Set();
  const visited=new Set([key(startCell.x,startCell.y)]);
  const stack=[startCell];
  const directions=[{x:0,y:-1},{x:1,y:0},{x:0,y:1},{x:-1,y:0}];
  while(stack.length) {
    const current=stack[stack.length-1];
    const options=directions.map(direction=>({x:current.x+direction.x,y:current.y+direction.y,direction}))
      .filter(next=>next.x>=0&&next.x<cols&&next.y>=0&&next.y<rows&&!visited.has(key(next.x,next.y)));
    if(!options.length) { stack.pop(); continue; }
    const next=options[Math.floor(random()*options.length)];
    passages.add(`${key(current.x,current.y)}|${key(next.x,next.y)}`);
    passages.add(`${key(next.x,next.y)}|${key(current.x,current.y)}`);
    visited.add(key(next.x,next.y)); stack.push({x:next.x,y:next.y});
  }
  const left=0.12,right=0.88,top=0.13,bottom=0.78;
  const cellW=(right-left)/cols,cellH=(bottom-top)/rows;
  const center=(x,y)=>({x:left+(x+.5)*cellW,y:top+(y+.5)*cellH});
  const isOpen=(x,y,nx,ny)=>passages.has(`${key(x,y)}|${key(nx,ny)}`);
  const walls=[];
  for(let y=0;y<rows;y++) for(let x=0;x<cols;x++) {
    const x0=left+x*cellW,x1=x0+cellW,y0=top+y*cellH,y1=y0+cellH;
    if(x===cols-1||!isOpen(x,y,x+1,y)) walls.push({x1,y1:y0,x2:x1,y2:y1});
    if(y===rows-1) {
      if(x!==startCell.x) walls.push({x1,y1,x2:x1,y2:y1});
      else { const gap=Math.min(cellW*.34,.035); walls.push({x1,y1,x2:(x0+x1-gap)/2,y2:y1},{x1:(x0+x1+gap)/2,y1,x2,y2:y1}); }
    } else if(!isOpen(x,y,x,y+1)) walls.push({x1,y1,x2:x1,y2:y1});
    if(x===0) walls.push({x1:x0,y1:y0,x2:x0,y2:y1});
    if(y===0) walls.push({x1:x0,y1:y0,x2:x1,y2:y0});
  }
  const distances=new Map([[key(startCell.x,startCell.y),0]]),queue=[startCell];
  while(queue.length) {
    const current=queue.shift(),distance=distances.get(key(current.x,current.y));
    for(const direction of directions) {
      const x=current.x+direction.x,y=current.y+direction.y,k=key(x,y);
      if(x<0||x>=cols||y<0||y>=rows||!isOpen(current.x,current.y,x,y)||distances.has(k)) continue;
      distances.set(k,distance+1); queue.push({x,y});
    }
  }
  const cells=[...distances.entries()].map(([cell,distance])=>({cell:cell.split(',').map(Number),distance}));
  const pick=side=>cells.filter(item=>side==='left'?item.cell[0]<cols/2:item.cell[0]>=cols/2)
    .sort((a,b)=>b.distance-a.distance)[0]||cells.sort((a,b)=>b.distance-a.distance)[0];
  const checkpoints=[pick('left'),pick('right')].map((item,index)=>({id:index,...center(item.cell[0],item.cell[1])}));
  if(checkpoints[0].x===checkpoints[1].x&&checkpoints[0].y===checkpoints[1].y) checkpoints[1]=center(0,0);
  return {level,cols,rows,start:{x:center(startCell.x,startCell.y).x,y:0.89},walls,checkpoints};
}

const pointSegmentDistance=(point,segment)=>{
  const dx=segment.x2-segment.x1,dy=segment.y2-segment.y1,length=dx*dx+dy*dy;
  const t=length===0?0:clamp(((point.x-segment.x1)*dx+(point.y-segment.y1)*dy)/length,0,1);
  return Math.hypot(point.x-(segment.x1+t*dx),point.y-(segment.y1+t*dy));
};
const orientation=(a,b,c)=>(b.x-a.x)*(c.y-a.y)-(b.y-a.y)*(c.x-a.x);
const onSegment=(a,b,p)=>p.x>=Math.min(a.x,b.x)-1e-10&&p.x<=Math.max(a.x,b.x)+1e-10&&p.y>=Math.min(a.y,b.y)-1e-10&&p.y<=Math.max(a.y,b.y)+1e-10;
const segmentsIntersect=(a,b,c,d)=>{
  const o1=orientation(a,b,c),o2=orientation(a,b,d),o3=orientation(c,d,a),o4=orientation(c,d,b);
  if(o1*o2<0&&o3*o4<0) return true;
  return (Math.abs(o1)<1e-10&&onSegment(a,b,c))||(Math.abs(o2)<1e-10&&onSegment(a,b,d))||
    (Math.abs(o3)<1e-10&&onSegment(c,d,a))||(Math.abs(o4)<1e-10&&onSegment(c,d,b));
};
const segmentDistance=(a,b,c,d)=>segmentsIntersect(a,b,c,d)?0:Math.min(pointSegmentDistance(a,{x1:c.x,y1:c.y,x2:d.x,y2:d.y}),pointSegmentDistance(b,{x1:c.x,y1:c.y,x2:d.x,y2:d.y}),pointSegmentDistance(c,{x1:a.x,y1:a.y,x2:b.x,y2:b.y}),pointSegmentDistance(d,{x1:a.x,y1:a.y,x2:b.x,y2:b.y}));

export function puzzleTouchHitsWall(previous,next,walls,tolerance=0.009) {
  if(!previous||!next) return false;
  return walls.some(wall=>segmentDistance(previous,next,{x:wall.x1,y:wall.y1},{x:wall.x2,y:wall.y2})<tolerance);
}

export function createPuzzleState(level=1,random=Math.random) {
  return {...createPuzzleLevel(level,random),needsStart:true,startedAt:0,holdStartedAt:0,lastProgressAt:0,checkpointPlayers:{},lastSentState:''};
}

export function distanceToPuzzleCheckpoint(point,checkpoint) {
  return Math.hypot(point.x-checkpoint.x,point.y-checkpoint.y);
}
