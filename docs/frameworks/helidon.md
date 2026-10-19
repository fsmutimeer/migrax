---
seo_title: "Helidon database migrations"
description: "Generate and apply database migrations in Helidon MP and SE: the CLI reads MicroProfile Config, and the integration migrates at startup."
---
# Helidon

## Helidon MP

### Command line

Migrax reads `META-INF/microprofile-config.properties` (and the `mp.config.profile`) together
with `META-INF/persistence.xml`:

```properties title="META-INF/microprofile-config.properties"
javax.sql.DataSource.shop.dataSourceClassName=org.postgresql.ds.PGSimpleDataSource
javax.sql.DataSource.shop.dataSource.url=jdbc:postgresql://localhost:5432/shop
javax.sql.DataSource.shop.dataSource.user=shop
javax.sql.DataSource.shop.dataSource.password=secret
```

```xml title="META-INF/persistence.xml"
<persistence-unit name="shop" transaction-type="JTA">
  <jta-data-source>shop</jta-data-source>
  <properties>
    <property name="hibernate.hbm2ddl.auto" value="none"/>
  </properties>
</persistence-unit>
```

When several data sources are configured, Migrax uses the one `persistence.xml` names. The
`jdbcUrl`, `username` and `password` keys of other connection pools work too, and so do the
MicroProfile environment variables, such as `JAVAX_SQL_DATASOURCE_SHOP_DATASOURCE_URL`.
`hibernate.*` properties from `persistence.xml` are passed to Hibernate.

**Naming:** Helidon keeps Hibernate's default naming (`jpa`).

### Migrate at startup

```xml
<dependency>
  <groupId>org.migrax</groupId>
  <artifactId>migrax-helidon</artifactId>
  <version>0.15.0</version>
</dependency>
```

Migrations run when the application starts, before your own beans and before JPA is first used,
so `hibernate.hbm2ddl.auto=validate` works. Settings go in `microprofile-config.properties`:

| Setting | Default | Meaning |
|---|---|---|
| `migrax.enabled` | `true` | Apply migrations at startup |
| `migrax.migrate-at-startup` | `true` | `false`: start without migrating, when a pipeline or a DBA runs the migrations; the health check and the pending gauge still report them ([details](../guides/observability.md#when-a-pipeline-migrates)) |
| `migrax.datasource` | the one `persistence.xml` names, or the only one | Data source to migrate |
| `migrax.locations` | `db/migration` | Migration folders on the classpath, comma-separated |
| `migrax.java-package` | `db.migration` | Package of `JavaMigration` classes |
| `migrax.env` | | Environment: migrations marked `-- migrax:env <name>` run only there |
| `migrax.resume` | `false` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `migrax.lock-timeout` | `0` | How long to wait while another instance migrates, for example `2m` when several start together |
| `migrax.placeholders.<name>` | | Values for `${name}` placeholders |
| `migrax.metrics.enabled` | `true` | Micrometer metrics of the migrations, when the application has a `MeterRegistry` ([Metrics and tracing](../guides/observability.md)) |
| `migrax.tracing.enabled` | `true` | OpenTelemetry spans of the migrations, when the application has an `OpenTelemetry` |
| `migrax.schemas` | | Schemas to migrate one after another |

### Readiness check

With `helidon-microprofile-health` in the application, Migrax adds a `migrax` readiness check:
UP when every migration is applied and none failed, was edited or is missing, DOWN otherwise,
with the counts as data (`/health/ready`).

## Helidon SE

Helidon SE uses its database client rather than JPA, so there are no entities to generate
migrations from. Write migrations with `migrax new` and apply them with `migrax migrate` in your
deployment ([SQL migrations for any language](../guides/sql-migrations-for-any-language.md) explains
this setup). Migrax reads the connection from `application.yaml`:

```yaml
db:
  connection:
    url: jdbc:postgresql://localhost:5432/shop
    username: shop
    password: secret
```
