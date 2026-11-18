# MT Manager & Standalone `PRANAV.dex` Integration Guide

`PRANAV.dex` is compiled strictly as a standalone Dalvik bytecode artifact (`pranav-dex/dist/PRANAV.dex`) — **never** packaged inside an APK.

## 1. Deliverable Verification
After building `PRANAV.dex` (`bash pranav-dex/scripts/build-dex.sh`), verify integrity before distribution:

```bash
sha256sum -c pranav-dex/dist/PRANAV.dex.sha256
cat pranav-dex/dist/dex-manifest.json
```

Confirm:
- `"format": "standalone-dex"`
- `"isApk": false`
- `"minSdk": 24`

## 2. Register Release in PRANAV Admin App / RANGIN Bot
1. Open **Module 15 (DEX Release Manager)** in the PRANAV Android Admin App or via RANGIN Telegram Bot.
2. Enter the release version (`1.0.0`), target `minSdk` (`24`), `PRANAV.dex` SHA-256 digest from `PRANAV.dex.sha256`, and download URI.
3. Publish the release so audit logs and signature hashes are recorded on the backend.

## 3. Authorized Host Inspection & Integration in MT Manager
For apps you own and have full legal authorization to build or inspect:
1. Open your authorized host APK in **MT Manager** and inspect `classes*.dex`.
2. Add the verified standalone `PRANAV.dex` alongside your existing DEX classes (`classesN.dex`) or load it cleanly from your application's `Application.onCreate` / main launcher `Activity.onCreate`:

```smali
const-string v0, "com.your.authorized.app"
const-string v1, "https://api.yourdomain.com"
invoke-static {p0, v0, v1}, Lcom/pranav/dex/PranavDexEntry;->initialize(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Z
```

3. Verify class metadata:
   - Class: `com.pranav.dex.PranavDexEntry`
   - Entrypoint: `initialize(Context, String appId, String baseUrl)`
   - Compatibility check: `verifyCompatibility(int hostApiLevel, int requiredMinApi)`
   - Metadata object: `getMetadata(): com.pranav.dex.DexMetadata`
4. Re-sign your authorized build with your own upload/release keystore before testing on-device.

## 4. Separation & Safety Guarantee
- `PRANAV.dex` contains zero hardcoded owner tokens, private keys, or credentials.
- `PranavDexEntry.initialize(...)` isolates all initialization failures cleanly and bridges via reflection to `com.pranav.sdk.PranavSdk` when present on the host classpath.
- Keep `PRANAV.dex` strictly separate from `:pranav-hook` and `:pranav-sdk`.
