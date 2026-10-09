# Contributing to Migrax

Thank you for helping. This guide covers building, testing and the rules for branches, commits
and releases.

## Build and test

Requirements: Java 17 or newer, Maven 3.9, and Docker for the real-database suite.

```bash
mvn install                            # core library, CLI and Maven plugin; unit tests and SpotBugs
mvn install -f integrations/pom.xml    # Spring Boot, Quarkus, Micronaut, Helidon and Gradle integrations
```

Install the CLI you just built:

```bash
sh install.sh                                           # macOS and Linux
powershell -ExecutionPolicy Bypass -File install.ps1    # Windows
```

### Test suites

| Suite | Command | Covers |
|---|---|---|
| Unit and H2 tests | `mvn verify` | CLI, diff engine, readers, runner, Hibernate validation on H2 |
| Integrations | `mvn install -f integrations/pom.xml` | Startup integrations in real Spring Boot, Quarkus, Micronaut and Helidon applications |
| Real databases | `mvn verify -Pdatabase-integration` | MySQL, MariaDB, PostgreSQL, SQL Server and Oracle in Docker |
| Hibernate versions | `bash compat/hibernate/run.sh [version ...]` | Generate, migrate and verify with every Hibernate minor version from 5.4 to 7.4 |

CI runs all of them on every pull request.

### Writing tests

- Changes to how entities are read must keep the annotation scanner and Hibernate's mapping in
  agreement (`HibernateValidationTest`) and pass the Hibernate version matrix.
- Changes to the CLI need a test in `src/test/java/io/migrax/cli`.
- A bug fix comes with a test that fails without the fix.

## How the code is organized

Everything lives under `src/main/java/io/migrax`:

| Package | Contents |
|---|---|
| `cli` | The `migrax` command: `Main`, one `*Command` class per command, and shared helpers |
| `model` | Reading entities into a `SchemaModel` (Hibernate's mapping or annotation scanning) |
| `orm` | The parts that run against the project's own Hibernate version |
| `diff` | Comparing two models into operations, rename detection, snapshots |
| `ops` | The operations a migration consists of (`CreateTable`, `AddColumn`, ...) |
| `dialect` | Rendering operations as SQL for each database |
| `runner` | Applying, rolling back and tracking migrations; startup integration support |
| `lint`, `verify` | SQL linting; throwaway databases and schema comparison |
| `plugin` | Maven goals (`mvn migrax:<command>`), which run the CLI commands |

### Adding a command

1. Create `src/main/java/io/migrax/cli/<Name>Command.java` implementing `Command`. Copy a small
   one such as `SqlCommand` as a starting point. The interface asks for the name, help texts,
   the options the command accepts, and `run(CommandContext)`.
2. Add it to `CommandRegistry.standard()`. `migrax help`, `migrax help <name>`, aliases and
   "did you mean" suggestions then work without other changes.
3. A new option goes into `Options`: the `VALUES` or `FLAGS` set, plus its help line.
4. Optionally add a Maven goal: a `*Mojo` in `plugin` extending `AbstractMigraxMojo`.

In `run`, take everything from the context instead of creating it: `context.project()`,
`context.args()`, `context.out()`, `context.migrationRunner()`. Report mistakes the user can fix
by throwing `UsageException` with a hint; Migrax prints them without a stack trace. Return an
`ExitCode`.

Shared helpers: `EntityChanges` (entity changes since the snapshot), `MigrationSql` (writing
migration and rollback files), `MigrationFiles`, `DuplicateMigrations`, `Reports` (printing
operations and lint findings) and `Errors`.

`CommandRegistryTest` checks every registered command's help and options; `CommandsTest` and
`MainTest` show how to run a command end to end against an H2 project.

### Adding a database

1. Create a dialect in `io.migrax.dialect` extending `AbstractDialect`: `id()` (also the JDBC
   URL prefix, `jdbc:<id>:`, unless you override `acceptsUrl`), `logicalType()` for the column
   types, and `aliases()` if the database has other common names.
2. List the class in `src/main/resources/META-INF/services/io.migrax.dialect.Dialect`.
   `--dialect`, detection from the JDBC URL and the help texts pick it up from there.
3. Add migration locking for the database in `runner/DatabaseMigrationLock`.
4. Add it to the real-database suite (`DatabaseEngineIT`) and to `DialectTest`.

## Branches

Work on a short-lived branch and open a pull request into `main`:

| Branch | Use |
|---|---|
| `feature/<name>` | New functionality |
| `fix/<name>` or `fix/<issue>-<name>` | Bug fixes |
| `docs/<name>`, `refactor/<name>`, `test/<name>`, `ci/<name>`, `chore/<name>` | Other changes |
| `release/X.Y.Z` | Release preparation (maintainers) |
| `hotfix/X.Y.Z` | Urgent fix for a released version (maintainers) |

`main` is protected: changes arrive by pull request with green CI.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org): `type(scope): summary`, for example
`fix(hibernate): read identity columns on Hibernate 6.3`. Types: `feat`, `fix`, `docs`,
`refactor`, `perf`, `test`, `build`, `ci`, `chore`. Mark breaking changes with `!` and a
`BREAKING CHANGE:` footer.

Add a line to the `Unreleased` section of `CHANGELOG.md` for every user-visible change.

## Documentation

The documentation site is built with MkDocs Material from `docs/`:

```bash
pip install -r docs/requirements.txt
python scripts/fetch-releases.py   # copies the release files and writes the Download page
mkdocs serve                       # live preview at http://127.0.0.1:8000
mkdocs build --strict              # what CI runs
```

`scripts/fetch-releases.py` must run before building: the Download page includes the file it
generates (`docs/downloads/`, not committed).

The CLI reference (`docs/reference/cli.md`) mirrors `migrax help <command>`: update it when you
change a command or option.

## Releases

Versioning follows [Semantic Versioning](https://semver.org). See
[Versioning and releases](https://docs-migrax.github.io/project/versioning/) for the release steps; in short:

```bash
git switch -c release/0.2.0 main
scripts/set-version.sh 0.2.0
# update CHANGELOG.md, open a pull request, merge it, then:
git tag -a v0.2.0 -m "Migrax 0.2.0" && git push origin v0.2.0
```
