import { loadConfig } from '../config/env.js';
import { checkDatabaseIntegrity, openPranavDatabase } from './database.js';

const cfg = loadConfig();
const db = openPranavDatabase(cfg.dbPath);
const status = checkDatabaseIntegrity(db);
db.close();
console.log(JSON.stringify({ event: 'migrate_complete', dbPath: cfg.dbPath, ...status }));
