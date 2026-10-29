/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.api;

import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.CONFIG_ELEMENT;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.CREDENTIALS_FILE;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.DEFAULT_MAX_ATTEMPTS;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.DEFAULT_SECRET_VERSION;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.DEFAULT_TIMEOUT_SECONDS;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.DEFAULT_VERSION;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.ENDPOINT;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.EXTENSION_PREFIX;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.LOCATION;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.MAX_ATTEMPTS;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.PROJECT_ID;
import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.TIMEOUT_SECONDS;

import io.github.mulegcp.secretmanager.provider.internal.CredentialsLoader;
import io.github.mulegcp.secretmanager.provider.internal.GsmConfigurationPropertiesProvider;
import io.github.mulegcp.secretmanager.provider.internal.GsmProviderSettings;
import io.github.mulegcp.secretmanager.provider.internal.GsmPropertiesProviderException;
import io.github.mulegcp.secretmanager.provider.internal.HttpSecretManagerClient;

import java.time.Duration;

import org.mule.runtime.api.component.ComponentIdentifier;
import org.mule.runtime.config.api.dsl.model.ConfigurationParameters;
import org.mule.runtime.config.api.dsl.model.ResourceProvider;
import org.mule.runtime.config.api.dsl.model.properties.ConfigurationPropertiesProvider;
import org.mule.runtime.config.api.dsl.model.properties.ConfigurationPropertiesProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a {@link GsmConfigurationPropertiesProvider} for each {@code <gcp-secret-manager:config/>}
 * element in the application. Registered through
 * {@code META-INF/services/org.mule.runtime.config.api.dsl.model.properties.ConfigurationPropertiesProviderFactory}.
 */
public class GsmConfigurationPropertiesProviderFactory implements ConfigurationPropertiesProviderFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(GsmConfigurationPropertiesProviderFactory.class);

  private static final ComponentIdentifier GSM_PROPERTIES_PROVIDER =
      ComponentIdentifier.builder().namespace(EXTENSION_PREFIX).name(CONFIG_ELEMENT).build();

  @Override
  public ComponentIdentifier getSupportedComponentIdentifier() {
    return GSM_PROPERTIES_PROVIDER;
  }

  @Override
  public ConfigurationPropertiesProvider createProvider(ConfigurationParameters parameters,
                                                        ResourceProvider externalResourceProvider) {
    GsmProviderSettings settings = new GsmProviderSettings(
        optionalString(parameters, PROJECT_ID),
        optionalString(parameters, LOCATION),
        orDefault(optionalString(parameters, DEFAULT_VERSION), DEFAULT_SECRET_VERSION),
        optionalString(parameters, ENDPOINT));

    int timeoutSeconds = positiveInt(parameters, TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS);
    int maxAttempts = positiveInt(parameters, MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS);
    CredentialsLoader credentials =
        new CredentialsLoader(optionalString(parameters, CREDENTIALS_FILE), externalResourceProvider::getResourceAsStream);

    LOGGER.info("Creating GCP Secret Manager properties provider (project={}, location={}, defaultVersion={})",
                settings.getProjectId() == null ? "<auto-detect>" : settings.getProjectId(),
                settings.getLocation() == null ? "global" : settings.getLocation(),
                settings.getDefaultVersion());

    HttpSecretManagerClient client =
        new HttpSecretManagerClient(credentials, Duration.ofSeconds(timeoutSeconds), maxAttempts);
    return new GsmConfigurationPropertiesProvider(settings, client, credentials);
  }

  private static String optionalString(ConfigurationParameters parameters, String name) {
    String value;
    try {
      value = parameters.getStringParameter(name);
    } catch (RuntimeException e) {
      // Mule throws (instead of returning null) for parameters absent from the XML in some versions.
      return null;
    }
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static String orDefault(String value, String defaultValue) {
    return value == null ? defaultValue : value;
  }

  private static int positiveInt(ConfigurationParameters parameters, String name, int defaultValue) {
    String value = optionalString(parameters, name);
    if (value == null) {
      return defaultValue;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed > 0) {
        return parsed;
      }
    } catch (NumberFormatException e) {
      // fall through
    }
    throw new GsmPropertiesProviderException("Parameter '" + name + "' of <" + EXTENSION_PREFIX + ":" + CONFIG_ELEMENT
        + "> must be a positive integer, got '" + value + "'");
  }
}
