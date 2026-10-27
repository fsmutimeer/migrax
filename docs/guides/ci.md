---
seo_title: "Database migration checks in CI: GitHub Actions, GitLab CI, Bitbucket"
description: "Check migrations in CI: entity changes without a migration, SQL lint, schema verification and an HTML report, with GitHub Actions, GitLab CI or Bitbucket."
---
# Continuous integration

Three commands make a good CI gate:

| Command | Fails when | Exit code |
|---|---|---|
| `migrax check` | an entity changed without a migration, or two migrations share a number | 2 / 1 |
| `migrax lint --strict` | a pending migration would lock tables, fail on data, or break running instances | 1 |
| `migrax verify` | the migrations don't produce a schema Hibernate accepts, or a rollback script fails | 1 |

## GitHub Actions

The Migrax repository is itself a GitHub Action. It runs `check`, lints new migrations, posts
the planned SQL as a pull request comment and uploads the [HTML report](#an-html-report) as the
artifact `migrax-report`:

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
      - uses: actions/checkout@v7
      - uses: fsmutimeer/migrax@v0.17.1
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
| `report` | `true` | Write the HTML report and upload it; the comment links to it |
| `github-token` | `${{ github.token }}` | Token used to comment |

Outputs: `changes` (entity changes without a migration) and `findings` (lint findings in
changed migrations).

## GitLab CI

New in Migrax 0.14.0.

Include the Migrax job in `.gitlab-ci.yml`. It runs in merge request pipelines and does what the
GitHub Action does: `check`, lint of the migrations the merge request adds or changes, the HTML
report (shown as **Migrax report** on the merge request), and a comment with the results.

```yaml title=".gitlab-ci.yml"
include:
  - remote: https://raw.githubusercontent.com/fsmutimeer/migrax/v0.17.1/ci/gitlab/migrax.gitlab-ci.yml
```

To change a setting, add a `migrax` job of your own with only the keys to replace:

```yaml title=".gitlab-ci.yml"
migrax:
  image: gradle:8-jdk21          # the default is maven:3.9-eclipse-temurin-21
  variables:
    MIGRAX_DIR: services/orders  # folder with pom.xml or build.gradle
```

For the comment, Migrax needs a token:

1. In the project, open **Settings > Access tokens** and add a token with the role
   **Reporter** and the scope **api**.
2. In **Settings > CI/CD > Variables**, add the variable `MIGRAX_COMMENT_TOKEN` with that token,
   with **Mask variable** on.

Without the token the job still runs and fails on problems; only the comment is left out. Each
pipeline updates the same comment instead of adding a new one.

## Bitbucket Pipelines

New in Migrax 0.14.0.

Copy this step into `bitbucket-pipelines.yml`. It runs for pull requests and does the same as
the GitLab job; the report is in the pipeline's **Artifacts** tab, where Bitbucket keeps it for
14 days.

```yaml title="bitbucket-pipelines.yml"
image: maven:3.9-eclipse-temurin-21   # gradle:8-jdk21 for Gradle projects

pipelines:
  pull-requests:
    '**':
      - step:
          name: Migrax
          caches:
            - maven
          script:
            - export MIGRAX_VERSION=0.17.1
            - export MIGRAX_DIR=.       # folder with pom.xml or build.gradle
            - curl -fsSL --retry 3 -o /tmp/migrax-ci.sh "https://raw.githubusercontent.com/fsmutimeer/migrax/v${MIGRAX_VERSION}/ci/migrax-ci.sh"
            - sh /tmp/migrax-ci.sh
          artifacts:
            - migrax-ci/migrax-report.html
```

For the comment:

1. In the repository, open **Repository settings > Access tokens** and create a token with the
   permission **Pull requests: Write**.
2. In **Repository settings > Repository variables**, add `MIGRAX_COMMENT_TOKEN` with that
   token, with **Secured** on.

### Settings

Both templates run the same script, `ci/migrax-ci.sh` in the Migrax repository. It needs
Java 17 or newer, `curl`, `git` and your build tool, so any Maven or Gradle image works. The
Migrax container image has no build tool, so `check` can't compile the entities there.

| Variable | Default | Meaning |
|---|---|---|
| `MIGRAX_VERSION` | the template's version | Migrax version to download |
| `MIGRAX_DIR` | `.` | Service folder |
| `MIGRAX_REPORT` | `true` | Write the HTML report |
| `MIGRAX_FAIL_ON_LINT_WARNINGS` | `false` | Fail on lint warnings, not only errors |
| `MIGRAX_COMMENT_TOKEN` | none | Token for the comment; without it, no comment |

The usual `MIGRAX_DATABASE_URL`, `MIGRAX_DATABASE_USER` and `MIGRAX_DATABASE_PASSWORD` work too:
with a database, the report also shows which migrations are applied, and the drift.

## An HTML report

`migrax report` writes one page for reviewers: the schema with a diagram of the tables, entity
changes that have no migration yet, every migration with its status and lint findings, and
drift between the database and the snapshot.

```console
$ migrax report --html target/migrax-report.html
Wrote target/migrax-report.html: 8 table(s), 4 migration(s), 1 pending, 3 lint finding(s), no drift.
```

The page is a single file that works offline; only the diagram needs the internet (it loads
Mermaid from a CDN, and shows its text without it). Without a database connection, the report
still has the schema, the migrations and the lint findings, and says that their status and
drift were not checked.

In other CI systems, keep the file as a build artifact. In GitHub Actions without the Migrax
action:

```yaml
      - run: migrax report --html migrax-report.html
      - uses: actions/upload-artifact@v7
        with:
          name: migrax-report
          path: migrax-report.html
```

With Maven: `mvn migrax:report -Dmigrax.html=target/migrax-report.html`.

## Any CI system

```bash
migrax check --no-input
migrax lint --strict
migrax verify          # H2, HSQLDB or Derby in memory, or Docker for other engines
migrax report --html migrax-report.html   # keep it as a build artifact
```

`verify` starts a throwaway database: in memory for H2, HSQLDB and Derby projects, or a container of your engine
when Docker is available. The container runs your server's version when Migrax can reach the
configured database (since Migrax 0.17.2), or else its default image; `--image` picks another.
Without Docker, point it at an empty scratch database:

```bash
migrax verify --url jdbc:postgresql://ci-db:5432/scratch
```

The database user and password come from the same place as usual (environment variables or
application configuration) unless you pass `--user` / `--password`.

## Machine-readable output

`migrate`, `rollback`, `status`, `plan`, `check`, `lint`, `drift`, `verify`, `diff`, `doctor`,
`baseline`, `repair`, `clean`, `new`, `merge`, `squash`, `backups` and `environments` accept
`--json`. Progress messages go to standard error, so standard output stays valid JSON:

```console
$ migrax check --json
{"ok":false,"changes":["add column orders.notes"],"conflicts":[]}
```
