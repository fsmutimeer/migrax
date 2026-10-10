package io.migrax.quarkus;

import io.migrax.runner.StartupMigrations;
import io.migrax.util.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Applies Migrax migrations from {@code db/migration} when a Quarkus application starts.
 *
 * <p>Settings: {@code migrax.enabled} (default true), {@code migrax.locations} (default
 * {@code db/migration}), {@code migrax.java-package}, {@code migrax.resume},
 * {@code migrax.schemas} and {@code migrax.placeholders.<name>}. Keep
 * {@code quarkus.hibernate-orm.schema-management.strategy} (formerly
 * {@code database.generation}) at {@code none}; Migrax owns the schema.
 */
@ApplicationScoped
public class MigraxStartup {
  private static final Logger LOG = Logger.getLogger("io.migrax");

  @Inject
  Instance<DataSource> dataSource;

  void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event)
      throws Exception {
    Config config = ConfigProvider.getConfig();
    if (!config.getOptionalValue("migrax.enabled", Boolean.class).orElse(true)) {
      return;
    }
    warnAboutSchemaManagement(config);
    if (!dataSource.isResolvable()) {
      LOG.warn("Migrax: no default DataSource; migrations were not applied.");
      return;
    }
    migrate(dataSource.get(), config, Thread.currentThread().getContextClassLoader());
  }

  /**
   * Hibernate starts before StartupEvent, so its schema management runs before Migrax: with
   * update or create it makes the tables first and the migrations then fail with "already
   * exists".
   */
  private static void warnAboutSchemaManagement(Config config) {
    for (String key : List.of("quarkus.hibernate-orm.schema-management.strategy",
        "quarkus.hibernate-orm.database.generation")) {
      String value = config.getOptionalValue(key, String.class).orElse("none").trim();
      if (!value.equalsIgnoreCase("none") && !value.equalsIgnoreCase("validate")) {
        LOG.warn("Migrax: " + key + "=" + value + " lets Hibernate change the schema before "
            + "Migrax runs, so migrations can fail with 'already exists'. Set it to none.");
      }
    }
  }

  /** Runs the migrations; separate from the event so it can be called directly. */
  public static int migrate(DataSource dataSource, Config config, ClassLoader loader)
      throws Exception {
    Map<String, String> settings = settings(config);
    Log.Sink previous = Log.setSink(new JbossSink());
    try {
      int applied = StartupMigrations.migrate(dataSource, settings, loader);
      LOG.info("Migrax: " + (applied == 0 ? "schema is up to date"
          : "applied " + applied + " migration(s)"));
      return applied;
    } finally {
      Log.setSink(previous);
    }
  }

  /** The {@code migrax.*} settings, with their full keys. */
  static Map<String, String> settings(Config config) {
    Map<String, String> settings = new LinkedHashMap<>();
    for (String name : config.getPropertyNames()) {
      if (name.startsWith("migrax.")) {
        config.getOptionalValue(name, String.class).ifPresent(value -> settings.put(name, value));
      }
    }
    return settings;
  }

  /** Sends Migrax core messages to JBoss Logging. */
  private static final class JbossSink implements Log.Sink {
    @Override
    public void debug(String message) {
      LOG.debug("Migrax: " + message);
    }

    @Override
    public void info(String message) {
      LOG.info("Migrax: " + message);
    }

    @Override
    public void warn(String message) {
      LOG.warn("Migrax: " + message);
    }

    @Override
    public void error(String message) {
      LOG.error("Migrax: " + message);
    }
  }
}
