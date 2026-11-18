package com.pranav.sdk

data class PranavConfiguration(
  val appId: String,
  val baseUrl: String,
  val appVersion: String = "1.0.0",
  val versionCode: Int = 1,
  val enableOfflineCache: Boolean = true,
  val maxRetries: Int = 3,
  val connectTimeoutMs: Long = 10_000L,
)
