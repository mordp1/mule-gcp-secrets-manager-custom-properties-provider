/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed placeholder key (the part after {@code gsm::}). Supported forms:
 *
 * <pre>
 *   my-secret                                           latest (or the configured default) version
 *   my-secret/versions/5                                pinned version (number or alias)
 *   projects/p/secrets/my-secret[/versions/5]           another project
 *   projects/p/locations/l/secrets/my-secret[/versions/5]  regional secret
 *   any of the above + #field                           top-level field of a JSON secret
 * </pre>
 */
public final class SecretReference {

  private static final String ID = "[A-Za-z0-9_-]+";
  // GCP project IDs are lowercase letters, digits and hyphens; legacy domain-scoped IDs add "domain.tld:".
  private static final String PROJECT = "[A-Za-z0-9.:_-]+";

  private static final Pattern SHORT_NAME =
      Pattern.compile("(?<secret>" + ID + ")(?:/versions/(?<version>" + ID + "))?");
  private static final Pattern FULL_NAME = Pattern.compile("projects/(?<project>" + PROJECT + ")"
      + "(?:/locations/(?<location>" + ID + "))?"
      + "/secrets/(?<secret>" + ID + ")(?:/versions/(?<version>" + ID + "))?");

  private final String project;
  private final String location;
  private final String secret;
  private final String version;
  private final String jsonField;

  private SecretReference(String project, String location, String secret, String version, String jsonField) {
    this.project = project;
    this.location = location;
    this.secret = secret;
    this.version = version;
    this.jsonField = jsonField;
  }

  /**
   * @param key placeholder key without the {@code gsm::} prefix
   * @param settings provider settings used to fill in project, location and version
   * @param projectIdDetector called only when a short name is used and no project is configured
   */
  public static SecretReference parse(String key, GsmProviderSettings settings, Supplier<String> projectIdDetector) {
    String reference = key.trim();
    String jsonField = null;
    int hash = reference.indexOf('#');
    if (hash >= 0) {
      jsonField = reference.substring(hash + 1);
      reference = reference.substring(0, hash);
      if (jsonField.isEmpty()) {
        throw invalid(key, "the JSON field after '#' is empty");
      }
    }

    Matcher full = FULL_NAME.matcher(reference);
    if (full.matches()) {
      String location = full.group("location") != null ? full.group("location") : settings.getLocation();
      return new SecretReference(full.group("project"), location, full.group("secret"),
                                 versionOrDefault(full.group("version"), settings), jsonField);
    }

    Matcher shortName = SHORT_NAME.matcher(reference);
    if (shortName.matches()) {
      String project = settings.getProjectId() != null ? settings.getProjectId() : projectIdDetector.get();
      if (project == null || project.isEmpty()) {
        throw new GsmPropertiesProviderException("Cannot resolve ${gsm::" + key + "}: no GCP project. Set projectId on "
            + "<gcp-secret-manager:config>, set GOOGLE_CLOUD_PROJECT, or use a full name projects/<project>/secrets/<name>");
      }
      return new SecretReference(project, settings.getLocation(), shortName.group("secret"),
                                 versionOrDefault(shortName.group("version"), settings), jsonField);
    }

    throw invalid(key, "expected <secret>[/versions/<version>][#field] or "
        + "projects/<project>[/locations/<location>]/secrets/<secret>[/versions/<version>][#field]");
  }

  private static String versionOrDefault(String version, GsmProviderSettings settings) {
    return version != null ? version : settings.getDefaultVersion();
  }

  private static GsmPropertiesProviderException invalid(String key, String reason) {
    return new GsmPropertiesProviderException("Invalid placeholder ${gsm::" + key + "}: " + reason);
  }

  /** Resource name of the secret version, e.g. {@code projects/p/secrets/s/versions/latest}. */
  public String versionResourceName() {
    StringBuilder name = new StringBuilder("projects/").append(project);
    if (location != null) {
      name.append("/locations/").append(location);
    }
    return name.append("/secrets/").append(secret).append("/versions/").append(version).toString();
  }

  public String getLocation() {
    return location;
  }

  /** Top-level field to extract from a JSON secret, or {@code null} to use the whole payload. */
  public String getJsonField() {
    return jsonField;
  }
}
