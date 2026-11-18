import crypto from 'node:crypto';

export interface EncryptedBlob {
  ciphertextBase64: string;
  ivBase64: string;
  tagBase64: string;
}

export function sha256Hex(input: string | Buffer): string {
  return crypto.createHash('sha256').update(input).digest('hex');
}

export function randomTokenHex(bytes = 32): string {
  return crypto.randomBytes(bytes).toString('hex');
}

export function randomId(prefix: string): string {
  return `${prefix}_${crypto.randomUUID().replace(/-/g, '')}`;
}

export function safeEqual(a: string, b: string): boolean {
  const bufA = Buffer.from(a, 'utf8');
  const bufB = Buffer.from(b, 'utf8');
  if (bufA.length !== bufB.length) return false;
  return crypto.timingSafeEqual(bufA, bufB);
}

export function encryptSecret(plaintext: string, masterKey: Buffer): EncryptedBlob {
  if (masterKey.length !== 32) {
    throw new Error('Master encryption key must be 32 bytes (AES-256-GCM).');
  }
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', masterKey, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  return {
    ciphertextBase64: encrypted.toString('base64'),
    ivBase64: iv.toString('base64'),
    tagBase64: tag.toString('base64'),
  };
}

export function decryptSecret(blob: EncryptedBlob, masterKey: Buffer): string {
  if (masterKey.length !== 32) {
    throw new Error('Master encryption key must be 32 bytes (AES-256-GCM).');
  }
  const decipher = crypto.createDecipheriv(
    'aes-256-gcm',
    masterKey,
    Buffer.from(blob.ivBase64, 'base64')
  );
  decipher.setAuthTag(Buffer.from(blob.tagBase64, 'base64'));
  const decrypted = Buffer.concat([
    decipher.update(Buffer.from(blob.ciphertextBase64, 'base64')),
    decipher.final(),
  ]);
  return decrypted.toString('utf8');
}

export function maskBotToken(token: string): string {
  const colonIdx = token.indexOf(':');
  if (colonIdx <= 0) return '***';
  const botId = token.slice(0, colonIdx);
  const secret = token.slice(colonIdx + 1);
  if (secret.length <= 8) return `${botId}:****`;
  return `${botId}:${secret.slice(0, 4)}...${secret.slice(-4)}`;
}

export function verifyEcdsaSignature(
  publicKeyPemOrBase64Spki: string,
  payload: string,
  signatureBase64: string
): boolean {
  try {
    const trimmed = publicKeyPemOrBase64Spki.trim();
    const keyPem = trimmed.startsWith('-----BEGIN')
      ? trimmed
      : `-----BEGIN PUBLIC KEY-----\n${trimmed.match(/.{1,64}/g)?.join('\n') ?? trimmed}\n-----END PUBLIC KEY-----`;

    const publicKey = crypto.createPublicKey(keyPem);
    const sigBuf = Buffer.from(signatureBase64, 'base64');
    const dataBuf = Buffer.from(payload, 'utf8');

    // Android Keystore SHA256withECDSA emits ASN.1 DER signatures by default.
    if (crypto.verify('sha256', dataBuf, publicKey, sigBuf)) {
      return true;
    }
    // Support IEEE P1363 raw 64-byte (R||S) ECDSA signatures if supplied by lightweight clients.
    if (sigBuf.length === 64) {
      return crypto.verify(
        'sha256',
        dataBuf,
        { key: publicKey, dsaEncoding: 'ieee-p1363' },
        sigBuf
      );
    }
    return false;
  } catch {
    return false;
  }
}

export function generateEcdsaKeyPairPem(): { publicKeyPem: string; privateKeyPem: string } {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
  });
  return {
    publicKeyPem: publicKey.export({ type: 'spki', format: 'pem' }).toString(),
    privateKeyPem: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
  };
}

export function signEcdsaPayload(privateKeyPem: string, payload: string): string {
  return crypto
    .sign('sha256', Buffer.from(payload, 'utf8'), crypto.createPrivateKey(privateKeyPem))
    .toString('base64');
}
