# Databases

| Database | Dialect name | Notes |
|---|---|---|
| PostgreSQL | `postgresql` | Transactional DDL; `generate --safe` for non-blocking indexes and constraints |
| CockroachDB | `cockroachdb` | Through the PostgreSQL driver; recognized from the server ([details](#cockroachdb)) |
| MySQL 8 | `mysql` | DDL commits immediately; sequences are `next_val` tables |
| MariaDB | `mariadb` | Like MySQL |
| SQL Server | `sqlserver` | Lock through `sp_getapplock` |
| Oracle 12c and newer | `oracle` | Lock needs `EXECUTE` on `DBMS_LOCK` |
| H2 | `h2` | For development and tests; `verify` uses it in memory |

The dialect comes from the JDBC URL. When there is no database URL (for example in a CI step that
only plans), pass `--dialect`.

## CockroachDB

CockroachDB speaks PostgreSQL's protocol, so the project uses the PostgreSQL driver and a
`jdbc:postgresql://host:26257/database` URL. Migrax asks the server which database it is, and
the first `generate` records `cockroachdb` in the snapshot, so later commands don't have to ask.
Without a reachable database, pass `--dialect cockroachdb`.

What is different from PostgreSQL:

- 32-bit integers are written as `int4`, because CockroachDB's `integer` has 64 bits.
- `@Lob` values are `text` and `bytea`: CockroachDB has no large objects.
- Indexes and unique constraints are removed with `DROP INDEX table@name`.
- The migration lock is a row in the `migrax_lock` table, because CockroachDB has no session
  locks. If a Migrax process is killed while migrating, the row stays and the next `migrate`
  says how to remove it.
- `verify` starts a throwaway `cockroachdb/cockroach` container in Docker.

CockroachDB runs schema changes in the background and some column type changes need its
experimental settings; review migrations that change column types before you apply them.

The JDBC driver comes from your project's dependencies, so Migrax always uses the driver
version your application uses.

## Column types

When Hibernate is in the project, column types are whatever **your** Hibernate version chooses
for your database. The same entity can map differently: an `Instant` is a
`timestamp(6) with time zone` with Hibernate 6 on PostgreSQL and a `timestamp` with Hibernate 5;
a `boolean` is `bit` on MySQL. Migrax records the exact type, so `generate`, `verify` and
`drift` all agree with the application.

## Transactions

Each migration runs in a transaction with its history record. On PostgreSQL, CockroachDB, SQL
Server and H2, a failed migration leaves nothing behind. MySQL, MariaDB and Oracle commit DDL statements
immediately, so a migration that fails halfway leaves the earlier statements applied; see
[Rollbacks and recovery](../guides/rollbacks-and-recovery.md#when-a-migration-fails).

Statements that cannot run in a transaction, such as PostgreSQL's `CREATE INDEX CONCURRENTLY`,
need `-- migrax:no-transaction` at the top of the migration.

## Testing against real engines

The Migrax build includes a suite that runs migrations and Hibernate validation against real
MySQL, MariaDB, PostgreSQL, SQL Server, Oracle and CockroachDB containers:

```bash
mvn verify -Pdatabase-integration    # needs Docker
```

Run it with your production engine version before you rely on a new database version.
