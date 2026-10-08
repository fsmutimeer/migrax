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
  <groupId>io.migrax</groupId>
  <artifactId>migrax-micronaut</artifactId>
  <version>0.1.0</version>
</dependency>
```

Migrations run as soon as Micronaut creates the data source, **before** Hibernate starts, so
`hbm2ddl.auto: validate` checks the migrated schema.

| Setting | Default | Meaning |
|---|---|---|
| `migrax.enabled` | `true` | Apply migrations at startup |
| `migrax.datasource` | `default` | Data source to migrate |
| `migrax.locations` | `db/migration` | Migration folder on the classpath |
| `migrax.java-package` | `db.migration` | Package of `JavaMigration` classes |
| `migrax.resume` | `false` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `migrax.placeholders.<name>` | | Values for `${name}` placeholders |
| `migrax.schemas` | | Schemas to migrate one after another |
