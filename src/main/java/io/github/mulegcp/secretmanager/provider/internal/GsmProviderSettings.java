/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

/**
 * Values of a {@code <gcp-secret-manager:config/>} element that drive how placeholders are mapped to
 * Secret Manager resource names. Optional values are {@code null} when not configured.
 */
public final class GsmProviderSettings {

  private static final String GLOBAL_ENDPOINT = "https://secretmanager.googleapis.com";

  private final String projectId;
  private final String location;
  private final String defaultVersion;
  private final String endpoint;

  public GsmProviderSettings(String projectId, String location, String defaultVersion, String endpoint) {
    this.projectId = projectId;
    this.location = location;
    this.defaultVersion = defaultVersion;
    this.endpoint = endpoint == null ? null : stripTrailingSlash(endpoint);
  }

  public String getProjectId() {
    return projectId;
  }

  public String getLocation() {
    return location;
  }

  public String getDefaultVersion() {
    return defaultVersion;
  }

  /**
   * Base URL to call for a secret in {@code secretLocation} ({@code null} for global secrets). Regional
   * secrets must be accessed through their regional endpoint.
   */
  public String endpointFor(String secretLocation) {
    if (endpoint != null) {
      return endpoint;
    }
    if (secretLocation == null) {
      return GLOBAL_ENDPOINT;
    }
    return "https://secretmanager." + secretLocation + ".rep.googleapis.com";
  }

  private static String stripTrailingSlash(String url) {
    String trimmed = url.trim();
    return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
  }
}
