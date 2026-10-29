/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class SecretReferenceTest {

  private static final GsmProviderSettings GLOBAL = new GsmProviderSettings("my-project", null, "latest", null);
  private static final Supplier<String> NO_DETECTION = () -> {
    throw new AssertionError("project detection must not be called");
  };

  @Test
  void shortNameUsesConfiguredProjectAndDefaultVersion() {
    SecretReference ref = SecretReference.parse("db-password", GLOBAL, NO_DETECTION);
    assertEquals("projects/my-project/secrets/db-password/versions/latest", ref.versionResourceName());
    assertNull(ref.getLocation());
    assertNull(ref.getJsonField());
  }

  @Test
  void shortNameWithPinnedVersion() {
    assertEquals("projects/my-project/secrets/db-password/versions/7",
                 SecretReference.parse("db-password/versions/7", GLOBAL, NO_DETECTION).versionResourceName());
  }

  @Test
  void configuredDefaultVersionIsUsed() {
    GsmProviderSettings settings = new GsmProviderSettings("p", null, "stable", null);
    assertEquals("projects/p/secrets/s/versions/stable",
                 SecretReference.parse("s", settings, NO_DETECTION).versionResourceName());
  }

  @Test
  void fullNameOverridesConfiguredProject() {
    assertEquals("projects/other/secrets/api-key/versions/3",
                 SecretReference.parse("projects/other/secrets/api-key/versions/3", GLOBAL, NO_DETECTION)
                     .versionResourceName());
    assertEquals("projects/other/secrets/api-key/versions/latest",
                 SecretReference.parse("projects/other/secrets/api-key", GLOBAL, NO_DETECTION).versionResourceName());
  }

  @Test
  void regionalSecrets() {
    GsmProviderSettings regional = new GsmProviderSettings("p", "us-central1", "latest", null);
    SecretReference shortName = SecretReference.parse("s", regional, NO_DETECTION);
    assertEquals("projects/p/locations/us-central1/secrets/s/versions/latest", shortName.versionResourceName());
    assertEquals("us-central1", shortName.getLocation());

    SecretReference fullName =
        SecretReference.parse("projects/p/locations/europe-west1/secrets/s/versions/2", GLOBAL, NO_DETECTION);
    assertEquals("projects/p/locations/europe-west1/secrets/s/versions/2", fullName.versionResourceName());
    assertEquals("europe-west1", fullName.getLocation());
  }

  @Test
  void jsonField() {
    SecretReference ref = SecretReference.parse("db-credentials#password", GLOBAL, NO_DETECTION);
    assertEquals("projects/my-project/secrets/db-credentials/versions/latest", ref.versionResourceName());
    assertEquals("password", ref.getJsonField());
  }

  @Test
  void projectIsDetectedWhenNotConfigured() {
    GsmProviderSettings settings = new GsmProviderSettings(null, null, "latest", null);
    assertEquals("projects/detected/secrets/s/versions/latest",
                 SecretReference.parse("s", settings, () -> "detected").versionResourceName());
  }

  @Test
  void missingProjectFailsWithHelpfulMessage() {
    GsmProviderSettings settings = new GsmProviderSettings(null, null, "latest", null);
    GsmPropertiesProviderException e =
        assertThrows(GsmPropertiesProviderException.class, () -> SecretReference.parse("s", settings, () -> null));
    assertTrue(e.getMessage().contains("projectId"));
  }

  @Test
  void invalidReferencesAreRejected() {
    assertThrows(GsmPropertiesProviderException.class, () -> SecretReference.parse("bad name", GLOBAL, NO_DETECTION));
    assertThrows(GsmPropertiesProviderException.class, () -> SecretReference.parse("s/versions/", GLOBAL, NO_DETECTION));
    assertThrows(GsmPropertiesProviderException.class, () -> SecretReference.parse("s#", GLOBAL, NO_DETECTION));
    assertThrows(GsmPropertiesProviderException.class, () -> SecretReference.parse("", GLOBAL, NO_DETECTION));
  }

  @Test
  void endpointSelection() {
    assertEquals("https://secretmanager.googleapis.com", GLOBAL.endpointFor(null));
    assertEquals("https://secretmanager.us-east1.rep.googleapis.com", GLOBAL.endpointFor("us-east1"));
    assertEquals("http://localhost:1234",
                 new GsmProviderSettings("p", null, "latest", "http://localhost:1234/").endpointFor("us-east1"));
  }
}
