package org.migrax.api;

import java.time.Duration;

/**
 * Hears what a migration run does, for metrics and tracing. Every method has an empty default;
 * an exception a listener throws is logged and never stops a migration.
 *
 * <p>{@code org.migrax.observe.MicrometerListener} and
 * {@code org.migrax.observe.OpenTelemetryListener} record Micrometer metrics and OpenTelemetry
 * spans; the framework integrations add them when the application has a {@code MeterRegistry}
 * or an {@code OpenTelemetry}.
 *
 * @since 0.13.0
 */
public interface MigrationListener {

  /** A run starts: the lock is taken, pending migrations are about to be applied. */
  default void runStarted() {
  }

  /** The migration lock was acquired after {@code waited} (zero when it was free). */
  default void lockAcquired(Duration waited) {
  }

  /** One migration starts. */
  default void migrationStarted(String version) {
  }

  /** One migration was applied and recorded. */
  default void migrationApplied(String version, Duration duration) {
  }

  /** One migration failed; the run stops. */
  default void migrationFailed(String version, Duration duration, Throwable error) {
  }

  /**
   * The run ended.
   *
   * @param applied migrations applied in this run
   * @param error what stopped it, or null when it succeeded
   */
  default void runFinished(int applied, Duration duration, Throwable error) {
  }
}
