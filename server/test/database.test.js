import test from 'node:test';
import assert from 'node:assert/strict';
import { databaseFromEnv } from '../db/database.js';

test('database connections verify TLS certificates by default',async()=>{
  const pool=databaseFromEnv({DATABASE_URL:'postgres://user:password@localhost:5432/psychconnect'});
  try { assert.deepEqual(pool.options.ssl,{rejectUnauthorized:true}); }
  finally { await pool.end(); }
});

test('database TLS can be disabled only by explicit local configuration',async()=>{
  const pool=databaseFromEnv({DATABASE_URL:'postgres://user:password@localhost:5432/psychconnect',DATABASE_SSL:'disable'});
  try { assert.equal(pool.options.ssl,false); }
  finally { await pool.end(); }
});

test('self-signed database certificates require an explicit insecure opt-in',async()=>{
  const pool=databaseFromEnv({DATABASE_URL:'postgres://user:password@localhost:5432/psychconnect',DATABASE_SSL:'insecure'});
  try { assert.deepEqual(pool.options.ssl,{rejectUnauthorized:false}); }
  finally { await pool.end(); }
});
