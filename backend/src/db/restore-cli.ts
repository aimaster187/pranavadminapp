import path from 'node:path';
import { loadConfig } from '../config/env.js';
import { restoreDatabaseFromBackup } from './database.js';

const backupArg = process.argv[2];
if (!backupArg) {
  console.error('Usage: npm run restore -- <backup-filename-or-path>');
  process.exit(1);
}

const cfg = loadConfig();
const backupPath = path.isAbsolute(backupArg) ? backupArg : path.join(cfg.backupDir, backupArg);
const result = restoreDatabaseFromBackup(cfg.dbPath, backupPath, cfg.backupDir);
console.log(JSON.stringify({ event: 'restore_complete', ...result }));
