import test from 'node:test';
import assert from 'node:assert/strict';
import { recordScoringPosition,sampleScoringPosition } from '../scoringTimeline.js';

test('server receipt samples interpolate at the delayed scoring time',()=>{
  const history=[];
  recordScoringPosition(history,{timeMs:100,active:true,x:0.2,y:0.4});
  recordScoringPosition(history,{timeMs:200,active:true,x:0.6,y:0.8});
  const point=sampleScoringPosition(history,150);
  assert.equal(point.x,0.4); assert.ok(Math.abs(point.y-0.6)<1e-12);
});

test('timeline rejects out-of-order samples and never scores before contact begins',()=>{
  const history=[];
  recordScoringPosition(history,{timeMs:200,active:true,x:0.6,y:0.8});
  assert.equal(recordScoringPosition(history,{timeMs:199,active:true,x:0.1,y:0.1}),false);
  assert.equal(sampleScoringPosition(history,100),null);
  assert.deepEqual(sampleScoringPosition(history,250),{x:0.6,y:0.8});
  assert.equal(sampleScoringPosition(history,501),null,'held coordinates expire after the configured scoring hold');
});

test('a lift is applied at its server receipt time, after the last active sample',()=>{
  const history=[
    {timeMs:100,active:true,x:0.4,y:0.5},
    {timeMs:200,active:false}
  ];
  assert.deepEqual(sampleScoringPosition(history,150),{x:0.4,y:0.5});
  assert.equal(sampleScoringPosition(history,200),null);
});
