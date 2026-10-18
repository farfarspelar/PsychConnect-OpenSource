/** Store server-received coordinates only; client clocks never affect competitive scoring. */
export function recordScoringPosition(history,sample,maxSamples=48) {
  if(!Array.isArray(history)||!sample||!Number.isFinite(sample.timeMs)||typeof sample.active!=='boolean') return false;
  const last=history.at(-1);
  if(last&&sample.timeMs<last.timeMs) return false;
  if(last&&sample.timeMs===last.timeMs) history[history.length-1]=sample;
  else history.push(sample);
  while(history.length>maxSamples) history.shift();
  return true;
}

/** Sample a server-time position without extrapolating into the future. */
export function sampleScoringPosition(history,targetMs,maxHoldMs=300) {
  if(!Array.isArray(history)||!Number.isFinite(targetMs)||history.length===0) return null;
  let before=null,after=null;
  for(const sample of history) {
    if(sample.timeMs<=targetMs) before=sample;
    else { after=sample; break; }
  }
  if(!before||!before.active||!Number.isFinite(before.x)||!Number.isFinite(before.y)) return null;
  if(after&&!after.active) return {x:before.x,y:before.y};
  if(after?.active&&Number.isFinite(after.x)&&Number.isFinite(after.y)) {
    const span=after.timeMs-before.timeMs;
    const fraction=span>0?Math.max(0,Math.min(1,(targetMs-before.timeMs)/span)):0;
    return {x:before.x+(after.x-before.x)*fraction,y:before.y+(after.y-before.y)*fraction};
  }
  return targetMs-before.timeMs<=maxHoldMs?{x:before.x,y:before.y}:null;
}
