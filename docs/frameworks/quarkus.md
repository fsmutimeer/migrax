# Quarkus

## Command line

Migrax reads `application.properties` / `application.yaml` with the active profile:

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/shop
quarkus.datasource.username=shop
quarkus.datasource.password=${DB_PASSWORD}
```

`QUARKUS_DATASOURCE_JDBC_URL` and the other Quarkus environment variables work too.

**Naming:** Quarkus keeps Hibernate's default naming, so columns are named exactly like fields
(`createdAt`). Migrax uses `jpa` naming, or the strategy in
`quarkus.hibernate-orm.physical-naming-strategy`.

Tell Hibernate not to change the schema itself. `validate` checks it against your entities
when the application starts; `none` leaves it alone:

```properties
quarkus.hibernate-orm.schema-management.strategy=validate
```

!!! warning "Not `update`"

    With `update`, Hibernate changes tables itself when the application starts, outside your
    migrations, and the next migration can fail with "already exists". `migrax doctor` warns
    about it.

## Migrate at startup

```xml
<dependency>
  <groupId>org.migrax</groupId>
  <artifactId>migrax-quarkus</artifactId>
  <version>0.6.0</version>
</dependency>
```

`migrax-quarkus` is a Quarkus extension. When the application starts, it applies the
migrations from `db/migration` before Hibernate starts, so `strategy=validate` checks the
migrated schema. It works in JVM mode and in native executables.

| Setting | Default | Meaning |
|---|---|---|
| `migrax.enabled` | `true` | Apply migrations at startup |
| `migrax.locations` | `db/migration` | Migration folders on the classpath, comma-separated |
| `migrax.java-package` | `db.migration` | Package of `JavaMigration` classes |
| `migrax.env` | | Environment: migrations marked `-- migrax:env <name>` run only there |
| `migrax.resume` | `false` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `migrax.lock-timeout` | `0` | How long to wait while another instance migrates, for example `2m` when several start together |
| `migrax.placeholders.<name>` | | Values for `${name}` placeholders |
| `migrax.schemas` | | Schemas to migrate one after another |

!!! note "Native executables"

    Quarkus lists the migrations while it builds the application, because a native executable
    can't look through its own files. Set `migrax.locations` and `migrax.java-package` before
    the build: in a native executable, a different value at runtime finds no new migrations.
    Migrations outside your application, in a library jar, are found only when that jar has a
    Jandex index.

## Readiness check

With `quarkus-smallrye-health` in the application, Migrax adds a `migrax` readiness check: UP
when every migration is applied and none failed, was edited or is missing, DOWN otherwise,
with the counts as data (`/q/health/ready`).

Without the health extension there is no readiness check, and nothing to configure.
