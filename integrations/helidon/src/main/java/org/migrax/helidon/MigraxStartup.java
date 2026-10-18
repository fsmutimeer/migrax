package org.migrax.helidon;

import org.migrax.runner.Databases;
import org.migrax.runner.StartupMigrations;
import org.migrax.util.Log;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Applies Migrax migrations from {@code db/migration} when a Helidon MP application starts,
 * before application beans that observe the same event.
 *
 * <p>Settings (microprofile-config.properties): {@code migrax.enabled} (default true),
 * {@code migrax.datasource} (default: the data source persistence.xml names, or the only one),
 * {@code migrax.locations} (default {@code db/migration}), {@code migrax.java-package},
 * {@code migrax.resume}, {@code migrax.schemas} and {@code migrax.placeholders.<name>}. Set
 * {@code hibernate.hbm2ddl.auto} in persistence.xml to none or validate; Migrax owns the schema.
 * Further databases are data sources ({@code javax.sql.DataSource.<name>.*}) with a block
 * {@code migrax.databases.<name>.*} (see {@link org.migrax.runner.Databases}).
 */
@ApplicationScoped
public class MigraxStartup {
  private static final Logger LOG = Logger.getLogger("org.migrax");

  @Inject
  @Any
  Instance<DataSource> dataSources;

  void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE)
               @Initialized(ApplicationScoped.class) Object event) throws Exception {
    Config config = ConfigProvider.getConfig();
    if (!config.getOptionalValue("migrax.enabled", Boolean.class).orElse(true)) {
      return;
    }
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    for (String database : databases(config)) {
      String name = dataSourceName(database, config, loader);
      DataSource dataSource = dataSource(name);
      if (dataSource == null) {
        continue;
      }
      migrate(database, name, dataSource, config, loader);
    }
  }

  private void migrate(String database, String name, DataSource dataSource, Config config,
                       ClassLoader loader) throws Exception {
    Map<String, String> settings = Databases.settings(database, settings(config));
    String prefix = Databases.DEFAULT.equals(database) ? "Migrax: "
        : "Migrax (" + database + "): ";
    org.migrax.observe.CdiListeners.pendingGauge(settings, loader,
        () -> StartupMigrations.status(dataSource, settings, loader).pending(), database);
    if (!StartupMigrations.migratesAtStartup(settings)) {
      LOG.info(prefix + StartupMigrations.notMigrating(dataSource, settings, loader));
      return;
    }
    Log.Sink previous = Log.setSink(new JulSink());
    try {
      int applied = StartupMigrations.migrate(dataSource, settings, loader,
          org.migrax.observe.CdiListeners.find(settings, loader, database));
      LOG.info(prefix + (applied == 0 ? "schema is up to date"
          : "applied " + applied + " migration(s)"
              + (name == null ? "" : " to data source '" + name + "'")));
    } finally {
      Log.setSink(previous);
    }
  }

  /**
   * Whether each database is up to date, without changing anything; the readiness check
   * reports it. Uses the same data sources as the migrations.
   */
  Map<String, StartupMigrations.Status> statuses() throws Exception {
    Config config = ConfigProvider.getConfig();
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    Map<String, StartupMigrations.Status> statuses = new LinkedHashMap<>();
    for (String database : databases(config)) {
      DataSource dataSource = dataSource(dataSourceName(database, config, loader));
      if (dataSource == null) {
        throw new IllegalStateException("no data source to check; set migrax.datasource");
      }
      statuses.put(database, StartupMigrations.status(dataSource,
          Databases.settings(database, settings(config)), loader));
    }
    return statuses;
  }

  /** The databases: default first, then the migrax.databases blocks by name. */
  private static List<String> databases(Config config) {
    List<String> names = new java.util.ArrayList<>(List.of(Databases.DEFAULT));
    names.addAll(Databases.names(settings(config)));
    return names;
  }

  /**
   * The data source name of a database: migrax.datasource or the one persistence.xml names for
   * the default one (null: the only one), migrax.databases.<name>.datasource for the others.
   */
  private static String dataSourceName(String database, Config config, ClassLoader loader) {
    if (!Databases.DEFAULT.equals(database)) {
      return Databases.dataSource(database, settings(config));
    }
    return config.getOptionalValue("migrax.datasource", String.class)
        .orElseGet(() -> StartupMigrations.persistenceUnitDataSource(loader));
  }

  private static Map<String, String> settings(Config config) {
    Map<String, String> settings = new LinkedHashMap<>();
    for (String key : config.getPropertyNames()) {
      if (key.startsWith("migrax.")) {
        config.getOptionalValue(key, String.class).ifPresent(value -> settings.put(key, value));
      }
    }
    return settings;
  }

  /** The named data source, or the only one; null (with a warning) when it is ambiguous. */
  private DataSource dataSource(String name) {
    if (name != null) {
      Instance<DataSource> named = dataSources.select(NamedLiteral.of(name));
      if (named.isResolvable()) {
        return named.get();
      }
      throw new IllegalStateException("Migrax: there is no DataSource named '" + name
          + "'. Configure javax.sql.DataSource." + name + ".* or set migrax.datasource.");
    }
    List<? extends Instance.Handle<DataSource>> all = dataSources.handlesStream().toList();
    if (all.size() == 1) {
      return all.get(0).get();
    }
    LOG.warning("Migrax: " + (all.isEmpty() ? "no DataSource is configured"
        : all.size() + " DataSources are configured; set migrax.datasource to pick one")
        + ". Migrations were not applied.");
    return null;
  }

  /** Sends Migrax core messages to java.util.logging, which Helidon uses. */
  private static final class JulSink implements Log.Sink {
    @Override
    public void debug(String message) {
      LOG.log(Level.FINE, "Migrax: {0}", message);
    }

    @Override
    public void info(String message) {
      LOG.info("Migrax: " + message);
    }

    @Override
    public void warn(String message) {
      LOG.warning("Migrax: " + message);
    }

    @Override
    public void error(String message) {
      LOG.severe("Migrax: " + message);
    }
  }
}
