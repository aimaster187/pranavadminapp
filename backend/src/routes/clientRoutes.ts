import { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { ClientSdkService } from '../services/clientSdkService.js';

export function registerClientRoutes(app: FastifyInstance, clientSdk: ClientSdkService): void {
  app.post('/api/v1/client/installations/register', async (req, reply) => {
    const body = z
      .object({
        appId: z.string().min(2),
        installationUuid: z.string().min(8),
        appVersion: z.string().min(1),
        versionCode: z.number().int().min(1),
        publicKeyPem: z.string().min(32),
        hardwareModel: z.string().default('Android'),
        osVersion: z.string().default('14'),
      })
      .parse(req.body);
    try {
      return clientSdk.registerInstallation(body);
    } catch (err) {
      return reply.code(403).send({ error: err instanceof Error ? err.message : 'Registration failed' });
    }
  });

  app.post('/api/v1/client/auth/challenge', async (req, reply) => {
    const body = z
      .object({
        appId: z.string().min(2),
        deviceId: z.string().min(4),
      })
      .parse(req.body);
    try {
      return clientSdk.createDeviceChallenge(body.appId, body.deviceId);
    } catch (err) {
      return reply.code(403).send({ error: err instanceof Error ? err.message : 'Challenge denied' });
    }
  });

  app.post('/api/v1/client/auth/verify', async (req, reply) => {
    const body = z
      .object({
        appId: z.string().min(2),
        installationId: z.string().min(4),
        deviceId: z.string().min(4),
        challengeId: z.string().min(4),
        timestamp: z.string().min(8),
        signatureBase64: z.string().min(16),
      })
      .parse(req.body);
    try {
      return clientSdk.verifyDeviceChallenge(body);
    } catch (err) {
      return reply.code(401).send({ error: err instanceof Error ? err.message : 'Verification failed' });
    }
  });

  // Protected SDK Session Routes (Strictly Isolated from Admin Routes)
  app.register(async function protectedClient(scoped) {
    scoped.addHook('preHandler', async (req, reply) => {
      const authHeader = req.headers.authorization ?? '';
      const sessionToken = authHeader.startsWith('Bearer ') ? authHeader.slice(7).trim() : '';
      const appId = String(req.headers['x-app-id'] ?? '');
      const installationId = String(req.headers['x-installation-id'] ?? '');
      const timestamp = String(req.headers['x-timestamp'] ?? '');
      const nonce = String(req.headers['x-nonce'] ?? '');
      const signatureBase64 = String(req.headers['x-signature'] ?? '');

      if (!sessionToken || !appId || !installationId || !timestamp || !nonce || !signatureBase64) {
        return reply.code(401).send({ error: 'Missing signed SDK request headers.' });
      }
      try {
        const ctx = clientSdk.authenticateClientRequest({
          sessionToken,
          appId,
          installationId,
          timestamp,
          nonce,
          signatureBase64,
          method: req.method,
          path: req.url.split('?')[0],
        });
        (req as any).clientCtx = ctx;
      } catch (err) {
        return reply.code(401).send({ error: err instanceof Error ? err.message : 'Unauthorized SDK client' });
      }
    });

    scoped.get('/api/v1/client/bootstrap', async (req, reply) => {
      const { versionCode } = req.query as { versionCode?: string };
      try {
        return clientSdk.getClientBootstrapPayload(
          (req as any).clientCtx,
          Number(versionCode ?? 1)
        );
      } catch (err) {
        return reply.code(403).send({ error: err instanceof Error ? err.message : 'Service unavailable' });
      }
    });

    scoped.post('/api/v1/client/licenses/activate', async (req, reply) => {
      const body = z
        .object({
          licenseKey: z.string().min(6),
          allowTransfer: z.boolean().default(false),
        })
        .parse(req.body);
      try {
        return clientSdk.activateOrTransferLicense(
          (req as any).clientCtx,
          body.licenseKey,
          body.allowTransfer
        );
      } catch (err) {
        return reply.code(403).send({ error: err instanceof Error ? err.message : 'License error' });
      }
    });

    scoped.post('/api/v1/client/updates/confirm', async (req) => {
      const body = z
        .object({
          updateId: z.string().min(2),
          action: z.enum(['shown', 'later', 'accepted', 'completed']),
          appVersionCode: z.number().int().min(1),
        })
        .parse(req.body);
      clientSdk.recordUpdateConfirmation(
        (req as any).clientCtx,
        body.updateId,
        body.action,
        body.appVersionCode
      );
      return { ok: true };
    });

    scoped.post('/api/v1/client/events', async (req) => {
      const body = z
        .object({
          eventType: z.string().min(1).max(64),
          payload: z.record(z.unknown()).default({}),
        })
        .parse(req.body);
      clientSdk.recordEvent((req as any).clientCtx, body.eventType, body.payload);
      return { ok: true };
    });
  });
}
