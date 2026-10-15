# Advanced migrations

## Java migrations

Some data changes are easier in Java than in SQL. Create one with:

```console
$ migrax new backfill_full_names --java
```

```java title="src/main/java/db/migration/V0006__BackfillFullNames.java"
package db.migration;

import org.migrax.api.JavaMigration;
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
- Add `org.migrax:migrax` as a `provided` (Maven) or `compileOnly` (Gradle) dependency so the
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

`${migrax.schema}` is built in: it is the schema the migration runs in. With
[several schemas](#several-schemas-multi-tenant) it changes from one schema to the next.

## Directives

Directives are comment lines in a migration:

| Directive | Effect |
|---|---|
| `-- migrax:no-transaction` | Run without a transaction, for statements like `CREATE INDEX CONCURRENTLY` |
| `-- migrax:resume-safe` | Allow `migrate --resume` to re-run it after a failure |
| `-- migrax:lint-ignore MX001` | Silence a lint rule for the next statement |
| `-- migrax:no-split` | Send the whole file to the database as one statement |

## Procedures, functions and triggers

Write procedures, functions, triggers, Oracle packages and anonymous blocks (`BEGIN ... END;`,
`DECLARE ... BEGIN ... END;`) as you would anywhere else. Migrax runs a migration statement by
statement, and it knows that a semicolon inside a body doesn't end the statement: the body ends
with the `END` that closes its first `BEGIN`. A package ends with its own last `END`.

```sql
CREATE TABLE note_log (id INT PRIMARY KEY, note VARCHAR(100));

CREATE PROCEDURE add_note(IN p_note VARCHAR(100))
BEGIN
  DECLARE next_id INT;
  SELECT COALESCE(MAX(id), 0) + 1 INTO next_id FROM note_log;
  INSERT INTO note_log (id, note) VALUES (next_id, p_note);
END;
```

On SQL Server, a procedure written without `BEGIN` and `END` runs to the end of the file, as
it does in SQL Server's own tools. `migrax lint` warns (MX016) when a body seems to take in
the statements after it.

You can also mark where a body ends the way your database's own tools do. Scripts copied from
those tools work unchanged:

=== "SQL Server"

    A line with only `GO` ends a batch. Each batch is sent whole. `GO 5` runs the batch five
    times.

    ```sql
    CREATE TABLE note_log (id INT PRIMARY KEY, note VARCHAR(100));
    GO
    CREATE PROCEDURE add_note @note VARCHAR(100) AS
    BEGIN
      SET NOCOUNT ON;
      INSERT INTO note_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, @note FROM note_log;
    END;
    GO
    ```

=== "MySQL and MariaDB"

    `DELIMITER` changes what ends a statement, until the next `DELIMITER` line.

    ```sql
    CREATE TABLE note_log (id INT PRIMARY KEY, note VARCHAR(100));
    DELIMITER $$
    CREATE PROCEDURE add_note(IN p_note VARCHAR(100))
    BEGIN
      DECLARE next_id INT;
      SELECT COALESCE(MAX(id), 0) + 1 INTO next_id FROM note_log;
      INSERT INTO note_log (id, note) VALUES (next_id, p_note);
    END$$
    DELIMITER ;
    ```

=== "Oracle"

    A line with only `/` ends a PL/SQL block: `CREATE PROCEDURE`, `FUNCTION`, `PACKAGE`,
    `TRIGGER`, `TYPE`, or an anonymous `BEGIN` or `DECLARE` block. Other statements still end
    at their semicolon.

    ```sql
    CREATE TABLE note_log (id NUMBER PRIMARY KEY, note VARCHAR2(100));
    CREATE OR REPLACE PROCEDURE add_note(p_note IN VARCHAR2) AS
    BEGIN
      INSERT INTO note_log (id, note) SELECT NVL(MAX(id), 0) + 1, p_note FROM note_log;
    END;
    /
    ```

=== "PostgreSQL"

    Function bodies in dollar quotes (`$$ ... $$`) need nothing extra.

    ```sql
    CREATE FUNCTION add_note(p_note VARCHAR) RETURNS void AS $$
    BEGIN
      INSERT INTO note_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, p_note FROM note_log;
    END;
    $$ LANGUAGE plpgsql;
    ```

=== "SQLite"

    End a trigger with a line holding only `/`, as for Oracle.

    ```sql
    CREATE TRIGGER add_note AFTER INSERT ON note_source
    BEGIN
      INSERT INTO note_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, NEW.note FROM note_log;
    END;
    /
    ```

Anything else can go in a file of its own with `-- migrax:no-split`: Migrax then sends the
whole file as one statement. `lint` and `verify` split files the same way.

## Several migration folders

Migrations can come from more than one folder, for example shared migrations from a library
module next to the application's own, or test data that only some environments load:

```properties
migrax.locations=classpath:db/migration,filesystem:db/dev-data
```

(or `--locations` and `MIGRAX_LOCATIONS` with the same value). Migrax reads every folder and
runs the files in version order across all of them. File names must be unique across the
folders: two folders with the same file name is an error. A rollback script lives in the
`rollback` folder next to its migration.

`new`, `generate`, `squash`, `merge` and `import` write to the first folder; new files are
numbered after the highest number in any folder.

## Migrations for some environments only

Test data, demo accounts or development helpers can live in migrations that run only in some
environments. Mark them with an `env` line:

```sql title="0002_demo_customers.sql"
-- migrax:env dev, test
INSERT INTO customer (id, name) VALUES (1, 'Demo customer');
```

and name the environment where Migrax runs: `--env dev`, `MIGRAX_ENV=dev`, or `migrax.env=dev`
in the application configuration (also for the startup integrations). Migrations without an
`env` line run everywhere. A marked migration runs only where its environment is set, and
elsewhere `status` shows it as skipped:

```console
$ migrax status
Migrations in src/main/resources/db/migration for jdbc:postgresql://localhost:5432/shop
  [X] 0001_create_customer.sql  applied 2026-10-12 13:53:55
  [-] 0002_demo_customers.sql   only for dev, test
1 applied, 1 skipped.

$ migrax migrate --env dev
Applying migration '0002_demo_customers.sql'...
Successfully applied migration '0002_demo_customers.sql'.
Applied 1 migration(s); 2 total in src/main/resources/db/migration.
```

Skipped migrations are not pending: health checks stay up. A folder per environment
([several migration folders](#several-migration-folders)) works too.

## Several schemas (multi-tenant)

Run the same migrations once per schema:

```console
$ migrax migrate --schemas tenant_a,tenant_b,tenant_c
```

Or set `migrax.schemas=tenant_a,tenant_b` in application config. `status`, `rollback`,
`migrate --sql` and `clean` accept the same option, and the startup integrations read
`migrax.schemas`. Each schema keeps its own `migrax_history`, so a schema added later gets every
migration on the next run.

Create the schemas before the first run; Migrax creates the tables in them, not the schemas.

### SQL Server

SQL Server can't switch a connection to another schema: a table name without a schema always
means the login's default schema, usually `dbo`. So in a project that sets `migrax.schemas`,
`generate` puts `${migrax.schema}.` in front of every table, and Migrax fills in each schema
when it runs the migration:

```sql
CREATE TABLE ${migrax.schema}.book (id bigint NOT NULL, title varchar(255), ...);
CREATE INDEX ix_book_title ON ${migrax.schema}.book (title);
```

Write your own migrations the same way. `migrax lint` warns about a table without its schema
(MX017), because that statement would change the table in the default schema, once for every
tenant. Migrax's own history tables are named the same way (`[tenant_a].migrax_history`).
