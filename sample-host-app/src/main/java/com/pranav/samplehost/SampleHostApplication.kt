package com.pranav.samplehost

import android.app.Application
import com.pranav.hook.PranavHook
import com.pranav.hook.PranavHookConfig
import com.pranav.sdk.PranavConfiguration

class SampleHostApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    PranavHook.attachApplication(
      application = this,
      config =
        PranavHookConfig(
          sdkConfiguration =
            PranavConfiguration(
              appId = "com.aistudio.pranav.samplehost",
              baseUrl = "https://api.pranav.example.com",
              appVersion = "1.0.0",
              versionCode = 1,
              enableOfflineCache = true,
            ),
          autoConnectOnLaunch = false,
          minSupportedApi = 24,
        ),
    )
  }
}
