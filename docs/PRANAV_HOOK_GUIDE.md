# PRANAV Hook Integration Guide (`:pranav-hook`)

`PRANAV Hook` is an independent Android library deliverable (`com.pranav.hook`) kept strictly separate from `PRANAV.dex` and `PRANAV SDK`.

## 1. Purpose
- Attaches safely to an authorized host `Application` without writing boilerplate lifecycle code in every `Activity`.
- Registers `Application.ActivityLifecycleCallbacks` (`onActivityCreated`, `onActivityResumed`, `onActivityDestroyed`) to track active foreground windows cleanly.
- Checks `Build.VERSION.SDK_INT >= minSupportedApi` before initializing `PranavSdk`.
- Wraps every callback in `runCatching` crash isolation so no lifecycle or network issue can crash the host app.

## 2. Usage in Host `Application`
```kotlin
class MyHostApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    PranavHook.attachApplication(
      application = this,
      config = PranavHookConfig(
        sdkConfiguration = PranavConfiguration(
          appId = "com.your.app",
          baseUrl = "https://api.yourdomain.com",
          appVersion = BuildConfig.VERSION_NAME,
          versionCode = BuildConfig.VERSION_CODE,
        ),
        autoConnectOnLaunch = true,
        minSupportedApi = 24,
      ),
    )
  }
}
```

## 3. Clean Teardown & Diagnostics
- Observe live hook state via `PranavHook.statusFlow` (`attached`, `compatible`, `activeActivities`, `lastActivityName`, `lastError`).
- Call `PranavHook.detach(application)` during teardown or test cleanup to unregister callbacks and shut down `PranavSdk`.
