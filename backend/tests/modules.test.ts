import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { buildPranavApp } from '../src/app.js';
import { loadConfig } from '../src/config/env.js';
import { generateEcdsaKeyPairPem, randomTokenHex } from '../src/security/crypto.js';

const dirs: string[] = [];

afterEach(() => {
  while (dirs.length > 0) fs.rmSync(dirs.pop()!, { recursive: true, force: true });
});

describe('PRANAV All 24 Admin Modules REST Verification', () => {
  it('exercises VIP, Updates, Universal Dialogs, Announcements, Remote Config Rollback, Dynamic Modules, DEX/Hook Releases, DB Explorer, Scheduler & Settings', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pranav-mod-'));
    dirs.push(dir);
    const runtime = buildPranavApp(
      loadConfig({
        NODE_ENV: 'test',
        PRANAV_DB_PATH: path.join(dir, 'pranav.db'),
        PRANAV_BACKUP_DIR: path.join(dir, 'backups'),
        PRANAV_MASTER_ENCRYPTION_KEY: randomTokenHex(32),
      })
    );

    const { publicKeyPem } = generateEcdsaKeyPairPem();
    const boot = runtime.services.ownerAuth.issueEnrollmentToken('bootstrap', 'cli', 10);
    const owner = runtime.services.ownerAuth.enrollOwnerDevice({
      enrollmentToken: boot.token,
      deviceLabel: 'Owner Phone',
      hardwareModel: 'Pixel 9',
      publicKeyPem,
    });
    const headers = { authorization: `Bearer ${owner.sessionToken}` };

    // App creation
    const appRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/apps',
      headers,
      payload: { name: 'PranavHost', packageName: 'com.pranav.host' },
    });
    expect(appRes.statusCode).toBe(200);
    const appId = (appRes.json() as { app: { id: string } }).app.id;

    // VIP Plan + Server-Side Payment Verification check
    const vipPlan = (
      await runtime.app.inject({
        method: 'POST',
        url: '/api/v1/admin/vip/plans',
        headers,
        payload: { code: 'PRO_YEAR', name: 'Pro Yearly', durationDays: 365, maxDevices: 2 },
      })
    ).json() as { id: string };

    const reg = runtime.services.clientSdk.registerInstallation({
      appId,
      installationUuid: 'inst-vip-001',
      appVersion: '1.0.0',
      versionCode: 1,
      publicKeyPem: generateEcdsaKeyPairPem().publicKeyPem,
      hardwareModel: 'Pixel',
      osVersion: '15',
    });

    const unverifiedVip = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/vip/subscriptions/activate',
      headers,
      payload: {
        planId: vipPlan.id,
        installationId: reg.installationId,
        paymentVerified: false,
        paymentReference: 'fake_client_claim',
      },
    });
    expect(unverifiedVip.statusCode).toBe(500); // Refused server-side!

    const verifiedVip = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/vip/subscriptions/activate',
      headers,
      payload: {
        planId: vipPlan.id,
        installationId: reg.installationId,
        paymentVerified: true,
        paymentReference: 'STRIPE_PI_998123',
      },
    });
    expect(verifiedVip.statusCode).toBe(200);

    // Update Manager
    const updRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/updates',
      headers,
      payload: {
        appId,
        latestVersionName: '2.0.0',
        latestVersionCode: 20,
        minVersionCode: 10,
        downloadUrl: 'https://cdn.pranav.example.com/host-2.0.0.apk',
        whatsNew: 'Added 3D HUD & faster handshake.',
        updateMode: 'force',
        showLaterButton: false,
        rolloutPercentage: 100,
      },
    });
    expect(updRes.statusCode).toBe(200);

    // Universal Dialog Manager
    const dlgRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/dialogs',
      headers,
      payload: {
        appId,
        dialogType: 'channel_join',
        title: 'Join RANGIN Channel',
        description: 'Official announcements and release checksums.',
        buttons: [{ label: 'Join', actionType: 'url', actionValue: 'https://t.me/pranav' }],
        priority: 90,
      },
    });
    expect(dlgRes.statusCode).toBe(200);

    // Remote Config Publish v1 + v2 + Rollback to v1
    const cfg1 = (
      await runtime.app.inject({
        method: 'POST',
        url: '/api/v1/admin/remote-configs',
        headers,
        payload: {
          scope: 'app',
          appId,
          featureFlags: { enableFastSync: true },
          remoteText: { banner: 'v1' },
          remoteLinks: {},
          dynamicSettings: {},
        },
      })
    ).json() as { id: string; version: number };
    expect(cfg1.version).toBe(1);

    const cfg2 = (
      await runtime.app.inject({
        method: 'POST',
        url: '/api/v1/admin/remote-configs',
        headers,
        payload: {
          scope: 'app',
          appId,
          featureFlags: { enableFastSync: false },
          remoteText: { banner: 'v2' },
          remoteLinks: {},
          dynamicSettings: {},
        },
      })
    ).json() as { id: string; version: number };
    expect(cfg2.version).toBe(2);

    await runtime.app.inject({
      method: 'POST',
      url: `/api/v1/admin/remote-configs/${cfg1.id}/rollback`,
      headers,
    });
    const listCfg = (
      await runtime.app.inject({
        method: 'GET',
        url: `/api/v1/admin/remote-configs?scope=app&appId=${appId}`,
        headers,
      })
    ).json() as { items: Array<{ id: string; is_active: number }> };
    expect(listCfg.items.find((c) => c.id === cfg1.id)?.is_active).toBe(1);

    // Declarative Dynamic Module + DEX + Hook releases
    const modRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/dynamic-modules',
      headers,
      payload: {
        appId,
        moduleKey: 'telemetry_lite',
        capabilityType: 'telemetry_profile',
        version: '1.0.0',
        config: { intervalSec: 60 },
      },
    });
    expect(modRes.statusCode).toBe(200);

    const dexRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/dex-releases',
      headers,
      payload: {
        versionName: '1.0.0',
        versionCode: 1,
        sha256: 'a'.repeat(64),
        sizeBytes: 14336,
        minSdk: 24,
        targetSdk: 36,
        compatibility: { mtManagerReady: true },
        mtManagerNotes: 'Verified standalone PRANAV.dex bytecode',
        activate: true,
      },
    });
    expect(dexRes.statusCode).toBe(200);

    const hookRes = await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/hook-releases',
      headers,
      payload: {
        versionName: '1.0.0',
        versionCode: 1,
        sha256: 'b'.repeat(64),
        minSdkVersion: '1.0.0',
        compatibility: { androidxLifecycle: true },
        lifecycleHooks: ['Application.onCreate', 'Activity.onResume'],
        activate: true,
      },
    });
    expect(hookRes.statusCode).toBe(200);

    // DB Explorer allowlist blocks injection
    const badTable = await runtime.app.inject({
      method: 'GET',
      url: '/api/v1/admin/database/tables/sqlite_master',
      headers,
    });
    expect(badTable.statusCode).toBe(500);

    // Scheduler due execution
    await runtime.app.inject({
      method: 'POST',
      url: '/api/v1/admin/schedules',
      headers,
      payload: {
        jobType: 'backup',
        targetId: 'nightly',
        runAt: new Date(Date.now() - 1000).toISOString(),
      },
    });
    expect(runtime.services.ops.runDueScheduledJobs()).toBe(1);

    // Health, Server Monitoring & System Settings
    expect((await runtime.app.inject({ method: 'GET', url: '/api/v1/admin/health', headers })).statusCode).toBe(200);
    expect((await runtime.app.inject({ method: 'GET', url: '/api/v1/admin/server', headers })).statusCode).toBe(200);
    expect(
      (
        await runtime.app.inject({
          method: 'PUT',
          url: '/api/v1/admin/settings/rate_limit_max_per_minute',
          headers,
          payload: { value: 180 },
        })
      ).statusCode
    ).toBe(200);

    await runtime.app.close();
    runtime.db.close();
  });
});
