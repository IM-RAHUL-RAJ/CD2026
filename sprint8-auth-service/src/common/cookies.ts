import { CookieOptions, Response } from 'express';

export const REFRESH_COOKIE_NAME = 'refresh_token';

export interface RefreshCookieConfig {
  name: string;
  secure: boolean;
  sameSite: 'lax' | 'strict' | 'none';
}

export function refreshCookieOptions(
  config: RefreshCookieConfig,
  maxAgeSeconds: number,
): CookieOptions {
  return {
    httpOnly: true,
    secure: config.secure,
    sameSite: config.sameSite,
    path: '/',
    maxAge: maxAgeSeconds * 1000,
  };
}

export function setRefreshCookie(
  res: Response,
  token: string,
  config: RefreshCookieConfig,
  maxAgeSeconds: number,
): void {
  res.clearCookie(REFRESH_COOKIE_NAME, {
    httpOnly: true,
    secure: config.secure,
    sameSite: config.sameSite,
    path: '/',
  });
  res.cookie(REFRESH_COOKIE_NAME, token, refreshCookieOptions(config, maxAgeSeconds));
}

export function clearRefreshCookie(res: Response, config: RefreshCookieConfig): void {
  res.clearCookie(REFRESH_COOKIE_NAME, {
    httpOnly: true,
    secure: config.secure,
    sameSite: config.sameSite,
    path: '/',
  });
}