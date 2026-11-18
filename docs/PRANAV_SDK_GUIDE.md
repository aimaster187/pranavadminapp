# PRANAV Android SDK Integration Guide (`:pranav-sdk`)

`PRANAV SDK` (`com.pranav.sdk`) connects authorized Android host applications to the PRANAV backend without any user login screen, Google Sign-In, or embedded owner secrets.

## 1. Authentication & Security Model
- Generates an Android Keystore ECDSA P-256 key pair per installation (`ClientCryptoIdentity`).
- Registers cleanly via `/api/v1/client/installations/register`, then completes challenge-response auth (`/api/v1/client/auth/challenge` + `/verify`).
- Signs every client API request with `X-App-Id`, `X-Installation-Id`, `X-Timestamp`, `X-Nonce`, and `X-Signature`.
- Falls back gracefully to cached configuration (`SdkConnectionState.OFFLINE_CACHED`) if offline.

## 2. Core API
```kotlin
PranavSdk.initialize(
  context = applicationContext,
  configuration = PranavConfiguration(
    appId = "com.your.app",
    baseUrl = "https://api.yourdomain.com",
    appVersion = "1.0.0",
    versionCode = 1,
  ),
)

// In a coroutine:
val status = PranavSdk.connect()
val activation = PranavSdk.activateLicense("PRNV-XXXX-XXXX-XXXX")
PranavSdk.reportEvent("feature_opened", mapOf("screen" to "home"))
```

## 3. Universal Dialog & Update Host (Compose)
Mount `PranavUniversalDialogHost` once in your root composable to automatically handle Maintenance Mode blocking, Optional/Force Updates, Custom Toasts, and Universal Dialogs:

```kotlin
val snapshot by PranavSdk.snapshotFlow.collectAsStateWithLifecycle()
PranavUniversalDialogHost(
  snapshot = snapshot,
  onDismissDialog = { PranavSdk.dismissLocalDialog(it) },
  onUpdateAction = { upd, action -> scope.launch { PranavSdk.confirmUpdate(upd.id, action) } },
)
```
