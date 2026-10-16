# Production safety

Migrax is built around one idea: a migration should be proven before it runs in production.

## Lint every migration

Every generated migration is linted as it is written, and `migrax lint` checks the pending ones
(or `--all`, or the files you name). Findings explain the risk and the safer alternative:

```console
$ migrax lint
src/main/resources/db/migration/0007_add_orders_notes_and_more.sql, statement 3: warning MX007
  ALTER TABLE test_table DROP COLUMN numberOfItems
  This deletes data, and running application instances that still use it will fail.
  Fix: Deploy code that no longer uses it first, then drop it in a later release (expand/contract: 'migrax generate --zero-downtime' does this).
1 file(s) checked, 1 finding(s).
```

See all [lint rules](../reference/lint-rules.md). To accept a finding deliberately, put
`-- migrax:lint-ignore MX007` on the line before the statement.

### Non-blocking changes on PostgreSQL

`migrax generate --safe` moves index and constraint creation on existing tables into a second,
non-transactional migration that uses `CREATE INDEX CONCURRENTLY`, `NOT VALID` and
`VALIDATE CONSTRAINT`, so writes keep flowing while they build.

### Changes while the old version still runs

During a rolling deployment, instances of the old and the new version use the database at the
same time. `migrax generate --zero-downtime` splits a change so that both work: see
[Zero-downtime changes](zero-downtime.md).

## Verify before you ship

```console
$ migrax verify
Applied 7 migration(s) to a fresh postgresql database.
Schema matches the entities (Hibernate 6.6.13.Final schema validation).
Rollback scripts: ok (7 rolled back and re-applied).
Verified.
```

`verify`:

1. starts a throwaway database: in-memory H2, HSQLDB or Derby, a Docker container of your engine, or the empty
   database you pass with `--url`;
2. applies every migration from scratch;
3. starts **your** Hibernate version with schema validation against the result (without
   Hibernate, it compares the schema with the entities);
4. tests every rollback script on its own, newest first: it rolls the migration back, applies
   it again and checks that the schema is the same as before. A script that fails or leaves
   something behind is named, for example
   `Rollback scripts: failed: 0005_tag_color.sql fails when applied again after its rollback
   script ran: ...`. When older migrations have no rollback script, it checks the newest ones
   that do.

Run it in CI on every pull request.

## Detect manual changes

```console
$ migrax drift
2 difference(s) between the database and the snapshot:
  - products.name: expected varchar(255) but the database column is shorter (varchar(100))
  - products.hotfix: column exists in the database but not in the expected schema
Someone changed the database outside migrations. Write a migration for intended changes
('migrax new <name>'), or revert them.
```

`drift` reads the live database and compares it with what the migrations produce (the snapshot),
or with the entities (`--entities`). It reports missing and extra tables and columns, keys,
unique constraints, nullability, type changes and narrowed columns. It exits with code 2 when it
finds drift, so it can run on a schedule.

## Hibernate must not change the schema

When Hibernate is allowed to create or update tables at startup, the database drifts away from
your migrations, and migrations later fail with "already exists". `create` and
`drop-and-create` even delete all data on every start. `migrax doctor` finds these settings and
shows the exact line to change:

```console
  [warn]  Hibernate will change your database schema by itself
          Found:  quarkus.hibernate-orm.schema-management.strategy = update
                  in src/main/resources/application.properties, line 15

          Why:    Hibernate adds tables and columns itself when the application starts. ...

          Fix:    change   quarkus.hibernate-orm.schema-management.strategy=update
                  to       quarkus.hibernate-orm.schema-management.strategy=none
                  (or validate: Hibernate then checks the schema at startup without changing it)
```

## When a DBA runs the migrations

Where developers may not change production themselves, hand the DBA one script:

```console
$ migrax migrate --sql > release-2026-10.sql
```

It is exactly what `migrate` would do now against that database: the pending migrations in
order, each with the row Migrax records in `migrax_history` (so `migrax status` shows them as
applied afterwards), transactions where the database supports them, and the end markers its
tool expects (`GO` for `sqlcmd`, `/` after PL/SQL for `sqlplus`, `DELIMITER` for `mysql`). The
first lines say how to run it so it stops at the first error, for example
`psql -v ON_ERROR_STOP=1 -f release-2026-10.sql`. Writing the script reads the database but
changes nothing.

With `--schemas tenant_a,tenant_b`, the script has a section per schema that starts by
switching to it (`SET search_path`, `USE` or `ALTER SESSION SET CURRENT_SCHEMA`), so one run of
the file migrates every tenant.

A Java migration can't be written as SQL. The script stops before the first one and its last
lines say what to do: run the script, then `migrax migrate --to <that migration>`, then write
the next script with `migrax migrate --sql`.

## Applying a release in steps

To roll out several migrations one at a time (for example, run the slow index first and check
the database before going on), stop after a given migration:

```console
$ migrax migrate --to 0007
Applying migration '0007_add_order_index.sql'...
Successfully applied migration '0007_add_order_index.sql'.
Applied 1 migration(s) up to 0007_add_order_index.sql.
2 migration(s) after it stay pending.
```

`--to` takes the file name, its start, or the version number. Repeatable migrations (`R__*.sql`)
wait until a `migrate` without `--to`, because they may use what later migrations add.

## A release checklist

- [x] Migration generated in development and reviewed in the pull request
- [x] CI: `check`, `lint --strict`, `verify`
- [x] Backup taken before the production run
- [x] `migrax migrate` (or startup migration) as part of the release
- [x] `migrax status` and `migrax drift` afterwards
