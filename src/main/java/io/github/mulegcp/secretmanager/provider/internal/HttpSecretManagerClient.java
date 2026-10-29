/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

import com.google.auth.Credentials;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Secret Manager client over the REST API ({@code GET v1/{name}:access}). The REST API is used instead
 * of the gRPC client library to keep the plugin small and free of gRPC/Netty/Guava/Protobuf, which
 * frequently clash with other libraries inside Mule applications.
 */
public class HttpSecretManagerClient implements SecretManagerClient {

  private static final Logger LOGGER = LoggerFactory.getLogger(HttpSecretManagerClient.class);

  private static final long INITIAL_BACKOFF_MILLIS = 500;

  private final Supplier<? extends Credentials> credentials;
  private final int timeoutMillis;
  private final int maxAttempts;

  public HttpSecretManagerClient(Supplier<? extends Credentials> credentials, Duration timeout, int maxAttempts) {
    this.credentials = credentials;
    this.timeoutMillis = (int) timeout.toMillis();
    this.maxAttempts = maxAttempts;
  }

  @Override
  public String accessSecretVersion(String endpoint, String versionResourceName) {
    URI uri = URI.create(endpoint + "/v1/" + versionResourceName + ":access");
    for (int attempt = 1;; attempt++) {
      try {
        return decodePayload(versionResourceName, call(uri, versionResourceName));
      } catch (TransientException e) {
        if (attempt >= maxAttempts) {
          throw new GsmPropertiesProviderException("Failed to access secret " + versionResourceName + " after "
              + attempt + " attempt(s): " + e.getMessage(), e.getCause());
        }
        long backoff = INITIAL_BACKOFF_MILLIS << (attempt - 1);
        LOGGER.warn("Transient error accessing secret {} (attempt {}/{}), retrying in {} ms: {}",
                    versionResourceName, attempt, maxAttempts, backoff, e.getMessage());
        sleep(backoff);
      }
    }
  }

  private String call(URI uri, String versionResourceName) throws TransientException {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) uri.toURL().openConnection();
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(timeoutMillis);
      connection.setReadTimeout(timeoutMillis);
      connection.setRequestProperty("Accept", "application/json");
      for (Map.Entry<String, List<String>> header : credentials.get().getRequestMetadata(uri).entrySet()) {
        for (String value : header.getValue()) {
          connection.addRequestProperty(header.getKey(), value);
        }
      }

      int status = connection.getResponseCode();
      String body = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
      if (status == 200) {
        return body;
      }
      String message = "HTTP " + status + ": " + errorMessage(body);
      if (status == 429 || status >= 500) {
        throw new TransientException(message, null);
      }
      throw new GsmPropertiesProviderException("Failed to access secret " + versionResourceName + " - " + message
          + hint(status));
    } catch (IOException e) {
      throw new TransientException(e.toString(), e);
    } finally {
      if (connection != null) {
        connection.disconnect();
      }
    }
  }

  private static String hint(int status) {
    switch (status) {
      case 401:
      case 403:
        return ". Check that the identity used by the app (e.g. the GCP service account bound to the pod's "
            + "Kubernetes service account via Workload Identity) has roles/secretmanager.secretAccessor on the secret";
      case 404:
        return ". Check the secret name, project, version and (for regional secrets) the location";
      default:
        return "";
    }
  }

  private static String decodePayload(String versionResourceName, String body) {
    try {
      JsonObject payload = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("payload");
      JsonElement data = payload == null ? null : payload.get("data");
      // An empty secret version is returned without the "data" field.
      if (data == null || data.isJsonNull()) {
        return "";
      }
      return new String(Base64.getDecoder().decode(data.getAsString()), StandardCharsets.UTF_8);
    } catch (RuntimeException e) {
      throw new GsmPropertiesProviderException("Unexpected response accessing secret " + versionResourceName, e);
    }
  }

  private static String errorMessage(String body) {
    try {
      JsonObject error = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("error");
      if (error != null && error.has("message")) {
        return error.get("message").getAsString();
      }
    } catch (JsonParseException | IllegalStateException e) {
      // not a JSON error body
    }
    return body.length() > 300 ? body.substring(0, 300) + "..." : body;
  }

  private static String read(InputStream in) throws IOException {
    if (in == null) {
      return "";
    }
    try (InputStream stream = in) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new GsmPropertiesProviderException("Interrupted while retrying Secret Manager call", e);
    }
  }

  private static final class TransientException extends Exception {

    private static final long serialVersionUID = 1L;

    TransientException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
