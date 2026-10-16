package org.migrax.quarkus;

import org.migrax.runner.StartupMigrations;
import org.migrax.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;

/**
 * Applies Migrax migrations when a Quarkus application starts. The extension's build step
 * calls it through {@link MigraxRecorder} before Hibernate ORM starts, so
 * {@code quarkus.hibernate-orm.schema-management.strategy=validate} checks the migrated schema.
 *
 * <p>Settings: {@code migrax.enabled} (default true), {@code migrax.locations} (default
 * {@code db/migration}), {@code migrax.java-package}, {@code migrax.resume},
 * {@code migrax.schemas} and {@code migrax.placeholders.<name>}.
 */
public final class MigraxStartup {
  private static final Logger LOG = Logger.getLogger("org.migrax");

  private MigraxStartup() {}

  /**
   * Hibernate's own schema changes (update, create) would change the schema after the
   * migrations, outside them. none and validate leave the schema to Migrax.
   */
  static void warnAboutSchemaManagement(Config config) {
    for (String key : List.of("quarkus.hibernate-orm.schema-management.strategy",
        "quarkus.hibernate-orm.database.generation")) {
      String value = config.getOptionalValue(key, String.class).orElse("none").trim();
      if (!value.equalsIgnoreCase("none") && !value.equalsIgnoreCase("validate")) {
        LOG.warn("Migrax: " + key + "=" + value + " lets Hibernate change the schema outside "
            + "the migrations. Set it to validate or none.");
      }
    }
  }

  /** Runs the migrations; separate from the event so it can be called directly. */
  public static int migrate(DataSource dataSource, Config config, ClassLoader loader)
      throws Exception {
    Map<String, String> settings = settings(config);
    if (!StartupMigrations.migratesAtStartup(settings)) {
      LOG.info("Migrax: " + StartupMigrations.notMigrating(dataSource, settings, loader));
      return 0;
    }
    Log.Sink previous = Log.setSink(new JbossSink());
    try {
      int applied = StartupMigrations.migrate(dataSource, settings, loader,
          MigraxListeners.find(settings));
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
