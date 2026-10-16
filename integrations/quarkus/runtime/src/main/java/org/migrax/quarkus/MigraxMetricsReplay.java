package org.migrax.quarkus;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableInstance;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.ConfigProvider;
import org.migrax.api.MigrationListener;
import org.migrax.observe.MicrometerListener;
import org.migrax.observe.PendingGauge;
import org.migrax.runner.StartupMigrations;

import java.util.Map;

/**
 * Records the metrics of the migrations at startup and hands them to the MeterRegistry once the
 * application has started, when Quarkus has attached its registries; then adds the gauge of
 * pending migrations. Only in applications with quarkus-micrometer.
 */
@Singleton
public class MigraxMetricsReplay implements MigraxListeners.Source {
  @Override
  public MigrationListener listener(Map<String, String> settings) {
    return MigraxListeners.enabled(settings, "migrax.metrics.enabled")
        && Arc.container().select(MeterRegistry.class).isResolvable()
        ? MigraxListeners.STARTUP_METRICS : null;
  }

  void onStart(@Observes StartupEvent event, MeterRegistry registry) {
    MigraxListeners.STARTUP_METRICS.replayTo(new MicrometerListener(registry));
    Map<String, String> settings = MigraxStartup.settings(ConfigProvider.getConfig());
    InjectableInstance<DataSource> dataSource = Arc.container().select(DataSource.class);
    if (MigraxListeners.enabled(settings, "migrax.metrics.enabled")
        && dataSource.isResolvable()) {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      new PendingGauge(() -> StartupMigrations.status(dataSource.get(), settings, loader)
          .pending()).bindTo(registry);
    }
  }
}
