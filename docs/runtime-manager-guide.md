# Using the provider from Anypoint Studio and Runtime Manager

This guide follows one Mule application from development to a Runtime Fabric deployment on GKE, managed
from the Anypoint Platform console (Runtime Manager). The [demo app](../examples/demo-app) is used as the
example.

## 1. Make the plugin available to your organization

Publish the plugin to your Anypoint Exchange once:

```bash
./scripts/deploy-to-exchange.sh <ANYPOINT_ORG_ID> 1.0.0
```

It then appears in Exchange as **GCP Secret Manager Properties Provider**. In Anypoint Studio, open
**Add Modules > Search in Exchange** and add it to any project.

## 2. Configure the application (Studio)

1. Add the module. Studio adds this dependency to the `pom.xml`:

   ```xml
   <dependency>
       <groupId>ANYPOINT_ORG_ID</groupId>
       <artifactId>mule-gcp-secret-manager-properties-provider</artifactId>
       <version>1.0.0</version>
       <classifier>mule-plugin</classifier>
   </dependency>
   ```

2. Open **Global Elements > Create > GCP Secret Manager Properties Provider Config** and set:
   - **Project ID**: `${gcp.projectId}`. This keeps the project configurable per environment.
   - Leave the other fields empty. The **Advanced** tab holds the credentials file, endpoint, timeout and
     retries.

3. Keep non-secret settings in `src/main/resources/config.yaml`:

   ```yaml
   gcp:
     projectId: "my-dev-project"
   ```

4. Replace each secret value in connector configs with a `gsm::` placeholder:

   | Before | After |
   |---|---|
   | `password="${secure::db.password}"` | `password="${gsm::orders-db-credentials#password}"` |
   | `clientSecret="${oauth.secret}"` | `clientSecret="${gsm::orders-oauth-client-secret}"` |

5. Run the app locally from Studio after running `gcloud auth application-default login` in a terminal.
   Your Google user needs `Secret Manager Secret Accessor` on the secrets of the dev project.

## 3. Create the secrets (Google Cloud)

```bash
echo -n 'abc123xyz' | gcloud secrets create orders-oauth-client-secret --data-file=- --project=my-prod-project
echo -n '{"username":"orders","password":"S3cr3t!"}' | \
  gcloud secrets create orders-db-credentials --data-file=- --project=my-prod-project
```

Grant the app's identity access. On RTF on GKE, grant the whole apps namespace through Workload Identity:

```bash
PROJECT_ID=my-gke-project SECRET_PROJECT_ID=my-prod-project NAMESPACE=rtf-prod \
SECRETS="orders-oauth-client-secret orders-db-credentials" ./scripts/setup-gcp-iam.sh
```

## 4. Deploy from Runtime Manager

1. Go to **Runtime Manager > Applications > Deploy application**.
2. **Deployment target**: your Runtime Fabric on GKE.
3. **Application file**: choose the app from Exchange, or upload the jar built by `mvn clean package`.
4. **Runtime** tab: Mule 4.6 or later on Java 17. Set replicas, vCores and memory as usual.
5. **Properties** tab: add only **non-secret** values that differ per environment:

   | Key | Value |
   |---|---|
   | `gcp.projectId` | `my-prod-project` |

   No password, token or key is needed here or in **Protect** (safely hidden) properties. The secrets stay in
   Secret Manager.
6. Click **Deploy Application**.

During startup the app logs:

```
INFO  GsmConfigurationPropertiesProviderFactory: Creating GCP Secret Manager properties provider (project=my-prod-project, location=global, defaultVersion=latest)
INFO  GsmConfigurationPropertiesProvider: Reading secret projects/my-prod-project/secrets/orders-db-credentials/versions/latest from GCP Secret Manager
```

If a secret cannot be read, the deployment fails, and the **Logs** tab shows the reason. See
[Error messages](../README.md#error-messages).

## 5. Promote to another environment

Deploy the same artifact to another environment or RTF namespace and change only `gcp.projectId` in the
**Properties** tab. Alternatively, keep one project and add an environment prefix to the secret names:

```xml
<!-- Property set in Runtime Manager: env = prod -->
<db:my-sql-connection password="${gsm::${env}-orders-db-credentials#password}" .../>
```

Test nested placeholders in your runtime version before you rely on them.

## 6. Rotate a secret

1. Add a new version in Secret Manager:

   ```bash
   echo -n '{"username":"orders","password":"N3wS3cr3t!"}' | \
     gcloud secrets versions add orders-db-credentials --data-file=- --project=my-prod-project
   ```

2. Make the app read it. Use either option:
   - **Manual**: in Runtime Manager, open the app and redeploy it with **Apply Changes**, or run
     `kubectl rollout restart deployment/<app-name> -n <apps-namespace>`. Each new pod reads the latest version
     at startup.
   - **Automatic**: with [GKE Secret Sync + Reloader + KubernetesTemplate](kubernetes-auto-restart.md), the
     restart happens within the sync rotation interval and needs no manual action.

3. Disable or destroy the old version once all apps run with the new one:

   ```bash
   gcloud secrets versions disable 1 --secret=orders-db-credentials --project=my-prod-project
   ```

## CloudHub 2.0 and other non-GKE targets

The provider works anywhere the app can reach `secretmanager.googleapis.com`. Without a GKE metadata
server, Application Default Credentials are not available, so set **Credentials file** on the config:

- **Preferred**: a [Workload Identity Federation](https://cloud.google.com/iam/docs/workload-identity-federation)
  credential configuration (`external_account` JSON). It holds no secret and trusts an identity provider that
  your platform can authenticate to.
- **Last resort**: a service account key. It is a long-lived secret, so do not commit it to the
  application. Mount it as a file on the runtime host and point `credentialsFile` at its path. Rotate it
  regularly.
