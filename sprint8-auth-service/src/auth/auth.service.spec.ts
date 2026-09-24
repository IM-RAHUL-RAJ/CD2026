import { HttpException } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as bcrypt from 'bcryptjs';
import { DbService } from '../db/db.service';
import { TokensService } from '../tokens/tokens.service';
import { AuthService } from './auth.service';
import { LoginThrottleService } from './login-throttle.service';
import { RegisterDto } from './dto/register.dto';
import { LoginDto } from './dto/login.dto';

describe('AuthService', () => {
  const secret = 'test-secret-that-is-at-least-32-bytes-long-abc';
  let db: {
    query: jest.Mock;
    getClient: jest.Mock;
  };
  let service: AuthService;
  let tokens: TokensService;

  function buildConfig(values: Record<string, unknown> = {}) {
    const defaults: Record<string, unknown> = {
      'database.host': 'localhost',
      'database.port': 5432,
      'database.user': 'postgres',
      'database.password': 'x',
      'database.name': 'trading_system_db',
      'jwt.secret': secret,
      'jwt.issuer': 'auth-service',
      'jwt.ttlSeconds': 900,
      'refresh.ttlSeconds': 604800,
      'throttle.maxAttempts': 5,
      'throttle.windowMs': 900000,
    };
    return {
      get: (key: string) => ({ ...defaults, ...values })[key],
    } as unknown as ConfigService;
  }

  beforeEach(() => {
    db = {
      query: jest.fn(),
      getClient: jest.fn(),
    };
    tokens = new TokensService(buildConfig());
    const throttle = new LoginThrottleService(buildConfig());
    service = new AuthService(
      db as unknown as DbService,
      buildConfig(),
      tokens,
      throttle,
    );
  });

  function sampleRegister(overrides: Partial<RegisterDto> = {}): RegisterDto {
    return {
      firstName: 'Priya',
      middleName: undefined,
      lastName: 'Menon',
      username: 'priya.menon',
      email: 'priya.menon@example.com',
      password: 'Capstone@2026',
      confirmPassword: 'Capstone@2026',
      ...overrides,
    } as RegisterDto;
  }

  describe('register', () => {
    it('rejects a mismatched confirm password with VAL-422', async () => {
      const dto = sampleRegister({ confirmPassword: 'Different@11' });
      await expect(service.register(dto)).rejects.toMatchObject({
        status: 422,
        response: { errorCode: 'VAL-422' },
      });
    });

    it('rejects a duplicate username/email with AUTH-409', async () => {
      db.query.mockResolvedValueOnce({ rows: [{ user_id: 7 }], rowCount: 1 });
      await expect(service.register(sampleRegister())).rejects.toMatchObject({
        status: 409,
        response: { errorCode: 'AUTH-409' },
      });
    });

    it('creates the user and trading account in one transaction but returns NO tokens', async () => {
      const client = {
        query: jest.fn(),
        release: jest.fn(),
      };
      const userRow = {
        user_id: 1,
        uuid: '11111111-1111-1111-1111-111111111111',
        first_name: 'Priya',
        middle_name: null,
        last_name: 'Menon',
        username: 'priya.menon',
        email: 'priya.menon@example.com',
        roles: ['CUSTOMER'],
      };
      client.query.mockImplementation((sql: string) => {
        if (sql.startsWith('SELECT user_id FROM auth.users')) {
          return Promise.resolve({ rows: [], rowCount: 0 });
        }
        if (sql.startsWith('BEGIN')) return Promise.resolve({ rows: [] });
        if (sql.startsWith('INSERT INTO auth.users')) {
          return Promise.resolve({ rows: [userRow] });
        }
        if (sql.startsWith('INSERT INTO trading.account')) {
          return Promise.resolve({ rows: [{ account_id: 42 }] });
        }
        if (sql.startsWith('COMMIT')) return Promise.resolve({ rows: [] });
        if (sql.startsWith('INSERT INTO auth.refresh_token')) {
          return Promise.resolve({ rows: [] });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });
      db.query.mockImplementation(client.query);
      db.getClient.mockResolvedValue(client);

      const result = await service.register(sampleRegister());

      expect(client.query).toHaveBeenCalledWith(
        expect.stringContaining('INSERT INTO auth.users'),
        expect.any(Array),
      );
      expect(client.query).toHaveBeenCalledWith(
        expect.stringContaining('INSERT INTO trading.account'),
        expect.any(Array),
      );
      expect(result.message).toContain('sign in');
      expect(result.user.accountId).toBe(42);
      expect(result.user.roles).toEqual(['CUSTOMER']);
      expect(result).not.toHaveProperty('accessToken');
      expect(result).not.toHaveProperty('refreshToken');
      expect(client.query).not.toHaveBeenCalledWith(
        expect.stringContaining('INSERT INTO auth.refresh_token'),
        expect.any(Array),
      );
    });
  });

  describe('login', () => {
    it('returns identical AUTH-401 for unknown user and wrong password', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      const unknown = await service
        .login({ identifier: 'nobody', password: 'whatever@12' } as LoginDto)
        .then(
          () => null,
          (e) => e as HttpException,
        );
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      const wrongPass = await service
        .login({
          identifier: 'priya.menon',
          password: 'WrongPass@123',
        } as LoginDto)
        .then(
          () => null,
          (e) => e as HttpException,
        );
      expect(unknown?.getStatus()).toBe(401);
      expect(wrongPass?.getStatus()).toBe(401);
      expect(unknown?.getResponse()).toEqual(wrongPass?.getResponse());
    });

    it('issues tokens on valid credentials', async () => {
      const hash = await bcrypt.hash('Capstone@2026', 4);
      const userRow = {
        user_id: 1,
        uuid: '11111111-1111-1111-1111-111111111111',
        first_name: 'Priya',
        middle_name: null,
        last_name: 'Menon',
        username: 'priya.menon',
        email: 'priya.menon@example.com',
        password_hash: hash,
        roles: ['CUSTOMER'],
      };
      db.query.mockImplementation((sql: string) => {
        if (sql.includes('FROM auth.users')) {
          return Promise.resolve({ rows: [userRow] });
        }
        if (sql.includes('FROM trading.account')) {
          return Promise.resolve({ rows: [{ account_id: 42 }] });
        }
        if (sql.startsWith('INSERT INTO auth.refresh_token')) {
          return Promise.resolve({ rows: [] });
        }
        return Promise.resolve({ rows: [], rowCount: 0 });
      });

      const result = await service.login({
        identifier: 'priya.menon',
        password: 'Capstone@2026',
      } as LoginDto);

      expect(result.accessToken).toBeTruthy();
      expect(result.refreshToken).toBeTruthy();
      expect(result.user.accountId).toBe(42);
    });
  });

  describe('refresh', () => {
    it('rejects an unknown refresh token with AUTH-401', async () => {
      db.query.mockResolvedValue({ rows: [], rowCount: 0 });
      await expect(
        service.refresh({ refreshToken: 'not-a-token' }),
      ).rejects.toMatchObject({ status: 401, response: { errorCode: 'AUTH-401' } });
    });
  });
});