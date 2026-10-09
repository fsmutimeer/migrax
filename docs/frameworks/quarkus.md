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

Tell Hibernate not to change the schema:

```properties
quarkus.hibernate-orm.schema-management.strategy=none
```

!!! warning "Not `update`"

    With `update`, Hibernate creates new tables when the application starts, before migrations
    run, and the migration then fails with "already exists". `migrax doctor` warns about it.

## Migrate at startup

```xml
<dependency>
  <groupId>io.migrax</groupId>
  <artifactId>migrax-quarkus</artifactId>
  <version>0.1.2</version>
</dependency>
```

Migrations from `db/migration` are applied when the application starts (JVM mode).

| Setting | Default | Meaning |
|---|---|---|
| `migrax.enabled` | `true` | Apply migrations at startup |
| `migrax.locations` | `db/migration` | Migration folder on the classpath |
| `migrax.java-package` | `db.migration` | Package of `JavaMigration` classes |
| `migrax.resume` | `false` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `migrax.placeholders.<name>` | | Values for `${name}` placeholders |
| `migrax.schemas` | | Schemas to migrate one after another |

!!! note "Keep `strategy=none` with the integration"

    Quarkus starts Hibernate before the integration runs. With `validate`, a pending migration
    would stop the application from starting, because Hibernate checks the schema first. Use
    `none`, and validate in CI with `migrax verify` instead.
