package org.migrax.quarkus;

import io.micrometer.core.instrument.MeterRegistry;
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
 * Records the metrics of the migrations at startup and hands them to the MeterRegistry once the
 * application has started, when Quarkus has attached its registries; then adds the gauge of
 * pending migrations of each database. Only in applications with quarkus-micrometer.
 */
@Singleton
public class MigraxMetricsReplay implements MigraxListeners.Source {
  @Override
  public MigrationListener listener(Map<String, String> settings, String database) {
    return MigraxListeners.enabled(settings, "migrax.metrics.enabled")
        && Arc.container().select(MeterRegistry.class).isResolvable()
        ? MigraxListeners.STARTUP_METRICS.computeIfAbsent(database, d -> new RecordingListener())
        : null;
  }

  void onStart(@Observes StartupEvent event, MeterRegistry registry) {
    MigraxListeners.STARTUP_METRICS.forEach((database, recorded) ->
        recorded.replayTo(new MicrometerListener(registry, database)));
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
}
