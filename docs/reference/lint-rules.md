---
seo_title: "SQL migration lint rules"
description: "The lint rules Migrax checks migrations against: table locks, NOT NULL columns, type changes, drops and other risky SQL, each with a safer alternative."
---
# Lint rules

Migrax lints every migration it generates, and `migrax lint` checks pending migrations (or
`--all`, or named files). Each finding explains the risk and a safer alternative. For MX001,
MX004, MX005, MX006, MX013, MX014 and MX020 it also writes the safer SQL with the statement's
own table, column and constraint names, ready to copy (`Safe SQL` in the text output, `safeSql`
in `--json`):

```console
$ migrax lint --dialect postgresql
db/migration/0007_total.sql, statement 1: warning MX004
  ALTER TABLE orders ALTER COLUMN total SET NOT NULL
  SET NOT NULL scans all of orders while holding an exclusive lock.
  Fix: First ADD CONSTRAINT ... CHECK (column IS NOT NULL) NOT VALID, then VALIDATE CONSTRAINT in a later migration; PostgreSQL 12+ then sets NOT NULL without a scan.
  Safe SQL:
    ALTER TABLE orders ADD CONSTRAINT orders_total_not_null CHECK (total IS NOT NULL) NOT VALID;
    -- In a later migration:
    ALTER TABLE orders VALIDATE CONSTRAINT orders_total_not_null;
    -- migrax:lint-ignore MX004
    ALTER TABLE orders ALTER COLUMN total SET NOT NULL;
    ALTER TABLE orders DROP CONSTRAINT orders_total_not_null;
```

**Severity:** errors make `lint` exit with 1. Warnings do too with `--strict`. Info findings are
advice.

To accept a finding on purpose, add a directive on the line before the statement:

```sql
-- migrax:lint-ignore MX007
ALTER TABLE customers DROP COLUMN legacy_code;
```

| Code | Severity | Database | Finds | Safer alternative |
|---|---|---|---|---|
| MX001 | warning | PostgreSQL | `CREATE INDEX` on an existing table without `CONCURRENTLY` blocks writes until it finishes | `CREATE INDEX CONCURRENTLY` in its own `-- migrax:no-transaction` migration (`generate --safe` does this) |
| MX002 | warning | all | `NOT NULL` column without a default on an existing table fails when the table has rows | Add a `DEFAULT`, or add it nullable, backfill, then make it `NOT NULL` |
| MX003 | warning | all | Column type change can rewrite or lock the table, and fails or truncates when the type gets narrower | Test on a production-size copy; for large tables add a new column, backfill, switch, drop |
| MX004 | warning | PostgreSQL | `SET NOT NULL` scans the table under an exclusive lock | `CHECK (col IS NOT NULL) NOT VALID`, then `VALIDATE CONSTRAINT` later |
| MX005 | warning | PostgreSQL | Adding a foreign key checks every row while blocking writes to both tables | Add it `NOT VALID`, validate in a later migration (`generate --safe`) |
| MX006 | warning | PostgreSQL | Adding a unique constraint builds its index while blocking writes | `CREATE UNIQUE INDEX CONCURRENTLY`, then `ADD CONSTRAINT ... UNIQUE USING INDEX` (`generate --safe`) |
| MX007 | warning | all | Dropping a table or column deletes data and breaks instances still using it | Deploy code that no longer uses it, then drop in a later release (`generate --zero-downtime`) |
| MX008 | warning | all | Renaming a table, column, view, materialized view, sequence or schema breaks instances still running the old code | Rename in steps (add, write both, switch reads, remove; `generate --zero-downtime`) or use a maintenance window |
| MX009 | warning | all | `UPDATE` or `DELETE` without `WHERE` changes every row in one transaction | Add a `WHERE`, or update large tables in batches |
| MX010 | warning | all | `TRUNCATE` deletes all rows and cannot be undone by a rollback script | Make sure it is intended and backed up |
| MX011 | warning | PostgreSQL | A volatile default rewrites every row under an exclusive lock | Add the column without a default, set the default separately, backfill in batches |
| MX012 | error | PostgreSQL | A statement that cannot run inside a transaction (`CREATE INDEX CONCURRENTLY`, `VACUUM`, `DETACH PARTITION ... CONCURRENTLY`, ...) | Add `-- migrax:no-transaction` to the migration |
| MX013 | warning | PostgreSQL | Adding a primary key builds an index while blocking writes | `CREATE UNIQUE INDEX CONCURRENTLY`, then `ADD CONSTRAINT ... PRIMARY KEY USING INDEX` |
| MX014 | warning | PostgreSQL | Adding a check constraint scans the table under an exclusive lock | Add it `NOT VALID`, then `VALIDATE CONSTRAINT` later |
| MX015 | info | SQL Server, Oracle | Index builds lock the table unless they run online | `WITH (ONLINE = ON)` (SQL Server Enterprise) or `ONLINE` (Oracle Enterprise) |
| MX016 | warning | all | A procedure, function, trigger, package or block whose body has an unmatched `BEGIN`, so it takes in the statements after it | Give every `BEGIN` its `END`, or end the body with `GO`, a `DELIMITER` line or a `/` line |
| MX017 | warning | SQL Server with `migrax.schemas` | A table without its schema in front lands in the login's default schema, not in each tenant's | Write `${migrax.schema}.<table>` ([several schemas](../guides/advanced-migrations.md#sql-server)) |
| MX018 | warning | all | `BEGIN`, `START TRANSACTION`, `COMMIT` or `ROLLBACK` (and `END` on PostgreSQL and SQLite) written in a migration. Migrax already runs it in one transaction: after a `COMMIT` a failure can leave the statements before it applied and the migration not recorded; a `ROLLBACK` undoes them while the migration is recorded as applied | Remove it; for statements that can't run in a transaction, add `-- migrax:no-transaction`. A `BEGIN`/`COMMIT` pair needs `lint-ignore` on both, or `lint-ignore-file` |
| MX020 | warning | PostgreSQL | `REINDEX`, `DROP INDEX`, `REFRESH MATERIALIZED VIEW` or `DETACH PARTITION` without `CONCURRENTLY` blocks queries on the table or view until it finishes | The `CONCURRENTLY` form; all but `REFRESH` in a `-- migrax:no-transaction` migration. For `DROP INDEX` on a small, quiet table the lock is short: `lint-ignore MX020` |
| MX021 | warning | PostgreSQL | `VACUUM FULL`, `CLUSTER`, `SET TABLESPACE`, `SET LOGGED` or `SET UNLOGGED` rewrites the whole table under an exclusive lock | A maintenance window, or an online rebuild with an extension such as pg_repack |
| MX000 | error | all | The migration could not be parsed | Fix the SQL |
