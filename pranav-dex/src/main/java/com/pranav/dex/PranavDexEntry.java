package com.pranav.dex;

import android.content.Context;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Standalone PRANAV.dex runtime entrypoint.
 *
 * Strictly compiled into standalone PRANAV.dex (never an APK).
 * Designed for safe invocation from authorized host applications or MT Manager inspection workflows.
 */
public final class PranavDexEntry {
  public static final String VERSION = "1.0.0";
  public static final String BUILD_ID = "pranav-dex-2026.1";
  public static final int MIN_SUPPORTED_SDK = 24;
  public static final int TARGET_SDK = 36;

  private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
  private static volatile String lastStatus = "UNINITIALIZED";
  private static volatile String activeAppId = "";
  private static volatile String activeBaseUrl = "";

  private PranavDexEntry() {}

  /**
   * Standard entrypoint invoked by an authorized host app or hook bridge.
   * Never throws uncaught exceptions into the host process.
   */
  public static boolean initialize(Context context, String appId, String baseUrl) {
    try {
      if (context == null || appId == null || appId.trim().isEmpty()) {
        lastStatus = "INVALID_PARAMETERS";
        return false;
      }
      if (baseUrl == null || !baseUrl.startsWith("https://") && !baseUrl.startsWith("http://127.0.0.1")) {
        lastStatus = "INVALID_BASE_URL";
        return false;
      }

      activeAppId = appId.trim();
      activeBaseUrl = baseUrl.trim();

      // Bridge cleanly to PRANAV SDK if present on host classpath without hard-failing if absent
      boolean sdkBridged = tryBridgeToPranavSdk(context.getApplicationContext(), activeAppId, activeBaseUrl);
      INITIALIZED.set(true);
      lastStatus = sdkBridged ? "INITIALIZED_WITH_SDK_BRIDGE" : "INITIALIZED_STANDALONE";
      return true;
    } catch (Throwable err) {
      lastStatus = "INIT_ISOLATED_ERROR:" + err.getClass().getSimpleName();
      return false;
    }
  }

  public static boolean verifyCompatibility(int hostApiLevel, int requiredMinApi) {
    int effectiveMin = Math.max(MIN_SUPPORTED_SDK, requiredMinApi);
    return hostApiLevel >= effectiveMin;
  }

  public static DexMetadata getMetadata() {
    return new DexMetadata(
        VERSION,
        BUILD_ID,
        MIN_SUPPORTED_SDK,
        TARGET_SDK,
        INITIALIZED.get(),
        lastStatus);
  }

  public static String getActiveAppId() {
    return activeAppId;
  }

  public static String getActiveBaseUrl() {
    return activeBaseUrl;
  }

  public static void reset() {
    INITIALIZED.set(false);
    activeAppId = "";
    activeBaseUrl = "";
    lastStatus = "RESET";
  }

  private static boolean tryBridgeToPranavSdk(Context appContext, String appId, String baseUrl) {
    try {
      Class<?> cfgClass = Class.forName("com.pranav.sdk.PranavConfiguration");
      Object cfg =
          cfgClass
              .getConstructor(
                  String.class,
                  String.class,
                  String.class,
                  int.class,
                  boolean.class,
                  int.class,
                  long.class)
              .newInstance(appId, baseUrl, "1.0.0", 1, true, 3, 10000L);

      Class<?> sdkClass = Class.forName("com.pranav.sdk.PranavSdk");
      Object sdkInstance = sdkClass.getField("INSTANCE").get(null);
      Method initMethod = sdkClass.getMethod("initialize", Context.class, cfgClass);
      initMethod.invoke(sdkInstance, appContext, cfg);
      return true;
    } catch (Throwable ignored) {
      return false;
    }
  }
}
