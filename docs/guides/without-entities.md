# Using Migrax without Java entities

Migrax can apply migrations you write yourself, in any project: Python, Node.js, Go, PHP, or a
Java application without JPA. It needs only a folder of SQL files and a database connection; it
doesn't read your application's code.

What you keep: numbered migrations applied once and in order, repeatable migrations, the
history table, checksums of applied files, the migration lock, rollback scripts, `status`,
`lint`, `repair` and `clean`. What you don't get: `generate`, `check`, `drift` and `verify`
compare the database with Java entities, so without entities there's nothing for them to read.

## What you need

- **Migrax.** Either the command line tool, which needs Java 17 or newer on the machine that
  runs it ([Installation](../getting-started/installation.md)), or the
  [container image](containers-and-kubernetes.md), which needs only Docker.
- **The JDBC driver of your database.** Migrax connects through Java's database drivers, not
  through your application's (psycopg, pg, mysql2 and so on). See [Drivers](#drivers) below.
- **The connection as a JDBC URL**, for example `jdbc:postgresql://localhost:5432/shop`.

## A project layout

```
shop/
  package.json
  src/...
  db/
    migrations/
      0001_create_customer.sql
      0002_add_email.sql
      R__customer_view.sql
      rollback/
        0002_add_email.sql
```

The migration folder can be anywhere; tell Migrax where with `--locations filesystem:<folder>`
(relative to the folder you run Migrax in) or `MIGRAX_LOCATIONS`.

## Configuration

A project without Java has no application configuration for Migrax to read, so set the
connection with environment variables, for example in a `.env` file your shell or CI loads:

```bash
export MIGRAX_DATABASE_URL=jdbc:postgresql://localhost:5432/shop
export MIGRAX_DATABASE_USER=shop
export MIGRAX_DATABASE_PASSWORD=secret
export MIGRAX_LOCATIONS=filesystem:db/migrations
export MIGRAX_DRIVERS=drivers
```

The same settings exist as options (`--url`, `--user`, `--password`, `--password-file`,
`--locations`, `--classpath`); see [Configuration](../reference/configuration.md).

| Database | JDBC URL | Driver |
|---|---|---|
| PostgreSQL | `jdbc:postgresql://host:5432/shop` | `org.postgresql:postgresql` |
| MySQL | `jdbc:mysql://host:3306/shop` | `com.mysql:mysql-connector-j` |
| MariaDB | `jdbc:mariadb://host:3306/shop` | `org.mariadb.jdbc:mariadb-java-client` |
| SQLite | `jdbc:sqlite:path/to/app.db` | `org.xerial:sqlite-jdbc` |

Other databases: [Databases](../reference/databases.md).

## Drivers

Download the driver's `.jar` from Maven Central (search.maven.org, using the name in the table
above) and give it to Migrax in one of three ways:

- put it in a folder and set `MIGRAX_DRIVERS` to that folder;
- put it in the `drivers` folder next to the `lib` folder of your Migrax installation, so every
  project can use it;
- pass it with `--classpath path/to/driver.jar`.

Without a driver, Migrax stops with error `MXE106` ("No JDBC driver ... accepts URL").

The container image already has the drivers for PostgreSQL, CockroachDB, MariaDB, SQL Server,
SQLite and H2; for MySQL Connector/J and Oracle see
[Other drivers](containers-and-kubernetes.md#other-drivers).

## Writing migrations

```console
$ migrax new create_customer
Created db/migrations/0001_create_customer.sql and db/migrations/rollback/0001_create_customer.sql.
```

`new` creates the next numbered file and an empty rollback script. Write the SQL yourself:

```sql title="db/migrations/0001_create_customer.sql"
CREATE TABLE customer (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL);
```

You can also create the files by hand. Numbered files (`0001_name.sql`) and files named
`V1__name.sql` both work, also mixed in one folder; they run in version order. Files named
`R__<name>.sql` are repeatable: they run after the numbered ones and again whenever they change,
which suits views and functions. Callbacks and placeholders work as described in
[Advanced migrations](advanced-migrations.md).

Never edit a migration after it has been applied: Migrax keeps a checksum of every applied file
and stops when one changes. Write a new migration instead.

## Applying them

```console
$ migrax status
Migrations in db/migrations for jdbc:postgresql://localhost:5432/shop
  [ ] 0001_create_customer.sql  pending
  [ ] 0002_add_email.sql        pending
  [ ] R__customer_view.sql      pending
3 pending.

$ migrax migrate
Applying migration '0001_create_customer.sql'...
Successfully applied migration '0001_create_customer.sql'.
Applying migration '0002_add_email.sql'...
Successfully applied migration '0002_add_email.sql'.
Applying migration 'R__customer_view.sql'...
Successfully applied migration 'R__customer_view.sql'.
Applied 3 migration(s); 2 total in db/migrations.

$ migrax migrate
Nothing to migrate: all 2 migration(s) are applied.
```

`migrax lint` checks the files for risky statements before they run, and
`migrax migrate --dry-run` shows what would run without changing the database.

With the container image, mount the folder at `/migrations`:

```console
$ docker run --rm -v "$PWD/db/migrations:/migrations:ro" \
    -e MIGRAX_DATABASE_URL=jdbc:postgresql://db:5432/shop \
    -e MIGRAX_DATABASE_USER=shop -e MIGRAX_DATABASE_PASSWORD=secret \
    ghcr.io/fsmutimeer/migrax:0.4.0 migrate
```

## Rolling back

`migrax rollback --yes` runs the rollback script of the newest applied migration
(`rollback/<same file name>`); `--steps n` rolls back several. A migration without a rollback
script can't be rolled back. Migrax checks every script first, so nothing is rolled back when
one is missing:

```console
$ migrax rollback --steps 2 --yes
error[MXE001]: No rollback script for 0001_create_customer.sql.
Write one in db/migrations/rollback/<same file name>, then run rollback again.
```

More in [Rollbacks and recovery](rollbacks-and-recovery.md).

## `migrax doctor`

`doctor` checks the setup and says how to fix what fails. When the folder has migrations but no
`pom.xml`, `build.gradle` or entity package, it skips the Java checks and checks only the
migrations and the database:

```console
$ migrax doctor
  [ok]    Java 21.0.5
  [info]  No pom.xml, build.gradle or entity package: checking migrations and the database only
  [ok]    Migrations: 3 file(s) in db/migrations
  [ok]    Database URL: jdbc:postgresql://localhost:5432/shop
  [ok]    Connected: PostgreSQL 16.15 (Debian 16.15-1.pgdg13+2) (driver 42.7.9)
  [ok]    Locking: supported
  [info]  Pending migrations: 0

All checks passed.
```

Older versions of Migrax report the build tool, the entity package and the JPA API as failed
here; ignore those three lines, the migrations and database lines are the ones that matter.
