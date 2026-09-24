import { HttpException, Injectable, Logger } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import * as bcrypt from 'bcryptjs';
import { randomBytes } from 'crypto';
import { DbService } from '../db/db.service';
import {
  invalidInput,
  unauthorized,
} from '../common/error.envelope.filter';
import { TokensService } from '../tokens/tokens.service';
import { LoginThrottleService } from './login-throttle.service';
import { LoginDto } from './dto/login.dto';
import { RefreshGrantDto } from './dto/refresh-grant.dto';
import { RegisterDto } from './dto/register.dto';

const BCRYPT_ROUNDS = 12;
const DEFAULT_ACCOUNT_BALANCE = '100000.00';

interface UserRow {
  user_id: number;
  uuid: string;
  first_name: string | null;
  middle_name: string | null;
  last_name: string | null;
  username: string;
  email: string;
  password_hash: string;
  roles: string[];
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  user: {
    userId: number;
    uuid: string;
    firstName: string | null;
    middleName: string | null;
    lastName: string | null;
    username: string;
    email: string;
    roles: string[];
    accountId: number | null;
  };
}

export interface RegistrationResult {
  message: string;
  user: AuthResponse['user'];
}

@Injectable()
export class AuthService {
  private readonly logger = new Logger(AuthService.name);
  private readonly refreshTtlSeconds: number;

  constructor(
    private readonly db: DbService,
    private readonly config: ConfigService,
    private readonly tokens: TokensService,
    private readonly throttle: LoginThrottleService,
  ) {
    this.refreshTtlSeconds = this.config.get<number>('refresh.ttlSeconds')!;
  }

  async register(dto: RegisterDto): Promise<RegistrationResult> {
    if (dto.password !== dto.confirmPassword) {
      throw invalidInput('Passwords do not match');
    }

    const existing = await this.db.query(
      'SELECT user_id FROM auth.users WHERE username = $1 OR email = $2',
      [dto.username, dto.email],
    );
    if (existing.rowCount && existing.rowCount > 0) {
      throw new HttpException(
        { errorCode: 'AUTH-409', message: 'Registration failed' },
        409,
      );
    }

    const passwordHash = await bcrypt.hash(dto.password, BCRYPT_ROUNDS);

    const client = await this.db.getClient();
    try {
      await client.query('BEGIN');
      const userRes = await client.query(
        `INSERT INTO auth.users
           (uuid, first_name, middle_name, last_name, username, email, password_hash, roles)
         VALUES (gen_random_uuid(), $1, $2, $3, $4, $5, $6, ARRAY['CUSTOMER'])
         RETURNING user_id, uuid, first_name, middle_name, last_name, username, email, roles`,
        [
          dto.firstName,
          dto.middleName ?? null,
          dto.lastName,
          dto.username,
          dto.email,
          passwordHash,
        ],
      );
      const user = userRes.rows[0] as UserRow;

      const accountRes = await client.query(
        `INSERT INTO trading.account (user_id, currency, cash_balance, status, version)
         VALUES ($1, 'USD', $2, 'ACTIVE', 1)
         RETURNING account_id`,
        [user.user_id, DEFAULT_ACCOUNT_BALANCE],
      );
      const accountId = accountRes.rows[0].account_id as number;
      await client.query('COMMIT');
      this.logger.log(`Registered user id=${user.user_id} with accountId=${accountId}`);

      // Registration never issues tokens: the client must sign in afterwards.
      return {
        message: 'Registration successful. Please sign in to continue.',
        user: this.toPublicUser(user, accountId),
      };
    } catch (error) {
      await client.query('ROLLBACK');
      if (this.isUniqueViolation(error)) {
        throw new HttpException(
          { errorCode: 'AUTH-409', message: 'Registration failed' },
          409,
        );
      }
      throw error;
    } finally {
      client.release();
    }
  }

  async login(dto: LoginDto): Promise<AuthResponse> {
    const identifier = dto.identifier.trim();
    this.throttle.check(identifier);

    const result = await this.db.query(
      `SELECT user_id, uuid, first_name, middle_name, last_name, username, email, password_hash, roles
         FROM auth.users
        WHERE username = $1 OR email = $1`,
      [identifier],
    );
    const user = result.rows[0] as UserRow | undefined;

    const passwordOk = user ? await bcrypt.compare(dto.password, user.password_hash) : false;
    // Uniform timing: always wait the same amount before responding.
    await this.throttle.delay();

    if (!user || !passwordOk) {
      this.throttle.recordFailure(identifier);
      this.logger.warn(`Login failed for identifier="${identifier}"`);
      throw unauthorized();
    }

    this.throttle.reset(identifier);

    let accountId: number | null = null;
    const accountRes = await this.db.query(
      'SELECT account_id FROM trading.account WHERE user_id = $1',
      [user.user_id],
    );
    if (accountRes.rows.length > 0) {
      accountId = accountRes.rows[0].account_id as number;
    }

    return this.issueTokens(user, accountId);
  }

  async refresh(dto: RefreshGrantDto): Promise<AuthResponse> {
    if (!dto.refreshToken) {
      throw unauthorized();
    }
    const tokenHash = this.tokens.hashRefreshToken(dto.refreshToken);

    const result = await this.db.query(
      `SELECT token_hash, family_id, revoked_on, user_id, expires_at
         FROM auth.refresh_token
        WHERE token_hash = $1`,
      [tokenHash],
    );

    if (result.rows.length === 0) {
      throw unauthorized();
    }
    const row = result.rows[0] as {
      token_hash: string;
      family_id: string;
      revoked_on: Date | null;
      user_id: number;
      expires_at: Date;
    };

    // Replay of an already rotated (revoked) token revokes the whole family.
    if (row.revoked_on) {
      this.logger.warn(`Replayed refresh token detected; revoking family=${row.family_id}`);
      await this.db.query(
        'UPDATE auth.refresh_token SET revoked_on = now() WHERE family_id = $1',
        [row.family_id],
      );
      throw unauthorized();
    }

    if (new Date(row.expires_at).getTime() < Date.now()) {
      await this.db.query(
        'UPDATE auth.refresh_token SET revoked_on = now() WHERE token_hash = $1',
        [tokenHash],
      );
      throw unauthorized();
    }

    const userRes = await this.db.query(
      `SELECT user_id, uuid, first_name, middle_name, last_name, username, email, password_hash, roles
         FROM auth.users
        WHERE user_id = $1`,
      [row.user_id],
    );
    if (userRes.rows.length === 0) {
      throw unauthorized();
    }
    const user = userRes.rows[0] as UserRow;
    let accountId: number | null = null;
    const accountRes = await this.db.query(
      'SELECT account_id FROM trading.account WHERE user_id = $1',
      [user.user_id],
    );
    if (accountRes.rows.length > 0) {
      accountId = accountRes.rows[0].account_id as number;
    }

    // Rotate: revoke the presented token and issue a new pair in the same family.
    await this.db.query(
      'UPDATE auth.refresh_token SET revoked_on = now() WHERE token_hash = $1',
      [tokenHash],
    );
    this.logger.log(`Rotated refresh token for user id=${row.user_id} family=${row.family_id}`);
    return this.issueTokens(user, accountId, row.family_id);
  }

  async logout(refreshToken: string): Promise<void> {
    const tokenHash = this.tokens.hashRefreshToken(refreshToken);
    const result = await this.db.query(
      'UPDATE auth.refresh_token SET revoked_on = now() WHERE token_hash = $1 AND revoked_on IS NULL',
      [tokenHash],
    );
    if ((result.rowCount ?? 0) > 0) {
      this.logger.log('Logged out: refresh token revoked');
    }
  }

  async me(uuid: string): Promise<AuthResponse['user']> {
    const userRes = await this.db.query(
      `SELECT user_id, uuid, first_name, middle_name, last_name, username, email, password_hash, roles
         FROM auth.users
        WHERE uuid = $1`,
      [uuid],
    );
    if (userRes.rows.length === 0) {
      throw unauthorized();
    }
    const user = userRes.rows[0] as UserRow;
    let accountId: number | null = null;
    const accountRes = await this.db.query(
      'SELECT account_id FROM trading.account WHERE user_id = $1',
      [user.user_id],
    );
    if (accountRes.rows.length > 0) {
      accountId = accountRes.rows[0].account_id as number;
    }
    return this.toPublicUser(user, accountId);
  }

  private async issueTokens(
    user: UserRow,
    accountId: number | null,
    familyId?: string,
  ): Promise<AuthResponse> {
    const { accessToken, expiresIn } = this.tokens.signAccessToken({
      uuid: user.uuid,
      accountId,
      roles: user.roles,
    });

    const refreshToken = this.tokens.generateRefreshToken();
    const refreshTokenHash = this.tokens.hashRefreshToken(refreshToken);
    const family = familyId ?? this.randomFamilyId();

    await this.db.query(
      `INSERT INTO auth.refresh_token (user_id, token_hash, family_id, expires_at, revoked_on)
       VALUES ($1, $2, $3, now() + ($4 || ' seconds')::interval, NULL)`,
      [user.user_id, refreshTokenHash, family, this.refreshTtlSeconds],
    );

    return {
      accessToken,
      refreshToken,
      expiresIn,
      user: this.toPublicUser(user, accountId),
    };
  }

  private toPublicUser(user: UserRow, accountId: number | null): AuthResponse['user'] {
    return {
      userId: user.user_id,
      uuid: user.uuid,
      firstName: user.first_name,
      middleName: user.middle_name,
      lastName: user.last_name,
      username: user.username,
      email: user.email,
      roles: user.roles,
      accountId,
    };
  }

  private randomFamilyId(): string {
    return randomBytes(16).toString('hex');
  }

  private isUniqueViolation(error: unknown): boolean {
    return (
      typeof error === 'object' &&
      error !== null &&
      (error as { code?: string }).code === '23505'
    );
  }
}