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
| `migrax.run` | `migrax_run_seconds` | `outcome` (`success`, `failure`) | Each migration run at startup |
| `migrax.migration` | `migrax_migration_seconds` | `migration` (file or class name), `outcome` | Each migration |
| `migrax.migrations.applied` | `migrax_migrations_applied_total` | | Migrations applied |
| `migrax.migrations.failed` | `migrax_migrations_failed_total` | | Migrations that failed |
| `migrax.lock.wait` | `migrax_lock_wait_seconds` | | How long the migration lock took to get |

A Prometheus alert on failed migrations, for example:

```yaml
- alert: MigraxMigrationFailed
  expr: increase(migrax_migrations_failed_total[15m]) > 0
```

With several instances starting together and `migrax.lock-timeout` set, `migrax_lock_wait_seconds`
shows how long they waited for each other.

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
