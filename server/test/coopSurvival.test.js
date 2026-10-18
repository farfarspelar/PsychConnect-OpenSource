import test from 'node:test';
import assert from 'node:assert/strict';
import { resetCoopSurvival,updateCoopSurvival } from '../coopSurvival.js';

const player=(now,motionFactor=0)=>({
  touch:{active:true},lastPoint:{x:.5,y:.7},survivalMotionAt:now,survivalMotionFactor:motionFactor
});

test('co-op survival score and difficulty time pause when both players are still',()=>{
  const room={players:[player(1_000),player(1_000)]};
  const messages=[];
  const broadcast=(_room,message)=>messages.push(message);
  resetCoopSurvival(room,1_000);

  updateCoopSurvival(room,1_000,broadcast,()=>{});
  updateCoopSurvival(room,5_000,broadcast,()=>{});
  assert.equal(room.coopSurvival.activeElapsedMs,0);
  assert.equal(room.coopSurvival.hazardTimeMs,0);
  assert.equal(messages.filter(message=>message.type==='survival_duo_state').at(-1).level,1);
});

test('co-op survival counts shared movement time and freezes it again at rest',()=>{
  const room={players:[player(1_000),player(1_000)]};
  const messages=[];
  const broadcast=(_room,message)=>messages.push(message);
  resetCoopSurvival(room,1_000);
  updateCoopSurvival(room,1_000,broadcast,()=>{});
  updateCoopSurvival(room,5_000,broadcast,()=>{});

  room.players[0].survivalMotionAt=5_100;
  room.players[0].survivalMotionFactor=1;
  updateCoopSurvival(room,5_200,broadcast,()=>{});
  assert.equal(Math.round(room.coopSurvival.activeElapsedMs),200);

  room.players[0].survivalMotionAt=5_200;
  updateCoopSurvival(room,5_300,broadcast,()=>{});
  assert.equal(Math.round(room.coopSurvival.activeElapsedMs),300);

  room.players[0].survivalMotionAt=5_300;
  room.players[0].survivalMotionFactor=0;
  updateCoopSurvival(room,5_400,broadcast,()=>{});
  updateCoopSurvival(room,6_000,broadcast,()=>{});
  assert.equal(Math.round(room.coopSurvival.activeElapsedMs),300);
  assert.equal(Math.round(room.coopSurvival.hazardTimeMs),300);
});
