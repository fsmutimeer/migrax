package org.migrax.quarkus;

import org.migrax.runner.Databases;
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
 * {@code migrax.schemas} and {@code migrax.placeholders.<name>}. Further databases are named
 * data sources ({@code quarkus.datasource.<name>.*}) with a block
 * {@code migrax.databases.<name>.*} (see {@link org.migrax.runner.Databases}).
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

  /** Runs the migrations of the default database; separate so it can be called directly. */
  public static int migrate(DataSource dataSource, Config config, ClassLoader loader)
      throws Exception {
    return migrate(Databases.DEFAULT, dataSource, config, loader);
  }

  /**
   * Runs the migrations of one database: {@code default} or a name from migrax.databases.
   *
   * @since 0.15.0
   */
  public static int migrate(String database, DataSource dataSource, Config config,
                            ClassLoader loader) throws Exception {
    Map<String, String> settings = Databases.settings(database, settings(config));
    String prefix = prefix(database);
    if (!StartupMigrations.migratesAtStartup(settings)) {
      LOG.info(prefix + StartupMigrations.notMigrating(dataSource, settings, loader));
      return 0;
    }
    Log.Sink previous = Log.setSink(new JbossSink());
    try {
      int applied = StartupMigrations.migrate(dataSource, settings, loader,
          MigraxListeners.find(settings, database));
      LOG.info(prefix + (applied == 0 ? "schema is up to date"
          : "applied " + applied + " migration(s)"));
      return applied;
    } finally {
      Log.setSink(previous);
    }
  }

  /** The databases to migrate: default first, then the migrax.databases blocks by name. */
  static List<String> databases(Config config) {
    List<String> names = new java.util.ArrayList<>(List.of(Databases.DEFAULT));
    names.addAll(Databases.names(settings(config)));
    return names;
  }

  /**
   * The data source of a database: the default one, or the named data source
   * ({@code quarkus.datasource.<name>.*}) that migrax.databases.<name>.datasource names;
   * null when there is none.
   */
  static DataSource dataSource(String database, Config config) {
    io.quarkus.arc.InjectableInstance<DataSource> instance = Databases.DEFAULT.equals(database)
        ? io.quarkus.arc.Arc.container().select(DataSource.class)
        : io.quarkus.arc.Arc.container().select(DataSource.class,
            new io.quarkus.agroal.DataSource.DataSourceLiteral(
                Databases.dataSource(database, settings(config))));
    return instance.isResolvable() ? instance.get() : null;
  }

  /** "Migrax: ", or "Migrax (billing): " for a further database. */
  static String prefix(String database) {
    return Databases.DEFAULT.equals(database) ? "Migrax: " : "Migrax (" + database + "): ";
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
