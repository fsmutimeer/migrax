---
seo_title: "Zero-downtime database migrations"
description: "Change the schema while the old and new application versions run together: expand and contract migrations generated from your entities."
---
# Zero-downtime changes

New in Migrax 0.11.0.

During a rolling deployment, instances of the old version keep running while the new version
starts. Both use the same database. A migration that renames or drops a column breaks the old
instances the moment it runs, and one that adds a NOT NULL column breaks their inserts.

`migrax generate --zero-downtime` splits such a change into two migrations:

- The **expand** migration ships with this release. Everything in it is safe for the old
  version: it only adds and relaxes.
- The **contract** migration ships with the next release, when no old instance is left. It
  removes and tightens.

<figure class="mx-diagram" markdown="0">
<svg viewBox="0 0 690 250" role="img" aria-labelledby="mx-zd-title">
<title id="mx-zd-title">The expand migration ships with release N, while old and new instances run together. When every instance runs release N, migrax generate --contract writes the contract migration, which ships with release N+1.</title>
<defs><marker id="mx-zd-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path class="mx-diagram__head" d="M0 0L10 5L0 10z"/></marker></defs>
<rect class="mx-diagram__box mx-diagram__box--accent" x="10" y="20" width="300" height="150" rx="12"/>
<text x="30" y="52">Expand migration</text>
<text class="mx-diagram__label" x="30" y="76">Adds and relaxes: safe for the old version.</text>
<rect class="mx-diagram__chip" x="30" y="98" width="120" height="30" rx="15"/>
<text class="mx-diagram__small" x="90" y="118" text-anchor="middle">old version</text>
<rect class="mx-diagram__chip" x="160" y="98" width="120" height="30" rx="15"/>
<text class="mx-diagram__small" x="220" y="118" text-anchor="middle">new version</text>
<text class="mx-diagram__label" x="30" y="154">Both run against the same database.</text>
<path class="mx-diagram__line" d="M310 95H376" marker-end="url(#mx-zd-arrow)"/>
<rect class="mx-diagram__box" x="380" y="20" width="300" height="150" rx="12"/>
<text x="400" y="52">Contract migration</text>
<text class="mx-diagram__label" x="400" y="76">Removes and tightens.</text>
<rect class="mx-diagram__chip" x="400" y="98" width="120" height="30" rx="15"/>
<text class="mx-diagram__small" x="460" y="118" text-anchor="middle">new version</text>
<text class="mx-diagram__label" x="400" y="154">No old instance is left.</text>
<path class="mx-diagram__axis" d="M10 205H676" marker-end="url(#mx-zd-arrow)"/>
<text class="mx-diagram__label" x="160" y="232" text-anchor="middle">Release N</text>
<text class="mx-diagram__label" x="530" y="232" text-anchor="middle">Release N+1</text>
<text class="mx-diagram__mono" x="345" y="196" text-anchor="middle">migrax generate --contract</text>
</svg>
</figure>

```console
$ migrax generate --zero-downtime --rename customer.email=email_address
Created src/main/resources/db/migration/0007_rename_customer_email_expand.sql (expand: safe while the old version runs):
  - alter column customer.email
  - add column customer.email_address
  - copy customer.email to email_address and keep both equal (trigger)
Waiting in .migrax/pending/0007_rename_customer_email_contract.sql (contract: for the next release):
  - drop column customer.email
  - alter column customer.email_address
  - remove the trigger that keeps customer.email and email_address equal
Rollback scripts written to src/main/resources/db/migration/rollback and .migrax/pending/rollback.
src/main/resources/db/migration/0007_rename_customer_email_expand.sql, statement 5: warning MX009
  UPDATE customer SET email_address = email
  This changes every row of the table in one transaction.
  Fix: Add a WHERE clause, or update large tables in batches.
Review the SQL, then run 'migrax migrate'. When every instance runs this release, run 'migrax generate --contract' for the next one.
```

The expand migration on PostgreSQL:

```sql
ALTER TABLE customer ALTER COLUMN email DROP NOT NULL;
ALTER TABLE customer ADD COLUMN email_address varchar(255);
CREATE FUNCTION migrax_sync_customer_email_address() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'INSERT' THEN
    IF NEW.email_address IS NULL THEN NEW.email_address := NEW.email; END IF;
    IF NEW.email IS NULL THEN NEW.email := NEW.email_address; END IF;
  ELSIF NEW.email_address IS DISTINCT FROM OLD.email_address THEN
    NEW.email := NEW.email_address;
  ELSIF NEW.email IS DISTINCT FROM OLD.email THEN
    NEW.email_address := NEW.email;
  END IF;
  RETURN NEW;
END
$$ LANGUAGE plpgsql;
CREATE TRIGGER migrax_sync_customer_email_address BEFORE INSERT OR UPDATE ON customer FOR EACH ROW EXECUTE FUNCTION migrax_sync_customer_email_address();
UPDATE customer SET email_address = email;
```

The contract migration removes the trigger and the old column, and makes the new column
NOT NULL:

```sql
DROP TRIGGER migrax_sync_customer_email_address ON customer;
DROP FUNCTION migrax_sync_customer_email_address();
ALTER TABLE customer DROP COLUMN email;
ALTER TABLE customer ALTER COLUMN email_address SET NOT NULL;
```

## What goes where

| Change in the entities | Expand migration (this release) | Contract migration (next release) |
|---|---|---|
| Column renamed | Add the new column, copy the values, and keep both columns equal with a trigger | Remove the trigger, drop the old column, set NOT NULL |
| Table renamed | Rename the table, and keep a view with the old name | Drop the view |
| Column or table dropped | Make the column nullable (the new version doesn't write it) | Drop it |
| New NOT NULL column without a default | Add it as nullable | Set NOT NULL |
| Column becomes NOT NULL | | Set NOT NULL |
| Type gets wider (longer text, `integer` to `bigint`) | Change the type | |
| Type gets narrower or changes kind | | Change the type |
| New unique key, foreign key or check | | Add it |
| Unique key, foreign key or check removed | Drop it | |
| New table, nullable column, index or sequence | Add it | |
| Index removed | | Drop it |

When nothing has to wait for the old version, `--zero-downtime` writes a normal migration.

## The workflow

**This release.** Change the entities, then run `migrax generate --zero-downtime` (with
`--allow-destructive` when something is dropped). Commit the expand migration and the files in
`.migrax/pending/`, and deploy as usual. The expand migration runs, then the new instances
start while the old ones still run.

**Next release.** Once no instance of the old version is left, run:

```console
$ migrax generate --contract
Created src/main/resources/db/migration/0009_rename_customer_email_contract.sql.
Release them only when no instance of the version before their expand migration is left.
```

The contract migration gets the next number and joins the migration folder, with its rollback
script. Commit it and deploy. As long as a contract migration is waiting, `migrax generate`
reminds you of it.

Nothing else changes: `migrate`, the startup integrations and `verify` treat both migrations
like any other.

## Renamed columns

From the expand migration on, the old version writes the old column and the new version writes
the new one. A trigger copies each value to the other column, so both versions see every row.
The expand migration also copies the existing values once (`UPDATE customer SET email_address =
email`). On a large table, that update takes time and locks rows: run it in batches if you need
to (lint warns about it as MX009).

On MySQL with binary logging (the default), only users with the SUPER privilege can create
triggers unless `log_bin_trust_function_creators` is 1. Set it before the expand migration runs:
`SET GLOBAL log_bin_trust_function_creators = 1`, or in the parameter group on Amazon RDS.

The trigger is written for PostgreSQL, YugabyteDB, MySQL, MariaDB and Oracle (TiDB has no
triggers). On other databases,
`--zero-downtime` stops with an explanation; rename the column in two releases by hand, or
generate without `--zero-downtime` during a maintenance window.

## Renamed tables

The expand migration renames the table and creates a view with the old name. The old version
reads and writes through the view. This works on PostgreSQL, YugabyteDB, MySQL, MariaDB, SQL
Server, Oracle and HSQLDB, whose simple views accept writes.

## New NOT NULL columns

The old version doesn't know the new column, so its rows have no value there. The column stays
nullable until the contract migration, and Migrax adds a note to both migrations:

```text
Note: customer.tier is NOT NULL in the contract migration. Rows written by the old version have no value: fill them before it runs, for example in the expand migration (UPDATE customer SET tier = ... WHERE tier IS NULL).
```

Give the column a default in the entity instead, and the old version's rows get it.

## Rollback

Both migrations have rollback scripts. Rolling back the contract migration brings the old column
back (filled from the new one, and kept equal by the trigger again). Rolling back the expand
migration removes what it added.

Columns the new version doesn't write stay nullable when the expand migration is rolled back:
rows written by the new version have no value for them, so NOT NULL would fail. The rollback
script says so in a comment.

A widened type is narrowed again by the expand rollback, so values the new version wrote must
fit the old type. Oracle narrows a `number` column only when it is empty (ORA-01440): set it
to NULL first, or keep the wider type.

## What it can't split

- A changed primary key: change it in a migration of its own, without `--zero-downtime`.
- `--keep-data` and `--safe` can't be combined with `--zero-downtime`.
- While the expand migration is applied, the schema has the old names next to the new ones.
  Hibernate's schema validation accepts that. It doesn't check nullability or column lengths,
  so a column that becomes NOT NULL or narrower later doesn't stop the new version from
  starting. A type that changes kind (`bigint` to `integer`, text to a number) keeps the old
  type until the contract migration, which the validation may reject: change such a type in a
  migration of its own.
