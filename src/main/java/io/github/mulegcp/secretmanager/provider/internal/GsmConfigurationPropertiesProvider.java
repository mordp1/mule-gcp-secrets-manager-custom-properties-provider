/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

import static io.github.mulegcp.secretmanager.provider.api.GsmPropertiesProviderConstants.PLACEHOLDER_PREFIX;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.mule.runtime.config.api.dsl.model.properties.ConfigurationPropertiesProvider;
import org.mule.runtime.config.api.dsl.model.properties.ConfigurationProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves {@code ${gsm::...}} placeholders. Secrets are read once, when Mule resolves the placeholder
 * during application startup, and cached for the lifetime of the application: picking up a new secret
 * version requires a restart (which Reloader automates on Kubernetes).
 */
public class GsmConfigurationPropertiesProvider implements ConfigurationPropertiesProvider {

  private static final Logger LOGGER = LoggerFactory.getLogger(GsmConfigurationPropertiesProvider.class);

  private final GsmProviderSettings settings;
  private final SecretManagerClient client;
  private final Supplier<String> projectIdDetector;
  private final Map<String, String> payloads = new ConcurrentHashMap<>();
  private volatile String detectedProjectId;

  public GsmConfigurationPropertiesProvider(GsmProviderSettings settings, SecretManagerClient client,
                                            CredentialsLoader credentials) {
    this(settings, client, credentials::detectProjectId);
  }

  public GsmConfigurationPropertiesProvider(GsmProviderSettings settings, SecretManagerClient client,
                                            Supplier<String> projectIdDetector) {
    this.settings = settings;
    this.client = client;
    this.projectIdDetector = projectIdDetector;
  }

  @Override
  public Optional<ConfigurationProperty> getConfigurationProperty(String configurationAttributeKey) {
    if (configurationAttributeKey == null || !configurationAttributeKey.startsWith(PLACEHOLDER_PREFIX)) {
      return Optional.empty();
    }
    String key = configurationAttributeKey.substring(PLACEHOLDER_PREFIX.length());
    SecretReference reference = SecretReference.parse(key, settings, this::projectId);
    String resourceName = reference.versionResourceName();

    // One call per secret version, even when several JSON fields of the same secret are referenced.
    String payload = payloads.computeIfAbsent(resourceName, name -> {
      LOGGER.info("Reading secret {} from GCP Secret Manager", name);
      return client.accessSecretVersion(settings.endpointFor(reference.getLocation()), name);
    });

    String value = reference.getJsonField() == null ? payload : jsonField(payload, reference.getJsonField(), key);
    return Optional.of(new GsmConfigurationProperty(this, key, value));
  }

  @Override
  public String getDescription() {
    return "GCP Secret Manager properties provider";
  }

  private String projectId() {
    String project = detectedProjectId;
    if (project == null) {
      project = detectedProjectId = projectIdDetector.get();
      if (project != null) {
        LOGGER.info("Using auto-detected GCP project '{}' for short secret names", project);
      }
    }
    return project;
  }

  private static String jsonField(String payload, String field, String key) {
    JsonObject json;
    try {
      json = JsonParser.parseString(payload).getAsJsonObject();
    } catch (JsonParseException | IllegalStateException e) {
      throw new GsmPropertiesProviderException("Cannot resolve ${gsm::" + key + "}: the secret is not a JSON object");
    }
    JsonElement element = json.get(field);
    if (element == null || element.isJsonNull()) {
      throw new GsmPropertiesProviderException("Cannot resolve ${gsm::" + key + "}: field '" + field
          + "' not found in the JSON secret");
    }
    return element.isJsonPrimitive() ? element.getAsString() : element.toString();
  }

  private static final class GsmConfigurationProperty implements ConfigurationProperty {

    private final Object source;
    private final String key;
    private final String value;

    private GsmConfigurationProperty(Object source, String key, String value) {
      this.source = source;
      this.key = key;
      this.value = value;
    }

    @Override
    public Object getSource() {
      return source;
    }

    @Override
    public Object getRawValue() {
      return value;
    }

    @Override
    public String getKey() {
      return key;
    }

    @Override
    public String toString() {
      // Never expose the secret value in logs or error messages.
      return "GsmConfigurationProperty{key=" + key + "}";
    }
  }
}
