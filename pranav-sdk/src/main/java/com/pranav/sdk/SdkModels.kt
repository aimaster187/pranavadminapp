package com.pranav.sdk

enum class SdkConnectionState {
  UNINITIALIZED,
  INITIALIZED,
  CONNECTING,
  CONNECTED,
  OFFLINE_CACHED,
  MAINTENANCE_BLOCKED,
  LOCKDOWN_BLOCKED,
  ERROR,
  SHUTDOWN,
}

data class SdkStatus(
  val state: SdkConnectionState = SdkConnectionState.UNINITIALIZED,
  val installationId: String = "",
  val deviceId: String = "",
  val isLicensed: Boolean = false,
  val isVip: Boolean = false,
  val lastSyncIso: String = "",
  val errorMessage: String? = null,
)

data class DialogButtonAction(
  val label: String,
  val actionType: String,
  val actionValue: String,
)

data class UniversalDialogPayload(
  val id: String,
  val dialogType: String,
  val title: String,
  val description: String,
  val imageUrl: String = "",
  val buttons: List<DialogButtonAction> = emptyList(),
  val priority: Int = 50,
)

data class UpdatePayload(
  val id: String,
  val latestVersionName: String,
  val latestVersionCode: Int,
  val minVersionCode: Int,
  val downloadUrl: String,
  val whatsNew: String,
  val isForce: Boolean,
  val showLaterButton: Boolean,
)

data class AnnouncementPayload(
  val id: String,
  val title: String,
  val body: String,
  val actionLabel: String = "",
  val actionUrl: String = "",
  val isPinned: Boolean = false,
)

data class RemoteConfigPayload(
  val featureFlags: Map<String, Boolean> = emptyMap(),
  val remoteText: Map<String, String> = emptyMap(),
  val remoteLinks: Map<String, String> = emptyMap(),
  val dynamicSettings: Map<String, Any?> = emptyMap(),
)

data class DeclarativeModulePayload(
  val id: String,
  val moduleKey: String,
  val capabilityType: String,
  val version: String,
  val configJson: String,
)

data class SdkBootstrapSnapshot(
  val maintenanceActive: Boolean = false,
  val maintenanceTitle: String = "",
  val maintenanceMessage: String = "",
  val update: UpdatePayload? = null,
  val dialogs: List<UniversalDialogPayload> = emptyList(),
  val announcements: List<AnnouncementPayload> = emptyList(),
  val remoteConfig: RemoteConfigPayload = RemoteConfigPayload(),
  val dynamicModules: List<DeclarativeModulePayload> = emptyList(),
  val activeLicenseKey: String? = null,
  val vipPlanCode: String? = null,
)
