---
seo_title: "Compare database schemas and environments"
description: "Compare the schemas of two databases, such as staging and production, and print the SQL that makes them match (migrax diff); see every environment's migrations and drift side by side (migrax environments)."
---
# Comparing databases

New in Migrax 0.14.0.

`migrax diff` compares the schemas of two databases of the same kind, such as staging and
production, and lists what would make one match the other. Use it when a hotfix was applied
by hand in one place, before a release to check that production is where staging was, or to
find out why two tenants' databases behave differently.

`drift` compares one database with what the migrations produce. `diff` compares two live
databases with each other, without looking at migrations or entities. To see which migrations
each environment has, use [`migrax environments`](#all-environments-at-a-glance).

## Run it

The **source** is the database whose schema you want; the **target** is the project's
database (the one Migrax normally connects to, or `--url`):

```console
$ migrax diff jdbc:postgresql://staging:5432/shop --url jdbc:postgresql://prod:5432/shop
To make jdbc:postgresql://prod:5432/shop match jdbc:postgresql://staging:5432/shop:
7 change(s) for postgresql:
  1. alter column customer.name
     ALTER TABLE customer ALTER COLUMN name TYPE varchar(100);
  2. add column customer.email
     ALTER TABLE customer ADD COLUMN email varchar(200);
  3. drop column customer.legacy_code  [DESTRUCTIVE]
     ALTER TABLE customer DROP COLUMN legacy_code;
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

## All environments at a glance

New in Migrax 0.17.0.

`migrax environments` asks every environment's database which migrations it has applied, so
you see before a release whether staging is ahead of production, or whether someone changed a
database by hand. It only reads.

Give each environment a URL in the application config:

```properties title="application.properties"
migrax.environments=dev,staging,production
migrax.environments.dev.url=jdbc:postgresql://dev-db:5432/shop
migrax.environments.staging.url=jdbc:postgresql://staging-db:5432/shop
migrax.environments.production.url=jdbc:postgresql://prod-db:5432/shop
```

The first line sets the order of the rows; without it they are sorted by name. Users and
passwords belong in the environment, not in the file:

```bash
export MIGRAX_ENVIRONMENTS_PRODUCTION_USER=readonly
export MIGRAX_ENVIRONMENTS_PRODUCTION_PASSWORD=...   # or ..._PASSWORD_FILE
```

Every setting also works as a variable alone (`MIGRAX_ENVIRONMENTS_STAGING_URL`), which is
handy in CI. A read-only user is enough.

```console
$ migrax environments
Environments for db/migrations (2 migration(s)):

  Environment  Applied  Pending  Last applied                                                 Drift
  dev          2        0        0002_add_customer_phone_create_orders.sql, 2026-10-21 20:58  none
  staging      2        0        0002_add_customer_phone_create_orders.sql, 2026-10-21 20:58  1 difference(s)
  production   1        1        0001_initial.sql, 2026-10-21 20:58                           not checked (pending)

staging: drift: customer.note: column exists in the database but not in the expected schema
production: pending 0002_add_customer_phone_create_orders.sql
```

Here production is one migration behind, which `migrax migrate` there fixes, and someone
added a column to staging by hand. The migrations are the ones in your current checkout:
run it from the branch you are about to release.

- **Pending** counts the migrations that would run there. A migration marked
  `-- migrax:env dev` counts only in dev. When an environment's name differs from the one in
  those lines, set `migrax.environments.<name>.env`.
- **Drift** compares the schema with the snapshot, as [`migrax drift`](../reference/cli.md#drift)
  does. It is checked only where nothing is pending, because pending migrations change the
  schema on purpose. `--no-drift` skips it.
- A database that can't be reached gets `can't connect` and the reason in its row; the others
  are still shown. All of them are asked at the same time, and each one has `--timeout`
  (30 seconds) to answer.
- Migrations an environment has applied that are not in your checkout are listed with `[!]`,
  as in `migrax status`: a newer branch was deployed there, or a file was deleted.

The exit code is `0` when every environment answered (pending migrations are fine), `1` when
one can't be reached or a migration failed, changed or is missing there, and `2` when drift
was found. `--json` gives the same per environment, for a dashboard or a release script:

```bash
migrax environments --json | jq '.environments[] | {name, pending}'
```

With [several databases](several-databases.md), each one has its own environments:
`migrax.databases.billing.environments.staging.url`, shown with
`migrax environments --database billing`.
