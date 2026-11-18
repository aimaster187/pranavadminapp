package com.pranav.sdk

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.UUID

internal class ClientCryptoIdentity(private val context: Context, private val appId: String) {
  private val prefs = context.getSharedPreferences("pranav_sdk_identity_$appId", Context.MODE_PRIVATE)
  private val ksAlias = "pranav_sdk_ecdsa_$appId"

  fun getOrCreateInstallationUuid(): String {
    val existing = prefs.getString("installation_uuid", null)
    if (!existing.isNullOrBlank()) return existing
    val created = UUID.randomUUID().toString()
    prefs.edit().putString("installation_uuid", created).apply()
    return created
  }

  fun getIds(): Triple<String, String, String> =
    Triple(
      prefs.getString("installation_id", "") ?: "",
      prefs.getString("device_id", "") ?: "",
      prefs.getString("session_token", "") ?: "",
    )

  fun saveRegistration(installationId: String, deviceId: String) {
    prefs.edit().putString("installation_id", installationId).putString("device_id", deviceId).apply()
  }

  fun saveSession(sessionToken: String) {
    prefs.edit().putString("session_token", sessionToken).apply()
  }

  fun clearSession() {
    prefs.edit().remove("session_token").apply()
  }

  fun getOrCreatePublicKeyPem(): String {
    return try {
      val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
      if (!ks.containsAlias(ksAlias)) {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val spec =
          KeyGenParameterSpec.Builder(
              ksAlias,
              KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
      }
      toSpkiPem(ks.getCertificate(ksAlias).publicKey.encoded)
    } catch (_: Throwable) {
      getOrCreateFallbackKeyPair().second
    }
  }

  fun sign(payload: String): String {
    val privateKey: PrivateKey =
      try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ksAlias, null) as? PrivateKey) ?: getOrCreateFallbackKeyPair().first
      } catch (_: Throwable) {
        getOrCreateFallbackKeyPair().first
      }
    val sig = Signature.getInstance("SHA256withECDSA")
    sig.initSign(privateKey)
    sig.update(payload.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
  }

  private fun getOrCreateFallbackKeyPair(): Pair<PrivateKey, String> {
    val priv = prefs.getString("fb_priv", null)
    val pub = prefs.getString("fb_pub", null)
    if (!priv.isNullOrBlank() && !pub.isNullOrBlank()) {
      val kf = KeyFactory.getInstance("EC")
      val pk = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)))
      return pk to pub
    }
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec("secp256r1"))
    val kp = kpg.generateKeyPair()
    val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
    val pubPem = toSpkiPem(kp.public.encoded)
    prefs.edit().putString("fb_priv", privB64).putString("fb_pub", pubPem).apply()
    return kp.private to pubPem
  }

  private fun toSpkiPem(encoded: ByteArray): String {
    val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
    return "-----BEGIN PUBLIC KEY-----\n${b64.chunked(64).joinToString("\n")}\n-----END PUBLIC KEY-----"
  }

  fun hardwareModel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
  fun osVersion(): String = Build.VERSION.RELEASE ?: "14"
}
