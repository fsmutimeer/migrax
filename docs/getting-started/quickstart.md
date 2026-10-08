# Quick start

This takes about five minutes. You need a service with JPA entities, a `pom.xml` or
`build.gradle`, and a database it can reach.

Run everything from the service folder, the one with `pom.xml` or `build.gradle`.

## 1. Let Migrax look at your project

```console
$ migrax init
```

`init` creates the migration folder and shows what Migrax detected: entity package, framework,
naming, database and dialect. It changes nothing else and is safe to run again.

## 2. Check everything is ready

```console
$ migrax doctor
  [ok]    Java 21.0.5
  [ok]    Build tool: maven
  [ok]    Entity package: com.example.shop
  [info]  Framework: Spring Boot
  [info]  Naming: spring (must match Hibernate; ...)
  [ok]    Project compiled and dependencies resolved
  [ok]    Hibernate 6.6.13.Final: entities are read from Hibernate's own mapping
  [ok]    Entities: 4 table(s) under com.example.shop (Hibernate 6.6.13.Final mapping)
  [ok]    Database URL: jdbc:postgresql://localhost:5432/shop
  [ok]    Connected: PostgreSQL 16.4
All checks passed.
```

Anything that fails comes with the fix. Warnings explain settings worth changing, for example
when Hibernate is allowed to create tables itself.

!!! warning "Let Migrax own the schema"

    If your configuration lets Hibernate create or update tables at startup
    (`spring.jpa.hibernate.ddl-auto=update`, `quarkus.hibernate-orm.schema-management.strategy=update`
    and similar), set it to `none` or `validate`. `doctor` shows the exact line to change.

## 3. Generate the first migration

```console
$ migrax generate
Created src/main/resources/db/migration/0001_initial.sql:
  - create table customers
  - create table orders
  ...
Rollback script written to src/main/resources/db/migration/rollback.
Review the SQL, then run 'migrax migrate'.
```

The first `generate` compares your entities with the **current database**:

- an empty database gets `0001_initial.sql` with every table;
- a database that already has tables gets only the differences.

Open the file and read it. Migrations are plain SQL, and you can edit them before they run.

## 4. Apply it

```console
$ migrax migrate
Applying migration '0001_initial.sql'...
Successfully applied migration '0001_initial.sql'.

$ migrax status
  [X] 0001_initial.sql   applied 2026-10-08 11:02:05
1 applied.
```

## 5. Commit

Commit these with your code:

- the migration files and the `rollback/` folder in `src/main/resources/db/migration`;
- `.migrax/snapshot.json` and `.migrax/history/`.

Everything else in `.migrax/` is a local cache, and Migrax tells git to ignore it.

## From now on

Every time you change an entity:

```console
$ migrax generate     # writes 0002_..., with a rollback script, linted
$ migrax migrate
```

Next, learn [how Migrax works](../guides/how-it-works.md), or set up your framework:
[Spring Boot](../frameworks/spring-boot.md), [Quarkus](../frameworks/quarkus.md),
[Micronaut](../frameworks/micronaut.md) or [Helidon](../frameworks/helidon.md).
