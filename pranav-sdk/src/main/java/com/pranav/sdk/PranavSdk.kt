package com.pranav.sdk

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

object PranavSdk {
  private var appContext: Context? = null
  private var config: PranavConfiguration? = null
  private var identity: ClientCryptoIdentity? = null
  private var httpClient: OkHttpClient? = null
  private var scope: CoroutineScope? = null
  private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
  private val jsonMedia = "application/json; charset=utf-8".toMediaType()

  private val _status = MutableStateFlow(SdkStatus())
  val statusFlow: StateFlow<SdkStatus> = _status.asStateFlow()

  private val _snapshot = MutableStateFlow(SdkBootstrapSnapshot())
  val snapshotFlow: StateFlow<SdkBootstrapSnapshot> = _snapshot.asStateFlow()

  @Synchronized
  fun initialize(context: Context, configuration: PranavConfiguration) {
    runCatching {
      val appCtx = context.applicationContext
      appContext = appCtx
      config = configuration
      identity = ClientCryptoIdentity(appCtx, configuration.appId)
      httpClient =
        OkHttpClient.Builder()
          .connectTimeout(configuration.connectTimeoutMs, TimeUnit.MILLISECONDS)
          .readTimeout(configuration.connectTimeoutMs, TimeUnit.MILLISECONDS)
          .build()
      scope?.cancel()
      scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

      loadCachedSnapshot()
      val (instId, devId, _) = identity!!.getIds()
      _status.value =
        SdkStatus(
          state = SdkConnectionState.INITIALIZED,
          installationId = instId,
          deviceId = devId,
        )
    }.onFailure { err ->
      // Crash-safe isolation: never crash host application if initialization fails
      _status.value =
        SdkStatus(state = SdkConnectionState.ERROR, errorMessage = err.message ?: "Init failed")
    }
  }

  fun getStatus(): SdkStatus = _status.value

  suspend fun connect(): SdkStatus =
    withContext(Dispatchers.IO) {
      val cfg = config ?: return@withContext _status.value
      val iden = identity ?: return@withContext _status.value

      _status.value = _status.value.copy(state = SdkConnectionState.CONNECTING, errorMessage = null)

      var attempt = 0
      var lastErr: Throwable? = null
      while (attempt <= cfg.maxRetries) {
        try {
          val regResult = ensureRegisteredAndAuthenticated(cfg, iden)
          val snapshot = fetchBootstrap(cfg, iden)
          _snapshot.value = snapshot
          saveCachedSnapshot(snapshot)

          val nextState =
            if (snapshot.maintenanceActive) SdkConnectionState.MAINTENANCE_BLOCKED
            else SdkConnectionState.CONNECTED

          val nextStatus =
            SdkStatus(
              state = nextState,
              installationId = regResult.first,
              deviceId = regResult.second,
              isLicensed = !snapshot.activeLicenseKey.isNullOrBlank(),
              isVip = !snapshot.vipPlanCode.isNullOrBlank(),
              lastSyncIso = utcNowIso(),
            )
          _status.value = nextStatus
          return@withContext nextStatus
        } catch (err: Throwable) {
          lastErr = err
          val msg = err.message ?: ""
          if (msg.contains("EMERGENCY_LOCKDOWN")) {
            val locked =
              _status.value.copy(state = SdkConnectionState.LOCKDOWN_BLOCKED, errorMessage = msg)
            _status.value = locked
            return@withContext locked
          }
          attempt++
          if (attempt <= cfg.maxRetries) {
            delay(300L * (1 shl attempt))
          }
        }
      }

      // Offline graceful fallback to cached config if available
      val hasCache = loadCachedSnapshot()
      val fallbackState =
        if (hasCache && cfg.enableOfflineCache) SdkConnectionState.OFFLINE_CACHED
        else SdkConnectionState.ERROR
      val failedStatus =
        _status.value.copy(state = fallbackState, errorMessage = lastErr?.message ?: "Connect failed")
      _status.value = failedStatus
      failedStatus
    }

  suspend fun refreshConfig(): SdkBootstrapSnapshot {
    connect()
    return _snapshot.value
  }

  suspend fun activateLicense(licenseKey: String, allowTransfer: Boolean = false): Result<String> =
    withContext(Dispatchers.IO) {
      runCatching {
        val cfg = config ?: error("SDK not initialized")
        val iden = identity ?: error("SDK identity missing")
        val body =
          moshi.adapter(Map::class.java).toJson(
            mapOf("licenseKey" to licenseKey.trim(), "allowTransfer" to allowTransfer)
          )
        val resMap = signedPost(cfg, iden, "/api/v1/client/licenses/activate", body)
        refreshConfig()
        resMap["status"]?.toString() ?: "activated"
      }
    }

  suspend fun confirmUpdate(updateId: String, action: String) =
    withContext(Dispatchers.IO) {
      runCatching {
        val cfg = config ?: return@runCatching
        val iden = identity ?: return@runCatching
        val body =
          moshi.adapter(Map::class.java).toJson(
            mapOf(
              "updateId" to updateId,
              "action" to action,
              "appVersionCode" to cfg.versionCode,
            )
          )
        signedPost(cfg, iden, "/api/v1/client/updates/confirm", body)
      }
    }

  suspend fun reportEvent(eventType: String, payload: Map<String, Any?> = emptyMap()) =
    withContext(Dispatchers.IO) {
      runCatching {
        val cfg = config ?: return@runCatching
        val iden = identity ?: return@runCatching
        val body =
          moshi.adapter(Map::class.java).toJson(mapOf("eventType" to eventType, "payload" to payload))
        signedPost(cfg, iden, "/api/v1/client/events", body)
      }
    }

  fun dismissLocalDialog(dialogId: String) {
    _snapshot.value =
      _snapshot.value.copy(dialogs = _snapshot.value.dialogs.filterNot { it.id == dialogId })
  }

  @Synchronized
  fun shutdown() {
    runCatching {
      scope?.cancel()
      scope = null
      _status.value = SdkStatus(state = SdkConnectionState.SHUTDOWN)
    }
  }

  private fun ensureRegisteredAndAuthenticated(
    cfg: PranavConfiguration,
    iden: ClientCryptoIdentity,
  ): Pair<String, String> {
    var (instId, devId, sessionToken) = iden.getIds()
    if (instId.isBlank() || devId.isBlank()) {
      val regPayload =
        moshi.adapter(Map::class.java).toJson(
          mapOf(
            "appId" to cfg.appId,
            "installationUuid" to iden.getOrCreateInstallationUuid(),
            "appVersion" to cfg.appVersion,
            "versionCode" to cfg.versionCode,
            "publicKeyPem" to iden.getOrCreatePublicKeyPem(),
            "hardwareModel" to iden.hardwareModel(),
            "osVersion" to iden.osVersion(),
          )
        )
      val regRes = postJson(cfg.baseUrl, "/api/v1/client/installations/register", regPayload)
      instId = regRes["installationId"].toString()
      devId = regRes["deviceId"].toString()
      iden.saveRegistration(instId, devId)
    }

    if (sessionToken.isBlank()) {
      val chlPayload =
        moshi.adapter(Map::class.java).toJson(mapOf("appId" to cfg.appId, "deviceId" to devId))
      val chlRes = postJson(cfg.baseUrl, "/api/v1/client/auth/challenge", chlPayload)
      val chlId = chlRes["challengeId"].toString()
      val nonce = chlRes["nonce"].toString()
      val ts = utcNowIso()
      val sig = iden.sign("$nonce:$chlId:$devId:$ts")
      val verPayload =
        moshi.adapter(Map::class.java).toJson(
          mapOf(
            "appId" to cfg.appId,
            "installationId" to instId,
            "deviceId" to devId,
            "challengeId" to chlId,
            "timestamp" to ts,
            "signatureBase64" to sig,
          )
        )
      val verRes = postJson(cfg.baseUrl, "/api/v1/client/auth/verify", verPayload)
      sessionToken = verRes["sessionToken"].toString()
      iden.saveSession(sessionToken)
    }

    return instId to devId
  }

  @Suppress("UNCHECKED_CAST")
  private fun fetchBootstrap(cfg: PranavConfiguration, iden: ClientCryptoIdentity): SdkBootstrapSnapshot {
    val path = "/api/v1/client/bootstrap"
    val (instId, _, sessionToken) = iden.getIds()
    val ts = utcNowIso()
    val nonce = UUID.randomUUID().toString()
    val sig = iden.sign("GET:$path:${cfg.appId}:$instId:$ts:$nonce")

    val req =
      Request.Builder()
        .url("${cfg.baseUrl.trimEnd('/')}$path?versionCode=${cfg.versionCode}")
        .get()
        .header("Authorization", "Bearer $sessionToken")
        .header("X-App-Id", cfg.appId)
        .header("X-Installation-Id", instId)
        .header("X-Timestamp", ts)
        .header("X-Nonce", nonce)
        .header("X-Signature", sig)
        .build()

    val resp = httpClient!!.newCall(req).execute()
    val raw = resp.body?.string().orEmpty()
    if (resp.code == 401) {
      iden.clearSession()
      throw IllegalStateException("Session expired or revoked")
    }
    if (!resp.isSuccessful) throw IllegalStateException(raw.ifBlank { "HTTP ${resp.code}" })

    val root = moshi.adapter(Map::class.java).fromJson(raw) as Map<String, Any?>
    val maintMap = root["maintenance"] as? Map<String, Any?> ?: emptyMap()
    val updMap = root["updateAvailable"] as? Map<String, Any?>
    val forceUpdate = root["forceUpdateRequired"] == true
    val dialogsList =
      (root["dialogs"] as? List<Map<String, Any?>>).orEmpty().map { d ->
        UniversalDialogPayload(
          id = d["id"]?.toString().orEmpty(),
          dialogType = d["dialog_type"]?.toString() ?: "custom_popup",
          title = d["title"]?.toString().orEmpty(),
          description = d["description"]?.toString().orEmpty(),
          imageUrl = d["image_url"]?.toString().orEmpty(),
          priority = (d["priority"] as? Number)?.toInt() ?: 50,
        )
      }
    val annsList =
      (root["announcements"] as? List<Map<String, Any?>>).orEmpty().map { a ->
        AnnouncementPayload(
          id = a["id"]?.toString().orEmpty(),
          title = a["title"]?.toString().orEmpty(),
          body = a["body"]?.toString().orEmpty(),
          actionLabel = a["action_label"]?.toString().orEmpty(),
          actionUrl = a["action_url"]?.toString().orEmpty(),
          isPinned = (a["is_pinned"] as? Number)?.toInt() == 1,
        )
      }
    val cfgMap = root["remoteConfig"] as? Map<String, Any?> ?: emptyMap()
    val licMap = root["license"] as? Map<String, Any?>
    val vipMap = root["vip"] as? Map<String, Any?>

    return SdkBootstrapSnapshot(
      maintenanceActive = maintMap["active"] == true,
      maintenanceTitle = maintMap["title"]?.toString().orEmpty(),
      maintenanceMessage = maintMap["message"]?.toString().orEmpty(),
      update =
        updMap?.let {
          UpdatePayload(
            id = it["id"]?.toString().orEmpty(),
            latestVersionName = it["latest_version_name"]?.toString().orEmpty(),
            latestVersionCode = (it["latest_version_code"] as? Number)?.toInt() ?: 1,
            minVersionCode = (it["min_version_code"] as? Number)?.toInt() ?: 1,
            downloadUrl = it["download_url"]?.toString().orEmpty(),
            whatsNew = it["whats_new"]?.toString().orEmpty(),
            isForce = forceUpdate || it["update_mode"]?.toString() == "force",
            showLaterButton = (it["show_later_button"] as? Number)?.toInt() != 0,
          )
        },
      dialogs = dialogsList,
      announcements = annsList,
      remoteConfig =
        RemoteConfigPayload(
          featureFlags =
            (cfgMap["featureFlags"] as? Map<String, Any?>)
              ?.mapValues { it.value == true }
              .orEmpty(),
          remoteText =
            (cfgMap["remoteText"] as? Map<String, Any?>)
              ?.mapValues { it.value?.toString().orEmpty() }
              .orEmpty(),
          remoteLinks =
            (cfgMap["remoteLinks"] as? Map<String, Any?>)
              ?.mapValues { it.value?.toString().orEmpty() }
              .orEmpty(),
          dynamicSettings = (cfgMap["dynamicSettings"] as? Map<String, Any?>).orEmpty(),
        ),
      activeLicenseKey = licMap?.get("license_key")?.toString(),
      vipPlanCode = vipMap?.get("code")?.toString(),
    )
  }

  @Suppress("UNCHECKED_CAST")
  private fun signedPost(
    cfg: PranavConfiguration,
    iden: ClientCryptoIdentity,
    path: String,
    jsonBody: String,
  ): Map<String, Any?> {
    val (instId, _, sessionToken) = iden.getIds()
    val ts = utcNowIso()
    val nonce = UUID.randomUUID().toString()
    val sig = iden.sign("POST:$path:${cfg.appId}:$instId:$ts:$nonce")
    val req =
      Request.Builder()
        .url("${cfg.baseUrl.trimEnd('/')}$path")
        .post(jsonBody.toRequestBody(jsonMedia))
        .header("Authorization", "Bearer $sessionToken")
        .header("X-App-Id", cfg.appId)
        .header("X-Installation-Id", instId)
        .header("X-Timestamp", ts)
        .header("X-Nonce", nonce)
        .header("X-Signature", sig)
        .build()

    val resp = httpClient!!.newCall(req).execute()
    val raw = resp.body?.string().orEmpty()
    if (!resp.isSuccessful) throw IllegalStateException(raw.ifBlank { "HTTP ${resp.code}" })
    return moshi.adapter(Map::class.java).fromJson(raw) as Map<String, Any?>
  }

  @Suppress("UNCHECKED_CAST")
  private fun postJson(baseUrl: String, path: String, jsonBody: String): Map<String, Any?> {
    val req =
      Request.Builder()
        .url("${baseUrl.trimEnd('/')}$path")
        .post(jsonBody.toRequestBody(jsonMedia))
        .build()
    val resp = httpClient!!.newCall(req).execute()
    val raw = resp.body?.string().orEmpty()
    if (!resp.isSuccessful) throw IllegalStateException(raw.ifBlank { "HTTP ${resp.code}" })
    return moshi.adapter(Map::class.java).fromJson(raw) as Map<String, Any?>
  }

  private fun saveCachedSnapshot(snapshot: SdkBootstrapSnapshot) {
    val ctx = appContext ?: return
    val cfg = config ?: return
    if (!cfg.enableOfflineCache) return
    runCatching {
      val json = moshi.adapter(SdkBootstrapSnapshot::class.java).toJson(snapshot)
      ctx.getSharedPreferences("pranav_sdk_cache_${cfg.appId}", Context.MODE_PRIVATE)
        .edit()
        .putString("snapshot_json", json)
        .apply()
    }
  }

  private fun loadCachedSnapshot(): Boolean {
    val ctx = appContext ?: return false
    val cfg = config ?: return false
    if (!cfg.enableOfflineCache) return false
    val raw =
      ctx.getSharedPreferences("pranav_sdk_cache_${cfg.appId}", Context.MODE_PRIVATE)
        .getString("snapshot_json", null) ?: return false
    val parsed =
      runCatching { moshi.adapter(SdkBootstrapSnapshot::class.java).fromJson(raw) }.getOrNull()
        ?: return false
    _snapshot.value = parsed
    return true
  }

  private fun utcNowIso(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
  }
}
