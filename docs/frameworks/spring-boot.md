# Spring Boot

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
      <groupId>io.migrax</groupId>
      <artifactId>migrax-spring-boot-starter</artifactId>
      <version>0.1.1</version>
    </dependency>
    ```

=== "Gradle"

    ```kotlin
    implementation("io.migrax:migrax-spring-boot-starter:0.1.1")
    ```

Migrations are applied when the application starts, **before** JPA starts, so
`ddl-auto=validate` checks the migrated schema. `JavaMigration` beans are picked up.

| Setting | Default | Meaning |
|---|---|---|
| `migrax.enabled` | `true` | Apply migrations at startup |
| `migrax.locations` | `classpath:db/migration` | Migration folder |
| `migrax.java-package` | `db.migration` | Package of `JavaMigration` classes |
| `migrax.resume` | `false` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `migrax.placeholders.<name>` | | Values for `${name}` placeholders |
| `migrax.schemas` | | Schemas to migrate one after another |

### Development mode

```properties title="application-dev.properties"
migrax.dev.generate=true
```

With `migrax.dev.generate=true`, the application generates a migration for changed entities on
every restart, then applies it. Drops still need `migrax.dev.allow-destructive=true`. Use it
only in development, and review what it wrote before you commit.
