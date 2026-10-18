import test from 'node:test';
import assert from 'node:assert/strict';
import { createTtlCache } from '../api/ttlCache.js';

test('TTL cache expires entries and refreshes least recently used order',()=>{
  let now=0;
  const cache=createTtlCache({ttlMs:100,maxEntries:2,clock:()=>now});
  cache.set('a',{rank:1}); cache.set('b',{rank:2});
  assert.deepEqual(cache.get('a'),{rank:1});
  cache.set('c',{rank:3});
  assert.equal(cache.get('b'),undefined);
  assert.equal(cache.size,2);
  now=100;
  assert.equal(cache.get('a'),undefined);
  assert.equal(cache.size,1);
});
