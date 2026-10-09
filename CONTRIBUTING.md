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
