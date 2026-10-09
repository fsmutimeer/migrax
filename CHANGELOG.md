# Changelog

All notable changes to Migrax are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org).

## [Unreleased]

### Added

- MIT license.
- Download page on the documentation site, hosting every release's files with SHA-256
  checksums, updated automatically after each release.

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

[Unreleased]: https://github.com/fsmutimeer/migrax/compare/v0.1.1...HEAD
[0.1.1]: https://github.com/fsmutimeer/migrax/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/fsmutimeer/migrax/releases/tag/v0.1.0
