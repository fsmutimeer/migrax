# Versioning and releases

## Semantic versioning

Migrax versions follow [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

| Change | Example | Version bump |
|---|---|---|
| Bug fix, no behavior change you rely on | wrong column type fixed | `0.1.0` → `0.1.1` |
| New feature, backward compatible | new command, new framework | `0.1.1` → `0.2.0` |
| Breaking change | renamed option, new snapshot format | `0.x`: minor; from `1.0.0`: major |

**Before 1.0.0**, a minor release may change commands, options and configuration keys; the
changelog says how to upgrade. **From 1.0.0**, these are stable within a major version: CLI
commands and options, exit codes, configuration keys, the snapshot format, migration file and
directive formats, and the `JavaMigration` API.

Pre-releases are marked `-rc.N` (`0.2.0-rc.1`). Between releases, the code on `main` carries the
next version with `-SNAPSHOT` (`0.2.0-SNAPSHOT`).

## Release tags

Each release is an annotated git tag `vX.Y.Z` on `main`. Pushing the tag builds and tests
Migrax and publishes a GitHub release with:

- `migrax-X.Y.Z.zip`: the command line tool and installers;
- `migrax-X.Y.Z.jar`: the core library and Maven plugin;
- the integration jars (Spring Boot, Quarkus, Micronaut, Helidon, Gradle).

Release notes come from the [changelog](changelog.md).

## Branches

Migrax uses short-lived branches that merge into `main` through pull requests:

| Branch | Purpose | Example |
|---|---|---|
| `main` | Always releasable; protected; every change arrives by pull request with green CI | |
| `feature/<name>` | New functionality | `feature/sqlite-dialect` |
| `fix/<name>` | Bug fixes (add the issue number when there is one) | `fix/42-mysql-enum-length` |
| `docs/<name>` | Documentation only | `docs/micronaut-guide` |
| `refactor/<name>`, `test/<name>`, `ci/<name>`, `chore/<name>` | Other changes | `ci/hibernate-matrix` |
| `release/X.Y.Z` | Preparing a release: version, changelog, last fixes | `release/0.2.0` |
| `hotfix/X.Y.Z` | Urgent fix for a released version, branched from its tag | `hotfix/0.1.1` |

Branch names are lowercase, with words separated by hyphens.

## Commit messages

Commits follow [Conventional Commits](https://www.conventionalcommits.org):

```text
feat(cli): add --impact to plan
fix(hibernate): read identity columns on Hibernate 6.3
docs: add the Helidon guide
chore(release): 0.2.0
```

Types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`. A breaking
change adds `!` (`feat(cli)!: rename --dir to --project`) and a `BREAKING CHANGE:` footer that
explains the upgrade.

## How a release is made

1. Create `release/X.Y.Z` from `main`.
2. Set the version everywhere with `scripts/set-version.sh X.Y.Z`.
3. Move the `Unreleased` changelog entries under `## [X.Y.Z] - YYYY-MM-DD`.
4. Open a pull request, wait for green CI, merge into `main`.
5. Tag the merge: `git tag -a vX.Y.Z -m "Migrax X.Y.Z"` and `git push origin vX.Y.Z`.
6. Set `main` to the next development version: `scripts/set-version.sh X.Y.(Z+1)-SNAPSHOT`.
