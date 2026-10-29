# Automatic restart on secret change (GKE + Runtime Fabric)

The properties provider reads secrets when the Mule app starts. This guide makes Kubernetes restart the
apps whenever a secret they use gets a new version in Secret Manager, so you never have to restart them
by hand.

```
GSM new version ──► GKE Secret Sync ──► K8s Secret "mule-gsm-secrets" ──► Reloader ──► rolling restart
                    (rotation poll)      (apps namespace)                   (annotation set by the RTF
                                                                             KubernetesTemplate)
```

The apps never read the Kubernetes Secret. They keep reading Secret Manager through the provider. The
Kubernetes Secret only acts as a **change trigger** that Reloader can watch.

## Components

| Component | Role | Manifest |
|---|---|---|
| GKE Secret Sync (managed add-on) | Copies Secret Manager secrets into a Kubernetes Secret. With rotation on, it polls for new versions | [`kubernetes/secret-sync/secret-sync.yaml`](../kubernetes/secret-sync/secret-sync.yaml) |
| [Reloader](https://github.com/stakater/Reloader) | Watches the Kubernetes Secret and does a rolling restart of the Deployments that name it | [`kubernetes/reloader/values.yaml`](../kubernetes/reloader/values.yaml) |
| RTF `KubernetesTemplate` | Adds the Reloader annotation to every Mule app Deployment created by RTF | [`kubernetes/rtf/kubernetes-template.yaml`](../kubernetes/rtf/kubernetes-template.yaml) |
| Workload Identity IAM | Lets the apps and the sync controller read the secrets without keys | [`scripts/setup-gcp-iam.sh`](../scripts/setup-gcp-iam.sh) |

## Prerequisites

- GKE 1.33 or later, with Workload Identity Federation for GKE (it is on by default in Autopilot).
- Runtime Fabric installed on the cluster, with Mule apps deployed to a known namespace. This guide calls
  it `RTF_APPS_NAMESPACE`.
- `gcloud` 378.0.0 or later, `kubectl`, and `helm`.

## Step 1: enable Secret Sync with rotation

```bash
gcloud container clusters update CLUSTER_NAME --location=LOCATION \
  --enable-secret-sync \
  --enable-secret-sync-rotation \
  --secret-sync-rotation-interval=120s
```

The rotation interval is the longest time between a new secret version and the restart that picks it up.
The minimum is 60s and the default is 2 minutes.

## Step 2: grant access with Workload Identity

```bash
PROJECT_ID=my-gke-project SECRET_PROJECT_ID=my-secrets-project NAMESPACE=RTF_APPS_NAMESPACE \
SECRETS="demo-api-key demo-db-credentials" ./scripts/setup-gcp-iam.sh
```

The script grants `roles/secretmanager.secretAccessor` on each secret to two principals:

- **Every Kubernetes service account in the apps namespace**:
  `principalSet://.../namespace/RTF_APPS_NAMESPACE`. RTF creates one service account per app with
  generated names, so a binding per account would not cover apps deployed later. To use one account per
  app instead, bind each app's account with `principal://.../subject/ns/NS/sa/KSA`.
- **The `gsm-secret-sync` service account**, which the sync controller uses.

The provider then uses the pod's identity through the GKE metadata server. The app needs no
`credentialsFile` and no key.

## Step 3: sync the secrets into the apps namespace

Edit [`kubernetes/secret-sync/secret-sync.yaml`](../kubernetes/secret-sync/secret-sync.yaml). Replace
`RTF_APPS_NAMESPACE` and `SECRET_PROJECT_ID`, and list every secret used by the apps in that namespace
with `versions/latest`. Then apply it:

```bash
kubectl apply -f kubernetes/secret-sync/secret-sync.yaml
kubectl describe secretsync mule-gsm-secrets -n RTF_APPS_NAMESPACE
kubectl get secret mule-gsm-secrets -n RTF_APPS_NAMESPACE
```

Secret Sync requires each secret version to be in the same region as the cluster. Global secrets are
replicated automatically. For user-managed replication, make sure that the cluster's region is included.

## Step 4: install Reloader

```bash
helm repo add stakater https://stakater.github.io/stakater-charts
helm repo update
helm upgrade --install reloader stakater/reloader -n reloader --create-namespace \
  -f kubernetes/reloader/values.yaml
```

## Step 5: annotate every Mule app with a KubernetesTemplate

```bash
sed -i.bak 's/RTF_APPS_NAMESPACE/my-apps-namespace/' kubernetes/rtf/kubernetes-template.yaml
kubectl apply -f kubernetes/rtf/kubernetes-template.yaml
```

The template adds this annotation to every Mule app Deployment:

```yaml
secret.reloader.stakater.com/reload: "mule-gsm-secrets"
```

Points to know:

- The template must be named `mule-application`. Create it in the apps namespace to cover the apps in that
  namespace, or in the RTF agent namespace to cover every app managed by that agent.
- **It only affects new deployments.** Redeploy existing apps once (Runtime Manager > app > Settings >
  **Apply Changes**) so that their Deployments get the annotation.
- The template uses the explicit `reload` annotation, not `reloader.stakater.com/auto` or search/match mode.
  Those modes only react to Secrets that the pod references in env vars or volumes. The Mule pods do not
  reference `mule-gsm-secrets`, so only the explicit annotation works.

Check that the annotation is on the app's Deployment:

```bash
kubectl get deploy -n RTF_APPS_NAMESPACE -o \
  custom-columns='NAME:.metadata.name,RELOAD:.metadata.annotations.secret\.reloader\.stakater\.com/reload'
```

## Step 6: test a rotation

```bash
echo -n "new-value" | gcloud secrets versions add demo-api-key --data-file=- --project=SECRET_PROJECT_ID

# Within the rotation interval, the Kubernetes Secret changes...
kubectl get secret mule-gsm-secrets -n RTF_APPS_NAMESPACE -o jsonpath='{.metadata.resourceVersion}'
# ...Reloader logs the reload...
kubectl logs -n reloader deploy/reloader-reloader | grep -i mule-gsm-secrets
# ...and the app pods are replaced one by one.
kubectl get pods -n RTF_APPS_NAMESPACE -w
```

The new pods log `Reading secret projects/.../secrets/demo-api-key/versions/latest from GCP Secret Manager`.

## Design choices and trade-offs

**One trigger Secret per namespace.** The KubernetesTemplate cannot vary per app: it is applied as is
to every Deployment in its scope. So every app in the namespace names the same Kubernetes Secret, and a
change to any listed secret restarts every app in that namespace. Rolling restarts keep apps with 2 or more
replicas available. To limit the blast radius, you can:

- group apps that share secrets into their own namespace, each with its own `SecretSync` and template, or
- list only the secrets whose rotation should restart apps automatically. Placeholders for other secrets
  still resolve at startup and pick up new versions on the next restart.

**Pinned versions do not change.** A placeholder with `/versions/N`, or a `defaultVersion` alias, resolves
to the same value after a restart. Only `latest`, or an alias that you move, picks up new values.

**Restart policy versus RTF.** Reloader patches the Deployment's pod template, which starts a normal
Kubernetes rolling update. With `reloadStrategy: annotations` it adds an annotation. With the default
strategy it adds an environment variable. Validate this in a non-production cluster with your RTF agent
version, and check that the RTF agent does not revert the change when it reconciles the Deployment.

**The synced Secret holds the values.** GKE Secret Sync copies the payload; it cannot sync only a hash.
Protect the namespace with RBAC and consider application-layer secrets encryption.

## Alternatives

- **External Secrets Operator** can replace GKE Secret Sync (`ExternalSecret` with a `refreshInterval`). The
  Reloader and KubernetesTemplate steps do not change.
- **No automation.** Restart or redeploy the app from Runtime Manager, or run
  `kubectl rollout restart deployment/<app> -n RTF_APPS_NAMESPACE`, after you rotate a secret.
