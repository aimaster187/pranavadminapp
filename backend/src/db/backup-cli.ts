import { loadConfig } from '../config/env.js';
import { createVerifiedBackup, openPranavDatabase } from './database.js';

const cfg = loadConfig();
const db = openPranavDatabase(cfg.dbPath);
const result = createVerifiedBackup(db, cfg.backupDir, process.argv[2] ?? 'scheduled');
db.close();
console.log(JSON.stringify({ event: 'backup_complete', ...result }));
