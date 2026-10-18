package org.migrax.observe;

import org.migrax.util.Log;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

/**
 * The gauge {@code migrax.migrations.pending} ({@code migrax_migrations_pending}): how many
 * migrations wait to run, the same count the health checks use. The database is asked at most
 * every 10 seconds, so frequent scrapes don't add load. When it can't be reached, the gauge
 * reads NaN. Its tag {@code database} is {@code default} or the name of a further database
 * (since 0.15.0). The framework integrations bind it when the application has a
 * {@code MeterRegistry}.
 *
 * @since 0.14.0
 */
public final class PendingGauge implements MeterBinder {
  /** Counts the pending migrations; may fail when the database can't be reached. */
  @FunctionalInterface
  public interface Source {
    int pending() throws Exception;
  }

  private final Source source;
  private final String database;
  private final long cacheNanos;
  private double value = Double.NaN;
  private long readAt;
  private boolean read;

  public PendingGauge(Source source) {
    this(source, org.migrax.runner.Databases.DEFAULT);
  }

  /**
   * The gauge of one database.
   *
   * @since 0.15.0
   */
  public PendingGauge(Source source, String database) {
    this(source, database, java.time.Duration.ofSeconds(10));
  }

  PendingGauge(Source source, java.time.Duration cache) {
    this(source, org.migrax.runner.Databases.DEFAULT, cache);
  }

  PendingGauge(Source source, String database, java.time.Duration cache) {
    this.source = source;
    this.database = database;
    this.cacheNanos = cache.toNanos();
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("migrax.migrations.pending", this, PendingGauge::value)
        .description("Migrations that wait to run").tag("database", database)
        .strongReference(true).register(registry);
  }

  synchronized double value() {
    long now = System.nanoTime();
    if (!read || now - readAt >= cacheNanos) {
      try {
        value = source.pending();
      } catch (Exception e) {
        Log.debug("Counting pending migrations failed: {}", e.getMessage());
        value = Double.NaN;
      }
      readAt = now;
      read = true;
    }
    return value;
  }
}
