# Databases

| Database | Dialect name | Notes |
|---|---|---|
| PostgreSQL | `postgresql` | Transactional DDL; `generate --safe` for non-blocking indexes and constraints |
| MySQL 8 | `mysql` | DDL commits immediately; sequences are `next_val` tables |
| MariaDB | `mariadb` | Like MySQL |
| SQL Server | `sqlserver` | Lock through `sp_getapplock` |
| Oracle 12c and newer | `oracle` | Lock needs `EXECUTE` on `DBMS_LOCK` |
| H2 | `h2` | For development and tests; `verify` uses it in memory |

The dialect comes from the JDBC URL. When there is no database URL (for example in a CI step that
only plans), pass `--dialect`.

The JDBC driver comes from your project's dependencies, so Migrax always uses the driver
version your application uses.

## Column types

When Hibernate is in the project, column types are whatever **your** Hibernate version chooses
for your database. The same entity can map differently: an `Instant` is a
`timestamp(6) with time zone` with Hibernate 6 on PostgreSQL and a `timestamp` with Hibernate 5;
a `boolean` is `bit` on MySQL. Migrax records the exact type, so `generate`, `verify` and
`drift` all agree with the application.

## Transactions

Each migration runs in a transaction with its history record. On PostgreSQL, SQL Server and H2,
a failed migration leaves nothing behind. MySQL, MariaDB and Oracle commit DDL statements
immediately, so a migration that fails halfway leaves the earlier statements applied; see
[Rollbacks and recovery](../guides/rollbacks-and-recovery.md#when-a-migration-fails).

Statements that cannot run in a transaction, such as PostgreSQL's `CREATE INDEX CONCURRENTLY`,
need `-- migrax:no-transaction` at the top of the migration.

## Testing against real engines

The Migrax build includes a suite that runs migrations and Hibernate validation against real
MySQL, MariaDB, PostgreSQL, SQL Server and Oracle containers:

```bash
mvn verify -Pdatabase-integration    # needs Docker
```

Run it with your production engine version before you rely on a new database version.
