package com.example.data

import com.squareup.moshi.Json

data class AuthStatusResponse(
  val enrolledDevicesCount: Int = 0,
  val hasActiveOwnerDevice: Boolean = false,
)

data class EnrollOwnerRequest(
  val enrollmentToken: String,
  val deviceLabel: String,
  val hardwareModel: String,
  val publicKeyPem: String,
)

data class EnrollOwnerResponse(
  val ownerDeviceId: String,
  val sessionToken: String,
  val expiresAt: String,
  val keyFingerprint: String,
)

data class ChallengeRequest(val ownerDeviceId: String)

data class ChallengeResponse(
  val challengeId: String,
  val nonce: String,
  val expiresAt: String,
)

data class VerifyChallengeRequest(
  val ownerDeviceId: String,
  val challengeId: String,
  val timestamp: String,
  val signatureBase64: String,
)

data class VerifyChallengeResponse(
  val sessionToken: String,
  val expiresAt: String,
  val ownerDeviceId: String,
)

data class DashboardKpis(
  val totalApps: Int = 0,
  val activeApps: Int = 0,
  val totalInstallations: Int = 0,
  val activeDevices: Int = 0,
  val activeLicenses: Int = 0,
  val totalLicenses: Int = 0,
  val activeVip: Int = 0,
  val activeBots: Int = 0,
  val totalBots: Int = 0,
  val activeDialogs: Int = 0,
  val pendingJobs: Int = 0,
  val maintenanceEnabled: Boolean = false,
  val emergencyLockdown: Boolean = false,
)

data class ActivityPoint(
  val day: String = "",
  @Json(name = "events_count") val eventsCount: Int = 0,
)

data class LicenseSlice(
  val status: String = "",
  val total: Int = 0,
)

data class DashboardResponse(
  val kpis: DashboardKpis = DashboardKpis(),
  val activitySeries: List<ActivityPoint> = emptyList(),
  val licenseBreakdown: List<LicenseSlice> = emptyList(),
  val recentAudit: List<AuditLogItem> = emptyList(),
)

data class AppItem(
  val id: String = "",
  val name: String = "",
  @Json(name = "package_name") val packageName: String = "",
  @Json(name = "signing_sha256") val signingSha256: String = "",
  @Json(name = "approval_policy") val approvalPolicy: String = "auto",
  val status: String = "active",
  val description: String = "",
  @Json(name = "installation_count") val installationCount: Int = 0,
  @Json(name = "active_license_count") val activeLicenseCount: Int = 0,
)

data class CreateAppResponse(
  val app: AppItem,
  val issuedAppKey: String,
)

data class InstallationItem(
  val id: String = "",
  @Json(name = "app_id") val appId: String = "",
  @Json(name = "app_name") val appName: String = "",
  @Json(name = "installation_uuid") val installationUuid: String = "",
  @Json(name = "app_version") val appVersion: String = "",
  @Json(name = "version_code") val versionCode: Int = 1,
  val status: String = "active",
  @Json(name = "last_seen_at") val lastSeenAt: String = "",
)

data class DeviceItem(
  val id: String = "",
  @Json(name = "app_id") val appId: String = "",
  @Json(name = "device_key_fingerprint") val fingerprint: String = "",
  @Json(name = "hardware_model") val hardwareModel: String = "",
  @Json(name = "os_version") val osVersion: String = "",
  @Json(name = "approval_status") val approvalStatus: String = "approved",
  @Json(name = "created_at") val createdAt: String = "",
)

data class LicenseItem(
  val id: String = "",
  @Json(name = "license_key") val licenseKey: String = "",
  val scope: String = "global",
  @Json(name = "app_id") val appId: String? = null,
  val status: String = "unused",
  @Json(name = "bound_device_id") val boundDeviceId: String? = null,
  @Json(name = "max_transfers") val maxTransfers: Int = 2,
  @Json(name = "transfer_count") val transferCount: Int = 0,
  @Json(name = "transfer_cooldown_seconds") val transferCooldownSeconds: Int = 86400,
  @Json(name = "expires_at") val expiresAt: String? = null,
)

data class VipPlanItem(
  val id: String = "",
  val code: String = "",
  val name: String = "",
  @Json(name = "duration_days") val durationDays: Int = 30,
  @Json(name = "max_devices") val maxDevices: Int = 1,
  @Json(name = "price_label") val priceLabel: String = "",
)

data class VipSubscriptionItem(
  val id: String = "",
  @Json(name = "plan_code") val planCode: String = "",
  @Json(name = "plan_name") val planName: String = "",
  @Json(name = "installation_id") val installationId: String = "",
  val status: String = "active",
  @Json(name = "payment_verified") val paymentVerified: Int = 0,
  @Json(name = "payment_reference") val paymentReference: String = "",
  @Json(name = "expires_at") val expiresAt: String = "",
)

data class UpdateItem(
  val id: String = "",
  @Json(name = "app_id") val appId: String = "",
  @Json(name = "latest_version_name") val latestVersionName: String = "",
  @Json(name = "latest_version_code") val latestVersionCode: Int = 1,
  @Json(name = "min_version_code") val minVersionCode: Int = 1,
  @Json(name = "download_url") val downloadUrl: String = "",
  @Json(name = "whats_new") val whatsNew: String = "",
  @Json(name = "update_mode") val updateMode: String = "normal",
  @Json(name = "show_later_button") val showLaterButton: Int = 1,
  @Json(name = "rollout_percentage") val rolloutPercentage: Int = 100,
)

data class DialogItem(
  val id: String = "",
  @Json(name = "app_id") val appId: String? = null,
  @Json(name = "dialog_type") val dialogType: String = "custom_popup",
  val title: String = "",
  val description: String = "",
  val priority: Int = 50,
  @Json(name = "is_enabled") val isEnabled: Int = 1,
)

data class AnnouncementItem(
  val id: String = "",
  val title: String = "",
  val body: String = "",
  @Json(name = "action_label") val actionLabel: String = "",
  @Json(name = "action_url") val actionUrl: String = "",
  val priority: Int = 50,
  @Json(name = "is_pinned") val isPinned: Int = 0,
)

data class RemoteConfigItem(
  val id: String = "",
  val scope: String = "global",
  @Json(name = "app_id") val appId: String? = null,
  val version: Int = 1,
  @Json(name = "feature_flags_json") val featureFlagsJson: String = "{}",
  @Json(name = "remote_text_json") val remoteTextJson: String = "{}",
  @Json(name = "is_active") val isActive: Int = 1,
  @Json(name = "created_at") val createdAt: String = "",
)

data class DynamicModuleItem(
  val id: String = "",
  @Json(name = "module_key") val moduleKey: String = "",
  @Json(name = "capability_type") val capabilityType: String = "",
  val version: String = "1.0.0",
  @Json(name = "config_json") val configJson: String = "{}",
  @Json(name = "is_enabled") val isEnabled: Int = 1,
)

data class MaintenanceItem(
  val id: String = "",
  val scope: String = "global",
  @Json(name = "app_id") val appId: String? = null,
  @Json(name = "is_enabled") val isEnabled: Int = 0,
  @Json(name = "emergency_lockdown") val emergencyLockdown: Int = 0,
  val title: String = "",
  val message: String = "",
)

data class TelegramBotItem(
  val id: String = "",
  val name: String = "",
  @Json(name = "bot_id") val botId: String = "",
  @Json(name = "bot_username") val botUsername: String = "",
  @Json(name = "token_masked") val tokenMasked: String = "",
  @Json(name = "owner_telegram_id") val ownerTelegramId: String = "",
  @Json(name = "webhook_url") val webhookUrl: String = "",
  @Json(name = "welcome_message") val welcomeMessage: String = "",
  @Json(name = "commands_json") val commandsJson: String = "[]",
  @Json(name = "permissions_json") val permissionsJson: String = "[]",
  val status: String = "stopped",
  @Json(name = "updates_processed") val updatesProcessed: Int = 0,
  @Json(name = "updates_rejected") val updatesRejected: Int = 0,
  @Json(name = "updates_deduped") val updatesDeduped: Int = 0,
  @Json(name = "last_error") val lastError: String = "",
  @Json(name = "last_heartbeat_at") val lastHeartbeatAt: String? = null,
)

data class DexReleaseItem(
  val id: String = "",
  @Json(name = "version_name") val versionName: String = "",
  @Json(name = "version_code") val versionCode: Int = 1,
  @Json(name = "file_name") val fileName: String = "PRANAV.dex",
  val sha256: String = "",
  @Json(name = "size_bytes") val sizeBytes: Long = 0,
  @Json(name = "min_sdk") val minSdk: Int = 24,
  @Json(name = "target_sdk") val targetSdk: Int = 36,
  @Json(name = "mt_manager_notes") val mtManagerNotes: String = "",
  @Json(name = "is_active") val isActive: Int = 0,
)

data class HookReleaseItem(
  val id: String = "",
  @Json(name = "version_name") val versionName: String = "",
  @Json(name = "version_code") val versionCode: Int = 1,
  val sha256: String = "",
  @Json(name = "min_sdk_version") val minSdkVersion: String = "1.0.0",
  @Json(name = "lifecycle_hooks_json") val lifecycleHooksJson: String = "[]",
  @Json(name = "is_active") val isActive: Int = 0,
)

data class TableOverviewItem(
  val table: String = "",
  val rowCount: Int = 0,
)

data class DbIntegrityInfo(
  val ok: Boolean = true,
  val integrity: String = "ok",
  val walMode: String = "wal",
  val tableCount: Int = 0,
)

data class DatabaseTablesResponse(
  val tables: List<TableOverviewItem> = emptyList(),
  val integrity: DbIntegrityInfo = DbIntegrityInfo(),
)

data class TableInspectResponse(
  val tableName: String = "",
  val total: Int = 0,
  val rows: List<Map<String, Any?>> = emptyList(),
)

data class AuditLogItem(
  val id: String = "",
  @Json(name = "actor_type") val actorType: String = "",
  @Json(name = "actor_id") val actorId: String = "",
  val module: String = "",
  val action: String = "",
  @Json(name = "target_id") val targetId: String = "",
  @Json(name = "details_json") val detailsJson: String = "{}",
  @Json(name = "created_at") val createdAt: String = "",
)

data class OwnerDeviceItem(
  val id: String = "",
  @Json(name = "device_label") val deviceLabel: String = "",
  @Json(name = "hardware_model") val hardwareModel: String = "",
  @Json(name = "key_fingerprint") val keyFingerprint: String = "",
  val status: String = "active",
  @Json(name = "enrolled_at") val enrolledAt: String = "",
)

data class SecurityOverviewResponse(
  val ownerDevices: List<OwnerDeviceItem> = emptyList(),
  val securityAlerts: List<AuditLogItem> = emptyList(),
)

data class RecoveryTokenResponse(
  val token: String = "",
  val expiresAt: String = "",
)

data class BackupItem(
  val fileName: String = "",
  val sizeBytes: Long = 0,
  val createdAt: String = "",
  val sha256: String = "",
)

data class BackendHealthResponse(
  val status: String = "unknown",
  val uptimeSeconds: Long = 0,
  val nodeVersion: String = "",
  val memory: Map<String, Any?> = emptyMap(),
  val database: Map<String, Any?> = emptyMap(),
)

data class ServerMonitoringResponse(
  val hostname: String = "",
  val platform: String = "",
  val cpuCores: Int = 0,
  val cpuModel: String = "",
  val loadAverage: List<Double> = emptyList(),
  val memory: Map<String, Any?> = emptyMap(),
  val disk: Map<String, Any?> = emptyMap(),
  val services: Map<String, Any?> = emptyMap(),
)

data class ScheduleItem(
  val id: String = "",
  @Json(name = "job_type") val jobType: String = "",
  @Json(name = "target_id") val targetId: String = "",
  @Json(name = "run_at") val runAt: String = "",
  val status: String = "pending",
)

data class SystemSettingItem(
  val key: String = "",
  @Json(name = "value_json") val valueJson: String = "",
  val description: String = "",
  @Json(name = "updated_at") val updatedAt: String = "",
)

data class ItemsWrapper<T>(val items: List<T> = emptyList())
