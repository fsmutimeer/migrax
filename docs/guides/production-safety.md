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
  Fix: Deploy code that no longer uses it first, then drop it in a later release (expand/contract).
1 file(s) checked, 1 finding(s).
```

See all [lint rules](../reference/lint-rules.md). To accept a finding deliberately, put
`-- migrax:lint-ignore MX007` on the line before the statement.

### Non-blocking changes on PostgreSQL

`migrax generate --safe` moves index and constraint creation on existing tables into a second,
non-transactional migration that uses `CREATE INDEX CONCURRENTLY`, `NOT VALID` and
`VALIDATE CONSTRAINT`, so writes keep flowing while they build.

## Verify before you ship

```console
$ migrax verify
Applied 7 migration(s) to a fresh postgresql database.
Schema matches the entities (Hibernate 6.6.13.Final schema validation).
Rollback scripts: ok (7 rolled back and re-applied).
Verified.
```

`verify`:

1. starts a throwaway database: in-memory H2, a Docker container of your engine, or the empty
   database you pass with `--url`;
2. applies every migration from scratch;
3. starts **your** Hibernate version with schema validation against the result (without
   Hibernate, it compares the schema with the entities);
4. rolls the migrations back and applies them again, proving the rollback scripts work. When
   older migrations have no rollback script, it checks the newest ones that do.

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

## A release checklist

- [x] Migration generated in development and reviewed in the pull request
- [x] CI: `check`, `lint --strict`, `verify`
- [x] Backup taken before the production run
- [x] `migrax migrate` (or startup migration) as part of the release
- [x] `migrax status` and `migrax drift` afterwards
