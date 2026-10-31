---
date: 2026-10-31T11:40:00
slug: migrax-vs-flyway
authors:
  - feroz
categories:
  - Comparisons
tags:
  - Flyway
  - generate
  - PostgreSQL
description: >-
  Migrax and Flyway side by side on the same change: what each one writes, checks and can
  undo, which features cost money, where Flyway is the better choice, and how to switch.
---

# Migrax vs Flyway: generated vs hand-written migrations

Flyway and Migrax both keep a database schema in numbered SQL files, apply them in order and
record what ran. The difference is where the SQL comes from. With Flyway you write every
migration yourself. With Migrax you change your JPA entities and it writes the migration,
which you then review.

This article makes the same change with both, on the same PostgreSQL database, and compares
what each one does along the way. It also says where Flyway is the better choice.

<!-- more -->

!!! info "Tested with"

    Flyway Community 13.12.0 (the open-source command line, released 2026-10-30), Migrax
    0.17.1, Spring Boot 3.5.7, Hibernate 6.6.33, PostgreSQL 17. Compared on 2026-10-31.
    Statements about Flyway's editions come from Redgate's
    [feature summary](https://documentation.red-gate.com/fd/feature-summary-165740620.html)
    (last updated 2026-10-27). Every output below was copied from a real run.

## The change

A `customers` table with two rows, and the change from
[Stop using `ddl-auto=update` in production](stop-using-ddl-auto-update-in-production.md):
`name` becomes `full_name`, `email` gets longer, and every customer needs a `country`.

## With Flyway

You write the migration. A correct version keeps the data by renaming the column, and adds the
required column in three steps because the table has rows:

```sql title="V2__rename_name_add_country.sql"
ALTER TABLE customers RENAME COLUMN name TO full_name;
ALTER TABLE customers ADD COLUMN country varchar(2);
UPDATE customers SET country = 'GB';
ALTER TABLE customers ALTER COLUMN country SET NOT NULL;
ALTER TABLE customers ALTER COLUMN email TYPE varchar(320);
```

```console
$ flyway migrate
Current version of schema "public": 1
Migrating schema "public" to version "2 - rename name add country"
Successfully applied 1 migration to schema "public", now at version v2 (execution time 00:00.019s)
```

Flyway runs what you wrote, exactly as written. Whether the file matches the entities, and
whether it keeps the data, is up to you and your reviewers. If you forget the `UPDATE`, or
write `DROP COLUMN name` and `ADD COLUMN full_name` instead of a rename, Flyway runs that too.

There is no way back with the open-source edition:

```console
$ flyway undo
ERROR: Flyway Redgate Edition Required: undo is not supported by OSS Edition
```

Undo scripts (`U2__...sql`) and the `undo` command are part of Flyway Teams and Enterprise, and
you write each undo script yourself.

## With Migrax

You change the entity (`fullName`, `length = 320`, a new `country` field) and run `generate`:

```console
$ migrax generate --rename customers.name=full_name
warning: Adding NOT NULL column 'customers.country' without a default fails if the table has rows. Add a default in the SQL, or add it as nullable, backfill, then make it NOT NULL.
Created src/main/resources/db/migration/0002_rename_customers_name_and_more.sql:
  - rename column customers.name to full_name
  - add column customers.country
  - alter column customers.email
Rollback script written to src/main/resources/db/migration/rollback.
...
```

It writes the forward migration and a rollback script, and lints the migration before
anything runs.
The warning above is the `UPDATE` you would otherwise have to remember. You edit the file the
same way as the Flyway one, and end up with the same five statements. Without `--rename`,
Migrax sees a column go away and another appear, asks whether it was a rename, and refuses to
drop the column without `--allow-destructive`. In CI, where no one can answer, it stops and
prints the `--rename` option to use.

`migrax rollback` runs the generated rollback script:

```sql title="rollback/0002_rename_customers_name_and_more.sql"
ALTER TABLE customers DROP COLUMN country;
ALTER TABLE customers ALTER COLUMN email TYPE varchar(255);
ALTER TABLE customers RENAME COLUMN full_name TO name;
```

So the work moves from writing SQL to reviewing it. You still read every migration; you no
longer type it, or its undo script.

## What both do

Much of the day-to-day is the same:

- **Plain SQL files in order.** Flyway's `V1__...sql`, Migrax's `0001_...sql`. Both also run
  Java migrations for changes SQL can't express.
- **A history table with checksums.** Both refuse to continue when someone edits a migration
  that already ran:

    ```console
    $ flyway validate
    Migration checksum mismatch for migration version 1
    -> Applied to database : -328024399
    -> Resolved locally    : 1803351363
    Either revert the changes to the migration, or run repair to update the schema history.

    $ migrax migrate
    error[MXE104]: Checksum changed for applied migration V1__create_customers.sql
    ```

- **Baselines** for a database that existed before the tool: `flyway baseline` and
  `migrax baseline`.
- **Running at application startup**, through Spring Boot's built-in Flyway support or the
  Migrax starter, and both have Maven and Gradle plugins.

## Where they differ

| | Flyway Community | Flyway Teams / Enterprise | Migrax |
|---|---|---|---|
| Who writes the SQL | You | You; Enterprise can generate it from a schema model | Generated from JPA entities, you review it |
| Undo / rollback | No | Teams and Enterprise: undo scripts you write | Rollback script generated with each migration |
| Dry run script | No | Yes | `migrax migrate --sql` prints the script without running it |
| Drift detection | No | Enterprise | `migrax drift`, and a check at startup |
| Lint for risky statements | No | Not in the feature summary | `migrax lint`, run on every `generate` |
| Throwaway-database check | No | Not in the feature summary | `migrax verify` applies every migration and rollback |
| Databases | About 30, including Snowflake, BigQuery and MongoDB ([list](https://documentation.red-gate.com/fd/supported-databases-and-versions-143754067.html)) | Same, with Redgate support | 12 relational databases |
| Licence | Apache 2.0 | Commercial | Apache 2.0, every feature |

"Not in the feature summary" means Redgate's feature summary doesn't list it; Redgate's
documentation describes the checks each edition has.

## Where Flyway is the better choice

- **There are no JPA entities.** Migrax generates migrations from Hibernate, JPA and a few other
  Java mappings (or a SQL schema file). A project that writes SQL by hand, or isn't on the JVM,
  gains less from it, while Flyway works the same for everyone.
- **The database isn't one of Migrax's 12.** Flyway reaches warehouses and document databases
  such as Snowflake, BigQuery, Databricks and MongoDB.
- **You want a vendor behind the tool.** Redgate sells support, a desktop application and
  compliance features. Migrax is an open-source project.
- **The team prefers writing every statement.** Some teams want the migration to be the source
  of truth, not the entities. Flyway is built around that.

## Switching from Flyway

Migrax reads Flyway's history table, so a project can switch without rerunning anything. Copy
or keep the `V*__` files in the migration folder and run:

```console
$ migrax import flyway
Recorded 2 migration(s) as applied.
Next: disable Flyway (spring.flyway.enabled=false), then run 'migrax generate'; its first run uses the current database as the baseline.

$ migrax status
Migrations in src/main/resources/db/migration for jdbc:postgresql://localhost:5432/shop_fw
  [X] V1__create_customers.sql         applied 2026-10-31 11:45:20
  [X] V2__rename_name_add_country.sql  applied 2026-10-31 11:45:20
2 applied.

$ migrax generate
No snapshot yet: using the current database schema as the baseline.
No changes detected.
Saved the entity schema as the baseline snapshot: .migrax/snapshot.json
```

The old files keep working, and the next entity change becomes `0003_...sql`. Each other
environment needs `migrax import flyway` once. The guide [Switching from Flyway or
Liquibase](../../guides/switching-tools.md) has the details.

## Summary

- Flyway applies the SQL you write. Migrax writes the SQL from your entities, with a rollback
  script and lint warnings, and you review it.
- Both keep a checksummed history, refuse edited migrations, baseline existing databases and
  run at startup.
- Undo, dry-run scripts and drift detection are paid Flyway features; in Migrax they are part
  of the free tool.
- Flyway is the better fit without JPA entities, for databases Migrax doesn't support, or when
  you want commercial support.
