---
seo_title: "Spring Boot database migrations from JPA entities"
description: "Generate and apply database migrations in Spring Boot: the CLI reads application.properties, and the starter migrates at startup before JPA."
---
# Spring Boot

## What you need

Migrax comes in two parts, and most projects use both:

| Part | What it's for | How you get it |
|---|---|---|
| A tool for your terminal | Writing migrations from your entities (`generate`) and looking after them: `status`, `lint`, `verify`, `rollback` and the other commands | The Maven plugin, the Gradle plugin **or** the `migrax` command-line tool |
| The startup library `migrax-spring-boot-starter` | Applying the pending migrations each time the application starts, before JPA | A dependency: see [Migrate at startup](#migrate-at-startup) |

--8<-- "docs/includes/terminal-tool.md"

## Command line

Nothing to configure. Migrax reads the database settings from `application.properties` or
`application.yml`, including the active profile:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/shop
spring.datasource.username=shop
spring.datasource.password=${DB_PASSWORD}
```

`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` environment
variables work too, and so does `spring.datasource.hikari.jdbc-url`.

**Naming:** Spring Boot converts names to snake_case (`fullName` → `full_name`) and names join
tables `<owner table>_<attribute>`. Migrax uses the same rules (`spring` naming), or a custom
naming strategy from `spring.jpa.hibernate.naming.*`.

**Hibernate settings:** anything under `spring.jpa.properties.*` is passed to Hibernate when
Migrax reads the mapping.

Set Hibernate so it doesn't change the schema itself:

```properties
spring.jpa.hibernate.ddl-auto=validate
```

## Migrate at startup

Add the starter:

=== "Maven"

    ```xml
    <dependency>
      <groupId>org.migrax</groupId>
      <artifactId>migrax-spring-boot-starter</artifactId>
      <version>0.15.0</version>
    </dependency>
    ```

=== "Gradle"

    ```kotlin
    implementation("org.migrax:migrax-spring-boot-starter:0.15.0")
    ```

Migrations are applied when the application starts, **before** JPA starts, so
`ddl-auto=validate` checks the migrated schema. `JavaMigration` beans are picked up.

| Setting | Default | Meaning |
|---|---|---|
| `migrax.enabled` | `true` | Apply migrations at startup |
| `migrax.migrate-at-startup` | `true` | `false`: start without migrating, when a pipeline or a DBA runs the migrations; the health check and the pending gauge still report them ([details](../guides/observability.md#when-a-pipeline-migrates)) |
| `migrax.drift.check` | `off` | `warn` or `fail`: compare the database with the schema its migrations produce when the application starts; the build must put `.migrax/snapshot.json` into the application ([details](../guides/production-safety.md#drift-at-startup)) |
| `migrax.locations` | `classpath:db/migration` | Migration folders, comma-separated |
| `migrax.java-package` | `db.migration` | Package of `JavaMigration` classes |
| `migrax.env` | | Environment: migrations marked `-- migrax:env <name>` run only there |
| `migrax.resume` | `false` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `migrax.lock-timeout` | `0` | How long to wait while another instance migrates, for example `2m` when several start together |
| `migrax.placeholders.<name>` | | Values for `${name}` placeholders |
| `migrax.metrics.enabled` | `true` | Micrometer metrics of the migrations, when the application has a `MeterRegistry` ([Metrics and tracing](../guides/observability.md)) |
| `migrax.tracing.enabled` | `true` | OpenTelemetry spans of the migrations, when the application has an `OpenTelemetry` |
| `migrax.schemas` | | Schemas to migrate one after another |

### Development mode

```properties title="application-dev.properties"
migrax.dev.generate=true
```

With `migrax.dev.generate=true`, the application generates a migration for changed entities on
every restart, then applies it. Drops still need `migrax.dev.allow-destructive=true`. Use it
only in development, and review what it wrote before you commit.

## Health check

With Spring Boot Actuator in the application, Migrax adds a `migrax` health component: UP when
every migration is applied and none failed, was edited or is missing, DOWN otherwise, with the
counts as details:

```json
"migrax": {"status": "UP", "details": {"applied": 3, "pending": 0, "problems": 0}}
```

Turn it off with `management.health.migrax.enabled=false`.
