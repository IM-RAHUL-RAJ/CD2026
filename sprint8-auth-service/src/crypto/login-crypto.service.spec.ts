import * as crypto from 'crypto';
import * as os from 'os';
import * as path from 'path';
import { ConfigService } from '@nestjs/config';
import { LoginCryptoService } from './login-crypto.service';

function buildConfig(dir: string): ConfigService {
  return {
    get: (key: string) => ({ 'crypto.keyDir': dir })[key],
  } as unknown as ConfigService;
}

/**
 * Replicates the browser Web Crypto sealing used by the front end:
 * RSA-OAEP-wrapped AES-GCM with the ciphertext's 16-byte tag appended.
 */
function seal(
  publicKey: crypto.KeyObject,
  payload: Record<string, unknown>,
): string {
  const aesKey = crypto.randomBytes(32);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', aesKey, iv);
  const body = Buffer.concat([
    cipher.update(JSON.stringify(payload), 'utf8'),
    cipher.final(),
  ]);
  const sealed = Buffer.concat([body, cipher.getAuthTag()]);
  const encKey = crypto.publicEncrypt(
    { key: publicKey, padding: crypto.constants.RSA_PKCS1_OAEP_PADDING, oaepHash: 'sha256' },
    aesKey,
  );
  return [encKey.toString('base64'), iv.toString('base64'), sealed.toString('base64')].join('.');
}

describe('LoginCryptoService', () => {
  let service: LoginCryptoService;
  let publicKey: crypto.KeyObject;
  const dir = path.join(os.tmpdir(), 'auth-crypto-unit-' + Date.now());

  function validPayload(overrides: Partial<Record<string, unknown>> = {}) {
    return {
      identifier: 'priya.menon',
      password: 'Capstone@2026',
      nonce: crypto.randomBytes(16).toString('hex'),
      expiresAt: Date.now() + 60000,
      ...overrides,
    };
  }

  beforeEach(async () => {
    service = new LoginCryptoService(buildConfig(dir));
    await service.onModuleInit();
    publicKey = crypto.createPublicKey(service.getPublicKeyPem());
  });

  it('serves a DER-encoded RSA public key', () => {
    expect(service.getPublicKeyPem()).toContain('BEGIN PUBLIC KEY');
    expect(publicKey.asymmetricKeyType).toBe('rsa');
  });

  it('decrypts a browser-sealed login payload', () => {
    const sealed = seal(publicKey, validPayload());
    const plain = service.decryptRequest(sealed);
    expect(plain.identifier).toBe('priya.menon');
    expect(plain.password).toBe('Capstone@2026');
  });

  it('rejects a replayed (same nonce) payload', () => {
    const sealed = seal(publicKey, validPayload());
    service.decryptRequest(sealed);
    expect(() => service.decryptRequest(sealed)).toThrow('replay');
  });

  it('accepts a fresh payload with a different nonce', () => {
    service.decryptRequest(seal(publicKey, validPayload()));
    expect(() => {
      service.decryptRequest(seal(publicKey, validPayload()));
    }).not.toThrow();
  });

  it('rejects a stale (expired) payload', () => {
    const sealed = seal(publicKey, validPayload({ expiresAt: Date.now() - 60000 }));
    expect(() => service.decryptRequest(sealed)).toThrow('stale');
  });

  it('rejects payloads sealed past the freshness window', () => {
    const sealed = seal(
      publicKey,
      validPayload({ expiresAt: Date.now() + 10 * 60 * 1000 }),
    );
    expect(() => service.decryptRequest(sealed)).toThrow('stale');
  });

  it('rejects tampered ciphertext', () => {
    const sealed = seal(publicKey, validPayload());
    const parts = sealed.split('.');
    const ct = Buffer.from(parts[2], 'base64');
    ct[0] = ct[0] ^ 0xff;
    const tampered = [parts[0], parts[1], ct.toString('base64')].join('.');
    expect(() => service.decryptRequest(tampered)).toThrow();
  });

  it('rejects a payload encrypted under a different key', () => {
    const { publicKey: other } = crypto.generateKeyPairSync('rsa', {
      modulusLength: 4096,
      publicKeyEncoding: { type: 'spki', format: 'pem' },
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    });
    const sealed = seal(crypto.createPublicKey(other), validPayload());
    expect(() => service.decryptRequest(sealed)).toThrow();
  });

  it('rejects malformed requests', () => {
    expect(() => service.decryptRequest('nonsense')).toThrow();
    expect(() => service.decryptRequest('a.b')).toThrow();
  });
});