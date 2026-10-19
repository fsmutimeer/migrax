package org.migrax.observe;

import org.migrax.api.MigrationListener;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/**
 * Records Micrometer metrics for migration runs (Prometheus names in brackets):
 * <ul>
 *   <li>{@code migrax.run} ({@code migrax_run_seconds}): each run, with {@code outcome}
 *       {@code success} or {@code failure};</li>
 *   <li>{@code migrax.migration} ({@code migrax_migration_seconds}): each migration, with its
 *       {@code migration} name and {@code outcome};</li>
 *   <li>{@code migrax.migrations.applied} and {@code migrax.migrations.failed}: counters;</li>
 *   <li>{@code migrax.lock.wait} ({@code migrax_lock_wait_seconds}): the wait for the
 *       migration lock.</li>
 * </ul>
 * Every meter has the tag {@code database}: {@code default}, or the name of a further database
 * ({@code migrax.databases.<name>}, since 0.15.0). Needs Micrometer on the class path; the
 * framework integrations add it when the application has a {@code MeterRegistry}.
 *
 * @since 0.13.0
 */
public final class MicrometerListener implements MigrationListener {
  private final MeterRegistry registry;
  private final String database;

  public MicrometerListener(MeterRegistry registry) {
    this(registry, org.migrax.runner.Databases.DEFAULT);
  }

  /**
   * Metrics of one database.
   *
   * @since 0.15.0
   */
  public MicrometerListener(MeterRegistry registry, String database) {
    this.registry = registry;
    this.database = database;
  }

  @Override
  public void lockAcquired(Duration waited) {
    Timer.builder("migrax.lock.wait")
        .description("How long the migration lock took to get").tag("database", database)
        .register(registry).record(waited);
  }

  @Override
  public void migrationApplied(String version, Duration duration) {
    migration(version, "success").record(duration);
    Counter.builder("migrax.migrations.applied").description("Migrations applied")
        .tag("database", database).register(registry).increment();
  }

  @Override
  public void migrationFailed(String version, Duration duration, Throwable error) {
    migration(version, "failure").record(duration);
    Counter.builder("migrax.migrations.failed").description("Migrations that failed")
        .tag("database", database).register(registry).increment();
  }

  @Override
  public void runFinished(int applied, Duration duration, Throwable error) {
    Timer.builder("migrax.run").description("Migration runs")
        .tag("outcome", error == null ? "success" : "failure").tag("database", database)
        .register(registry).record(duration);
  }

  private Timer migration(String version, String outcome) {
    return Timer.builder("migrax.migration").description("Migrations, one by one")
        .tag("migration", version).tag("outcome", outcome).tag("database", database)
        .register(registry);
  }
}
