package org.migrax.micronaut;

import io.micronaut.context.BeanContext;
import org.migrax.api.MigrationListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The listeners of a migration run: the application's MigrationListener beans, plus
 * Micrometer metrics and OpenTelemetry spans when it has a MeterRegistry or an OpenTelemetry
 * bean, unless {@code migrax.metrics.enabled} or {@code migrax.tracing.enabled} is false.
 */
final class MigraxListeners {
  private MigraxListeners() {}

  static List<MigrationListener> find(BeanContext context, Map<String, String> settings,
                                      ClassLoader loader, String database) {
    List<MigrationListener> listeners =
        new ArrayList<>(context.getBeansOfType(MigrationListener.class));
    if (!"false".equalsIgnoreCase(settings.get("migrax.metrics.enabled"))
        && present("io.micrometer.core.instrument.MeterRegistry", loader)) {
      Metrics.add(context, listeners, database);
    }
    if (!"false".equalsIgnoreCase(settings.get("migrax.tracing.enabled"))
        && present("io.opentelemetry.api.OpenTelemetry", loader)) {
      Tracing.add(context, listeners, database);
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

  /** Loaded only when Micrometer is on the class path. */
  private static final class Metrics {
    static void add(BeanContext context, List<MigrationListener> listeners, String database) {
      context.findBean(io.micrometer.core.instrument.MeterRegistry.class).ifPresent(registry ->
          listeners.add(new org.migrax.observe.MicrometerListener(registry, database)));
    }
  }

  /** Loaded only when the OpenTelemetry API is on the class path. */
  private static final class Tracing {
    static void add(BeanContext context, List<MigrationListener> listeners, String database) {
      context.findBean(io.opentelemetry.api.OpenTelemetry.class).ifPresent(openTelemetry ->
          listeners.add(new org.migrax.observe.OpenTelemetryListener(openTelemetry, database)));
    }
  }
}
