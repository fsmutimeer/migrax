# Continuous integration

Three commands make a good CI gate:

| Command | Fails when | Exit code |
|---|---|---|
| `migrax check` | an entity changed without a migration, or two migrations share a number | 2 / 1 |
| `migrax lint --strict` | a pending migration would lock tables, fail on data, or break running instances | 1 |
| `migrax verify` | the migrations don't produce a schema Hibernate accepts, or a rollback script fails | 1 |

## GitHub Actions

The Migrax repository is itself a GitHub Action. It runs `check`, lints new migrations and posts
the planned SQL as a pull request comment:

```yaml title=".github/workflows/migrations.yml"
name: Migrations
on: pull_request

permissions:
  contents: read
  pull-requests: write   # for the comment

jobs:
  migrax:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: fsmutimeer/migrax@v0.1.3
        with:
          working-directory: .          # folder with pom.xml or build.gradle
          fail-on-lint-warnings: 'false'
```

| Input | Default | Meaning |
|---|---|---|
| `working-directory` | `.` | Service folder |
| `java-version` | `21` | Java used to build Migrax and your service |
| `fail-on-lint-warnings` | `false` | Fail on lint warnings, not only errors |
| `comment` | `true` | Post or update a pull request comment with the results |
| `github-token` | `${{ github.token }}` | Token used to comment |

Outputs: `changes` (entity changes without a migration) and `findings` (lint findings in
changed migrations).

## Any CI system

```bash
migrax check --no-input
migrax lint --strict
migrax verify          # H2 in memory, or Docker for other engines
```

`verify` starts a throwaway database: in-memory H2 for H2 projects, or a container of your engine
when Docker is available. Without Docker, point it at an empty scratch database:

```bash
migrax verify --url jdbc:postgresql://ci-db:5432/scratch
```

The database user and password come from the same place as usual (environment variables or
application configuration) unless you pass `--user` / `--password`.

## Machine-readable output

`plan`, `status`, `check`, `lint`, `drift` and `verify` accept `--json`. Progress messages go to
standard error, so standard output stays valid JSON:

```console
$ migrax check --json
{"ok":false,"changes":["add column orders.notes"],"conflicts":[]}
```
