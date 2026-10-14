# Changing entities

The everyday loop is short:

```console
$ migrax generate
$ migrax migrate
```

This page covers the cases where Migrax needs a decision from you.

## Preview first

```console
$ migrax plan
2 change(s) for postgresql:
  1. add column orders.notes
     ALTER TABLE orders ADD COLUMN notes varchar(500);
  2. alter column tags.label
     ALTER TABLE tags ALTER COLUMN label TYPE varchar(100);
```

`plan --impact` adds how many rows each affected table has, read from the database, so you can
spot changes that will take long on large tables.

## Renamed a field or entity?

To a database, a rename looks like "one column disappeared, another appeared". Dropping and
re-adding would lose the data, so Migrax looks for likely renames:

- the same name in another style, like `created_at` and `createdAt`, even when the type changed
  too;
- otherwise a new column of the same type in the same table.

In a terminal it asks:

```console
$ migrax generate
Did you rename customers.email to customers.contact_email? [y/N] y
Created src/main/resources/db/migration/0004_rename_customers_email.sql:
  - rename column customers.email to contact_email
```

In scripts and CI, where nothing can be asked, pass the answer:

```console
$ migrax generate --rename customers.email=contact_email
$ migrax generate --rename-table client=customer
```

Several column renames are separated by commas. A rename with a type change becomes a
`RENAME` followed by an `ALTER`.

!!! note "Non-interactive runs"

    When input isn't a terminal, Migrax prints each possible rename with the exact
    `--rename` option to use, and does not assume a rename.

## Check constraints and comments

`generate` writes the check constraints and comments your entities declare:

```java
@Entity
@Comment("Things we sell")
@Check(name = "ck_product_price", constraints = "price >= 0")
public class Product {
  @Comment("Price in cents")
  @Check(constraints = "discount between 0 and 100")
  private int discount;
  ...
}
```

Migrax reads Hibernate's `@Check`, `@Checks` and `@Comment` (`org.hibernate.annotations`), and
the `check` and `comment` attributes of Jakarta Persistence 3.2's `@Table` and `@Column`. A new
table gets its checks inside `CREATE TABLE`; an existing one gets
`ALTER TABLE ... ADD CONSTRAINT ... CHECK (...)`. The condition is copied as written, so use SQL
that your database understands; `migrax verify` runs it on that database before release.
MySQL enforces checks from 8.0.16 on, and `migrax doctor` warns on older servers.

A check without a name gets one from its table and condition, such as
`ck_product_3f2a9c1e`. When you change the condition, the name changes too: the migration drops
the old constraint and adds the new one. Adding a check fails when existing rows break it; fix
those rows in the same migration, before the check is added.

Comments are written the way each database stores them: `COMMENT ON` (PostgreSQL, CockroachDB,
Oracle, H2), part of the table or column definition (MySQL, MariaDB), or the `MS_Description`
property (SQL Server). SQLite keeps no comments, so they are left out there.

With `--safe` on PostgreSQL and CockroachDB, a check added to an existing table goes into the
second migration as `NOT VALID` followed by `VALIDATE CONSTRAINT`, so writes keep flowing.

### Enum columns

Hibernate 6 and newer also writes a check for every enum column, such as
`status between 0 and 2`, or `status in ('ACTIVE','BLOCKED')` for `@Enumerated(STRING)`.
Migrax leaves these out by default: with them, every new enum value needs a migration before the
application can store it, and a forgotten one only shows up as failing inserts. To generate
them, set `migrax.enum-checks=true` (or `MIGRAX_ENUM_CHECKS=true`).

!!! note "Upgrading from Migrax 0.4"
    Earlier versions ignored `@Check` and `@Comment`. The first `generate` after upgrading
    writes the ones your entities already have. If you added them before with a hand-written
    migration, edit the generated file and remove those statements before it runs.

## Drops and narrowing changes

Dropping a table or column, or making a column smaller, deletes data. Migrax refuses until you
confirm with `--allow-destructive`:

```console
$ migrax generate
3 change(s) for mysql:
  ...
  3. drop column test_table.number_of_items  [DESTRUCTIVE]
     ALTER TABLE test_table DROP COLUMN number_of_items;
error[MXE108]: 1 destructive change(s) detected (marked [DESTRUCTIVE] above).
Review them, then run 'migrax generate --allow-destructive'. If a column or table
was renamed, pass --rename table.old=new or --rename-table old=new instead.
```

For a running production system, drop in two releases (expand and contract): first deploy code
that no longer uses the column, then drop it in a later release.

## Naming the migration

The name is generated from the first changes, for example `0005_add_orders_notes_and_more.sql`.
Choose your own with `--name`; the next number is added for you:

```console
$ migrax generate --name split_customer_name
Created src/main/resources/db/migration/0005_split_customer_name.sql
```

## Editing generated SQL

Generated migrations are yours: edit them before they run anywhere, for example to backfill
data in the same release. Never edit a migration after it has been applied: Migrax keeps a
checksum of every applied file and stops when one changes. Write a new migration instead.

## Hand-written migrations

```console
$ migrax new backfill_full_names
Created src/main/resources/db/migration/0006_backfill_full_names.sql
```

`new` creates the next numbered file and an empty rollback script, for changes Migrax can't
derive from entities: data migrations, views, grants, partitions. `--java` creates a Java class
instead; see [Advanced migrations](advanced-migrations.md).
