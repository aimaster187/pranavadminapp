package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AdminHub(val title: String) {
  OVERVIEW("Overview"),
  APPS("Apps & Devices"),
  LICENSES("Licenses & VIP"),
  DELIVERY("Control & Comms"),
  OPERATIONS("Ops & Bots"),
}

enum class AdminModule(val number: Int, val title: String, val hub: AdminHub) {
  DASHBOARD(1, "Dashboard & Analytics", AdminHub.OVERVIEW),
  APP_MANAGER(2, "App Manager", AdminHub.APPS),
  USER_MANAGER(3, "Installation Manager", AdminHub.APPS),
  DEVICE_MANAGER(4, "Device Manager", AdminHub.APPS),
  LICENSE_MANAGER(5, "License Manager", AdminHub.LICENSES),
  VIP_MANAGER(6, "VIP Manager", AdminHub.LICENSES),
  UPDATE_MANAGER(7, "Update Manager", AdminHub.DELIVERY),
  DIALOG_MANAGER(8, "Universal Dialog Manager", AdminHub.DELIVERY),
  ANNOUNCEMENT_MANAGER(9, "Announcement Manager", AdminHub.DELIVERY),
  REMOTE_CONFIG(10, "Remote Config Manager", AdminHub.DELIVERY),
  DYNAMIC_MODULES(11, "Dynamic Module Registry", AdminHub.DELIVERY),
  MAINTENANCE(12, "Maintenance Manager", AdminHub.OPERATIONS),
  EMERGENCY_LOCKDOWN(13, "Emergency Lockdown", AdminHub.OPERATIONS),
  TELEGRAM_BOTS(14, "Telegram Bot Manager", AdminHub.OPERATIONS),
  DEX_RELEASES(15, "DEX Release Manager", AdminHub.OPERATIONS),
  HOOK_RELEASES(16, "Hook Release Manager", AdminHub.OPERATIONS),
  DATABASE_EXPLORER(17, "Database Explorer", AdminHub.OPERATIONS),
  AUDIT_LOGS(18, "Audit Logs", AdminHub.OPERATIONS),
  SECURITY_CENTER(19, "Security Center", AdminHub.OPERATIONS),
  BACKUP_RESTORE(20, "Backup & Restore", AdminHub.OPERATIONS),
  BACKEND_HEALTH(21, "Backend Health", AdminHub.OPERATIONS),
  SERVER_MONITORING(22, "Server Monitoring", AdminHub.OPERATIONS),
  SCHEDULER(23, "Configuration Scheduler", AdminHub.OPERATIONS),
  SYSTEM_SETTINGS(24, "System Settings", AdminHub.OPERATIONS),
}

sealed interface AuthState {
  data object Checking : AuthState
  data class Unverified(
    val apiBaseUrl: String,
    val keyFingerprint: String,
    val hardwareLabel: String,
    val reason: String,
  ) : AuthState
  data class Authenticated(val ownerDeviceId: String) : AuthState
}

data class AdminUiState(
  val authState: AuthState = AuthState.Checking,
  val activeModule: AdminModule = AdminModule.DASHBOARD,
  val darkTheme: Boolean = false,
  val isLoading: Boolean = false,
  val bannerMessage: String? = null,
  val errorMessage: String? = null,
  val dashboard: DashboardResponse = DashboardResponse(),
  val apps: List<AppItem> = emptyList(),
  val lastIssuedAppKey: String? = null,
  val installations: List<InstallationItem> = emptyList(),
  val devices: List<DeviceItem> = emptyList(),
  val licenses: List<LicenseItem> = emptyList(),
  val vipPlans: List<VipPlanItem> = emptyList(),
  val vipSubscriptions: List<VipSubscriptionItem> = emptyList(),
  val updates: List<UpdateItem> = emptyList(),
  val dialogs: List<DialogItem> = emptyList(),
  val announcements: List<AnnouncementItem> = emptyList(),
  val remoteConfigs: List<RemoteConfigItem> = emptyList(),
  val dynamicModules: List<DynamicModuleItem> = emptyList(),
  val maintenance: List<MaintenanceItem> = emptyList(),
  val telegramBots: List<TelegramBotItem> = emptyList(),
  val dexReleases: List<DexReleaseItem> = emptyList(),
  val hookReleases: List<HookReleaseItem> = emptyList(),
  val dbTables: DatabaseTablesResponse = DatabaseTablesResponse(),
  val inspectedTable: TableInspectResponse? = null,
  val auditLogs: List<AuditLogItem> = emptyList(),
  val securityOverview: SecurityOverviewResponse = SecurityOverviewResponse(),
  val recoveryToken: RecoveryTokenResponse? = null,
  val backups: List<BackupItem> = emptyList(),
  val backendHealth: BackendHealthResponse = BackendHealthResponse(),
  val serverMonitoring: ServerMonitoringResponse = ServerMonitoringResponse(),
  val schedules: List<ScheduleItem> = emptyList(),
  val settings: List<SystemSettingItem> = emptyList(),
)

class AdminViewModel(private val repo: PranavAdminRepository) : ViewModel() {
  private val _uiState = MutableStateFlow(AdminUiState())
  val uiState: StateFlow<AdminUiState> = _uiState.asStateFlow()

  init {
    viewModelScope.launch {
      val creds = repo.vault.loadCredentials()
      _uiState.update { it.copy(darkTheme = creds.darkTheme) }
      runSilentAuth()
    }
  }

  fun runSilentAuth() {
    viewModelScope.launch {
      _uiState.update { it.copy(authState = AuthState.Checking, errorMessage = null) }
      val creds = repo.vault.loadCredentials()
      when (val res = repo.performSilentAuth()) {
        is SilentAuthOutcome.Verified -> {
          _uiState.update { it.copy(authState = AuthState.Authenticated(res.ownerDeviceId)) }
          loadModuleData(_uiState.value.activeModule)
        }
        is SilentAuthOutcome.EnrollmentRequired -> {
          _uiState.update {
            it.copy(
              authState =
                AuthState.Unverified(
                  apiBaseUrl = creds.apiBaseUrl,
                  keyFingerprint = res.keyFingerprint,
                  hardwareLabel = res.hardwareLabel,
                  reason = res.reason,
                )
            )
          }
        }
      }
    }
  }

  fun enrollDevice(apiBaseUrl: String, enrollmentToken: String, deviceLabel: String) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null, bannerMessage = null) }
      repo
        .enrollDevice(apiBaseUrl, enrollmentToken, deviceLabel)
        .onSuccess { deviceId ->
          _uiState.update {
            it.copy(
              isLoading = false,
              authState = AuthState.Authenticated(deviceId),
              bannerMessage = "Owner device enrolled via Android Keystore.",
            )
          }
          loadModuleData(AdminModule.DASHBOARD)
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(isLoading = false, errorMessage = err.message ?: "Enrollment failed.")
          }
        }
    }
  }

  fun selectModule(module: AdminModule) {
    _uiState.update { it.copy(activeModule = module, bannerMessage = null, errorMessage = null) }
    if (_uiState.value.authState is AuthState.Authenticated) {
      loadModuleData(module)
    }
  }

  fun toggleDarkTheme() {
    val next = !_uiState.value.darkTheme
    _uiState.update { it.copy(darkTheme = next) }
    viewModelScope.launch { repo.vault.setDarkTheme(next) }
  }

  fun clearMessages() {
    _uiState.update { it.copy(bannerMessage = null, errorMessage = null) }
  }

  fun loadModuleData(module: AdminModule = _uiState.value.activeModule) {
    if (_uiState.value.authState !is AuthState.Authenticated) return
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null) }
      runCatching {
        val api = repo.api
        when (module) {
          AdminModule.DASHBOARD -> {
            val d = api.dashboard()
            _uiState.update { it.copy(dashboard = d) }
          }
          AdminModule.APP_MANAGER -> {
            val res = api.apps()
            _uiState.update { it.copy(apps = res.items) }
          }
          AdminModule.USER_MANAGER -> {
            val res = api.installations()
            _uiState.update { it.copy(installations = res.items) }
          }
          AdminModule.DEVICE_MANAGER -> {
            val res = api.devices()
            _uiState.update { it.copy(devices = res.items) }
          }
          AdminModule.LICENSE_MANAGER -> {
            val lics = api.licenses().items
            val apps = api.apps().items
            _uiState.update { it.copy(licenses = lics, apps = apps) }
          }
          AdminModule.VIP_MANAGER -> {
            val plans = api.vipPlans().items
            val subs = api.vipSubscriptions().items
            val installs = api.installations().items
            _uiState.update {
              it.copy(vipPlans = plans, vipSubscriptions = subs, installations = installs)
            }
          }
          AdminModule.UPDATE_MANAGER -> {
            val upds = api.updates().items
            val apps = api.apps().items
            _uiState.update { it.copy(updates = upds, apps = apps) }
          }
          AdminModule.DIALOG_MANAGER -> {
            val dlgs = api.dialogs().items
            val apps = api.apps().items
            _uiState.update { it.copy(dialogs = dlgs, apps = apps) }
          }
          AdminModule.ANNOUNCEMENT_MANAGER -> {
            val anns = api.announcements().items
            _uiState.update { it.copy(announcements = anns) }
          }
          AdminModule.REMOTE_CONFIG -> {
            val cfgs = api.remoteConfigs().items
            val apps = api.apps().items
            _uiState.update { it.copy(remoteConfigs = cfgs, apps = apps) }
          }
          AdminModule.DYNAMIC_MODULES -> {
            val mods = api.dynamicModules().items
            _uiState.update { it.copy(dynamicModules = mods) }
          }
          AdminModule.MAINTENANCE,
          AdminModule.EMERGENCY_LOCKDOWN -> {
            val m = api.maintenance().items
            _uiState.update { it.copy(maintenance = m) }
          }
          AdminModule.TELEGRAM_BOTS -> {
            val bots = api.telegramBots().items
            _uiState.update { it.copy(telegramBots = bots) }
          }
          AdminModule.DEX_RELEASES -> {
            val dex = api.dexReleases().items
            _uiState.update { it.copy(dexReleases = dex) }
          }
          AdminModule.HOOK_RELEASES -> {
            val hooks = api.hookReleases().items
            _uiState.update { it.copy(hookReleases = hooks) }
          }
          AdminModule.DATABASE_EXPLORER -> {
            val tbls = api.databaseTables()
            _uiState.update { it.copy(dbTables = tbls) }
          }
          AdminModule.AUDIT_LOGS -> {
            val logs = api.auditLogs().items
            _uiState.update { it.copy(auditLogs = logs) }
          }
          AdminModule.SECURITY_CENTER -> {
            val sec = api.securityOverview()
            _uiState.update { it.copy(securityOverview = sec) }
          }
          AdminModule.BACKUP_RESTORE -> {
            val bks = api.backups().items
            _uiState.update { it.copy(backups = bks) }
          }
          AdminModule.BACKEND_HEALTH -> {
            val h = api.backendHealth()
            _uiState.update { it.copy(backendHealth = h) }
          }
          AdminModule.SERVER_MONITORING -> {
            val s = api.serverMonitoring()
            _uiState.update { it.copy(serverMonitoring = s) }
          }
          AdminModule.SCHEDULER -> {
            val sch = api.schedules().items
            _uiState.update { it.copy(schedules = sch) }
          }
          AdminModule.SYSTEM_SETTINGS -> {
            val st = api.settings().items
            _uiState.update { it.copy(settings = st) }
          }
        }
      }.onFailure { err ->
        _uiState.update { it.copy(errorMessage = err.message ?: "Request failed") }
      }
      _uiState.update { it.copy(isLoading = false) }
    }
  }

  private fun execAndRefresh(
    module: AdminModule,
    successMsg: String,
    block: suspend () -> Unit,
  ) {
    viewModelScope.launch {
      _uiState.update { it.copy(isLoading = true, errorMessage = null) }
      runCatching { block() }
        .onSuccess {
          _uiState.update { it.copy(bannerMessage = successMsg) }
          loadModuleData(module)
        }
        .onFailure { err ->
          _uiState.update {
            it.copy(isLoading = false, errorMessage = err.message ?: "Operation failed")
          }
        }
    }
  }

  // --- Apps, Installations & Devices Actions ---
  fun createApp(name: String, packageName: String, approvalPolicy: String) =
    execAndRefresh(AdminModule.APP_MANAGER, "App registered.") {
      val res =
        repo.api.createApp(
          mapOf("name" to name, "packageName" to packageName, "approvalPolicy" to approvalPolicy)
        )
      _uiState.update { it.copy(lastIssuedAppKey = res.issuedAppKey) }
    }

  fun setAppStatus(id: String, status: String) =
    execAndRefresh(AdminModule.APP_MANAGER, "App status updated.") {
      repo.api.updateApp(id, mapOf("status" to status))
    }

  fun deleteApp(id: String) =
    execAndRefresh(AdminModule.APP_MANAGER, "App deleted.") { repo.api.deleteApp(id) }

  fun setInstallationStatus(id: String, status: String) =
    execAndRefresh(AdminModule.USER_MANAGER, "Installation $status.") {
      repo.api.setInstallationStatus(id, mapOf("status" to status))
    }

  fun setDeviceApproval(id: String, approvalStatus: String) =
    execAndRefresh(AdminModule.DEVICE_MANAGER, "Device marked $approvalStatus.") {
      repo.api.updateDeviceApproval(id, mapOf("approvalStatus" to approvalStatus))
    }

  // --- License & VIP Actions ---
  fun generateLicenses(count: Int, scope: String, appId: String?, durationDays: Int) =
    execAndRefresh(AdminModule.LICENSE_MANAGER, "Generated $count license(s).") {
      repo.api.generateLicenses(
        mapOf(
          "count" to count,
          "scope" to scope,
          "appId" to appId,
          "durationDays" to durationDays,
        )
      )
    }

  fun mutateLicense(id: String, action: String, renewDays: Int = 30) =
    execAndRefresh(AdminModule.LICENSE_MANAGER, "License $action completed.") {
      repo.api.mutateLicense(id, mapOf("action" to action, "renewDays" to renewDays))
    }

  fun createVipPlan(code: String, name: String, durationDays: Int, maxDevices: Int, price: String) =
    execAndRefresh(AdminModule.VIP_MANAGER, "VIP plan created.") {
      repo.api.createVipPlan(
        mapOf(
          "code" to code,
          "name" to name,
          "durationDays" to durationDays,
          "maxDevices" to maxDevices,
          "priceLabel" to price,
        )
      )
    }

  fun activateVip(planId: String, installationId: String, paymentRef: String) =
    execAndRefresh(AdminModule.VIP_MANAGER, "VIP activated with server payment verification.") {
      repo.api.activateVip(
        mapOf(
          "planId" to planId,
          "installationId" to installationId,
          "paymentVerified" to true,
          "paymentReference" to paymentRef,
        )
      )
    }

  fun revokeVip(id: String) =
    execAndRefresh(AdminModule.VIP_MANAGER, "VIP subscription revoked.") { repo.api.revokeVip(id) }

  // --- Updates, Dialogs, Announcements, Remote Config, Dynamic Modules ---
  fun createUpdate(
    appId: String,
    versionName: String,
    versionCode: Int,
    minVersionCode: Int,
    downloadUrl: String,
    whatsNew: String,
    forceUpdate: Boolean,
    showLater: Boolean,
  ) =
    execAndRefresh(AdminModule.UPDATE_MANAGER, "Update release published.") {
      repo.api.createUpdate(
        mapOf(
          "appId" to appId,
          "latestVersionName" to versionName,
          "latestVersionCode" to versionCode,
          "minVersionCode" to minVersionCode,
          "downloadUrl" to downloadUrl,
          "whatsNew" to whatsNew,
          "updateMode" to if (forceUpdate) "force" else "normal",
          "showLaterButton" to showLater,
          "rolloutPercentage" to 100,
        )
      )
    }

  fun createDialog(
    dialogType: String,
    title: String,
    description: String,
    actionLabel: String,
    actionUrl: String,
    priority: Int,
  ) =
    execAndRefresh(AdminModule.DIALOG_MANAGER, "Universal dialog published.") {
      val buttons =
        if (actionLabel.isNotBlank()) {
          listOf(
            mapOf(
              "label" to actionLabel,
              "actionType" to if (actionUrl.isNotBlank()) "url" else "dismiss",
              "actionValue" to actionUrl,
            )
          )
        } else {
          emptyList()
        }
      repo.api.createDialog(
        mapOf(
          "dialogType" to dialogType,
          "title" to title,
          "description" to description,
          "buttons" to buttons,
          "priority" to priority,
          "isEnabled" to true,
        )
      )
    }

  fun toggleDialog(id: String, enabled: Boolean) =
    execAndRefresh(AdminModule.DIALOG_MANAGER, "Dialog state updated.") {
      repo.api.toggleDialog(id, mapOf("enabled" to enabled))
    }

  fun createAnnouncement(title: String, body: String, pinned: Boolean) =
    execAndRefresh(AdminModule.ANNOUNCEMENT_MANAGER, "Announcement published.") {
      repo.api.createAnnouncement(mapOf("title" to title, "body" to body, "isPinned" to pinned))
    }

  fun publishRemoteConfig(scope: String, appId: String?, flagKey: String, flagVal: Boolean, bannerText: String) =
    execAndRefresh(AdminModule.REMOTE_CONFIG, "Remote config version published.") {
      repo.api.publishRemoteConfig(
        mapOf(
          "scope" to scope,
          "appId" to appId,
          "featureFlags" to if (flagKey.isNotBlank()) mapOf(flagKey to flagVal) else emptyMap<String, Boolean>(),
          "remoteText" to if (bannerText.isNotBlank()) mapOf("notice" to bannerText) else emptyMap<String, String>(),
          "remoteLinks" to emptyMap<String, String>(),
          "dynamicSettings" to emptyMap<String, Any>(),
        )
      )
    }

  fun rollbackRemoteConfig(id: String) =
    execAndRefresh(AdminModule.REMOTE_CONFIG, "Rolled back remote configuration.") {
      repo.api.rollbackRemoteConfig(id)
    }

  fun upsertDynamicModule(moduleKey: String, capabilityType: String, version: String) =
    execAndRefresh(AdminModule.DYNAMIC_MODULES, "Declarative module registered.") {
      repo.api.upsertDynamicModule(
        mapOf(
          "moduleKey" to moduleKey,
          "capabilityType" to capabilityType,
          "version" to version,
          "isEnabled" to true,
        )
      )
    }

  // --- Maintenance & Emergency Lockdown ---
  fun setMaintenance(isEnabled: Boolean, lockdown: Boolean, title: String, message: String) =
    execAndRefresh(
      if (lockdown) AdminModule.EMERGENCY_LOCKDOWN else AdminModule.MAINTENANCE,
      if (lockdown) "Emergency Lockdown state updated." else "Maintenance configuration saved.",
    ) {
      repo.api.setMaintenance(
        mapOf(
          "scope" to "global",
          "isEnabled" to isEnabled,
          "emergencyLockdown" to lockdown,
          "title" to title,
          "message" to message,
        )
      )
    }

  // --- Multi-Bot Encrypted Telegram Bot Manager ---
  fun addTelegramBot(
    name: String,
    botFatherToken: String,
    ownerTelegramId: String,
    webhookBaseUrl: String,
    welcomeMessage: String,
  ) =
    execAndRefresh(AdminModule.TELEGRAM_BOTS, "Bot verified, AES-256-GCM encrypted & stored.") {
      repo.api.addTelegramBot(
        mapOf(
          "name" to name,
          "botFatherToken" to botFatherToken,
          "ownerTelegramId" to ownerTelegramId,
          "webhookBaseUrl" to webhookBaseUrl.ifBlank { null },
          "welcomeMessage" to welcomeMessage.ifBlank { "Welcome to PRANAV RANGIN." },
          "autoStart" to true,
        )
      )
    }

  fun configureTelegramBot(
    id: String,
    ownerTelegramId: String,
    welcomeMessage: String,
    webhookUrl: String,
    rotateSecret: Boolean,
  ) =
    execAndRefresh(AdminModule.TELEGRAM_BOTS, "Bot configuration updated.") {
      repo.api.configureTelegramBot(
        id,
        mapOf(
          "ownerTelegramId" to ownerTelegramId.ifBlank { null },
          "welcomeMessage" to welcomeMessage.ifBlank { null },
          "webhookUrl" to webhookUrl.ifBlank { null },
          "rotateWebhookSecret" to rotateSecret,
        ),
      )
    }

  fun botLifecycleAction(id: String, action: String) =
    execAndRefresh(AdminModule.TELEGRAM_BOTS, "Bot $action executed.") {
      when (action) {
        "start" -> repo.api.startTelegramBot(id)
        "stop" -> repo.api.stopTelegramBot(id)
        "restart" -> repo.api.restartTelegramBot(id)
        "health" -> repo.api.refreshTelegramBotHealth(id)
        "remove" -> repo.api.removeTelegramBot(id)
      }
    }

  // --- DEX & Hook Releases ---
  fun registerDexRelease(versionName: String, versionCode: Int, sha256: String, sizeBytes: Long, notes: String) =
    execAndRefresh(AdminModule.DEX_RELEASES, "PRANAV.dex release registered.") {
      repo.api.registerDexRelease(
        mapOf(
          "versionName" to versionName,
          "versionCode" to versionCode,
          "sha256" to sha256,
          "sizeBytes" to sizeBytes,
          "minSdk" to 24,
          "targetSdk" to 36,
          "mtManagerNotes" to notes,
          "activate" to true,
        )
      )
    }

  fun activateDexRelease(id: String) =
    execAndRefresh(AdminModule.DEX_RELEASES, "Activated DEX release.") {
      repo.api.activateDexRelease(id)
    }

  fun registerHookRelease(versionName: String, versionCode: Int, sha256: String) =
    execAndRefresh(AdminModule.HOOK_RELEASES, "PRANAV Hook release registered.") {
      repo.api.registerHookRelease(
        mapOf(
          "versionName" to versionName,
          "versionCode" to versionCode,
          "sha256" to sha256,
          "minSdkVersion" to "1.0.0",
          "lifecycleHooks" to listOf("Application.onCreate", "Activity.onResume", "Activity.onDestroy"),
          "activate" to true,
        )
      )
    }

  fun activateHookRelease(id: String) =
    execAndRefresh(AdminModule.HOOK_RELEASES, "Activated Hook release.") {
      repo.api.activateHookRelease(id)
    }

  // --- DB Explorer, Security, Backups, Scheduler, Settings ---
  fun inspectTable(table: String) {
    viewModelScope.launch {
      runCatching { repo.api.inspectTable(table) }
        .onSuccess { res -> _uiState.update { it.copy(inspectedTable = res) } }
        .onFailure { err -> _uiState.update { it.copy(errorMessage = err.message) } }
    }
  }

  fun issueRecoveryToken() {
    viewModelScope.launch {
      runCatching { repo.api.issueRecoveryToken() }
        .onSuccess { tok ->
          _uiState.update {
            it.copy(recoveryToken = tok, bannerMessage = "10-minute recovery token issued.")
          }
        }
        .onFailure { err -> _uiState.update { it.copy(errorMessage = err.message) } }
    }
  }

  fun revokeOwnerDevice(id: String) =
    execAndRefresh(AdminModule.SECURITY_CENTER, "Owner device revoked.") {
      repo.api.revokeOwnerDevice(id)
    }

  fun triggerBackup(label: String) =
    execAndRefresh(AdminModule.BACKUP_RESTORE, "Verified hot backup created.") {
      repo.api.triggerBackup(mapOf("label" to label.ifBlank { "admin_manual" }))
    }

  fun verifyBackup(fileName: String) {
    viewModelScope.launch {
      runCatching { repo.api.verifyBackup(fileName) }
        .onSuccess { res ->
          val ok = res["ok"] == true
          _uiState.update {
            it.copy(
              bannerMessage =
                if (ok) "Backup $fileName passed SHA-256 & integrity check."
                else "Verification failed: ${res["error"]}"
            )
          }
        }
        .onFailure { err -> _uiState.update { it.copy(errorMessage = err.message) } }
    }
  }

  fun restoreBackup(fileName: String) =
    execAndRefresh(AdminModule.BACKUP_RESTORE, "Database restored from $fileName.") {
      repo.api.restoreBackup(fileName)
    }

  fun createSchedule(jobType: String, targetId: String, runAtIso: String) =
    execAndRefresh(AdminModule.SCHEDULER, "Scheduled job queued.") {
      repo.api.createSchedule(
        mapOf("jobType" to jobType, "targetId" to targetId, "runAt" to runAtIso)
      )
    }

  fun cancelSchedule(id: String) =
    execAndRefresh(AdminModule.SCHEDULER, "Scheduled job cancelled.") {
      repo.api.cancelSchedule(id)
    }

  fun updateSetting(key: String, rawValue: String) =
    execAndRefresh(AdminModule.SYSTEM_SETTINGS, "Setting $key saved.") {
      val parsed: Any = rawValue.toIntOrNull() ?: rawValue.toBooleanStrictOrNull() ?: rawValue
      repo.api.updateSetting(key, mapOf("value" to parsed))
    }

  class Factory(private val repo: PranavAdminRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      return AdminViewModel(repo) as T
    }
  }
}
