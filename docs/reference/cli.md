# CLI commands

Run `migrax help <command>` for the same information in the terminal. Run every command from the
service folder (the one with `pom.xml` or `build.gradle`), or pass `--dir <path>`.

## Options for every command

| Option | Meaning |
|---|---|
| `--dir <path>` | Project folder (default: current folder) |
| `--no-build` | Do not run Maven/Gradle; use the classes and dependencies from the last build |
| `--refresh` | Re-resolve dependencies even if cached |
| `--json` | Machine-readable output (`plan`, `status`, `check`, `lint`, `drift`, `verify`) |
| `--verbose`, `-v` | Show debug output, Hibernate's own log and stack traces |
| `--no-input` | Never ask questions (the default when input is not a terminal) |

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Error, failed check, or failed migration |
| `2` | Differences found: `check` (entity changes without a migration), `drift` (database differs) |

## Getting started

### init

```
migrax init
```

Creates the .migrax folder and the migration folder, then shows the entity package, database, dialect and naming Migrax detected. Safe to run more than once.

| Option | Meaning |
|---|---|
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--naming <strategy>` | spring, jpa, jpa-snake or micronaut (default: detected) |
| `--dir <path>` | Project folder (default: current folder) |

### doctor

```
migrax doctor
```

Runs every check Migrax needs and explains how to fix anything that fails. Exits with code 1 when a check fails.

| Option | Meaning |
|---|---|
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--user <name>` | Database user |
| `--password <secret>` | Database password (prefer env vars) |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--extractor <mode>` | auto, hibernate or annotations (default: auto) |
| `--dir <path>` | Project folder (default: current folder) |

## Everyday

### generate

*Alias:* `makemigrations`

```
migrax generate [--name <name>] [--allow-destructive] [--safe]
```

Compiles the project if needed, compares the entities with `.migrax/snapshot.json` and writes a numbered SQL file plus a rollback script (`rollback/<file>`). On the first run, with no snapshot, the current database schema is the baseline: its tables are written to `0001_baseline.sql`, which that database records as applied without running it. When a column or table seems renamed, Migrax asks (or pass `--rename` / `--rename-table`) so the data is kept. Drops need `--allow-destructive`. `--safe` (PostgreSQL, CockroachDB) builds indexes and constraints on existing tables without blocking writes, in a second migration. Always review the generated SQL; Migrax lints it for you.

| Option | Meaning |
|---|---|
| `--name <name>` | Migration file name (default: next number + description) |
| `--allow-destructive` | Allow drops and narrowing type changes |
| `--safe` | Non-blocking indexes and constraints (PostgreSQL, CockroachDB) |
| `--rename t.old=new` | Treat a column change as a rename (comma-separated) |
| `--rename-table old=new` | Treat a table change as a rename |
| `--no-input` | Never ask questions |
| `--dialect <name>` | postgresql, cockroachdb, mysql, mariadb, sqlserver, oracle, h2 |
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--naming <strategy>` | spring, jpa, jpa-snake or micronaut (default: detected) |
| `--extractor <mode>` | auto, hibernate or annotations (default: auto) |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--user <name>` | Database user |
| `--password <secret>` | Database password (prefer env vars) |
| `--schema <name>` | Database schema to read |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--refresh` | Re-resolve dependencies even if cached |
| `--dir <path>` | Project folder (default: current folder) |

### migrate

```
migrax migrate [--dry-run] [--resume] [--schemas a,b]
```

Applies pending SQL and Java migrations in order, then new or changed repeatable `R__*.sql` migrations, with beforeMigrate/afterMigrate callbacks and `${placeholders}`. `--dry-run` lists and lints pending migrations without changing the database. `--schemas` runs the migrations once per schema (multi-tenant). `--resume` re-runs a failed migration only if it is marked `-- migrax:resume-safe`.

| Option | Meaning |
|---|---|
| `--dry-run` | Show what would happen without doing it |
| `--resume` | Re-run a failed resume-safe migration |
| `--schemas <a,b>` | Run for each schema (multi-tenant) |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--user <name>` | Database user |
| `--password <secret>` | Database password (prefer env vars) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--java-package <pkg>` | Package of Java migrations (default: db.migration) |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--dir <path>` | Project folder (default: current folder) |

### status

*Alias:* `showmigrations`

```
migrax status [--json]
```

Lists migrations as applied [X], pending [ ] or a problem [!], without changing the database. Exits with code 1 when a migration failed, changed or is missing.

| Option | Meaning |
|---|---|
| `--json` | Machine-readable output |
| `--schemas <a,b>` | Run for each schema (multi-tenant) |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--user <name>` | Database user |
| `--password <secret>` | Database password (prefer env vars) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--java-package <pkg>` | Package of Java migrations (default: db.migration) |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--dir <path>` | Project folder (default: current folder) |

### plan

*Alias:* `sqlmigrate`

```
migrax plan [--impact] [--json]
```

Shows the operations and SQL for current entity changes without writing files. `--impact` adds the estimated size of each affected table from the database.

| Option | Meaning |
|---|---|
| `--impact` | Show affected table sizes (needs the database) |
| `--json` | Machine-readable output |
| `--dialect <name>` | postgresql, cockroachdb, mysql, mariadb, sqlserver, oracle, h2 |
| `--rename t.old=new` | Treat a column change as a rename (comma-separated) |
| `--rename-table old=new` | Treat a table change as a rename |
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--naming <strategy>` | spring, jpa, jpa-snake or micronaut (default: detected) |
| `--extractor <mode>` | auto, hibernate or annotations (default: auto) |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--refresh` | Re-resolve dependencies even if cached |
| `--dir <path>` | Project folder (default: current folder) |

### rollback

```
migrax rollback [--steps n | --to <migration>] [--dry-run] --yes
```

Runs the rollback script of the newest applied migration (or several) and removes it from the history. generate writes rollback scripts to `rollback/<file>`; Java migrations roll back with `JavaMigration.rollback`. Restores structure, not deleted data.

| Option | Meaning |
|---|---|
| `--steps <n>` | Number of migrations to roll back |
| `--to <migration>` | Last migration to keep / squash up to |
| `--dry-run` | Show what would happen without doing it |
| `--yes, -y` | Confirm without asking |
| `--schemas <a,b>` | Run for each schema (multi-tenant) |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--user <name>` | Database user |
| `--password <secret>` | Database password (prefer env vars) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--dir <path>` | Project folder (default: current folder) |

### new

```
migrax new <name> [--java]
```

Creates the next numbered SQL file for hand-written changes such as data migrations, with an empty rollback script. `--java` creates a JavaMigration class instead.

| Option | Meaning |
|---|---|
| `--java` | Create a Java migration |
| `--java-package <pkg>` | Package of Java migrations (default: db.migration) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--dir <path>` | Project folder (default: current folder) |

## Safety and CI

### check

```
migrax check [--json]
```

Exits with code 2 when the entities differ from `.migrax/snapshot.json`, and with 1 when two migrations share a number (run `migrax merge`).

| Option | Meaning |
|---|---|
| `--json` | Machine-readable output |
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--naming <strategy>` | spring, jpa, jpa-snake or micronaut (default: detected) |
| `--extractor <mode>` | auto, hibernate or annotations (default: auto) |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--refresh` | Re-resolve dependencies even if cached |
| `--dir <path>` | Project folder (default: current folder) |

### lint

```
migrax lint [files...] [--all] [--strict] [--json]
```

Checks pending migrations (or the given files, or `--all`) for statements that block tables, fail on existing data, or break running application instances, and suggests safe alternatives. Exits with 1 on errors, or on warnings with `--strict`. Silence a finding with `-- migrax:lint-ignore MX001` before the statement.

| Option | Meaning |
|---|---|
| `--all` | Lint every migration, not only pending ones |
| `--strict` | Fail on warnings too |
| `--json` | Machine-readable output |
| `--dialect <name>` | postgresql, cockroachdb, mysql, mariadb, sqlserver, oracle, h2 |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--dir <path>` | Project folder (default: current folder) |

### verify

```
migrax verify [--url <scratch-db>] [--skip-rollbacks]
```

Starts a throwaway database (H2 in memory, or Docker for other engines), applies every migration, then checks the result: Hibernate's schema validation when Hibernate is in the project, otherwise a structural comparison with the entities. It also rolls every migration back and forward again to prove the rollback scripts work.

| Option | Meaning |
|---|---|
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--user <name>` | Database user |
| `--password <secret>` | Database password (prefer env vars) |
| `--image <image>` | Docker image for the throwaway database |
| `--skip-rollbacks` | Do not test rollback scripts |
| `--json` | Machine-readable output |
| `--dialect <name>` | postgresql, cockroachdb, mysql, mariadb, sqlserver, oracle, h2 |
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--naming <strategy>` | spring, jpa, jpa-snake or micronaut (default: detected) |
| `--extractor <mode>` | auto, hibernate or annotations (default: auto) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--dir <path>` | Project folder (default: current folder) |

### drift

```
migrax drift [--entities] [--json]
```

Reads the database schema and reports manual changes: missing or extra tables, columns, keys and unique constraints, nullability and type differences. Compares with the snapshot (what migrations produce) or, with `--entities`, the entities. Exits with code 2 when drift is found.

| Option | Meaning |
|---|---|
| `--entities` | Compare with the entities instead of the snapshot |
| `--json` | Machine-readable output |
| `--schema <name>` | Database schema to read |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--user <name>` | Database user |
| `--password <secret>` | Database password (prefer env vars) |
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--naming <strategy>` | spring, jpa, jpa-snake or micronaut (default: detected) |
| `--extractor <mode>` | auto, hibernate or annotations (default: auto) |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--dir <path>` | Project folder (default: current folder) |

## Maintenance

### squash

*Alias:* `squashmigrations`

```
migrax squash --to <migration> [--optimize]
```

Writes one migration that replaces all migrations up to `--to`. Databases that already applied them record it without running it; new databases run just the squashed file. `--optimize` writes only the resulting schema (data statements are dropped). Delete the old files once every environment has run migrate.

| Option | Meaning |
|---|---|
| `--to <migration>` | Last migration to keep / squash up to |
| `--name <name>` | Migration file name (default: next number + description) |
| `--optimize` | Squash to the resulting schema only |
| `--dialect <name>` | postgresql, cockroachdb, mysql, mariadb, sqlserver, oracle, h2 |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--dir <path>` | Project folder (default: current folder) |

### merge

```
migrax merge
```

Renumbers migrations that share a number after a git merge (the one added later moves to the next free number) and rebuilds a snapshot that has merge conflicts.

| Option | Meaning |
|---|---|
| `--dialect <name>` | postgresql, cockroachdb, mysql, mariadb, sqlserver, oracle, h2 |
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--naming <strategy>` | spring, jpa, jpa-snake or micronaut (default: detected) |
| `--extractor <mode>` | auto, hibernate or annotations (default: auto) |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--dir <path>` | Project folder (default: current folder) |

### repair

```
migrax repair <migration>... --action applied|retry|forget --yes
```

Use only after inspecting the database.

- `--action applied`: every statement took effect, so record the migration as applied.
- `--action retry`: you restored the database, so clear the failure and let it run again.
- `--action forget`: you deleted applied migration files on purpose, so remove them from the
  history. The database keeps the changes they made. Accepts several migrations at once.

`--yes` confirms the change to the migration history.

| Option | Meaning |
|---|---|
| `--action <action>` | applied, retry or forget |
| `--yes, -y` | Confirm without asking |
| `--url <jdbc-url>` | Database URL (default: application config) |
| `--user <name>` | Database user |
| `--password <secret>` | Database password (prefer env vars) |
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--dir <path>` | Project folder (default: current folder) |

### inspect

```
migrax inspect
```

Prints the schema model Migrax builds from the entities.

| Option | Meaning |
|---|---|
| `--package <name>` | Entity package (default: MIGRAX_PACKAGE or pom groupId) |
| `--naming <strategy>` | spring, jpa, jpa-snake or micronaut (default: detected) |
| `--extractor <mode>` | auto, hibernate or annotations (default: auto) |
| `--dialect <name>` | postgresql, cockroachdb, mysql, mariadb, sqlserver, oracle, h2 |
| `--classpath <paths>` | Extra classpath; skips Maven/Gradle resolution |
| `--no-build` | Do not run Maven/Gradle; use compiled classes |
| `--refresh` | Re-resolve dependencies even if cached |
| `--dir <path>` | Project folder (default: current folder) |

### sql

```
migrax sql <migration>
```

Prints a migration file from the migration folder.

| Option | Meaning |
|---|---|
| `--locations <path>` | Migration folder, e.g. filesystem:db/sql |
| `--dir <path>` | Project folder (default: current folder) |

### version

```
migrax version
```

Prints the Migrax version.

### help

```
migrax help [command]
```

Shows general help, or help for one command.
