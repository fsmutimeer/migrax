---
seo_title: "Micronaut database migrations"
description: "Generate and apply database migrations in Micronaut 4 and 5, with Micronaut Data or JPA, migrating at startup with health and metrics."
---
# Micronaut

Micronaut 4 and 5 are supported.

## Command line

Migrax reads `application.yml` / `application.properties` and the environments in
`MICRONAUT_ENVIRONMENTS`:

```yaml
datasources:
  default:
    url: jdbc:postgresql://localhost:5432/shop
    username: shop
    password: ${DB_PASSWORD}
jpa:
  default:
    properties:
      hibernate:
        hbm2ddl:
          auto: none
```

`DATASOURCES_DEFAULT_URL`, `DATASOURCES_DEFAULT_USERNAME` and `DATASOURCES_DEFAULT_PASSWORD` work
too. Without a `default` data source, Migrax uses the first one. Hibernate settings under
`jpa.default.properties.*` are passed to Hibernate.

!!! warning "Change the starter's `update`"

    Projects created from the Micronaut starter set `hbm2ddl.auto: update`. Set it to `none` or
    `validate`; `migrax doctor` shows the exact line.

## Naming

| Project uses | Naming | Example: `homepageURL` |
|---|---|---|
| Micronaut Data (`micronaut-data-hibernate-jpa`) | `micronaut` | `homepage_url` |
| Micronaut Hibernate JPA without Micronaut Data | `jpa` | `homepageURL` |

Micronaut Data converts names to snake_case with its own rule, which differs from other
snake_case rules for names like `myURL` (`my_url`) or `productSKU` (`product_sku`). It also
uses one shared `hibernate_sequence` for generated ids. Migrax matches both, and when Micronaut
Data is on the classpath it uses Micronaut's own naming class.

## Migrate at startup

```xml
<dependency>
  <groupId>org.migrax</groupId>
  <artifactId>migrax-micronaut</artifactId>
  <version>0.14.0</version>
</dependency>
```

Migrations run as soon as Micronaut creates the data source, **before** Hibernate starts, so
`hbm2ddl.auto: validate` checks the migrated schema.

| Setting | Default | Meaning |
|---|---|---|
| `migrax.enabled` | `true` | Apply migrations at startup |
| `migrax.migrate-at-startup` | `true` | `false`: start without migrating, when a pipeline or a DBA runs the migrations; the health check and the pending gauge still report them ([details](../guides/observability.md#when-a-pipeline-migrates)) |
| `migrax.datasource` | `default` | Data source to migrate |
| `migrax.locations` | `db/migration` | Migration folders on the classpath, comma-separated |
| `migrax.java-package` | `db.migration` | Package of `JavaMigration` classes |
| `migrax.env` | | Environment: migrations marked `-- migrax:env <name>` run only there |
| `migrax.resume` | `false` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `migrax.lock-timeout` | `0` | How long to wait while another instance migrates, for example `2m` when several start together |
| `migrax.placeholders.<name>` | | Values for `${name}` placeholders |
| `migrax.metrics.enabled` | `true` | Micrometer metrics of the migrations, when the application has a `MeterRegistry` ([Metrics and tracing](../guides/observability.md)) |
| `migrax.tracing.enabled` | `true` | OpenTelemetry spans of the migrations, when the application has an `OpenTelemetry` |
| `migrax.schemas` | | Schemas to migrate one after another |

## Health check

With `micronaut-management` in the application, Migrax adds a `migrax` health indicator: UP
when every migration is applied and none failed, was edited or is missing, DOWN otherwise, with
the counts as details (`/health`).
