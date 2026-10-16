# Databases

| Database | Dialect name | Notes |
|---|---|---|
| PostgreSQL | `postgresql` | Transactional DDL; `generate --safe` for non-blocking indexes and constraints |
| CockroachDB | `cockroachdb` | Through the PostgreSQL driver; recognized from the server ([details](#cockroachdb)) |
| YugabyteDB 2024.2 and newer | `yugabytedb` | PostgreSQL's SQL; DDL commits immediately ([details](#yugabytedb)) |
| MySQL 8 | `mysql` | DDL commits immediately; sequences are `next_val` tables |
| MariaDB | `mariadb` | Like MySQL |
| TiDB 7.5 and newer | `tidb` | Through the MySQL driver; recognized from the server ([details](#tidb)) |
| SQL Server | `sqlserver` | Lock through `sp_getapplock` |
| Oracle 12c and newer | `oracle` | Lock needs `EXECUTE` on `DBMS_LOCK`; booleans follow the version ([details](#oracle)) |
| H2 | `h2` | For development and tests; `verify` uses it in memory |
| HSQLDB (HyperSQL) 2.7 and newer | `hsqldb` | In memory, files or server; `verify` uses it in memory ([details](#hsqldb)) |
| Apache Derby 10.16 and newer | `derby` | Embedded or network server; `verify` uses it in memory ([details](#derby)) |
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

CockroachDB widens a column in place (a longer `VARCHAR`, `INT4` to `INT8`). Any other type
change needs its experimental setting and can't run inside a transaction, so a migration with
one starts with `-- migrax:no-transaction` and
`SET enable_experimental_alter_column_type_general = true`. Its statements then commit one by
one: if one fails, the earlier ones stay applied.

## YugabyteDB

YugabyteDB (its YSQL API) runs PostgreSQL's own SQL layer, so Migrax writes the same SQL as for
PostgreSQL. Use the PostgreSQL driver with a `jdbc:postgresql://host:5433/database` URL, or
YugabyteDB's own driver (`jdbc:yugabytedb://`). With the PostgreSQL driver, Migrax asks the
server which database it is and the first `generate` records `yugabytedb` in the snapshot.
Without a reachable database, pass `--dialect yugabytedb`.

What is different from PostgreSQL:

- DDL commits immediately, as on MySQL: a migration that fails halfway leaves its earlier
  statements applied.
- The migration lock is PostgreSQL's advisory lock where YugabyteDB has it (2025.1 and newer).
  On older versions it is a row in the `migrax_lock` table, as on CockroachDB.
- There are no large objects, which Hibernate's PostgreSQL mapping uses for `@Lob`, so saving a
  `@Lob` value fails on YugabyteDB. Leave out `@Lob`: a `byte[]` field is then `bytea`, and a
  long `String` can be `@Column(columnDefinition = "text")`.
- `generate --safe` isn't needed: YugabyteDB builds indexes without blocking writes.
- `verify` starts a throwaway `yugabytedb/yugabyte` container in Docker (about a minute).

Zero-downtime changes (`generate --zero-downtime`) work as on PostgreSQL, with triggers for
renamed columns and views for renamed tables.

## TiDB

TiDB speaks MySQL's protocol, so the project uses the MySQL driver and a
`jdbc:mysql://host:4000/database` URL. Migrax asks the server which database it is, and the
first `generate` records `tidb` in the snapshot. Without a reachable database, pass
`--dialect tidb`. Migrax writes the SQL it writes for MySQL, as Hibernate's MySQL dialect maps
the entities for TiDB.

What is different from MySQL:

- Check constraints (`@Check`, and enum checks with `migrax.enum-checks`) are only kept with
  `SET GLOBAL tidb_enable_check_constraint = ON`; otherwise TiDB ignores them. `migrax doctor` warns when the entities have checks and the setting is off.
- `--keep-data` backs up dropped columns with `CREATE TABLE ... LIKE` and `INSERT ... SELECT`,
  because TiDB has no `CREATE TABLE ... AS SELECT`.
- There are no triggers and no writable views, so `generate --zero-downtime` can't split
  column or table renames on TiDB. Drops, NOT NULL and type changes split as usual.
- There are no stored procedures.
- `verify` starts a throwaway `pingcap/tidb` container in Docker.

## HSQLDB

HyperSQL is recognized from `jdbc:hsqldb:` URLs, in memory (`jdbc:hsqldb:mem:`), in files or as
a server. `verify` replays the migrations on a new in-memory database, without Docker.

What is different from H2:

- DDL commits immediately, as on MySQL: a migration that fails halfway leaves its earlier
  statements applied.
- The migration lock is a row in the `migrax_lock` table, so two processes that share a file
  or server database don't migrate at once. If a Migrax process is killed while migrating, the
  row stays and the next `migrate` says how to remove it.
- Sequence ids have no column default (HyperSQL has no sequence default); Hibernate takes the
  next value from the sequence itself.
- Hibernate stores `Instant` as `timestamp` on HyperSQL. Projects read by annotation scanning
  (without a JPA provider) get `timestamp with time zone` for it, so prefer reading the
  entities through Hibernate there.
- `generate --zero-downtime` keeps a renamed table's old name as a view (HyperSQL's simple
  views accept writes). Column renames can't be split: Migrax writes no triggers for HyperSQL.

## Derby

Apache Derby is recognized from `jdbc:derby:` URLs, embedded (`jdbc:derby:memory:` or a folder)
or through its network server. `verify` replays the migrations on a new in-memory database,
without Docker. Derby 10.16 runs on Java 17; 10.17 needs Java 21.

What is different:

- Derby keeps no comments: `@Comment` texts are left out of the migrations.
- Derby changes a column's type in place only to lengthen a `VARCHAR`, `CLOB` or `BLOB`. Any
  other type change rebuilds the column: a new column of the new type, the values copied, the
  old column dropped and the new one renamed, then NOT NULL and the indexes, keys and checks
  on it come back. The column moves to the end of the table. A primary key column can't be
  rebuilt this way.
- Sequence ids have no column default (Derby has none); Hibernate takes the next value from the
  sequence itself. Decimals have at most 31 digits.
- `UUID` fields are `char(16) for bit data`, as Hibernate stores them on Derby.
- The migration lock is a row in the `migrax_lock` table, so processes that share a network
  server don't migrate at once.
- Stored procedures are Java methods in Derby, so migrations can't define them in SQL.
- With Hibernate 7, Derby's Hibernate dialect is in `hibernate-community-dialects`, which the
  project then needs (Hibernate 6 has it built in).
- `generate --zero-downtime` can't split renames on Derby (no triggers or writable views).

## Oracle

Migrax's migration lock uses `DBMS_LOCK`, so the account that runs the migrations needs
`GRANT EXECUTE ON SYS.DBMS_LOCK TO <user>`.

Oracle 23ai has a `boolean` type, and Hibernate uses it there, while on older versions a
`boolean` field is a `number(1,0)` column. Migrax asks the database for its version and writes
what your Hibernate expects on it. The first `generate` records the version in the snapshot
(`"server": "23.26"`), so commands without a database connection, such as `verify` in CI,
write the same types. `verify` starts Oracle Free 23ai for a 23ai project and Oracle XE 21
otherwise, in Docker.

Moving an existing database to 23ai doesn't change its columns: `number(1,0)` columns stay,
and new ones are `boolean`. If your application validates its schema with Hibernate, change
the old columns in a migration of your own.

## SQLite

Use the `org.xerial:sqlite-jdbc` driver and a `jdbc:sqlite:path/to/app.db` URL. With Hibernate
6 or newer, add `org.hibernate.orm:hibernate-community-dialects` (the same version as
Hibernate): it has Hibernate's SQLite dialect, which Migrax uses to read the entity mapping.

SQLite adds, renames and drops columns, renames tables and creates indexes in place. Any other
change to an existing table (a column's type, length or `NOT NULL`, a primary key, a foreign
key, a unique or check constraint) is made by **rebuilding the table**, as SQLite's documentation
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
- SQLite keeps no table or column comments, so `@Comment` is left out of its migrations.
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
Server, H2, Derby and SQLite, a failed migration leaves nothing behind. MySQL, MariaDB, TiDB, Oracle,
YugabyteDB and HSQLDB commit DDL statements immediately, so a migration that fails halfway leaves the earlier statements applied; see
[Rollbacks and recovery](../guides/rollbacks-and-recovery.md#when-a-migration-fails).

Statements that cannot run in a transaction, such as PostgreSQL's `CREATE INDEX CONCURRENTLY`,
need `-- migrax:no-transaction` at the top of the migration.

## Testing against real engines

The Migrax build includes a suite that runs migrations and Hibernate validation against real
MySQL, MariaDB, TiDB (7.5 and 8.5), PostgreSQL, SQL Server, Oracle (XE 21 and Free 23ai),
CockroachDB and YugabyteDB (2024.2 and 2026.1) containers, and on HSQLDB, Derby and SQLite:

```bash
mvn verify -Pdatabase-integration    # needs Docker
```

Run it with your production engine version before you rely on a new database version.
