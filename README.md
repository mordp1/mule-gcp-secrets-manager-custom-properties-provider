# GCP Secret Manager Properties Provider for Mule 4

A Mule 4 [custom configuration properties provider](https://docs.mulesoft.com/mule-runtime/latest/custom-configuration-properties-provider)
that resolves `${gsm::secret-name}` placeholders from
[Google Cloud Secret Manager](https://cloud.google.com/secret-manager) when the application starts.

MuleSoft ships providers for AWS and Azure but not for Google Cloud. This project fills that gap. It was
inspired by [mule-aws-secrets-manager-properties-provider](https://github.com/mulesoft-catalyst/mule-aws-secrets-manager-properties-provider).

```xml
<gcp-secret-manager:config name="GCP_Secret_Manager_Config" projectId="${gcp.projectId}"/>

<db:config name="Database_Config">
    <db:my-sql-connection host="${db.host}" user="${gsm::db-credentials#username}"
                          password="${gsm::db-credentials#password}"/>
</db:config>
```

- **No secrets in the app or in Runtime Manager.** Secret Manager is the source of truth.
- **No keys to manage.** Authentication uses Application Default Credentials: GKE Workload Identity on
  Runtime Fabric, `gcloud auth application-default login` on a laptop.
- **Fresh values on every start.** Restarting the app is enough to pick up a new secret version. On GKE
  you can automate the restart with [Reloader](#automatic-restart-on-secret-change-gke--rtf).
- **Fails fast.** A missing secret or a permission error stops the deployment with a clear message
  instead of starting with a wrong value.
- **Small.** It calls the Secret Manager REST API with `google-auth-library` and Gson. It does not use
  gRPC, Netty, Guava or Protobuf, so it does not cause classloading conflicts with other connectors.

## How it works

```
 STARTUP (every app start)
 ─────────────────────────

  ┌──────────────────────┐   ${gsm::secret-name}   ┌──────────────────────┐
  │   Mule app (RTF/GKE) │ ──────────────────────► │  GCP Secret Manager  │
  │  + GSM custom        │ ◄────────────────────── │  (source of truth)   │
  │    properties        │     secret value        └──────────────────────┘
  │    provider          │   (Workload Identity,
  └──────────────────────┘    no JSON key)


 SECRET UPDATE (automatic restart)
 ─────────────────────────────────

  1. Update secret       ┌──────────────────────┐
     in GSM  ──────────► │  GCP Secret Manager  │
                         └──────────┬───────────┘
                                    │ 2. Sync (GKE Secret Sync)
                                    ▼
                         ┌──────────────────────┐
                         │  K8s Secret          │
                         │  (app namespace)     │
                         └──────────┬───────────┘
                                    │ 3. Reloader detects the change
                                    ▼
                         ┌──────────────────────┐
                         │  Reloader            │ annotation added to all
                         │  rolling restart     │ Deployments via RTF
                         └──────────┬───────────┘ KubernetesTemplate
                                    │
                                    ▼
                         ┌──────────────────────┐
                         │  Mule app restarts   │ 4. Provider reads the
                         │  and loads new value │    new value from GSM
                         └──────────────────────┘
```

Mule resolves every `${gsm::...}` placeholder once, while the application starts. The provider calls
`projects/*/secrets/*/versions/*:access` once per secret version, keeps the value in memory, and never
calls Secret Manager again until the next start. Several placeholders that point to fields of the same
JSON secret share one call.

## Compatibility

| | |
|---|---|
| Mule runtime | 4.6.0 or later, on Java 17 or 21 (the plugin declares `minMuleVersion` 4.6.0) |
| Tested on | Mule 4.9.0 (Java 17), end to end with a demo app |
| Deployment targets | Runtime Fabric (GKE or any Kubernetes), standalone/hybrid, CloudHub 2.0. See [Authentication](#authentication) |
| Built against | `mule-modules-parent` 1.11.7 (Mule 4.11 APIs) |

## Installation

The plugin is not on a public Maven repository yet. Use one of these options:

**Option A: publish to your Anypoint Exchange (recommended for teams).** Exchange requires the Maven
`groupId` to be your organization ID. The script below publishes a copy of the POM with that `groupId`.

```bash
./scripts/deploy-to-exchange.sh <ANYPOINT_ORG_ID> 1.0.0
```

It reads the credentials from the `anypoint-exchange-v3` server in `~/.m2/settings.xml`. The script header
shows the expected format for a Connected App.

**Option B: install in your local Maven repository** (to try it out):

```bash
mvn clean install
```

Then add the dependency to the Mule application's `pom.xml`:

```xml
<dependency>
    <groupId>io.github.mulegcp</groupId>   <!-- or your Anypoint org ID (option A) -->
    <artifactId>mule-gcp-secret-manager-properties-provider</artifactId>
    <version>1.0.0</version>
    <classifier>mule-plugin</classifier>
</dependency>
```

## Usage

### 1. Declare the global element

In Anypoint Studio, open **Global Elements > Create** and choose **GCP Secret Manager Properties
Provider Config**. In XML:

```xml
<mule xmlns:gcp-secret-manager="http://www.mulesoft.org/schema/mule/gcp-secret-manager"
      xsi:schemaLocation="...
        http://www.mulesoft.org/schema/mule/gcp-secret-manager http://www.mulesoft.org/schema/mule/gcp-secret-manager/current/mule-gcp-secret-manager.xsd">

    <configuration-properties file="config.yaml"/>
    <gcp-secret-manager:config name="GCP_Secret_Manager_Config" projectId="${gcp.projectId}"/>
</mule>
```

Declare one `gcp-secret-manager:config` per application. Its parameters can use `${...}` placeholders from
other providers such as `configuration-properties`, system properties or Runtime Manager properties.
They cannot use DataWeave expressions, because they are resolved before the app starts.

### 2. Reference secrets

| Placeholder | Resolves to |
|---|---|
| `${gsm::db-password}` | The `defaultVersion` (default `latest`) of secret `db-password` in `projectId` |
| `${gsm::db-password/versions/3}` | Version 3 of that secret. A version alias also works |
| `${gsm::db-credentials#password}` | Field `password` of a JSON secret such as `{"username":"app","password":"..."}` |
| `${gsm::projects/shared-prj/secrets/api-key}` | A secret in another project |
| `${gsm::projects/p/locations/us-central1/secrets/api-key/versions/2}` | A regional secret |

All forms can be combined. For example, `${gsm::projects/p/secrets/db/versions/5#password}` is valid.

JSON fields are top-level only. Numbers and booleans are returned as text, and nested objects or arrays are
returned as JSON text.

### 3. Configuration reference

| Parameter | Required | Default | Description |
|---|---|---|---|
| `projectId` | no | auto-detected | Project used for short names. When empty, the provider tries `GOOGLE_CLOUD_PROJECT`/`GCLOUD_PROJECT`, then the credentials (service account key or quota project), then the GCE/GKE metadata server |
| `location` | no | (global) | Region of [regional secrets](https://cloud.google.com/secret-manager/regional-secrets/regional-secrets-overview), for example `us-central1`. The provider then calls `secretmanager.<location>.rep.googleapis.com` |
| `defaultVersion` | no | `latest` | Version used when a placeholder does not set one. Pin a version alias here (for example `prod`) to promote secrets explicitly |
| `credentialsFile` | no | (ADC) | Path to a credentials JSON on the filesystem or in the app's classpath. This can be a service account key or a Workload Identity Federation config (`external_account`). Leave empty to use Application Default Credentials |
| `endpoint` | no | Google API | Overrides the REST base URL, for example a Private Service Connect endpoint or a test server |
| `timeoutSeconds` | no | `10` | Connect and read timeout of each call |
| `maxAttempts` | no | `3` | Attempts on HTTP 429/5xx and I/O errors, with exponential backoff starting at 500 ms |

## Authentication

The provider uses [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials)
unless you set `credentialsFile`. The identity needs `roles/secretmanager.secretAccessor` on each secret,
or on the project.

| Where the app runs | What to do |
|---|---|
| **Runtime Fabric on GKE** | Use [Workload Identity Federation for GKE](https://cloud.google.com/kubernetes-engine/docs/how-to/workload-identity). RTF creates one Kubernetes service account per app, so grant the role to the whole namespace with `principalSet://iam.googleapis.com/projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/PROJECT_ID.svc.id.goog/namespace/NAMESPACE`. [`scripts/setup-gcp-iam.sh`](scripts/setup-gcp-iam.sh) does this |
| **Local (Studio / standalone)** | Run `gcloud auth application-default login`. Your user needs access to the secrets |
| **Outside Google Cloud** (CloudHub 2.0, RTF on EKS/AKS, on-prem) | Prefer a [Workload Identity Federation](https://cloud.google.com/iam/docs/workload-identity-federation) credential config file. It holds no secret. A service account key works too, but it is a long-lived secret that you must protect and rotate |

## Automatic restart on secret change (GKE + RTF)

The provider reads secrets only at startup, so a new secret version needs a restart. On GKE, these pieces
automate it:

1. The **GKE Secret Sync** add-on copies the secrets into a Kubernetes Secret in the apps namespace, and its
   rotation polls for new versions.
2. **Reloader** watches that Kubernetes Secret and starts a rolling restart of the Deployments that name it.
3. An **RTF `KubernetesTemplate`** adds the Reloader annotation to every Mule app Deployment.
4. The restarted pods read the new version through the provider.

See [docs/kubernetes-auto-restart.md](docs/kubernetes-auto-restart.md) for the full setup and the
manifests in [`kubernetes/`](kubernetes).

## Deploying from Runtime Manager

[docs/runtime-manager-guide.md](docs/runtime-manager-guide.md) walks through building the app, deploying it
to Runtime Fabric from the Anypoint Runtime Manager console, setting properties per environment, and
rotating a secret.

## Demo application

[`examples/demo-app`](examples/demo-app) is a small Mule app with one HTTP endpoint, `GET /secrets`. It reads
a plain secret and two fields of a JSON secret, and returns them masked.

```bash
mvn clean install                      # install the plugin locally first
cd examples/demo-app && mvn clean package
```

## Error messages

The deployment fails with messages like these:

| Message contains | Meaning |
|---|---|
| `HTTP 404 ... Check the secret name, project, version` | The secret or version does not exist. For regional secrets, also check `location` |
| `HTTP 403 ... roles/secretmanager.secretAccessor` | The app's identity cannot read the secret. Check the IAM binding and Workload Identity |
| `Unable to load Google credentials` | No ADC was found. On GKE, check that Workload Identity is enabled on the node pool. Locally, run `gcloud auth application-default login` |
| `no GCP project` | A short name was used, `projectId` is empty, and no project could be detected |
| `field 'x' not found in the JSON secret` / `not a JSON object` | Fix the `#field` part of the placeholder or the secret content |
| `after N attempt(s)` | Secret Manager kept returning 429/5xx or could not be reached. Check egress, DNS and Private Google Access |

Set `io.github.mulegcp.secretmanager` to `DEBUG` in `log4j2.xml` to see which credentials are used.
Secret values are never logged.

## Security notes

- The provider keeps secret values in memory for the life of the application, like any other property.
  Do not log them or return them from flows.
- A secret value that contains `${...}` is resolved again by Mule as a placeholder.
- In the auto-restart setup, the synced Kubernetes Secret contains the secret values, because that is how GKE
  Secret Sync works. Restrict `get`/`list` on Secrets in the apps namespace with RBAC. Consider
  [application-layer secrets encryption](https://cloud.google.com/kubernetes-engine/docs/how-to/encrypting-secrets).

## Building

Requirements: JDK 17 and Maven 3.9+.

```bash
mvn clean verify
```

The unit tests run against an in-process fake Secret Manager endpoint and need no GCP access.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE). This is a community project. It is not supported by MuleSoft, Salesforce or Google.
