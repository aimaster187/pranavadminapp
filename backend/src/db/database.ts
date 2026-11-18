import fs from 'node:fs';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { MIGRATION_001_ID, MIGRATION_001_SQL } from './migrations/001_initial.js';
import { sha256Hex } from '../security/crypto.js';

export class CorruptedDatabaseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CorruptedDatabaseError';
  }
}

export interface BackupResult {
  fileName: string;
  filePath: string;
  sha256: string;
  sizeBytes: number;
  integrityOk: boolean;
  createdAt: string;
}

export function openPranavDatabase(dbPath: string): DatabaseSync {
  fs.mkdirSync(path.dirname(dbPath), { recursive: true });
  const db = new DatabaseSync(dbPath);

  // Check integrity BEFORE applying migrations so corrupted production DB is never silently replaced.
  try {
    const integrityRows = db.prepare('PRAGMA integrity_check;').all() as Array<{
      integrity_check: string;
    }>;
    const integrityResult = integrityRows.map((r) => r.integrity_check).join('; ');
    if (integrityResult.toLowerCase() !== 'ok') {
      throw new CorruptedDatabaseError(
        `SQLite integrity_check failed for ${dbPath}: ${integrityResult}`
      );
    }
  } catch (err) {
    db.close();
    if (err instanceof CorruptedDatabaseError) throw err;
    throw new CorruptedDatabaseError(
      `SQLite file unreadable or corrupted (${dbPath}): ${err instanceof Error ? err.message : String(err)}`
    );
  }

  db.exec('PRAGMA journal_mode = WAL;');
  db.exec('PRAGMA synchronous = NORMAL;');
  db.exec('PRAGMA foreign_keys = ON;');
  db.exec('PRAGMA busy_timeout = 5000;');

  applyMigrations(db);
  return db;
}

function applyMigrations(db: DatabaseSync): void {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      id TEXT PRIMARY KEY,
      applied_at TEXT NOT NULL
    );
  `);

  const applied = db
    .prepare('SELECT id FROM schema_migrations WHERE id = ?')
    .get(MIGRATION_001_ID) as { id: string } | undefined;

  if (!applied) {
    const now = new Date().toISOString();
    db.exec('BEGIN IMMEDIATE;');
    try {
      db.exec(MIGRATION_001_SQL);
      db.prepare('INSERT INTO schema_migrations (id, applied_at) VALUES (?, ?)').run(
        MIGRATION_001_ID,
        now
      );

      db.prepare(
        `INSERT OR IGNORE INTO admins (id, owner_name, owner_telegram_id, recovery_enabled, created_at, updated_at)
         VALUES ('owner_primary', 'PRANAV Owner', '', 1, ?, ?)`
      ).run(now, now);

      db.prepare(
        `INSERT OR IGNORE INTO maintenance (id, scope, app_id, is_enabled, emergency_lockdown, title, message, allowlisted_installations_json, updated_at)
         VALUES ('maint_global', 'global', NULL, 0, 0, 'System Maintenance', 'PRANAV services are undergoing scheduled maintenance.', '[]', ?)`
      ).run(now);

      const defaultSettings: Array<[string, unknown, string]> = [
        ['challenge_ttl_seconds', 45, 'Cryptographic challenge expiry window'],
        ['admin_session_ttl_seconds', 86400, 'Admin device session lifetime'],
        ['sdk_session_ttl_seconds', 43200, 'Client SDK session lifetime'],
        ['default_max_transfers', 2, 'Default device transfer allowance per license'],
        ['default_transfer_cooldown_seconds', 86400, 'Default license transfer cooldown'],
        ['rate_limit_max_per_minute', 120, 'Default per-IP API rate limit'],
        ['backup_retention_count', 14, 'Number of verified backups retained on disk'],
      ];
      const insertSetting = db.prepare(
        `INSERT OR IGNORE INTO system_settings (key, value_json, description, updated_at)
         VALUES (?, ?, ?, ?)`
      );
      for (const [k, v, desc] of defaultSettings) {
        insertSetting.run(k, JSON.stringify(v), desc, now);
      }

      db.exec('COMMIT;');
    } catch (err) {
      db.exec('ROLLBACK;');
      throw err;
    }
  }
}

export function checkDatabaseIntegrity(db: DatabaseSync): {
  ok: boolean;
  integrity: string;
  walMode: string;
  tableCount: number;
} {
  const integrityRow = db.prepare('PRAGMA integrity_check;').get() as { integrity_check: string };
  const journalRow = db.prepare('PRAGMA journal_mode;').get() as { journal_mode: string };
  const countRow = db
    .prepare("SELECT count(*) AS cnt FROM sqlite_master WHERE type = 'table'")
    .get() as { cnt: number };

  return {
    ok: integrityRow.integrity_check.toLowerCase() === 'ok',
    integrity: integrityRow.integrity_check,
    walMode: journalRow.journal_mode,
    tableCount: countRow.cnt,
  };
}

export function createVerifiedBackup(
  db: DatabaseSync,
  backupDir: string,
  label = 'manual'
): BackupResult {
  fs.mkdirSync(backupDir, { recursive: true });
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const safeLabel = label.replace(/[^a-zA-Z0-9_-]/g, '_');
  const fileName = `pranav-${safeLabel}-${stamp}.db`;
  const filePath = path.join(backupDir, fileName);

  // Hot atomic SQLite snapshot using VACUUM INTO
  const escapedPath = filePath.replace(/'/g, "''");
  db.exec(`VACUUM INTO '${escapedPath}';`);

  const verification = verifyBackupFile(filePath);
  if (!verification.ok) {
    throw new Error(`Backup verification failed for ${filePath}: ${verification.error}`);
  }

  const stat = fs.statSync(filePath);
  const sha256 = sha256Hex(fs.readFileSync(filePath));
  const createdAt = new Date().toISOString();

  fs.writeFileSync(
    `${filePath}.meta.json`,
    JSON.stringify({ fileName, sha256, sizeBytes: stat.size, createdAt, label: safeLabel }, null, 2)
  );

  return {
    fileName,
    filePath,
    sha256,
    sizeBytes: stat.size,
    integrityOk: true,
    createdAt,
  };
}

export function verifyBackupFile(backupFilePath: string): {
  ok: boolean;
  error?: string;
  tableCount?: number;
} {
  if (!fs.existsSync(backupFilePath)) {
    return { ok: false, error: 'Backup file does not exist.' };
  }
  const metaPath = `${backupFilePath}.meta.json`;
  if (fs.existsSync(metaPath)) {
    const meta = JSON.parse(fs.readFileSync(metaPath, 'utf8')) as { sha256?: string };
    const actualSha = sha256Hex(fs.readFileSync(backupFilePath));
    if (meta.sha256 && meta.sha256 !== actualSha) {
      return { ok: false, error: 'Backup SHA-256 checksum mismatch.' };
    }
  }

  let snap: DatabaseSync | null = null;
  try {
    snap = new DatabaseSync(backupFilePath, { readOnly: true });
    const row = snap.prepare('PRAGMA integrity_check;').get() as { integrity_check: string };
    if (row.integrity_check.toLowerCase() !== 'ok') {
      return { ok: false, error: `Integrity check failed: ${row.integrity_check}` };
    }
    const tables = snap
      .prepare("SELECT name FROM sqlite_master WHERE type = 'table'")
      .all() as Array<{ name: string }>;
    const names = new Set(tables.map((t) => t.name));
    for (const required of ['admins', 'apps', 'licenses', 'telegram_bots', 'audit_logs']) {
      if (!names.has(required)) {
        return { ok: false, error: `Missing core table '${required}' in backup.` };
      }
    }
    return { ok: true, tableCount: tables.length };
  } catch (err) {
    return { ok: false, error: err instanceof Error ? err.message : String(err) };
  } finally {
    snap?.close();
  }
}

export function restoreLiveDatabaseFromBackup(
  liveDb: DatabaseSync,
  backupFilePath: string,
  backupDir: string
): { safetySnapshot: string; restoredFrom: string } {
  const verify = verifyBackupFile(backupFilePath);
  if (!verify.ok) {
    throw new Error(`Refusing restore: backup verification failed (${verify.error})`);
  }

  const safety = createVerifiedBackup(liveDb, backupDir, 'pre_restore_safety');
  const escapedPath = backupFilePath.replace(/'/g, "''");

  liveDb.exec('PRAGMA foreign_keys = OFF;');
  liveDb.exec(`ATTACH DATABASE '${escapedPath}' AS restore_src;`);
  try {
    const tables = liveDb
      .prepare(
        "SELECT name FROM restore_src.sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"
      )
      .all() as Array<{ name: string }>;

    liveDb.exec('BEGIN IMMEDIATE;');
    try {
      for (const { name } of tables) {
        const safeName = name.replace(/"/g, '""');
        liveDb.exec(`DELETE FROM main."${safeName}";`);
        liveDb.exec(`INSERT INTO main."${safeName}" SELECT * FROM restore_src."${safeName}";`);
      }
      liveDb.exec('COMMIT;');
    } catch (err) {
      liveDb.exec('ROLLBACK;');
      throw err;
    }
  } finally {
    liveDb.exec('DETACH DATABASE restore_src;');
    liveDb.exec('PRAGMA foreign_keys = ON;');
  }

  const check = checkDatabaseIntegrity(liveDb);
  if (!check.ok) {
    throw new Error(`Post-restore integrity check failed: ${check.integrity}`);
  }

  return { safetySnapshot: safety.fileName, restoredFrom: path.basename(backupFilePath) };
}

export function restoreDatabaseFromBackup(
  liveDbPath: string,
  backupFilePath: string,
  backupDir: string
): { safetySnapshot: string; restoredFrom: string } {
  const verify = verifyBackupFile(backupFilePath);
  if (!verify.ok) {
    throw new Error(`Refusing restore: backup verification failed (${verify.error})`);
  }

  let safetySnapshot = '';
  if (fs.existsSync(liveDbPath)) {
    try {
      const liveDb = openPranavDatabase(liveDbPath);
      const snap = createVerifiedBackup(liveDb, backupDir, 'pre_restore_safety');
      safetySnapshot = snap.fileName;
      liveDb.close();
    } catch {
      const rawCopy = path.join(backupDir, `corrupted-pre-restore-${Date.now()}.db`);
      fs.mkdirSync(backupDir, { recursive: true });
      fs.copyFileSync(liveDbPath, rawCopy);
      safetySnapshot = path.basename(rawCopy);
    }
  }

  for (const suffix of ['-wal', '-shm']) {
    const aux = `${liveDbPath}${suffix}`;
    if (fs.existsSync(aux)) fs.unlinkSync(aux);
  }
  fs.copyFileSync(backupFilePath, liveDbPath);

  const reopened = openPranavDatabase(liveDbPath);
  const check = checkDatabaseIntegrity(reopened);
  reopened.close();
  if (!check.ok) {
    throw new Error(`Post-restore integrity check failed: ${check.integrity}`);
  }

  return { safetySnapshot, restoredFrom: path.basename(backupFilePath) };
}
