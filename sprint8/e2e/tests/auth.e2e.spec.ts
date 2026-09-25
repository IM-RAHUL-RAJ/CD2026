import { expect, test, type Page, type APIRequestContext } from '@playwright/test';
import { readFileSync } from 'fs';
import { join } from 'path';
import { connect as tcpConnect, type Socket } from 'node:net';
import { Client } from 'pg';

const FRONT = 'http://localhost:5000';
const AUTH = 'http://localhost:3000';
const BACKEND = 'http://localhost:8085';
const KAFKA = { host: '10.8.71.240', port: 9092 };

function loadEnv(): Record<string, string> {
  const values: Record<string, string> = {};
  try {
    const content = readFileSync(join(__dirname, '../../sprint8-auth-service/.env'), 'utf8');
    for (const line of content.split(/\r?\n/)) {
      const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
      if (match) values[match[1]] = match[2];
    }
  } catch {
    // rely on process.env
  }
  return values;
}

const env = loadEnv();
const DB = {
  host: process.env.DB_HOST || env.DB_HOST || 'localhost',
  port: Number(process.env.DB_PORT || env.DB_PORT || '5432'),
  user: process.env.DB_USER || env.DB_USER || 'postgres',
  password: process.env.DB_PASSWORD || env.DB_PASSWORD || 'postgres',
  database: process.env.DB_NAME || env.DB_NAME || 'trading_system_db',
};

let db: Client;

function unique(prefix: string): string {
  return `${prefix}${Date.now().toString(36)}${Math.floor(Math.random() * 1e6).toString(36)}`;
}

function makeCreds() {
  const username = unique('e2e');
  const password = 'capstone-e2e-password-check-2026';
  const email = `${username}@example.com`;
  return { username, password, email };
}

async function registerViaApi(request: APIRequestContext, firstName: string, lastName: string, creds: ReturnType<typeof makeCreds>) {
  const res = await request.post(`${AUTH}/auth/register`, {
    data: {
      firstName,
      lastName,
      username: creds.username,
      email: creds.email,
      password: creds.password,
      confirmPassword: creds.password,
    },
  });
  expect(res.status()).toBe(201);
  return creds;
}

async function fillRegisterForm(page: Page, creds: ReturnType<typeof makeCreds>) {
  await page.fill('#firstName', 'E2E');
  await page.fill('#lastName', 'User');
  await page.fill('#username', creds.username);
  await page.fill('#email', creds.email);
  await page.fill('#password', creds.password);
  await page.fill('#confirmPassword', creds.password);
}

async function loginViaUi(page: Page, identifier: string, password: string) {
  await page.goto('/login');
  await page.fill('#username', identifier);
  await page.fill('#password', password);
  await page.click('.btn[type="submit"]');
  await page.waitForURL((url) => url.pathname === '/dashboard');
}

function kafkaReachable(timeoutMs = 4000): Promise<boolean> {
  return new Promise((resolve) => {
    const socket: Socket = tcpConnect({ host: KAFKA.host, port: KAFKA.port });
    const done = (ok: boolean) => {
      socket.destroy();
      resolve(ok);
    };
    socket.setTimeout(timeoutMs);
    socket.once('connect', () => done(true));
    socket.once('error', () => done(false));
    socket.once('timeout', () => done(false));
  });
}

test.beforeAll(async () => {
  db = new Client(DB);
  await db.connect();
});

test.afterAll(async () => {
  await db.end();
});

test.describe('Registration page (public)', () => {
  test('serves the registration page at / and /register without requiring auth', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByRole('heading', { name: 'Create account' })).toBeVisible();
    await expect(page.locator('#registerForm')).toBeVisible();

    await page.goto('/register');
    await expect(page.locator('#registerForm')).toBeVisible();
  });

  test('marks the mandatory fields and leaves middle name optional', async ({ page }) => {
    await page.goto('/register');
    for (const field of ['firstName', 'lastName', 'username', 'email', 'password', 'confirmPassword']) {
      await expect(page.locator(`label[for="${field}"] .required`)).toHaveCount(1);
      await expect(page.locator(`#${field}`)).toHaveAttribute('required', '');
    }
    await expect(page.locator('label[for="middleName"] .required')).toHaveCount(0);
    await expect(page.locator('#middleName')).not.toHaveAttribute('required', '');
    await expect(page.locator('.hint')).toContainText('required');
  });

  test('updates the live password checklist at the 12-character boundary', async ({ page }) => {
    await page.goto('/register');
    const indicator = page.locator('#reqMin');

    await page.fill('#password', 'abc');
    await expect(indicator).toContainText('✗ Minimum 12 characters');
    await expect(indicator).not.toHaveClass(/ok/);

    await page.fill('#password', 'abcdefghijkl'); // 12 chars
    await expect(indicator).toContainText('✓ Minimum 12 characters');
    await expect(indicator).toHaveClass(/ok/);
  });

  test('the browser blocks a short password natively (no submission, no API call)', async ({ page }) => {
    const requests: string[] = [];
    page.on('request', (r) => requests.push(r.url()));

    const creds = makeCreds();
    await page.goto('/register');
    await fillRegisterForm(page, creds);
    await page.fill('#password', 'tooshort'); // 8 chars (minlength=12)
    await page.fill('#confirmPassword', 'tooshort');
    await page.click('.btn[type="submit"]');

    // The password field has minlength=12, so the browser blocks submit natively.
    expect(await page.locator('#password').evaluate((el: HTMLInputElement) => el.validity.tooShort)).toBe(true);
    expect(page.url()).toBe(`${FRONT}/register`);
    expect(requests.filter((u) => u.includes('/auth/register'))).toHaveLength(0);
  });

  test('rejects a password confirmation mismatch client-side and stays put', async ({ page }) => {
    const requests: string[] = [];
    page.on('request', (r) => requests.push(r.url()));

    const creds = makeCreds();
    await page.goto('/register');
    await fillRegisterForm(page, creds);
    await page.fill('#confirmPassword', creds.password + 'X');
    await page.click('.btn[type="submit"]');

    await expect(page.locator('#message')).toContainText('Passwords do not match');
    expect(page.url()).toBe(`${FRONT}/register`);
    expect(requests.filter((u) => u.includes('/auth/register'))).toHaveLength(0);
  });
});

test.describe('Registration -> sign in flow', () => {
  test('registers, lands on exactly /login with no query string, issues no tokens, and persists user + account', async ({ page, context }) => {
    const creds = makeCreds();
    await page.goto('/register');
    await fillRegisterForm(page, creds);
    await page.click('.btn[type="submit"]');

    await page.waitForURL((url) => url.origin === FRONT && url.pathname === '/login' && url.search === '');
    expect(page.url()).toBe(`${FRONT}/login`);

    // No tokens in storage, no refresh cookie anywhere.
    expect(page.evaluate(() => Object.keys(localStorage))).resolves.toEqual([]);
    const cookies = await context.cookies();
    expect(cookies.filter((c) => c.name === 'refresh_token')).toHaveLength(0);

    // DB: user exists with a bcrypt hash + the trading account exists.
    const userRes = await db.query('SELECT user_id, password_hash FROM auth.users WHERE username = $1', [creds.username]);
    expect(userRes.rowCount).toBe(1);
    const { user_id, password_hash } = userRes.rows[0];
    expect(password_hash).toMatch(/^\$2/);
    const accountRes = await db.query('SELECT account_id, cash_balance FROM trading.account WHERE user_id = $1', [user_id]);
    expect(accountRes.rowCount).toBe(1);
    expect(Number(accountRes.rows[0].cash_balance)).toBe(100000);
    const refreshRes = await db.query('SELECT 1 FROM auth.refresh_token WHERE user_id = $1', [user_id]);
    expect(refreshRes.rowCount).toBe(0);
  });

  test('surfaces a duplicate-username error on the register page without redirecting', async ({ page }) => {
    const creds = makeCreds();
    await registerViaApi(page.request, 'E2E', 'User', creds);

    await page.goto('/register');
    await fillRegisterForm(page, creds);
    await page.click('.btn[type="submit"]');

    await expect(page.locator('#message')).toContainText('Registration failed');
    expect(page.url()).toBe(`${FRONT}/register`);
  });
});

test.describe('Credential transport (encrypted)', () => {
  test('registration and login send a sealed `request` blob, never the plaintext password', async ({ page }) => {
    const creds = makeCreds();
    const credentialsBodies: string[] = [];
    page.on('request', (r) => {
      if (r.url().includes('/auth/register') || r.url().includes('/auth/login')) {
        credentialsBodies.push(r.postData() || '');
      }
    });

    await page.goto('/register');
    await fillRegisterForm(page, creds);
    await page.click('.btn[type="submit"]');
    await page.waitForURL((url) => url.pathname === '/login');

    await page.fill('#username', creds.username);
    await page.fill('#password', creds.password);
    await page.click('.btn[type="submit"]');
    await page.waitForURL((url) => url.pathname === '/dashboard');

    expect(credentialsBodies).toHaveLength(2);
    for (const body of credentialsBodies) {
      // Sealed form: { "request": "<base64>.<base64>.<base64>" } — no readable fields.
      expect(body).toContain('"request"');
      expect(body).not.toContain(creds.password);
      for (const key of ['"password"', '"identifier"', '"firstName"', '"username"', '"email"']) {
        expect(body).not.toContain(key);
      }
      const match = /"request"\s*:\s*"([A-Za-z0-9+/=]+\.){2}[A-Za-z0-9+/=]+"/.exec(body);
      expect(match).toBeTruthy();
    }
  });
});

test.describe('Auth guard on protected pages', () => {
  test('redirects anonymous users away from protected pages to /login', async ({ page }) => {
    await page.goto('/dashboard');
    await page.waitForURL((url) => url.pathname === '/login');
    expect(page.url()).toBe(`${FRONT}/login`);
  });

  test('leaves /analytics public — it is not part of the session', async ({ page }) => {
    await page.goto('/analytics');
    await page.waitForURL((url) => url.pathname === '/analytics');
    // No /auth/me guard call happens on the public analytics page.
    const meRequests: string[] = [];
    page.on('request', (r) => {
      if (r.url().includes('/auth/me')) meRequests.push(r.url());
    });
    await page.reload();
    expect(meRequests).toHaveLength(0);
  });

  test('lets an authenticated user keep protected pages, deriving the account from the token', async ({ page, context }) => {
    const creds = makeCreds();
    await registerViaApi(page.request, 'E2E', 'User', creds);
    await loginViaUi(page, creds.username, creds.password);

    // localStorage holds only the JWT — never account ids or refresh tokens.
    const storageKeys = await page.evaluate(() => Object.keys(localStorage).sort());
    expect(storageKeys).toEqual(['jwt']);
    for (const key of ['accountId', 'account_id', 'refreshToken', 'refresh_token']) {
      expect(await page.evaluate((k) => localStorage.getItem(k), key)).toBeNull();
    }

    // The refresh token cookie exists on the auth origin with the right attributes.
    const cookies = await context.cookies(AUTH);
    const refreshCookie = cookies.find((c) => c.name === 'refresh_token');
    expect(refreshCookie).toBeTruthy();
    expect(refreshCookie!.httpOnly).toBe(true);
    expect(refreshCookie!.secure).toBe(true);
    expect(refreshCookie!.sameSite).toBe('Lax');
    expect(refreshCookie!.path).toBe('/');

    // Account comes from /auth/me-derived state and matches the DB row.
    const accountId = await page.evaluate(() => window.getAccountId());
    const dbRes = await db.query(
      `SELECT a.account_id FROM trading.account a
        JOIN auth.users u ON u.user_id = a.user_id
       WHERE u.username = $1`,
      [creds.username],
    );
    expect(accountId).toBe(Number(dbRes.rows[0].account_id));

    // Protected pages stay put for the authenticated user.
    await page.goto('/dashboard');
    await expect(page.locator('#btnProfile')).toBeVisible();
    await page.goto('/analytics');
    await expect(page.url()).toContain('/analytics');
  });
});

test.describe('Refresh-token behaviour in the browser', () => {
  test('does not refresh the access token on ordinary navigation, only on a 401', async ({ page }) => {
    const creds = makeCreds();
    await registerViaApi(page.request, 'E2E', 'User', creds);
    await loginViaUi(page, creds.username, creds.password);

    const refreshRequests: string[] = [];
    const meRequests: string[] = [];
    page.on('request', (r) => {
      if (r.url().includes('/auth/refresh')) refreshRequests.push(r.url());
      if (r.url().includes('/auth/me')) meRequests.push(r.url());
    });

    // Navigate across protected and public pages: the guard checks via
    // /auth/me only on the protected dashboard; /analytics is public (no /auth/me).
    await page.goto('/dashboard');
    await page.goto('/analytics');
    await page.goto('/dashboard');
    expect(refreshRequests).toHaveLength(0);
    expect(meRequests).toHaveLength(2);

    // Simulate an expired/invalid access token: the UI must rotate once via refresh.
    await page.evaluate(() => localStorage.setItem('jwt', 'invalid.broken.token'));
    const oldJwt = 'invalid.broken.token';
    await page.click('#btnProfile');
    await expect(page.locator('#content')).toContainText('Account Profile');

    expect(refreshRequests).toHaveLength(1);
    const newJwt = await page.evaluate(() => localStorage.getItem('jwt'));
    expect(newJwt).not.toBe(oldJwt);
    expect(newJwt!.length).toBeGreaterThan(20);
  });

  test('redirects to /login when the refresh token is invalid/revoked and the access token is unusable', async ({ page }) => {
    const creds = makeCreds();
    await registerViaApi(page.request, 'E2E', 'User', creds);
    await loginViaUi(page, creds.username, creds.password);

    // Revoke the refresh token server-side (as logout does).
    const logout = await page.request.post(`${AUTH}/auth/logout`);
    expect(logout.status()).toBe(200);

    // Corrupt the access token so /auth/me 401s and forces a refresh attempt.
    await page.evaluate(() => localStorage.setItem('jwt', 'invalid.broken.token'));
    await page.goto('/dashboard');

    await page.waitForURL((url) => url.pathname === '/login');
    expect(page.url()).toBe(`${FRONT}/login`);
    expect(await page.evaluate(() => localStorage.getItem('jwt'))).toBeNull();
  });

  test('logout clears the session locally and server-side', async ({ page, context }) => {
    const creds = makeCreds();
    await registerViaApi(page.request, 'E2E', 'User', creds);
    await loginViaUi(page, creds.username, creds.password);

    const refreshBefore = (await context.cookies(AUTH)).filter((c) => c.name === 'refresh_token');
    expect(refreshBefore).toHaveLength(1);

    await page.click('button:has-text("Logout")');
    await page.waitForURL((url) => url.pathname === '/login');

    expect(await page.evaluate(() => localStorage.getItem('jwt'))).toBeNull();
    const refreshAfter = (await context.cookies(AUTH)).filter((c) => c.name === 'refresh_token');
    expect(refreshAfter).toHaveLength(0);

    // The presented refresh token must now be revoked server-side.
    const dbRows = await db.query(
      `SELECT COUNT(*)::int AS n FROM auth.refresh_token rt
        JOIN auth.users u ON u.user_id = rt.user_id
       WHERE u.username = $1 AND rt.revoked_on IS NULL`,
      [creds.username],
    );
    expect(dbRows.rows[0].n).toBe(0);
  });
});

test.describe('Account isolation', () => {
  test('orders are confined to the caller\'s own account; another account is refused with 403', async ({ page }) => {
    const a = await registerViaApi(page.request, 'A', 'User', makeCreds());
    const b = await registerViaApi(page.request, 'B', 'User', makeCreds());

    const bDb = await db.query(
      'SELECT a.account_id FROM trading.account a JOIN auth.users u ON u.user_id = a.user_id WHERE u.username = $1',
      [b.username],
    );
    const bAccountId = Number(bDb.rows[0].account_id);

    await loginViaUi(page, a.username, a.password);
    const aAccountId = await page.evaluate(() => window.getAccountId());

    const orderBody = {
      accountId: aAccountId,
      symbol: 'AAPL',
      quantity: 1,
      price: 0,
      side: 'BUY',
      orderType: 'MARKET',
      idempotencyKey: unique('ord'),
    };
    const aToken = await page.evaluate(() => localStorage.getItem('jwt'));
    const orderOptions = {
      headers: { Authorization: `Bearer ${aToken}` },
      data: orderBody,
      timeout: 15000,
    };

    // Controlling another user's account with the caller's token is refused
    // before any Kafka/order work happens.
    const foreign = await page.request.post(`${BACKEND}/api/v1/orders`, {
      ...orderOptions,
      data: { ...orderBody, accountId: bAccountId, idempotencyKey: unique('ord') },
    });
    expect(foreign.status()).toBe(403);
    const foreignBody = (await foreign.json()) as { errorCode?: string };
    expect(foreignBody.errorCode).toBe('ACC-403');

    // Own account: accepted. Gated on Kafka being reachable (the order placement
    // publishes to the Kafka bus; skip gracefully if the broker is unavailable).
    if (await kafkaReachable()) {
      const own = await page.request.post(`${BACKEND}/api/v1/orders`, orderOptions);
      expect(own.status()).toBe(200);
    } else {
      console.log('Kafka broker unreachable — skipping own-account order assertion');
    }
  });
});