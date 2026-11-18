import { DatabaseSync } from 'node:sqlite';
import { randomId } from '../security/crypto.js';

export type AuditActorType =
  | 'owner_app'
  | 'telegram_owner'
  | 'telegram_blocked'
  | 'client_sdk'
  | 'system'
  | 'worker';

export interface AuditEntryInput {
  actorType: AuditActorType;
  actorId: string;
  module: string;
  action: string;
  targetId?: string;
  details?: Record<string, unknown>;
}

export class AuditService {
  constructor(private readonly db: DatabaseSync) {}

  log(entry: AuditEntryInput): string {
    const id = randomId('aud');
    const now = new Date().toISOString();
    this.db
      .prepare(
        `INSERT INTO audit_logs (id, actor_type, actor_id, module, action, target_id, details_json, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        id,
        entry.actorType,
        entry.actorId,
        entry.module,
        entry.action,
        entry.targetId ?? '',
        JSON.stringify(entry.details ?? {}),
        now
      );
    return id;
  }

  list(limit = 100, moduleFilter?: string, actorFilter?: string) {
    const clauses: string[] = [];
    const params: Array<string | number> = [];
    if (moduleFilter) {
      clauses.push('module = ?');
      params.push(moduleFilter);
    }
    if (actorFilter) {
      clauses.push('actor_type = ?');
      params.push(actorFilter);
    }
    const where = clauses.length > 0 ? `WHERE ${clauses.join(' AND ')}` : '';
    params.push(Math.min(Math.max(limit, 1), 500));
    return this.db
      .prepare(`SELECT * FROM audit_logs ${where} ORDER BY created_at DESC LIMIT ?`)
      .all(...params);
  }
}
