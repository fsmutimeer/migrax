# Changelog

All notable changes to Migrax are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

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

[Unreleased]: https://github.com/fsmutimeer/migrax/compare/v0.3.0-rc.2...HEAD
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
