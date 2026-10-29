/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the provider end to end against a fake Secret Manager REST endpoint.
 */
class GsmConfigurationPropertiesProviderTest {

  private HttpServer server;
  private final Map<String, Response> responses = new ConcurrentHashMap<>();
  private final List<String> requestedPaths = new CopyOnWriteArrayList<>();
  private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      String path = exchange.getRequestURI().getPath();
      requestedPaths.add(path);
      authorizationHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
      Response response = responses.getOrDefault(path, Response.of(404,
          "{\"error\":{\"code\":404,\"message\":\"Secret [x] not found or has no versions.\",\"status\":\"NOT_FOUND\"}}"));
      byte[] body = response.next().getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(response.status(), body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private GsmConfigurationPropertiesProvider provider(String projectId, int maxAttempts) {
    GsmProviderSettings settings = new GsmProviderSettings(projectId, null, "latest",
        "http://127.0.0.1:" + server.getAddress().getPort());
    GoogleCredentials credentials = GoogleCredentials.create(new AccessToken("test-token", null));
    HttpSecretManagerClient client = new HttpSecretManagerClient(() -> credentials, Duration.ofSeconds(5), maxAttempts);
    return new GsmConfigurationPropertiesProvider(settings, client, () -> "detected-project");
  }

  private void secret(String resourceName, String value) {
    responses.put("/v1/" + resourceName + ":access", Response.of(200, accessResponse(resourceName, value)));
  }

  private static String accessResponse(String resourceName, String value) {
    String data = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    return "{\"name\":\"" + resourceName + "\",\"payload\":{\"data\":\"" + data + "\"}}";
  }

  private static Object resolve(GsmConfigurationPropertiesProvider provider, String key) {
    return provider.getConfigurationProperty(key).orElseThrow(AssertionError::new).getRawValue();
  }

  @Test
  void resolvesSecretWithBearerToken() {
    secret("projects/p/secrets/db-password/versions/latest", "s3cr3t-ção");

    assertEquals("s3cr3t-ção", resolve(provider("p", 1), "gsm::db-password"));
    assertEquals(List.of("Bearer test-token"), authorizationHeaders);
  }

  @Test
  void ignoresPlaceholdersOfOtherProviders() {
    GsmConfigurationPropertiesProvider provider = provider("p", 1);
    assertFalse(provider.getConfigurationProperty("secure::db.password").isPresent());
    assertFalse(provider.getConfigurationProperty("http.port").isPresent());
    assertTrue(requestedPaths.isEmpty());
  }

  @Test
  void pinnedVersionAndOtherProject() {
    secret("projects/other/secrets/api-key/versions/4", "v4");
    secret("projects/p/secrets/api-key/versions/2", "v2");

    GsmConfigurationPropertiesProvider provider = provider("p", 1);
    assertEquals("v4", resolve(provider, "gsm::projects/other/secrets/api-key/versions/4"));
    assertEquals("v2", resolve(provider, "gsm::api-key/versions/2"));
  }

  @Test
  void extractsJsonFieldsWithASingleCall() {
    secret("projects/p/secrets/db/versions/latest", "{\"user\":\"app\",\"password\":\"pw\",\"port\":5432}");

    GsmConfigurationPropertiesProvider provider = provider("p", 1);
    assertEquals("app", resolve(provider, "gsm::db#user"));
    assertEquals("pw", resolve(provider, "gsm::db#password"));
    assertEquals("5432", resolve(provider, "gsm::db#port"));
    assertEquals("{\"user\":\"app\",\"password\":\"pw\",\"port\":5432}", resolve(provider, "gsm::db"));
    assertEquals(1, requestedPaths.size());
  }

  @Test
  void missingJsonFieldFails() {
    secret("projects/p/secrets/db/versions/latest", "{\"user\":\"app\"}");
    GsmPropertiesProviderException e = assertThrows(GsmPropertiesProviderException.class,
        () -> provider("p", 1).getConfigurationProperty("gsm::db#password"));
    assertTrue(e.getMessage().contains("password"));
  }

  @Test
  void usesDetectedProjectWhenNotConfigured() {
    secret("projects/detected-project/secrets/s/versions/latest", "value");
    assertEquals("value", resolve(provider(null, 1), "gsm::s"));
  }

  @Test
  void notFoundFailsFastWithoutRetry() {
    GsmPropertiesProviderException e = assertThrows(GsmPropertiesProviderException.class,
        () -> provider("p", 3).getConfigurationProperty("gsm::missing"));
    assertTrue(e.getMessage().contains("projects/p/secrets/missing/versions/latest"));
    assertTrue(e.getMessage().contains("HTTP 404"));
    assertEquals(1, requestedPaths.size());
  }

  @Test
  void permissionDeniedExplainsTheRequiredRole() {
    responses.put("/v1/projects/p/secrets/s/versions/latest:access", Response.of(403,
        "{\"error\":{\"code\":403,\"message\":\"Permission denied\",\"status\":\"PERMISSION_DENIED\"}}"));
    GsmPropertiesProviderException e = assertThrows(GsmPropertiesProviderException.class,
        () -> provider("p", 3).getConfigurationProperty("gsm::s"));
    assertTrue(e.getMessage().contains("roles/secretmanager.secretAccessor"));
  }

  @Test
  void retriesTransientErrors() {
    String path = "/v1/projects/p/secrets/s/versions/latest:access";
    responses.put(path, new Response(new int[] {503, 200},
        new String[] {"{\"error\":{\"message\":\"unavailable\"}}", accessResponse("projects/p/secrets/s/versions/latest", "ok")}));

    assertEquals("ok", resolve(provider("p", 3), "gsm::s"));
    assertEquals(2, requestedPaths.size());
  }

  @Test
  void givesUpAfterMaxAttempts() {
    responses.put("/v1/projects/p/secrets/s/versions/latest:access", Response.of(503, "unavailable"));
    GsmPropertiesProviderException e = assertThrows(GsmPropertiesProviderException.class,
        () -> provider("p", 2).getConfigurationProperty("gsm::s"));
    assertTrue(e.getMessage().contains("after 2 attempt(s)"));
    assertEquals(2, requestedPaths.size());
  }

  @Test
  void emptySecretVersionResolvesToEmptyString() {
    responses.put("/v1/projects/p/secrets/empty/versions/latest:access",
                  Response.of(200, "{\"name\":\"projects/p/secrets/empty/versions/1\",\"payload\":{}}"));
    assertEquals("", resolve(provider("p", 1), "gsm::empty"));
  }

  /** Sequence of canned responses; the last one repeats. */
  private static final class Response {

    private final int[] statuses;
    private final String[] bodies;
    private final AtomicInteger index = new AtomicInteger();
    private volatile int current;

    Response(int[] statuses, String[] bodies) {
      this.statuses = statuses;
      this.bodies = bodies;
    }

    static Response of(int status, String body) {
      return new Response(new int[] {status}, new String[] {body});
    }

    String next() {
      current = Math.min(index.getAndIncrement(), statuses.length - 1);
      return bodies[current];
    }

    int status() {
      return statuses[current];
    }
  }
}
