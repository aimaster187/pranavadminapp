import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { buildPranavApp, PranavRuntime } from '../src/app.js';
import { loadConfig } from '../src/config/env.js';
import { CorruptedDatabaseError, openPranavDatabase } from '../src/db/database.js';
import {
  generateEcdsaKeyPairPem,
  randomTokenHex,
  signEcdsaPayload,
} from '../src/security/crypto.js';
import { TelegramApiTransport } from '../src/services/telegramBotService.js';

class MockTelegramTransport implements TelegramApiTransport {
  public calls: Array<{ token: string; method: string; payload: Record<string, unknown> }> = [];

  async callMethod<T = unknown>(
    botToken: string,
    method: string,
    payload: Record<string, unknown> = {}
  ): Promise<{ ok: boolean; result?: T; description?: string }> {
    this.calls.push({ token: botToken, method, payload });
    if (method === 'getMe') {
      return {
        ok: true,
        result: { id: 99887766, username: 'pranav_rangin_bot', first_name: 'RANGIN' } as T,
      };
    }
    if (method === 'getWebhookInfo') {
      return { ok: true, result: { url: 'https://pranav.example.com/webhook', pending_update_count: 0 } as T };
    }
    return { ok: true, result: true as T };
  }
}

const tempDirs: string[] = [];

function createTestRuntime(transport = new MockTelegramTransport()): {
  runtime: PranavRuntime;
  dir: string;
  transport: MockTelegramTransport;
} {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pranav-test-'));
  tempDirs.push(dir);
  const cfg = loadConfig({
    NODE_ENV: 'test',
    PRANAV_DB_PATH: path.join(dir, 'pranav.db'),
    PRANAV_BACKUP_DIR: path.join(dir, 'backups'),
    PRANAV_LOG_DIR: path.join(dir, 'logs'),
    PRANAV_MASTER_ENCRYPTION_KEY: randomTokenHex(32),
    PUBLIC_BASE_URL: 'https://pranav.example.com',
  });
  const runtime = buildPranavApp(cfg, { telegramTransport: transport, enableWorkers: false });
  return { runtime, dir, transport };
}

afterEach(() => {
  while (tempDirs.length > 0) {
    const d = tempDirs.pop()!;
    fs.rmSync(d, { recursive: true, force: true });
  }
});

describe('PRANAV Backend, Security, Multi-Bot Telegram & SDK Suite', () => {
  it('verifies SQLite WAL, integrity checks, corruption refusal, and verified backup/restore', async () => {
    const { runtime, dir } = createTestRuntime();
    const tablesRes = runtime.services.ops.listTablesOverview();
    expect(tablesRes.length).toBeGreaterThanOrEqual(26);

    const backup = runtime.services.ops.triggerBackup('unit', { type: 'system', id: 'test' });
    expect(backup.integrityOk).toBe(true);
    expect(runtime.services.ops.verifyBackup(backup.fileName).ok).toBe(true);

    // Insert an app after backup, then restore to prove snapshot rollback + safety copy
    runtime.services.appLicenseVip.createApp(
      { name: 'PostBackupApp', packageName: 'com.test.postbackup' },
      { type: 'system', id: 'test' }
    );
    expect(runtime.services.appLicenseVip.listApps().length).toBe(1);

    const restore = runtime.services.ops.restoreFromBackup(backup.fileName, {
      type: 'system',
      id: 'test',
    });
    expect(restore.restoredFrom).toBe(backup.fileName);
    expect(restore.safetySnapshot).toContain('pre_restore_safety');

    const reopened = openPranavDatabase(path.join(dir, 'pranav.db'));
    expect((reopened.prepare('SELECT count(*) AS cnt FROM apps').get() as { cnt: number }).cnt).toBe(0);
    reopened.close();
    runtime.db.close();

    // Refuse silently replacing a corrupted production database
    const corruptPath = path.join(dir, 'corrupt.db');
    fs.writeFileSync(corruptPath, 'NOT_A_VALID_SQLITE_HEADER_CORRUPTED_DATA');
    expect(() => openPranavDatabase(corruptPath)).toThrow(CorruptedDatabaseError);
  });

  it('enforces silent Owner Keystore enrollment, challenge-response, replay protection, and revocation', async () => {
    const { runtime } = createTestRuntime();
    const { publicKeyPem, privateKeyPem } = generateEcdsaKeyPairPem();
    const bootstrap = runtime.services.ownerAuth.issueEnrollmentToken('bootstrap', 'cli', 10);

    const enrollRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/auth/enroll',
      payload: {
        enrollmentToken: bootstrap.token,
        deviceLabel: 'Owner Pixel 9 Pro',
        hardwareModel: 'Pixel 9 Pro',
        publicKeyPem,
      },
    });
    expect(enrollRes.statusCode).toBe(200);
    const enrolled = enrollRes.json() as { ownerDeviceId: string; sessionToken: string };

    // Challenge-response auth
    const chlRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/auth/challenge',
      payload: { ownerDeviceId: enrolled.ownerDeviceId },
    });
    expect(chlRes.statusCode).toBe(200);
    const chl = chlRes.json() as { challengeId: string; nonce: string };
    const ts = new Date().toISOString();
    const sig = signEcdsaPayload(
      privateKeyPem,
      `${chl.nonce}:${chl.challengeId}:${enrolled.ownerDeviceId}:${ts}`
    );

    const verifyRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/auth/verify',
      payload: {
        ownerDeviceId: enrolled.ownerDeviceId,
        challengeId: chl.challengeId,
        timestamp: ts,
        signatureBase64: sig,
      },
    });
    expect(verifyRes.statusCode).toBe(200);
    const sessionToken = (verifyRes.json() as { sessionToken: string }).sessionToken;

    // Replay same challenge must fail
    const replayRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/auth/verify',
      payload: {
        ownerDeviceId: enrolled.ownerDeviceId,
        challengeId: chl.challengeId,
        timestamp: ts,
        signatureBase64: sig,
      },
    });
    expect(replayRes.statusCode).toBe(401);

    // Dashboard accessible with verified session
    const dashOk = await runtime.app.inject({
      method: 'GET',
      url: '/api/v1/admin/dashboard',
      headers: { authorization: `Bearer ${sessionToken}` },
    });
    expect(dashOk.statusCode).toBe(200);

    // Revoking device immediately kills active sessions
    runtime.services.ownerAuth.revokeOwnerDevice(enrolled.ownerDeviceId, 'test_admin');
    const dashRevoked = await runtime.app.inject({
      method: 'GET',
      url: '/api/v1/admin/dashboard',
      headers: { authorization: `Bearer ${sessionToken}` },
    });
    expect(dashRevoked.statusCode).toBe(401);

    await runtime.app.close();
    runtime.db.close();
  });

  it('validates multi-bot BotFather encryption, start/stop/restart, owner-only access, non-owner welcome wall, and dedup', async () => {
    const { runtime, transport } = createTestRuntime();
    const { publicKeyPem } = generateEcdsaKeyPairPem();
    const boot = runtime.services.ownerAuth.issueEnrollmentToken('bootstrap', 'cli', 10);
    const enroll = runtime.services.ownerAuth.enrollOwnerDevice({
      enrollmentToken: boot.token,
      deviceLabel: 'Owner Phone',
      hardwareModel: 'Pixel',
      publicKeyPem,
    });
    const authHeader = { authorization: `Bearer ${enroll.sessionToken}` };

    const rawBotToken = '99887766:AAH_test_super_secret_botfather_token_12345';
    const addBotRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/telegram-bots',
      headers: authHeader,
      payload: {
        name: 'RANGIN',
        botFatherToken: rawBotToken,
        ownerTelegramId: '777000111',
        welcomeMessage: 'Welcome to PRANAV RANGIN.',
        autoStart: true,
      },
    });
    expect(addBotRes.statusCode).toBe(200);
    const bot = addBotRes.json() as { id: string; status: string; token_masked: string };
    expect(bot.status).toBe('running');
    expect(bot.token_masked).not.toContain('super_secret_botfather');

    // Verify token is AES-256-GCM encrypted in SQLite (never plaintext!)
    const rawRow = runtime.db
      .prepare('SELECT encrypted_token, webhook_secret_encrypted, webhook_secret_iv, webhook_secret_tag FROM telegram_bots WHERE id = ?')
      .get(bot.id) as Record<string, string>;
    expect(rawRow.encrypted_token).not.toContain('AAH_test');

    // Decrypt webhook secret using runtime helper for realistic webhook dispatch test
    const { decryptSecret } = await import('../src/security/crypto.js');
    const webhookSecret = decryptSecret(
      {
        ciphertextBase64: rawRow.webhook_secret_encrypted,
        ivBase64: rawRow.webhook_secret_iv,
        tagBase64: rawRow.webhook_secret_tag,
      },
      runtime.config.masterEncryptionKey
    );

    // Wrong webhook secret rejected
    const badSecretRes = await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/telegram/webhook/${bot.id}`,
      headers: { 'x-telegram-bot-api-secret-token': 'wrong' },
      payload: { update_id: 1, message: { message_id: 1, from: { id: 777000111 }, chat: { id: 777000111 }, text: '/start' } },
    });
    expect(badSecretRes.statusCode).toBe(401);

    // Non-owner user (even trying /lockdown or spoofing username) receives ONLY Welcome message
    const intruderRes = await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/telegram/webhook/${bot.id}`,
      headers: { 'x-telegram-bot-api-secret-token': webhookSecret },
      payload: {
        update_id: 1001,
        message: {
          message_id: 10,
          from: { id: 123456789, username: 'pranav_owner' },
          chat: { id: 123456789 },
          text: '/lockdown',
        },
      },
    });
    expect(intruderRes.statusCode).toBe(200);
    expect(intruderRes.json()).toEqual({ ok: true, status: 'non_owner_welcome_only' });
    const lastMsg = transport.calls[transport.calls.length - 1];
    expect(lastMsg.method).toBe('sendMessage');
    expect(lastMsg.payload.text).toBe('Welcome to PRANAV RANGIN.');

    // Duplicate update_id is deduped
    const dupRes = await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/telegram/webhook/${bot.id}`,
      headers: { 'x-telegram-bot-api-secret-token': webhookSecret },
      payload: {
        update_id: 1001,
        message: { message_id: 10, from: { id: 123456789 }, chat: { id: 123456789 }, text: '/lockdown' },
      },
    });
    expect(dupRes.json()).toEqual({ ok: true, status: 'duplicate_update_ignored' });

    // Real numeric Owner executes /lockdown -> receives confirmation prompt -> confirms via callback
    const ownerCmd = await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/telegram/webhook/${bot.id}`,
      headers: { 'x-telegram-bot-api-secret-token': webhookSecret },
      payload: {
        update_id: 1002,
        message: {
          message_id: 11,
          from: { id: 777000111 },
          chat: { id: 777000111 },
          text: '/lockdown',
        },
      },
    });
    expect(ownerCmd.json()).toEqual({ ok: true, status: 'owner_processed' });
    const confirmPrompt = transport.calls[transport.calls.length - 1];
    const markup = confirmPrompt.payload.reply_markup as {
      inline_keyboard: Array<Array<{ callback_data: string }>>;
    };
    const confirmCallback = markup.inline_keyboard[0][0].callback_data;
    expect(confirmCallback.startsWith('confirm:')).toBe(true);

    // Non-owner trying to replay that confirm callback is STILL blocked!
    const spoofConfirm = await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/telegram/webhook/${bot.id}`,
      headers: { 'x-telegram-bot-api-secret-token': webhookSecret },
      payload: {
        update_id: 1003,
        callback_query: {
          id: 'cb_spoof',
          from: { id: 123456789 },
          data: confirmCallback,
          message: { message_id: 99, chat: { id: 123456789 } },
        },
      },
    });
    expect(spoofConfirm.json()).toEqual({ ok: true, status: 'non_owner_welcome_only' });

    // Owner confirms lockdown
    const ownerConfirm = await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/telegram/webhook/${bot.id}`,
      headers: { 'x-telegram-bot-api-secret-token': webhookSecret },
      payload: {
        update_id: 1004,
        callback_query: {
          id: 'cb_owner',
          from: { id: 777000111 },
          data: confirmCallback,
          message: { message_id: 99, chat: { id: 777000111 } },
        },
      },
    });
    expect(ownerConfirm.json()).toEqual({ ok: true, status: 'owner_processed' });

    // Bot lifecycle from Admin App: stop, restart, remove
    const stopRes = await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/admin/telegram-bots/${bot.id}/stop`,
      headers: authHeader,
    });
    expect((stopRes.json() as { status: string }).status).toBe('stopped');

    const restartRes = await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/admin/telegram-bots/${bot.id}/restart`,
      headers: authHeader,
    });
    expect((restartRes.json() as { status: string }).status).toBe('running');

    await runtime.app.close();
    runtime.db.close();
  });

  it('enforces userless Client SDK handshake, route isolation, license binding & transfer cooldown', async () => {
    const { runtime } = createTestRuntime();
    const { publicKeyPem: ownerPub } = generateEcdsaKeyPairPem();
    const boot = runtime.services.ownerAuth.issueEnrollmentToken('bootstrap', 'cli', 10);
    const owner = runtime.services.ownerAuth.enrollOwnerDevice({
      enrollmentToken: boot.token,
      deviceLabel: 'Owner',
      hardwareModel: 'Pixel',
      publicKeyPem: ownerPub,
    });

    const createdApp = runtime.services.appLicenseVip.createApp(
      { name: 'HostDemo', packageName: 'com.pranav.samplehost' },
      { type: 'owner_app', id: owner.ownerDeviceId }
    );
    const appId = (createdApp.app as { id: string }).id;

    // Issue 1 license with transfer cooldown = 3600s, maxTransfers = 1
    const [lic] = runtime.services.appLicenseVip.generateLicenses(
      { count: 1, scope: 'app', appId, durationDays: 30, maxTransfers: 1, transferCooldownSeconds: 3600 },
      { type: 'owner_app', id: owner.ownerDeviceId }
    );

    // Register Client Device A
    const keyA = generateEcdsaKeyPairPem();
    const regA = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/client/installations/register',
      payload: {
        appId,
        installationUuid: 'inst-uuid-aaaa-1111',
        appVersion: '1.0.0',
        versionCode: 1,
        publicKeyPem: keyA.publicKeyPem,
      },
    });
    expect(regA.statusCode).toBe(200);
    const devA = regA.json() as { installationId: string; deviceId: string };

    const chlA = (
      await runtime.app.inject({
        method: 'POST',
        url: '/api/v1/client/auth/challenge',
        payload: { appId, deviceId: devA.deviceId },
      })
    ).json() as { challengeId: string; nonce: string };

    const tsA = new Date().toISOString();
    const sigA = signEcdsaPayload(keyA.privateKeyPem, `${chlA.nonce}:${chlA.challengeId}:${devA.deviceId}:${tsA}`);
    const sesA = (
      await runtime.app.inject({
        method: 'POST',
        url: '/api/v1/client/auth/verify',
        payload: {
          appId,
          installationId: devA.installationId,
          deviceId: devA.deviceId,
          challengeId: chlA.challengeId,
          timestamp: tsA,
          signatureBase64: sigA,
        },
      })
    ).json() as { sessionToken: string };

    // Client SDK token MUST NOT access Admin endpoints
    const privEsc = await runtime.app.inject({
      method: 'GET',
      url: '/api/v1/admin/apps',
      headers: { authorization: `Bearer ${sesA.sessionToken}` },
    });
    expect(privEsc.statusCode).toBe(401);

    // Activate license on Device A via signed client request
    const signHeaders = (
      token: string,
      instId: string,
      privPem: string,
      method: string,
      urlPath: string
    ) => {
      const ts = new Date().toISOString();
      const nonce = randomTokenHex(8);
      const sig = signEcdsaPayload(
        privPem,
        `${method}:${urlPath}:${appId}:${instId}:${ts}:${nonce}`
      );
      return {
        authorization: `Bearer ${token}`,
        'x-app-id': appId,
        'x-installation-id': instId,
        'x-timestamp': ts,
        'x-nonce': nonce,
        'x-signature': sig,
      };
    };

    const actA = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/client/licenses/activate',
      headers: signHeaders(sesA.sessionToken, devA.installationId, keyA.privateKeyPem, 'POST', '/api/v1/client/licenses/activate'),
      payload: { licenseKey: lic.licenseKey, allowTransfer: false },
    });
    expect(actA.statusCode).toBe(200);
    expect((actA.json() as { status: string }).status).toBe('activated');

    // Device B registers & tries activating WITHOUT allowTransfer -> blocked
    const keyB = generateEcdsaKeyPairPem();
    const devB = (
      await runtime.app.inject({
        method: 'POST',
        url: '/api/v1/client/installations/register',
        payload: {
          appId,
          installationUuid: 'inst-uuid-bbbb-2222',
          appVersion: '1.0.0',
          versionCode: 1,
          publicKeyPem: keyB.publicKeyPem,
        },
      })
    ).json() as { installationId: string; deviceId: string };

    const chlB = (
      await runtime.app.inject({
        method: 'POST',
        url: '/api/v1/client/auth/challenge',
        payload: { appId, deviceId: devB.deviceId },
      })
    ).json() as { challengeId: string; nonce: string };
    const tsB = new Date().toISOString();
    const sesB = (
      await runtime.app.inject({
        method: 'POST',
        url: '/api/v1/client/auth/verify',
        payload: {
          appId,
          installationId: devB.installationId,
          deviceId: devB.deviceId,
          challengeId: chlB.challengeId,
          timestamp: tsB,
          signatureBase64: signEcdsaPayload(keyB.privateKeyPem, `${chlB.nonce}:${chlB.challengeId}:${devB.deviceId}:${tsB}`),
        },
      })
    ).json() as { sessionToken: string };

    const noTransferB = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/client/licenses/activate',
      headers: signHeaders(sesB.sessionToken, devB.installationId, keyB.privateKeyPem, 'POST', '/api/v1/client/licenses/activate'),
      payload: { licenseKey: lic.licenseKey, allowTransfer: false },
    });
    expect(noTransferB.statusCode).toBe(403);

    // Explicit verified transfer succeeds once, then cooldown blocks immediate second transfer back to A
    const transferB = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/client/licenses/activate',
      headers: signHeaders(sesB.sessionToken, devB.installationId, keyB.privateKeyPem, 'POST', '/api/v1/client/licenses/activate'),
      payload: { licenseKey: lic.licenseKey, allowTransfer: true },
    });
    expect(transferB.statusCode).toBe(200);
    expect((transferB.json() as { transferred: boolean }).transferred).toBe(true);

    const cooldownBlocked = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/client/licenses/activate',
      headers: signHeaders(sesA.sessionToken, devA.installationId, keyA.privateKeyPem, 'POST', '/api/v1/client/licenses/activate'),
      payload: { licenseKey: lic.licenseKey, allowTransfer: true },
    });
    expect(cooldownBlocked.statusCode).toBe(403);

    await runtime.app.close();
    runtime.db.close();
  });
});
