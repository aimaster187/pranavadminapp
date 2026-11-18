import crypto from 'node:crypto';
import path from 'node:path';

export interface PranavConfig {
  nodeEnv: string;
  port: number;
  host: string;
  dbPath: string;
  backupDir: string;
  logDir: string;
  masterEncryptionKey: Buffer;
  ownerTelegramId: string;
  ranginBotToken: string;
  publicBaseUrl: string;
  challengeTtlSeconds: number;
  adminSessionTtlSeconds: number;
  sdkSessionTtlSeconds: number;
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): PranavConfig {
  const nodeEnv = env.NODE_ENV ?? 'development';
  const rawKey = env.PRANAV_MASTER_ENCRYPTION_KEY?.trim() ?? '';

  let masterEncryptionKey: Buffer;
  if (/^[0-9a-fA-F]{64}$/.test(rawKey)) {
    masterEncryptionKey = Buffer.from(rawKey, 'hex');
  } else if (nodeEnv === 'production') {
    throw new Error(
      'PRANAV_MASTER_ENCRYPTION_KEY must be a 64-character hex string in production.'
    );
  } else {
    // Ephemeral process-scoped key for local dev/test only; never hardcoded across restarts.
    masterEncryptionKey = crypto.randomBytes(32);
  }

  return {
    nodeEnv,
    port: Number(env.PORT ?? 8443),
    host: env.HOST ?? '127.0.0.1',
    dbPath: path.resolve(env.PRANAV_DB_PATH ?? './data/pranav.db'),
    backupDir: path.resolve(env.PRANAV_BACKUP_DIR ?? './backups'),
    logDir: path.resolve(env.PRANAV_LOG_DIR ?? './logs'),
    masterEncryptionKey,
    ownerTelegramId: (env.OWNER_TELEGRAM_ID ?? '').trim(),
    ranginBotToken: (env.RANGIN_BOT_TOKEN ?? '').trim(),
    publicBaseUrl: (env.PUBLIC_BASE_URL ?? 'https://pranav.example.com').replace(/\/+$/, ''),
    challengeTtlSeconds: Number(env.CHALLENGE_TTL_SECONDS ?? 45),
    adminSessionTtlSeconds: Number(env.ADMIN_SESSION_TTL_SECONDS ?? 86400),
    sdkSessionTtlSeconds: Number(env.SDK_SESSION_TTL_SECONDS ?? 43200),
  };
}
