# Advanced migrations

## Java migrations

Some data changes are easier in Java than in SQL. Create one with:

```console
$ migrax new backfill_full_names --java
```

```java title="src/main/java/db/migration/V0006__BackfillFullNames.java"
package db.migration;

import io.migrax.api.JavaMigration;
import java.sql.Connection;

public class V0006__BackfillFullNames implements JavaMigration {
    @Override
    public void migrate(Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(
                "UPDATE customer SET full_name = first_name || ' ' || last_name");
        }
    }

    @Override
    public void rollback(Connection connection) throws Exception {
        // optional; without it, 'migrax rollback' refuses to roll this migration back
    }
}
```

- The class name gives the version and order, like a file name: `V0006__BackfillFullNames`
  runs after `0005_....sql`.
- Classes live in the `db.migration` package (change it with `migrax.java-package`).
- The migration runs in the same transaction as its history record. Don't commit or close the
  connection.
- Add `io.migrax:migrax` as a `provided` (Maven) or `compileOnly` (Gradle) dependency so the
  class compiles. With the Spring Boot starter, `JavaMigration` beans are picked up too.

## Repeatable migrations

Files named `R__<name>.sql` run after the versioned migrations, and again whenever their content
changes. Use them for views, functions, stored procedures and grants:

```sql title="R__active_customers_view.sql"
CREATE OR REPLACE VIEW active_customers AS
SELECT id, email FROM customers WHERE active = true;
```

## Callbacks

SQL files with these names run around migrations:

| File | Runs |
|---|---|
| `beforeMigrate.sql` | once, before any migration |
| `beforeEachMigrate.sql` | before each migration |
| `afterEachMigrate.sql` | after each migration |
| `afterMigrate.sql` | once, after all migrations |

## Placeholders

`${name}` in a migration is replaced at run time:

```sql
GRANT SELECT ON customers TO ${reporting_role};
```

Set values in application config (`migrax.placeholders.reporting_role=analyst`), as a system
property (`-Dmigrax.placeholders.reporting_role=analyst`) or an environment variable
(`MIGRAX_PLACEHOLDERS_REPORTING_ROLE=analyst`).

## Directives

Directives are comment lines in a migration:

| Directive | Effect |
|---|---|
| `-- migrax:no-transaction` | Run without a transaction, for statements like `CREATE INDEX CONCURRENTLY` |
| `-- migrax:resume-safe` | Allow `migrate --resume` to re-run it after a failure |
| `-- migrax:lint-ignore MX001` | Silence a lint rule for the next statement |

## Several schemas (multi-tenant)

Run the same migrations once per schema:

```console
$ migrax migrate --schemas tenant_a,tenant_b,tenant_c
```

Or set `migrax.schemas=tenant_a,tenant_b` in application config. `status` and `rollback`
accept the same option, and the startup integrations read `migrax.schemas`.
