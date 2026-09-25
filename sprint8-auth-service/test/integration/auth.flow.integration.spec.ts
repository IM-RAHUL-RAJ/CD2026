/**
 * Integration tests: real PostgreSQL (trading_system_db_test) + the real HTTP
 * pipeline (createApplication()) — no mocks. Run with `npm run test:integration`.
 */
import { INestApplication } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as nodeCrypto from 'crypto';
import { createHash } from 'crypto';
import jwt from 'jsonwebtoken';
import { Client } from 'pg';
import { createApplication } from '../../src/init-app';
import { REFRESH_COOKIE_NAME } from '../../src/common/cookies';

const TEST_DB = process.env.DB_NAME_TEST || 'trading_system_db_test';

interface HttpResult {
  status: number;
  body: any;
  setCookie: string[];
}

async function start(baseUrl: string, path: string, init: RequestInit): Promise<HttpResult> {
  const response = await fetch(baseUrl + path, init);
  const text = await response.text();
  let body: any = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  const setCookie: string[] =
    typeof response.headers.getSetCookie === 'function'
      ? response.headers.getSetCookie()
      : [response.headers.get('set-cookie') ?? ''].filter(Boolean);
  return { status: response.status, body, setCookie };
}

function post(baseUrl: string, path: string, payload: unknown, cookie?: string): Promise<HttpResult> {
  return start(baseUrl, path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(cookie ? { Cookie: cookie } : {}),
    },
    body: JSON.stringify(payload),
  });
}

function get(baseUrl: string, path: string, bearer?: string): Promise<HttpResult> {
  return start(baseUrl, path, {
    method: 'GET',
    headers: bearer ? { Authorization: `Bearer ${bearer}` } : {},
  });
}

function cookieValue(setCookie: string[]): string | null {
  // The controller emits a clear-cookie header (empty value) followed by the
  // real token cookie; pick the value-bearing one (the last match).
  const raw = [...setCookie].reverse().find(
    (c) => c.startsWith(`${REFRESH_COOKIE_NAME}=`) && c.length > REFRESH_COOKIE_NAME.length + 1,
  );
  if (!raw) return null;
  const pair = raw.split(';')[0];
  return pair.slice(REFRESH_COOKIE_NAME.length + 1);
}

function sha256(value: string): string {
  return createHash('sha256').update(value).digest('hex');
}

/**
 * Replicates the browser Web Crypto sealing: RSA-OAEP-wrapped AES-256-GCM with
 * the 16-byte authentication tag appended to the ciphertext and a fresh nonce
 * plus expiry inside the sealed JSON.
 */
function seal(publicKeyPem: string, payload: Record<string, unknown>): string {
  const publicKey = nodeCrypto.createPublicKey(publicKeyPem);
  const aesKey = nodeCrypto.randomBytes(32);
  const iv = nodeCrypto.randomBytes(12);
  const cipher = nodeCrypto.createCipheriv('aes-256-gcm', aesKey, iv);
  const body = Buffer.concat([
    cipher.update(JSON.stringify(payload), 'utf8'),
    cipher.final(),
  ]);
  const sealed = Buffer.concat([body, cipher.getAuthTag()]);
  const encKey = nodeCrypto.publicEncrypt(
    {
      key: publicKey,
      padding: nodeCrypto.constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: 'sha256',
    },
    aesKey,
  );
  return [encKey.toString('base64'), iv.toString('base64'), sealed.toString('base64')].join('.');
}

function sealedBody(pem: string, fields: Record<string, unknown>): { request: string } {
  return {
    request: seal(pem, {
      ...fields,
      nonce: nodeCrypto.randomBytes(16).toString('hex'),
      expiresAt: Date.now() + 30000,
    }),
  };
}

function randomUsername(prefix: string): string {
  return `${prefix}_${Date.now().toString(36)}_${Math.floor(Math.random() * 1e6).toString(36)}`;
}

describe('Auth integration (real DB + HTTP pipeline)', () => {
  let app: INestApplication;
  let baseUrl: string;
  let db: Client;
  let jwtSecret: string;

  beforeAll(async () => {
    process.env.DB_NAME = TEST_DB;
    app = await createApplication();
    await app.listen(0);
    const address = app.getHttpServer().address();
    baseUrl = `http://127.0.0.1:${address.port}`;

    const config = app.get(ConfigService);
    jwtSecret = config.get<string>('jwt.secret')!;

    const creds = {
      host: config.get<string>('database.host'),
      port: config.get<number>('database.port'),
      user: config.get<string>('database.user'),
      password: config.get<string>('database.password'),
      database: TEST_DB,
    };
    db = new Client(creds);
    await db.connect();
  });

  afterAll(async () => {
    await db.end();
    await app.close();
  });

  describe('Sealed credentials (RSA-OAEP + AES-GCM transport)', () => {
    let publicKeyPem: string;

    beforeAll(async () => {
      const res = await start(baseUrl, '/auth/public-key', { method: 'GET' });
      expect(res.status).toBe(200);
      expect(res.body.key).toContain('BEGIN PUBLIC KEY');
      publicKeyPem = res.body.key;
    });

    it('accepts a sealed register payload exactly like the plaintext one', async () => {
      const username = randomUsername('sealed_reg');
      const password = 'capstone-sealed-reg-2026';
      const res = await post(baseUrl, '/auth/register', sealedBody(publicKeyPem, {
        firstName: 'Sealed', lastName: 'Reg', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      }));

      expect(res.status).toBe(201);
      expect(res.body.user.username).toBe(username);
      expect(res.body.user.accountId).toEqual(expect.any(Number));
    });

    it('accepts a sealed login and rejects a replayed payload with VAL-422', async () => {
      const username = randomUsername('sealed_login');
      const password = 'capstone-sealed-login-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'Sealed', lastName: 'Login', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      });

      const payload = sealedBody(publicKeyPem, { identifier: username, password });
      const res = await post(baseUrl, '/auth/login', payload);
      expect(res.status).toBe(200);
      expect(res.body.accessToken).toBeTruthy();
      expect(res.body.user.accountId).toEqual(expect.any(Number));

      // Replaying the identical ciphertext is refused: same nonce, no new token.
      const replay = await post(baseUrl, '/auth/login', payload);
      expect(replay.status).toBe(422);
      expect(replay.body.errorCode).toBe('VAL-422');
    });

    it('rejects tampered or malformed sealed requests with VAL-422', async () => {
      const res = await post(baseUrl, '/auth/login', { request: 'garbage.not.sealed' });
      expect(res.status).toBe(422);
      expect(res.body.errorCode).toBe('VAL-422');

      const payload = sealedBody(publicKeyPem, { identifier: 'x', password: 'y' });
      const parts = payload.request.split('.');
      const ct = Buffer.from(parts[2], 'base64');
      ct[0] = ct[0] ^ 0xff;
      const tampered = await post(baseUrl, '/auth/login', {
        request: [parts[0], parts[1], ct.toString('base64')].join('.'),
      });
      expect(tampered.status).toBe(422);
    });

    it('still applies the validation rules to decrypted content', async () => {
      const username = randomUsername('sealed_invalid');
      const res = await post(baseUrl, '/auth/register', sealedBody(publicKeyPem, {
        firstName: 'A', lastName: 'B', username,
        email: 'not-an-email', password: 'tooshort', confirmPassword: 'tooshort',
      }));
      expect(res.status).toBe(422);
      expect(res.body.errorCode).toBe('VAL-422');
      expect(res.body.message).toContain('at least 12');
    });
  });

  describe('Registration', () => {
    it('succeeds with valid data, returns no tokens and no cookie', async () => {
      const username = randomUsername('reg');
      const password = 'capstone-register-2026';
      const res = await post(baseUrl, '/auth/register', {
        firstName: 'Reg',
        lastName: 'One',
        username,
        email: `${username}@example.com`,
        password,
        confirmPassword: password,
      });

      expect(res.status).toBe(201);
      expect(res.body.message).toContain('sign in');
      expect(res.body.user.username).toBe(username);
      expect(res.body.user.accountId).toEqual(expect.any(Number));
      expect(res.body).not.toHaveProperty('accessToken');
      expect(res.body).not.toHaveProperty('refreshToken');
      expect(res.setCookie).toHaveLength(0);
    });

    it('persists the user and their trading account to the database with a bcrypt hash', async () => {
      const username = randomUsername('regpersist');
      const password = 'capstone-persist-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'Persist',
        lastName: 'Two',
        username,
        email: `${username}@example.com`,
        password,
        confirmPassword: password,
      });

      const users = await db.query('SELECT user_id, uuid, password_hash FROM auth.users WHERE username = $1', [username]);
      expect(users.rowCount).toBe(1);
      const user = users.rows[0];
      expect(String(user.password_hash)).not.toBe(password);
      expect(String(user.password_hash)).toMatch(/^\$2/); // bcrypt
      expect(user.uuid).toBeTruthy();

      const accounts = await db.query('SELECT account_id, cash_balance FROM trading.account WHERE user_id = $1', [user.user_id]);
      expect(accounts.rowCount).toBe(1);
      expect(Number(accounts.rows[0].cash_balance)).toBe(100000);
    });

    it('rejects a duplicate username with AUTH-409', async () => {
      const username = randomUsername('regdup');
      const password = 'capstone-dup-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      });
      const res = await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}-other@example.com`, password, confirmPassword: password,
      });
      expect(res.status).toBe(409);
      expect(res.body.errorCode).toBe('AUTH-409');
    });

    it('rejects a duplicate email with AUTH-409', async () => {
      const username = randomUsername('regdupemail');
      const password = 'capstone-dupemail-2026';
      const email = `${username}@example.com`;
      await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email, password, confirmPassword: password,
      });
      const res = await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username: `${username}x`,
        email, password, confirmPassword: password,
      });
      expect(res.status).toBe(409);
      expect(res.body.errorCode).toBe('AUTH-409');
    });

    it('rejects an invalid email with VAL-422', async () => {
      const username = randomUsername('rege');
      const password = 'capstone-invalid-email-2026';
      const res = await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: 'not-an-email', password, confirmPassword: password,
      });
      expect(res.status).toBe(422);
      expect(res.body.errorCode).toBe('VAL-422');
    });

    it('rejects a password shorter than 12 characters with VAL-422', async () => {
      const username = randomUsername('regpw');
      const res = await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`, password: 'tooshort', confirmPassword: 'tooshort',
      });
      expect(res.status).toBe(422);
      expect(res.body.errorCode).toBe('VAL-422');
      expect(res.body.message).toContain('at least 12');
    });

    it('rejects a password confirmation mismatch with VAL-422', async () => {
      const username = randomUsername('regcf');
      const res = await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`,
        password: 'capstone-mismatch-2026', confirmPassword: 'capstone-mismatch-2027',
      });
      expect(res.status).toBe(422);
      expect(res.body.errorCode).toBe('VAL-422');
      expect(res.body.message).toContain('match');
    });
  });

  describe('Login', () => {
    it('issues an access token and sets the refresh_token cookie with the configured attributes', async () => {
      const username = randomUsername('login');
      const password = 'capstone-login-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      });

      const res = await post(baseUrl, '/auth/login', { identifier: username, password });
      expect(res.status).toBe(200);
      expect(res.body.accessToken).toBeTruthy();
      expect(res.body.expiresIn).toBe(900);
      expect(res.body.user.accountId).toEqual(expect.any(Number));
      expect(res.body).not.toHaveProperty('refreshToken');

      // Cookie attributes: HttpOnly, SameSite=Lax, Secure, Path=/ and ~7d TTL.
      const token = cookieValue(res.setCookie);
      expect(token).toBeTruthy();
      const raw = res.setCookie.find((c) => c.startsWith(`refresh_token=${token}`));
      expect(raw).toBeTruthy();
      expect(raw).toContain('HttpOnly');
      expect(raw).toContain('SameSite=Lax');
      expect(raw).toContain('Secure');
      expect(raw).toContain('Path=/');
      expect(raw).toMatch(/Max-Age=(604800|604799|604801)/);
    });

    it('returns the identical AUTH-401 envelope for an unknown user and a wrong password', async () => {
      const username = randomUsername('lognone');
      const password = 'capstone-login-none-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      });

      const unknown = await post(baseUrl, '/auth/login', { identifier: 'nobody-' + username, password });
      const wrong = await post(baseUrl, '/auth/login', { identifier: username, password: password + 'x' });
      expect(unknown.status).toBe(401);
      expect(wrong.status).toBe(401);
      expect(unknown.body).toEqual({ errorCode: 'AUTH-401', message: 'Unauthorised' });
      expect(wrong.body).toEqual(unknown.body);
    });

    it('mints a token carrying exactly the contract claims', async () => {
      const username = randomUsername('claims');
      const password = 'capstone-claims-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      });
      const login = await post(baseUrl, '/auth/login', { identifier: username, password });
      const payload = jwt.verify(login.body.accessToken, jwtSecret, { algorithms: ['HS256'] }) as jwt.JwtPayload;

      expect(Object.keys(payload).sort()).toEqual(['accountId', 'exp', 'iat', 'iss', 'roles', 'sub']);
      expect(payload.iss).toBe('auth-service');
      expect(payload.roles).toEqual(['CUSTOMER']);
      expect(typeof payload.sub).toBe('string');
      expect(Number(payload.exp) - Number(payload.iat)).toBe(900);
      expect(typeof payload.accountId).toBe('number');
    });

    it('stores the refresh token as a SHA-256 hash, never the token', async () => {
      const username = randomUsername('hash');
      const password = 'capstone-hash-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      });
      const login = await post(baseUrl, '/auth/login', { identifier: username, password });
      const token = cookieValue(login.setCookie)!;
      const rows = await db.query('SELECT token_hash FROM auth.refresh_token');
      expect(rows.rows.map((r) => r.token_hash)).toContain(sha256(token));
    });
  });

  describe('Refresh', () => {
    async function loginUser(): Promise<{ username: string; token: string }> {
      const username = randomUsername('rf');
      const password = 'capstone-refresh-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      });
      const login = await post(baseUrl, '/auth/login', { identifier: username, password });
      return { username, token: cookieValue(login.setCookie)! };
    }

    it('renews only the access token — the same refresh token stays valid for its 7-day life', async () => {
      const { username, token: first } = await loginUser();

      const refresh1 = await post(baseUrl, '/auth/refresh', {}, `refresh_token=${first}`);
      expect(refresh1.status).toBe(200);
      expect(refresh1.body.accessToken).toBeTruthy();
      const accessPayload = jwt.verify(refresh1.body.accessToken, jwtSecret, { algorithms: ['HS256'] }) as jwt.JwtPayload;
      expect(Number(accessPayload.exp) - Math.floor(Date.now() / 1000)).toBeCloseTo(900, -1);

      // No new refresh cookie is issued and the DB still holds exactly one
      // (unrevoked) token row — the very row created at login.
      expect(cookieValue(refresh1.setCookie)).toBeNull();
      const user = await db.query('SELECT user_id FROM auth.users WHERE username = $1', [username]);
      const rows1 = await db.query('SELECT token_hash, revoked_on FROM auth.refresh_token WHERE user_id = $1', [user.rows[0].user_id]);
      expect(rows1.rowCount).toBe(1);
      expect(rows1.rows[0].token_hash).toBe(sha256(first));
      expect(rows1.rows[0].revoked_on).toBeNull();

      // The SAME token still works hours later (simulated: still within 7 days).
      const refresh2 = await post(baseUrl, '/auth/refresh', {}, `refresh_token=${first}`);
      expect(refresh2.status).toBe(200);
      const rows2 = await db.query('SELECT COUNT(*)::int AS n FROM auth.refresh_token WHERE user_id = $1', [user.rows[0].user_id]);
      expect(rows2.rows[0].n).toBe(1);
      const login2 = await post(baseUrl, '/auth/login', { identifier: username, password: 'capstone-refresh-2026' });
      expect(login2.status).toBe(200);
      expect(cookieValue(login2.setCookie)).not.toBe(first);
    });

    it('rejects an invalid refresh token with AUTH-401', async () => {
      const res = await post(baseUrl, '/auth/refresh', {}, 'refresh_token=deadbeefdeadbeef');
      expect(res.status).toBe(401);
      expect(res.body.errorCode).toBe('AUTH-401');
    });

    it('rejects an expired refresh token with AUTH-401', async () => {
      const { token } = await loginUser();
      await db.query(
        "UPDATE auth.refresh_token SET expires_at = now() - interval '1 hour' WHERE token_hash = $1",
        [sha256(token)],
      );
      const res = await post(baseUrl, '/auth/refresh', {}, `refresh_token=${token}`);
      expect(res.status).toBe(401);
    });

    it('revokes the token and clears the cookie on logout, making it unusable', async () => {
      const { token } = await loginUser();
      const logout = await post(baseUrl, '/auth/logout', {}, `refresh_token=${token}`);
      expect(logout.status).toBe(200);
      const after = await post(baseUrl, '/auth/refresh', {}, `refresh_token=${token}`);
      expect(after.status).toBe(401);
    });
  });

  describe('/auth/me — authenticated profile and account ownership', () => {
    it('returns the authenticated user and their own account, refusing bad tokens', async () => {
      const username = randomUsername('me');
      const password = 'capstone-me-2026';
      await post(baseUrl, '/auth/register', {
        firstName: 'A', lastName: 'B', username,
        email: `${username}@example.com`, password, confirmPassword: password,
      });
      const login = await post(baseUrl, '/auth/login', { identifier: username, password });
      const access = login.body.accessToken;

      const own = await get(baseUrl, '/auth/me', access);
      expect(own.status).toBe(200);
      expect(own.body.username).toBe(username);
      const dbUser = await db.query('SELECT user_id FROM auth.users WHERE username = $1', [username]);
      const dbAccount = await db.query('SELECT account_id FROM trading.account WHERE user_id = $1', [dbUser.rows[0].user_id]);
      expect(own.body.accountId).toBe(Number(dbAccount.rows[0].account_id));

      const noToken = await get(baseUrl, '/auth/me');
      expect(noToken.status).toBe(401);
      const garbage = await get(baseUrl, '/auth/me', 'not-a-jwt');
      expect(garbage.status).toBe(401);
    });

    it('maps each user to a distinct account (user_id -> account, never a shared id)', async () => {
      const mk = async (name: string) => {
        const username = randomUsername(name);
        const password = 'capstone-own-2026';
        await post(baseUrl, '/auth/register', {
          firstName: 'A', lastName: 'B', username,
          email: `${username}@example.com`, password, confirmPassword: password,
        });
        const login = await post(baseUrl, '/auth/login', { identifier: username, password });
        const me = await get(baseUrl, '/auth/me', login.body.accessToken);
        return me.body.accountId;
      };
      const first = await mk('own1');
      const second = await mk('own2');
      expect(first).toEqual(expect.any(Number));
      expect(second).toEqual(expect.any(Number));
      expect(first).not.toBe(second);
    });
  });
});