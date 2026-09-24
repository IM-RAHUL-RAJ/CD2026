export const configuration = () => ({
  port: parseInt(process.env.PORT || '3000', 10),
  database: {
    host: process.env.DB_HOST || 'localhost',
    port: parseInt(process.env.DB_PORT || '5432', 10),
    user: process.env.DB_USER || 'postgres',
    password: process.env.DB_PASSWORD || 'postgres',
    name: process.env.DB_NAME || 'trading_system_db',
  },
  jwt: {
    secret: process.env.JWT_SECRET || 'akatsuki_akatsuki_akatsuki_akatsuki_akatsuki',
    issuer: process.env.JWT_ISSUER || 'auth-service',
    ttlSeconds: parseInt(process.env.JWT_TTL_SECONDS || '900', 10),
  },
  refresh: {
    ttlSeconds: parseInt(process.env.REFRESH_TTL_SECONDS || '604800', 10),
  },
  throttle: {
    maxAttempts: parseInt(process.env.THROTTLE_MAX_ATTEMPTS || '5', 10),
    windowMs: parseInt(process.env.THROTTLE_WINDOW_MS || '900000', 10),
  },
});