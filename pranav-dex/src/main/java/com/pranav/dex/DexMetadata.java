package com.pranav.dex;

/**
 * Immutable build metadata exposed by standalone PRANAV.dex artifact.
 */
public final class DexMetadata {
  private final String version;
  private final String buildId;
  private final int minSdk;
  private final int targetSdk;
  private final boolean initialized;
  private final String statusMessage;

  public DexMetadata(
      String version,
      String buildId,
      int minSdk,
      int targetSdk,
      boolean initialized,
      String statusMessage) {
    this.version = version;
    this.buildId = buildId;
    this.minSdk = minSdk;
    this.targetSdk = targetSdk;
    this.initialized = initialized;
    this.statusMessage = statusMessage;
  }

  public String getVersion() {
    return version;
  }

  public String getBuildId() {
    return buildId;
  }

  public int getMinSdk() {
    return minSdk;
  }

  public int getTargetSdk() {
    return targetSdk;
  }

  public boolean isInitialized() {
    return initialized;
  }

  public String getStatusMessage() {
    return statusMessage;
  }
}
