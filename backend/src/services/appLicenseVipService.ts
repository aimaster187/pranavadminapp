import { DatabaseSync } from 'node:sqlite';
import { randomId, randomTokenHex, sha256Hex } from '../security/crypto.js';
import { AuditActorType, AuditService } from './auditService.js';

export class AppLicenseVipService {
  constructor(
    private readonly db: DatabaseSync,
    private readonly audit: AuditService
  ) {}

  // --- Module 2: App Manager ---
  listApps() {
    return this.db
      .prepare(
        `SELECT id, name, package_name, signing_sha256, approval_policy, status, description, created_at, updated_at,
         (SELECT count(*) FROM installations i WHERE i.app_id = apps.id) AS installation_count,
         (SELECT count(*) FROM licenses l WHERE l.app_id = apps.id AND l.status = 'active') AS active_license_count
         FROM apps ORDER BY created_at DESC`
      )
      .all();
  }

  createApp(
    input: {
      name: string;
      packageName: string;
      signingSha256?: string;
      approvalPolicy?: 'auto' | 'manual';
      description?: string;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('app');
    const rawSecret = `prn_app_${randomTokenHex(24)}`;
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO apps (id, name, package_name, signing_sha256, app_secret_hash, approval_policy, status, description, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, 'active', ?, ?, ?)`
      )
      .run(
        id,
        input.name,
        input.packageName,
        input.signingSha256 ?? '',
        sha256Hex(rawSecret),
        input.approvalPolicy ?? 'auto',
        input.description ?? '',
        now,
        now
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'apps',
      action: 'create_app',
      targetId: id,
      details: { name: input.name, packageName: input.packageName },
    });

    return { app: this.getAppById(id), issuedAppKey: rawSecret };
  }

  getAppById(id: string) {
    return this.db
      .prepare(
        `SELECT id, name, package_name, signing_sha256, approval_policy, status, description, created_at, updated_at
         FROM apps WHERE id = ?`
      )
      .get(id);
  }

  updateApp(
    id: string,
    patch: {
      name?: string;
      signingSha256?: string;
      approvalPolicy?: 'auto' | 'manual';
      status?: 'active' | 'suspended' | 'locked';
      description?: string;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const existing = this.getAppById(id) as Record<string, unknown> | undefined;
    if (!existing) throw new Error('App not found.');
    const now = new Date().toISOString();
    this.db
      .prepare(
        `UPDATE apps
         SET name = ?, signing_sha256 = ?, approval_policy = ?, status = ?, description = ?, updated_at = ?
         WHERE id = ?`
      )
      .run(
        String(patch.name ?? existing.name),
        String(patch.signingSha256 ?? existing.signing_sha256),
        String(patch.approvalPolicy ?? existing.approval_policy),
        String(patch.status ?? existing.status),
        String(patch.description ?? existing.description),
        now,
        id
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'apps',
      action: 'update_app',
      targetId: id,
      details: patch,
    });
    return this.getAppById(id);
  }

  deleteApp(id: string, actor: { type: AuditActorType; id: string }) {
    this.db.prepare('DELETE FROM apps WHERE id = ?').run(id);
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'apps',
      action: 'delete_app',
      targetId: id,
    });
  }

  // --- Module 3: User (Installation) Manager ---
  listInstallations(appId?: string, limit = 100) {
    if (appId) {
      return this.db
        .prepare(
          `SELECT i.*, a.name AS app_name FROM installations i
           JOIN apps a ON a.id = i.app_id
           WHERE i.app_id = ? ORDER BY i.last_seen_at DESC LIMIT ?`
        )
        .all(appId, limit);
    }
    return this.db
      .prepare(
        `SELECT i.*, a.name AS app_name FROM installations i
         JOIN apps a ON a.id = i.app_id
         ORDER BY i.last_seen_at DESC LIMIT ?`
      )
      .all(limit);
  }

  setInstallationStatus(
    installationId: string,
    status: 'active' | 'blocked',
    actor: { type: AuditActorType; id: string }
  ) {
    this.db.prepare('UPDATE installations SET status = ? WHERE id = ?').run(status, installationId);
    if (status === 'blocked') {
      this.db
        .prepare('UPDATE sdk_sessions SET revoked_at = ? WHERE installation_id = ? AND revoked_at IS NULL')
        .run(new Date().toISOString(), installationId);
    }
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'installations',
      action: `set_status_${status}`,
      targetId: installationId,
    });
  }

  // --- Module 4: Device Manager ---
  listDevices(appId?: string, limit = 100) {
    if (appId) {
      return this.db
        .prepare(
          `SELECT id, installation_id, app_id, device_key_fingerprint, hardware_model, os_version, approval_status, created_at, last_authenticated_at, revoked_at
           FROM devices WHERE app_id = ? ORDER BY created_at DESC LIMIT ?`
        )
        .all(appId, limit);
    }
    return this.db
      .prepare(
        `SELECT id, installation_id, app_id, device_key_fingerprint, hardware_model, os_version, approval_status, created_at, last_authenticated_at, revoked_at
         FROM devices ORDER BY created_at DESC LIMIT ?`
      )
      .all(limit);
  }

  updateDeviceApproval(
    deviceId: string,
    approvalStatus: 'pending' | 'approved' | 'revoked' | 'banned',
    actor: { type: AuditActorType; id: string }
  ) {
    const now = new Date().toISOString();
    const revokedAt = approvalStatus === 'revoked' || approvalStatus === 'banned' ? now : null;
    this.db
      .prepare('UPDATE devices SET approval_status = ?, revoked_at = ? WHERE id = ?')
      .run(approvalStatus, revokedAt, deviceId);

    if (revokedAt) {
      this.db
        .prepare('UPDATE sdk_sessions SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL')
        .run(now, deviceId);
    }
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'devices',
      action: `device_${approvalStatus}`,
      targetId: deviceId,
    });
  }

  // --- Module 5: License Manager ---
  listLicenses(status?: string, limit = 150) {
    if (status) {
      return this.db
        .prepare('SELECT * FROM licenses WHERE status = ? ORDER BY created_at DESC LIMIT ?')
        .all(status, limit);
    }
    return this.db.prepare('SELECT * FROM licenses ORDER BY created_at DESC LIMIT ?').all(limit);
  }

  generateLicenses(
    input: {
      count: number;
      scope: 'app' | 'global';
      appId?: string;
      durationDays?: number;
      maxTransfers?: number;
      transferCooldownSeconds?: number;
      notes?: string;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    if (input.scope === 'app' && !input.appId) {
      throw new Error('appId is required for app-specific licenses.');
    }
    const count = Math.min(Math.max(input.count, 1), 100);
    const now = new Date();
    const nowIso = now.toISOString();
    const expiresAt =
      input.durationDays && input.durationDays > 0
        ? new Date(now.getTime() + input.durationDays * 86400_000).toISOString()
        : null;

    const created: Array<Record<string, unknown>> = [];
    this.db.exec('BEGIN IMMEDIATE;');
    try {
      const stmt = this.db.prepare(
        `INSERT INTO licenses
         (id, license_key, scope, app_id, status, max_transfers, transfer_count, transfer_cooldown_seconds, expires_at, notes, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'unused', ?, 0, ?, ?, ?, ?, ?)`
      );
      for (let i = 0; i < count; i++) {
        const id = randomId('lic');
        const seg = () => randomTokenHex(2).toUpperCase();
        const key = `PRN-${seg()}-${seg()}-${seg()}-${seg()}`;
        stmt.run(
          id,
          key,
          input.scope,
          input.scope === 'app' ? input.appId! : null,
          input.maxTransfers ?? 2,
          input.transferCooldownSeconds ?? 86400,
          expiresAt,
          input.notes ?? '',
          nowIso,
          nowIso
        );
        created.push({ id, licenseKey: key, scope: input.scope, expiresAt });
      }
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'licenses',
      action: 'generate_licenses',
      details: { count, scope: input.scope, appId: input.appId },
    });
    return created;
  }

  mutateLicense(
    licenseId: string,
    op: { action: 'activate' | 'revoke' | 'renew'; renewDays?: number; deviceId?: string },
    actor: { type: AuditActorType; id: string }
  ) {
    const lic = this.db.prepare('SELECT * FROM licenses WHERE id = ?').get(licenseId) as
      | Record<string, unknown>
      | undefined;
    if (!lic) throw new Error('License not found.');
    const now = new Date();
    const nowIso = now.toISOString();

    if (op.action === 'revoke') {
      this.db
        .prepare("UPDATE licenses SET status = 'revoked', updated_at = ? WHERE id = ?")
        .run(nowIso, licenseId);
    } else if (op.action === 'activate') {
      this.db
        .prepare(
          `UPDATE licenses SET status = 'active', bound_device_id = COALESCE(?, bound_device_id),
           activated_at = COALESCE(activated_at, ?), updated_at = ? WHERE id = ?`
        )
        .run(op.deviceId ?? null, nowIso, nowIso, licenseId);
    } else if (op.action === 'renew') {
      const days = Math.max(op.renewDays ?? 30, 1);
      const base =
        lic.expires_at && new Date(String(lic.expires_at)) > now
          ? new Date(String(lic.expires_at))
          : now;
      const nextExpiry = new Date(base.getTime() + days * 86400_000).toISOString();
      this.db
        .prepare(
          `UPDATE licenses SET expires_at = ?, status = CASE WHEN status = 'expired' THEN 'active' ELSE status END, updated_at = ? WHERE id = ?`
        )
        .run(nextExpiry, nowIso, licenseId);
    }

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'licenses',
      action: `license_${op.action}`,
      targetId: licenseId,
      details: op,
    });
    return this.db.prepare('SELECT * FROM licenses WHERE id = ?').get(licenseId);
  }

  getLicenseHistory(licenseId: string) {
    return this.db
      .prepare('SELECT * FROM license_transfers WHERE license_id = ? ORDER BY transferred_at DESC')
      .all(licenseId);
  }

  // --- Module 6: VIP Manager ---
  listVipPlans() {
    return this.db.prepare('SELECT * FROM vip_plans ORDER BY created_at DESC').all();
  }

  createVipPlan(
    input: {
      code: string;
      name: string;
      appId?: string;
      durationDays: number;
      maxDevices: number;
      priceLabel: string;
      entitlements?: Record<string, unknown>;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('vplan');
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO vip_plans (id, code, name, app_id, duration_days, max_devices, price_label, entitlements_json, is_active, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`
      )
      .run(
        id,
        input.code,
        input.name,
        input.appId ?? null,
        input.durationDays,
        input.maxDevices,
        input.priceLabel,
        JSON.stringify(input.entitlements ?? {}),
        now,
        now
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'vip',
      action: 'create_vip_plan',
      targetId: id,
      details: { code: input.code, name: input.name },
    });
    return this.db.prepare('SELECT * FROM vip_plans WHERE id = ?').get(id);
  }

  listVipSubscriptions() {
    return this.db
      .prepare(
        `SELECT s.*, p.code AS plan_code, p.name AS plan_name
         FROM vip_subscriptions s JOIN vip_plans p ON p.id = s.plan_id
         ORDER BY s.created_at DESC LIMIT 150`
      )
      .all();
  }

  activateVipSubscription(
    input: {
      planId: string;
      installationId: string;
      deviceId?: string;
      paymentVerified: boolean;
      paymentReference: string;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    if (!input.paymentVerified) {
      throw new Error('Server-side payment verification is required before activating VIP.');
    }
    const plan = this.db.prepare('SELECT * FROM vip_plans WHERE id = ?').get(input.planId) as
      | { id: string; duration_days: number; max_devices: number }
      | undefined;
    if (!plan) throw new Error('VIP plan not found.');

    const id = randomId('vsub');
    const now = new Date();
    const expiresAt = new Date(now.getTime() + plan.duration_days * 86400_000).toISOString();
    this.db
      .prepare(
        `INSERT INTO vip_subscriptions
         (id, plan_id, installation_id, device_id, status, payment_verified, payment_reference, device_limit, activated_at, expires_at, created_at)
         VALUES (?, ?, ?, ?, 'active', 1, ?, ?, ?, ?, ?)`
      )
      .run(
        id,
        plan.id,
        input.installationId,
        input.deviceId ?? null,
        input.paymentReference,
        plan.max_devices,
        now.toISOString(),
        expiresAt,
        now.toISOString()
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'vip',
      action: 'activate_vip_subscription',
      targetId: id,
      details: { planId: plan.id, installationId: input.installationId },
    });
    return this.db.prepare('SELECT * FROM vip_subscriptions WHERE id = ?').get(id);
  }

  revokeVipSubscription(subscriptionId: string, actor: { type: AuditActorType; id: string }) {
    const now = new Date().toISOString();
    this.db
      .prepare("UPDATE vip_subscriptions SET status = 'revoked', revoked_at = ? WHERE id = ?")
      .run(now, subscriptionId);
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'vip',
      action: 'revoke_vip_subscription',
      targetId: subscriptionId,
    });
  }
}
