#!/usr/bin/env bash
# Grants Secret Manager access to the Mule apps (and to the GKE secret sync controller) through
# Workload Identity Federation for GKE. No service account keys are created.
#
# Usage:
#   PROJECT_ID=my-gke-project SECRET_PROJECT_ID=my-secrets-project NAMESPACE=rtf-apps \
#   SECRETS="demo-api-key demo-db-credentials" ./scripts/setup-gcp-iam.sh
#
# PROJECT_ID         project that hosts the GKE cluster (owner of the workload identity pool)
# SECRET_PROJECT_ID  project that hosts the secrets (defaults to PROJECT_ID)
# NAMESPACE          Kubernetes namespace where RTF deploys the Mule apps
# SECRETS            space-separated list of Secret Manager secret IDs
# SYNC_KSA           Kubernetes service account used by SecretSync (default: gsm-secret-sync)
set -euo pipefail

: "${PROJECT_ID:?set PROJECT_ID}"
: "${NAMESPACE:?set NAMESPACE}"
: "${SECRETS:?set SECRETS}"
SECRET_PROJECT_ID="${SECRET_PROJECT_ID:-$PROJECT_ID}"
SYNC_KSA="${SYNC_KSA:-gsm-secret-sync}"

PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
POOL="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${PROJECT_ID}.svc.id.goog"

# RTF creates one Kubernetes service account per app by default, with names you do not control.
# Granting the whole namespace covers every app in it, including apps deployed later.
APPS_PRINCIPAL="principalSet://iam.googleapis.com/${POOL}/namespace/${NAMESPACE}"
SYNC_PRINCIPAL="principal://iam.googleapis.com/${POOL}/subject/ns/${NAMESPACE}/sa/${SYNC_KSA}"

for secret in $SECRETS; do
  for member in "$APPS_PRINCIPAL" "$SYNC_PRINCIPAL"; do
    echo "Granting roles/secretmanager.secretAccessor on ${secret} to ${member}"
    gcloud secrets add-iam-policy-binding "$secret" \
      --project="$SECRET_PROJECT_ID" \
      --role=roles/secretmanager.secretAccessor \
      --member="$member" \
      --condition=None >/dev/null
  done
done

echo "Done. Apps in namespace '${NAMESPACE}' can now read: ${SECRETS}"
