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
