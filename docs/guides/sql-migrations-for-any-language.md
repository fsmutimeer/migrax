---
seo_title: "SQL migrations for any language, step by step"
description: "Apply hand-written SQL migrations in any project, such as Python, Node.js, Go or PHP, with history, checksums, locking and rollbacks."
---
# SQL migrations for any language

Migrax applies SQL migrations you write yourself, in any project: Python, Node.js, Go, .NET,
PHP, Ruby, or a Java application without JPA. It needs only a folder of SQL files and a
database connection; it doesn't read your application's code.

What you keep: numbered migrations applied once and in order, repeatable migrations, the
history table, checksums of applied files, the migration lock, rollback scripts, `status`,
`lint`, `repair` and `clean`. Migrax can also write the migrations for you: keep your tables
as they should be in one SQL file, and `generate`, `check` and `verify` work from it as they
do from Java entities. See [Let Migrax write the migrations](#let-migrax-write-the-migrations).

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

The container image already has the drivers for PostgreSQL (also for CockroachDB and
YugabyteDB), MariaDB, SQL Server,
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
    ghcr.io/fsmutimeer/migrax:0.16.0 migrate
```

## A database that already has tables

If the database was built before you started using Migrax, write migrations that describe
what it already has (or one migration for all of it), then tell Migrax they are applied:

```console
$ migrax baseline 0002 --yes
Recording 2 migration(s) as applied without running them:
  0001_create_customer.sql
  0002_add_email.sql
Recorded. Run 'migrax migrate' to apply the migrations after 0002_add_email.sql.

$ migrax migrate
Applying migration '0003_add_phone.sql'...
Successfully applied migration '0003_add_phone.sql'.
Applied 1 migration(s); 3 total in db/migrations.
```

`baseline` only works on a database without migration history, and `--dry-run` shows what it
would record. Run it once per database (each environment), then use `migrate` as usual.

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

## Let Migrax write the migrations

New in Migrax 0.17.0.

Instead of writing each migration by hand, keep one SQL file with your tables as they should
be, and let `migrax generate` write the migration and its rollback script whenever the file
changes:

```sql title="db/schema.sql"
-- The tables as they should be. Migrax writes the migrations.
CREATE TABLE customer (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  email VARCHAR(150) NOT NULL UNIQUE,
  name VARCHAR(100) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE orders (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  customer_id BIGINT NOT NULL REFERENCES customer (id),
  total NUMERIC(12, 2) NOT NULL CHECK (total >= 0),
  status VARCHAR(20) NOT NULL DEFAULT 'new'
);

CREATE INDEX orders_customer ON orders (customer_id);
```

Tell Migrax where the file is, next to the other settings:

```bash
export MIGRAX_SCHEMA_FILE=db/schema.sql
```

(`--schema-file db/schema.sql` on the command line, or `migrax.schema-file` in a Java
project's application configuration.)

To read the file, Migrax runs it on a throwaway database of the same kind and reads the tables
back. For PostgreSQL, MySQL and the other server databases that is a Docker container, so
Docker must be running; H2, SQLite, Derby and HSQLDB need no Docker.

```console
$ migrax generate
No snapshot yet: using the current database schema as the baseline.
Created db/migrations/0001_initial.sql:
  - create table customer
  - create table orders
  - add index orders_customer on orders
  - add foreign key orders_customer_id_fkey on orders
Rollback script written to db/migrations/rollback.
Review the SQL, then run 'migrax migrate'.
```

```sql title="db/migrations/0001_initial.sql"
CREATE TABLE customer (id bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL, email varchar(150) NOT NULL, name varchar(100) NOT NULL, created_at timestamp(6) with time zone DEFAULT now() NOT NULL, CONSTRAINT customer_pkey PRIMARY KEY (id), CONSTRAINT uk_customer_email UNIQUE (email));
CREATE TABLE orders (id bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL, customer_id bigint NOT NULL, total numeric(12,2) NOT NULL, status varchar(20) DEFAULT 'new' NOT NULL, CONSTRAINT orders_pkey PRIMARY KEY (id), CONSTRAINT orders_total_check CHECK (((total >= (0)::numeric))));
CREATE INDEX orders_customer ON orders (customer_id);
ALTER TABLE orders ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES customer (id);
```

Apply it with `migrax migrate` as usual. Commit the migration together with `.migrax/`:
`snapshot.json` records the schema the migrations produce, and `schema-model.json` records
what Migrax read from your file.

### Changing the schema

Edit the file, for example rename `name` to `full_name` and add a `phone` column:

```sql
  full_name VARCHAR(100) NOT NULL,
  phone VARCHAR(30),
```

`migrax check` lists what has no migration yet, and exits with code 2:

```console
$ migrax check
3 change(s) in db/schema.sql have no migration:
  - drop column customer.name
  - add column customer.full_name
  - add column customer.phone
Run 'migrax generate' and commit the result.
```

A dropped column next to a new one may be a rename. In a terminal, `generate` asks; in a script
it stops before dropping anything and tells you how to keep the data:

```console
$ migrax generate
Possible rename: customer.name to customer.full_name. To keep the data, rerun with --rename customer.name=full_name.
...
error[MXE108]: 1 destructive change(s) detected (marked [DESTRUCTIVE] above).
$ migrax generate --rename customer.name=full_name
Created db/migrations/0002_rename_customer_name_add_customer_phone.sql:
  - rename column customer.name to full_name
  - add column customer.phone
Rollback script written to db/migrations/rollback.
```

```sql title="db/migrations/0002_rename_customer_name_add_customer_phone.sql"
ALTER TABLE customer RENAME COLUMN name TO full_name;
ALTER TABLE customer ADD COLUMN phone varchar(30);
```

`migrax verify` applies all migrations to an empty throwaway database, compares the result
with your file, and tests every rollback script:

```console
$ migrax verify
Applied 2 migration(s) to a fresh postgresql database.
Schema matches the schema file (structural comparison).
Rollback scripts: ok (2 rolled back and re-applied).
Verified.
```

Everything else works as for Java entities: lint warnings, `--zero-downtime`, `--keep-data`,
`plan`, and [several databases](several-databases.md) (each with its own
`migrax.databases.<name>.schema-file`).

### In CI without Docker

While `db/schema.sql` is unchanged, Migrax reads it from the committed
`.migrax/schema-model.json` and starts no database, so `migrax check` runs anywhere, including
inside the [container image](containers-and-kubernetes.md). When someone changes the file but
forgets to run `generate`, the saved model no longer matches and `check` needs Docker to read
the file; without Docker it stops with error `MXE115`. Either way the change doesn't slip
through. To read the file without Docker, give Migrax an empty database of its own with
`--scratch-url` (or `MIGRAX_SCRATCH_URL`); Migrax empties it again afterwards. It connects
with the same user and password as your database.

### What the file can contain

Tables with their columns, defaults, primary and foreign keys, unique constraints, checks,
indexes and comments. Name constraints if you like. An unnamed one keeps the database's name
when that name is predictable, such as PostgreSQL's `orders_customer_id_fkey`; random ones,
such as Oracle's `SYS_C...` or SQL Server's `FK__...`, get a stable name from Migrax.

Not read from the file:

- **Views, functions, procedures and triggers.** Keep them in
  [repeatable migrations](advanced-migrations.md#repeatable-migrations).
- **Sequences** (`CREATE SEQUENCE`; Migrax warns). Identity columns work; for a sequence of
  your own, write its migration with `migrax new`.
- **Tables in another schema** (`CREATE TABLE billing.invoice`; Migrax warns). Migrax reads the
  database's default schema.
- **`ON UPDATE CURRENT_TIMESTAMP`** on MySQL and MariaDB (Migrax warns). The column and its
  default are read; add the `ON UPDATE` part to the generated migration yourself.

A statement that fails on the throwaway database stops Migrax with the file name, the statement
number and its line (error `MXE115`).

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
