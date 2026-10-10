# Changelog

All notable changes to Migrax are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

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

[Unreleased]: https://github.com/fsmutimeer/migrax/compare/v0.1.4...HEAD
[0.1.4]: https://github.com/fsmutimeer/migrax/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/fsmutimeer/migrax/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/fsmutimeer/migrax/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/fsmutimeer/migrax/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/fsmutimeer/migrax/releases/tag/v0.1.0
