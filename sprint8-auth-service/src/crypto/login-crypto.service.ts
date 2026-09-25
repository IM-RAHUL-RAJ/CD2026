import {
  createDecipheriv,
  createHash,
  createPrivateKey,
  generateKeyPairSync,
  KeyObject,
  privateDecrypt,
} from 'crypto';
import * as fs from 'fs';
import * as path from 'path';
import { Injectable, Logger, OnModuleInit } from '@nestjs/common';
import { ConfigService } from '@nestjs/config';

const OAEP_HASH = 'sha256';
const AES_ALGORITHM = 'aes-256-gcm';
const GCM_TAG_BYTES = 16;
const NONCE_RE = /^[a-f0-9]{16,}$/i;
const REPLAY_TTL_MS = 10 * 60 * 1000;
const MAX_FRESHNESS_MS = 5 * 60 * 1000;
const CLOCK_SKEW_MS = 30 * 1000;

interface SealedPayload {
  nonce?: unknown;
  expiresAt?: unknown;
  [key: string]: unknown;
}

/**
 * Holds the RSA-4096 keypair used to protect customer credentials in transit.
 *
 * The browser seals the login/register payload with the public key (RSA-OAEP
 * wrapped AES-GCM) so the plaintext password never appears in the request
 * body. The private key never leaves the server. Each sealed payload carries a
 * fresh nonce and an expiry; the server rejects replayed or stale payloads.
 */
@Injectable()
export class LoginCryptoService implements OnModuleInit {
  private readonly logger = new Logger(LoginCryptoService.name);
  private privateKey: KeyObject | undefined;
  private publicPem = '';
  private readonly keyDir: string;
  private readonly replay = new Map<string, number>();

  constructor(private readonly config: ConfigService) {
    this.keyDir =
      this.config.get<string>('crypto.keyDir') ??
      path.join(process.cwd(), 'login-keys');
  }

  async onModuleInit(): Promise<void> {
    this.loadOrCreateKeypair();
  }

  getPublicKeyPem(): string {
    if (!this.publicPem) {
      this.loadOrCreateKeypair();
    }
    return this.publicPem;
  }

  /**
   * Decrypts a sealed credential payload in the form
   * `base64(RSA-OAEP(aesKey)) . base64(iv) . base64(AES-GCM-ciphertext)` and
   * validates its nonce/expiry. Throws on any tamper, expiry or replay.
   */
  decryptRequest(request: string): Record<string, unknown> {
    const parts = request.split('.');
    if (parts.length !== 3 || parts.some((p) => p.length === 0)) {
      throw new Error('malformed credential request');
    }

    const aesKey = this.unwrapAesKey(parts[0]);
    const payload = this.decryptPayload(aesKey, parts[1], parts[2]);
    this.consumeNonce(payload);

    return payload;
  }

  private loadOrCreateKeypair(): void {
    const privatePath = path.join(this.keyDir, 'private.pem');
    const publicPath = path.join(this.keyDir, 'public.pem');

    if (fs.existsSync(privatePath) && fs.existsSync(publicPath)) {
      this.privateKey = createPrivateKey(fs.readFileSync(privatePath, 'utf8'));
      this.publicPem = fs.readFileSync(publicPath, 'utf8');
      return;
    }

    fs.mkdirSync(this.keyDir, { recursive: true });
    const { publicKey, privateKey } = generateKeyPairSync('rsa', {
      modulusLength: 4096,
      publicKeyEncoding: { type: 'spki', format: 'pem' },
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    });
    fs.writeFileSync(privatePath, privateKey, { mode: 0o600 });
    fs.writeFileSync(publicPath, publicKey, { mode: 0o644 });
    this.privateKey = createPrivateKey(privateKey);
    this.publicPem = publicKey;
    this.logger.log(`Generated RSA-4096 login keypair at ${this.keyDir}`);
  }

  private unwrapAesKey(encKeyB64: string): Buffer {
    const encKey = Buffer.from(encKeyB64, 'base64');
    try {
      return privateDecrypt(
        {
          key: this.rootPrivateKey(),
          padding: 4, // RSA_PKCS1_OAEP_PADDING
          oaepHash: OAEP_HASH,
        },
        encKey,
      );
    } catch {
      throw new Error('unable to unwrap credential key');
    }
  }

  private decryptPayload(aesKey: Buffer, ivB64: string, cipherB64: string): SealedPayload {
    const iv = Buffer.from(ivB64, 'base64');
    const cipher = Buffer.from(cipherB64, 'base64');
    if (cipher.length <= GCM_TAG_BYTES) {
      throw new Error('truncated credential payload');
    }
    const tag = cipher.subarray(cipher.length - GCM_TAG_BYTES);
    const data = cipher.subarray(0, cipher.length - GCM_TAG_BYTES);

    let plaintext: Buffer;
    try {
      const decipher = createDecipheriv(AES_ALGORITHM, aesKey, iv);
      decipher.setAuthTag(tag);
      plaintext = Buffer.concat([decipher.update(data), decipher.final()]);
    } catch {
      throw new Error('credential payload failed authentication');
    }

    try {
      return JSON.parse(plaintext.toString('utf8')) as SealedPayload;
    } catch {
      throw new Error('credential payload is not valid JSON');
    }
  }

  private consumeNonce(payload: SealedPayload): void {
    const { nonce, expiresAt } = payload;
    if (typeof nonce !== 'string' || !NONCE_RE.test(nonce)) {
      throw new Error('missing credential nonce');
    }
    const now = Date.now();
    if (
      typeof expiresAt !== 'number' ||
      expiresAt <= now - CLOCK_SKEW_MS ||
      expiresAt > now + MAX_FRESHNESS_MS
    ) {
      throw new Error('stale credential payload');
    }

    this.pruneReplay(now);
    const nonceHash = createHash('sha256').update(nonce).digest('hex');
    if (this.replay.has(nonceHash)) {
      throw new Error('credential replay detected');
    }
    this.replay.set(nonceHash, now + REPLAY_TTL_MS);
  }

  private pruneReplay(now: number): void {
    for (const [key, expiresAt] of this.replay) {
      if (expiresAt <= now) {
        this.replay.delete(key);
      }
    }
  }

  private rootPrivateKey(): KeyObject {
    if (!this.privateKey) {
      this.loadOrCreateKeypair();
    }
    return this.privateKey as KeyObject;
  }
}