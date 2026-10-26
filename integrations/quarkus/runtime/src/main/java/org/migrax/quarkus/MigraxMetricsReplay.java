package org.migrax.quarkus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.quarkus.arc.Arc;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.migrax.api.MigrationListener;
import org.migrax.observe.MicrometerListener;
import org.migrax.observe.PendingGauge;
import org.migrax.observe.RecordingListener;
import org.migrax.runner.Databases;
import org.migrax.runner.StartupMigrations;

import java.util.Map;

/**
 * Records the metrics of the migrations at startup and hands them to the MeterRegistry once
 * Quarkus has attached its registries; then adds the gauge of pending migrations of each
 * database. Only in applications with quarkus-micrometer.
 *
 * <p>Quarkus attaches its registries and then binds the MeterBinder beans, this one included.
 * That can happen before or after the StartupEvent: Quarkus does not order those two startup
 * steps. A counter handed to the root registry before its registries are attached starts at 0
 * in them, so nothing is replayed before {@link #bindTo}.
 */
@Singleton
public class MigraxMetricsReplay implements MigraxListeners.Source, MeterBinder {
  /** The root registry, once Quarkus has attached its registries. */
  private volatile MeterRegistry registry;

  @Override
  public MigrationListener listener(Map<String, String> settings, String database) {
    return MigraxListeners.enabled(settings, "migrax.metrics.enabled")
        && Arc.container().select(MeterRegistry.class).isResolvable()
        ? MigraxListeners.STARTUP_METRICS.computeIfAbsent(database, d -> new RecordingListener())
        : null;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    this.registry = registry;
    replay(registry);
    Config config = ConfigProvider.getConfig();
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    for (String database : MigraxStartup.databases(config)) {
      Map<String, String> settings = Databases.settings(database, MigraxStartup.settings(config));
      DataSource dataSource = MigraxStartup.dataSource(database, config);
      if (MigraxListeners.enabled(settings, "migrax.metrics.enabled") && dataSource != null) {
        new PendingGauge(() -> StartupMigrations.status(dataSource, settings, loader).pending(),
            database).bindTo(registry);
      }
    }
  }

  /** Hands over the runs that ended after {@link #bindTo}; bindTo handed over the others. */
  public void onStart(@Observes StartupEvent event) {
    MeterRegistry attached = registry;
    if (attached != null) {
      replay(attached);
    }
  }

  private static void replay(MeterRegistry registry) {
    MigraxListeners.STARTUP_METRICS.forEach((database, recorded) ->
        recorded.replayTo(new MicrometerListener(registry, database)));
  }
}
