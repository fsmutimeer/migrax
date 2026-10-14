# Changelog

All notable changes to Migrax are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

## [0.6.1] - 2026-10-14

### Fixed

- Oracle packages, package and type bodies, and anonymous blocks (`BEGIN ... END;`,
  `DECLARE ... BEGIN ... END;`) no longer need a line with only `/` after them: Migrax finds
  where they end, as it already did for procedures, functions and triggers.
- PostgreSQL and CockroachDB: a `migrate --sql` script run with psql recorded `applied_at` in
  the server's time zone, so `migrax status` could show it hours before the migrations Migrax
  applied itself. The script now records the time in the same zone as Migrax.
- `verify`: when a migration fails on the throwaway database, the error says that the migration
  fails on an empty database and what to check. It used to give the advice for a real database
  (inspect it, repair, resume), which doesn't apply there.

## [0.6.0] - 2026-10-14

The 0.6 release removes most of Migrax's limitations: EclipseLink and OpenJPA are read through
their own mapping like Hibernate (now from 5.0), Quarkus migrates before Hibernate and in native
executables, Jakarta EE servers migrate their own data sources, and `--keep-data` rollbacks
restore dropped data.

### Added

- Quarkus: `migrax-quarkus` is now a Quarkus extension. It applies the migrations before
  Hibernate starts, so `quarkus.hibernate-orm.schema-management.strategy=validate` works, and it
  runs in native executables. The dependency stays the same; Quarkus fetches the new
  `migrax-quarkus-deployment` artifact itself. The readiness check is added only when the
  application has the health extension, so the build warning about `HealthCheck` is gone.
  Migrations are listed when the application is built: set `migrax.locations` before the build.
- `generate --keep-data` keeps the data a migration drops, and its rollback script restores it:
  a dropped table is renamed to a `migrax_backup_*` table and renamed back, and a dropped or
  narrowed column is copied there with the table's primary key and filled again row by row.
  The new `migrax backups` command lists the backup tables and drops them (`--drop`, `--yes`).
  Tested on all 8 databases.
- OpenJPA: projects that use OpenJPA (Apache TomEE's JPA provider) are read through OpenJPA's
  own mapping too: its names, the indexes it puts on foreign keys, identity columns, sequences
  and `OPENJPA_SEQUENCE_TABLE`. Tested with OpenJPA 3.2 and 4.1, and on PostgreSQL and MySQL;
  `--extractor openjpa` forces it.
- EclipseLink: projects that use EclipseLink are read through EclipseLink's own mapping, as
  Hibernate projects are through Hibernate's: its names, its join and collection tables, and
  its id strategies, including the `SEQUENCE` table and rows a plain `@GeneratedValue` needs.
  Before, they were read with Hibernate's rules and EclipseLink failed at runtime. Tested with
  EclipseLink 2.7, 3.0 and 4.0, and on PostgreSQL and MySQL. `--extractor eclipselink` forces
  it. The database reader also recognizes H2's `BINARY LARGE OBJECT` and `CHARACTER LARGE
  OBJECT` columns, which made the first `generate` change `@Lob` columns on H2.
- Jakarta EE: the new `migrax-jakarta-ee` integration applies the migrations when the
  application starts on the server, to the data source `persistence.xml` names as the server
  provides it (or `migrax.datasource`), so the connection isn't configured twice. Tested on
  WildFly and Payara. Migrations and Java migrations are also found in WildFly's virtual file
  system.
- Hibernate 5.0 to 5.3 are read through their own mapping and validate the schema in
  `verify`, like 5.4 and newer, instead of annotation scanning. 5.0 to 5.2 need JAXB in the
  project on Java 11 and newer; without it Migrax falls back as before and says what is
  missing. The Hibernate compatibility tests now cover 19 versions.
- SQL Server migrations can repeat a batch with `GO 5`, as in SQL Server's own tools.
- `migrate --sql` works with `--schemas`: the script has a section per schema that switches to
  it first. When a Java migration is pending, the script now stops before it and says how to
  go on, instead of refusing to write anything.
- CockroachDB: a migration that changes a column type in a way CockroachDB can't do in place
  (anything but widening) turns on CockroachDB's setting for it and runs outside a
  transaction, as CockroachDB requires. Before, such migrations failed until edited by hand.
- `migrax doctor` warns when the entities declare check constraints and the server is MySQL
  before 8.0.16, which accepts them without enforcing them.
- Procedures, functions and triggers no longer need `GO`, `DELIMITER` or `/` lines: Migrax ends
  the statement with the `END` that closes the body's first `BEGIN`. Scripts with those lines
  work as before. New lint rule MX016 warns when a body has an unmatched `BEGIN` and so takes
  in the statements after it.

### Fixed

- The first `generate` on an existing database saw a fixed-length `BINARY(n)` column as
  `varbinary` and wrote a change for it, for example for the `binary(255)` columns Hibernate 5
  writes on H2. The database reader now keeps the column's real type.
- On a database Hibernate created, an `@Check` without a name was added a second time by the
  first `generate`, because the database had given the existing check a name of its own.
  Migrax now recognizes the check by its condition and keeps the database's name.

## [0.5.0] - 2026-10-14

The 0.5 release: check constraints and comments in generated migrations, stored procedures and
triggers in SQL migrations, and more control over what `migrate` runs and where.

### Added

- `generate` writes check constraints and comments: Hibernate's `@Check`, `@Checks` and
  `@Comment`, and the `check` and `comment` attributes of Jakarta Persistence 3.2's `@Table`
  and `@Column`, on all 8 databases (SQLite keeps no comments). A changed condition drops the
  old check and adds the new one, and `--safe` adds checks on PostgreSQL and CockroachDB
  without blocking writes. The checks Hibernate 6+ derives from enum columns are generated only
  with `migrax.enum-checks=true`. Projects that already use these annotations get them in their
  next generated migration; remove any that a hand-written migration added before.
- `migrate --sql` (Maven: `-Dmigrax.sql=true`) prints one SQL script with everything `migrate`
  would do, for a DBA to review and run with `psql`, `mysql`, `sqlcmd` or `sqlplus`: the
  pending migrations, the history rows Migrax records, transactions where the database has
  them, and each tool's end markers. Nothing changes until the script runs.
- Migrations for some environments only: a `-- migrax:env dev,test` line makes a migration
  run only where `--env`, `MIGRAX_ENV` or `migrax.env` names one of them. Elsewhere `status`
  shows it as skipped (`[-]`, state `SKIPPED` in `--json`), and health checks don't count it
  as pending.
- Several migration folders: `--locations`, `MIGRAX_LOCATIONS` and `migrax.locations` take a
  comma-separated list (CLI, Maven, Gradle and the startup integrations). Files run in version
  order across all folders; new files go into the first one.
- `migrax baseline <migration> --yes` (Maven: `migrax:baseline`) adopts a database whose
  tables already exist: it records the migrations up to the named one as applied without
  running them, so `migrate` only runs the later ones.
- `migrate --to <migration>` (Maven: `-Dmigrax.to=...`) applies versioned migrations only up
  to and including the named one, for staged rollouts. The name can be the file name, its
  start, or the version number (`--to 5`). Repeatable migrations wait for the next migrate
  without `--to`.
- Migrations can contain stored procedures, functions and triggers: a line with only `GO` ends
  a SQL Server batch, `DELIMITER` lines change the MySQL/MariaDB delimiter, and a line with only
  `/` ends an Oracle PL/SQL block (also usable for SQLite triggers). `-- migrax:no-split` sends
  a whole file as one statement. `migrate`, `rollback`, `lint` and `verify` all split this way.
- Guides > Without Java entities: how to apply hand-written SQL migrations with Migrax in
  projects without JPA entities (Python, Node.js, other languages): drivers, connection
  settings, the container image and rollback scripts.

### Changed

- The documentation moved to https://migrax.org/; the old address
  https://docs-migrax.github.io/ redirects there. The site has a new black, white and yellow
  look.

### Fixed

- `migrax doctor` no longer fails in projects without Java, such as the container image or a
  Python or Node.js project with hand-written migrations: when there is no `pom.xml`,
  `build.gradle` or entity package but there are migrations, it checks only the migrations
  and the database. Without migrations it still reports the missing build file, so a Java
  project opened in the wrong folder is still caught.

## [0.4.0] - 2026-10-11

The 0.4 release: Migrax moves to `org.migrax`. Nothing else changes in how it works; see
below for the one-line upgrade.

### Changed

- Migrax now lives under `org.migrax` (the project's domain is migrax.org): the Maven
  groupId, the Gradle plugin ID and the Java packages all change from `io.migrax` to
  `org.migrax`. Commands, options, configuration keys, environment variables, migration files,
  the snapshot and the database tables are unchanged. The migration lock has a new name too,
  so a 0.3 and a 0.4 instance don't wait for each other: during the upgrade, make sure only
  one of them runs migrations against a database at a time.

  To upgrade, replace `io.migrax` with `org.migrax` in your build:

  - Maven: `<groupId>org.migrax</groupId>` for the plugin and the integrations.
  - Gradle: `plugins { id("org.migrax") version "..." }` and `org.migrax:...` dependencies.
  - Java migrations: `import org.migrax.api.JavaMigration;`.
  - Logging configuration: the logger is now `org.migrax`.

## [0.3.0] - 2026-10-11

The 0.3 release: Migrax in containers and Kubernetes, machine-readable output, and AI
assistants. It contains everything in the release candidates 0.3.0-rc.1 to 0.3.0-rc.3; the
changelog lists each of their changes in detail.

### Added

- `migrax mcp`: a Model Context Protocol server for AI assistants (Claude Code, Codex CLI,
  Gemini CLI, GitHub Copilot in VS Code, Cursor, ...). Its tools only read; `--allow-generate`
  also lets it write migration files. It never changes a database.
- The documentation as plain text for AI assistants: `llms.txt` and `llms-full.txt`.
- Container image `ghcr.io/fsmutimeer/migrax` (amd64 and arm64) with the common JDBC drivers,
  and Guides > Containers and Kubernetes with tested example manifests.
- `--lock-timeout <time>`: wait for the migration lock instead of failing, for instances that
  start together.
- Database secrets from files (`MIGRAX_DATABASE_PASSWORD_FILE`, `--password-file`, ...) and
  JDBC drivers without a project (`MIGRAX_DRIVERS` or the installation's `drivers` folder).
- `--json` on every command, with failures as JSON too, and stable error codes (`MXE...`)
  listed in Reference > Errors and JSON output.
- Health checks in the Spring Boot, Quarkus, Micronaut and Helidon integrations.

### Changed

- Error lines start with `error[<code>]:` instead of `error:`.
- Guides > Using Migrax with AI assistants: setup for Codex CLI, Gemini CLI, GitHub Copilot in
  VS Code and Cursor, how to start `migrax mcp` through `cmd` on Windows for clients that can't
  run `.cmd` scripts, and why the ChatGPT app can't use a local MCP server.

## [0.3.0-rc.3] - 2026-10-10

Third release candidate for 0.3.0, for testing. It contains everything in 0.3.0-rc.2 and
these changes: Migrax for AI assistants.

### Added

- `migrax mcp`: a Model Context Protocol server, so AI assistants such as Claude Code can call
  status, check, plan, lint, drift, doctor, verify and show_migration as tools. It never
  changes a database; `--allow-generate` also lets it write migration files. Guide: Guides >
  Using Migrax with AI assistants, with instructions to copy into a project's `AGENTS.md` or
  `CLAUDE.md`.
- The documentation as plain text for AI assistants: `llms.txt` and `llms-full.txt` on the
  docs site.

## [0.3.0-rc.2] - 2026-10-10

Second release candidate for 0.3.0, for testing. It contains everything in 0.3.0-rc.1 and
these changes: machine-readable results and errors, and health checks.

### Added

- `--json` for `migrate`, `rollback`, `repair`, `clean`, `doctor`, `new`, `squash` and `merge`:
  one result object with `ok` and `exitCode` on standard output, progress on standard error.
  With `--json`, a failure is also a JSON object.
- Stable error codes (`error[MXE104]: ...`), listed in Reference > Errors and JSON output.
- Health checks in the startup integrations: a `migrax` health indicator for Spring Boot
  Actuator and Micronaut, and a readiness check for Quarkus and Helidon; UP when every
  migration is applied and none failed, was edited or is missing. Each is active only when the
  application has the framework's health module.

### Changed

- Error lines start with `error[<code>]:` instead of `error:`.

## [0.3.0-rc.1] - 2026-10-10

First release candidate for 0.3.0, for testing: running Migrax in containers and Kubernetes.

### Added

- Container image `ghcr.io/fsmutimeer/migrax:<version>` (amd64 and arm64) with Migrax and the
  PostgreSQL, MariaDB, SQL Server, SQLite and H2 drivers, for running migrations without a
  Java project. Guide: Guides > Containers and Kubernetes, with example manifests (Job, init
  container, Helm hook, nightly drift CronJob) tested on a local Kubernetes cluster.
- `--lock-timeout <time>` (`MIGRAX_LOCK_TIMEOUT`, `migrax.lock-timeout` in the startup
  integrations): wait while another process holds the migration lock instead of failing, for
  instances that start together.
- Database secrets from files: `MIGRAX_DATABASE_PASSWORD_FILE` (and `_USER_FILE`, `_URL_FILE`)
  and `--password-file`, for Kubernetes and Docker secrets.
- JDBC drivers without a project: every jar in `MIGRAX_DRIVERS` or the installation's
  `drivers` folder is available, after the project's own dependencies.

## [0.2.0] - 2026-10-10

The 0.2 release: two more databases, a command to start a development database over, and the
fixes from 0.1.1 to 0.1.4. It contains everything in the release candidates 0.2.0-rc.1 to
0.2.0-rc.6; the changelog lists each of their changes in detail.

### Added

- CockroachDB support (`cockroachdb`), through the PostgreSQL driver: Migrax recognizes the
  server by itself, and `verify` starts a throwaway CockroachDB in Docker.
- SQLite support (`sqlite`, SQLite 3.35 and newer): changes SQLite can't make in place
  rebuild the table with its rows; `verify` runs on a temporary SQLite file.
- `migrax clean` (and `mvn migrax:clean`) drops every table, view and sequence so `migrate`
  can rebuild a development database; `MIGRAX_CLEAN_DISABLED=true` turns it off.
- `migrax repair <migration>... --action forget --yes` removes applied migrations whose files
  were deleted on purpose.
- The first `generate` against a database that already has tables also writes those tables
  to `0001_baseline.sql`, so an empty database can be built from the migrations.
- Documentation for `migrax import flyway|liquibase` (Guides > Switching from Flyway or
  Liquibase), and a Download page with every release's files and SHA-256 checksums.
- MIT license.

### Changed

- `migrax doctor` checks locking by taking and releasing the migration lock.
- Each database's support, including its migration lock, is one class found with
  `java.util.ServiceLoader`; the CLI is one class per command. Commands and output are
  unchanged.

### Fixed

- The first `generate` against an existing database no longer finds changes that aren't real,
  on every supported database and with both ways of reading entities.
- No JVM crash with Java 21 and Hibernate 7.3 or newer.
- `migrax import liquibase` includes the entities' sequences in its baseline.

## [0.2.0-rc.6] - 2026-10-10

Sixth release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.5 and
these changes.

### Added

- CockroachDB support (`cockroachdb`), through the PostgreSQL driver: Migrax recognizes the
  server, writes CockroachDB's types, takes its migration lock as a row in `migrax_lock`, and
  `verify` starts a throwaway CockroachDB in Docker. Tested with the real-database suite and
  Hibernate's schema validation.
- SQLite support (`sqlite`, SQLite 3.35 and newer): changes SQLite can't make in place, such
  as a column's type or a new foreign key, rebuild the table with its rows, and a rebuild that
  breaks a foreign key fails before it commits. `verify` runs on a temporary SQLite file.
- `migrax clean` (and `mvn migrax:clean`): drops every table, view and sequence, the migration
  history included, so `migrate` can rebuild a development database. It asks first (or needs
  `--yes`), `--dry-run` lists what it would drop, and `MIGRAX_CLEAN_DISABLED=true` turns it off.
- Documentation for `migrax import flyway|liquibase`, with a guide for switching an existing
  project (Guides > Switching from Flyway or Liquibase).

### Fixed

- `migrax import liquibase` left the entities' sequences out of the baseline, so a database
  built from it failed Hibernate's validation; it now reads the database as the first
  `generate` does.
- Hibernate's mapping of a `@Lob String` as `varchar(255)` (CockroachDB) is read as text, so
  long values aren't cut off.
- Hibernate's bulk-update helper tables (`HTE_<table>`) are no longer seen as user tables.

## [0.2.0-rc.5] - 2026-10-10

Fifth release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.4 and
these fixes (also released for 0.1 as 0.1.4).

### Fixed

- The first `generate` against a database that already has tables wrote only the differences,
  so an empty database couldn't be built from the migrations and `migrax verify` failed on the
  first one ("table doesn't exist"). It now also writes those tables to `0001_baseline.sql`
  and records it as applied in that database without running it; the changes follow as
  `0002_...`. Projects that already have migrations are not changed.
- H2: the first `generate` against an existing database wanted to drop the indexes H2 creates
  for foreign keys (named like `fk_..._INDEX_8`) and refused as destructive. They are now
  recognized as part of the foreign key, as on MySQL and MariaDB.

## [0.2.0-rc.4] - 2026-10-09

Fourth release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.3 and
these fixes (also released for 0.1 as 0.1.3).

### Fixed

- More changes that weren't real in the first `generate` against an existing database, found
  by a new test that runs it on MySQL (also with Windows-style lower-case table names),
  MariaDB, PostgreSQL, SQL Server and Oracle, reading entities both through Hibernate and by
  annotation scanning:
  - PostgreSQL: ids that take their value from a sequence were seen as identity columns, and
    the identity was dropped;
  - SQL Server: `varchar(max)` and `varbinary(max)` columns were altered to themselves;
  - MySQL: UUID columns (`binary(16)`) were altered to themselves; UUID columns are now kept
    as they are, whether stored natively or as binary;
  - `numeric` and `decimal` are treated as the same type.

## [0.2.0-rc.3] - 2026-10-09

Third release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.2 and
these changes (also released for 0.1 as 0.1.2).

### Added

- `migrax repair <migration>... --action forget --yes` removes applied migrations whose files
  were deleted on purpose from the history; the database keeps their changes. The "files are
  missing" error now shows the exact command.

### Fixed

- The first `generate` of a project, which compares the entities with the live database, no
  longer writes changes that aren't real:
  - tables whose names differ only in letter case (`categories_seq` in the database,
    `categories_SEQ` in the entity) on databases that fold names, such as MySQL on Windows,
    PostgreSQL, H2 and Oracle; this produced a `CREATE` and a `DROP` of the same table;
  - the index MySQL and MariaDB create for each foreign key, which was dropped;
  - sequences that already exist, which were created again: real sequences, and the
    `next_val` tables MySQL uses instead (sequences the entities don't use, such as those of
    identity columns, are never touched);
  - columns whose types the database stores the same way, such as an `Instant` field on MySQL
    (`datetime(6)` either way).
- `migrate` with an empty migration folder now reports applied migrations whose files were
  deleted, instead of saying there is nothing to migrate.
- On Windows, every command printed the path of the old class-data archive
  (`...\migrax\cache\migrax-java21.jsa`) and left the file in place: Java creates it read-only.
  The launcher now removes it silently.

## [0.2.0-rc.2] - 2026-10-09

Second release candidate for 0.2.0, for testing. It contains everything in 0.2.0-rc.1 and
this fix.

### Fixed

- Migrax crashed (a JVM crash, not an error message) when reading Hibernate 7.3 or newer entities
  on Java 21. A Java 21 bug (JDK-8391430) breaks the class-data archive the launcher keeps to
  start faster, so the launcher now keeps it only on Java 25 and newer, and removes archives
  that earlier versions left behind.

## [0.2.0-rc.1] - 2026-10-09

Release candidate for 0.2.0, for testing.

### Added

- MIT license.
- Download page on the documentation site, hosting every release's files with SHA-256
  checksums, updated automatically after each release.
- Database dialects are found with `java.util.ServiceLoader`
  (`META-INF/services/io.migrax.dialect.Dialect`), and each dialect brings its own migration
  lock, so a new database needs no change to existing code.

### Changed

- `migrax doctor` checks locking by taking and releasing the migration lock, so a missing
  permission (for example EXECUTE on DBMS_LOCK on Oracle) shows up before `migrate`.
- The CLI is split into one class per command; the commands and their output are unchanged.
  `CONTRIBUTING.md` explains how to add a command or a database.
- The Download page lists pre-releases in their own section, for testing.

## [0.1.4] - 2026-10-10

### Fixed

- The first `generate` against a database that already has tables wrote only the differences,
  so an empty database couldn't be built from the migrations and `migrax verify` failed on the
  first one ("table doesn't exist"). It now also writes those tables to `0001_baseline.sql`
  and records it as applied in that database without running it; the changes follow as
  `0002_...`. Projects that already have migrations are not changed.
- H2: the first `generate` against an existing database wanted to drop the indexes H2 creates
  for foreign keys (named like `fk_..._INDEX_8`) and refused as destructive. They are now
  recognized as part of the foreign key, as on MySQL and MariaDB.

## [0.1.3] - 2026-10-09

### Fixed

- More changes that weren't real in the first `generate` against an existing database, found
  by a new test that runs it on MySQL (also with Windows-style lower-case table names),
  MariaDB, PostgreSQL, SQL Server and Oracle, reading entities both through Hibernate and by
  annotation scanning:
  - PostgreSQL: ids that take their value from a sequence were seen as identity columns, and
    the identity was dropped;
  - SQL Server: `varchar(max)` and `varbinary(max)` columns were altered to themselves;
  - MySQL: UUID columns (`binary(16)`) were altered to themselves; UUID columns are now kept
    as they are, whether stored natively or as binary;
  - `numeric` and `decimal` are treated as the same type.

## [0.1.2] - 2026-10-09

### Added

- MIT license.
- Download page on the documentation site, hosting every release's files with SHA-256
  checksums.
- `migrax repair <migration>... --action forget --yes` removes applied migrations whose files
  were deleted on purpose from the history; the database keeps their changes. The "files are
  missing" error now shows the exact command.

### Fixed

- The first `generate` of a project, which compares the entities with the live database, no
  longer writes changes that aren't real:
  - tables whose names differ only in letter case (`categories_seq` in the database,
    `categories_SEQ` in the entity) on databases that fold names, such as MySQL on Windows,
    PostgreSQL, H2 and Oracle; this produced a `CREATE` and a `DROP` of the same table;
  - the index MySQL and MariaDB create for each foreign key, which was dropped;
  - sequences that already exist, which were created again: real sequences, and the
    `next_val` tables MySQL uses instead (sequences the entities don't use, such as those of
    identity columns, are never touched);
  - columns whose types the database stores the same way, such as an `Instant` field on MySQL
    (`datetime(6)` either way).
- `migrate` with an empty migration folder now reports applied migrations whose files were
  deleted, instead of saying there is nothing to migrate.
- On Windows, every command printed the path of the old class-data archive
  (`...\migrax\cache\migrax-java21.jsa`) and left the file in place: Java creates it read-only.
  The launcher now removes it silently.

## [0.1.1] - 2026-10-09

### Fixed

- The `migrax` command crashed (a JVM crash, not an error message) when reading Hibernate 7.3 or
  newer entities on Java 21. A Java 21 bug (JDK-8391430) breaks the class-data archive the
  launcher keeps to start faster, so the launcher now keeps it only on Java 25 and newer, and
  removes archives that 0.1.0 left behind. The Maven plugin and the framework integrations were
  not affected.

## [0.1.0] - 2026-10-08

First release.

### Added

- **Generated migrations.** `migrax generate` compares JPA entities with a committed snapshot
  and writes a numbered SQL migration with a rollback script. `plan` previews it.
- **Hibernate's own mapping.** With Hibernate 5.4 to 7.4 in the project, entities are read
  through that Hibernate version, so names, types, join tables, element collections,
  inheritance and sequences match exactly. Annotation scanning covers projects without it.
- **Safe changes.** Rename detection with a confirmation prompt or `--rename`; drops only with
  `--allow-destructive`; `generate --safe` for non-blocking PostgreSQL indexes and constraints.
- **Runner.** `migrate`, `status`, `rollback`, `repair`; checksums of applied migrations,
  database locks, failure tracking, repeatable migrations, callbacks, placeholders, Java
  migrations and multi-tenant schemas.
- **Checks.** `check` for CI, `lint` with 15 rules for locking and data-loss risks, `verify`
  (migrations on a throwaway database validated by your Hibernate version, with rollback
  round trips) and `drift` (manual changes in the live database, including narrowed columns).
- **Team workflow.** `merge` for migrations two branches numbered the same, `squash`, and `new`
  for hand-written SQL or Java migrations.
- **Frameworks.** Configuration, naming and profile detection for Spring Boot, Quarkus,
  Micronaut 4 and 5 (including Micronaut Data naming), Helidon MP and SE, and Jakarta EE /
  plain Hibernate.
- **Integrations.** Startup migration for Spring Boot, Quarkus, Micronaut and Helidon; Maven
  and Gradle plugins; a GitHub Action that checks, lints and comments the planned SQL.
- **Databases.** PostgreSQL, MySQL 8, MariaDB, SQL Server, Oracle 12c+ and H2.
- **Diagnostics.** `doctor` checks the setup and explains risky Hibernate schema settings with
  the file, line and exact fix.
- **Command line.** Installers for Windows, macOS and Linux; works from any service folder
  without configuration.

[Unreleased]: https://github.com/fsmutimeer/migrax/compare/v0.6.1...HEAD
[0.6.1]: https://github.com/fsmutimeer/migrax/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/fsmutimeer/migrax/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/fsmutimeer/migrax/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/fsmutimeer/migrax/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/fsmutimeer/migrax/compare/v0.2.0...v0.3.0
[0.3.0-rc.3]: https://github.com/fsmutimeer/migrax/compare/v0.3.0-rc.2...v0.3.0-rc.3
[0.3.0-rc.2]: https://github.com/fsmutimeer/migrax/compare/v0.3.0-rc.1...v0.3.0-rc.2
[0.3.0-rc.1]: https://github.com/fsmutimeer/migrax/compare/v0.2.0...v0.3.0-rc.1
[0.2.0]: https://github.com/fsmutimeer/migrax/compare/v0.1.0...v0.2.0
[0.2.0-rc.6]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.5...v0.2.0-rc.6
[0.2.0-rc.5]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.4...v0.2.0-rc.5
[0.2.0-rc.4]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.3...v0.2.0-rc.4
[0.2.0-rc.3]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.2...v0.2.0-rc.3
[0.2.0-rc.2]: https://github.com/fsmutimeer/migrax/compare/v0.2.0-rc.1...v0.2.0-rc.2
[0.2.0-rc.1]: https://github.com/fsmutimeer/migrax/compare/v0.1.0...v0.2.0-rc.1
[0.1.4]: https://github.com/fsmutimeer/migrax/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/fsmutimeer/migrax/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/fsmutimeer/migrax/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/fsmutimeer/migrax/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/fsmutimeer/migrax/releases/tag/v0.1.0
