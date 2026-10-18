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

Folders, Java migrations, schemas and placeholders belong to one database: a further database
never runs the default database's migrations. The defaults `db/<name>` and `db.<name>` are kept
apart from `db/migration` and `db.migration` on purpose, which is why `default` and
`migration` can't be database names. Names may contain letters, digits, `-` and `_`.

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

The `migrax` command works with one database at a time. For a further database, point it at
that database and its folder:

```bash
migrax status --url jdbc:postgresql://localhost:5432/billing --locations db/billing
```
