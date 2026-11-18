import { DatabaseSync } from 'node:sqlite';
import { PranavConfig } from '../config/env.js';
import {
  decryptSecret,
  encryptSecret,
  maskBotToken,
  randomId,
  randomTokenHex,
  safeEqual,
  sha256Hex,
} from '../security/crypto.js';
import { AppLicenseVipService } from './appLicenseVipService.js';
import { AuditActorType, AuditService } from './auditService.js';
import { DeliveryControlService } from './deliveryControlService.js';
import { OpsMonitorService } from './opsMonitorService.js';
import { OwnerAuthService } from './ownerAuthService.js';

export interface TelegramApiTransport {
  callMethod<T = unknown>(
    botToken: string,
    method: string,
    payload?: Record<string, unknown>
  ): Promise<{ ok: boolean; result?: T; description?: string }>;
}

export class FetchTelegramTransport implements TelegramApiTransport {
  async callMethod<T = unknown>(
    botToken: string,
    method: string,
    payload: Record<string, unknown> = {}
  ): Promise<{ ok: boolean; result?: T; description?: string }> {
    const res = await fetch(`https://api.telegram.org/bot${botToken}/${method}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    });
    return (await res.json()) as { ok: boolean; result?: T; description?: string };
  }
}

export interface TelegramUpdatePayload {
  update_id: number;
  message?: {
    message_id: number;
    from?: { id: number; username?: string; first_name?: string };
    chat?: { id: number; type?: string };
    text?: string;
  };
  callback_query?: {
    id: string;
    from?: { id: number; username?: string; first_name?: string };
    data?: string;
    message?: { message_id: number; chat?: { id: number } };
  };
}

const DEFAULT_COMMANDS = [
  { command: 'start', description: 'Open PRANAV Owner Control Hub' },
  { command: 'dashboard', description: 'KPIs & Live Analytics' },
  { command: 'apps', description: 'App Manager' },
  { command: 'users', description: 'Installation Manager' },
  { command: 'devices', description: 'Device Manager' },
  { command: 'licenses', description: 'License Manager' },
  { command: 'vip', description: 'VIP Manager' },
  { command: 'updates', description: 'Update Manager' },
  { command: 'dialogs', description: 'Universal Dialog Manager' },
  { command: 'announcements', description: 'Announcement Manager' },
  { command: 'configs', description: 'Remote Configuration Manager' },
  { command: 'modules', description: 'Dynamic Module Registry' },
  { command: 'maintenance', description: 'Maintenance Manager' },
  { command: 'lockdown', description: 'Emergency Lockdown' },
  { command: 'bots', description: 'Telegram Bot Manager (RANGIN)' },
  { command: 'dex', description: 'PRANAV.dex Release Manager' },
  { command: 'hook', description: 'PRANAV Hook Release Manager' },
  { command: 'db', description: 'Database Explorer' },
  { command: 'audit', description: 'Audit Logs' },
  { command: 'security', description: 'Security Center & Recovery' },
  { command: 'backup', description: 'Backup & Restore' },
  { command: 'health', description: 'Backend Health' },
  { command: 'server', description: 'Server Monitoring' },
  { command: 'scheduler', description: 'Configuration Scheduler' },
  { command: 'settings', description: 'System Settings' },
  { command: 'recovery', description: 'Issue 10m Owner Recovery Token' },
];

export class TelegramBotService {
  private readonly senderRateWindow = new Map<string, number[]>();
  private readonly pendingConfirms = new Map<
    string,
    { action: string; targetId: string; ownerId: string; expiresAt: number }
  >();

  constructor(
    private readonly db: DatabaseSync,
    private readonly config: PranavConfig,
    private readonly audit: AuditService,
    private readonly ownerAuth: OwnerAuthService,
    private readonly appLicenseVip: AppLicenseVipService,
    private readonly delivery: DeliveryControlService,
    private readonly ops: OpsMonitorService,
    private readonly transport: TelegramApiTransport = new FetchTelegramTransport()
  ) {}

  listBots() {
    const rows = this.db
      .prepare(
        `SELECT id, name, bot_id, bot_username, token_masked, owner_telegram_id, webhook_url,
                welcome_message, commands_json, permissions_json, settings_json, status,
                health_json, updates_processed, updates_rejected, updates_deduped,
                last_error, last_heartbeat_at, created_at, updated_at
         FROM telegram_bots ORDER BY created_at DESC`
      )
      .all() as Array<Record<string, unknown>>;
    return rows;
  }

  getBotPublicById(id: string) {
    return this.db
      .prepare(
        `SELECT id, name, bot_id, bot_username, token_masked, owner_telegram_id, webhook_url,
                welcome_message, commands_json, permissions_json, settings_json, status,
                health_json, updates_processed, updates_rejected, updates_deduped,
                last_error, last_heartbeat_at, created_at, updated_at
         FROM telegram_bots WHERE id = ?`
      )
      .get(id) as Record<string, unknown> | undefined;
  }

  async addBot(
    input: {
      name: string;
      botFatherToken: string;
      ownerTelegramId: string;
      webhookBaseUrl?: string;
      welcomeMessage?: string;
      commands?: Array<{ command: string; description: string }>;
      permissions?: string[];
      settings?: Record<string, unknown>;
      autoStart?: boolean;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const token = input.botFatherToken.trim();
    if (!/^\d+:[A-Za-z0-9_-]{20,}$/.test(token)) {
      throw new Error('Invalid BotFather token format.');
    }
    const ownerId = input.ownerTelegramId.trim();
    if (!/^\d{4,20}$/.test(ownerId)) {
      throw new Error('Owner Telegram Numeric ID must be a valid positive integer.');
    }

    const meRes = await this.transport.callMethod<{ id: number; username?: string; first_name?: string }>(
      token,
      'getMe'
    );
    if (!meRes.ok || !meRes.result) {
      throw new Error(`Telegram BotFather token verification failed: ${meRes.description ?? 'unknown error'}`);
    }

    const id = randomId('bot');
    const webhookSecret = randomTokenHex(24);
    const encToken = encryptSecret(token, this.config.masterEncryptionKey);
    const encSecret = encryptSecret(webhookSecret, this.config.masterEncryptionKey);
    const baseUrl = (input.webhookBaseUrl?.trim() || this.config.publicBaseUrl).replace(/\/+$/, '');
    const webhookUrl = `${baseUrl}/api/v1/telegram/webhook/${id}`;
    const now = new Date().toISOString();

    this.db
      .prepare(
        `INSERT INTO telegram_bots
         (id, name, bot_id, bot_username, encrypted_token, token_iv, token_tag, token_masked, token_sha256,
          owner_telegram_id, webhook_url, webhook_secret_encrypted, webhook_secret_iv, webhook_secret_tag, webhook_secret_sha256,
          welcome_message, commands_json, permissions_json, settings_json, status, health_json, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'stopped', ?, ?, ?)`
      )
      .run(
        id,
        input.name.trim(),
        String(meRes.result.id),
        meRes.result.username ?? '',
        encToken.ciphertextBase64,
        encToken.ivBase64,
        encToken.tagBase64,
        maskBotToken(token),
        sha256Hex(token),
        ownerId,
        webhookUrl,
        encSecret.ciphertextBase64,
        encSecret.ivBase64,
        encSecret.tagBase64,
        sha256Hex(webhookSecret),
        input.welcomeMessage?.trim() || 'Welcome to PRANAV RANGIN.',
        JSON.stringify(input.commands && input.commands.length > 0 ? input.commands : DEFAULT_COMMANDS),
        JSON.stringify(input.permissions && input.permissions.length > 0 ? input.permissions : ['all']),
        JSON.stringify(input.settings ?? { silentNotifications: false, notifyOnLockdown: true }),
        JSON.stringify({ verifiedAt: now, botFirstName: meRes.result.first_name ?? '' }),
        now,
        now
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'telegram_bots',
      action: 'add_telegram_bot',
      targetId: id,
      details: { name: input.name, botUsername: meRes.result.username, ownerTelegramId: ownerId },
    });

    if (input.autoStart !== false) {
      return await this.startBot(id, actor);
    }
    return this.getBotPublicById(id);
  }

  async configureBot(
    botId: string,
    patch: {
      name?: string;
      ownerTelegramId?: string;
      webhookUrl?: string;
      rotateWebhookSecret?: boolean;
      welcomeMessage?: string;
      commands?: Array<{ command: string; description: string }>;
      permissions?: string[];
      settings?: Record<string, unknown>;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const row = this.getBotSecretRow(botId);
    if (!row) throw new Error('Telegram bot not found.');

    if (patch.ownerTelegramId !== undefined && !/^\d{4,20}$/.test(patch.ownerTelegramId.trim())) {
      throw new Error('Owner Telegram Numeric ID must be numeric.');
    }

    const now = new Date().toISOString();
    let secretEncrypted = row.webhook_secret_encrypted;
    let secretIv = row.webhook_secret_iv;
    let secretTag = row.webhook_secret_tag;
    let secretSha = row.webhook_secret_sha256;

    if (patch.rotateWebhookSecret) {
      const nextSecret = randomTokenHex(24);
      const enc = encryptSecret(nextSecret, this.config.masterEncryptionKey);
      secretEncrypted = enc.ciphertextBase64;
      secretIv = enc.ivBase64;
      secretTag = enc.tagBase64;
      secretSha = sha256Hex(nextSecret);
    }

    this.db
      .prepare(
        `UPDATE telegram_bots
         SET name = ?, owner_telegram_id = ?, webhook_url = ?,
             webhook_secret_encrypted = ?, webhook_secret_iv = ?, webhook_secret_tag = ?, webhook_secret_sha256 = ?,
             welcome_message = ?, commands_json = ?, permissions_json = ?, settings_json = ?, updated_at = ?
         WHERE id = ?`
      )
      .run(
        patch.name ?? row.name,
        patch.ownerTelegramId?.trim() ?? row.owner_telegram_id,
        patch.webhookUrl?.trim() ?? row.webhook_url,
        secretEncrypted,
        secretIv,
        secretTag,
        secretSha,
        patch.welcomeMessage ?? row.welcome_message,
        patch.commands ? JSON.stringify(patch.commands) : row.commands_json,
        patch.permissions ? JSON.stringify(patch.permissions) : row.permissions_json,
        patch.settings ? JSON.stringify(patch.settings) : row.settings_json,
        now,
        botId
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'telegram_bots',
      action: 'configure_telegram_bot',
      targetId: botId,
      details: { ...patch, rotateWebhookSecret: Boolean(patch.rotateWebhookSecret) },
    });

    if (row.status === 'running') {
      return await this.startBot(botId, actor);
    }
    return this.getBotPublicById(botId);
  }

  async startBot(botId: string, actor: { type: AuditActorType; id: string }) {
    const row = this.getBotSecretRow(botId);
    if (!row) throw new Error('Telegram bot not found.');

    const token = decryptSecret(
      { ciphertextBase64: row.encrypted_token, ivBase64: row.token_iv, tagBase64: row.token_tag },
      this.config.masterEncryptionKey
    );
    const secret = decryptSecret(
      {
        ciphertextBase64: row.webhook_secret_encrypted,
        ivBase64: row.webhook_secret_iv,
        tagBase64: row.webhook_secret_tag,
      },
      this.config.masterEncryptionKey
    );

    const commands = JSON.parse(row.commands_json || '[]') as Array<{
      command: string;
      description: string;
    }>;
    const now = new Date().toISOString();

    if (row.webhook_url.startsWith('https://')) {
      const hookRes = await this.transport.callMethod(token, 'setWebhook', {
        url: row.webhook_url,
        secret_token: secret,
        allowed_updates: ['message', 'callback_query'],
      });
      if (!hookRes.ok) {
        this.db
          .prepare("UPDATE telegram_bots SET status = 'error', last_error = ?, updated_at = ? WHERE id = ?")
          .run(hookRes.description ?? 'setWebhook failed', now, botId);
        throw new Error(`setWebhook failed: ${hookRes.description ?? 'unknown'}`);
      }
    }

    await this.transport.callMethod(token, 'setMyCommands', { commands });

    this.db
      .prepare(
        "UPDATE telegram_bots SET status = 'running', last_error = '', last_heartbeat_at = ?, updated_at = ? WHERE id = ?"
      )
      .run(now, now, botId);

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'telegram_bots',
      action: 'start_telegram_bot',
      targetId: botId,
    });
    return this.getBotPublicById(botId);
  }

  async stopBot(botId: string, actor: { type: AuditActorType; id: string }) {
    const row = this.getBotSecretRow(botId);
    if (!row) throw new Error('Telegram bot not found.');
    const token = decryptSecret(
      { ciphertextBase64: row.encrypted_token, ivBase64: row.token_iv, tagBase64: row.token_tag },
      this.config.masterEncryptionKey
    );

    try {
      await this.transport.callMethod(token, 'deleteWebhook', { drop_pending_updates: false });
    } catch {
      // ignore network failure when stopping locally
    }

    const now = new Date().toISOString();
    this.db
      .prepare("UPDATE telegram_bots SET status = 'stopped', updated_at = ? WHERE id = ?")
      .run(now, botId);

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'telegram_bots',
      action: 'stop_telegram_bot',
      targetId: botId,
    });
    return this.getBotPublicById(botId);
  }

  async restartBot(botId: string, actor: { type: AuditActorType; id: string }) {
    await this.stopBot(botId, actor);
    const row = this.getBotSecretRow(botId)!;
    const token = decryptSecret(
      { ciphertextBase64: row.encrypted_token, ivBase64: row.token_iv, tagBase64: row.token_tag },
      this.config.masterEncryptionKey
    );
    const me = await this.transport.callMethod(token, 'getMe');
    if (!me.ok) {
      this.db
        .prepare("UPDATE telegram_bots SET status = 'error', last_error = ?, updated_at = ? WHERE id = ?")
        .run(me.description ?? 'getMe failed on restart', new Date().toISOString(), botId);
      throw new Error(`Bot restart verification failed: ${me.description ?? 'unknown'}`);
    }
    return await this.startBot(botId, actor);
  }

  async removeBot(botId: string, actor: { type: AuditActorType; id: string }) {
    await this.stopBot(botId, actor);
    this.db.prepare('DELETE FROM telegram_bots WHERE id = ?').run(botId);
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'telegram_bots',
      action: 'remove_telegram_bot',
      targetId: botId,
    });
  }

  async refreshBotHealth(botId: string) {
    const row = this.getBotSecretRow(botId);
    if (!row) throw new Error('Telegram bot not found.');
    const token = decryptSecret(
      { ciphertextBase64: row.encrypted_token, ivBase64: row.token_iv, tagBase64: row.token_tag },
      this.config.masterEncryptionKey
    );
    const info = await this.transport.callMethod(token, 'getWebhookInfo');
    const now = new Date().toISOString();
    this.db
      .prepare('UPDATE telegram_bots SET health_json = ?, last_heartbeat_at = ?, updated_at = ? WHERE id = ?')
      .run(JSON.stringify(info.result ?? { ok: info.ok, description: info.description }), now, now, botId);
    return this.getBotPublicById(botId);
  }

  // --- Strict Single-Owner Webhook Entrypoint ---
  async handleWebhookUpdate(
    botId: string,
    secretHeader: string | undefined,
    update: TelegramUpdatePayload
  ): Promise<{ ok: boolean; status: string }> {
    const row = this.getBotSecretRow(botId);
    if (!row) return { ok: false, status: 'bot_not_found' };
    if (row.status !== 'running') return { ok: false, status: 'bot_stopped' };

    if (!secretHeader || !safeEqual(sha256Hex(secretHeader), row.webhook_secret_sha256)) {
      this.audit.log({
        actorType: 'telegram_blocked',
        actorId: botId,
        module: 'telegram_bots',
        action: 'webhook_secret_mismatch',
        targetId: botId,
      });
      return { ok: false, status: 'invalid_webhook_secret' };
    }

    const senderIdNum = update.message?.from?.id ?? update.callback_query?.from?.id;
    const chatId = update.message?.chat?.id ?? update.callback_query?.message?.chat?.id ?? senderIdNum;
    if (senderIdNum === undefined || chatId === undefined) {
      return { ok: true, status: 'ignored_no_sender' };
    }
    const senderId = String(senderIdNum);

    // Rate limit per sender (30 requests / 60s)
    const rateKey = `${botId}:${senderId}`;
    const nowMs = Date.now();
    const hits = (this.senderRateWindow.get(rateKey) ?? []).filter((t) => nowMs - t < 60_000);
    if (hits.length >= 30) {
      return { ok: false, status: 'rate_limited' };
    }
    hits.push(nowMs);
    this.senderRateWindow.set(rateKey, hits);

    // Atomic Update ID deduplication
    const existing = this.db
      .prepare('SELECT id FROM telegram_updates WHERE bot_id = ? AND update_id = ?')
      .get(botId, update.update_id);
    if (existing) {
      this.db
        .prepare('UPDATE telegram_bots SET updates_deduped = updates_deduped + 1 WHERE id = ?')
        .run(botId);
      return { ok: true, status: 'duplicate_update_ignored' };
    }

    // STRICT NUMERIC OWNER ID VERIFICATION — never trust username, display name, or callback text
    const isAuthorizedOwner =
      /^\d+$/.test(senderId) &&
      /^\d+$/.test(row.owner_telegram_id) &&
      BigInt(senderId) === BigInt(row.owner_telegram_id);

    const interactionType = update.callback_query ? 'callback_query' : 'message';
    this.db
      .prepare(
        `INSERT INTO telegram_updates (id, bot_id, update_id, sender_id, authorized, interaction_type, received_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        randomId('tgu'),
        botId,
        update.update_id,
        senderId,
        isAuthorizedOwner ? 1 : 0,
        interactionType,
        new Date().toISOString()
      );

    const token = decryptSecret(
      { ciphertextBase64: row.encrypted_token, ivBase64: row.token_iv, tagBase64: row.token_tag },
      this.config.masterEncryptionKey
    );

    // NON-OWNER STRICT WELCOME-ONLY WALL
    if (!isAuthorizedOwner) {
      this.db
        .prepare('UPDATE telegram_bots SET updates_rejected = updates_rejected + 1 WHERE id = ?')
        .run(botId);
      this.audit.log({
        actorType: 'telegram_blocked',
        actorId: senderId,
        module: 'telegram_bots',
        action: 'non_owner_welcome_only',
        targetId: botId,
        details: { interactionType, text: update.message?.text ?? update.callback_query?.data ?? '' },
      });
      if (update.callback_query) {
        await this.transport.callMethod(token, 'answerCallbackQuery', {
          callback_query_id: update.callback_query.id,
          text: row.welcome_message,
          show_alert: false,
        });
      } else {
        await this.transport.callMethod(token, 'sendMessage', {
          chat_id: chatId,
          text: row.welcome_message,
        });
      }
      return { ok: true, status: 'non_owner_welcome_only' };
    }

    // AUTHORIZED OWNER EXECUTION
    this.db
      .prepare(
        'UPDATE telegram_bots SET updates_processed = updates_processed + 1, last_heartbeat_at = ? WHERE id = ?'
      )
      .run(new Date().toISOString(), botId);

    try {
      if (update.callback_query) {
        await this.handleOwnerCallback(token, botId, senderId, chatId, update.callback_query);
      } else if (update.message?.text) {
        await this.handleOwnerCommand(token, botId, senderId, chatId, update.message.text.trim());
      }
      return { ok: true, status: 'owner_processed' };
    } catch (err) {
      const msg = err instanceof Error ? err.message : String(err);
      this.db
        .prepare('UPDATE telegram_bots SET last_error = ? WHERE id = ?')
        .run(msg, botId);
      await this.transport.callMethod(token, 'sendMessage', {
        chat_id: chatId,
        text: `PRANAV Error: ${msg}`,
      });
      return { ok: false, status: 'owner_error' };
    }
  }

  private async handleOwnerCommand(
    token: string,
    botId: string,
    ownerId: string,
    chatId: number,
    text: string
  ) {
    const cmd = text.split(/\s+/)[0].toLowerCase().split('@')[0];
    const actor = { type: 'telegram_owner' as const, id: `tg:${ownerId}` };

    if (cmd === '/start' || cmd === '/menu') {
      await this.sendOwnerHub(token, chatId, botId);
      return;
    }

    if (cmd === '/recovery') {
      const rec = this.ownerAuth.issueEnrollmentToken('recovery', `tg:${ownerId}`, 10);
      await this.transport.callMethod(token, 'sendMessage', {
        chat_id: chatId,
        text: `Owner Recovery Token (10 min single-use):\n${rec.token}\nExpires: ${rec.expiresAt}`,
      });
      return;
    }

    if (cmd === '/bots') {
      await this.sendBotsMenu(token, chatId, botId);
      return;
    }

    if (cmd === '/lockdown') {
      await this.requestConfirmation(token, chatId, ownerId, 'lockdown_toggle', 'maint_global', 'Toggle Global Emergency Lockdown?');
      return;
    }

    if (cmd === '/backup') {
      const res = this.ops.triggerBackup('telegram_owner', actor);
      await this.transport.callMethod(token, 'sendMessage', {
        chat_id: chatId,
        text: `Verified Backup Created: ${res.fileName}\nSHA-256: ${res.sha256.slice(0, 16)}...\nSize: ${res.sizeBytes} bytes`,
      });
      return;
    }

    const moduleText = this.renderModuleSummary(cmd.replace('/', ''));
    await this.transport.callMethod(token, 'sendMessage', {
      chat_id: chatId,
      text: moduleText,
      reply_markup: this.buildRootKeyboard(botId),
    });
  }

  private async handleOwnerCallback(
    token: string,
    botId: string,
    ownerId: string,
    chatId: number,
    cb: NonNullable<TelegramUpdatePayload['callback_query']>
  ) {
    await this.transport.callMethod(token, 'answerCallbackQuery', {
      callback_query_id: cb.id,
    });
    const data = cb.data ?? '';
    const actor = { type: 'telegram_owner' as const, id: `tg:${ownerId}` };

    if (data.startsWith('confirm:')) {
      const tokenKey = data.slice('confirm:'.length);
      const pending = this.pendingConfirms.get(tokenKey);
      if (!pending || pending.ownerId !== ownerId || pending.expiresAt < Date.now()) {
        await this.transport.callMethod(token, 'sendMessage', {
          chat_id: chatId,
          text: 'Confirmation expired or invalid.',
        });
        return;
      }
      this.pendingConfirms.delete(tokenKey);
      const resultText = await this.executeConfirmedAction(pending.action, pending.targetId, actor);
      await this.transport.callMethod(token, 'sendMessage', {
        chat_id: chatId,
        text: resultText,
        reply_markup: this.buildRootKeyboard(botId),
      });
      return;
    }

    if (data === 'cancel') {
      await this.transport.callMethod(token, 'sendMessage', {
        chat_id: chatId,
        text: 'Action cancelled.',
        reply_markup: this.buildRootKeyboard(botId),
      });
      return;
    }

    if (data.startsWith('mod:')) {
      const mod = data.slice('mod:'.length);
      if (mod === 'bots') {
        await this.sendBotsMenu(token, chatId, botId);
        return;
      }
      await this.transport.callMethod(token, 'sendMessage', {
        chat_id: chatId,
        text: this.renderModuleSummary(mod),
        reply_markup: this.buildModuleActionsKeyboard(mod, botId),
      });
      return;
    }

    if (data.startsWith('bot_act:')) {
      const [, op, targetBotId] = data.split(':');
      if (op === 'start') {
        await this.startBot(targetBotId, actor);
        await this.sendBotsMenu(token, chatId, botId);
      } else if (op === 'restart') {
        await this.restartBot(targetBotId, actor);
        await this.sendBotsMenu(token, chatId, botId);
      } else if (op === 'stop') {
        await this.requestConfirmation(token, chatId, ownerId, 'bot_stop', targetBotId, `Stop Telegram bot ${targetBotId}?`);
      } else if (op === 'remove') {
        await this.requestConfirmation(token, chatId, ownerId, 'bot_remove', targetBotId, `Permanently remove Telegram bot ${targetBotId}?`);
      }
      return;
    }

    if (data.startsWith('ask:')) {
      const [, action, targetId] = data.split(':');
      await this.requestConfirmation(token, chatId, ownerId, action, targetId, `Confirm destructive action: ${action} (${targetId})?`);
      return;
    }

    await this.sendOwnerHub(token, chatId, botId);
  }

  private async executeConfirmedAction(
    action: string,
    targetId: string,
    actor: { type: AuditActorType; id: string }
  ): Promise<string> {
    if (action === 'lockdown_toggle') {
      const current = this.delivery.getMaintenanceState() as Array<{
        id: string;
        emergency_lockdown: number;
      }>;
      const globalRow = current.find((m) => m.id === 'maint_global');
      const nextLock = !globalRow?.emergency_lockdown;
      this.delivery.setMaintenanceState(
        {
          scope: 'global',
          isEnabled: nextLock,
          emergencyLockdown: nextLock,
          title: nextLock ? 'Emergency Lockdown Active' : 'Normal Operation',
          message: nextLock
            ? 'All client access suspended by Owner via Telegram.'
            : 'Services restored.',
        },
        actor
      );
      return `Global Emergency Lockdown is now ${nextLock ? 'ENABLED' : 'DISABLED'}.`;
    }
    if (action === 'bot_stop') {
      await this.stopBot(targetId, actor);
      return `Telegram bot ${targetId} stopped.`;
    }
    if (action === 'bot_remove') {
      await this.removeBot(targetId, actor);
      return `Telegram bot ${targetId} removed.`;
    }
    if (action === 'issue_license') {
      const [created] = this.appLicenseVip.generateLicenses(
        { count: 1, scope: 'global', durationDays: 30, notes: 'Issued via RANGIN Telegram' },
        actor
      );
      return `Global 30-day license generated: ${created.licenseKey}`;
    }
    return `Executed ${action} on ${targetId}.`;
  }

  private async requestConfirmation(
    token: string,
    chatId: number,
    ownerId: string,
    action: string,
    targetId: string,
    promptText: string
  ) {
    const confirmKey = randomTokenHex(8);
    this.pendingConfirms.set(confirmKey, {
      action,
      targetId,
      ownerId,
      expiresAt: Date.now() + 120_000,
    });
    await this.transport.callMethod(token, 'sendMessage', {
      chat_id: chatId,
      text: promptText,
      reply_markup: {
        inline_keyboard: [
          [
            { text: 'Confirm', callback_data: `confirm:${confirmKey}` },
            { text: 'Cancel', callback_data: 'cancel' },
          ],
        ],
      },
    });
  }

  private async sendOwnerHub(token: string, chatId: number, botId: string) {
    const dash = this.ops.getDashboardSummary();
    await this.transport.callMethod(token, 'sendMessage', {
      chat_id: chatId,
      text: [
        'PRANAV RANGIN — Single-Owner Super Admin',
        `Apps: ${dash.kpis.activeApps}/${dash.kpis.totalApps} | Installs: ${dash.kpis.totalInstallations}`,
        `Licenses: ${dash.kpis.activeLicenses}/${dash.kpis.totalLicenses} | VIP: ${dash.kpis.activeVip}`,
        `Bots Running: ${dash.kpis.activeBots}/${dash.kpis.totalBots} | Lockdown: ${dash.kpis.emergencyLockdown ? 'ON' : 'OFF'}`,
      ].join('\n'),
      reply_markup: this.buildRootKeyboard(botId),
    });
  }

  private async sendBotsMenu(token: string, chatId: number, currentBotId: string) {
    const bots = this.listBots();
    const lines = bots.map(
      (b) =>
        `• ${b.name} (@${b.bot_username || 'unlinked'}) [${b.status}] Owner:${b.owner_telegram_id} | Proc:${b.updates_processed} Rej:${b.updates_rejected}`
    );
    const keyboard = bots.slice(0, 6).map((b) => [
      { text: `Start ${b.name}`, callback_data: `bot_act:start:${b.id}` },
      { text: `Restart`, callback_data: `bot_act:restart:${b.id}` },
      { text: `Stop`, callback_data: `bot_act:stop:${b.id}` },
    ]);
    keyboard.push([{ text: 'Back to Hub', callback_data: `mod:dashboard` }]);

    await this.transport.callMethod(token, 'sendMessage', {
      chat_id: chatId,
      text: `Telegram Bot Manager (Current Bot: ${currentBotId})\n${lines.join('\n') || 'No bots configured.'}`,
      reply_markup: { inline_keyboard: keyboard },
    });
  }

  private buildRootKeyboard(_botId: string) {
    return {
      inline_keyboard: [
        [
          { text: 'Dashboard', callback_data: 'mod:dashboard' },
          { text: 'Apps', callback_data: 'mod:apps' },
          { text: 'Installs', callback_data: 'mod:users' },
        ],
        [
          { text: 'Devices', callback_data: 'mod:devices' },
          { text: 'Licenses', callback_data: 'mod:licenses' },
          { text: 'VIP', callback_data: 'mod:vip' },
        ],
        [
          { text: 'Updates', callback_data: 'mod:updates' },
          { text: 'Dialogs', callback_data: 'mod:dialogs' },
          { text: 'Announce', callback_data: 'mod:announcements' },
        ],
        [
          { text: 'RemoteCfg', callback_data: 'mod:configs' },
          { text: 'Modules', callback_data: 'mod:modules' },
          { text: 'Maint', callback_data: 'mod:maintenance' },
        ],
        [
          { text: 'Lockdown', callback_data: 'mod:lockdown' },
          { text: 'Bot Mgr', callback_data: 'mod:bots' },
          { text: 'DEX/Hook', callback_data: 'mod:dex' },
        ],
        [
          { text: 'DB/Audit', callback_data: 'mod:db' },
          { text: 'Security', callback_data: 'mod:security' },
          { text: 'Health/Srv', callback_data: 'mod:health' },
        ],
      ],
    };
  }

  private buildModuleActionsKeyboard(mod: string, _botId: string) {
    const rows: Array<Array<{ text: string; callback_data: string }>> = [];
    if (mod === 'licenses') {
      rows.push([{ text: 'Generate Global 30d Key', callback_data: 'ask:issue_license:global' }]);
    } else if (mod === 'lockdown' || mod === 'maintenance') {
      rows.push([{ text: 'Toggle Emergency Lockdown', callback_data: 'ask:lockdown_toggle:maint_global' }]);
    }
    rows.push([{ text: 'Back to Hub', callback_data: 'mod:dashboard' }]);
    return { inline_keyboard: rows };
  }

  private renderModuleSummary(mod: string): string {
    switch (mod) {
      case 'dashboard': {
        const d = this.ops.getDashboardSummary();
        return `Dashboard\nApps: ${d.kpis.activeApps}/${d.kpis.totalApps}\nInstalls: ${d.kpis.totalInstallations}\nActive Devices: ${d.kpis.activeDevices}\nActive Licenses: ${d.kpis.activeLicenses}\nVIP Active: ${d.kpis.activeVip}`;
      }
      case 'apps':
        return `Apps (${this.appLicenseVip.listApps().length} total)`;
      case 'users':
        return `Installations (${this.appLicenseVip.listInstallations().length} latest)`;
      case 'devices':
        return `Cryptographic Devices (${this.appLicenseVip.listDevices().length} latest)`;
      case 'licenses':
        return `Licenses (${this.appLicenseVip.listLicenses().length} latest)`;
      case 'vip':
        return `VIP Plans: ${this.appLicenseVip.listVipPlans().length} | Subscriptions: ${this.appLicenseVip.listVipSubscriptions().length}`;
      case 'updates':
        return `Updates (${this.delivery.listUpdates().length} configured)`;
      case 'dialogs':
        return `Universal Dialogs (${this.delivery.listDialogs().length} configured)`;
      case 'announcements':
        return `Announcements (${this.delivery.listAnnouncements().length})`;
      case 'configs':
        return `Remote Config Versions (${this.delivery.listRemoteConfigs().length})`;
      case 'modules':
        return `Declarative Dynamic Modules (${this.delivery.listDynamicModules().length})`;
      case 'maintenance':
      case 'lockdown':
        return `Maintenance & Lockdown:\n${JSON.stringify(this.delivery.getMaintenanceState())}`;
      case 'dex':
        return `PRANAV.dex Releases: ${this.delivery.listDexReleases().length} | Hook Releases: ${this.delivery.listHookReleases().length}`;
      case 'hook':
        return `PRANAV Hook Releases: ${this.delivery.listHookReleases().length}`;
      case 'db':
        return `Database Tables: ${this.ops.listTablesOverview().length} | Integrity: ${this.ops.runDatabaseIntegrityCheck().integrity}`;
      case 'audit':
        return `Recent Audit Entries: ${this.audit.list(10).length}`;
      case 'security':
        return `Security Center — Enrolled Owner Devices: ${this.ownerAuth.listOwnerDevices().length}`;
      case 'health':
      case 'server': {
        const h = this.ops.getBackendHealth();
        const s = this.ops.getServerMonitoring();
        return `Backend: ${h.status} (${h.uptimeSeconds}s) | RAM: ${h.memory.rssMb}MB | OS Load: ${s.loadAverage[0].toFixed(2)}`;
      }
      case 'scheduler':
        return `Scheduled Jobs: ${this.ops.listSchedules().length}`;
      case 'settings':
        return `System Settings Keys: ${this.ops.listSystemSettings().length}`;
      default:
        return `Module '${mod}' ready.`;
    }
  }

  private getBotSecretRow(botId: string) {
    return this.db.prepare('SELECT * FROM telegram_bots WHERE id = ?').get(botId) as
      | {
          id: string;
          name: string;
          encrypted_token: string;
          token_iv: string;
          token_tag: string;
          owner_telegram_id: string;
          webhook_url: string;
          webhook_secret_encrypted: string;
          webhook_secret_iv: string;
          webhook_secret_tag: string;
          webhook_secret_sha256: string;
          welcome_message: string;
          commands_json: string;
          permissions_json: string;
          settings_json: string;
          status: string;
        }
      | undefined;
  }
}
