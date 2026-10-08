# Lint rules

Migrax lints every migration it generates, and `migrax lint` checks pending migrations (or
`--all`, or named files). Each finding explains the risk and a safer alternative.

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
| MX007 | warning | all | Dropping a table or column deletes data and breaks instances still using it | Deploy code that no longer uses it, then drop in a later release |
| MX008 | warning | all | Renaming breaks instances still running the old code | Rename in steps (add, write both, switch reads, remove) or use a maintenance window |
| MX009 | warning | all | `UPDATE` or `DELETE` without `WHERE` changes every row in one transaction | Add a `WHERE`, or update large tables in batches |
| MX010 | warning | all | `TRUNCATE` deletes all rows and cannot be undone by a rollback script | Make sure it is intended and backed up |
| MX011 | warning | PostgreSQL | A volatile default rewrites every row under an exclusive lock | Add the column without a default, set the default separately, backfill in batches |
| MX012 | error | PostgreSQL | A statement that cannot run inside a transaction (`CREATE INDEX CONCURRENTLY`, `VACUUM`, ...) | Add `-- migrax:no-transaction` to the migration |
| MX013 | warning | PostgreSQL | Adding a primary key builds an index while blocking writes | `CREATE UNIQUE INDEX CONCURRENTLY`, then `ADD CONSTRAINT ... PRIMARY KEY USING INDEX` |
| MX014 | warning | PostgreSQL | Adding a check constraint scans the table under an exclusive lock | Add it `NOT VALID`, then `VALIDATE CONSTRAINT` later |
| MX015 | info | SQL Server, Oracle | Index builds lock the table unless they run online | `WITH (ONLINE = ON)` (SQL Server Enterprise) or `ONLINE` (Oracle Enterprise) |
| MX000 | error | all | The migration could not be parsed | Fix the SQL |
