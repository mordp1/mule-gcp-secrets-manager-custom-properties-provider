# Demo app

A minimal Mule 4 app that resolves secrets from GCP Secret Manager with the properties provider. It has
one endpoint, `GET /secrets`, which returns masked values.

## Secrets used

```bash
echo -n 'abc123xyz' | gcloud secrets create demo-api-key --data-file=- --project=MY_PROJECT
echo -n '{"username":"app_user","password":"Sup3rS3cret"}' | \
  gcloud secrets create demo-db-credentials --data-file=- --project=MY_PROJECT
```

## Run

```bash
(cd ../.. && mvn clean install)          # install the plugin
mvn clean package                        # build the app
gcloud auth application-default login    # local credentials
```

Deploy `target/*-mule-application.jar` to a Mule 4.9+ runtime (Studio, standalone, or Runtime Fabric) with
the property `gcp.projectId=MY_PROJECT`. Then call the endpoint:

```bash
curl localhost:8081/secrets
# {"apiKeyLength": 9, "dbUser": "app_user", "dbPasswordMasked": "S****"}
```

The app uses HTTP connector 1.10.x so that it still deploys on Mule 4.9.0. Newer connector versions
declare Java 25 support, and 4.9.0 runtimes fail to load that.
