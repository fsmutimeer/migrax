git # Migrax

[![CI](https://github.com/fsmutimeer/migrax/actions/workflows/ci.yml/badge.svg)](https://github.com/fsmutimeer/migrax/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/fsmutimeer/migrax?sort=semver)](https://github.com/fsmutimeer/migrax/releases)
[![Docs](https://img.shields.io/badge/docs-docs--migrax.github.io-e53935)](https://docs-migrax.github.io/)
![Java 17+](https://img.shields.io/badge/java-17%2B-orange)
[![License: MIT](https://img.shields.io/badge/license-MIT-green)](LICENSE)

**Your entities change. Migrax writes the migration.**

Migrax reads your JPA entities, compares them with what your database looks like, and writes the
SQL migration for you, with a rollback script, linted for locking and data loss, and verified by
your own Hibernate version before it reaches production.

**[Documentation](https://docs-migrax.github.io/)** ·
[Quick start](https://docs-migrax.github.io/getting-started/quickstart/) ·
[CLI reference](https://docs-migrax.github.io/reference/cli/) ·
[Changelog](CHANGELOG.md)

```console
$ migrax generate
Created src/main/resources/db/migration/0002_add_customers_phone.sql:
  - add column customers.phone
Rollback script written to src/main/resources/db/migration/rollback.
Review the SQL, then run 'migrax migrate'.

$ migrax migrate
Successfully applied migration '0002_add_customers_phone.sql'.
```

## Highlights

- **Migrations written for you.** Tables, columns, keys, indexes, join tables, element
  collections, sequences and inheritance, exactly as your Hibernate version maps them.
- **Asks before it loses data.** Likely renames are confirmed and kept as renames; drops need
  `--allow-destructive`.
- **Rollback included.** Every generated migration has a rollback script, and `verify` proves it
  works.
- **Checked before production.** `lint` finds statements that lock tables or break running
  instances, `verify` has Hibernate validate the result, and `drift` catches manual changes.
- **Zero setup.** Run it in your service folder; it builds the project and reads the database
  settings your application already has.

## Supported

| | |
|---|---|
| Frameworks | Spring Boot, Quarkus, Micronaut 4 and 5, Helidon 4, Jakarta EE and plain Hibernate |
| Hibernate | 5.4 to 7.4, each version reading and validating its own mapping |
| Databases | PostgreSQL, MySQL 8, MariaDB, SQL Server, Oracle 12c+, H2 |
| Ways to run | Command line, Maven plugin, Gradle plugin, at application startup, GitHub Action |

## Install

Java 17 or newer is required. Download the ZIP from the
[Download page](https://docs-migrax.github.io/download/) (or build with `mvn install`), extract
it, then run the installer from that folder:

```bash
sh install.sh                                           # macOS and Linux
powershell -ExecutionPolicy Bypass -File install.ps1    # Windows
```

Open a new terminal and check it with `migrax version`.

## Quick start

From your service folder (the one with `pom.xml` or `build.gradle`):

```bash
migrax doctor      # checks Java, build, entities and the database connection
migrax generate    # writes db/migration/0001_initial.sql and its rollback script
migrax migrate     # applies it
```

Then, every time you change an entity: `migrax generate` and `migrax migrate`.
Read the [full documentation](https://docs-migrax.github.io/) for guides on every
framework, CI and production safety.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for building, testing, branch and commit conventions.
Versions follow [Semantic Versioning](https://semver.org); changes are listed in
[CHANGELOG.md](CHANGELOG.md).

## License

Migrax is released under the [MIT License](LICENSE).
