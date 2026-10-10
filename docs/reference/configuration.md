# Configuration

Most projects need no configuration: Migrax reads the settings your application already has.
When you do want to set something, every setting can come from several places.

## Where Migrax looks, in order

1. **Command line options**, such as `--url` or `--naming`.
2. **System properties**: `-Dmigrax.<key>`, set through `MIGRAX_JAVA_OPTS`.
3. **Environment variables**: `MIGRAX_<KEY>`, with dots and dashes turned into underscores.
4. **Application configuration**: `migrax.<key>` in your application's own files.
5. **Your framework's settings**, such as `spring.datasource.url`.

Application configuration files, from lowest to highest priority:

| File (in `src/main/resources`) | Used by |
|---|---|
| `META-INF/persistence.xml` (first persistence unit) | Jakarta EE, Helidon, plain Hibernate |
| `META-INF/microprofile-config.properties` | Helidon MP and other MicroProfile runtimes |
| `application.properties`, `application.yml`, `application.yaml` | Spring Boot, Quarkus, Micronaut, Helidon SE |
| `application-<profile>.*`, `microprofile-config-<profile>.properties` | the active profile |

The active profile comes from `MIGRAX_PROFILE`, `QUARKUS_PROFILE`, `SPRING_PROFILES_ACTIVE`,
`MICRONAUT_ENVIRONMENTS` or `MP_CONFIG_PROFILE`, or the matching key in the configuration.
Several comma-separated profiles are loaded in order. `${VAR}` and `${VAR:default}` placeholders
in values are resolved from the environment.

## Settings

| Setting | Key / variable | Default |
|---|---|---|
| Database URL | `--url`, `MIGRAX_DATABASE_URL`, `migrax.url` | from the framework settings below |
| Database user | `--user`, `MIGRAX_DATABASE_USER`, `migrax.user` | from the framework settings |
| Database password | `--password`, `MIGRAX_DATABASE_PASSWORD`, `migrax.password` | from the framework settings |
| Secrets in files | `--password-file`, `MIGRAX_DATABASE_PASSWORD_FILE`, `MIGRAX_DATABASE_USER_FILE`, `MIGRAX_DATABASE_URL_FILE` | the file's content without its last line break; a value set directly wins |
| Lock timeout | `--lock-timeout`, `MIGRAX_LOCK_TIMEOUT`, `-Dmigrax.lockTimeout`; `migrax.lock-timeout` in the startup integrations | `0` (fail at once); for example `30s`, `2m` |
| JDBC drivers without a project | `--classpath`, `MIGRAX_DRIVERS` (a folder of jars) | the `drivers` folder of the installation, if present |
| Never allow `clean` | `MIGRAX_CLEAN_DISABLED=true` | allowed |
| Entity package | `--package`, `MIGRAX_PACKAGE`, `migrax.package` | Maven `groupId` or Gradle `group` |
| Migration folder | `--locations`, `MIGRAX_LOCATIONS`, `migrax.locations` | `classpath:db/migration` |
| Naming strategy | `--naming`, `MIGRAX_NAMING`, `migrax.naming` | recorded in the snapshot, else detected ([details](naming.md)) |
| Entity reader | `--extractor`, `MIGRAX_EXTRACTOR`, `migrax.extractor` | `auto` |
| SQL dialect | `--dialect` | from the database URL |
| Java migration package | `--java-package`, `migrax.java-package` | `db.migration` |
| Schemas (multi-tenant) | `--schemas`, `MIGRAX_SCHEMAS`, `migrax.schemas` | the connection's default schema |
| Placeholders | `migrax.placeholders.<name>`, `-Dmigrax.placeholders.<name>`, `MIGRAX_PLACEHOLDERS_<NAME>` | |

The migration folder is `classpath:<path>` (under `src/main/resources`) or
`filesystem:<path>` (relative to the project folder).

### Database connection from framework settings

| Framework | URL | User / password |
|---|---|---|
| Spring Boot | `spring.datasource.url`, `spring.datasource.hikari.jdbc-url`, `SPRING_DATASOURCE_URL` | `spring.datasource.username` / `.password` |
| Quarkus | `quarkus.datasource.jdbc.url`, `QUARKUS_DATASOURCE_JDBC_URL` | `quarkus.datasource.username` / `.password` |
| Micronaut | `datasources.default.url` (or the first data source), `DATASOURCES_DEFAULT_URL` | `datasources.default.username` / `.password` |
| Helidon MP | `javax.sql.DataSource.<name>.dataSource.url` (or `jdbcUrl`, `URL`) | `...dataSource.user` / `...dataSource.password` |
| Helidon SE | `db.connection.url` | `db.connection.username` / `.password` |
| Jakarta EE | `jakarta.persistence.jdbc.url`, `javax.persistence.jdbc.url`, `hibernate.connection.url` | `...jdbc.user` / `...jdbc.password` |
| Any | `DATABASE_URL`, `DB_URL` | `DB_USERNAME` / `DB_PASSWORD` |

### Hibernate settings

Hibernate settings in `hibernate.*`, `spring.jpa.properties.*`, `jpa.default.properties.*`
(Micronaut), the naming-strategy keys of each framework, and `persistence.xml` properties are
passed to Hibernate when Migrax reads the mapping. Connection and schema-generation settings are
left out, because Migrax never connects Hibernate to your database while reading.

## The `.migrax` folder

| File | Commit? | Purpose |
|---|---|---|
| `snapshot.json` | yes | Schema your migrations produce, with dialect and naming |
| `history/` | yes | Snapshot after each migration |
| `classpath.txt`, `classpath.hash`, `build.stamp` | no | Build cache; refresh with `--refresh` |
| `.gitignore` | yes | Written by Migrax; ignores the caches |
