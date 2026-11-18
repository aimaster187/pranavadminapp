import { DatabaseSync } from 'node:sqlite';
import { PranavConfig } from '../config/env.js';
import {
  randomId,
  randomTokenHex,
  sha256Hex,
  verifyEcdsaSignature,
} from '../security/crypto.js';
import { AuditService } from './auditService.js';

export interface VerifiedClientContext {
  appId: string;
  installationId: string;
  deviceId: string;
  sessionId: string;
}

export class ClientSdkService {
  constructor(
    private readonly db: DatabaseSync,
    private readonly config: PranavConfig,
    private readonly audit: AuditService
  ) {}

  private checkAppAndMaintenance(appId: string, installationId?: string) {
    const app = this.db.prepare('SELECT * FROM apps WHERE id = ?').get(appId) as
      | { id: string; status: string; approval_policy: string; name: string }
      | undefined;
    if (!app) throw new Error('Unauthorized application.');
    if (app.status !== 'active') throw new Error(`Application is ${app.status}.`);

    const maintRows = this.db
      .prepare(
        "SELECT * FROM maintenance WHERE scope = 'global' OR (scope = 'app' AND app_id = ?) ORDER BY scope DESC"
      )
      .all(appId) as Array<{
      id: string;
      scope: string;
      is_enabled: number;
      emergency_lockdown: number;
      title: string;
      message: string;
      eta_iso: string | null;
      allowlisted_installations_json: string;
    }>;

    for (const m of maintRows) {
      if (m.emergency_lockdown === 1) {
        throw new Error('EMERGENCY_LOCKDOWN: Service access temporarily suspended.');
      }
      if (m.is_enabled === 1) {
        const allowlist = JSON.parse(m.allowlisted_installations_json || '[]') as string[];
        if (!installationId || !allowlist.includes(installationId)) {
          return {
            app,
            maintenance: {
              active: true,
              title: m.title,
              message: m.message,
              etaIso: m.eta_iso,
            },
          };
        }
      }
    }
    return { app, maintenance: { active: false } };
  }

  registerInstallation(input: {
    appId: string;
    installationUuid: string;
    appVersion: string;
    versionCode: number;
    publicKeyPem: string;
    hardwareModel: string;
    osVersion: string;
  }) {
    const { app } = this.checkAppAndMaintenance(input.appId);
    const now = new Date().toISOString();
    const keyFingerprint = sha256Hex(input.publicKeyPem.trim());

    let installation = this.db
      .prepare('SELECT * FROM installations WHERE app_id = ? AND installation_uuid = ?')
      .get(input.appId, input.installationUuid) as { id: string; status: string } | undefined;

    if (!installation) {
      const instId = randomId('inst');
      this.db
        .prepare(
          `INSERT INTO installations (id, app_id, installation_uuid, app_version, version_code, status, first_seen_at, last_seen_at)
           VALUES (?, ?, ?, ?, ?, 'active', ?, ?)`
        )
        .run(instId, input.appId, input.installationUuid, input.appVersion, input.versionCode, now, now);
      installation = { id: instId, status: 'active' };
    } else {
      if (installation.status === 'blocked') {
        throw new Error('Installation is blocked.');
      }
      this.db
        .prepare('UPDATE installations SET app_version = ?, version_code = ?, last_seen_at = ? WHERE id = ?')
        .run(input.appVersion, input.versionCode, now, installation.id);
    }

    let device = this.db
      .prepare('SELECT * FROM devices WHERE app_id = ? AND device_key_fingerprint = ?')
      .get(input.appId, keyFingerprint) as { id: string; approval_status: string } | undefined;

    if (!device) {
      const devId = randomId('dev');
      const initialApproval = app.approval_policy === 'manual' ? 'pending' : 'approved';
      this.db
        .prepare(
          `INSERT INTO devices (id, installation_id, app_id, device_key_fingerprint, public_key_pem, hardware_model, os_version, approval_status, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .run(
          devId,
          installation.id,
          input.appId,
          keyFingerprint,
          input.publicKeyPem.trim(),
          input.hardwareModel,
          input.osVersion,
          initialApproval,
          now
        );
      device = { id: devId, approval_status: initialApproval };
    }

    if (device.approval_status === 'revoked' || device.approval_status === 'banned') {
      throw new Error(`Device is ${device.approval_status}.`);
    }

    this.audit.log({
      actorType: 'client_sdk',
      actorId: installation.id,
      module: 'sdk',
      action: 'register_installation',
      targetId: device.id,
      details: { appId: input.appId, approvalStatus: device.approval_status },
    });

    return {
      installationId: installation.id,
      deviceId: device.id,
      approvalStatus: device.approval_status,
      keyFingerprint,
    };
  }

  createDeviceChallenge(appId: string, deviceId: string) {
    this.checkAppAndMaintenance(appId);
    const device = this.db
      .prepare('SELECT id, approval_status FROM devices WHERE id = ? AND app_id = ?')
      .get(deviceId, appId) as { id: string; approval_status: string } | undefined;

    if (!device) throw new Error('Device not registered.');
    if (device.approval_status !== 'approved') {
      throw new Error(`Device approval status is '${device.approval_status}'.`);
    }

    const challengeId = randomId('cchl');
    const nonce = randomTokenHex(32);
    const now = new Date();
    const expiresAt = new Date(now.getTime() + this.config.challengeTtlSeconds * 1000).toISOString();

    this.db
      .prepare(
        `INSERT INTO device_challenges (id, actor_type, actor_id, nonce, expires_at, created_at)
         VALUES (?, 'client', ?, ?, ?, ?)`
      )
      .run(challengeId, deviceId, nonce, expiresAt, now.toISOString());

    return { challengeId, nonce, expiresAt };
  }

  verifyDeviceChallenge(input: {
    appId: string;
    installationId: string;
    deviceId: string;
    challengeId: string;
    timestamp: string;
    signatureBase64: string;
  }) {
    this.checkAppAndMaintenance(input.appId, input.installationId);
    const now = new Date();
    const nowIso = now.toISOString();

    const device = this.db
      .prepare("SELECT * FROM devices WHERE id = ? AND app_id = ? AND approval_status = 'approved'")
      .get(input.deviceId, input.appId) as
      | { id: string; public_key_pem: string }
      | undefined;
    if (!device) throw new Error('Device not approved.');

    const challenge = this.db
      .prepare(
        `SELECT * FROM device_challenges
         WHERE id = ? AND actor_type = 'client' AND actor_id = ? AND consumed_at IS NULL AND expires_at > ?`
      )
      .get(input.challengeId, input.deviceId, nowIso) as { id: string; nonce: string } | undefined;
    if (!challenge) throw new Error('Challenge expired or invalid.');

    const canonical = `${challenge.nonce}:${challenge.id}:${input.deviceId}:${input.timestamp}`;
    if (!verifyEcdsaSignature(device.public_key_pem, canonical, input.signatureBase64)) {
      throw new Error('Client device signature verification failed.');
    }

    const sessionToken = `prn_sdk_${randomTokenHex(32)}`;
    const expiresAt = new Date(now.getTime() + this.config.sdkSessionTtlSeconds * 1000).toISOString();

    this.db.exec('BEGIN IMMEDIATE;');
    try {
      this.db
        .prepare('UPDATE device_challenges SET consumed_at = ? WHERE id = ?')
        .run(nowIso, challenge.id);
      this.db
        .prepare('UPDATE devices SET last_authenticated_at = ? WHERE id = ?')
        .run(nowIso, input.deviceId);
      this.db
        .prepare(
          `INSERT INTO sdk_sessions (id, app_id, installation_id, device_id, token_hash, expires_at, created_at, last_seen_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .run(
          randomId('sdks'),
          input.appId,
          input.installationId,
          input.deviceId,
          sha256Hex(sessionToken),
          expiresAt,
          nowIso,
          nowIso
        );
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }

    return { sessionToken, expiresAt };
  }

  authenticateClientRequest(headers: {
    sessionToken: string;
    appId: string;
    installationId: string;
    timestamp: string;
    nonce: string;
    signatureBase64: string;
    method: string;
    path: string;
  }): VerifiedClientContext {
    const nowIso = new Date().toISOString();
    const sess = this.db
      .prepare(
        `SELECT s.id AS session_id, s.app_id, s.installation_id, s.device_id, d.public_key_pem, d.approval_status, i.status AS inst_status
         FROM sdk_sessions s
         JOIN devices d ON d.id = s.device_id
         JOIN installations i ON i.id = s.installation_id
         WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > ?`
      )
      .get(sha256Hex(headers.sessionToken), nowIso) as
      | {
          session_id: string;
          app_id: string;
          installation_id: string;
          device_id: string;
          public_key_pem: string;
          approval_status: string;
          inst_status: string;
        }
      | undefined;

    if (!sess) throw new Error('Invalid or expired SDK session.');
    if (sess.app_id !== headers.appId || sess.installation_id !== headers.installationId) {
      throw new Error('Session binding mismatch.');
    }
    if (sess.approval_status !== 'approved' || sess.inst_status !== 'active') {
      throw new Error('Device or installation is revoked/blocked.');
    }

    const tsMs = Date.parse(headers.timestamp);
    if (!Number.isFinite(tsMs) || Math.abs(Date.now() - tsMs) > 120_000) {
      throw new Error('Request timestamp outside allowed clock skew.');
    }

    const canonical = `${headers.method.toUpperCase()}:${headers.path}:${headers.appId}:${headers.installationId}:${headers.timestamp}:${headers.nonce}`;
    if (!verifyEcdsaSignature(sess.public_key_pem, canonical, headers.signatureBase64)) {
      throw new Error('Signed client request verification failed.');
    }

    this.db.prepare('UPDATE sdk_sessions SET last_seen_at = ? WHERE id = ?').run(nowIso, sess.session_id);
    this.db.prepare('UPDATE installations SET last_seen_at = ? WHERE id = ?').run(nowIso, sess.installation_id);

    return {
      appId: sess.app_id,
      installationId: sess.installation_id,
      deviceId: sess.device_id,
      sessionId: sess.session_id,
    };
  }

  getClientBootstrapPayload(ctx: VerifiedClientContext, appVersionCode: number) {
    const { maintenance } = this.checkAppAndMaintenance(ctx.appId, ctx.installationId);
    const nowIso = new Date().toISOString();

    const update = this.db
      .prepare(
        `SELECT * FROM updates
         WHERE app_id = ? AND is_active = 1 AND (scheduled_at IS NULL OR scheduled_at <= ?)
         ORDER BY latest_version_code DESC LIMIT 1`
      )
      .get(ctx.appId, nowIso) as Record<string, unknown> | undefined;

    const dialogs = this.db
      .prepare(
        `SELECT * FROM dialogs
         WHERE is_enabled = 1 AND (app_id = ? OR app_id IS NULL)
           AND (scheduled_at IS NULL OR scheduled_at <= ?)
           AND (expires_at IS NULL OR expires_at > ?)
         ORDER BY priority DESC, created_at DESC`
      )
      .all(ctx.appId, nowIso, nowIso);

    const announcements = this.db
      .prepare(
        `SELECT * FROM announcements
         WHERE is_enabled = 1 AND (app_id = ? OR app_id IS NULL)
           AND (scheduled_at IS NULL OR scheduled_at <= ?)
           AND (expires_at IS NULL OR expires_at > ?)
         ORDER BY is_pinned DESC, priority DESC`
      )
      .all(ctx.appId, nowIso, nowIso);

    const configs = this.db
      .prepare(
        `SELECT * FROM remote_configs
         WHERE is_active = 1
           AND ((scope = 'global') OR (scope = 'app' AND app_id = ?) OR (scope = 'installation' AND installation_id = ?))
           AND (scheduled_at IS NULL OR scheduled_at <= ?)
         ORDER BY CASE scope WHEN 'global' THEN 1 WHEN 'app' THEN 2 ELSE 3 END ASC`
      )
      .all(ctx.appId, ctx.installationId, nowIso) as Array<Record<string, unknown>>;

    const mergedConfig = {
      featureFlags: {} as Record<string, unknown>,
      remoteText: {} as Record<string, unknown>,
      remoteLinks: {} as Record<string, unknown>,
      dynamicSettings: {} as Record<string, unknown>,
    };
    for (const c of configs) {
      Object.assign(mergedConfig.featureFlags, JSON.parse(String(c.feature_flags_json || '{}')));
      Object.assign(mergedConfig.remoteText, JSON.parse(String(c.remote_text_json || '{}')));
      Object.assign(mergedConfig.remoteLinks, JSON.parse(String(c.remote_links_json || '{}')));
      Object.assign(mergedConfig.dynamicSettings, JSON.parse(String(c.dynamic_settings_json || '{}')));
    }

    const dynamicModules = this.db
      .prepare('SELECT id, module_key, capability_type, version, config_json FROM dynamic_modules WHERE is_enabled = 1 AND (app_id = ? OR app_id IS NULL)')
      .all(ctx.appId);

    const activeLicense = this.db
      .prepare(
        `SELECT id, license_key, scope, status, expires_at, max_transfers, transfer_count
         FROM licenses WHERE bound_device_id = ? AND status = 'active' AND (scope = 'global' OR app_id = ?) LIMIT 1`
      )
      .get(ctx.deviceId, ctx.appId);

    const activeVip = this.db
      .prepare(
        `SELECT s.id, s.status, s.payment_verified, s.expires_at, p.code, p.name, p.entitlements_json
         FROM vip_subscriptions s JOIN vip_plans p ON p.id = s.plan_id
         WHERE s.installation_id = ? AND s.status = 'active' AND s.payment_verified = 1 AND s.expires_at > ?
         ORDER BY s.expires_at DESC LIMIT 1`
      )
      .get(ctx.installationId, nowIso);

    return {
      maintenance,
      updateAvailable:
        update && Number(update.latest_version_code) > appVersionCode ? update : null,
      forceUpdateRequired:
        update && Number(update.min_version_code) > appVersionCode ? true : false,
      dialogs,
      announcements,
      remoteConfig: mergedConfig,
      dynamicModules,
      license: activeLicense ?? null,
      vip: activeVip ?? null,
      serverTimestamp: nowIso,
    };
  }

  activateOrTransferLicense(ctx: VerifiedClientContext, licenseKey: string, allowTransfer: boolean) {
    this.checkAppAndMaintenance(ctx.appId, ctx.installationId);
    const now = new Date();
    const nowIso = now.toISOString();

    const lic = this.db.prepare('SELECT * FROM licenses WHERE license_key = ?').get(licenseKey.trim()) as
      | {
          id: string;
          scope: string;
          app_id: string | null;
          status: string;
          bound_device_id: string | null;
          max_transfers: number;
          transfer_count: number;
          transfer_cooldown_seconds: number;
          last_transfer_at: string | null;
          expires_at: string | null;
        }
      | undefined;

    if (!lic) throw new Error('License key not found.');
    if (lic.scope === 'app' && lic.app_id !== ctx.appId) {
      throw new Error('License is not valid for this application.');
    }
    if (lic.status === 'revoked') throw new Error('License has been revoked.');
    if (lic.expires_at && new Date(lic.expires_at) <= now) {
      this.db.prepare("UPDATE licenses SET status = 'expired', updated_at = ? WHERE id = ?").run(nowIso, lic.id);
      throw new Error('License has expired.');
    }

    if (!lic.bound_device_id || lic.bound_device_id === ctx.deviceId) {
      this.db
        .prepare(
          `UPDATE licenses SET status = 'active', bound_device_id = ?, bound_installation_id = ?,
           activated_at = COALESCE(activated_at, ?), updated_at = ? WHERE id = ?`
        )
        .run(ctx.deviceId, ctx.installationId, nowIso, nowIso, lic.id);
      return { status: 'activated', licenseId: lic.id, transferred: false };
    }

    // Bound to another device: strictly enforce cryptographic device auth + transfer flag + limits + cooldown!
    if (!allowTransfer) {
      throw new Error('License is bound to another device. Explicit verified device transfer is required.');
    }
    if (lic.transfer_count >= lic.max_transfers) {
      throw new Error('License transfer limit reached.');
    }
    if (lic.last_transfer_at) {
      const elapsedSec = (now.getTime() - new Date(lic.last_transfer_at).getTime()) / 1000;
      if (elapsedSec < lic.transfer_cooldown_seconds) {
        const remaining = Math.ceil(lic.transfer_cooldown_seconds - elapsedSec);
        throw new Error(`Transfer cooldown active (${remaining}s remaining).`);
      }
    }

    this.db.exec('BEGIN IMMEDIATE;');
    try {
      this.db
        .prepare(
          `INSERT INTO license_transfers (id, license_id, from_device_id, to_device_id, initiated_by, reason, transferred_at)
           VALUES (?, ?, ?, ?, ?, 'verified_client_transfer', ?)`
        )
        .run(randomId('ltr'), lic.id, lic.bound_device_id, ctx.deviceId, ctx.deviceId, nowIso);

      this.db
        .prepare(
          `UPDATE licenses
           SET bound_device_id = ?, bound_installation_id = ?, transfer_count = transfer_count + 1,
               last_transfer_at = ?, updated_at = ?
           WHERE id = ?`
        )
        .run(ctx.deviceId, ctx.installationId, nowIso, nowIso, lic.id);
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }

    this.audit.log({
      actorType: 'client_sdk',
      actorId: ctx.deviceId,
      module: 'licenses',
      action: 'device_transfer',
      targetId: lic.id,
      details: { fromDevice: lic.bound_device_id, toDevice: ctx.deviceId },
    });

    return { status: 'transferred', licenseId: lic.id, transferred: true };
  }

  recordUpdateConfirmation(
    ctx: VerifiedClientContext,
    updateId: string,
    action: 'shown' | 'later' | 'accepted' | 'completed',
    appVersionCode: number
  ) {
    this.db
      .prepare(
        `INSERT INTO update_confirmations (id, update_id, installation_id, device_id, action, app_version_code, confirmed_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        randomId('ucnf'),
        updateId,
        ctx.installationId,
        ctx.deviceId,
        action,
        appVersionCode,
        new Date().toISOString()
      );
  }

  recordEvent(ctx: VerifiedClientContext, eventType: string, payload: Record<string, unknown>) {
    this.db
      .prepare(
        `INSERT INTO events (id, app_id, installation_id, device_id, event_type, payload_json, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        randomId('evt'),
        ctx.appId,
        ctx.installationId,
        ctx.deviceId,
        eventType,
        JSON.stringify(payload),
        new Date().toISOString()
      );
  }
}
