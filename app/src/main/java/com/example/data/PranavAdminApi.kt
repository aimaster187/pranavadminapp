package com.example.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface PranavAdminApi {
  @GET("api/v1/admin/auth/status")
  suspend fun authStatus(): AuthStatusResponse

  @POST("api/v1/admin/auth/enroll")
  suspend fun enroll(@Body body: EnrollOwnerRequest): EnrollOwnerResponse

  @POST("api/v1/admin/auth/challenge")
  suspend fun challenge(@Body body: ChallengeRequest): ChallengeResponse

  @POST("api/v1/admin/auth/verify")
  suspend fun verify(@Body body: VerifyChallengeRequest): VerifyChallengeResponse

  @GET("api/v1/admin/dashboard")
  suspend fun dashboard(): DashboardResponse

  @GET("api/v1/admin/apps")
  suspend fun apps(): ItemsWrapper<AppItem>

  @POST("api/v1/admin/apps")
  suspend fun createApp(@Body body: Map<String, @JvmSuppressWildcards Any?>): CreateAppResponse

  @PATCH("api/v1/admin/apps/{id}")
  suspend fun updateApp(
    @Path("id") id: String,
    @Body body: Map<String, @JvmSuppressWildcards Any?>,
  ): AppItem

  @DELETE("api/v1/admin/apps/{id}")
  suspend fun deleteApp(@Path("id") id: String): Map<String, Boolean>

  @GET("api/v1/admin/installations")
  suspend fun installations(): ItemsWrapper<InstallationItem>

  @PATCH("api/v1/admin/installations/{id}/status")
  suspend fun setInstallationStatus(
    @Path("id") id: String,
    @Body body: Map<String, String>,
  ): Map<String, Boolean>

  @GET("api/v1/admin/devices")
  suspend fun devices(): ItemsWrapper<DeviceItem>

  @PATCH("api/v1/admin/devices/{id}/approval")
  suspend fun updateDeviceApproval(
    @Path("id") id: String,
    @Body body: Map<String, String>,
  ): Map<String, Boolean>

  @GET("api/v1/admin/licenses")
  suspend fun licenses(): ItemsWrapper<LicenseItem>

  @POST("api/v1/admin/licenses/generate")
  suspend fun generateLicenses(
    @Body body: Map<String, @JvmSuppressWildcards Any?>
  ): ItemsWrapper<Map<String, Any?>>

  @POST("api/v1/admin/licenses/{id}/mutate")
  suspend fun mutateLicense(
    @Path("id") id: String,
    @Body body: Map<String, @JvmSuppressWildcards Any?>,
  ): LicenseItem

  @GET("api/v1/admin/vip/plans")
  suspend fun vipPlans(): ItemsWrapper<VipPlanItem>

  @POST("api/v1/admin/vip/plans")
  suspend fun createVipPlan(@Body body: Map<String, @JvmSuppressWildcards Any?>): VipPlanItem

  @GET("api/v1/admin/vip/subscriptions")
  suspend fun vipSubscriptions(): ItemsWrapper<VipSubscriptionItem>

  @POST("api/v1/admin/vip/subscriptions/activate")
  suspend fun activateVip(
    @Body body: Map<String, @JvmSuppressWildcards Any?>
  ): VipSubscriptionItem

  @POST("api/v1/admin/vip/subscriptions/{id}/revoke")
  suspend fun revokeVip(@Path("id") id: String): Map<String, Boolean>

  @GET("api/v1/admin/updates")
  suspend fun updates(): ItemsWrapper<UpdateItem>

  @POST("api/v1/admin/updates")
  suspend fun createUpdate(@Body body: Map<String, @JvmSuppressWildcards Any?>): UpdateItem

  @GET("api/v1/admin/dialogs")
  suspend fun dialogs(): ItemsWrapper<DialogItem>

  @POST("api/v1/admin/dialogs")
  suspend fun createDialog(@Body body: Map<String, @JvmSuppressWildcards Any?>): DialogItem

  @PATCH("api/v1/admin/dialogs/{id}/toggle")
  suspend fun toggleDialog(
    @Path("id") id: String,
    @Body body: Map<String, Boolean>,
  ): Map<String, Boolean>

  @GET("api/v1/admin/announcements")
  suspend fun announcements(): ItemsWrapper<AnnouncementItem>

  @POST("api/v1/admin/announcements")
  suspend fun createAnnouncement(
    @Body body: Map<String, @JvmSuppressWildcards Any?>
  ): AnnouncementItem

  @GET("api/v1/admin/remote-configs")
  suspend fun remoteConfigs(): ItemsWrapper<RemoteConfigItem>

  @POST("api/v1/admin/remote-configs")
  suspend fun publishRemoteConfig(
    @Body body: Map<String, @JvmSuppressWildcards Any?>
  ): RemoteConfigItem

  @POST("api/v1/admin/remote-configs/{id}/rollback")
  suspend fun rollbackRemoteConfig(@Path("id") id: String): Map<String, Boolean>

  @GET("api/v1/admin/dynamic-modules")
  suspend fun dynamicModules(): ItemsWrapper<DynamicModuleItem>

  @POST("api/v1/admin/dynamic-modules")
  suspend fun upsertDynamicModule(
    @Body body: Map<String, @JvmSuppressWildcards Any?>
  ): DynamicModuleItem

  @GET("api/v1/admin/maintenance")
  suspend fun maintenance(): ItemsWrapper<MaintenanceItem>

  @POST("api/v1/admin/maintenance")
  suspend fun setMaintenance(@Body body: Map<String, @JvmSuppressWildcards Any?>): MaintenanceItem

  @GET("api/v1/admin/telegram-bots")
  suspend fun telegramBots(): ItemsWrapper<TelegramBotItem>

  @POST("api/v1/admin/telegram-bots")
  suspend fun addTelegramBot(
    @Body body: Map<String, @JvmSuppressWildcards Any?>
  ): TelegramBotItem

  @PATCH("api/v1/admin/telegram-bots/{id}")
  suspend fun configureTelegramBot(
    @Path("id") id: String,
    @Body body: Map<String, @JvmSuppressWildcards Any?>,
  ): TelegramBotItem

  @POST("api/v1/admin/telegram-bots/{id}/start")
  suspend fun startTelegramBot(@Path("id") id: String): TelegramBotItem

  @POST("api/v1/admin/telegram-bots/{id}/stop")
  suspend fun stopTelegramBot(@Path("id") id: String): TelegramBotItem

  @POST("api/v1/admin/telegram-bots/{id}/restart")
  suspend fun restartTelegramBot(@Path("id") id: String): TelegramBotItem

  @GET("api/v1/admin/telegram-bots/{id}/health")
  suspend fun refreshTelegramBotHealth(@Path("id") id: String): TelegramBotItem

  @DELETE("api/v1/admin/telegram-bots/{id}")
  suspend fun removeTelegramBot(@Path("id") id: String): Map<String, Boolean>

  @GET("api/v1/admin/dex-releases")
  suspend fun dexReleases(): ItemsWrapper<DexReleaseItem>

  @POST("api/v1/admin/dex-releases")
  suspend fun registerDexRelease(
    @Body body: Map<String, @JvmSuppressWildcards Any?>
  ): DexReleaseItem

  @POST("api/v1/admin/dex-releases/{id}/activate")
  suspend fun activateDexRelease(@Path("id") id: String): Map<String, Boolean>

  @GET("api/v1/admin/hook-releases")
  suspend fun hookReleases(): ItemsWrapper<HookReleaseItem>

  @POST("api/v1/admin/hook-releases")
  suspend fun registerHookRelease(
    @Body body: Map<String, @JvmSuppressWildcards Any?>
  ): HookReleaseItem

  @POST("api/v1/admin/hook-releases/{id}/activate")
  suspend fun activateHookRelease(@Path("id") id: String): Map<String, Boolean>

  @GET("api/v1/admin/database/tables")
  suspend fun databaseTables(): DatabaseTablesResponse

  @GET("api/v1/admin/database/tables/{table}")
  suspend fun inspectTable(
    @Path("table") table: String,
    @Query("limit") limit: Int = 25,
  ): TableInspectResponse

  @GET("api/v1/admin/audit-logs")
  suspend fun auditLogs(@Query("limit") limit: Int = 100): ItemsWrapper<AuditLogItem>

  @GET("api/v1/admin/security")
  suspend fun securityOverview(): SecurityOverviewResponse

  @POST("api/v1/admin/security/recovery-token")
  suspend fun issueRecoveryToken(): RecoveryTokenResponse

  @POST("api/v1/admin/security/owner-devices/{id}/revoke")
  suspend fun revokeOwnerDevice(@Path("id") id: String): Map<String, Boolean>

  @GET("api/v1/admin/backups")
  suspend fun backups(): ItemsWrapper<BackupItem>

  @POST("api/v1/admin/backups")
  suspend fun triggerBackup(@Body body: Map<String, String>): BackupItem

  @POST("api/v1/admin/backups/{fileName}/verify")
  suspend fun verifyBackup(@Path("fileName") fileName: String): Map<String, Any?>

  @POST("api/v1/admin/backups/{fileName}/restore")
  suspend fun restoreBackup(@Path("fileName") fileName: String): Map<String, Any?>

  @GET("api/v1/admin/health")
  suspend fun backendHealth(): BackendHealthResponse

  @GET("api/v1/admin/server")
  suspend fun serverMonitoring(): ServerMonitoringResponse

  @GET("api/v1/admin/schedules")
  suspend fun schedules(): ItemsWrapper<ScheduleItem>

  @POST("api/v1/admin/schedules")
  suspend fun createSchedule(@Body body: Map<String, @JvmSuppressWildcards Any?>): ScheduleItem

  @POST("api/v1/admin/schedules/{id}/cancel")
  suspend fun cancelSchedule(@Path("id") id: String): Map<String, Boolean>

  @GET("api/v1/admin/settings")
  suspend fun settings(): ItemsWrapper<SystemSettingItem>

  @PUT("api/v1/admin/settings/{key}")
  suspend fun updateSetting(
    @Path("key") key: String,
    @Body body: Map<String, @JvmSuppressWildcards Any?>,
  ): SystemSettingItem
}

class DynamicApiEndpointHolder(initialBaseUrl: String) {
  @Volatile var baseUrl: String = initialBaseUrl.trim().trimEnd('/')
  @Volatile var bearerToken: String = ""
}

object PranavApiFactory {
  fun create(holder: DynamicApiEndpointHolder): PranavAdminApi {
    val dynamicInterceptor = Interceptor { chain ->
      val original = chain.request()
      val targetBase = holder.baseUrl.toHttpUrlOrNull()
      val newUrl =
        if (targetBase != null) {
          original.url
            .newBuilder()
            .scheme(targetBase.scheme)
            .host(targetBase.host)
            .port(targetBase.port)
            .build()
        } else {
          original.url
        }

      val reqBuilder = original.newBuilder().url(newUrl)
      val token = holder.bearerToken
      if (token.isNotBlank() && !original.url.encodedPath.contains("/auth/")) {
        reqBuilder.header("Authorization", "Bearer $token")
      }
      chain.proceed(reqBuilder.build())
    }

    val client =
      OkHttpClient.Builder()
        .addInterceptor(dynamicInterceptor)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    return Retrofit.Builder()
      .baseUrl("http://127.0.0.1:8443/")
      .client(client)
      .addConverterFactory(MoshiConverterFactory.create(moshi))
      .build()
      .create(PranavAdminApi::class.java)
  }
}
