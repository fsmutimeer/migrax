package org.migrax.quarkus;

import io.quarkus.arc.Arc;
import org.migrax.api.MigrationListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The listeners of a migration run: the application's MigrationListener beans, plus Micrometer
 * metrics and OpenTelemetry spans (quarkus-micrometer, quarkus-opentelemetry), unless
 * {@code migrax.metrics.enabled} or {@code migrax.tracing.enabled} is false.
 *
 * <p>This class never names a Micrometer or OpenTelemetry type: a native build reads every
 * method it can reach and fails on a missing class. The {@link Source} beans that do are added
 * at build time, only when the application has the extension.
 */
final class MigraxListeners {
  private MigraxListeners() {}

  /** A bean adding a listener when its extension is in the application. */
  interface Source {
    /** The listener of one database, or null when it is off or its backend is missing. */
    MigrationListener listener(Map<String, String> settings, String database);
  }

  /**
   * The metrics of the runs during startup, per database, until {@link MigraxMetricsReplay}
   * hands them to the registry: Quarkus may attach its registries after the migrations ran.
   */
  static final Map<String, org.migrax.observe.RecordingListener> STARTUP_METRICS =
      new java.util.concurrent.ConcurrentHashMap<>();

  static List<MigrationListener> find(Map<String, String> settings, String database) {
    List<MigrationListener> listeners = new ArrayList<>();
    if (Arc.container() == null) {
      return listeners;
    }
    Arc.container().select(MigrationListener.class).forEach(listeners::add);
    for (Source source : Arc.container().select(Source.class)) {
      MigrationListener listener = source.listener(settings, database);
      if (listener != null) {
        listeners.add(listener);
      }
    }
    return listeners;
  }

  static boolean enabled(Map<String, String> settings, String key) {
    return !"false".equalsIgnoreCase(settings.get(key));
  }
}
