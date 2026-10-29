/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads Google credentials lazily (on the first {@code ${gsm::...}} placeholder), so an application that
 * declares the config but references no secret does not need GCP access.
 *
 * <p>Without a credentials file, Application Default Credentials are used, in this order:
 * {@code GOOGLE_APPLICATION_CREDENTIALS}, the gcloud ADC file, then the metadata server (GKE Workload
 * Identity, GCE, Cloud Run).
 */
public class CredentialsLoader implements Supplier<GoogleCredentials> {

  private static final Logger LOGGER = LoggerFactory.getLogger(CredentialsLoader.class);

  static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";

  private final String credentialsFile;
  private final Function<String, InputStream> classpathResources;
  private volatile GoogleCredentials credentials;

  /**
   * @param credentialsFile path to a service account JSON key, or {@code null} for ADC
   * @param classpathResources resolves resources from the Mule application (used when the file is not
   *        found on the filesystem)
   */
  public CredentialsLoader(String credentialsFile, Function<String, InputStream> classpathResources) {
    this.credentialsFile = credentialsFile;
    this.classpathResources = classpathResources;
  }

  @Override
  public GoogleCredentials get() {
    GoogleCredentials result = credentials;
    if (result == null) {
      synchronized (this) {
        result = credentials;
        if (result == null) {
          result = credentials = load();
        }
      }
    }
    return result;
  }

  private GoogleCredentials load() {
    try {
      GoogleCredentials loaded;
      if (credentialsFile == null) {
        LOGGER.debug("Using Application Default Credentials");
        loaded = GoogleCredentials.getApplicationDefault();
      } else {
        LOGGER.debug("Using credentials file {}", credentialsFile);
        try (InputStream in = openCredentialsFile()) {
          loaded = GoogleCredentials.fromStream(in);
        }
      }
      return loaded.createScopedRequired()
          ? loaded.createScoped(Collections.singletonList(CLOUD_PLATFORM_SCOPE))
          : loaded;
    } catch (IOException e) {
      throw new GsmPropertiesProviderException("Unable to load Google credentials"
          + (credentialsFile == null
              ? " (Application Default Credentials). On GKE enable Workload Identity for the pod's "
                  + "Kubernetes service account; locally run 'gcloud auth application-default login'"
              : " from " + credentialsFile)
          + ": " + e.getMessage(), e);
    }
  }

  private InputStream openCredentialsFile() throws IOException {
    Path path = Paths.get(credentialsFile);
    if (Files.isRegularFile(path)) {
      return Files.newInputStream(path);
    }
    InputStream resource = classpathResources.apply(credentialsFile);
    if (resource == null) {
      throw new IOException("file not found on the filesystem or in the application classpath");
    }
    return resource;
  }

  /**
   * Best-effort project detection for short secret names: {@code GOOGLE_CLOUD_PROJECT}/{@code GCLOUD_PROJECT},
   * the service account key or quota project, then the metadata server. Returns {@code null} if unknown.
   */
  public String detectProjectId() {
    String project = firstNonEmpty(System.getenv("GOOGLE_CLOUD_PROJECT"), System.getenv("GCLOUD_PROJECT"));
    if (project != null) {
      return project;
    }
    GoogleCredentials current = get();
    if (current instanceof ServiceAccountCredentials) {
      project = ((ServiceAccountCredentials) current).getProjectId();
    }
    project = firstNonEmpty(project, current.getQuotaProjectId());
    return project != null ? project : projectFromMetadataServer();
  }

  private static String projectFromMetadataServer() {
    String host = firstNonEmpty(System.getenv("GCE_METADATA_HOST"), "metadata.google.internal");
    try {
      HttpURLConnection connection =
          (HttpURLConnection) new URL("http://" + host + "/computeMetadata/v1/project/project-id").openConnection();
      connection.setRequestProperty("Metadata-Flavor", "Google");
      connection.setConnectTimeout(2000);
      connection.setReadTimeout(2000);
      try {
        if (connection.getResponseCode() != 200) {
          return null;
        }
        try (InputStream in = connection.getInputStream()) {
          return firstNonEmpty(new String(in.readAllBytes(), StandardCharsets.UTF_8), null);
        }
      } finally {
        connection.disconnect();
      }
    } catch (IOException e) {
      LOGGER.debug("Project ID not available from the metadata server: {}", e.toString());
      return null;
    }
  }

  private static String firstNonEmpty(String first, String second) {
    if (first != null && !first.trim().isEmpty()) {
      return first.trim();
    }
    return second == null || second.trim().isEmpty() ? null : second.trim();
  }
}
