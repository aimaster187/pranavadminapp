import Fastify, { FastifyInstance } from 'fastify';
import { DatabaseSync } from 'node:sqlite';
import { ZodError } from 'zod';
import { PranavConfig } from './config/env.js';
import { openPranavDatabase } from './db/database.js';
import { registerAdminRoutes } from './routes/adminRoutes.js';
import { registerClientRoutes } from './routes/clientRoutes.js';
import { registerTelegramRoutes } from './routes/telegramRoutes.js';
import { AppLicenseVipService } from './services/appLicenseVipService.js';
import { AuditService } from './services/auditService.js';
import { ClientSdkService } from './services/clientSdkService.js';
import { DeliveryControlService } from './services/deliveryControlService.js';
import { OpsMonitorService } from './services/opsMonitorService.js';
import { OwnerAuthService } from './services/ownerAuthService.js';
import {
  FetchTelegramTransport,
  TelegramApiTransport,
  TelegramBotService,
} from './services/telegramBotService.js';

export interface PranavRuntime {
  app: FastifyInstance;
  db: DatabaseSync;
  config: PranavConfig;
  services: {
    audit: AuditService;
    ownerAuth: OwnerAuthService;
    appLicenseVip: AppLicenseVipService;
    delivery: DeliveryControlService;
    ops: OpsMonitorService;
    clientSdk: ClientSdkService;
    telegram: TelegramBotService;
  };
  stopWorkers: () => void;
}

export function buildPranavApp(
  config: PranavConfig,
  options: {
    telegramTransport?: TelegramApiTransport;
    enableWorkers?: boolean;
    logger?: boolean;
  } = {}
): PranavRuntime {
  const db = openPranavDatabase(config.dbPath);

  const audit = new AuditService(db);
  const ownerAuth = new OwnerAuthService(db, config, audit);
  const appLicenseVip = new AppLicenseVipService(db, audit);
  const delivery = new DeliveryControlService(db, audit);
  const ops = new OpsMonitorService(db, config, audit);
  const clientSdk = new ClientSdkService(db, config, audit);
  const telegram = new TelegramBotService(
    db,
    config,
    audit,
    ownerAuth,
    appLicenseVip,
    delivery,
    ops,
    options.telegramTransport ?? new FetchTelegramTransport()
  );

  const app = Fastify({
    logger: options.logger ?? false,
    bodyLimit: 1048576,
    trustProxy: true,
  });

  // Global in-memory IP sliding-window rate limiter
  const ipHits = new Map<string, number[]>();
  app.addHook('onRequest', async (req, reply) => {
    if (req.url === '/healthz' || req.url === '/readyz') return;
    const ip = req.ip || 'unknown';
    const now = Date.now();
    const recent = (ipHits.get(ip) ?? []).filter((t) => now - t < 60_000);
    if (recent.length >= 240) {
      return reply.code(429).send({ error: 'Rate limit exceeded' });
    }
    recent.push(now);
    ipHits.set(ip, recent);
  });

  app.setErrorHandler((err, _req, reply) => {
    if (err instanceof ZodError) {
      return reply.code(400).send({ error: 'Validation error', issues: err.issues });
    }
    const statusCode = (err as { statusCode?: number }).statusCode ?? 500;
    return reply.code(statusCode).send({
      error: err instanceof Error ? err.message : 'Internal server error',
    });
  });

  app.get('/healthz', async () => {
    const health = ops.getBackendHealth();
    return { ok: health.status === 'healthy', status: health.status, uptimeSeconds: health.uptimeSeconds };
  });

  app.get('/readyz', async (_req, reply) => {
    const integrity = ops.runDatabaseIntegrityCheck();
    if (!integrity.ok) {
      return reply.code(503).send({ ok: false, integrity });
    }
    return { ok: true, walMode: integrity.walMode, tables: integrity.tableCount };
  });

  registerAdminRoutes(app, {
    ownerAuth,
    appLicenseVip,
    delivery,
    ops,
    telegram,
    audit,
  });
  registerClientRoutes(app, clientSdk);
  registerTelegramRoutes(app, telegram);

  let workerTimer: NodeJS.Timeout | null = null;
  if (options.enableWorkers) {
    workerTimer = setInterval(() => {
      try {
        ops.runDueScheduledJobs();
      } catch {
        // Worker loop isolates errors to avoid crashing process
      }
    }, 30_000);
  }

  return {
    app,
    db,
    config,
    services: { audit, ownerAuth, appLicenseVip, delivery, ops, clientSdk, telegram },
    stopWorkers: () => {
      if (workerTimer) clearInterval(workerTimer);
    },
  };
}
