package com.pranav.hook

import android.app.Activity
import android.os.Build
import com.pranav.sdk.PranavConfiguration
import com.pranav.sdk.PranavSdk
import com.pranav.sdk.SdkConnectionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PranavHookTest {
  @After
  fun tearDown() {
    PranavHook.detach(RuntimeEnvironment.getApplication())
  }

  @Test
  fun attachRejectsIncompatibleApiLevelWithoutCrashing() {
    val app = RuntimeEnvironment.getApplication()
    val status =
      PranavHook.attachApplication(
        application = app,
        config =
          PranavHookConfig(
            sdkConfiguration = PranavConfiguration(appId = "com.test.hook", baseUrl = "https://api.example.com"),
            autoConnectOnLaunch = false,
            minSupportedApi = Build.VERSION.SDK_INT + 10,
          ),
      )

    assertFalse(status.attached)
    assertFalse(status.compatible)
    assertTrue(status.lastError?.contains("minSupportedApi") == true)
  }

  @Test
  fun attachAndLifecycleCallbacksTrackActivitiesAndInitializeSdk() {
    val app = RuntimeEnvironment.getApplication()
    val status =
      PranavHook.attachApplication(
        application = app,
        config =
          PranavHookConfig(
            sdkConfiguration = PranavConfiguration(appId = "com.test.hook.ok", baseUrl = "http://127.0.0.1:19991"),
            autoConnectOnLaunch = false,
            minSupportedApi = 24,
          ),
      )

    assertTrue(status.attached)
    assertEquals(SdkConnectionState.INITIALIZED, PranavSdk.getStatus().state)

    val controller = Robolectric.buildActivity(Activity::class.java).create().resume()
    assertEquals(1, PranavHook.statusFlow.value.activeActivities)
    assertEquals("Activity", PranavHook.statusFlow.value.lastActivityName)

    controller.destroy()
    assertEquals(0, PranavHook.statusFlow.value.activeActivities)
  }
}
