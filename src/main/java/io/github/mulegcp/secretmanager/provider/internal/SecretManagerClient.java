/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

/**
 * Reads secret payloads from Secret Manager.
 */
public interface SecretManagerClient {

  /**
   * Calls {@code versions.access} for one secret version.
   *
   * @param endpoint base URL, e.g. {@code https://secretmanager.googleapis.com}
   * @param versionResourceName e.g. {@code projects/p/secrets/s/versions/latest}
   * @return the decoded payload as UTF-8 text
   * @throws GsmPropertiesProviderException if the secret cannot be read
   */
  String accessSecretVersion(String endpoint, String versionResourceName);
}
