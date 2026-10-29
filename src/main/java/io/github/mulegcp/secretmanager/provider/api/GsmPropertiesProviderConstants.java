/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.api;

/**
 * Names shared between the extension declaration (what Anypoint Studio / the XML DSL shows) and the
 * factory that reads the configured values.
 */
public final class GsmPropertiesProviderConstants {

  /** Human readable extension name. */
  public static final String EXTENSION_NAME = "GCP Secret Manager Properties Provider";

  /** XML prefix of the global element: {@code <gcp-secret-manager:config .../>}. */
  public static final String EXTENSION_PREFIX = "gcp-secret-manager";

  /** XML namespace of the global element. */
  public static final String EXTENSION_NAMESPACE_URI = "http://www.mulesoft.org/schema/mule/" + EXTENSION_PREFIX;

  /** Name of the global configuration element. */
  public static final String CONFIG_ELEMENT = "config";

  /** Placeholder prefix: {@code ${gsm::my-secret}}. */
  public static final String PLACEHOLDER_PREFIX = "gsm::";

  // Configuration parameter names (must match the field names of GsmPropertiesProviderExtension).
  public static final String PROJECT_ID = "projectId";
  public static final String LOCATION = "location";
  public static final String DEFAULT_VERSION = "defaultVersion";
  public static final String CREDENTIALS_FILE = "credentialsFile";
  public static final String ENDPOINT = "endpoint";
  public static final String TIMEOUT_SECONDS = "timeoutSeconds";
  public static final String MAX_ATTEMPTS = "maxAttempts";

  public static final String DEFAULT_SECRET_VERSION = "latest";
  public static final int DEFAULT_TIMEOUT_SECONDS = 10;
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  private GsmPropertiesProviderConstants() {}
}
