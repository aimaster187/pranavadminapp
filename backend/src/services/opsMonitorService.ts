import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { DatabaseSync } from 'node:sqlite';
import { PranavConfig } from '../config/env.js';
import {
  checkDatabaseIntegrity,
  createVerifiedBackup,
  restoreLiveDatabaseFromBackup,
  verifyBackupFile,
} from '../db/database.js';
import { randomId } from '../security/crypto.js';
import { AuditActorType, AuditService } from './auditService.js';

const ALLOWED_TABLES = new Set([
  'admins',
  'owner_devices',
  'apps',
  'installations',
  'devices',
  'licenses',
  'license_transfers',
  'vip_plans',
  'vip_subscriptions',
  'updates',
  'update_confirmations',
  'dialogs',
  'announcements',
  'remote_configs',
  'dynamic_modules',
  'dex_releases',
  'hook_releases',
  'sdk_sessions',
  'device_challenges',
  'telegram_updates',
  'telegram_bots',
  'events',
  'audit_logs',
  'system_settings',
  'maintenance',
  'config_schedules',
]);

export class OpsMonitorService {
  private readonly startedAtMs = Date.now();

  constructor(
    private readonly db: DatabaseSync,
    private readonly config: PranavConfig,
    private readonly audit: AuditService
  ) {}

  // --- Module 1: Dashboard & Analytics ---
  getDashboardSummary() {
    const count = (sql: string) =>
      (this.db.prepare(sql).get() as { cnt: number }).cnt;

    const totalApps = count('SELECT count(*) AS cnt FROM apps');
    const activeApps = count("SELECT count(*) AS cnt FROM apps WHERE status = 'active'");
    const totalInstallations = count('SELECT count(*) AS cnt FROM installations');
    const activeDevices = count("SELECT count(*) AS cnt FROM devices WHERE approval_status = 'approved'");
    const activeLicenses = count("SELECT count(*) AS cnt FROM licenses WHERE status = 'active'");
    const totalLicenses = count('SELECT count(*) AS cnt FROM licenses');
    const activeVip = count("SELECT count(*) AS cnt FROM vip_subscriptions WHERE status = 'active'");
    const activeBots = count("SELECT count(*) AS cnt FROM telegram_bots WHERE status = 'running'");
    const totalBots = count('SELECT count(*) AS cnt FROM telegram_bots');
    const activeDialogs = count('SELECT count(*) AS cnt FROM dialogs WHERE is_enabled = 1');
    const pendingJobs = count("SELECT count(*) AS cnt FROM config_schedules WHERE status = 'pending'");
    const globalMaint = this.db
      .prepare("SELECT is_enabled, emergency_lockdown FROM maintenance WHERE id = 'maint_global'")
      .get() as { is_enabled: number; emergency_lockdown: number } | undefined;

    // Real 7-day installation & event activity buckets for Compose Charts
    const activitySeries = this.db
      .prepare(
        `SELECT substr(created_at, 1, 10) AS day, count(*) AS events_count
         FROM events
         GROUP BY day
         ORDER BY day DESC
         LIMIT 7`
      )
      .all() as Array<{ day: string; events_count: number }>;

    const licenseBreakdown = this.db
      .prepare('SELECT status, count(*) AS total FROM licenses GROUP BY status')
      .all() as Array<{ status: string; total: number }>;

    return {
      kpis: {
        totalApps,
        activeApps,
        totalInstallations,
        activeDevices,
        activeLicenses,
        totalLicenses,
        activeVip,
        activeBots,
        totalBots,
        activeDialogs,
        pendingJobs,
        maintenanceEnabled: Boolean(globalMaint?.is_enabled),
        emergencyLockdown: Boolean(globalMaint?.emergency_lockdown),
      },
      activitySeries: activitySeries.reverse(),
      licenseBreakdown,
      recentAudit: this.audit.list(10),
    };
  }

  // --- Module 17: Database Explorer ---
  listTablesOverview() {
    const tables = Array.from(ALLOWED_TABLES).sort();
    return tables.map((table) => {
      const row = this.db.prepare(`SELECT count(*) AS cnt FROM ${table}`).get() as { cnt: number };
      return { table, rowCount: row.cnt };
    });
  }

  inspectTable(tableName: string, limit = 50, offset = 0) {
    if (!ALLOWED_TABLES.has(tableName)) {
      throw new Error(`Table '${tableName}' is not in allowlisted Database Explorer tables.`);
    }
    const safeLimit = Math.min(Math.max(limit, 1), 100);
    const safeOffset = Math.max(offset, 0);
    const columns = this.db.prepare(`PRAGMA table_info(${tableName})`).all();
    const rows = this.db
      .prepare(`SELECT * FROM ${tableName} LIMIT ? OFFSET ?`)
      .all(safeLimit, safeOffset);
    const total = (this.db.prepare(`SELECT count(*) AS cnt FROM ${tableName}`).get() as { cnt: number })
      .cnt;
    return { tableName, total, columns, rows };
  }

  runDatabaseIntegrityCheck() {
    return checkDatabaseIntegrity(this.db);
  }

  // --- Module 19: Security Center ---
  getSecurityOverview() {
    const ownerDevices = this.db
      .prepare(
        `SELECT id, device_label, hardware_model, key_fingerprint, status, enrolled_at, last_verified_at, revoked_at
         FROM owner_devices ORDER BY enrolled_at DESC`
      )
      .all();
    const recentChallenges = this.db
      .prepare('SELECT id, actor_type, actor_id, expires_at, consumed_at, created_at FROM device_challenges ORDER BY created_at DESC LIMIT 25')
      .all();
    const securityAlerts = this.db
      .prepare(
        `SELECT * FROM audit_logs
         WHERE actor_type = 'telegram_blocked' OR action LIKE '%invalid%' OR action LIKE '%rejected%' OR action LIKE '%lockdown%'
         ORDER BY created_at DESC LIMIT 30`
      )
      .all();
    return { ownerDevices, recentChallenges, securityAlerts };
  }

  // --- Module 20: Backup & Restore ---
  listBackups() {
    if (!fs.existsSync(this.config.backupDir)) return [];
    const files = fs
      .readdirSync(this.config.backupDir)
      .filter((f) => f.endsWith('.db'))
      .sort()
      .reverse();

    return files.map((fileName) => {
      const fullPath = path.join(this.config.backupDir, fileName);
      const stat = fs.statSync(fullPath);
      const metaPath = `${fullPath}.meta.json`;
      let meta: Record<string, unknown> = {};
      if (fs.existsSync(metaPath)) {
        try {
          meta = JSON.parse(fs.readFileSync(metaPath, 'utf8')) as Record<string, unknown>;
        } catch {
          meta = {};
        }
      }
      return {
        fileName,
        sizeBytes: stat.size,
        createdAt: (meta.createdAt as string) ?? stat.mtime.toISOString(),
        sha256: (meta.sha256 as string) ?? '',
      };
    });
  }

  triggerBackup(label: string, actor: { type: AuditActorType; id: string }) {
    const res = createVerifiedBackup(this.db, this.config.backupDir, label);
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'backup',
      action: 'create_verified_backup',
      targetId: res.fileName,
      details: { sha256: res.sha256, sizeBytes: res.sizeBytes },
    });
    return res;
  }

  verifyBackup(fileName: string) {
    const safeName = path.basename(fileName);
    return verifyBackupFile(path.join(this.config.backupDir, safeName));
  }

  restoreFromBackup(fileName: string, actor: { type: AuditActorType; id: string }) {
    const safeName = path.basename(fileName);
    const fullPath = path.join(this.config.backupDir, safeName);
    const res = restoreLiveDatabaseFromBackup(this.db, fullPath, this.config.backupDir);
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'backup',
      action: 'restore_verified_backup',
      targetId: safeName,
      details: res,
    });
    return res;
  }

  // --- Module 21: Backend Health ---
  getBackendHealth() {
    const mem = process.memoryUsage();
    const integrity = checkDatabaseIntegrity(this.db);
    let dbSizeBytes = 0;
    let walSizeBytes = 0;
    try {
      dbSizeBytes = fs.statSync(this.config.dbPath).size;
      const walPath = `${this.config.dbPath}-wal`;
      if (fs.existsSync(walPath)) walSizeBytes = fs.statSync(walPath).size;
    } catch {
      // ignore stat errors
    }

    return {
      status: integrity.ok ? 'healthy' : 'degraded',
      uptimeSeconds: Math.floor((Date.now() - this.startedAtMs) / 1000),
      nodeVersion: process.version,
      memory: {
        rssMb: Math.round(mem.rss / 1048576),
        heapUsedMb: Math.round(mem.heapUsed / 1048576),
        heapTotalMb: Math.round(mem.heapTotal / 1048576),
      },
      database: {
        ...integrity,
        dbSizeBytes,
        walSizeBytes,
      },
      timestamp: new Date().toISOString(),
    };
  }

  // --- Module 22: Server Monitoring ---
  getServerMonitoring() {
    const totalMem = os.totalmem();
    const freeMem = os.freemem();
    const cpus = os.cpus();
    const loadAvg = os.loadavg();

    let diskTotalBytes = 0;
    let diskFreeBytes = 0;
    try {
      const statfs = fs.statfsSync(path.dirname(this.config.dbPath));
      diskTotalBytes = statfs.blocks * statfs.bsize;
      diskFreeBytes = statfs.bavail * statfs.bsize;
    } catch {
      // ignore if unsupported
    }

    const probeService = (unit: string): string => {
      try {
        return execSync(`systemctl is-active ${unit} 2>/dev/null`, {
          encoding: 'utf8',
          timeout: 1000,
        }).trim();
      } catch {
        return 'not_systemd_managed';
      }
    };

    return {
      hostname: os.hostname(),
      platform: `${os.platform()} ${os.release()}`,
      arch: os.arch(),
      osUptimeSeconds: Math.floor(os.uptime()),
      cpuCores: cpus.length,
      cpuModel: cpus[0]?.model ?? 'unknown',
      loadAverage: loadAvg,
      memory: {
        totalMb: Math.round(totalMem / 1048576),
        freeMb: Math.round(freeMem / 1048576),
        usedPercent: Math.round(((totalMem - freeMem) / totalMem) * 100),
      },
      disk: {
        totalGb: Number((diskTotalBytes / 1073741824).toFixed(2)),
        freeGb: Number((diskFreeBytes / 1073741824).toFixed(2)),
      },
      services: {
        pranavBackend: probeService('pranav-backend'),
        nginx: probeService('nginx'),
      },
      timestamp: new Date().toISOString(),
    };
  }

  // --- Module 23: Configuration Scheduler ---
  listSchedules() {
    return this.db.prepare('SELECT * FROM config_schedules ORDER BY run_at DESC LIMIT 100').all();
  }

  createSchedule(
    input: {
      jobType: 'remote_config' | 'update' | 'dialog' | 'announcement' | 'maintenance' | 'backup';
      targetId: string;
      payload?: Record<string, unknown>;
      runAt: string;
    },
    actor: { type: AuditActorType; id: string }
  ) {
    const id = randomId('sch');
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO config_schedules (id, job_type, target_id, payload_json, run_at, status, created_at)
         VALUES (?, ?, ?, ?, ?, 'pending', ?)`
      )
      .run(id, input.jobType, input.targetId, JSON.stringify(input.payload ?? {}), input.runAt, now);

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'scheduler',
      action: 'create_schedule',
      targetId: id,
      details: input,
    });
    return this.db.prepare('SELECT * FROM config_schedules WHERE id = ?').get(id);
  }

  cancelSchedule(scheduleId: string, actor: { type: AuditActorType; id: string }) {
    this.db
      .prepare("UPDATE config_schedules SET status = 'cancelled' WHERE id = ? AND status = 'pending'")
      .run(scheduleId);
    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'scheduler',
      action: 'cancel_schedule',
      targetId: scheduleId,
    });
  }

  runDueScheduledJobs(): number {
    const now = new Date().toISOString();
    const due = this.db
      .prepare("SELECT * FROM config_schedules WHERE status = 'pending' AND run_at <= ?")
      .all(now) as Array<{ id: string; job_type: string; target_id: string; payload_json: string }>;

    let executedCount = 0;
    for (const job of due) {
      try {
        const payload = JSON.parse(job.payload_json) as Record<string, unknown>;
        if (job.job_type === 'backup') {
          createVerifiedBackup(this.db, this.config.backupDir, 'scheduled_job');
        } else if (job.job_type === 'dialog') {
          this.db
            .prepare('UPDATE dialogs SET is_enabled = ?, updated_at = ? WHERE id = ?')
            .run(payload.enable === false ? 0 : 1, now, job.target_id);
        } else if (job.job_type === 'announcement') {
          this.db
            .prepare('UPDATE announcements SET is_enabled = ?, updated_at = ? WHERE id = ?')
            .run(payload.enable === false ? 0 : 1, now, job.target_id);
        } else if (job.job_type === 'update') {
          this.db
            .prepare('UPDATE updates SET is_active = ?, updated_at = ? WHERE id = ?')
            .run(payload.enable === false ? 0 : 1, now, job.target_id);
        } else if (job.job_type === 'maintenance') {
          this.db
            .prepare('UPDATE maintenance SET is_enabled = ?, updated_at = ? WHERE id = ?')
            .run(payload.enable === false ? 0 : 1, now, job.target_id);
        } else if (job.job_type === 'remote_config') {
          this.db.prepare('UPDATE remote_configs SET is_active = 1 WHERE id = ?').run(job.target_id);
        }

        this.db
          .prepare("UPDATE config_schedules SET status = 'executed', executed_at = ? WHERE id = ?")
          .run(now, job.id);
        this.audit.log({
          actorType: 'worker',
          actorId: 'scheduler_worker',
          module: 'scheduler',
          action: 'execute_scheduled_job',
          targetId: job.id,
          details: { jobType: job.job_type, targetId: job.target_id },
        });
        executedCount++;
      } catch (err) {
        this.db
          .prepare("UPDATE config_schedules SET status = 'failed', error_message = ? WHERE id = ?")
          .run(err instanceof Error ? err.message : String(err), job.id);
      }
    }
    return executedCount;
  }

  // --- Module 24: System Settings ---
  listSystemSettings() {
    return this.db.prepare('SELECT * FROM system_settings ORDER BY key ASC').all();
  }

  updateSystemSetting(
    key: string,
    value: unknown,
    description: string | undefined,
    actor: { type: AuditActorType; id: string }
  ) {
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO system_settings (key, value_json, description, updated_at)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(key) DO UPDATE SET
           value_json = excluded.value_json,
           description = CASE WHEN excluded.description = '' THEN system_settings.description ELSE excluded.description END,
           updated_at = excluded.updated_at`
      )
      .run(key, JSON.stringify(value), description ?? '', now);

    this.audit.log({
      actorType: actor.type,
      actorId: actor.id,
      module: 'system_settings',
      action: 'update_setting',
      targetId: key,
      details: { value },
    });
    return this.db.prepare('SELECT * FROM system_settings WHERE key = ?').get(key);
  }
}
