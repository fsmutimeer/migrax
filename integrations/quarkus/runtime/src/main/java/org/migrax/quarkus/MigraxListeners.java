package org.migrax.quarkus;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableInstance;
import org.migrax.api.MigrationListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The listeners of a migration run: the application's MigrationListener beans, plus
 * Micrometer metrics and OpenTelemetry spans when it has a MeterRegistry or an OpenTelemetry
 * bean (quarkus-micrometer, quarkus-opentelemetry), unless {@code migrax.metrics.enabled} or
 * {@code migrax.tracing.enabled} is false.
 */
final class MigraxListeners {
  private MigraxListeners() {}

  static List<MigrationListener> find(Map<String, String> settings, ClassLoader loader) {
    List<MigrationListener> listeners = new ArrayList<>();
    if (Arc.container() == null) {
      return listeners;
    }
    Arc.container().select(MigrationListener.class).forEach(listeners::add);
    if (!"false".equalsIgnoreCase(settings.get("migrax.metrics.enabled"))
        && present("io.micrometer.core.instrument.MeterRegistry", loader)) {
      Metrics.add(listeners);
    }
    if (!"false".equalsIgnoreCase(settings.get("migrax.tracing.enabled"))
        && present("io.opentelemetry.api.OpenTelemetry", loader)) {
      Tracing.add(listeners);
    }
    return listeners;
  }

  private static boolean present(String name, ClassLoader loader) {
    try {
      Class.forName(name, false, loader);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /**
   * The metrics of the runs during startup, until {@link MigraxMetricsReplay} hands them to the
   * registry: Quarkus attaches its registries after the migrations ran.
   */
  static final org.migrax.observe.RecordingListener STARTUP_METRICS =
      new org.migrax.observe.RecordingListener();

  /** Loaded only when Micrometer is on the class path. */
  private static final class Metrics {
    static void add(List<MigrationListener> listeners) {
      if (Arc.container().select(io.micrometer.core.instrument.MeterRegistry.class)
          .isResolvable()) {
        listeners.add(STARTUP_METRICS);
      }
    }
  }

  /** Loaded only when the OpenTelemetry API is on the class path. */
  private static final class Tracing {
    static void add(List<MigrationListener> listeners) {
      InjectableInstance<io.opentelemetry.api.OpenTelemetry> openTelemetry =
          Arc.container().select(io.opentelemetry.api.OpenTelemetry.class);
      if (openTelemetry.isResolvable()) {
        listeners.add(new org.migrax.observe.OpenTelemetryListener(openTelemetry.get()));
      }
    }
  }
}
