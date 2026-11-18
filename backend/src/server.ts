import { loadConfig } from './config/env.js';
import { buildPranavApp } from './app.js';

async function main() {
  const config = loadConfig();
  const runtime = buildPranavApp(config, { enableWorkers: true, logger: true });

  // Automated First-Run Owner Setup: print a one-time bootstrap token if no owner device is enrolled yet
  if (!runtime.services.ownerAuth.hasActiveOwnerDevice()) {
    const bootstrap = runtime.services.ownerAuth.issueEnrollmentToken(
      'bootstrap',
      'system_first_run',
      30
    );
    console.log('\n==============================================================');
    console.log('PRANAV FIRST-RUN OWNER ENROLLMENT TOKEN (30 MIN SINGLE-USE):');
    console.log(bootstrap.token);
    console.log('==============================================================\n');
  }

  // Optional automated RANGIN bootstrap if both OWNER_TELEGRAM_ID and RANGIN_BOT_TOKEN are configured in env
  if (
    config.ownerTelegramId &&
    config.ranginBotToken &&
    runtime.services.telegram.listBots().length === 0
  ) {
    try {
      await runtime.services.telegram.addBot(
        {
          name: 'RANGIN',
          botFatherToken: config.ranginBotToken,
          ownerTelegramId: config.ownerTelegramId,
          webhookBaseUrl: config.publicBaseUrl,
          welcomeMessage: 'Welcome to PRANAV RANGIN.',
          autoStart: true,
        },
        { type: 'system', id: 'bootstrap_rangin' }
      );
      console.log('RANGIN Telegram Bot initialized and encrypted in database.');
    } catch (err) {
      console.error('RANGIN bootstrap failed:', err instanceof Error ? err.message : err);
    }
  }

  const shutdown = async () => {
    runtime.stopWorkers();
    await runtime.app.close();
    runtime.db.close();
    process.exit(0);
  };

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);

  await runtime.app.listen({ host: config.host, port: config.port });
}

main().catch((err) => {
  console.error('Fatal PRANAV startup error:', err);
  process.exit(1);
});
