package com.pranav.dex;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class PranavDexTest {
  @After
  public void cleanup() {
    PranavDexEntry.reset();
  }

  @Test
  public void initializeRejectsInvalidParamsSafely() {
    assertFalse(PranavDexEntry.initialize(null, "com.test", "https://api.pranav.example.com"));
    assertEquals("INVALID_PARAMETERS", PranavDexEntry.getMetadata().getStatusMessage());

    assertFalse(
        PranavDexEntry.initialize(
            RuntimeEnvironment.getApplication(), "com.test", "http://insecure.example.com"));
    assertEquals("INVALID_BASE_URL", PranavDexEntry.getMetadata().getStatusMessage());
  }

  @Test
  public void initializeStandaloneSucceedsAndExposesMetadata() {
    boolean ok =
        PranavDexEntry.initialize(
            RuntimeEnvironment.getApplication(),
            "com.aistudio.pranav.samplehost",
            "https://api.pranav.example.com");
    assertTrue(ok);
    DexMetadata meta = PranavDexEntry.getMetadata();
    assertEquals(PranavDexEntry.VERSION, meta.getVersion());
    assertTrue(meta.isInitialized());
    assertTrue(PranavDexEntry.verifyCompatibility(34, 24));
    assertFalse(PranavDexEntry.verifyCompatibility(21, 24));
  }
}
