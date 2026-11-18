import { DatabaseSync } from 'node:sqlite';
import { randomId } from '../security/crypto.js';
import { AuditActorType, AuditService } from './auditService.js';

export class DeliveryControlService {
  constructor(
    private readonly db: DatabaseSync,
    private readonly audit: AuditService
  ) {}

  // --- Module 7: Update Manager ---
  listUpdates(appId?: string) {
    if (appId) {
      return this.db
        .prepare('SELECT * FROM updates WHERE app_id = ? ORDER BY created_at DESC')
        .all(appId);
    }
    return this.db.prepare('SELECT * FROM updates ORDER BY created_at DESC LIMIT 100').all();
  }

  createUpdate(
    input: {
      appId: string;
      latestVersionName: string;
      latestVersionCode: number;
      minVersionCode: number;
      downloadUrl: string;
      whatsNew: string;
      updateMode: 'normal' | 'force';
      showLaterButton: boolean;
      rolloutPercentage: number;
      scheduledAt?: string;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('upd');
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO updates
         (id, app_id, latest_version_name, latest_version_code, min_version_code, download_url, whats_new, update_mode, show_later_button, rollout_percentage, scheduled_at, is_active, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`
      )
      .run(
        id,
        input.appId,
        input.latestVersionName,
        input.latestVersionCode,
        input.minVersionCode,
        input.downloadUrl,
        input.whatsNew,
        input.updateMode,
        input.showLaterButton ? 1 : 0,
        input.rolloutPercentage,
        input.scheduledAt ?? null,
        now,
        now
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'updates',
      action: 'create_update',
      targetId: id,
      details: { appId: input.appId, version: input.latestVersionName, mode: input.updateMode },
    });
    return this.db.prepare('SELECT * FROM updates WHERE id = ?').get(id);
  }

  getUpdateAnalytics(updateId: string) {
    return this.db
      .prepare(
        `SELECT action, count(*) AS total FROM update_confirmations WHERE update_id = ? GROUP BY action`
      )
      .all(updateId);
  }

  // --- Module 8: Universal Dialog Manager ---
  listDialogs(appId?: string) {
    if (appId) {
      return this.db
        .prepare('SELECT * FROM dialogs WHERE app_id = ? OR app_id IS NULL ORDER BY priority DESC, created_at DESC')
        .all(appId);
    }
    return this.db.prepare('SELECT * FROM dialogs ORDER BY priority DESC, created_at DESC').all();
  }

  createDialog(
    input: {
      appId?: string;
      dialogType:
        | 'custom_popup'
        | 'optional'
        | 'force'
        | 'maintenance'
        | 'update'
        | 'channel_join'
        | 'custom_toast'
        | 'announcement';
      title: string;
      description: string;
      imageUrl?: string;
      buttons?: Array<{ label: string; actionType: string; actionValue: string }>;
      targetRules?: Record<string, unknown>;
      priority?: number;
      scheduledAt?: string;
      expiresAt?: string;
      isEnabled?: boolean;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('dlg');
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO dialogs
         (id, app_id, dialog_type, title, description, image_url, buttons_json, target_rules_json, priority, scheduled_at, expires_at, is_enabled, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        id,
        input.appId ?? null,
        input.dialogType,
        input.title,
        input.description,
        input.imageUrl ?? '',
        JSON.stringify(input.buttons ?? []),
        JSON.stringify(input.targetRules ?? {}),
        input.priority ?? 50,
        input.scheduledAt ?? null,
        input.expiresAt ?? null,
        input.isEnabled === false ? 0 : 1,
        now,
        now
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'dialogs',
      action: 'create_dialog',
      targetId: id,
      details: { dialogType: input.dialogType, title: input.title },
    });
    return this.db.prepare('SELECT * FROM dialogs WHERE id = ?').get(id);
  }

  toggleDialog(dialogId: string, enabled: boolean, actor: { type: AuditActorType; id: string }) {
    this.db
      .prepare('UPDATE dialogs SET is_enabled = ?, updated_at = ? WHERE id = ?')
      .run(enabled ? 1 : 0, new Date().toISOString(), dialogId);
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'dialogs',
      action: enabled ? 'enable_dialog' : 'disable_dialog',
      targetId: dialogId,
    });
  }

  // --- Module 9: Announcement Manager ---
  listAnnouncements() {
    return this.db
      .prepare('SELECT * FROM announcements ORDER BY is_pinned DESC, priority DESC, created_at DESC')
      .all();
  }

  createAnnouncement(
    input: {
      appId?: string;
      title: string;
      body: string;
      actionLabel?: string;
      actionUrl?: string;
      priority?: number;
      isPinned?: boolean;
      scheduledAt?: string;
      expiresAt?: string;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('ann');
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO announcements
         (id, app_id, title, body, action_label, action_url, priority, is_pinned, target_rules_json, scheduled_at, expires_at, is_enabled, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, '{}', ?, ?, 1, ?, ?)`
      )
      .run(
        id,
        input.appId ?? null,
        input.title,
        input.body,
        input.actionLabel ?? '',
        input.actionUrl ?? '',
        input.priority ?? 50,
        input.isPinned ? 1 : 0,
        input.scheduledAt ?? null,
        input.expiresAt ?? null,
        now,
        now
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'announcements',
      action: 'create_announcement',
      targetId: id,
    });
    return this.db.prepare('SELECT * FROM announcements WHERE id = ?').get(id);
  }

  // --- Module 10: Remote Configuration Manager ---
  listRemoteConfigs(scope?: string, appId?: string) {
    if (scope && appId) {
      return this.db
        .prepare('SELECT * FROM remote_configs WHERE scope = ? AND app_id = ? ORDER BY version DESC')
        .all(scope, appId);
    }
    return this.db.prepare('SELECT * FROM remote_configs ORDER BY created_at DESC LIMIT 100').all();
  }

  publishRemoteConfig(
    input: {
      scope: 'global' | 'app' | 'installation';
      appId?: string;
      installationId?: string;
      featureFlags: Record<string, boolean>;
      remoteText: Record<string, string>;
      remoteLinks: Record<string, string>;
      dynamicSettings: Record<string, unknown>;
      targetRules?: Record<string, unknown>;
      scheduledAt?: string;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('cfg');
    const now = new Date().toISOString();

    const maxRow = this.db
      .prepare(
        `SELECT COALESCE(MAX(version), 0) AS max_ver FROM remote_configs
         WHERE scope = ? AND COALESCE(app_id, '') = ? AND COALESCE(installation_id, '') = ?`
      )
      .get(input.scope, input.appId ?? '', input.installationId ?? '') as { max_ver: number };
    const nextVersion = maxRow.max_ver + 1;

    this.db.exec('BEGIN IMMEDIATE;');
    try {
      this.db
        .prepare(
          `UPDATE remote_configs SET is_active = 0
           WHERE scope = ? AND COALESCE(app_id, '') = ? AND COALESCE(installation_id, '') = ?`
        )
        .run(input.scope, input.appId ?? '', input.installationId ?? '');

      this.db
        .prepare(
          `INSERT INTO remote_configs
           (id, scope, app_id, installation_id, version, feature_flags_json, remote_text_json, remote_links_json, dynamic_settings_json, target_rules_json, scheduled_at, is_active, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?)`
        )
        .run(
          id,
          input.scope,
          input.appId ?? null,
          input.installationId ?? null,
          nextVersion,
          JSON.stringify(input.featureFlags),
          JSON.stringify(input.remoteText),
          JSON.stringify(input.remoteLinks),
          JSON.stringify(input.dynamicSettings),
          JSON.stringify(input.targetRules ?? {}),
          input.scheduledAt ?? null,
          now
        );
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'remote_config',
      action: 'publish_remote_config',
      targetId: id,
      details: { scope: input.scope, version: nextVersion },
    });
    return this.db.prepare('SELECT * FROM remote_configs WHERE id = ?').get(id);
  }

  rollbackRemoteConfig(configId: string, actor: { type: AuditActorType; id: string }) {
    const target = this.db.prepare('SELECT * FROM remote_configs WHERE id = ?').get(configId) as
      | { scope: string; app_id: string | null; installation_id: string | null; version: number }
      | undefined;
    if (!target) throw new Error('Config version not found.');

    this.db.exec('BEGIN IMMEDIATE;');
    try {
      this.db
        .prepare(
          `UPDATE remote_configs SET is_active = 0
           WHERE scope = ? AND COALESCE(app_id, '') = ? AND COALESCE(installation_id, '') = ?`
        )
        .run(target.scope, target.app_id ?? '', target.installation_id ?? '');
      this.db.prepare('UPDATE remote_configs SET is_active = 1 WHERE id = ?').run(configId);
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'remote_config',
      action: 'rollback_remote_config',
      targetId: configId,
      details: { version: target.version },
    });
  }

  // --- Module 11: Declarative Dynamic Module Registry ---
  listDynamicModules() {
    return this.db.prepare('SELECT * FROM dynamic_modules ORDER BY created_at DESC').all();
  }

  upsertDynamicModule(
    input: {
      appId?: string;
      moduleKey: string;
      capabilityType:
        | 'feature_gate'
        | 'ui_banner'
        | 'dialog_trigger'
        | 'link_router'
        | 'telemetry_profile';
      version: string;
      config: Record<string, unknown>;
      targetRules?: Record<string, unknown>;
      isEnabled?: boolean;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('mod');
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO dynamic_modules
         (id, app_id, module_key, capability_type, version, config_json, target_rules_json, is_enabled, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        id,
        input.appId ?? null,
        input.moduleKey,
        input.capabilityType,
        input.version,
        JSON.stringify(input.config),
        JSON.stringify(input.targetRules ?? {}),
        input.isEnabled === false ? 0 : 1,
        now,
        now
      );

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'dynamic_modules',
      action: 'register_declarative_module',
      targetId: id,
      details: { moduleKey: input.moduleKey, capabilityType: input.capabilityType },
    });
    return this.db.prepare('SELECT * FROM dynamic_modules WHERE id = ?').get(id);
  }

  // --- Modules 12 & 13: Maintenance & Emergency Lockdown ---
  getMaintenanceState(appId?: string) {
    if (appId) {
      return (
        this.db.prepare("SELECT * FROM maintenance WHERE scope = 'app' AND app_id = ?").get(appId) ??
        this.db.prepare("SELECT * FROM maintenance WHERE id = 'maint_global'").get()
      );
    }
    return this.db.prepare('SELECT * FROM maintenance ORDER BY scope ASC').all();
  }

  setMaintenanceState(
    input: {
      scope: 'global' | 'app';
      appId?: string;
      isEnabled: boolean;
      emergencyLockdown: boolean;
      title: string;
      message: string;
      etaIso?: string;
      allowlistedInstallations?: string[];
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const now = new Date().toISOString();
    const id = input.scope === 'global' ? 'maint_global' : `maint_${input.appId}`;
    this.db
      .prepare(
        `INSERT INTO maintenance (id, scope, app_id, is_enabled, emergency_lockdown, title, message, eta_iso, allowlisted_installations_json, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
         ON CONFLICT(id) DO UPDATE SET
           is_enabled = excluded.is_enabled,
           emergency_lockdown = excluded.emergency_lockdown,
           title = excluded.title,
           message = excluded.message,
           eta_iso = excluded.eta_iso,
           allowlisted_installations_json = excluded.allowlisted_installations_json,
           updated_at = excluded.updated_at`
      )
      .run(
        id,
        input.scope,
        input.scope === 'app' ? input.appId! : null,
        input.isEnabled ? 1 : 0,
        input.emergencyLockdown ? 1 : 0,
        input.title,
        input.message,
        input.etaIso ?? null,
        JSON.stringify(input.allowlistedInstallations ?? []),
        now
      );

    if (input.emergencyLockdown) {
      if (input.scope === 'global') {
        this.db.prepare('UPDATE sdk_sessions SET revoked_at = ? WHERE revoked_at IS NULL').run(now);
      } else if (input.appId) {
        this.db
          .prepare('UPDATE sdk_sessions SET revoked_at = ? WHERE app_id = ? AND revoked_at IS NULL')
          .run(now, input.appId);
      }
    }

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: input.emergencyLockdown ? 'emergency_lockdown' : 'maintenance',
      action: input.emergencyLockdown ? 'toggle_emergency_lockdown' : 'update_maintenance',
      targetId: id,
      details: input,
    });

    return this.db.prepare('SELECT * FROM maintenance WHERE id = ?').get(id);
  }

  // --- Modules 15 & 16: DEX Release Manager & Hook Release Manager ---
  listDexReleases() {
    return this.db.prepare('SELECT * FROM dex_releases ORDER BY version_code DESC, created_at DESC').all();
  }

  registerDexRelease(
    input: {
      versionName: string;
      versionCode: number;
      sha256: string;
      sizeBytes: number;
      minSdk: number;
      targetSdk: number;
      compatibility: Record<string, unknown>;
      mtManagerNotes: string;
      activate?: boolean;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('dex');
    const now = new Date().toISOString();
    this.db.exec('BEGIN IMMEDIATE;');
    try {
      if (input.activate) {
        this.db.prepare('UPDATE dex_releases SET is_active = 0').run();
      }
      this.db
        .prepare(
          `INSERT INTO dex_releases
           (id, version_name, version_code, file_name, sha256, size_bytes, min_sdk, target_sdk, compatibility_json, mt_manager_notes, is_active, created_at)
           VALUES (?, ?, ?, 'PRANAV.dex', ?, ?, ?, ?, ?, ?, ?, ?)`
        )
        .run(
          id,
          input.versionName,
          input.versionCode,
          input.sha256,
          input.sizeBytes,
          input.minSdk,
          input.targetSdk,
          JSON.stringify(input.compatibility),
          input.mtManagerNotes,
          input.activate ? 1 : 0,
          now
        );
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'dex_releases',
      action: 'register_dex_release',
      targetId: id,
      details: { versionName: input.versionName, sha256: input.sha256 },
    });
    return this.db.prepare('SELECT * FROM dex_releases WHERE id = ?').get(id);
  }

  activateDexRelease(releaseId: string, actor: { type: AuditActorType; id: string }) {
    this.db.exec('BEGIN IMMEDIATE;');
    try {
      this.db.prepare('UPDATE dex_releases SET is_active = 0').run();
      this.db.prepare('UPDATE dex_releases SET is_active = 1 WHERE id = ?').run(releaseId);
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'dex_releases',
      action: 'activate_dex_release',
      targetId: releaseId,
    });
  }

  listHookReleases() {
    return this.db.prepare('SELECT * FROM hook_releases ORDER BY version_code DESC, created_at DESC').all();
  }

  registerHookRelease(
    input: {
      versionName: string;
      versionCode: number;
      sha256: string;
      minSdkVersion: string;
      compatibility: Record<string, unknown>;
      lifecycleHooks: string[];
      activate?: boolean;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('hook');
    const now = new Date().toISOString();
    this.db.exec('BEGIN IMMEDIATE;');
    try {
      if (input.activate) {
        this.db.prepare('UPDATE hook_releases SET is_active = 0').run();
      }
      this.db
        .prepare(
          `INSERT INTO hook_releases
           (id, version_name, version_code, package_name, sha256, min_sdk_version, compatibility_json, lifecycle_hooks_json, is_active, created_at)
           VALUES (?, ?, ?, 'pranav-hook', ?, ?, ?, ?, ?, ?)`
        )
        .run(
          id,
          input.versionName,
          input.versionCode,
          input.sha256,
          input.minSdkVersion,
          JSON.stringify(input.compatibility),
          JSON.stringify(input.lifecycleHooks),
          input.activate ? 1 : 0,
          now
        );
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'hook_releases',
      action: 'register_hook_release',
      targetId: id,
      details: { versionName: input.versionName, sha256: input.sha256 },
    });
    return this.db.prepare('SELECT * FROM hook_releases WHERE id = ?').get(id);
  }

  activateHookRelease(releaseId: string, actor: { type: AuditActorType; id: string }) {
    this.db.exec('BEGIN IMMEDIATE;');
    try {
      this.db.prepare('UPDATE hook_releases SET is_active = 0').run();
      this.db.prepare('UPDATE hook_releases SET is_active = 1 WHERE id = ?').run(releaseId);
      this.db.exec('COMMIT;');
    } catch (err) {
      this.db.exec('ROLLBACK;');
      throw err;
    }
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'hook_releases',
      action: 'activate_hook_release',
      targetId: releaseId,
    });
  }
}
