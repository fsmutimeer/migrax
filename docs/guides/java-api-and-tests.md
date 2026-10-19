---
seo_title: "Java API and JUnit tests for database migrations"
description: "Run Migrax from Java code, and test your migrations with JUnit 5: @MigraxTest gives each test a database with the migrations applied."
---
# Java API and JUnit tests

New in Migrax 0.10.0.

Migrax can run from your own Java code, and its JUnit 5 extension can check in a unit test that
your migrations still match your entities.

## Test that migrations match the entities

Add `migrax-junit` as a test dependency:

```xml
<dependency>
  <groupId>org.migrax</groupId>
  <artifactId>migrax-junit</artifactId>
  <version>0.15.0</version>
  <scope>test</scope>
</dependency>
```

Then put `@MigraxTest` on a test class:

```java
import org.migrax.junit.MigraxTest;

@MigraxTest
class BookRepositoryTest {

  @Test
  void savesBooks(DataSource dataSource) throws Exception {
    // The database already has every migration applied.
  }
}
```

Before the tests run, Migrax creates the test database, applies every migration from
`src/main/resources/db/migration` and compares the result with your entities. If they differ,
every test in the class fails with a list of the differences:

```text
The migrated schema does not match the entities (Hibernate 6.6.13.Final mapping):
  - book.isbn: column is missing from the database
Generate a migration for the entity changes (migrax generate), or fix the migration that differs.
```

This catches an entity that changed without a new migration, and a migration that was edited by
hand and no longer creates what the entities need. The test is an ordinary JUnit test, so it
runs in `mvn test` and in your CI like every other test.

### What is compared

The entities are read the same way `migrax generate` reads them: through Hibernate, EclipseLink
or OpenJPA when the project has one, otherwise from the JPA annotations. Every table, column,
primary key, foreign key and check constraint the entities need must be in the database, with
a matching type and nullability.

The database may contain more than the entities need: tables that no entity maps, and nullable
columns that no entity maps, are fine. Other code may use them. A NOT NULL column without a
default that no entity maps is reported, because inserting an entity would fail.

### Which database

By default the test runs on a new H2 database in memory, which needs H2 on the test classpath.
This works when your migrations are written for H2.

For the database you use in production, mark a static field with `@MigraxDatabase`. The field
can hold a `DataSource` or a Testcontainers JDBC container. A container that isn't running is
started:

```java
@MigraxTest
class OrderRepositoryTest {

  @MigraxDatabase
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

  @Test
  void findsOrders(DataSource dataSource) { ... }
}
```

You can also give a JDBC URL: `@MigraxTest(url = "jdbc:postgresql://localhost:5432/test",
user = "test", password = "test")`. Use a database that only tests use.

### Settings

| Setting | Default | Meaning |
|---|---|---|
| `entities` | `migrax.package`, else the Maven groupId | Package that holds the entities |
| `locations` | `db/migration` | Migration folders on the classpath; `filesystem:<path>` for folders on disk |
| `javaPackage` | `db.migration` | Package of `JavaMigration` classes |
| `validate` | `true` | Compare the migrated schema with the entities |
| `rollbacks` | `false` | Also test every rollback script (see below) |
| `schemas` | | Schemas to migrate one after another |
| `env` | | Environment: migrations marked `-- migrax:env <name>` run only there |
| `url`, `user`, `password` | | The test database, when no field is marked `@MigraxDatabase` |

The naming of tables and columns is the one the `migrax` command uses for your project. It is
recorded in `.migrax/snapshot.json`.

### Test the rollback scripts too

With `@MigraxTest(rollbacks = true)`, Migrax rolls each migration back with its rollback script,
applies it again and checks that the schema is the same as before. A broken rollback script
fails the tests and is named:

```text
Rollback scripts: failed: 0002_add_isbn.sql fails when applied again after its rollback script ran: ...
```

This is the same check `migrax verify` runs.

### Parameters

Test methods, constructors and `@BeforeEach` methods can ask for these as parameters:

| Type | What it is |
|---|---|
| `javax.sql.DataSource` | The migrated test database |
| `org.migrax.api.Migrax` | The Java API for this database (below) |
| `org.migrax.api.Migrax.MigrateResult` | How many migrations were applied |

The database is migrated once per test class. `@Nested` classes share it with the class around
them.

## Run Migrax from Java

The `migrax` library (`org.migrax:migrax`) has a Java API. Use it when your application starts
some other way than the framework integrations, or in tools of your own:

```java
import org.migrax.api.Migrax;

Migrax migrax = Migrax.configure()
    .dataSource(dataSource)
    .locations("db/migration")
    .load();

int applied = migrax.migrate().applied();
```

| Method | What it does |
|---|---|
| `migrate()` | Applies the pending migrations; returns how many, per schema |
| `status()` | The state of every migration (applied, pending, failed, changed, ...) without changing anything; `upToDate()` tells if nothing is pending or wrong |
| `rollback(steps)` | Rolls back the newest migrations with their rollback scripts; returns their names |
| `rollbackTo(version)` | Rolls back every migration after `version` |
| `validate()` | Compares the entities with the database, as described above |
| `testRollbacks()` | Tests every rollback script on the database. Rolling back drops data, so use a test database |

The builder takes the same settings as the startup integrations:

| Builder method | Meaning |
|---|---|
| `dataSource(ds)` or `url(url, user, password)` | The database (required) |
| `locations(...)` | Migration folders: classpath folders, or `filesystem:<path>` |
| `javaPackage(name)` | Package of `JavaMigration` classes (default `db.migration`) |
| `javaMigrations(...)` | `JavaMigration` objects to run as well |
| `schemas(...)` | Schemas to migrate one after another |
| `environment(name)` | Environment for `-- migrax:env` migrations |
| `placeholder(name, value)` | Values for `${name}` placeholders |
| `lockTimeout(duration)` | How long to wait while another instance migrates |
| `resume(true)` | Re-run a failed migration marked `-- migrax:resume-safe` |
| `entities(package)`, `naming(name)` | For `validate()`: the entity package and naming |
| `classLoader(loader)` | Where migrations and JDBC drivers are found |
| `listener(...)` | Listeners for metrics and tracing ([Metrics and tracing](observability.md)) |
| `settings(map)` | `migrax.*` settings, as in `application.properties` |

Each method call opens its own connection and closes it again.
