package org.migrax.observe;

import org.migrax.api.MigrationListener;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The listeners of a migration run in a CDI container (Helidon MP, Jakarta EE servers): the
 * application's MigrationListener beans, plus Micrometer metrics and OpenTelemetry spans when it
 * has a MeterRegistry or an OpenTelemetry bean, unless {@code migrax.metrics.enabled} or
 * {@code migrax.tracing.enabled} is false.
 *
 * @since 0.13.0
 */
public final class CdiListeners {
  private CdiListeners() {}

  /**
   * @param settings the {@code migrax.*} settings, by full key
   * @param loader the application's class loader, to tell whether Micrometer and OpenTelemetry
   *     are there
   */
  public static List<MigrationListener> find(Map<String, String> settings, ClassLoader loader) {
    List<MigrationListener> listeners = new ArrayList<>();
    CDI<Object> cdi;
    try {
      cdi = CDI.current();
    } catch (IllegalStateException e) {
      return listeners;
    }
    cdi.select(MigrationListener.class).forEach(listeners::add);
    if (!"false".equalsIgnoreCase(settings.get("migrax.metrics.enabled"))
        && present("io.micrometer.core.instrument.MeterRegistry", loader)) {
      Metrics.add(cdi, listeners);
    }
    if (!"false".equalsIgnoreCase(settings.get("migrax.tracing.enabled"))
        && present("io.opentelemetry.api.OpenTelemetry", loader)) {
      Tracing.add(cdi, listeners);
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
    static void add(CDI<Object> cdi, List<MigrationListener> listeners) {
      Instance<io.micrometer.core.instrument.MeterRegistry> registry =
          cdi.select(io.micrometer.core.instrument.MeterRegistry.class);
      if (registry.isResolvable()) {
        listeners.add(new MicrometerListener(registry.get()));
      }
    }
  }

  /** Loaded only when the OpenTelemetry API is on the class path. */
  private static final class Tracing {
    static void add(CDI<Object> cdi, List<MigrationListener> listeners) {
      Instance<io.opentelemetry.api.OpenTelemetry> openTelemetry =
          cdi.select(io.opentelemetry.api.OpenTelemetry.class);
      if (openTelemetry.isResolvable()) {
        listeners.add(new OpenTelemetryListener(openTelemetry.get()));
      }
    }
  }
}
