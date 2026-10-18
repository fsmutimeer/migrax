---
seo_title: "Compare two database schemas"
description: "Compare the schemas of two databases, such as staging and production, list the differences and print the SQL that makes them match: migrax diff."
---
# Comparing databases

New in Migrax 0.14.0.

`migrax diff` compares the schemas of two databases of the same kind, such as staging and
production, and lists what would make one match the other. Use it when a hotfix was applied
by hand in one place, before a release to check that production is where staging was, or to
find out why two tenants' databases behave differently.

`drift` compares one database with what the migrations produce. `diff` compares two live
databases with each other, without looking at migrations or entities.

## Run it

The **source** is the database whose schema you want; the **target** is the project's
database (the one Migrax normally connects to, or `--url`):

```console
$ migrax diff jdbc:postgresql://staging:5432/shop --url jdbc:postgresql://prod:5432/shop
To make jdbc:postgresql://prod:5432/shop match jdbc:postgresql://staging:5432/shop:
7 change(s) for postgresql:
  1. drop column customer.legacy_code  [DESTRUCTIVE]
     ALTER TABLE customer DROP COLUMN legacy_code;
  2. alter column customer.name
     ALTER TABLE customer ALTER COLUMN name TYPE varchar(100);
  3. add column customer.email
     ALTER TABLE customer ADD COLUMN email varchar(200);
  4. comment on customer
     COMMENT ON TABLE customer IS 'People who buy';
  5. add index idx_customer_email on customer
     CREATE INDEX idx_customer_email ON customer (email);
  6. add check orders_total_check on orders
     ALTER TABLE orders ADD CONSTRAINT orders_total_check CHECK (((total >= (0)::numeric)));
  7. add foreign key orders_customer_id_fkey on orders
     ALTER TABLE orders ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES customer (id);
To keep them, save the SQL as a migration: migrax diff <source-url> --sql
```

When nothing differs, it says so and exits with 0:

```console
$ migrax diff jdbc:postgresql://staging:5432/shop --url jdbc:postgresql://prod:5432/shop
Same schema: jdbc:postgresql://prod:5432/shop matches jdbc:postgresql://staging:5432/shop.
```

Tables, columns and their types, nullability, primary keys, foreign keys, unique constraints,
indexes, checks, comments and sequences are compared. Migrax's own tables
(`migrax_history` and the others) are left out.

## Credentials

The target uses the usual settings: the application configuration, `MIGRAX_DATABASE_URL`,
`MIGRAX_DATABASE_USER`, `MIGRAX_DATABASE_PASSWORD`, or `--url`, `--user` and `--password-file`.
The source has its own:

```bash
export MIGRAX_SOURCE_USER=readonly
export MIGRAX_SOURCE_PASSWORD=...        # or MIGRAX_SOURCE_PASSWORD_FILE / --source-password-file
migrax diff jdbc:postgresql://staging:5432/shop
```

`diff` only reads. A read-only user is enough on both sides. Passwords inside URLs are shown
as `***`.

## Turn the differences into a migration

`--sql` prints only the SQL, ready to review. To keep the changes, save it as a migration with
the next free number, so every environment gets them:

```bash
migrax diff jdbc:postgresql://staging:5432/shop --sql \
  > src/main/resources/db/migration/0012_align_with_staging.sql
```

Read it before you commit: a column that exists only in the target is dropped, with its data.
If that column was added by hand on purpose, write a migration for it instead, so the source
gets it too.

## In scripts and CI

The exit code is `2` when the databases differ, `0` when they match. `--json` lists each change
with its kind, a summary, its SQL, and whether it is destructive:

```bash
migrax diff "$STAGING_URL" --json | jq '.changes[] | .summary'
```
