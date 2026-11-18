import { DatabaseSync } from 'node:sqlite';
import { PranavConfig } from '../config/env.js';
import {
  randomId,
  randomTokenHex,
  sha256Hex,
  verifyEcdsaSignature,
} from '../security/crypto.js';
import { AuditService } from './auditService.js';

export interface AuthenticatedOwner {
  sessionId: string;
  ownerDeviceId: string;
  deviceLabel: string;
}

export class OwnerAuthService {
  constructor(
    private readonly db: DatabaseSync,
    private readonly config: PranavConfig,
    private readonly audit: AuditService
  ) {}

  hasActiveOwnerDevice(): boolean {
    const row = this.db
      .prepare("SELECT count(*) AS cnt FROM owner_devices WHERE status = 'active'")
      .get() as { cnt: number };
    return row.cnt > 0;
  }

  issueEnrollmentToken(
    purpose: 'bootstrap' | 'recovery',
    issuedBy: string,
    ttlMinutes = 10
  ): { token: string; expiresAt: string } {
    if (purpose === 'bootstrap' && this.hasActiveOwnerDevice()) {
      throw new Error('Bootstrap token cannot be issued once an active owner device exists; use recovery.');
    }
    const token = `PRN-${purpose.toUpperCase()}-${randomTokenHex(12).toUpperCase()}`;
    const now = new Date();
    const expiresAt = new Date(now.getTime() + ttlMinutes * 60_000).toISOString();
    this.db
      .prepare(
        `INSERT INTO owner_enrollment_tokens (id, token_hash, purpose, issued_by, expires_at, created_at)
         VALUES (?, ?, ?, ?, ?, ?)`
      )
      .run(randomId('enr'), sha256Hex(token), purpose, issuedBy, expiresAt, now.toISOString());

    this.audit.log({
      actorType: issuedBy.startsWith('tg:') ? 'telegram_owner' : 'system',
      actorId: issuedBy,
      module: 'security',
      action: `issue_${purpose}_token`,
      details: { expiresAt },
    });

    return { token, expiresAt };
  }

  enrollOwnerDevice(input: {
    enrollmentToken: string;
    deviceLabel: string;
    hardwareModel: string;
    publicKeyPem: string;
  }): { ownerDeviceId: string; sessionToken: string; expiresAt: string; keyFingerprint: string } {
    const tokenHash = sha256Hex(input.enrollmentToken.trim());
    const nowIso = new Date().toISOString();

    const tokenRow = this.db
      .prepare(
        `SELECT * FROM owner_enrollment_tokens
         WHERE token_hash = ? AND consumed_at IS NULL AND expires_at > ?`
      )
      .get(tokenHash, nowIso) as
      | { id: string; purpose: string }
      | undefined;

    if (!tokenRow) {
      this.audit.log({
        actorType: 'system',
        actorId: 'unknown_device',
        module: 'security',
        action: 'owner_enroll_rejected',
        details: { reason: 'invalid_or_expired_token' },
      });
      throw new Error('Invalid or expired owner enrollment token.');
    }

    const fingerprint = sha256Hex(input.publicKeyPem.trim());
    const deviceId = randomId('owd');

    this.db.exec('BEGIN IMMEDIATE;');
    try {
      // Allow re-enrollment if same hardware key exists previously revoked or fresh device
      this.db
        .prepare('DELETE FROM owner_devices WHERE key_fingerprint = ?')
        .run(fingerprint);

      this.db
        .prepare(
          `INSERT INTO owner_devices
           (id, admin_id, device_label, hardware_model, public_key_pem, key_fingerprint, status, enrolled_at, last_verified_at)
           VALUES (?, 'owner_primary', ?, ?, ?, ?, 'active', ?, ?)`
        )
        .run(
          deviceId,
          input.deviceLabel,
          input.hardwareModel,
          input.publicKeyPem.trim(),
          fingerprint,
          nowIso,
          nowIso
        );

      this.db
        .prepare(
          `UPDATE owner_enrollment_tokens
           SET consumed_at = ?, consumed_by_device_id = ?
           WHERE id = ?`
        )
        .run(nowIso, deviceId, tokenRow.id);

      const session = this.createSessionRecord(deviceId);
      this.db.exec('COMMIT;');

      this.audit.log({
        actorType: 'owner_app',
        actorId: deviceId,
        module: 'security',
        action: 'owner_device_enrolled',
        targetId: deviceId,
        details: { purpose: tokenRow.purpose, deviceLabel: input.deviceLabel, fingerprint },
      });

      return {
        ownerDeviceId: deviceId,
        sessionToken: session.sessionToken,
        expiresAt: session.expiresAt,
        keyFingerprint: fingerprint,
      };
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }
  }

  createChallenge(ownerDeviceId: string): { challengeId: string; nonce: string; expiresAt: string } {
    const device = this.db
      .prepare("SELECT id, status FROM owner_devices WHERE id = ? AND status = 'active'")
      .get(ownerDeviceId) as { id: string; status: string } | undefined;

    if (!device) {
      throw new Error('Owner device is not enrolled or has been revoked.');
    }

    const challengeId = randomId('chl');
    const nonce = randomTokenHex(32);
    const now = new Date();
    const expiresAt = new Date(now.getTime() + this.config.challengeTtlSeconds * 1000).toISOString();

    this.db
      .prepare(
        `INSERT INTO device_challenges (id, actor_type, actor_id, nonce, expires_at, created_at)
         VALUES (?, 'owner', ?, ?, ?, ?)`
      )
      .run(challengeId, ownerDeviceId, nonce, expiresAt, now.toISOString());

    return { challengeId, nonce, expiresAt };
  }

  verifyChallenge(input: {
    ownerDeviceId: string;
    challengeId: string;
    timestamp: string;
    signatureBase64: string;
  }): { sessionToken: string; expiresAt: string; ownerDeviceId: string } {
    const nowIso = new Date().toISOString();
    const device = this.db
      .prepare("SELECT * FROM owner_devices WHERE id = ? AND status = 'active'")
      .get(input.ownerDeviceId) as
      | { id: string; public_key_pem: string; device_label: string }
      | undefined;

    if (!device) {
      throw new Error('Owner device not active.');
    }

    const challenge = this.db
      .prepare(
        `SELECT * FROM device_challenges
         WHERE id = ? AND actor_type = 'owner' AND actor_id = ?
           AND consumed_at IS NULL AND expires_at > ?`
      )
      .get(input.challengeId, input.ownerDeviceId, nowIso) as
      | { id: string; nonce: string }
      | undefined;

    if (!challenge) {
      this.audit.log({
        actorType: 'system',
        actorId: input.ownerDeviceId,
        module: 'security',
        action: 'owner_challenge_failed',
        details: { reason: 'challenge_invalid_or_expired' },
      });
      throw new Error('Challenge invalid or expired.');
    }

    const canonicalPayload = `${challenge.nonce}:${challenge.id}:${input.ownerDeviceId}:${input.timestamp}`;
    const valid = verifyEcdsaSignature(
      device.public_key_pem,
      canonicalPayload,
      input.signatureBase64
    );

    if (!valid) {
      this.audit.log({
        actorType: 'system',
        actorId: input.ownerDeviceId,
        module: 'security',
        action: 'owner_signature_invalid',
        targetId: input.ownerDeviceId,
      });
      throw new Error('Cryptographic signature verification failed.');
    }

    this.db.exec('BEGIN IMMEDIATE;');
    try {
      this.db
        .prepare('UPDATE device_challenges SET consumed_at = ? WHERE id = ?')
        .run(nowIso, challenge.id);
      this.db
        .prepare('UPDATE owner_devices SET last_verified_at = ? WHERE id = ?')
        .run(nowIso, input.ownerDeviceId);
      const session = this.createSessionRecord(input.ownerDeviceId);
      this.db.exec('COMMIT;');

      this.audit.log({
        actorType: 'owner_app',
        actorId: input.ownerDeviceId,
        module: 'security',
        action: 'owner_silent_auth_success',
        targetId: input.ownerDeviceId,
      });

      return {
        sessionToken: session.sessionToken,
        expiresAt: session.expiresAt,
        ownerDeviceId: input.ownerDeviceId,
      };
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }
  }

  authenticateBearerSession(token: string): AuthenticatedOwner | null {
    if (!token) return null;
    const tokenHash = sha256Hex(token.trim());
    const nowIso = new Date().toISOString();
    const row = this.db
      .prepare(
        `SELECT s.id AS session_id, s.owner_device_id, d.device_label
         FROM owner_sessions s
         JOIN owner_devices d ON d.id = s.owner_device_id
         WHERE s.token_hash = ?
           AND s.revoked_at IS NULL
           AND s.expires_at > ?
           AND d.status = 'active'`
      )
      .get(tokenHash, nowIso) as
      | { session_id: string; owner_device_id: string; device_label: string }
      | undefined;

    if (!row) return null;

    this.db
      .prepare('UPDATE owner_sessions SET last_used_at = ? WHERE id = ?')
      .run(nowIso, row.session_id);

    return {
      sessionId: row.session_id,
      ownerDeviceId: row.owner_device_id,
      deviceLabel: row.device_label,
    };
  }

  revokeOwnerDevice(ownerDeviceId: string, actorId: string): void {
    const nowIso = new Date().toISOString();
    this.db.exec('BEGIN IMMEDIATE;');
    try {
      this.db
        .prepare("UPDATE owner_devices SET status = 'revoked', revoked_at = ? WHERE id = ?")
        .run(nowIso, ownerDeviceId);
      this.db
        .prepare('UPDATE owner_sessions SET revoked_at = ? WHERE owner_device_id = ? AND revoked_at IS NULL')
        .run(nowIso, ownerDeviceId);
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }

    this.audit.log({
      actorType: actorId.startsWith('tg:') ? 'telegram_owner' : 'owner_app',
      actorId,
      module: 'security',
      action: 'owner_device_revoked',
      targetId: ownerDeviceId,
    });
  }

  revokeSession(sessionId: string, actorId: string): void {
    const nowIso = new Date().toISOString();
    this.db
      .prepare('UPDATE owner_sessions SET revoked_at = ? WHERE id = ?')
      .run(nowIso, sessionId);
    this.audit.log({
      actorType: actorId.startsWith('tg:') ? 'telegram_owner' : 'owner_app',
      actorId,
      module: 'security',
      action: 'owner_session_revoked',
      targetId: sessionId,
    });
  }

  listOwnerDevices() {
    return this.db
      .prepare(
        `SELECT id, admin_id, device_label, hardware_model, key_fingerprint, status, enrolled_at, last_verified_at, revoked_at
         FROM owner_devices ORDER BY enrolled_at DESC`
      )
      .all();
  }

  listActiveSessions() {
    return this.db
      .prepare(
        `SELECT id, owner_device_id, expires_at, revoked_at, created_at, last_used_at
         FROM owner_sessions ORDER BY created_at DESC LIMIT 50`
      )
      .all();
  }

  private createSessionRecord(ownerDeviceId: string): { sessionToken: string; expiresAt: string } {
    const sessionToken = `prn_adm_${randomTokenHex(32)}`;
    const now = new Date();
    const expiresAt = new Date(
      now.getTime() + this.config.adminSessionTtlSeconds * 1000
    ).toISOString();

    this.db
      .prepare(
        `INSERT INTO owner_sessions (id, owner_device_id, token_hash, expires_at, created_at, last_used_at)
         VALUES (?, ?, ?, ?, ?, ?)`
      )
      .run(
        randomId('ses'),
        ownerDeviceId,
        sha256Hex(sessionToken),
        expiresAt,
        now.toISOString(),
        now.toISOString()
      );
    return { sessionToken, expiresAt };
  }
}
