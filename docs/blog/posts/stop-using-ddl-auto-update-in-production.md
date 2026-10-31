---
date: 2026-10-31T11:30:00
slug: stop-using-ddl-auto-update-in-production
authors:
  - feroz
categories:
  - How-to
tags:
  - Spring Boot
  - Hibernate
  - PostgreSQL
  - baseline
description: >-
  What spring.jpa.hibernate.ddl-auto=update really does to a database with data in it, shown
  on a real run, and how to switch an existing database to reviewed migrations without
  starting over.
---

# Stop using `ddl-auto=update` in production

`spring.jpa.hibernate.ddl-auto=update` is the fastest way to get started: change an entity,
restart, and the table follows. It stays in the configuration long after the first demo, and
one day it runs against a database with real customers in it.

This article makes three ordinary entity changes with `update` switched on and shows what
Hibernate does to the database. Then it moves the same project, with its existing data, to
generated migrations that you review before they run.

<!-- more -->

!!! info "Tested with"

    Migrax 0.17.1, Spring Boot 3.5.7, Hibernate 6.6.33, PostgreSQL 17. Every output below was
    copied from a real run; log lines are shortened to the message.

## The starting point

A Spring Boot application with one entity:

```java
@Entity
@Table(name = "customers")
public class Customer {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(nullable = false, length = 100)
  private String name;
}
```

and the setting this article is about:

```properties
spring.jpa.hibernate.ddl-auto=update
```

On the first start Hibernate creates the table. Two customers sign up:

```text
 id |      email       |     name
----+------------------+--------------
  1 | ada@example.com  | Ada Lovelace
  2 | alan@example.com | Alan Turing
```

So far `update` has done exactly what it promises.

## Three ordinary changes

The next release changes the entity in three small ways:

- `name` becomes `fullName`,
- `email` gets longer (`length = 320`),
- every customer now needs a `country` (`nullable = false`, `"GB"` by default in Java).

```java
  @Column(nullable = false, unique = true, length = 320)
  private String email;

  @Column(nullable = false, length = 100)
  private String fullName;

  @Column(nullable = false, length = 2)
  private String country = "GB";
```

The application starts, and the next customer tries to sign up. With
`logging.level.org.hibernate.SQL=debug`, the log shows what `update` did:

```text
alter table if exists customers add column country varchar(2) not null
GenerationTarget encountered exception accepting command : Error executing DDL "alter table
  if exists customers add column country varchar(2) not null" via JDBC [ERROR: column
  "country" of relation "customers" contains null values]
alter table if exists customers alter column email set data type varchar(320)
alter table if exists customers add column full_name varchar(100) not null
GenerationTarget encountered exception accepting command : Error executing DDL "alter table
  if exists customers add column full_name varchar(100) not null" via JDBC [ERROR: column
  "full_name" of relation "customers" contains null values]
Started ShopApplication in 5.803 seconds (process running for 6.91)
insert into customers (country,email,full_name) values (?,?,?)
ERROR: column "country" of relation "customers" does not exist
Application run failed
```

Three things happened, and only one of them was what you meant:

1. **The rename isn't a rename.** Hibernate has no way to know that `full_name` is the old
   `name`. It tries to add a new, empty column next to it.
2. **Adding the required columns fails.** PostgreSQL can't add a `NOT NULL` column without a
   default to a table that already has rows. Hibernate logs a warning and carries on.
3. **The application starts anyway.** The failures are logged as warnings and startup
   carries on. The error only shows up when the first customer signs up.

Only the longer `email` worked.

## The quick fix makes it worse

The obvious way out is to drop `nullable = false` from the two new fields so Hibernate can add
them. That works, in the sense that the columns appear:

```text
alter table if exists customers add column country varchar(2)
alter table if exists customers add column full_name varchar(100)
Started ShopApplication in 6.557 seconds (process running for 7.569)
insert into customers (country,email,full_name) values (?,?,?)
ERROR: null value in column "name" of relation "customers" violates not-null constraint
Application run failed
```

The old `name` column is still there, still `NOT NULL`, and the entity no longer writes to it.
Every signup fails. The table now looks like this:

```text
 id |      email       |     name     | country | full_name
----+------------------+--------------+---------+-----------
  1 | ada@example.com  | Ada Lovelace |         |
  2 | alan@example.com | Alan Turing  |         |
```

The existing customers have no `full_name` and no `country`, so the application shows them
without a name. Nothing recorded what changed or when, and there is no way to undo it except
by hand.

None of this is a bug in Hibernate. `update` only ever adds: it never renames, never drops,
never moves data, and doesn't stop the application when a statement fails. That is a safe
choice for a tool that runs on every start without asking anyone, and it is the reason it
shouldn't touch a database whose data matters.

## Switching to migrations

The same change, done with Migrax. The database goes back to where it was before the release:
two customers and a `name` column. The entity is back at version 1 too, because the switch is
easiest between releases.

### 1. Let `doctor` find the setting

```console
$ migrax doctor
...
  [warn]  Hibernate will change your database schema by itself
          Found:  spring.jpa.hibernate.ddl-auto = update
                  in src/main/resources/application.properties, line 4

          Why:    Hibernate adds tables and columns itself when the application starts. Migrations
                  then fail with "already exists", and the database drifts from what your migrations
                  describe, so other environments end up different.

          Fix:    change   spring.jpa.hibernate.ddl-auto=update
                  to       spring.jpa.hibernate.ddl-auto=none
                  (or validate: Hibernate then checks the schema at startup without changing it)
  [info]  Snapshot: none yet; the first generate uses the database as baseline
  [ok]    Database URL: jdbc:postgresql://localhost:5432/shop
  [ok]    Connected: PostgreSQL 17.11 (Debian 17.11-1.pgdg13+2) (driver 42.7.8)
...
All checks passed, with 1 warning(s) above.
```

Migrax reads the database settings from `spring.datasource.*`, so there is nothing else to
configure. Change the line to `validate`:

```properties
spring.jpa.hibernate.ddl-auto=validate
```

With `validate`, Hibernate still checks at startup that every entity matches a table and
column, and refuses to start if one doesn't. It just never changes anything.

### 2. Record what the database already has

The first `generate` has nothing to compare with yet, so it uses the live database:

```console
$ migrax generate
No snapshot yet: using the current database schema as the baseline.
No changes detected.
Created src/main/resources/db/migration/0001_baseline.sql with the 1 table(s) the database already has.
  It is recorded as applied in this database without running it; empty databases are created from it.
Saved the entity schema as the baseline snapshot: .migrax/snapshot.json
```

`0001_baseline.sql` describes the table as it is:

```sql
-- Generated by Migrax 0.17.1 for postgresql
-- Baseline: the tables the database already had before its first migration.
-- That database records it as applied without running it; empty databases are created from it.

CREATE TABLE customers (id bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL, email varchar(255) NOT NULL, name varchar(100) NOT NULL, CONSTRAINT customers_pkey PRIMARY KEY (id), CONSTRAINT uk_customers_email UNIQUE (email));
```

This database won't run it, because the table is already there. A new laptop, a test database
or `migrax verify` starts empty and builds the table from it. "No changes detected" means the
entities and the database agree, so nothing is lost by switching. If `update` had already left
old columns behind, they would show up here as changes to review.

Commit the migration folder and `.migrax/snapshot.json`.

### 3. Make the change again

Now the same three changes to `Customer`. This time `generate` writes them down instead of
running them:

```console
$ migrax generate --rename customers.name=full_name
warning: Adding NOT NULL column 'customers.country' without a default fails if the table has rows. Add a default in the SQL, or add it as nullable, backfill, then make it NOT NULL.
Created src/main/resources/db/migration/0002_rename_customers_name_and_more.sql:
  - rename column customers.name to full_name
  - add column customers.country
  - alter column customers.email
Rollback script written to src/main/resources/db/migration/rollback.
src/main/resources/db/migration/0002_rename_customers_name_and_more.sql, statement 1: warning MX008
  ALTER TABLE customers RENAME COLUMN name TO full_name
  Application instances still running the old code use the old name and will fail.
  ...
src/main/resources/db/migration/0002_rename_customers_name_and_more.sql, statement 2: warning MX002
  ALTER TABLE customers ADD COLUMN country varchar(2) NOT NULL
  Adding a NOT NULL column without a default fails when customers has rows.
  Fix: Add a DEFAULT, or add the column as nullable, backfill it, then make it NOT NULL.
...
Review the SQL, then run 'migrax migrate'.
```

Without `--rename`, Migrax notices that a column went away while another appeared and asks
whether it was a rename; in CI it refuses to guess and stops. [Renaming columns and tables
without losing data](renaming-columns-and-tables-without-losing-data.md) shows both.

The warning MX002 is the failure from the `update` run, found before anything ran. The
migration is a plain SQL file, so fix it the way the warning says: add the column, fill it,
then make it required.

```sql
-- Generated by Migrax 0.17.1 for postgresql

ALTER TABLE customers RENAME COLUMN name TO full_name;
ALTER TABLE customers ADD COLUMN country varchar(2);
UPDATE customers SET country = 'GB';
ALTER TABLE customers ALTER COLUMN country SET NOT NULL;
ALTER TABLE customers ALTER COLUMN email TYPE varchar(320);
```

Next to it, `rollback/0002_rename_customers_name_and_more.sql` undoes the change:

```sql
ALTER TABLE customers DROP COLUMN country;
ALTER TABLE customers ALTER COLUMN email TYPE varchar(255);
ALTER TABLE customers RENAME COLUMN full_name TO name;
```

### 4. Apply it

```console
$ migrax migrate
Applying migration '0002_rename_customers_name_and_more.sql'...
Successfully applied migration '0002_rename_customers_name_and_more.sql'.
Applied 1 migration(s); 2 total in src/main/resources/db/migration.
```

The application starts with `validate`, the next customer signs up, and the existing ones kept
their names:

```text
 id |       email       |  full_name   | country
----+-------------------+--------------+---------
  1 | ada@example.com   | Ada Lovelace | GB
  2 | alan@example.com  | Alan Turing  | GB
  3 | grace@example.com | Grace Hopper | GB
```

## The other environments

Production, staging and every teammate's database have the same table but no migration
history. Each one needs to be told once that the baseline is already there:

```console
$ migrax status
Migrations in src/main/resources/db/migration for jdbc:postgresql://localhost:5432/shop_prod
  [ ] 0001_baseline.sql                        pending
  [ ] 0002_rename_customers_name_and_more.sql  pending
2 pending.

$ migrax baseline 0001 --yes
Recording 1 migration(s) as applied without running them:
  0001_baseline.sql
Recorded. Run 'migrax migrate' to apply the migrations after 0001_baseline.sql.

$ migrax migrate
Applying migration '0002_rename_customers_name_and_more.sql'...
Successfully applied migration '0002_rename_customers_name_and_more.sql'.
Applied 1 migration(s); 2 total in src/main/resources/db/migration.
```

(Point each command at that database with `--url`, or the environment variables your
deployment already uses.)

Forgetting `baseline` is safe. `migrate` tries to create the table, the database refuses, and
nothing is recorded or changed:

```console
$ migrax migrate
Applying migration '0001_baseline.sql'...
error: Failed executing migration '0001_baseline.sql': ERROR: relation "customers" already exists
error[MXE102]: Migration '0001_baseline.sql' failed and was not recorded as applied. ...
```

`baseline` then refuses too, because the failed attempt counts as history. Record the baseline
with `repair` instead, and carry on:

```console
$ migrax repair 0001_baseline.sql --action applied --yes
Repaired history for 0001_baseline.sql (action: applied). Keep a record of this repair.

$ migrax migrate
Applying migration '0002_rename_customers_name_and_more.sql'...
Successfully applied migration '0002_rename_customers_name_and_more.sql'.
Applied 1 migration(s); 2 total in src/main/resources/db/migration.
```

## Other frameworks

The setting has a different name outside Spring Boot, and `migrax doctor` finds each of them:

| Framework | Setting | Change to |
|---|---|---|
| Spring Boot | `spring.jpa.hibernate.ddl-auto` | `validate` or `none` |
| Quarkus | `quarkus.hibernate-orm.schema-management.strategy` | `validate` or `none` |
| Micronaut | `jpa.default.properties.hibernate.hbm2ddl.auto` | `validate` or `none` |
| Plain Hibernate, Jakarta EE | `hibernate.hbm2ddl.auto` | `validate` or `none` |

## Summary

- `ddl-auto=update` only adds. It turns a rename into a second, empty column, can't add a
  required column to a table with rows, and lets the application start when a statement
  fails.
- Switch between releases: set `validate`, run `migrax generate` once to record the existing
  tables as `0001_baseline.sql`, and run `migrax baseline 0001 --yes` once on every other
  database.
- From then on every change is a SQL file you review, with lint warnings for the statements
  that fail on real data, and a rollback script.

More in the guides: [Quick start](../../getting-started/quickstart.md) and [Production
safety](../../guides/production-safety.md).
