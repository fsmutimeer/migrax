---
seo_title: "Migration metrics and tracing"
description: "Micrometer metrics and OpenTelemetry traces for database migrations, plus a pending-migrations gauge, in Spring Boot, Quarkus, Micronaut, Helidon and Jakarta EE."
---
# Metrics and tracing

New in Migrax 0.13.0.

When an application migrates at startup, Migrax reports what happened to the monitoring tools
the application already uses: Micrometer metrics (Prometheus, Grafana, Datadog, ...) and
OpenTelemetry traces (Jaeger, Tempo, ...). Dashboards and alerts then show how long migrations
take, which one failed, and how long an instance waited while another one migrated.

There is nothing to set up: the startup integrations for Spring Boot, Quarkus, Micronaut,
Helidon and Jakarta EE record them when the application has a `MeterRegistry` (Micrometer) or
an `OpenTelemetry` bean. Without them, nothing changes.

## Metrics

| Metric | Prometheus name | Tags | What it measures |
|---|---|---|---|
| `migrax.run` | `migrax_run_seconds` | `outcome` (`success`, `failure`), `database` | Each migration run at startup |
| `migrax.migration` | `migrax_migration_seconds` | `migration` (file or class name), `outcome`, `database` | Each migration |
| `migrax.migrations.applied` | `migrax_migrations_applied_total` | `database` | Migrations applied |
| `migrax.migrations.failed` | `migrax_migrations_failed_total` | `database` | Migrations that failed |
| `migrax.lock.wait` | `migrax_lock_wait_seconds` | `database` | How long the migration lock took to get |
| `migrax.migrations.pending` | `migrax_migrations_pending` | `database` | Migrations that wait to run (from Migrax 0.14.0) |

The tag `database` (from Migrax 0.15.0) is `default`, or the name of a further database in an
application with [several databases](several-databases.md). Queries without it add the
databases up.

A Prometheus alert on failed migrations, for example:

```yaml
- alert: MigraxMigrationFailed
  expr: increase(migrax_migrations_failed_total[15m]) > 0
```

With several instances starting together and `migrax.lock-timeout` set, `migrax_lock_wait_seconds`
shows how long they waited for each other.

`migrax_migrations_pending` is the count the health check reports. An application that
migrates at startup only starts when nothing is pending, so there it stays 0; it shows what
waits when a pipeline migrates (below). Migrax asks the database at most every 10 seconds;
when the database can't be reached, the gauge has no value (NaN).

## When a pipeline migrates

New in Migrax 0.14.0.

Some teams run the migrations in a deployment pipeline or have a DBA run them, and the
application must never change the schema. Set `migrax.migrate-at-startup=false`: the
application then starts without migrating and logs what waits:

```text
Migrax: not migrating at startup (migrax.migrate-at-startup=false); 3 migration(s) pending
```

The health check and `migrax_migrations_pending` keep reporting, so a release whose
migrations haven't run yet shows up: readiness is DOWN, and an alert can fire:

```yaml
- alert: MigraxMigrationsPending
  expr: migrax_migrations_pending > 0
  for: 30m
```

`migrax.enabled=false` instead turns Migrax off completely, health check and metrics included.

## Traces

Each run is a span named `migrax migrate` (under the current span, when the startup has one),
with one `migrax migration` span per migration under it:

| Span | Attributes |
|---|---|
| `migrax migrate` | `migrax.applied` (migrations applied), `migrax.lock.wait_ms` |
| `migrax migration` | `migrax.migration` (file or class name) |

A failed migration marks both spans as errors and records the exception, so the trace shows
which migration stopped the startup.

## By framework

| Framework | Metrics | Traces |
|---|---|---|
| Spring Boot | Actuator's `MeterRegistry` | The `OpenTelemetry` bean (Micrometer Tracing with the OpenTelemetry bridge) |
| Quarkus | `quarkus-micrometer` | `quarkus-opentelemetry` |
| Micronaut | `micronaut-micrometer` | An `OpenTelemetry` bean (`micronaut-tracing-opentelemetry`) |
| Helidon MP, Jakarta EE | A `MeterRegistry` CDI bean | An `OpenTelemetry` CDI bean (MicroProfile Telemetry) |

On Quarkus, the metrics of the migrations at startup reach the registry once the application
has started, because Quarkus attaches its registries after the migrations run.

Turn either off with `migrax.metrics.enabled=false` or `migrax.tracing.enabled=false`.

## Your own listener

A `MigrationListener` bean of the application hears every run too: when it starts, how long the
lock took, each migration applied or failed, and when it ends. Use it to send the events
somewhere else, such as an audit log:

```java
@Component   // or a CDI bean in Quarkus, Micronaut, Helidon and Jakarta EE
class MigrationAudit implements MigrationListener {
  @Override
  public void migrationApplied(String version, Duration duration) {
    audit.record("migration " + version + " applied in " + duration.toMillis() + " ms");
  }
}
```

With the [Java API](java-api-and-tests.md#run-migrax-from-java), pass listeners to the builder:

```java
Migrax.configure()
    .dataSource(dataSource)
    .listener(new MicrometerListener(meterRegistry), new OpenTelemetryListener(openTelemetry))
    .load()
    .migrate();
```

A listener that throws an exception is logged and never stops a migration.
