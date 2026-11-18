export const MIGRATION_001_ID = '001_initial_pranav_schema';

export const MIGRATION_001_SQL = `
CREATE TABLE IF NOT EXISTS schema_migrations (
  id TEXT PRIMARY KEY,
  applied_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS admins (
  id TEXT PRIMARY KEY,
  owner_name TEXT NOT NULL,
  owner_telegram_id TEXT NOT NULL DEFAULT '',
  recovery_enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS owner_devices (
  id TEXT PRIMARY KEY,
  admin_id TEXT NOT NULL REFERENCES admins(id) ON DELETE CASCADE,
  device_label TEXT NOT NULL,
  hardware_model TEXT NOT NULL DEFAULT '',
  public_key_pem TEXT NOT NULL,
  key_fingerprint TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('active', 'revoked')),
  enrolled_at TEXT NOT NULL,
  last_verified_at TEXT,
  revoked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_owner_devices_status ON owner_devices(status);

CREATE TABLE IF NOT EXISTS owner_enrollment_tokens (
  id TEXT PRIMARY KEY,
  token_hash TEXT NOT NULL UNIQUE,
  purpose TEXT NOT NULL CHECK (purpose IN ('bootstrap', 'recovery')),
  issued_by TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  consumed_by_device_id TEXT,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS owner_sessions (
  id TEXT PRIMARY KEY,
  owner_device_id TEXT NOT NULL REFERENCES owner_devices(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL,
  last_used_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_owner_sessions_device ON owner_sessions(owner_device_id);

CREATE TABLE IF NOT EXISTS apps (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  package_name TEXT NOT NULL UNIQUE,
  signing_sha256 TEXT NOT NULL DEFAULT '',
  app_secret_hash TEXT NOT NULL,
  approval_policy TEXT NOT NULL DEFAULT 'auto' CHECK (approval_policy IN ('auto', 'manual')),
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'suspended', 'locked')),
  description TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS installations (
  id TEXT PRIMARY KEY,
  app_id TEXT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  installation_uuid TEXT NOT NULL,
  app_version TEXT NOT NULL DEFAULT '1.0.0',
  version_code INTEGER NOT NULL DEFAULT 1,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'blocked')),
  first_seen_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL,
  metadata_json TEXT NOT NULL DEFAULT '{}',
  UNIQUE(app_id, installation_uuid)
);
CREATE INDEX IF NOT EXISTS idx_installations_app ON installations(app_id);

CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  installation_id TEXT REFERENCES installations(id) ON DELETE SET NULL,
  app_id TEXT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  device_key_fingerprint TEXT NOT NULL,
  public_key_pem TEXT NOT NULL,
  hardware_model TEXT NOT NULL DEFAULT '',
  os_version TEXT NOT NULL DEFAULT '',
  approval_status TEXT NOT NULL DEFAULT 'approved' CHECK (approval_status IN ('pending', 'approved', 'revoked', 'banned')),
  created_at TEXT NOT NULL,
  last_authenticated_at TEXT,
  revoked_at TEXT,
  UNIQUE(app_id, device_key_fingerprint)
);
CREATE INDEX IF NOT EXISTS idx_devices_app_status ON devices(app_id, approval_status);

CREATE TABLE IF NOT EXISTS licenses (
  id TEXT PRIMARY KEY,
  license_key TEXT NOT NULL UNIQUE,
  scope TEXT NOT NULL CHECK (scope IN ('app', 'global')),
  app_id TEXT REFERENCES apps(id) ON DELETE CASCADE,
  status TEXT NOT NULL DEFAULT 'unused' CHECK (status IN ('unused', 'active', 'expired', 'revoked')),
  bound_device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
  bound_installation_id TEXT REFERENCES installations(id) ON DELETE SET NULL,
  max_transfers INTEGER NOT NULL DEFAULT 2,
  transfer_count INTEGER NOT NULL DEFAULT 0,
  transfer_cooldown_seconds INTEGER NOT NULL DEFAULT 86400,
  last_transfer_at TEXT,
  activated_at TEXT,
  expires_at TEXT,
  notes TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_licenses_key ON licenses(license_key);
CREATE INDEX IF NOT EXISTS idx_licenses_status ON licenses(status);

CREATE TABLE IF NOT EXISTS license_transfers (
  id TEXT PRIMARY KEY,
  license_id TEXT NOT NULL REFERENCES licenses(id) ON DELETE CASCADE,
  from_device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
  to_device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  initiated_by TEXT NOT NULL,
  reason TEXT NOT NULL DEFAULT '',
  transferred_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS vip_plans (
  id TEXT PRIMARY KEY,
  code TEXT NOT NULL UNIQUE,
  name TEXT NOT NULL,
  app_id TEXT REFERENCES apps(id) ON DELETE CASCADE,
  duration_days INTEGER NOT NULL,
  max_devices INTEGER NOT NULL DEFAULT 1,
  price_label TEXT NOT NULL DEFAULT '',
  entitlements_json TEXT NOT NULL DEFAULT '{}',
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS vip_subscriptions (
  id TEXT PRIMARY KEY,
  plan_id TEXT NOT NULL REFERENCES vip_plans(id) ON DELETE CASCADE,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
  status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('pending', 'active', 'expired', 'revoked')),
  payment_verified INTEGER NOT NULL DEFAULT 0,
  payment_reference TEXT NOT NULL DEFAULT '',
  device_limit INTEGER NOT NULL DEFAULT 1,
  activated_at TEXT,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_vip_subs_inst ON vip_subscriptions(installation_id, status);

CREATE TABLE IF NOT EXISTS updates (
  id TEXT PRIMARY KEY,
  app_id TEXT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  latest_version_name TEXT NOT NULL,
  latest_version_code INTEGER NOT NULL,
  min_version_code INTEGER NOT NULL,
  download_url TEXT NOT NULL,
  whats_new TEXT NOT NULL DEFAULT '',
  update_mode TEXT NOT NULL DEFAULT 'normal' CHECK (update_mode IN ('normal', 'force')),
  show_later_button INTEGER NOT NULL DEFAULT 1,
  rollout_percentage INTEGER NOT NULL DEFAULT 100 CHECK (rollout_percentage >= 0 AND rollout_percentage <= 100),
  scheduled_at TEXT,
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_updates_app_active ON updates(app_id, is_active);

CREATE TABLE IF NOT EXISTS update_confirmations (
  id TEXT PRIMARY KEY,
  update_id TEXT NOT NULL REFERENCES updates(id) ON DELETE CASCADE,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
  action TEXT NOT NULL CHECK (action IN ('shown', 'later', 'accepted', 'completed')),
  app_version_code INTEGER NOT NULL,
  confirmed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dialogs (
  id TEXT PRIMARY KEY,
  app_id TEXT REFERENCES apps(id) ON DELETE CASCADE,
  dialog_type TEXT NOT NULL CHECK (dialog_type IN ('custom_popup', 'optional', 'force', 'maintenance', 'update', 'channel_join', 'custom_toast', 'announcement')),
  title TEXT NOT NULL,
  description TEXT NOT NULL,
  image_url TEXT NOT NULL DEFAULT '',
  buttons_json TEXT NOT NULL DEFAULT '[]',
  target_rules_json TEXT NOT NULL DEFAULT '{}',
  priority INTEGER NOT NULL DEFAULT 50,
  scheduled_at TEXT,
  expires_at TEXT,
  is_enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_dialogs_app_enabled ON dialogs(app_id, is_enabled, priority);

CREATE TABLE IF NOT EXISTS announcements (
  id TEXT PRIMARY KEY,
  app_id TEXT REFERENCES apps(id) ON DELETE CASCADE,
  title TEXT NOT NULL,
  body TEXT NOT NULL,
  action_label TEXT NOT NULL DEFAULT '',
  action_url TEXT NOT NULL DEFAULT '',
  priority INTEGER NOT NULL DEFAULT 50,
  is_pinned INTEGER NOT NULL DEFAULT 0,
  target_rules_json TEXT NOT NULL DEFAULT '{}',
  scheduled_at TEXT,
  expires_at TEXT,
  is_enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS remote_configs (
  id TEXT PRIMARY KEY,
  scope TEXT NOT NULL CHECK (scope IN ('global', 'app', 'installation')),
  app_id TEXT REFERENCES apps(id) ON DELETE CASCADE,
  installation_id TEXT REFERENCES installations(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  feature_flags_json TEXT NOT NULL DEFAULT '{}',
  remote_text_json TEXT NOT NULL DEFAULT '{}',
  remote_links_json TEXT NOT NULL DEFAULT '{}',
  dynamic_settings_json TEXT NOT NULL DEFAULT '{}',
  target_rules_json TEXT NOT NULL DEFAULT '{}',
  scheduled_at TEXT,
  is_active INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_remote_configs_scope ON remote_configs(scope, app_id, is_active, version);

CREATE TABLE IF NOT EXISTS dynamic_modules (
  id TEXT PRIMARY KEY,
  app_id TEXT REFERENCES apps(id) ON DELETE CASCADE,
  module_key TEXT NOT NULL,
  capability_type TEXT NOT NULL CHECK (capability_type IN ('feature_gate', 'ui_banner', 'dialog_trigger', 'link_router', 'telemetry_profile')),
  version TEXT NOT NULL DEFAULT '1.0.0',
  config_json TEXT NOT NULL DEFAULT '{}',
  target_rules_json TEXT NOT NULL DEFAULT '{}',
  is_enabled INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS dex_releases (
  id TEXT PRIMARY KEY,
  version_name TEXT NOT NULL,
  version_code INTEGER NOT NULL,
  file_name TEXT NOT NULL DEFAULT 'PRANAV.dex',
  sha256 TEXT NOT NULL,
  size_bytes INTEGER NOT NULL,
  min_sdk INTEGER NOT NULL DEFAULT 24,
  target_sdk INTEGER NOT NULL DEFAULT 36,
  compatibility_json TEXT NOT NULL DEFAULT '{}',
  mt_manager_notes TEXT NOT NULL DEFAULT '',
  is_active INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS hook_releases (
  id TEXT PRIMARY KEY,
  version_name TEXT NOT NULL,
  version_code INTEGER NOT NULL,
  package_name TEXT NOT NULL DEFAULT 'pranav-hook',
  sha256 TEXT NOT NULL,
  min_sdk_version TEXT NOT NULL DEFAULT '1.0.0',
  compatibility_json TEXT NOT NULL DEFAULT '{}',
  lifecycle_hooks_json TEXT NOT NULL DEFAULT '[]',
  is_active INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sdk_sessions (
  id TEXT PRIMARY KEY,
  app_id TEXT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  installation_id TEXT NOT NULL REFERENCES installations(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL,
  last_seen_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS device_challenges (
  id TEXT PRIMARY KEY,
  actor_type TEXT NOT NULL CHECK (actor_type IN ('owner', 'client')),
  actor_id TEXT NOT NULL,
  nonce TEXT NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  consumed_at TEXT,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_challenges_expiry ON device_challenges(expires_at);

CREATE TABLE IF NOT EXISTS telegram_bots (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  bot_id TEXT NOT NULL DEFAULT '',
  bot_username TEXT NOT NULL DEFAULT '',
  encrypted_token TEXT NOT NULL,
  token_iv TEXT NOT NULL,
  token_tag TEXT NOT NULL,
  token_masked TEXT NOT NULL,
  token_sha256 TEXT NOT NULL UNIQUE,
  owner_telegram_id TEXT NOT NULL,
  webhook_url TEXT NOT NULL DEFAULT '',
  webhook_secret_encrypted TEXT NOT NULL,
  webhook_secret_iv TEXT NOT NULL,
  webhook_secret_tag TEXT NOT NULL,
  webhook_secret_sha256 TEXT NOT NULL,
  welcome_message TEXT NOT NULL DEFAULT 'Welcome to PRANAV RANGIN.',
  commands_json TEXT NOT NULL DEFAULT '[]',
  permissions_json TEXT NOT NULL DEFAULT '["all"]',
  settings_json TEXT NOT NULL DEFAULT '{}',
  status TEXT NOT NULL DEFAULT 'stopped' CHECK (status IN ('running', 'stopped', 'error')),
  health_json TEXT NOT NULL DEFAULT '{}',
  updates_processed INTEGER NOT NULL DEFAULT 0,
  updates_rejected INTEGER NOT NULL DEFAULT 0,
  updates_deduped INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT '',
  last_heartbeat_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS telegram_updates (
  id TEXT PRIMARY KEY,
  bot_id TEXT NOT NULL REFERENCES telegram_bots(id) ON DELETE CASCADE,
  update_id INTEGER NOT NULL,
  sender_id TEXT NOT NULL,
  authorized INTEGER NOT NULL,
  interaction_type TEXT NOT NULL,
  received_at TEXT NOT NULL,
  UNIQUE(bot_id, update_id)
);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY,
  app_id TEXT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  installation_id TEXT REFERENCES installations(id) ON DELETE SET NULL,
  device_id TEXT REFERENCES devices(id) ON DELETE SET NULL,
  event_type TEXT NOT NULL,
  payload_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_events_app_time ON events(app_id, created_at);

CREATE TABLE IF NOT EXISTS audit_logs (
  id TEXT PRIMARY KEY,
  actor_type TEXT NOT NULL CHECK (actor_type IN ('owner_app', 'telegram_owner', 'telegram_blocked', 'client_sdk', 'system', 'worker')),
  actor_id TEXT NOT NULL,
  module TEXT NOT NULL,
  action TEXT NOT NULL,
  target_id TEXT NOT NULL DEFAULT '',
  details_json TEXT NOT NULL DEFAULT '{}',
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_logs_time ON audit_logs(created_at DESC);

CREATE TABLE IF NOT EXISTS system_settings (
  key TEXT PRIMARY KEY,
  value_json TEXT NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS maintenance (
  id TEXT PRIMARY KEY,
  scope TEXT NOT NULL CHECK (scope IN ('global', 'app')),
  app_id TEXT REFERENCES apps(id) ON DELETE CASCADE,
  is_enabled INTEGER NOT NULL DEFAULT 0,
  emergency_lockdown INTEGER NOT NULL DEFAULT 0,
  title TEXT NOT NULL DEFAULT 'Scheduled Maintenance',
  message TEXT NOT NULL DEFAULT 'Service is undergoing maintenance.',
  eta_iso TEXT,
  allowlisted_installations_json TEXT NOT NULL DEFAULT '[]',
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS config_schedules (
  id TEXT PRIMARY KEY,
  job_type TEXT NOT NULL CHECK (job_type IN ('remote_config', 'update', 'dialog', 'announcement', 'maintenance', 'backup')),
  target_id TEXT NOT NULL,
  payload_json TEXT NOT NULL DEFAULT '{}',
  run_at TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'executed', 'cancelled', 'failed')),
  executed_at TEXT,
  error_message TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_schedules_pending ON config_schedules(status, run_at);
`;
