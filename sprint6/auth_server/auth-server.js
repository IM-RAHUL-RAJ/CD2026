const http = require('http');
const jwt = require('jsonwebtoken');

const ALGORITHM = 'HS256';
const ISSUER = 'auth-service';
// JWT secret is required - no default for security
const JWT_SECRET = process.env.JWT_SECRET;
if (!JWT_SECRET) {
  throw new Error('FATAL: JWT_SECRET environment variable is required');
}
const PORT = Number.parseInt(process.env.PORT || '3000', 10);
const TTL_SECONDS = Number.parseInt(process.env.JWT_TTL_SECONDS || '900', 10);

function getAllowedOrigins() {
  const configuredOrigins = process.env.CORS_ALLOWED_ORIGINS;

  if (!configuredOrigins) {
    // Default CORS origins if not configured
    return ['http://localhost:5000', 'http://127.0.0.1:5000', 'http://frontend:5000'];
  }

  return configuredOrigins
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean);
}

function getSecret() {
  // JWT_SECRET is already set at module level
  // No fallback to hardcoded value for security
  return JWT_SECRET;
}

function applyCorsHeaders(req, res) {
  const allowedOrigins = getAllowedOrigins();
  const requestOrigin = req.headers.origin;

  if (requestOrigin && allowedOrigins.includes(requestOrigin)) {
    res.setHeader('Access-Control-Allow-Origin', requestOrigin);
    res.setHeader('Vary', 'Origin');
    res.setHeader('Access-Control-Allow-Credentials', 'false');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  }
}

function normalizeUserId(userId) {
  if (userId === undefined || userId === null || userId === '') {
    return 'user-123';
  }

  const value = String(userId).trim();
  if (!value) {
    throw new Error('userId must be a non-empty string');
  }

  return value;
}

function normalizeAccountId(accountId) {
  if (accountId === undefined || accountId === null || accountId === '') {
    return 1001;
  }

  const value = Number(accountId);
  if (!Number.isFinite(value) || !Number.isInteger(value)) {
    throw new Error('accountId must be an integer');
  }

  return value;
}

function normalizeRoles(roles) {
  if (roles === undefined || roles === null || roles === '') {
    return ['CUSTOMER'];
  }

  if (!Array.isArray(roles) || roles.length === 0) {
    throw new Error('roles must be a non-empty array of strings');
  }

  return roles.map((role) => {
    const value = String(role).trim();
    if (!value) {
      throw new Error('roles must be a non-empty array of strings');
    }
    return value;
  });
}

function normalizeTtlSeconds(ttlSeconds) {
  if (ttlSeconds === undefined || ttlSeconds === null || ttlSeconds === '') {
    return DEFAULT_TTL_SECONDS;
  }

  const value = Number(ttlSeconds);
  if (!Number.isFinite(value) || !Number.isInteger(value) || value <= 0) {
    throw new Error('ttlSeconds must be a positive integer');
  }

  return value;
}

function generateToken({ userId, accountId, roles, ttlSeconds }) {
  const normalizedUserId = normalizeUserId(userId);
  const normalizedAccountId = normalizeAccountId(accountId);
  const normalizedRoles = normalizeRoles(roles);
  const normalizedTtlSeconds = normalizeTtlSeconds(ttlSeconds);

  const payload = {
    sub: normalizedUserId,
    accountId: normalizedAccountId,
    roles: normalizedRoles,
  };

  const token = jwt.sign(payload, getSecret(), {
    algorithm: ALGORITHM,
    issuer: ISSUER,
    header: { typ: 'JWT' },
    expiresIn: `${normalizedTtlSeconds}s`,
  });

  const decoded = jwt.decode(token);

  return {
    token,
    claims: decoded,
    tokenType: 'Bearer',
    algorithm: ALGORITHM,
    issuer: ISSUER,
  };
}

function verifyToken(token) {
  const claims = jwt.verify(token, getSecret(), {
    algorithms: [ALGORITHM],
    issuer: ISSUER,
  });

  if (typeof claims.sub !== 'string' || !claims.sub.trim()) {
    throw new Error('Invalid token subject');
  }

  if (typeof claims.accountId !== 'number' || !Number.isFinite(claims.accountId)) {
    throw new Error('Invalid token accountId');
  }

  if (
    !Array.isArray(claims.roles) ||
    claims.roles.length === 0 ||
    claims.roles.some((role) => typeof role !== 'string' || !role.trim())
  ) {
    throw new Error('Invalid token roles');
  }

  return claims;
}

function sendJson(res, statusCode, body) {
  res.statusCode = statusCode;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store');
  res.end(JSON.stringify(body, null, 2));
}

function readRequestBody(req) {
  return new Promise((resolve, reject) => {
    let data = '';
    req.on('data', (chunk) => {
      data += chunk;
      if (data.length > 1024 * 1024) {
        reject(new Error('Request body too large'));
        req.destroy();
      }
    });
    req.on('end', () => resolve(data));
    req.on('error', reject);
  });
}

function extractBearerToken(req) {
  const header = req.headers.authorization;
  if (!header || !header.startsWith('Bearer ')) {
    throw new Error('Missing Bearer token');
  }

  const token = header.slice(7).trim();
  if (!token) {
    throw new Error('Missing Bearer token');
  }

  return token;
}

async function handleTokenRequest(req, res) {
  try {
    const rawBody = await readRequestBody(req);
    const body = rawBody ? JSON.parse(rawBody) : {};
    const result = generateToken(body);

    sendJson(res, 200, {
      message: 'JWT generated successfully',
      ...result,
    });
  } catch (error) {
    const statusCode = error instanceof SyntaxError ? 400 : 400;
    sendJson(res, statusCode, {
      error: 'Unable to generate token',
      message: error.message,
    });
  }
}

function handleVerificationRequest(req, res) {
  try {
    const token = extractBearerToken(req);
    const claims = verifyToken(token);

    sendJson(res, 200, {
      message: 'JWT is valid',
      authenticated: true,
      principal: {
        userId: claims.sub,
        accountId: claims.accountId,
        roles: claims.roles,
      },
      claims,
    });
  } catch (error) {
    sendJson(res, 401, {
      error: 'Unauthorized',
      message: error.message,
    });
  }
}

function createServer() {
  return http.createServer((req, res) => {
    applyCorsHeaders(req, res);

    if (req.method === 'OPTIONS') {
      res.statusCode = 204;
      res.end();
      return;
    }

    if (req.method === 'GET' && req.url === '/health') {
      sendJson(res, 200, { status: 'ok' });
      return;
    }

    if (req.method === 'POST' && req.url === '/api/auth') {
      void handleTokenRequest(req, res);
      return;
    }

    if (req.method === 'GET' && req.url === '/api/auth/verify') {
      handleVerificationRequest(req, res);
      return;
    }

    if (req.method === 'GET' && req.url === '/api/v1/secure') {
      handleVerificationRequest(req, res);
      return;
    }

    sendJson(res, 404, {
      error: 'Not Found',
      message: 'Use POST /api/auth or GET /api/auth/verify',
    });
  });
}

if (require.main === module) {
  try {
    const server = createServer();
    server.listen(DEFAULT_PORT, () => {
      console.log(`JWT auth server listening on http://localhost:${DEFAULT_PORT}`);
      console.log('POST /api/auth to create a token');
      console.log('GET /api/auth/verify with Authorization: Bearer <token> to validate it');
    });
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}

module.exports = {
  createServer,
  generateToken,
  verifyToken,
};



