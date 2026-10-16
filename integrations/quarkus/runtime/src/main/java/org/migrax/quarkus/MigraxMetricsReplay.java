package org.migrax.quarkus;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.migrax.observe.MicrometerListener;

/**
 * Hands the metrics of the migrations at startup to the MeterRegistry once the application has
 * started, when Quarkus has attached its registries. Only in applications with
 * quarkus-micrometer.
 */
@ApplicationScoped
public class MigraxMetricsReplay {
  void onStart(@Observes StartupEvent event, MeterRegistry registry) {
    MigraxListeners.STARTUP_METRICS.replayTo(new MicrometerListener(registry));
  }
}
