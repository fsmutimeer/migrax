---
seo_title: "Migrax configuration"
description: "Migrax reads your application's own settings. All environment variables, migrax.properties keys and framework properties in one place."
---
# Configuration

Most projects need no configuration: Migrax reads the settings your application already has.
When you do want to set something, every setting can come from several places.

## Where Migrax looks, in order

1. **Command line options**, such as `--url` or `--naming`.
2. **System properties**: `-Dmigrax.<key>`, set through `MIGRAX_JAVA_OPTS`.
3. **Environment variables**: `MIGRAX_<KEY>`, with dots and dashes turned into underscores.
4. **Application configuration**: `migrax.<key>` in `migrax.properties` in the project folder,
   then in your application's own files (below).
5. **Your framework's settings**, such as `spring.datasource.url`.

Application configuration files, from lowest to highest priority:

| File (in `src/main/resources`) | Used by |
|---|---|
| `META-INF/persistence.xml` (first persistence unit) | Jakarta EE, Helidon, plain Hibernate |
| `META-INF/microprofile-config.properties` | Helidon MP and other MicroProfile runtimes |
| `application.properties`, `application.yml`, `application.yaml` | Spring Boot, Quarkus, Micronaut, Helidon SE |
| `application-<profile>.*`, `microprofile-config-<profile>.properties` | the active profile |

`migrax.properties` in the project folder (next to `pom.xml` or `build.gradle`, or on its own
in a project [without Java](../frameworks/any-language.md)) is Migrax's own file: its `migrax.*`
keys win over all of the files above. The `migrax` command, the Maven goals and the Gradle
plugin read it; the startup integrations read the application's settings instead. It also
marks the project folder, so commands find it from any folder below. It is not the
`migrax.properties` that the [Jakarta EE integration](../frameworks/jakarta-ee.md) reads from
`src/main/resources`.

The active profile comes from `MIGRAX_PROFILE`, `QUARKUS_PROFILE`, `SPRING_PROFILES_ACTIVE`,
`MICRONAUT_ENVIRONMENTS` or `MP_CONFIG_PROFILE`, or the matching key in the configuration
(`migrax.profile` in `migrax.properties` first).
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
| Table lock timeout (PostgreSQL; [details](#table-lock-timeout)) | `--table-lock-timeout`, `MIGRAX_TABLE_LOCK_TIMEOUT`, `migrax.table-lock-timeout` | `5s`; `0` waits as long as the database does |
| Drift check at startup | `migrax.drift.check` in the startup integrations | `off`; `warn` logs, `fail` stops the start ([details](../guides/production-safety.md#drift-at-startup)) |
| JDBC drivers without a project | `--classpath`, `MIGRAX_DRIVERS` (a folder of jars) | the `drivers` folder of the installation, if present |
| Never allow `clean` | `MIGRAX_CLEAN_DISABLED=true` | allowed |
| Entity package | `--package`, `MIGRAX_PACKAGE`, `migrax.package` | Maven `groupId` or Gradle `group` |
| Migration folders | `--locations`, `MIGRAX_LOCATIONS`, `migrax.locations` (comma-separated) | `classpath:db/migration` |
| Naming strategy | `--naming`, `MIGRAX_NAMING`, `migrax.naming` | recorded in the snapshot, else detected ([details](naming.md)) |
| Entity reader | `--extractor`, `MIGRAX_EXTRACTOR`, `migrax.extractor` | `auto` |
| Schema file (the desired schema as SQL, instead of entities; [guide](../guides/sql-migrations-for-any-language.md#let-migrax-write-the-migrations)) | `--schema-file`, `MIGRAX_SCHEMA_FILE`, `migrax.schema-file` | none: entities |
| Empty database to read the schema file in | `--scratch-url`, `MIGRAX_SCRATCH_URL`, `migrax.scratch-url` | a throwaway one (Docker for server databases) |
| Docker image for that throwaway database | `MIGRAX_SCRATCH_IMAGE`, `migrax.scratch-image` | as for `verify` |
| Check constraints for enum columns | `MIGRAX_ENUM_CHECKS`, `migrax.enum-checks` (`true` / `false`) | `false` ([details](../guides/changing-entities.md#enum-columns)) |
| Compile edited Java files without Maven or Gradle | `MIGRAX_QUICK_COMPILE`, `-Dmigrax.quick-compile` (`true` / `false`) | `true` ([details](#the-migrax-folder)) |
| SQL dialect | `--dialect` | from the database URL |
| Java migration package | `--java-package`, `migrax.java-package` | `db.migration` |
| Environment (`-- migrax:env` migrations) | `--env`, `MIGRAX_ENV`, `migrax.env` | none: marked migrations are skipped |
| Schemas (multi-tenant) | `--schemas`, `MIGRAX_SCHEMAS`, `migrax.schemas` | the connection's default schema |
| Placeholders | `migrax.placeholders.<name>`, `-Dmigrax.placeholders.<name>`, `MIGRAX_PLACEHOLDERS_<NAME>` | |
| Database to work on | `--database`, `MIGRAX_DATABASE`, `-Dmigrax.database` | the default database; others come from `migrax.databases.<name>.*` ([details](../guides/several-databases.md#the-command-line)) |
| Environments for `migrax environments` | `migrax.environments.<name>.url`, `.user`, `.password`, `.password-file`, `.env`; or `MIGRAX_ENVIRONMENTS_<NAME>_URL` and so on | none ([details](../guides/comparing-databases.md#all-environments-at-a-glance)) |
| Which environments, in which order | `--environments`, `MIGRAX_ENVIRONMENTS`, `migrax.environments` (comma-separated) | all configured, in alphabetical order |

Each migration folder is `classpath:<path>` (under `src/main/resources`) or
`filesystem:<path>` (relative to the project folder). A folder without a prefix, such as
`db/migration`, is a classpath folder, the same as when the application migrates at startup.
Several folders are separated by commas;
see [Several migration folders](../guides/advanced-migrations.md#several-migration-folders).

### Table lock timeout

A schema change such as `ALTER TABLE` needs a short exclusive lock on its table. On a busy
PostgreSQL database it can wait behind a long-running query, and while it waits every other
query on that table waits behind it, so a change that takes milliseconds can stop the
application. Migrax therefore sets PostgreSQL's `lock_timeout` to 5 seconds while it migrates.
The limit covers every lock a statement waits for, so an `UPDATE` that waits for rows another
session is changing stops too. When the run ends, the connection gets its own `lock_timeout`
back, including a value an application's connection pool set.

A migration that runs in a transaction (the default for SQL migrations) and waits longer stops
with error MXE117. PostgreSQL undoes the whole migration, so Migrax doesn't record it as
failed: run `migrate` again when the table is less busy. A migration that already failed
before and is run again with `--resume` keeps its failed record, as any failure would.

- Migrations marked `-- migrax:no-transaction` keep the connection's own `lock_timeout`:
  `CREATE INDEX CONCURRENTLY` waits for running writes on purpose, and stopping it half way
  would leave an invalid index behind.
- Java migrations run with the limit, but a timeout there is recorded as failed as usual,
  because a Java migration may have committed part of its work.
- A migration that writes its own `COMMIT`, `ROLLBACK`, `BEGIN` or `START TRANSACTION` may have
  committed part of its work, so a timeout there is recorded as failed as usual. This includes
  a command that comes from a placeholder or from a `beforeEachMigrate.sql` or
  `afterEachMigrate.sql` callback, and a migration marked `-- migrax:no-split`. Lint rule
  MX018 warns about these statements.
- `beforeMigrate.sql` and `afterMigrate.sql` callbacks run with the limit; `rollback` uses it
  as well.
- CockroachDB and YugabyteDB are left as they are.

To give every migration longer, use `--table-lock-timeout 30s` (or `MIGRAX_TABLE_LOCK_TIMEOUT`,
`migrax.table-lock-timeout`); `0` turns the limit off. To give one migration longer, start its
file with `SET lock_timeout = '1min';`: Migrax sets its own value again before the next
migration.

### Database connection from framework settings

| Framework | URL | User / password |
|---|---|---|
| Spring Boot | `spring.datasource.url`, `spring.datasource.hikari.jdbc-url`, `SPRING_DATASOURCE_URL` | `spring.datasource.username` / `.password` |
| Quarkus | `quarkus.datasource.jdbc.url`, `QUARKUS_DATASOURCE_JDBC_URL` | `quarkus.datasource.username` / `.password` |
| Micronaut | `datasources.default.url` (or the first data source), `DATASOURCES_DEFAULT_URL` | `datasources.default.username` / `.password` |
| Helidon MP | `javax.sql.DataSource.<name>.dataSource.url` (or `jdbcUrl`, `URL`) | `...dataSource.user` / `...dataSource.password` |
| Helidon SE | `db.connection.url` | `db.connection.username` / `.password` |
| Jakarta EE | `jakarta.persistence.jdbc.url`, `javax.persistence.jdbc.url`, `hibernate.connection.url` | `...jdbc.user` / `...jdbc.password` |
| Any | `DATABASE_URL`, `DB_URL` (only a JDBC URL, see below) | `DB_USERNAME` / `DB_PASSWORD` |

`DATABASE_URL` and `DB_URL` are often set for an application in another language, as
`postgres://...` or `sqlite:///...`. Migrax uses them only when they hold a JDBC URL
(`jdbc:...`); otherwise it skips them and reads the next setting, such as `migrax.url`.
`migrax doctor` says so, and so does the error when no other URL is found.

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
| `<name>/` | yes | `snapshot.json`, `history/` and `pending/` of a further database (`--database <name>`) |
| `classpath.txt`, `classpath.hash`, `build.stamp` | no | Build cache; refresh with `--refresh` |
| `classes/`, `classes.stamp`, `sources.txt` | no | Your Java sources compiled by Migrax after an edit, so commands don't wait for Maven or Gradle. The build tool still runs when a build file changed, a source was deleted, the project has Kotlin sources or annotation processors (Lombok, MapStruct), the code doesn't compile, or you built the project yourself since Migrax last did. `MIGRAX_QUICK_COMPILE=false` always uses the build tool. In a multi-module build only the module's own sources are compiled |
| `.gitignore` | yes | Written by Migrax; ignores the caches |
