package com.example.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.pranavVaultStore: DataStore<Preferences> by
  preferencesDataStore(name = "pranav_owner_vault")

data class StoredOwnerCredentials(
  val apiBaseUrl: String,
  val ownerDeviceId: String,
  val sessionToken: String,
  val darkTheme: Boolean,
)

class OwnerKeystoreVault(private val context: Context, private val defaultBaseUrl: String) {
  companion object {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val SIGNING_ALIAS = "pranav_owner_ecdsa_p256"
    private const val WRAP_ALIAS = "pranav_owner_aes_wrap"

    private val KEY_BASE_URL = stringPreferencesKey("api_base_url")
    private val KEY_DEVICE_ID = stringPreferencesKey("owner_device_id")
    private val KEY_SESSION_ENC = stringPreferencesKey("session_enc")
    private val KEY_SESSION_IV = stringPreferencesKey("session_iv")
    private val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
    private val KEY_FALLBACK_PRIV = stringPreferencesKey("fallback_priv")
    private val KEY_FALLBACK_PUB = stringPreferencesKey("fallback_pub")
  }

  suspend fun loadCredentials(): StoredOwnerCredentials {
    val prefs = context.pranavVaultStore.data.first()
    val url = prefs[KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: defaultBaseUrl
    val deviceId = prefs[KEY_DEVICE_ID] ?: ""
    val darkTheme = prefs[KEY_DARK_THEME] ?: false
    val enc = prefs[KEY_SESSION_ENC]
    val iv = prefs[KEY_SESSION_IV]
    val token =
      if (!enc.isNullOrBlank() && !iv.isNullOrBlank()) {
        decryptString(enc, iv) ?: ""
      } else {
        ""
      }
    return StoredOwnerCredentials(url, deviceId, token, darkTheme)
  }

  fun darkThemeFlow() = context.pranavVaultStore.data.map { it[KEY_DARK_THEME] ?: false }

  suspend fun setDarkTheme(enabled: Boolean) {
    context.pranavVaultStore.edit { it[KEY_DARK_THEME] = enabled }
  }

  suspend fun saveEnrollment(baseUrl: String, ownerDeviceId: String, sessionToken: String) {
    val (enc, iv) = encryptString(sessionToken)
    context.pranavVaultStore.edit { prefs ->
      prefs[KEY_BASE_URL] = baseUrl.trim().trimEnd('/')
      prefs[KEY_DEVICE_ID] = ownerDeviceId
      prefs[KEY_SESSION_ENC] = enc
      prefs[KEY_SESSION_IV] = iv
    }
  }

  suspend fun saveSessionToken(sessionToken: String) {
    val (enc, iv) = encryptString(sessionToken)
    context.pranavVaultStore.edit { prefs ->
      prefs[KEY_SESSION_ENC] = enc
      prefs[KEY_SESSION_IV] = iv
    }
  }

  suspend fun clearSessionAndDevice() {
    context.pranavVaultStore.edit { prefs ->
      prefs.remove(KEY_DEVICE_ID)
      prefs.remove(KEY_SESSION_ENC)
      prefs.remove(KEY_SESSION_IV)
    }
  }

  suspend fun setBaseUrl(url: String) {
    context.pranavVaultStore.edit { it[KEY_BASE_URL] = url.trim().trimEnd('/') }
  }

  suspend fun getOrCreatePublicKeyPem(): String {
    return try {
      val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
      if (!ks.containsAlias(SIGNING_ALIAS)) {
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val spec =
          KeyGenParameterSpec.Builder(
              SIGNING_ALIAS,
              KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
      }
      val cert = ks.getCertificate(SIGNING_ALIAS)
      toSpkiPem(cert.publicKey.encoded)
    } catch (_: Throwable) {
      // Fallback for JVM / Robolectric environments lacking AndroidKeyStore provider
      getOrCreateSoftwareKeyPair().second
    }
  }

  suspend fun getKeyFingerprint(): String {
    val pem = getOrCreatePublicKeyPem()
    val digest = MessageDigest.getInstance("SHA-256").digest(pem.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  suspend fun signPayload(payload: String): String {
    val privateKey: PrivateKey =
      try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(SIGNING_ALIAS, null) as? PrivateKey) ?: getOrCreateSoftwareKeyPair().first
      } catch (_: Throwable) {
        getOrCreateSoftwareKeyPair().first
      }
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(privateKey)
    signature.update(payload.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
  }

  private suspend fun getOrCreateSoftwareKeyPair(): Pair<PrivateKey, String> {
    val prefs = context.pranavVaultStore.data.first()
    val storedPriv = prefs[KEY_FALLBACK_PRIV]
    val storedPub = prefs[KEY_FALLBACK_PUB]
    if (!storedPriv.isNullOrBlank() && !storedPub.isNullOrBlank()) {
      val kf = java.security.KeyFactory.getInstance("EC")
      val pk =
        kf.generatePrivate(
          java.security.spec.PKCS8EncodedKeySpec(Base64.decode(storedPriv, Base64.NO_WRAP))
        )
      return pk to storedPub
    }
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec("secp256r1"))
    val kp = kpg.generateKeyPair()
    val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
    val pubPem = toSpkiPem(kp.public.encoded)
    context.pranavVaultStore.edit {
      it[KEY_FALLBACK_PRIV] = privB64
      it[KEY_FALLBACK_PUB] = pubPem
    }
    return kp.private to pubPem
  }

  private fun encryptString(plain: String): Pair<String, String> {
    return try {
      val key = getOrCreateAesWrapKey()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key)
      val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
      Base64.encodeToString(out, Base64.NO_WRAP) to
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    } catch (_: Throwable) {
      Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) to "plain"
    }
  }

  private fun decryptString(cipherB64: String, ivB64: String): String? {
    return try {
      if (ivB64 == "plain") {
        return String(Base64.decode(cipherB64, Base64.NO_WRAP), Charsets.UTF_8)
      }
      val key = getOrCreateAesWrapKey()
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
      String(cipher.doFinal(Base64.decode(cipherB64, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Throwable) {
      null
    }
  }

  private fun getOrCreateAesWrapKey(): SecretKey {
    val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    if (!ks.containsAlias(WRAP_ALIAS)) {
      val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
      val spec =
        KeyGenParameterSpec.Builder(
            WRAP_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
          )
          .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
          .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
          .setKeySize(256)
          .build()
      kg.init(spec)
      kg.generateKey()
    }
    return ks.getKey(WRAP_ALIAS, null) as SecretKey
  }

  private fun toSpkiPem(encoded: ByteArray): String {
    val b64 = Base64.encodeToString(encoded, Base64.NO_WRAP)
    val wrapped = b64.chunked(64).joinToString("\n")
    return "-----BEGIN PUBLIC KEY-----\n$wrapped\n-----END PUBLIC KEY-----"
  }

  fun getHardwareLabel(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
