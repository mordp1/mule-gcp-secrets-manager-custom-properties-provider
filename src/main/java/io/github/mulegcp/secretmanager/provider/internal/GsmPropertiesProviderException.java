/*
 * Copyright 2026 The mule-gcp-secret-manager-properties-provider authors.
 * Licensed under the Apache License, Version 2.0. See LICENSE in the project root.
 */
package io.github.mulegcp.secretmanager.provider.internal;

/**
 * Raised when a {@code ${gsm::...}} placeholder cannot be resolved. It is unchecked on purpose: Mule
 * aborts the deployment, so an application never starts with a missing or wrong secret.
 */
public class GsmPropertiesProviderException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public GsmPropertiesProviderException(String message) {
    super(message);
  }

  public GsmPropertiesProviderException(String message, Throwable cause) {
    super(message, cause);
  }
}
