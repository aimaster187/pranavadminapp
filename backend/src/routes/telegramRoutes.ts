import { FastifyInstance } from 'fastify';
import { TelegramBotService, TelegramUpdatePayload } from '../services/telegramBotService.js';

export function registerTelegramRoutes(
  app: FastifyInstance,
  telegramService: TelegramBotService
): void {
  app.post('/api/v1/telegram/webhook/:botId', async (req, reply) => {
    const { botId } = req.params as { botId: string };
    const secretHeader = req.headers['x-telegram-bot-api-secret-token'] as string | undefined;
    const payload = req.body as TelegramUpdatePayload;

    if (!payload || typeof payload.update_id !== 'number') {
      return reply.code(400).send({ ok: false, error: 'Malformed Telegram update' });
    }

    const result = await telegramService.handleWebhookUpdate(botId, secretHeader, payload);
    if (result.status === 'invalid_webhook_secret') {
      return reply.code(401).send(result);
    }
    if (result.status === 'rate_limited') {
      return reply.code(429).send(result);
    }
    return reply.code(200).send(result);
  });
}
