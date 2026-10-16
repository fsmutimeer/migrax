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
 * Needs Micrometer on the class path; the framework integrations add it when the application
 * has a {@code MeterRegistry}.
 *
 * @since 0.13.0
 */
public final class MicrometerListener implements MigrationListener {
  private final MeterRegistry registry;

  public MicrometerListener(MeterRegistry registry) {
    this.registry = registry;
  }

  @Override
  public void lockAcquired(Duration waited) {
    Timer.builder("migrax.lock.wait")
        .description("How long the migration lock took to get")
        .register(registry).record(waited);
  }

  @Override
  public void migrationApplied(String version, Duration duration) {
    migration(version, "success").record(duration);
    Counter.builder("migrax.migrations.applied").description("Migrations applied")
        .register(registry).increment();
  }

  @Override
  public void migrationFailed(String version, Duration duration, Throwable error) {
    migration(version, "failure").record(duration);
    Counter.builder("migrax.migrations.failed").description("Migrations that failed")
        .register(registry).increment();
  }

  @Override
  public void runFinished(int applied, Duration duration, Throwable error) {
    Timer.builder("migrax.run").description("Migration runs")
        .tag("outcome", error == null ? "success" : "failure")
        .register(registry).record(duration);
  }

  private Timer migration(String version, String outcome) {
    return Timer.builder("migrax.migration").description("Migrations, one by one")
        .tag("migration", version).tag("outcome", outcome)
        .register(registry);
  }
}
