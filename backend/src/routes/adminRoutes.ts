import { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { AppLicenseVipService } from '../services/appLicenseVipService.js';
import { AuditService } from '../services/auditService.js';
import { DeliveryControlService } from '../services/deliveryControlService.js';
import { OpsMonitorService } from '../services/opsMonitorService.js';
import { OwnerAuthService } from '../services/ownerAuthService.js';
import { TelegramBotService } from '../services/telegramBotService.js';

export interface AdminRouteDeps {
  ownerAuth: OwnerAuthService;
  appLicenseVip: AppLicenseVipService;
  delivery: DeliveryControlService;
  ops: OpsMonitorService;
  telegram: TelegramBotService;
  audit: AuditService;
}

export function registerAdminRoutes(app: FastifyInstance, deps: AdminRouteDeps): void {
  // --- Public Auth Endpoints (Enrollment / Silent Keystore Challenge Only — Zero Login Form) ---
  app.get('/api/v1/admin/auth/status', async () => {
    return {
      enrolledDevicesCount: deps.ownerAuth.listOwnerDevices().filter((d: any) => d.status === 'active').length,
      hasActiveOwnerDevice: deps.ownerAuth.hasActiveOwnerDevice(),
    };
  });

  app.post('/api/v1/admin/auth/enroll', async (req, reply) => {
    const schema = z.object({
      enrollmentToken: z.string().min(8),
      deviceLabel: z.string().min(1).max(80),
      hardwareModel: z.string().max(80).default('Android'),
      publicKeyPem: z.string().min(32),
    });
    const body = schema.parse(req.body);
    try {
      return deps.ownerAuth.enrollOwnerDevice(body);
    } catch (err) {
      return reply.code(403).send({ error: err instanceof Error ? err.message : 'Enrollment rejected' });
    }
  });

  app.post('/api/v1/admin/auth/challenge', async (req, reply) => {
    const body = z.object({ ownerDeviceId: z.string().min(4) }).parse(req.body);
    try {
      return deps.ownerAuth.createChallenge(body.ownerDeviceId);
    } catch (err) {
      return reply.code(403).send({ error: err instanceof Error ? err.message : 'Challenge denied' });
    }
  });

  app.post('/api/v1/admin/auth/verify', async (req, reply) => {
    const body = z
      .object({
        ownerDeviceId: z.string().min(4),
        challengeId: z.string().min(4),
        timestamp: z.string().min(8),
        signatureBase64: z.string().min(16),
      })
      .parse(req.body);
    try {
      return deps.ownerAuth.verifyChallenge(body);
    } catch (err) {
      return reply.code(401).send({ error: err instanceof Error ? err.message : 'Verification failed' });
    }
  });

  // --- Protected Owner Admin Namespace ---
  app.register(async function protectedAdmin(scoped) {
    scoped.addHook('preHandler', async (req, reply) => {
      const authHeader = req.headers.authorization ?? '';
      const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7).trim() : '';
      const owner = deps.ownerAuth.authenticateBearerSession(token);
      if (!owner) {
        return reply.code(401).send({ error: 'Unauthorized: valid silent Keystore owner session required.' });
      }
      (req as any).owner = owner;
    });

    const actorOf = (req: any) => ({
      type: 'owner_app' as const,
      id: (req.owner?.ownerDeviceId as string) ?? 'owner_app',
    });

    // Module 1: Dashboard & Analytics
    scoped.get('/api/v1/admin/dashboard', async () => deps.ops.getDashboardSummary());

    // Module 2: App Manager
    scoped.get('/api/v1/admin/apps', async () => ({ items: deps.appLicenseVip.listApps() }));
    scoped.post('/api/v1/admin/apps', async (req) => {
      const body = z
        .object({
          name: z.string().min(1),
          packageName: z.string().min(3),
          signingSha256: z.string().optional(),
          approvalPolicy: z.enum(['auto', 'manual']).optional(),
          description: z.string().optional(),
        })
        .parse(req.body);
      return deps.appLicenseVip.createApp(body, actorOf(req));
    });
    scoped.patch('/api/v1/admin/apps/:id', async (req) => {
      const { id } = req.params as { id: string };
      const body = z
        .object({
          name: z.string().optional(),
          signingSha256: z.string().optional(),
          approvalPolicy: z.enum(['auto', 'manual']).optional(),
          status: z.enum(['active', 'suspended', 'locked']).optional(),
          description: z.string().optional(),
        })
        .parse(req.body);
      return deps.appLicenseVip.updateApp(id, body, actorOf(req));
    });
    scoped.delete('/api/v1/admin/apps/:id', async (req) => {
      const { id } = req.params as { id: string };
      deps.appLicenseVip.deleteApp(id, actorOf(req));
      return { ok: true };
    });

    // Module 3: User (Installation) Manager
    scoped.get('/api/v1/admin/installations', async (req) => {
      const { appId } = req.query as { appId?: string };
      return { items: deps.appLicenseVip.listInstallations(appId) };
    });
    scoped.patch('/api/v1/admin/installations/:id/status', async (req) => {
      const { id } = req.params as { id: string };
      const { status } = z.object({ status: z.enum(['active', 'blocked']) }).parse(req.body);
      deps.appLicenseVip.setInstallationStatus(id, status, actorOf(req));
      return { ok: true };
    });

    // Module 4: Device Manager
    scoped.get('/api/v1/admin/devices', async (req) => {
      const { appId } = req.query as { appId?: string };
      return { items: deps.appLicenseVip.listDevices(appId) };
    });
    scoped.patch('/api/v1/admin/devices/:id/approval', async (req) => {
      const { id } = req.params as { id: string };
      const { approvalStatus } = z
        .object({ approvalStatus: z.enum(['pending', 'approved', 'revoked', 'banned']) })
        .parse(req.body);
      deps.appLicenseVip.updateDeviceApproval(id, approvalStatus, actorOf(req));
      return { ok: true };
    });

    // Module 5: License Manager
    scoped.get('/api/v1/admin/licenses', async (req) => {
      const { status } = req.query as { status?: string };
      return { items: deps.appLicenseVip.listLicenses(status) };
    });
    scoped.post('/api/v1/admin/licenses/generate', async (req) => {
      const body = z
        .object({
          count: z.number().int().min(1).max(100).default(1),
          scope: z.enum(['app', 'global']),
          appId: z.string().optional(),
          durationDays: z.number().int().min(1).optional(),
          maxTransfers: z.number().int().min(0).max(20).optional(),
          transferCooldownSeconds: z.number().int().min(0).optional(),
          notes: z.string().optional(),
        })
        .parse(req.body);
      return { items: deps.appLicenseVip.generateLicenses(body, actorOf(req)) };
    });
    scoped.post('/api/v1/admin/licenses/:id/mutate', async (req) => {
      const { id } = req.params as { id: string };
      const body = z
        .object({
          action: z.enum(['activate', 'revoke', 'renew']),
          renewDays: z.number().int().min(1).optional(),
          deviceId: z.string().optional(),
        })
        .parse(req.body);
      return deps.appLicenseVip.mutateLicense(id, body, actorOf(req));
    });
    scoped.get('/api/v1/admin/licenses/:id/history', async (req) => {
      const { id } = req.params as { id: string };
      return { items: deps.appLicenseVip.getLicenseHistory(id) };
    });

    // Module 6: VIP Manager
    scoped.get('/api/v1/admin/vip/plans', async () => ({ items: deps.appLicenseVip.listVipPlans() }));
    scoped.post('/api/v1/admin/vip/plans', async (req) => {
      const body = z
        .object({
          code: z.string().min(2),
          name: z.string().min(2),
          appId: z.string().optional(),
          durationDays: z.number().int().min(1),
          maxDevices: z.number().int().min(1).default(1),
          priceLabel: z.string().default(''),
          entitlements: z.record(z.unknown()).optional(),
        })
        .parse(req.body);
      return deps.appLicenseVip.createVipPlan(body, actorOf(req));
    });
    scoped.get('/api/v1/admin/vip/subscriptions', async () => ({
      items: deps.appLicenseVip.listVipSubscriptions(),
    }));
    scoped.post('/api/v1/admin/vip/subscriptions/activate', async (req) => {
      const body = z
        .object({
          planId: z.string().min(2),
          installationId: z.string().min(2),
          deviceId: z.string().optional(),
          paymentVerified: z.boolean(),
          paymentReference: z.string().min(2),
        })
        .parse(req.body);
      return deps.appLicenseVip.activateVipSubscription(body, actorOf(req));
    });
    scoped.post('/api/v1/admin/vip/subscriptions/:id/revoke', async (req) => {
      const { id } = req.params as { id: string };
      deps.appLicenseVip.revokeVipSubscription(id, actorOf(req));
      return { ok: true };
    });

    // Module 7: Update Manager
    scoped.get('/api/v1/admin/updates', async (req) => {
      const { appId } = req.query as { appId?: string };
      return { items: deps.delivery.listUpdates(appId) };
    });
    scoped.post('/api/v1/admin/updates', async (req) => {
      const body = z
        .object({
          appId: z.string().min(2),
          latestVersionName: z.string().min(1),
          latestVersionCode: z.number().int().min(1),
          minVersionCode: z.number().int().min(1),
          downloadUrl: z.string().url(),
          whatsNew: z.string().default(''),
          updateMode: z.enum(['normal', 'force']).default('normal'),
          showLaterButton: z.boolean().default(true),
          rolloutPercentage: z.number().int().min(0).max(100).default(100),
          scheduledAt: z.string().optional(),
        })
        .parse(req.body);
      return deps.delivery.createUpdate(body, actorOf(req));
    });
    scoped.get('/api/v1/admin/updates/:id/analytics', async (req) => {
      const { id } = req.params as { id: string };
      return { items: deps.delivery.getUpdateAnalytics(id) };
    });

    // Module 8: Universal Dialog Manager
    scoped.get('/api/v1/admin/dialogs', async (req) => {
      const { appId } = req.query as { appId?: string };
      return { items: deps.delivery.listDialogs(appId) };
    });
    scoped.post('/api/v1/admin/dialogs', async (req) => {
      const body = z
        .object({
          appId: z.string().optional(),
          dialogType: z.enum([
            'custom_popup',
            'optional',
            'force',
            'maintenance',
            'update',
            'channel_join',
            'custom_toast',
            'announcement',
          ]),
          title: z.string().min(1),
          description: z.string().min(1),
          imageUrl: z.string().optional(),
          buttons: z
            .array(z.object({ label: z.string(), actionType: z.string(), actionValue: z.string() }))
            .optional(),
          targetRules: z.record(z.unknown()).optional(),
          priority: z.number().int().optional(),
          scheduledAt: z.string().optional(),
          expiresAt: z.string().optional(),
          isEnabled: z.boolean().optional(),
        })
        .parse(req.body);
      return deps.delivery.createDialog(body, actorOf(req));
    });
    scoped.patch('/api/v1/admin/dialogs/:id/toggle', async (req) => {
      const { id } = req.params as { id: string };
      const { enabled } = z.object({ enabled: z.boolean() }).parse(req.body);
      deps.delivery.toggleDialog(id, enabled, actorOf(req));
      return { ok: true };
    });

    // Module 9: Announcement Manager
    scoped.get('/api/v1/admin/announcements', async () => ({
      items: deps.delivery.listAnnouncements(),
    }));
    scoped.post('/api/v1/admin/announcements', async (req) => {
      const body = z
        .object({
          appId: z.string().optional(),
          title: z.string().min(1),
          body: z.string().min(1),
          actionLabel: z.string().optional(),
          actionUrl: z.string().optional(),
          priority: z.number().int().optional(),
          isPinned: z.boolean().optional(),
          scheduledAt: z.string().optional(),
          expiresAt: z.string().optional(),
        })
        .parse(req.body);
      return deps.delivery.createAnnouncement(body, actorOf(req));
    });

    // Module 10: Remote Configuration Manager
    scoped.get('/api/v1/admin/remote-configs', async (req) => {
      const { scope, appId } = req.query as { scope?: string; appId?: string };
      return { items: deps.delivery.listRemoteConfigs(scope, appId) };
    });
    scoped.post('/api/v1/admin/remote-configs', async (req) => {
      const body = z
        .object({
          scope: z.enum(['global', 'app', 'installation']),
          appId: z.string().optional(),
          installationId: z.string().optional(),
          featureFlags: z.record(z.boolean()).default({}),
          remoteText: z.record(z.string()).default({}),
          remoteLinks: z.record(z.string()).default({}),
          dynamicSettings: z.record(z.unknown()).default({}),
          targetRules: z.record(z.unknown()).optional(),
          scheduledAt: z.string().optional(),
        })
        .parse(req.body);
      return deps.delivery.publishRemoteConfig(body, actorOf(req));
    });
    scoped.post('/api/v1/admin/remote-configs/:id/rollback', async (req) => {
      const { id } = req.params as { id: string };
      deps.delivery.rollbackRemoteConfig(id, actorOf(req));
      return { ok: true };
    });

    // Module 11: Dynamic Module Registry (Declarative Only)
    scoped.get('/api/v1/admin/dynamic-modules', async () => ({
      items: deps.delivery.listDynamicModules(),
    }));
    scoped.post('/api/v1/admin/dynamic-modules', async (req) => {
      const body = z
        .object({
          appId: z.string().optional(),
          moduleKey: z.string().min(2),
          capabilityType: z.enum([
            'feature_gate',
            'ui_banner',
            'dialog_trigger',
            'link_router',
            'telemetry_profile',
          ]),
          version: z.string().default('1.0.0'),
          config: z.record(z.unknown()).default({}),
          targetRules: z.record(z.unknown()).optional(),
          isEnabled: z.boolean().optional(),
        })
        .parse(req.body);
      return deps.delivery.upsertDynamicModule(body, actorOf(req));
    });

    // Modules 12 & 13: Maintenance & Emergency Lockdown
    scoped.get('/api/v1/admin/maintenance', async () => ({
      items: deps.delivery.getMaintenanceState(),
    }));
    scoped.post('/api/v1/admin/maintenance', async (req) => {
      const body = z
        .object({
          scope: z.enum(['global', 'app']),
          appId: z.string().optional(),
          isEnabled: z.boolean(),
          emergencyLockdown: z.boolean(),
          title: z.string().min(1),
          message: z.string().min(1),
          etaIso: z.string().optional(),
          allowlistedInstallations: z.array(z.string()).optional(),
        })
        .parse(req.body);
      return deps.delivery.setMaintenanceState(body, actorOf(req));
    });

    // Module 14: Telegram Bot Manager (Multi-Bot + Encrypted Token + RANGIN)
    scoped.get('/api/v1/admin/telegram-bots', async () => ({ items: deps.telegram.listBots() }));
    scoped.post('/api/v1/admin/telegram-bots', async (req, reply) => {
      const body = z
        .object({
          name: z.string().min(1),
          botFatherToken: z.string().min(25),
          ownerTelegramId: z.string().regex(/^\d{4,20}$/),
          webhookBaseUrl: z.string().optional(),
          welcomeMessage: z.string().optional(),
          commands: z
            .array(z.object({ command: z.string(), description: z.string() }))
            .optional(),
          permissions: z.array(z.string()).optional(),
          settings: z.record(z.unknown()).optional(),
          autoStart: z.boolean().optional(),
        })
        .parse(req.body);
      try {
        return await deps.telegram.addBot(body, actorOf(req));
      } catch (err) {
        return reply.code(400).send({ error: err instanceof Error ? err.message : 'Bot add failed' });
      }
    });
    scoped.patch('/api/v1/admin/telegram-bots/:id', async (req) => {
      const { id } = req.params as { id: string };
      const body = z
        .object({
          name: z.string().optional(),
          ownerTelegramId: z.string().optional(),
          webhookUrl: z.string().optional(),
          rotateWebhookSecret: z.boolean().optional(),
          welcomeMessage: z.string().optional(),
          commands: z
            .array(z.object({ command: z.string(), description: z.string() }))
            .optional(),
          permissions: z.array(z.string()).optional(),
          settings: z.record(z.unknown()).optional(),
        })
        .parse(req.body);
      return await deps.telegram.configureBot(id, body, actorOf(req));
    });
    scoped.post('/api/v1/admin/telegram-bots/:id/start', async (req) => {
      const { id } = req.params as { id: string };
      return await deps.telegram.startBot(id, actorOf(req));
    });
    scoped.post('/api/v1/admin/telegram-bots/:id/stop', async (req) => {
      const { id } = req.params as { id: string };
      return await deps.telegram.stopBot(id, actorOf(req));
    });
    scoped.post('/api/v1/admin/telegram-bots/:id/restart', async (req) => {
      const { id } = req.params as { id: string };
      return await deps.telegram.restartBot(id, actorOf(req));
    });
    scoped.get('/api/v1/admin/telegram-bots/:id/health', async (req) => {
      const { id } = req.params as { id: string };
      return await deps.telegram.refreshBotHealth(id);
    });
    scoped.delete('/api/v1/admin/telegram-bots/:id', async (req) => {
      const { id } = req.params as { id: string };
      await deps.telegram.removeBot(id, actorOf(req));
      return { ok: true };
    });

    // Modules 15 & 16: DEX Release Manager & Hook Release Manager
    scoped.get('/api/v1/admin/dex-releases', async () => ({
      items: deps.delivery.listDexReleases(),
    }));
    scoped.post('/api/v1/admin/dex-releases', async (req) => {
      const body = z
        .object({
          versionName: z.string().min(1),
          versionCode: z.number().int().min(1),
          sha256: z.string().length(64),
          sizeBytes: z.number().int().min(1),
          minSdk: z.number().int().min(21),
          targetSdk: z.number().int().min(24),
          compatibility: z.record(z.unknown()).default({}),
          mtManagerNotes: z.string().default(''),
          activate: z.boolean().optional(),
        })
        .parse(req.body);
      return deps.delivery.registerDexRelease(body, actorOf(req));
    });
    scoped.post('/api/v1/admin/dex-releases/:id/activate', async (req) => {
      const { id } = req.params as { id: string };
      deps.delivery.activateDexRelease(id, actorOf(req));
      return { ok: true };
    });

    scoped.get('/api/v1/admin/hook-releases', async () => ({
      items: deps.delivery.listHookReleases(),
    }));
    scoped.post('/api/v1/admin/hook-releases', async (req) => {
      const body = z
        .object({
          versionName: z.string().min(1),
          versionCode: z.number().int().min(1),
          sha256: z.string().length(64),
          minSdkVersion: z.string().min(1),
          compatibility: z.record(z.unknown()).default({}),
          lifecycleHooks: z.array(z.string()).default([]),
          activate: z.boolean().optional(),
        })
        .parse(req.body);
      return deps.delivery.registerHookRelease(body, actorOf(req));
    });
    scoped.post('/api/v1/admin/hook-releases/:id/activate', async (req) => {
      const { id } = req.params as { id: string };
      deps.delivery.activateHookRelease(id, actorOf(req));
      return { ok: true };
    });

    // Module 17: Database Explorer
    scoped.get('/api/v1/admin/database/tables', async () => ({
      tables: deps.ops.listTablesOverview(),
      integrity: deps.ops.runDatabaseIntegrityCheck(),
    }));
    scoped.get('/api/v1/admin/database/tables/:table', async (req) => {
      const { table } = req.params as { table: string };
      const { limit, offset } = req.query as { limit?: string; offset?: string };
      return deps.ops.inspectTable(table, Number(limit ?? 50), Number(offset ?? 0));
    });

    // Module 18: Audit Logs
    scoped.get('/api/v1/admin/audit-logs', async (req) => {
      const { limit, module, actorType } = req.query as {
        limit?: string;
        module?: string;
        actorType?: string;
      };
      return { items: deps.audit.list(Number(limit ?? 100), module, actorType) };
    });

    // Module 19: Security Center
    scoped.get('/api/v1/admin/security', async () => deps.ops.getSecurityOverview());
    scoped.post('/api/v1/admin/security/recovery-token', async (req) =>
      deps.ownerAuth.issueEnrollmentToken('recovery', actorOf(req).id, 10)
    );
    scoped.post('/api/v1/admin/security/owner-devices/:id/revoke', async (req) => {
      const { id } = req.params as { id: string };
      deps.ownerAuth.revokeOwnerDevice(id, actorOf(req).id);
      return { ok: true };
    });

    // Module 20: Backup & Restore
    scoped.get('/api/v1/admin/backups', async () => ({ items: deps.ops.listBackups() }));
    scoped.post('/api/v1/admin/backups', async (req) => {
      const body = z.object({ label: z.string().default('manual') }).parse(req.body ?? {});
      return deps.ops.triggerBackup(body.label, actorOf(req));
    });
    scoped.post('/api/v1/admin/backups/:fileName/verify', async (req) => {
      const { fileName } = req.params as { fileName: string };
      return deps.ops.verifyBackup(fileName);
    });
    scoped.post('/api/v1/admin/backups/:fileName/restore', async (req) => {
      const { fileName } = req.params as { fileName: string };
      return deps.ops.restoreFromBackup(fileName, actorOf(req));
    });

    // Module 21: Backend Health
    scoped.get('/api/v1/admin/health', async () => deps.ops.getBackendHealth());

    // Module 22: Server Monitoring
    scoped.get('/api/v1/admin/server', async () => deps.ops.getServerMonitoring());

    // Module 23: Configuration Scheduler
    scoped.get('/api/v1/admin/schedules', async () => ({ items: deps.ops.listSchedules() }));
    scoped.post('/api/v1/admin/schedules', async (req) => {
      const body = z
        .object({
          jobType: z.enum([
            'remote_config',
            'update',
            'dialog',
            'announcement',
            'maintenance',
            'backup',
          ]),
          targetId: z.string().min(1),
          payload: z.record(z.unknown()).optional(),
          runAt: z.string().min(10),
        })
        .parse(req.body);
      return deps.ops.createSchedule(body, actorOf(req));
    });
    scoped.post('/api/v1/admin/schedules/:id/cancel', async (req) => {
      const { id } = req.params as { id: string };
      deps.ops.cancelSchedule(id, actorOf(req));
      return { ok: true };
    });

    // Module 24: System Settings
    scoped.get('/api/v1/admin/settings', async () => ({ items: deps.ops.listSystemSettings() }));
    scoped.put('/api/v1/admin/settings/:key', async (req) => {
      const { key } = req.params as { key: string };
      const body = z
        .object({ value: z.unknown(), description: z.string().optional() })
        .parse(req.body);
      return deps.ops.updateSystemSetting(key, body.value, body.description, actorOf(req));
    });
  });
}
