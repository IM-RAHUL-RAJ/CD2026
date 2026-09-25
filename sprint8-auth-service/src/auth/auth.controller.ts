import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Post,
  Req,
  Res,
  UseGuards,
} from '@nestjs/common';
import { ConfigService } from '@nestjs/config';
import { ApiBearerAuth, ApiBody, ApiOperation, ApiTags } from '@nestjs/swagger';
import { Request, Response } from 'express';
import { AuthService, AuthResponse, RegistrationResult } from './auth.service';
import { JwtAuthGuard, AuthenticatedRequest } from './auth.guard';
import { LoginDto } from './dto/login.dto';
import { RefreshGrantDto } from './dto/refresh-grant.dto';
import { RegisterDto } from './dto/register.dto';
import { LoginCryptoService } from '../crypto/login-crypto.service';
import { unauthorized } from '../common/error.envelope.filter';
import {
  REFRESH_COOKIE_NAME,
  clearRefreshCookie,
  setRefreshCookie,
  RefreshCookieConfig,
} from '../common/cookies';

export type AuthResponseBody = Omit<AuthResponse, 'refreshToken'>;

@ApiTags('auth')
@Controller('auth')
export class AuthController {
  private readonly cookieConfig: RefreshCookieConfig;
  private readonly refreshTtlSeconds: number;

  constructor(
    private readonly authService: AuthService,
    private readonly loginCrypto: LoginCryptoService,
    private readonly config: ConfigService,
  ) {
    this.cookieConfig = this.config.get<RefreshCookieConfig>('cookie')!;
    this.refreshTtlSeconds = this.config.get<number>('refresh.ttlSeconds')!;
  }

  @Get('public-key')
  @ApiOperation({ summary: 'Return the RSA public key used to seal login/register credentials' })
  async publicKey(): Promise<{ key: string }> {
    return { key: this.loginCrypto.getPublicKeyPem() };
  }

  @Post('register')
  @HttpCode(HttpStatus.CREATED)
  @ApiBody({ type: RegisterDto })
  @ApiOperation({ summary: 'Register a new customer and open a trading account' })
  async register(
    @Body() dto: unknown,
  ): Promise<RegistrationResult> {
    // Registration is account creation only — no tokens are issued and no
    // refresh cookie is set. The client must call /auth/login afterwards.
    return this.authService.register(dto as RegisterDto);
  }

  @Post('login')
  @HttpCode(HttpStatus.OK)
  @ApiBody({ type: LoginDto })
  @ApiOperation({ summary: 'Log in with username/email and password' })
  async login(
    @Body() dto: unknown,
    @Res({ passthrough: true }) res: Response,
  ): Promise<AuthResponseBody> {
    const result = await this.authService.login(dto as LoginDto);
    setRefreshCookie(res, result.refreshToken, this.cookieConfig, this.refreshTtlSeconds);
    return this.stripRefreshToken(result);
  }

  @Post('refresh')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Rotate a refresh token and issue a new access token' })
  async refresh(
    @Req() req: Request,
    @Body() dto: RefreshGrantDto,
    @Res({ passthrough: true }) res: Response,
  ): Promise<AuthResponseBody> {
    const token = this.extractRefreshToken(req, dto);
    if (!token) {
      throw unauthorized();
    }
    const result = await this.authService.refresh({ refreshToken: token });
    // Non-rotating refresh: the same refresh token keeps its 7-day lifetime, so
    // the browser cookie is left untouched (only the access token is renewed).
    if (result.refreshToken !== token) {
      setRefreshCookie(res, result.refreshToken, this.cookieConfig, this.refreshTtlSeconds);
    }
    return this.stripRefreshToken(result);
  }

  @Post('logout')
  @HttpCode(HttpStatus.OK)
  @ApiOperation({ summary: 'Revoke the refresh token and clear the session cookie' })
  async logout(
    @Req() req: Request,
    @Body() dto: RefreshGrantDto,
    @Res({ passthrough: true }) res: Response,
  ) {
    const token = this.extractRefreshToken(req, dto);
    if (token) {
      await this.authService.logout(token);
    }
    clearRefreshCookie(res, this.cookieConfig);
    return { message: 'Logged out' };
  }

  @Get('me')
  @UseGuards(JwtAuthGuard)
  @ApiBearerAuth()
  @ApiOperation({ summary: 'Return the authenticated user profile' })
  me(@Req() req: AuthenticatedRequest): Promise<AuthResponse['user']> {
    return this.authService.me(req.authUser!.uuid);
  }

  private extractRefreshToken(req: Request, dto: RefreshGrantDto): string | null {
    const fromCookie: unknown =
      req.cookies && (req.cookies as Record<string, unknown>)[REFRESH_COOKIE_NAME];
    if (typeof fromCookie === 'string' && fromCookie.length > 0) {
      return fromCookie;
    }
    return dto.refreshToken && dto.refreshToken.length > 0 ? dto.refreshToken : null;
  }

  private stripRefreshToken(result: AuthResponse): AuthResponseBody {
    const { refreshToken: _omitted, ...body } = result;
    return body;
  }
}