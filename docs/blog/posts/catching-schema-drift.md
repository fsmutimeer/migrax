---
date: 2026-11-01T10:00:00
slug: catching-schema-drift
draft: true
authors:
  - feroz
categories:
  - Features
tags:
  - drift
  - diff
  - Spring Boot
  - PostgreSQL
description: >-
  Someone changed production by hand. How migrax drift finds it, how to keep the changes you
  want and undo the rest, and how to stop the application from starting on a drifted
  database.
---

# Catching schema drift before it breaks production

Migrations only describe a database as long as nobody changes it any other way. Then someone
adds a column during an incident, a DBA widens a type, a data import relaxes a constraint, and
nobody writes it down. The next migration was tested against a database that production no
longer is.

That gap is called drift. This article makes four changes by hand on a production database,
finds them with `migrax drift`, keeps the one that was wanted, undoes the rest, and then makes
the application refuse to start on a database that has drifted.

<!-- more -->

!!! info "Tested with"

    Migrax 0.17.1, Spring Boot 3.5.7, Hibernate 6.6.33, PostgreSQL 17. Every output below was
    copied from a real run; log lines are shortened to the message.

## The starting point

A Spring Boot application with a `customers` table, built by two migrations and applied to
production:

```java
@Entity
@Table(name = "customers")
public class Customer {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 320)
  private String email;

  @Column(nullable = false, length = 100)
  private String fullName;

  @Column(nullable = false, length = 2)
  private String country = "GB";
}
```

Here production is the database `shop_prod` and development is `shop`, on the same
PostgreSQL server. `.migrax/snapshot.json` records the schema those migrations produce. `generate` keeps it up to
date and it is committed with the migrations, so it is also the yardstick for drift.

## Four changes nobody wrote down

During a busy week, four statements run on production by hand:

```sql
ALTER TABLE customers ADD COLUMN vip boolean NOT NULL DEFAULT false;
ALTER TABLE customers ALTER COLUMN full_name DROP NOT NULL;
ALTER TABLE customers ALTER COLUMN email TYPE varchar(200);
CREATE INDEX customers_country_idx ON customers (country);
```

The first is a hotfix for a feature that is coming anyway. The second was needed for a data
import and never reverted. The third was a mistake. The fourth sped up a slow report.

## Finding them

```console
$ migrax drift --url jdbc:postgresql://localhost:5432/shop_prod
3 difference(s) between the database and the snapshot:
  - customers.email: expected varchar(320) but the database column is shorter (varchar(200))
  - customers.full_name: expected NOT NULL but the database has NULL
  - customers.vip: column exists in the database but not in the expected schema
Someone changed the database outside migrations. Write a migration for intended changes ('migrax new <name>'), or revert them.
```

`drift` reads the live schema and compares it with the snapshot. It checks tables, columns,
types, nullability, primary and foreign keys and unique constraints. Plain indexes are not part
of that comparison, so `customers_country_idx` isn't listed; `migrax diff`, further down, does
show it.

The shorter `email` is the dangerous one. The entity allows 320 characters, so the first
customer with a long address gets an error from the database. Nothing in the code or the
migrations would show why.

`drift` exits with code 2 when it finds differences, and `--json` gives the same result for a
script:

```console
$ migrax drift --url jdbc:postgresql://localhost:5432/shop_prod --json
{"against":"snapshot","pending":0,"differences":[{"kind":"type","object":"customers.email","detail":"expected varchar(320) but the database column is shorter (varchar(200))"},{"kind":"nullability","object":"customers.full_name","detail":"expected NOT NULL but the database has NULL"},{"kind":"extra_column","object":"customers.vip","detail":"column exists in the database but not in the expected schema"}]}
$ echo $?
2
```

With `--entities`, `drift` compares the database with the entities instead of the snapshot.
That answers a different question: not "did someone change the database?" but "does the
database fit the code I have now?"

## Keeping the change you want

The `vip` column is wanted, so it belongs in the entity and in a migration, like every other
change:

```java
  @ColumnDefault("false")
  @Column(nullable = false)
  private boolean vip;
```

```console
$ migrax generate
Created src/main/resources/db/migration/0003_add_customers_vip.sql:
  - add column customers.vip
Rollback script written to src/main/resources/db/migration/rollback.
Review the SQL, then run 'migrax migrate'.
```

```sql
ALTER TABLE customers ADD COLUMN vip boolean DEFAULT false NOT NULL;
```

Every other database (development, staging, a new laptop) needs this statement. Production
already has the column and would fail on it, so add `IF NOT EXISTS`:

```sql
ALTER TABLE customers ADD COLUMN IF NOT EXISTS vip boolean DEFAULT false NOT NULL;
```

The migration now runs everywhere, and production records it like any other:

```console
$ migrax migrate --url jdbc:postgresql://localhost:5432/shop_prod
Applying migration '0003_add_customers_vip.sql'...
Successfully applied migration '0003_add_customers_vip.sql'.
Applied 1 migration(s); 3 total in src/main/resources/db/migration.

$ migrax drift --url jdbc:postgresql://localhost:5432/shop_prod
2 difference(s) between the database and the snapshot:
  - customers.email: expected varchar(320) but the database column is shorter (varchar(200))
  - customers.full_name: expected NOT NULL but the database has NULL
Someone changed the database outside migrations. Write a migration for intended changes ('migrax new <name>'), or revert them.
```

## Undoing the rest

For the changes that shouldn't be there, `migrax diff` writes the SQL. It compares two live
databases: the source is one that is right (here the development database, which only ever got
migrations), the target is production. The source's credentials come from
`MIGRAX_SOURCE_USER` and `MIGRAX_SOURCE_PASSWORD`, or `--source-user` and
`--source-password-file`:

```console
$ migrax diff jdbc:postgresql://localhost:5432/shop --url jdbc:postgresql://localhost:5432/shop_prod
To make jdbc:postgresql://localhost:5432/shop_prod match jdbc:postgresql://localhost:5432/shop:
3 change(s) for postgresql:
  1. drop index customers_country_idx on customers  [DESTRUCTIVE]
     DROP INDEX customers_country_idx;
  2. alter column customers.email
     ALTER TABLE customers ALTER COLUMN email TYPE varchar(320);
  3. alter column customers.full_name
     ALTER TABLE customers ALTER COLUMN full_name SET NOT NULL;
To keep them, save the SQL as a migration: migrax diff <source-url> --sql
```

`diff` also compares indexes, so the report index shows up here. `--sql` prints the same
statements as one script to review and hand to whoever runs SQL on production:

```sql
-- Generated by Migrax 0.17.1 for postgresql

DROP INDEX customers_country_idx;
ALTER TABLE customers ALTER COLUMN email TYPE varchar(320);
ALTER TABLE customers ALTER COLUMN full_name SET NOT NULL;
```

Keep the lines you mean to run. If the index is worth having, leave out the `DROP INDEX` and
add the index to the entity (`@Table(indexes = ...)`) so the next `generate` writes it for
everyone else. `SET NOT NULL` fails if the import left rows without a name, so fill those in
first. Once the script has run:

```console
$ migrax drift --url jdbc:postgresql://localhost:5432/shop_prod
No drift: the database matches the snapshot.

$ migrax diff jdbc:postgresql://localhost:5432/shop --url jdbc:postgresql://localhost:5432/shop_prod
Same schema: jdbc:postgresql://localhost:5432/shop_prod matches jdbc:postgresql://localhost:5432/shop.
```

## Noticing it the next time

Finding drift by chance is too late. There are two places to check for it on purpose.

**On a schedule.** Because `drift` exits with 2, a nightly CI job or Kubernetes CronJob fails
when it finds something, and the usual alert for a failed job tells you the next morning.

**When the application starts.** With the [Spring Boot
starter](../../frameworks/spring-boot.md), the application runs the same comparison each time
it starts, right after it applies any pending migrations. Turn it on with one line:

```properties
migrax.drift.check=fail
```

The application reads the expected schema from `META-INF/migrax/snapshot.json`, so the build
has to put the snapshot there. With Maven, that is the `package-snapshot` goal of the Migrax
plugin:

```xml
<plugin>
  <groupId>org.migrax</groupId>
  <artifactId>migrax</artifactId>
  <version>0.17.1</version>
  <executions>
    <execution>
      <goals>
        <goal>package-snapshot</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

(The Gradle plugin and the Quarkus extension copy it without any setup.)

A week later someone adds another column by hand, and the next deployment stops:

```text
Migrax: schema is up to date
Migrax: 1 difference(s) between the database and the schema its migrations produce:
  - customers.hotfix_note: column exists in the database but not in the expected schema
Someone changed the database outside migrations. Write a migration for intended changes, or revert them.
(migrax.drift.check=fail stops the start; warn only logs.)
```

The change is noticed at the deployment, not weeks later when something breaks. On a database
without drift, the log says so and the application starts:

```text
Migrax: schema is up to date
Migrax: no drift: the database matches the schema its migrations produce
Started ShopApplication in 6.757 seconds (process running for 7.945)
```

`migrax.drift.check=warn` logs the same differences without stopping the start, which is a
gentler way to begin. Quarkus, Micronaut, Helidon and Jakarta EE take the same setting.

## Summary

- `migrax drift` compares a live database with the schema its migrations produce and exits
  with 2 when someone changed it by hand. `--json` is for scripts, `--entities` compares with
  the code instead.
- A hand-made change you want goes into the entity and a migration. `IF NOT EXISTS` lets the
  database that already has it run the migration too.
- `migrax diff` writes the SQL that undoes the rest, by comparing with a database that only
  ever got migrations. Unlike `drift`, it also compares indexes.
- Run `drift` on a schedule, or set `migrax.drift.check=fail` so the application doesn't start
  on a drifted database.

More in the guides: [Production safety](../../guides/production-safety.md) and [Comparing
databases](../../guides/comparing-databases.md).
