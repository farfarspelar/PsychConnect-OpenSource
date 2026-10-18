import pg from 'pg';
import { readdir,readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { RIVAL_ACHIEVEMENTS } from '../domain/rivalries.js';

export function databaseFromEnv(env=process.env) {
  if(!env.DATABASE_URL) return null;
  const ssl=env.DATABASE_SSL==='disable'?false:{rejectUnauthorized:env.DATABASE_SSL!=='insecure'};
  return new pg.Pool({
    connectionString:env.DATABASE_URL,
    ssl,
    max:Number(env.PG_POOL_MAX||8),
    idleTimeoutMillis:30_000,
    connectionTimeoutMillis:5_000
  });
}

export async function migrateDatabase(pool,migrationsDir=fileURLToPath(new URL('../migrations/',import.meta.url))) {
  await pool.query('CREATE TABLE IF NOT EXISTS schema_migrations (version VARCHAR(128) PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())');
  await pool.query('CREATE TABLE IF NOT EXISTS schema_migration_lock (id SMALLINT PRIMARY KEY CHECK (id=1))');
  await pool.query('INSERT INTO schema_migration_lock(id) VALUES(1) ON CONFLICT(id) DO NOTHING');
  const files=(await readdir(migrationsDir)).filter(file=>file.endsWith('.sql')).sort();
  for(const file of files) {
    const sql=await readFile(path.join(migrationsDir,file),'utf8');
    const client=await pool.connect();
    try {
      await client.query('BEGIN');
      // Serialize runners across processes, then check the applied version
      // while holding the same transaction-scoped row lock.
      await client.query('SELECT id FROM schema_migration_lock WHERE id=1 FOR UPDATE');
      const exists=await client.query('SELECT version FROM schema_migrations WHERE version=$1',[file]);
      if(exists.rowCount) { await client.query('COMMIT'); continue; }
      await client.query(sql);
      await client.query('INSERT INTO schema_migrations(version) VALUES($1)',[file]);
      await client.query('COMMIT');
    } catch(error) {
      await client.query('ROLLBACK');
      throw error;
    } finally { client.release(); }
  }
  for(const achievement of RIVAL_ACHIEVEMENTS) {
    await pool.query(`INSERT INTO achievements(code,title,description) VALUES($1,$2,$3)
      ON CONFLICT(code) DO UPDATE SET title=EXCLUDED.title,description=EXCLUDED.description`,
    [achievement.code,achievement.title,achievement.description]);
  }
}
