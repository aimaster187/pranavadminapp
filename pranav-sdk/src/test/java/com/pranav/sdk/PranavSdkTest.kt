package com.pranav.sdk

import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PranavSdkTest {
  @After
  fun tearDown() {
    PranavSdk.shutdown()
  }

  @Test
  fun initializeAndShutdownTransitionsStateSafely() {
    val ctx = RuntimeEnvironment.getApplication()
    PranavSdk.initialize(
      ctx,
      PranavConfiguration(
        appId = "com.test.pranav",
        baseUrl = "http://127.0.0.1:19999",
        enableOfflineCache = true,
        maxRetries = 0,
        connectTimeoutMs = 500L,
      ),
    )

    val status = PranavSdk.getStatus()
    assertEquals(SdkConnectionState.INITIALIZED, status.state)

    PranavSdk.shutdown()
    assertEquals(SdkConnectionState.SHUTDOWN, PranavSdk.getStatus().state)
  }

  @Test
  fun connectFallsBackToOfflineCacheWhenServerUnavailable() = runTest {
    val ctx = RuntimeEnvironment.getApplication()
    val appId = "com.test.cached"
    // Seed cached snapshot cleanly
    ctx.getSharedPreferences("pranav_sdk_cache_$appId", Context.MODE_PRIVATE)
      .edit()
      .putString(
        "snapshot_json",
        """{"maintenanceActive":false,"maintenanceTitle":"","maintenanceMessage":"","dialogs":[],"announcements":[],"remoteConfig":{"featureFlags":{"beta_mode":true},"remoteText":{},"remoteLinks":{},"dynamicSettings":{}},"dynamicModules":[]}""",
      )
      .commit()

    PranavSdk.initialize(
      ctx,
      PranavConfiguration(
        appId = appId,
        baseUrl = "http://127.0.0.1:19998",
        enableOfflineCache = true,
        maxRetries = 0,
        connectTimeoutMs = 300L,
      ),
    )

    val result = PranavSdk.connect()
    assertEquals(SdkConnectionState.OFFLINE_CACHED, result.state)
    assertTrue(PranavSdk.snapshotFlow.value.remoteConfig.featureFlags["beta_mode"] == true)
    assertNotNull(result.errorMessage)
  }

  @Test
  fun dismissLocalDialogRemovesDialogFromSnapshot() {
    val ctx = RuntimeEnvironment.getApplication()
    ctx.getSharedPreferences("pranav_sdk_cache_com.test.dlg", Context.MODE_PRIVATE)
      .edit()
      .putString(
        "snapshot_json",
        """{"maintenanceActive":false,"dialogs":[{"id":"d1","dialogType":"info","title":"Hi","description":"Msg","imageUrl":"","buttons":[],"priority":50}],"announcements":[],"remoteConfig":{"featureFlags":{},"remoteText":{},"remoteLinks":{},"dynamicSettings":{}},"dynamicModules":[]}""",
      )
      .commit()

    PranavSdk.initialize(
      ctx,
      PranavConfiguration(appId = "com.test.dlg", baseUrl = "http://127.0.0.1:19997", enableOfflineCache = true),
    )
    assertEquals(1, PranavSdk.snapshotFlow.value.dialogs.size)
    PranavSdk.dismissLocalDialog("d1")
    assertTrue(PranavSdk.snapshotFlow.value.dialogs.isEmpty())
  }
}
