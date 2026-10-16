package org.migrax.quarkus;

import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableInstance;
import jakarta.inject.Singleton;
import org.migrax.api.MigrationListener;
import org.migrax.observe.OpenTelemetryListener;

import java.util.Map;

/** OpenTelemetry spans of migration runs. Only in applications with quarkus-opentelemetry. */
@Singleton
public class MigraxTracing implements MigraxListeners.Source {
  @Override
  public MigrationListener listener(Map<String, String> settings) {
    if (!MigraxListeners.enabled(settings, "migrax.tracing.enabled")) {
      return null;
    }
    InjectableInstance<OpenTelemetry> openTelemetry = Arc.container().select(OpenTelemetry.class);
    return openTelemetry.isResolvable() ? new OpenTelemetryListener(openTelemetry.get()) : null;
  }
}
