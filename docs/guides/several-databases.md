---
seo_title: "Migrations for several databases in one application"
description: "Migrate several databases from one Java application: one migrax.databases block per database, in Spring Boot, Quarkus, Micronaut, Helidon and Jakarta EE."
---
# Several databases

New in Migrax 0.15.0.

An application that uses more than one database, for example orders and billing, migrates
each of them when it starts. The default database keeps the usual `migrax.*` settings. Each
further database gets a block `migrax.databases.<name>.*`:

```properties title="application.properties"
# The default database: unchanged
migrax.locations=db/migration

# A second database
migrax.databases.billing.datasource=billingDataSource
migrax.databases.billing.locations=db/billing
migrax.databases.billing.placeholders.owner=billing_app
```

The default database is migrated first, then the others in alphabetical order. Each has its own
migration history table, lock and checksums, in its own database.

## Settings of a database

| Setting | Default | Meaning |
|---|---|---|
| `datasource` | the database's name | The data source to migrate: see [By framework](#by-framework) |
| `locations` | `db/<name>` | Migration folders, separated by commas |
| `java-package` | `db.<name>` | Package of Java migrations |
| `schemas` | none | Schemas to migrate one after another |
| `placeholders.<key>` | none | Values for `${key}` in this database's migrations |
| `env` | `migrax.env` | Environment for migrations marked `-- migrax:env` |
| `lock-timeout` | `migrax.lock-timeout` | How long to wait while another instance migrates |
| `resume` | `migrax.resume` | Re-run a failed resume-safe migration |
| `migrate-at-startup` | `migrax.migrate-at-startup` | `false`: report only, as described in [Metrics and tracing](observability.md#when-a-pipeline-migrates) |
| `drift.check` | `migrax.drift.check` | Compare this database with its snapshot at startup: see [Drift at startup](production-safety.md#drift-at-startup) |

Folders, Java migrations, schemas and placeholders belong to one database: a further database
never runs the default database's migrations. The defaults `db/<name>` and `db.<name>` are kept
apart from `db/migration` and `db.migration` on purpose, which is why `default` and
`migration` can't be database names; nor can `history` and `pending`, the names of folders the
`migrax` command keeps in `.migrax/`. Names may contain letters, digits, `-` and `_`.

## By framework

| Framework | `datasource` names | Example |
|---|---|---|
| Spring Boot | a `DataSource` bean | `billingDataSource` |
| Quarkus | a named data source (`quarkus.datasource.<name>.*`) | `billing` |
| Micronaut | a data source (`datasources.<name>.*`) | `billing` |
| Helidon | a data source (`javax.sql.DataSource.<name>.*`) | `billing` |
| Jakarta EE | a JNDI name | `java:global/jdbc/billing` |

=== "Spring Boot"

    An application with two databases defines both `DataSource` beans; the default database
    and JPA use the one marked `@Primary`:

    ```java
    @Bean
    @Primary
    @ConfigurationProperties("app.datasource.orders")
    DataSource dataSource() {
        return DataSourceBuilder.create().build();
    }

    @Bean
    @ConfigurationProperties("app.datasource.billing")
    DataSource billingDataSource() {
        return DataSourceBuilder.create().build();
    }
    ```

    ```properties
    migrax.databases.billing.datasource=billingDataSource
    ```

    Every `EntityManagerFactory` waits until all databases are migrated. `JavaMigration` beans
    belong to the default database; a further database finds its Java migrations in its
    `java-package`.

=== "Quarkus"

    ```properties
    quarkus.datasource.billing.db-kind=postgresql
    quarkus.datasource.billing.jdbc.url=jdbc:postgresql://localhost:5432/billing
    migrax.databases.billing.locations=db/billing
    ```

    The block's name is also the data source's name unless `datasource` says otherwise. The
    migrations are listed when the application is built, so native executables migrate every
    database too.

=== "Micronaut"

    ```yaml
    datasources:
      billing:
        url: jdbc:postgresql://localhost:5432/billing
    jpa:
      billing:
        entity-scan:
          packages: com.example.billing
    migrax:
      databases:
        billing:
          locations: db/billing
    ```

    Micronaut starts Hibernate for each data source, so give each one its own `jpa.<name>`
    entities. Migrax creates every configured data source at startup, so a database is migrated
    even when nothing uses it yet.

=== "Helidon"

    ```properties
    javax.sql.DataSource.billing.dataSourceClassName=org.postgresql.ds.PGSimpleDataSource
    javax.sql.DataSource.billing.dataSource.url=jdbc:postgresql://localhost:5432/billing
    migrax.databases.billing.locations=db/billing
    ```

=== "Jakarta EE"

    ```properties title="migrax.properties"
    migrax.databases.billing.datasource=java:global/jdbc/billing
    ```

    Use a global JNDI name: a data source defined in the server, or one the application
    defines under `java:global/`. Some servers, such as Payara, can't look up `java:app/` names
    yet while the application starts.

## Health and metrics

The health checks report every database. The default database keeps its usual details
(`applied`, `pending`, `problems`), and each further one adds its own, such as
`billing.pending`; the check is UP only when every database is up to date. Spring Boot shows
one entry per database under `migrax` instead.

Every metric has the tag `database`: `default`, or the name of a further database. See
[Metrics and tracing](observability.md).

## The command line

The `migrax` command works on one database at a time: the default database, or the one
`--database` names.

```bash
migrax generate --database billing
migrax migrate --database billing
migrax status --database billing
```

With Maven, pass `-Dmigrax.database=billing` (for example `mvn migrax:status
-Dmigrax.database=billing`). In a pipeline, `MIGRAX_DATABASE=billing` does the same for every
command.

For a further database the command reads:

| What | From |
|---|---|
| Connection | `--url`, then `MIGRAX_DATABASES_BILLING_URL` (or `..._URL_FILE`), then `migrax.databases.billing.url`, then the framework's settings of its data source: `quarkus.datasource.billing.*`, `datasources.billing.*` (Micronaut) or `javax.sql.DataSource.billing.*` (Helidon). User and password the same way. Spring Boot has no standard key for a second data source, so set `migrax.databases.billing.url`, `.user` and `.password` |
| Migration folders | `migrax.databases.billing.locations`, else `db/billing`, under `src/main/resources` |
| Entities | `migrax.databases.billing.package` (or `--package`), needed by the commands that read entities, such as `generate`, `check` and `plan` |
| Snapshot | `.migrax/billing/`, next to the default database's `.migrax/snapshot.json` |

```properties title="application.properties"
migrax.databases.billing.datasource=billingDataSource
migrax.databases.billing.package=com.shop.billing
migrax.databases.billing.url=jdbc:postgresql://localhost:5432/billing
migrax.databases.billing.user=billing
```

The default database leaves a further database's package out when it sits inside its own: with
the entity package `com.shop`, the entities in `com.shop.billing` belong to billing only. Keep
each database's entities in their own package, the same way the application's JPA setup
separates them.

Each database's settings stay its own: `MIGRAX_LOCATIONS`, `MIGRAX_SCHEMAS` and the default
database's placeholders never apply to billing. Its own variables start with
`MIGRAX_DATABASES_BILLING_`, for example `MIGRAX_DATABASES_BILLING_PLACEHOLDERS_OWNER`.
Settings for the whole application, such as `MIGRAX_ENV`, apply to every database unless the
block sets them.
