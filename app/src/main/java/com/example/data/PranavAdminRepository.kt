package com.example.data

import com.example.security.OwnerKeystoreVault
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed interface SilentAuthOutcome {
  data class Verified(val ownerDeviceId: String) : SilentAuthOutcome
  data class EnrollmentRequired(val keyFingerprint: String, val hardwareLabel: String, val reason: String) : SilentAuthOutcome
}

class PranavAdminRepository(
  val vault: OwnerKeystoreVault,
  val endpointHolder: DynamicApiEndpointHolder,
  val api: PranavAdminApi,
) {
  private fun utcIsoNow(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date())
  }

  suspend fun performSilentAuth(): SilentAuthOutcome {
    val creds = vault.loadCredentials()
    endpointHolder.baseUrl = creds.apiBaseUrl
    endpointHolder.bearerToken = creds.sessionToken
    val fp = vault.getKeyFingerprint()
    val hw = vault.getHardwareLabel()

    if (creds.ownerDeviceId.isBlank()) {
      return SilentAuthOutcome.EnrollmentRequired(
        keyFingerprint = fp,
        hardwareLabel = hw,
        reason = "Device not yet enrolled with PRANAV backend.",
      )
    }

    return try {
      val challenge = api.challenge(ChallengeRequest(creds.ownerDeviceId))
      val ts = utcIsoNow()
      val canonical = "${challenge.nonce}:${challenge.challengeId}:${creds.ownerDeviceId}:$ts"
      val signature = vault.signPayload(canonical)
      val verified =
        api.verify(
          VerifyChallengeRequest(
            ownerDeviceId = creds.ownerDeviceId,
            challengeId = challenge.challengeId,
            timestamp = ts,
            signatureBase64 = signature,
          )
        )
      vault.saveSessionToken(verified.sessionToken)
      endpointHolder.bearerToken = verified.sessionToken
      SilentAuthOutcome.Verified(verified.ownerDeviceId)
    } catch (err: Throwable) {
      endpointHolder.bearerToken = ""
      SilentAuthOutcome.EnrollmentRequired(
        keyFingerprint = fp,
        hardwareLabel = hw,
        reason = err.message ?: "Silent Keystore authentication failed or device revoked.",
      )
    }
  }

  suspend fun enrollDevice(
    apiBaseUrl: String,
    enrollmentToken: String,
    deviceLabel: String,
  ): Result<String> = runCatching {
    val cleanUrl = apiBaseUrl.trim().trimEnd('/')
    vault.setBaseUrl(cleanUrl)
    endpointHolder.baseUrl = cleanUrl
    val pubPem = vault.getOrCreatePublicKeyPem()
    val res =
      api.enroll(
        EnrollOwnerRequest(
          enrollmentToken = enrollmentToken.trim(),
          deviceLabel = deviceLabel.ifBlank { vault.getHardwareLabel() },
          hardwareModel = vault.getHardwareLabel(),
          publicKeyPem = pubPem,
        )
      )
    vault.saveEnrollment(cleanUrl, res.ownerDeviceId, res.sessionToken)
    endpointHolder.bearerToken = res.sessionToken
    res.ownerDeviceId
  }
}
