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
| [Jakarta EE, plain Hibernate](jakarta-ee.md) | `persistence.xml` | `jpa` | run the CLI in your deployment |

Build tools: [Maven plugin](maven.md) and [Gradle plugin](gradle.md).

!!! info "Getting the integration libraries"

    Migrax 0.1.3 is not published to Maven Central yet. Install the libraries into your local
    Maven repository from the source:

    ```bash
    git clone https://github.com/fsmutimeer/migrax.git && cd migrax
    mvn install                          # core library and Maven plugin
    mvn install -f integrations/pom.xml  # framework integrations and Gradle plugin
    ```
