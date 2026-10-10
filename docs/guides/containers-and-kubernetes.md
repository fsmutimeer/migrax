# Containers and Kubernetes

In container platforms, migrations usually run as their own step before the new version of
the application starts: a Kubernetes Job, an init container, or a deploy hook. Migrax has an
official container image for this, and it runs with just the migration files; no Java project
or build is needed.

!!! note "Available from Migrax 0.3.0"

    The image is published with Migrax 0.3.0 and newer. Until 0.3.0 is out, test it with the
    pre-release tag `0.3.0-rc.1`.

## The container image

```
ghcr.io/fsmutimeer/migrax:0.3.0
```

It has Java, Migrax and these JDBC drivers: PostgreSQL (also for CockroachDB), MariaDB (also
for MySQL servers), SQL Server, SQLite and H2. It runs as a non-root user, for `amd64` and
`arm64`. Migrations are read from `/migrations`.

```console
$ docker run --rm -v "$PWD/src/main/resources/db/migration:/migrations:ro" \
    -e MIGRAX_DATABASE_URL=jdbc:postgresql://db:5432/shop \
    -e MIGRAX_DATABASE_USER=shop -e MIGRAX_DATABASE_PASSWORD_FILE=/run/secrets/db-password \
    -v "$PWD/db-password:/run/secrets/db-password:ro" \
    ghcr.io/fsmutimeer/migrax:0.3.0 migrate
Applying migration '0001_initial.sql'...
Successfully applied migration '0001_initial.sql'.
Applied 1 migration(s); 1 total in /migrations.
```

`migrate`, `status`, `rollback`, `repair`, `clean`, `lint` and `drift` work in the image.
`generate`, `check` and `verify` read your entities, so they run where your project is built
(your machine or CI), not in the image.

### Your migrations in an image

Usually the migrations are baked into an image built with each version of the application:

```dockerfile
FROM ghcr.io/fsmutimeer/migrax:0.3.0
COPY src/main/resources/db/migration/ /migrations/
# The snapshot lets 'migrax drift' compare the database with what the migrations produce.
COPY .migrax/snapshot.json /workspace/.migrax/snapshot.json
```

### Other drivers

MySQL Connector/J and Oracle's driver aren't included, because their licenses differ from
Migrax's. Add them in your image:

```dockerfile
FROM ghcr.io/fsmutimeer/migrax:0.3.0
ADD --chmod=644 https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar /opt/migrax/drivers/
```

Every jar in `/opt/migrax/drivers` (or in the folder `MIGRAX_DRIVERS` names) is available to
Migrax. Outside the image the same works with `--classpath <driver.jar>` or a `drivers` folder
next to Migrax's `lib` folder.

## Database logins in containers

Kubernetes and Docker mount secrets as files. Point Migrax at them:

| Variable | Contains |
|---|---|
| `MIGRAX_DATABASE_URL` or `MIGRAX_DATABASE_URL_FILE` | the JDBC URL |
| `MIGRAX_DATABASE_USER` or `MIGRAX_DATABASE_USER_FILE` | the user |
| `MIGRAX_DATABASE_PASSWORD` or `MIGRAX_DATABASE_PASSWORD_FILE` | the password |

A `*_FILE` variable names a file; its content is used without the last line break. When both
forms are set, the plain variable wins. `--password-file <path>` does the same on the command
line.

### Logins with cloud identities

Cloud databases can accept short-lived tokens instead of passwords (AWS RDS with IAM, Google
Cloud SQL, Azure Database with Microsoft Entra ID). That works through the provider's JDBC
plugin: put its jar in the drivers folder (or a derived image) and use the URL form its
documentation gives. Migrax passes the URL and user through unchanged.

!!! warning "Not tested by the Migrax build"

    The Migrax build has no cloud accounts, so these logins are not tested by it. Check them
    in a test environment with `migrax doctor` before you rely on them.

## Running in Kubernetes

The manifests below are in the repository under
[`examples/kubernetes`](https://github.com/fsmutimeer/migrax/tree/main/examples/kubernetes).
Each was run on a local Kubernetes cluster (kind) against PostgreSQL.

They read the database login from a Secret named `shop-db` with the keys `url`, `user` and
`password` (`examples/kubernetes/secret.yaml`).

### A Job before the rollout (recommended)

Run the Job, wait for it, then roll out the application. A failed migration stops the
deployment and needs a person: `backoffLimit: 0` keeps Kubernetes from retrying it.

```yaml
apiVersion: batch/v1
kind: Job
metadata:
  name: shop-migrate
spec:
  backoffLimit: 0
  ttlSecondsAfterFinished: 3600
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: migrate
          image: registry.example.com/shop-migrations:1.0
          args: ["migrate", "--lock-timeout", "2m"]
          env:
            - name: MIGRAX_DATABASE_URL
              valueFrom: {secretKeyRef: {name: shop-db, key: url}}
            - name: MIGRAX_DATABASE_USER
              valueFrom: {secretKeyRef: {name: shop-db, key: user}}
            - name: MIGRAX_DATABASE_PASSWORD_FILE
              value: /run/secrets/shop-db/password
          volumeMounts:
            - {name: db-secret, mountPath: /run/secrets/shop-db, readOnly: true}
      volumes:
        - name: db-secret
          secret:
            secretName: shop-db
            items: [{key: password, path: password}]
```

```console
$ kubectl apply -f job.yaml
$ kubectl wait --for=condition=complete job/shop-migrate --timeout=5m
```

### With Helm or Argo CD

The same Job runs as a hook before each release. For Helm, add to its `metadata`:

```yaml
  annotations:
    "helm.sh/hook": pre-install,pre-upgrade
    "helm.sh/hook-delete-policy": before-hook-creation
```

For Argo CD, the annotation is `argocd.argoproj.io/hook: PreSync`.

### An init container in each pod

Simpler to set up: every pod migrates before the application starts. Pods that start together
wait for each other with `--lock-timeout`; the first one applies the migrations and the others
find nothing to do. From the test with three replicas:

```console
== pod/shop-7bf9684b9c-sxnq9
Applying migration '0001_initial.sql'...
Successfully applied migration '0001_initial.sql'.
Applied 1 migration(s); 1 total in /migrations.
== pod/shop-7bf9684b9c-5n79b
Another Migrax process holds the migration lock; waiting up to 5m...
Nothing to migrate: all 1 migration(s) are applied.
```

The manifest is `examples/kubernetes/init-container.yaml`. Give the lock timeout more time than
your longest migration takes. Without `--lock-timeout`, the second pod fails at once with
"Another Migrax process is applying migrations" and Kubernetes restarts it.

### Detecting manual changes every night

`migrax drift` exits with code 2 when the database differs from what the migrations produce,
so a CronJob fails and your cluster's alerting for failed jobs tells you
(`examples/kubernetes/drift-cronjob.yaml`):

```console
1 difference(s) between the database and the snapshot:
  - customers.hotfix: column exists in the database but not in the expected schema
Someone changed the database outside migrations. Write a migration for intended changes ('migrax new <name>'), or revert them.
```

## Startup integrations in several instances

The Spring Boot, Quarkus, Micronaut and Helidon integrations migrate when the application
starts. When several instances start at the same time, set `migrax.lock-timeout` (for example
`2m`) so they wait for each other instead of failing.
