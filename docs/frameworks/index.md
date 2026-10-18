# Frameworks and build tools

Migrax doesn't depend on any framework. It reads your entities and talks to the database over
JDBC, so the command line works the same everywhere. What differs per framework is:

- **where the database settings are**, which Migrax reads for you;
- **how Hibernate names tables and columns**, which Migrax must match exactly;
- **how migrations can run at startup**, through a small integration library.

Migrax recognizes the framework from your build file and configuration. `migrax doctor` shows
what it detected.

| Framework | Database settings | Naming | Startup integration |
|---|---|---|---|
| [Spring Boot](spring-boot.md) | `spring.datasource.*` | `spring` | `migrax-spring-boot-starter` |
| [Quarkus](quarkus.md) | `quarkus.datasource.*` | `jpa` | `migrax-quarkus` |
| [Micronaut](micronaut.md) | `datasources.default.*` | `micronaut` with Micronaut Data, else `jpa` | `migrax-micronaut` |
| [Helidon](helidon.md) | `javax.sql.DataSource.<name>.*`, `db.connection.*` (SE) | `jpa` | `migrax-helidon` (MP) |
| [Jakarta EE, plain Hibernate](jakarta-ee.md) | `persistence.xml` | `jpa` | `migrax-jakarta-ee` |

Build tools: [Maven plugin](maven.md) and [Gradle plugin](gradle.md).

!!! info "Getting the integration libraries"

    The libraries and both build plugins are on Maven Central from Migrax 0.14.0 on: add the
    dependency or plugin shown on each page, and Maven or Gradle downloads it. For 0.13.0 and
    older, install them from the source instead (`mvn install`, then
    `mvn install -f integrations/pom.xml`, in a clone of the repository).

!!! note "Upgrading from 0.3 or older"

    Since 0.4.0, Migrax is published as `org.migrax` instead of `io.migrax`: the groupId, the
    Gradle plugin ID (`id("org.migrax")`), the Java packages (`org.migrax.api.JavaMigration`)
    and the logger name. Replace `io.migrax` with `org.migrax` in your build files, Java
    migrations and logging configuration. During the upgrade, let only one instance run
    migrations against a database at a time: 0.3 and 0.4 use differently named migration
    locks.
