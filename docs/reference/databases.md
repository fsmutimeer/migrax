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
| SQLite 3.35 and newer | `sqlite` | Changes SQLite can't make in place rebuild the table ([details](#sqlite)) |

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

## SQLite

Use the `org.xerial:sqlite-jdbc` driver and a `jdbc:sqlite:path/to/app.db` URL. With Hibernate
6 or newer, add `org.hibernate.orm:hibernate-community-dialects` (the same version as
Hibernate): it has Hibernate's SQLite dialect, which Migrax uses to read the entity mapping.

SQLite adds, renames and drops columns, renames tables and creates indexes in place. Any other
change to an existing table (a column's type, length or `NOT NULL`, a primary key, a foreign
key, a unique constraint) is made by **rebuilding the table**, as SQLite's documentation
describes: Migrax writes a migration that creates the new table, copies the rows, drops the old
table, renames the new one and recreates its indexes. A rebuilt table's foreign keys are
checked before the migration commits; if a row breaks one, the migration fails and nothing
changes.

Good to know:

- An identity id is declared `integer`: in SQLite only an `integer` primary key fills itself.
  Other whole-number columns are `int` or `bigint`; SQLite stores them all the same way.
- SQLite has no sequences: Hibernate keeps the next value in a one-row table, which Migrax
  creates with its starting value.
- The migration lock is a file next to the database (`app.db.migrax-lock`). While Migrax
  migrates, its own connection has foreign key enforcement off, because SQLite can only rebuild
  a table that others refer to that way; it is switched back on afterwards.
- `verify` runs on a temporary SQLite file, no Docker needed. It compares the schema with
  Migrax's own check instead of Hibernate's validation, because Hibernate's validator rejects
  the `integer` id columns that its own SQLite dialect creates.
- SQLite has no schemas: `--schema` and `--schemas` don't apply.
- Hibernate can't load `@Lob` fields through the SQLite driver; prefer plain `String` and
  `byte[]` fields.

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
Server, H2 and SQLite, a failed migration leaves nothing behind. MySQL, MariaDB and Oracle commit DDL statements
immediately, so a migration that fails halfway leaves the earlier statements applied; see
[Rollbacks and recovery](../guides/rollbacks-and-recovery.md#when-a-migration-fails).

Statements that cannot run in a transaction, such as PostgreSQL's `CREATE INDEX CONCURRENTLY`,
need `-- migrax:no-transaction` at the top of the migration.

## Testing against real engines

The Migrax build includes a suite that runs migrations and Hibernate validation against real
MySQL, MariaDB, PostgreSQL, SQL Server, Oracle and CockroachDB containers, and on SQLite:

```bash
mvn verify -Pdatabase-integration    # needs Docker
```

Run it with your production engine version before you rely on a new database version.
