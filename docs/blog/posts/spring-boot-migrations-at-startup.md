---
date: 2026-11-08T06:19:00
slug: spring-boot-migrations-at-startup
authors:
  - feroz
categories:
  - Frameworks
tags:
  - Spring Boot
  - Actuator
  - Prometheus
  - PostgreSQL
description: >-
  Apply database migrations when a Spring Boot application starts, before JPA, and see them
  in Actuator: a health check, Prometheus metrics, several instances starting together, and
  what changes when a pipeline runs the migrations instead.
---

# Spring Boot: migrations at startup, health checks and metrics

Migrations are written in development, but they have to run wherever the application runs:
on a colleague's laptop, in CI, in staging, in production. The simplest way to get there is to
let the application apply them itself, each time it starts, before anything else touches the
database.

This article adds the Migrax starter to a Spring Boot application, follows what happens at
startup, starts two instances at once, and looks at what Actuator shows: a health check and
Prometheus metrics. At the end, the pipeline runs the migrations instead of the application.

<!-- more -->

!!! info "Tested with"

    Migrax 0.17.1, Spring Boot 3.5.7, Hibernate 6.6.33, Micrometer 1.15.5, PostgreSQL 17.
    Every output below was copied from a real run; log lines are shortened to the message.

## The setup

A shop with two entities, `Customer` and `PurchaseOrder`. Besides the usual web and JPA
starters, the application has Actuator, the Prometheus registry and the Migrax starter:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
  <groupId>org.migrax</groupId>
  <artifactId>migrax-spring-boot-starter</artifactId>
  <version>0.17.1</version>
</dependency>
```

`application.properties` has the database, Hibernate set to only check the schema, and the
two Actuator endpoints used below:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/shop
spring.datasource.username=shop
spring.datasource.password=${DB_PASSWORD}
spring.jpa.hibernate.ddl-auto=validate

management.endpoints.web.exposure.include=health,prometheus
management.endpoint.health.show-details=always
```

The migrations come from the entities. `migrax init` sets up the project (it reads the
database settings from `application.properties`), and `generate` writes the first migration:

```console
$ migrax generate
No snapshot yet: using the current database schema as the baseline.
Created src/main/resources/db/migration/0001_initial.sql:
  - create table customers
  - create table purchase_orders
  - add foreign key fk_purchase_orders_cu_1ba47d9c on purchase_orders
Rollback script written to src/main/resources/db/migration/rollback.
Review the SQL, then run 'migrax migrate'.
```

The file sits in `src/main/resources/db/migration`, so it is packaged into the jar. That is
where the starter looks.

## What happens at startup

Start the application on the empty database:

```text
HikariPool-1 - Start completed.
Migrax: Applying migration '0001_initial.sql'...
Migrax: Successfully applied migration '0001_initial.sql'.
Migrax: applied 1 migration(s)
Tomcat started on port 8080 (http) with context path '/'
Started ShopApplication in 9.05 seconds (process running for 10.302)
```

The order matters. The starter runs as soon as the connection pool is there and **before**
JPA starts, so when Hibernate validates the schema, the tables already exist. With
`ddl-auto=validate`, a database that doesn't match the entities stops the start, instead of
failing on the first request.

Start it again and there is nothing to do:

```text
Migrax: schema is up to date
Started ShopApplication in 8.571 seconds (process running for 9.58)
```

The application never needs the `migrax` command. It reads the migration files from the jar
and records each one in the history table in the database, with a checksum.

## Several instances starting together

A new field on `Customer`:

```java
@Column(nullable = false, length = 2)
@ColumnDefault("'GB'")
private String country = "GB";
```

`migrax generate` writes `0002_add_customers_country.sql`:

```sql
ALTER TABLE customers ADD COLUMN country varchar(2) DEFAULT 'GB' NOT NULL;
```

In production, the new version rarely starts alone: two or more instances start at the same
moment. Only one of them may apply the migration. Migrax takes a lock in the database first
(on PostgreSQL an advisory lock), and by default an instance that doesn't get it stops at
once. Here are the two instances, started together:

```text
--- instance 1
Migrax: Applying migration '0002_add_customers_country.sql'...
Migrax: Successfully applied migration '0002_add_customers_country.sql'.
Migrax: applied 1 migration(s)
Started ShopApplication in 10.515 seconds (process running for 11.809)

--- instance 2
Caused by: org.migrax.dialect.MigrationLockHeldException: Another Migrax process is applying migrations.
```

Nothing is broken: the migration ran once. But the second instance failed and has to be
restarted, and an orchestrator counts that as a crash. Tell the instances to wait for each
other instead:

```properties
migrax.lock-timeout=2m
```

To make the wait visible, the next run has more to do. The table now has two million
customers, and an index on `full_name` is added to the entity (`@Table(indexes =
@Index(columnList = "fullName"))`). `generate` writes it as `0003_index_customers.sql`, and
warns about it:

```text
src/main/resources/db/migration/0003_index_customers.sql, statement 1: warning MX001
  CREATE INDEX idx_customers_full_name ON customers (full_name)
  Building this index blocks writes to customers until it finishes.
  Fix: Use CREATE INDEX CONCURRENTLY in its own migration that starts with '-- migrax:no-transaction' ('migrax generate --safe' does this).
```

Worth knowing on a busy table. Here it is fine. Both instances start again, with `0002` and
`0003` pending (`0002` was rolled back to run again):

```text
--- instance 1
Migrax: Applying migration '0002_add_customers_country.sql'...
Migrax: Successfully applied migration '0002_add_customers_country.sql'.
Migrax: Applying migration '0003_index_customers.sql'...
Migrax: Successfully applied migration '0003_index_customers.sql'.
Migrax: applied 2 migration(s)
Started ShopApplication in 17.185 seconds (process running for 18.461)

--- instance 2
Migrax: Another Migrax process holds the migration lock; waiting up to 2m...
Migrax: schema is up to date
Started ShopApplication in 18.851 seconds (process running for 20.138)
```

The second instance waited until the first had built the index, found nothing left to do,
and started. Set the timeout longer than your slowest migration takes; when it runs out, the
instance stops as before.

## The health check

With Actuator in the application, the starter adds a `migrax` health component:

```console
$ curl -s localhost:8080/actuator/health/migrax
{"status":"UP","details":{"applied":3,"pending":0,"problems":0}}
```

`problems` counts migrations that failed, were edited after they were applied, or are in the
history but no longer in the jar. Any of those, or a pending migration, makes the component
DOWN. Turn it off with `management.health.migrax.enabled=false`.

An edited migration is the one people run into. Someone changes `varchar(20)` to
`varchar(30)` in a migration that production has already applied, and the next start stops:

```text
Caused by: org.springframework.beans.factory.BeanCreationException: Error creating bean with name 'migraxMigrator' defined in class path resource [org/migrax/spring/MigraxAutoConfiguration.class]: Checksum changed for applied migration 0004_add_purchase_orders_status.sql
```

The database already has the old version of that migration, and it never gets the new
one. Leave applied migrations alone; a change goes into a new migration.

## Metrics

The application has a Micrometer registry (here Prometheus), so Migrax records its
migrations there without any setting. After the run above, the first instance shows:

```console
$ curl -s localhost:8080/actuator/prometheus | grep ^migrax
migrax_lock_wait_seconds_max{database="default"} 0.044737607
migrax_migration_seconds_max{database="default",migration="0002_add_customers_country.sql",outcome="success"} 0.005012614
migrax_migration_seconds_max{database="default",migration="0003_index_customers.sql",outcome="success"} 6.10576425
migrax_migrations_applied_total{database="default"} 2.0
migrax_migrations_pending{database="default"} 0.0
migrax_run_seconds_max{database="default",outcome="success"} 6.212559502
```

(Only the `_max` lines of each timer are shown; `_count` and `_sum` are there too.) The
second instance, the one that waited:

```text
migrax_lock_wait_seconds_max{database="default"} 7.803599713
migrax_run_seconds_max{database="default",outcome="success"} 7.843474718
```

That answers the questions that come up after a slow deployment: which migration took the
time (the index, 6.1 seconds), and how long the other instances waited for it (7.8 seconds).
For an alert, the counter of failed migrations is the one to watch:

```yaml
- alert: MigraxMigrationFailed
  expr: increase(migrax_migrations_failed_total[15m]) > 0
```

With an `OpenTelemetry` bean, each run and each migration also becomes a span in the trace
of the startup. `migrax.metrics.enabled=false` and `migrax.tracing.enabled=false` turn them
off.

## When a pipeline migrates

Some teams don't let the application change the schema. A deployment pipeline or a DBA runs
the migrations, and the application only reads. One setting:

```properties
migrax.migrate-at-startup=false
```

The order then becomes the pipeline's job. A new `status` column on `PurchaseOrder`, deployed
before the pipeline has migrated, doesn't get far:

```text
Migrax: not migrating at startup (migrax.migrate-at-startup=false); 1 migration(s) pending
Caused by: org.hibernate.tool.schema.spi.SchemaManagementException: Schema-validation: missing column [status] in table [purchase_orders]
```

That is `ddl-auto=validate` doing its job. So: run `migrax migrate` (or the Maven goal
`migrax:migrate`) as a step before the new version starts:

```console
$ migrax migrate
Applying migration '0004_add_purchase_orders_status.sql'...
Successfully applied migration '0004_add_purchase_orders_status.sql'.
Applied 1 migration(s); 4 total in src/main/resources/db/migration.
```

Not every pending migration stops the start. An index isn't checked by Hibernate's
validation, so a release with a forgotten index migration (`0005`) starts fine, and is slow.
That is where the health check and the pending gauge come in, because they keep working when
the application doesn't migrate:

```console
$ curl -s -w ' HTTP %{http_code}\n' localhost:8080/actuator/health/migrax
{"status":"DOWN","details":{"applied":4,"pending":1,"problems":0}} HTTP 503

$ curl -s localhost:8080/actuator/prometheus | grep ^migrax
migrax_migrations_pending{database="default"} 1.0
```

An alert on the gauge catches a pipeline step that was skipped:

```yaml
- alert: MigraxMigrationsPending
  expr: migrax_migrations_pending > 0
  for: 30m
```

To keep traffic away from such an instance on Kubernetes, add the component to the readiness
group:

```properties
management.endpoint.health.probes.enabled=true
management.endpoint.health.group.readiness.include=readinessState,migrax
```

Once the pipeline has run `migrax migrate`, the application notices on its own. It asks the
database at most every 10 seconds, so no restart is needed:

```console
$ curl -s -w ' HTTP %{http_code}\n' localhost:8080/actuator/health/migrax
{"status":"UP","details":{"applied":5,"pending":0,"problems":0}} HTTP 200
```

## Summary

- Add `migrax-spring-boot-starter`, keep the migrations in `src/main/resources/db/migration`,
  and the application applies them each time it starts, before JPA, so
  `ddl-auto=validate` checks the migrated schema.
- Several instances: set `migrax.lock-timeout` (for example `2m`). One instance migrates, the
  others wait and then start.
- With Actuator, the `migrax` health component is DOWN for a pending, failed, edited or
  missing migration. With Micrometer, metrics show how long each migration took and how long
  the instances waited.
- When a pipeline migrates, set `migrax.migrate-at-startup=false`, run `migrax migrate`
  before the new version starts, and alert on `migrax_migrations_pending`.

More in the docs: [Spring Boot](../../frameworks/spring-boot.md), [Metrics and
tracing](../../guides/observability.md) and [Containers and
Kubernetes](../../guides/containers-and-kubernetes.md).
