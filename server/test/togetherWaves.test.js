import test from 'node:test';
import assert from 'node:assert/strict';
import { createTogetherWaveGroup,classifyTogetherWaveContact,togetherWaveResult } from '../togetherWaves.js';

test('Together wave groups are usually one wave, sometimes two, and rarely three',()=>{
  const values=[0.9,0.1,0.01];
  const counts=values.map((value,index)=>createTogetherWaveGroup(index+1,10,()=>value).waves.length);
  assert.deepEqual(counts,[1,2,3]);
});

test('wave contacts distinguish an opening from the soft wave front',()=>{
  const wave={x:0.5,y:0.5,gaps:[0,Math.PI],startAt:0,durationMs:4_800,maxRadius:0.76};
  const now=2_500; const radius=wave.maxRadius*(now/wave.durationMs);
  const safe=classifyTogetherWaveContact(wave,{x:wave.x+radius,y:wave.y},now);
  const bumped=classifyTogetherWaveContact(wave,{x:wave.x,y:wave.y+radius},now);
  assert.equal(safe.outcome,'safe'); assert.equal(safe.gapIndex,0);
  assert.equal(bumped.outcome,'bumped');
  assert.equal(classifyTogetherWaveContact(wave,{x:0.5,y:0.5},now),null,'being inside the expanding front is not a collision');
  assert.equal(classifyTogetherWaveContact(wave,{x:wave.x+radius,y:wave.y},-1),null,'waves do not score before they begin');
});

test('choosing the same nearby opening earns more than separate safe paths',()=>{
  const closeA={outcome:'safe',gapIndex:0,angle:0.1};
  const closeB={outcome:'safe',gapIndex:0,angle:0.2};
  const separate={outcome:'safe',gapIndex:1,angle:Math.PI};
  assert.deepEqual(togetherWaveResult(closeA,closeB),{delta:8,cue:'shared_passage'});
  assert.deepEqual(togetherWaveResult(closeA,separate),{delta:3,cue:'safe_passage'});
  assert.deepEqual(togetherWaveResult(closeA,null),{delta:2,cue:'one_safe'});
  assert.deepEqual(togetherWaveResult({outcome:'bumped'},null),{delta:-2,cue:'soft_bump'});
  assert.deepEqual(togetherWaveResult(null,null),{delta:0,cue:'wave_passed'});
});
