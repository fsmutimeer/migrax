package org.migrax.spring;

import org.migrax.api.MigrationListener;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.util.ClassUtils;

/**
 * The listeners of a migration run: the application's own MigrationListener beans, plus
 * Micrometer metrics and OpenTelemetry spans when the application has a MeterRegistry or an
 * OpenTelemetry bean. Looked up when the migrations run, so the order of auto-configurations
 * doesn't matter.
 */
final class MigraxListeners {
  private MigraxListeners() {}

  static List<MigrationListener> find(ListableBeanFactory beans, MigraxProperties properties,
                                      ClassLoader loader) {
    List<MigrationListener> listeners = new ArrayList<>(
        beans.getBeanProvider(MigrationListener.class).orderedStream().toList());
    if (properties.getMetrics().isEnabled()
        && ClassUtils.isPresent("io.micrometer.core.instrument.MeterRegistry", loader)) {
      Metrics.add(beans, listeners);
    }
    if (properties.getTracing().isEnabled()
        && ClassUtils.isPresent("io.opentelemetry.api.OpenTelemetry", loader)) {
      Tracing.add(beans, listeners);
    }
    return listeners;
  }

  /** Loaded only when Micrometer is on the class path. */
  private static final class Metrics {
    static void add(ListableBeanFactory beans, List<MigrationListener> listeners) {
      io.micrometer.core.instrument.MeterRegistry registry = beans
          .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class).getIfUnique();
      if (registry != null) {
        listeners.add(new org.migrax.observe.MicrometerListener(registry));
      }
    }
  }

  /** Loaded only when the OpenTelemetry API is on the class path. */
  private static final class Tracing {
    static void add(ListableBeanFactory beans, List<MigrationListener> listeners) {
      io.opentelemetry.api.OpenTelemetry openTelemetry = beans
          .getBeanProvider(io.opentelemetry.api.OpenTelemetry.class).getIfUnique();
      if (openTelemetry != null) {
        listeners.add(new org.migrax.observe.OpenTelemetryListener(openTelemetry));
      }
    }
  }
}
