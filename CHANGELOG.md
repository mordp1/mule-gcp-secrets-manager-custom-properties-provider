# Changelog

## 1.0.0 (unreleased)

- `${gsm::...}` placeholders resolved from Google Cloud Secret Manager at application startup.
- Short names, pinned versions and aliases, full resource names (other projects), regional secrets,
  and top-level fields of JSON secrets (`#field`).
- Application Default Credentials (GKE Workload Identity, gcloud ADC, metadata server) or a credentials file.
- One call per secret version, retries with exponential backoff on HTTP 429/5xx, and fail-fast errors with hints.
- Kubernetes examples for automatic restarts: GKE Secret Sync + Reloader + RTF KubernetesTemplate.
