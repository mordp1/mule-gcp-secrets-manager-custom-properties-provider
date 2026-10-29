/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.api;

import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.EXTENSION_NAME;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.EXTENSION_NAMESPACE_URI;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.EXTENSION_PREFIX;
import static org.mule.runtime.api.meta.Category.SELECT;
import static org.mule.runtime.api.meta.ExpressionSupport.NOT_SUPPORTED;
import static org.mule.sdk.api.meta.JavaVersion.JAVA_17;
import static org.mule.sdk.api.meta.JavaVersion.JAVA_21;

import org.mule.runtime.extension.api.annotation.Expression;
import org.mule.runtime.extension.api.annotation.Extension;
import org.mule.runtime.extension.api.annotation.dsl.xml.Xml;
import org.mule.runtime.extension.api.annotation.param.Optional;
import org.mule.runtime.extension.api.annotation.param.Parameter;
import org.mule.runtime.extension.api.annotation.param.display.DisplayName;
import org.mule.runtime.extension.api.annotation.param.display.Placement;
import org.mule.runtime.extension.api.annotation.param.display.Summary;
import org.mule.sdk.api.annotation.Export;
import org.mule.sdk.api.annotation.JavaVersionSupport;

/**
 * Declares the {@code <gcp-secret-manager:config/>} global element. The values of these parameters are
 * read by {@link GsmConfigurationPropertiesProviderFactory}; the fields themselves are never used at
 * runtime. All parameters are resolved at deployment time, so they accept {@code ${...}} property
 * placeholders but not DataWeave expressions.
 */
@Extension(name = EXTENSION_NAME, category = SELECT, vendor = "Community")
@Xml(prefix = EXTENSION_PREFIX, namespace = EXTENSION_NAMESPACE_URI)
@Export(classes = GsmConfigurationPropertiesProviderFactory.class,
    resources = "META-INF/services/org.mule.runtime.config.api.dsl.model.properties.ConfigurationPropertiesProviderFactory")
@JavaVersionSupport({JAVA_17, JAVA_21})
public class GsmPropertiesProviderExtension {

  @Parameter
  @Optional
  @Expression(NOT_SUPPORTED)
  @DisplayName("Project ID")
  @Summary("GCP project that owns the secrets referenced by short name (${gsm::my-secret}). "
      + "If empty, it is taken from GOOGLE_CLOUD_PROJECT, the credentials, or the GCE/GKE metadata server.")
  @Placement(order = 1)
  private String projectId;

  @Parameter
  @Optional
  @Expression(NOT_SUPPORTED)
  @DisplayName("Location (regional secrets)")
  @Summary("Region of regional secrets, e.g. us-central1. Leave empty for global secrets.")
  @Placement(order = 2)
  private String location;

  @Parameter
  @Optional(defaultValue = "latest")
  @Expression(NOT_SUPPORTED)
  @DisplayName("Default secret version")
  @Summary("Version used when the placeholder does not pin one: 'latest', a version number, or a version alias.")
  @Placement(order = 3)
  private String defaultVersion;

  @Parameter
  @Optional
  @Expression(NOT_SUPPORTED)
  @DisplayName("Credentials file")
  @Summary("Optional path to a service account JSON key (filesystem or application classpath). "
      + "Leave empty to use Application Default Credentials (recommended: GKE Workload Identity).")
  @Placement(tab = "Advanced", order = 1)
  private String credentialsFile;

  @Parameter
  @Optional
  @Expression(NOT_SUPPORTED)
  @DisplayName("Endpoint")
  @Summary("Override the Secret Manager REST endpoint, e.g. a Private Service Connect address. "
      + "Default: https://secretmanager.googleapis.com (or the regional endpoint when Location is set).")
  @Placement(tab = "Advanced", order = 2)
  private String endpoint;

  @Parameter
  @Optional(defaultValue = "10")
  @Expression(NOT_SUPPORTED)
  @DisplayName("Timeout (seconds)")
  @Summary("Connect and read timeout of each Secret Manager call.")
  @Placement(tab = "Advanced", order = 3)
  private int timeoutSeconds;

  @Parameter
  @Optional(defaultValue = "3")
  @Expression(NOT_SUPPORTED)
  @DisplayName("Max attempts")
  @Summary("Attempts per secret on transient errors (HTTP 429/5xx, I/O errors), with exponential backoff.")
  @Placement(tab = "Advanced", order = 4)
  private int maxAttempts;
}
