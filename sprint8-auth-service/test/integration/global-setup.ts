import { Client } from 'pg';
import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * Creates and reinitialises the dedicated integration-test database
 * (trading_system_db_test by default) with the auth + trading schemas.
 * Credentials resolve from process.env first, then sprint8-auth-service/.env,
 * then the repository defaults; the default `npm test` (unit) never touches
 * this — run with `npm run test:integration`.
 */
export default async function () {
  const env = readEnv();

  const host = env.DB_HOST || 'localhost';
  const port = parseInt(env.DB_PORT || '5432', 10);
  const user = env.DB_USER || 'postgres';
  const password = env.DB_PASSWORD || 'postgres';
  const adminDb = env.DB_NAME || 'trading_system_db';
  const testDb = env.DB_NAME_TEST || 'trading_system_db_test';

  const admin = new Client({ host, port, user, password, database: adminDb });
  await admin.connect();
  const exists = await admin.query('SELECT 1 FROM pg_database WHERE datname = $1', [testDb]);
  if (exists.rowCount === 0) {
    await admin.query(`CREATE DATABASE "${testDb}"`);
  }
  await admin.end();

  const db = new Client({ host, port, user, password, database: testDb });
  await db.connect();
  await db.query('DROP TABLE IF EXISTS auth.refresh_token CASCADE');
  await db.query('DROP TABLE IF EXISTS trading.account CASCADE');
  await db.query('DROP TABLE IF EXISTS auth.users CASCADE');
  await db.query('CREATE SCHEMA IF NOT EXISTS auth');
  await db.query('CREATE SCHEMA IF NOT EXISTS trading');
  await db.query(`CREATE TABLE auth.users (
      user_id       BIGSERIAL PRIMARY KEY,
      uuid          UUID NOT NULL DEFAULT gen_random_uuid(),
      first_name    TEXT NOT NULL,
      middle_name   TEXT,
      last_name     TEXT NOT NULL,
      username      VARCHAR(64) NOT NULL UNIQUE,
      email         VARCHAR(255) NOT NULL UNIQUE,
      password_hash TEXT NOT NULL,
      roles         TEXT[] NOT NULL DEFAULT ARRAY['CUSTOMER'],
      created_on    TIMESTAMPTZ NOT NULL DEFAULT now()
    )`);
  await db.query('CREATE UNIQUE INDEX IF NOT EXISTS uq_users_uuid ON auth.users (uuid)');
  await db.query(`CREATE TABLE auth.refresh_token (
      refresh_token_id BIGSERIAL PRIMARY KEY,
      user_id          BIGINT NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
      token_hash       TEXT NOT NULL UNIQUE,
      family_id        UUID NOT NULL DEFAULT gen_random_uuid(),
      expires_at       TIMESTAMPTZ NOT NULL,
      revoked_on       TIMESTAMPTZ,
      created_on       TIMESTAMPTZ NOT NULL DEFAULT now()
    )`);
  await db.query(`CREATE TABLE trading.account (
      account_id   BIGSERIAL PRIMARY KEY,
      user_id      BIGINT NOT NULL UNIQUE REFERENCES auth.users (user_id) ON DELETE CASCADE,
      currency     VARCHAR(8) NOT NULL DEFAULT 'USD',
      cash_balance NUMERIC(19,2) NOT NULL DEFAULT 100000.00,
      status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
      version      BIGINT NOT NULL DEFAULT 1,
      closed_at    TIMESTAMPTZ
    )`);
  await db.end();
}

function readEnv(): Record<string, string> {
  const values: Record<string, string> = {};
  const envPath = join(__dirname, '../..', '.env');
  try {
    const content = readFileSync(envPath, 'utf8');
    for (const line of content.split(/\r?\n/)) {
      const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
      if (match) values[match[1]] = match[2];
    }
  } catch {
    // no sprint8-auth-service/.env — rely on process.env / defaults
  }
  return { ...values, ...normalize(process.env) };
}

function normalize(record: NodeJS.ProcessEnv): Record<string, string> {
  const out: Record<string, string> = {};
  for (const key of Object.keys(record)) {
    if (record[key] !== undefined) out[key] = record[key] as string;
  }
  return out;
}