package org.migrax.micronaut;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.naming.conventions.StringConvention;
import org.migrax.runner.Databases;
import org.migrax.runner.StartupMigrations;
import org.migrax.util.Log;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies Migrax migrations from {@code db/migration} as soon as Micronaut creates the
 * application's DataSource, which is before Hibernate builds its EntityManagerFactory. So
 * {@code jpa.default.properties.hibernate.hbm2ddl.auto=validate} works: Hibernate validates
 * the migrated schema.
 *
 * <p>Settings: {@code migrax.enabled} (default true), {@code migrax.datasource} (default
 * {@code default}), {@code migrax.locations} (default {@code db/migration}),
 * {@code migrax.java-package}, {@code migrax.resume}, {@code migrax.schemas} and
 * {@code migrax.placeholders.<name>}. Further databases are data sources
 * ({@code datasources.<name>.*}) with a block {@code migrax.databases.<name>.*} (see
 * {@link org.migrax.runner.Databases}).
 */
@Singleton
@Requires(property = "migrax.enabled", notEquals = "false")
public class MigraxDataSourceListener implements BeanCreatedEventListener<DataSource> {
  private static final Logger LOG = LoggerFactory.getLogger("org.migrax");
  private static final List<String> DATA_SOURCE_WRAPPERS = List.of(
      "io.micronaut.data.connection.jdbc.advice.DelegatingDataSource",
      "io.micronaut.transaction.jdbc.DelegatingDataSource");

  private final Environment environment;
  private final io.micronaut.context.BeanContext context;

  public MigraxDataSourceListener(Environment environment,
                                  io.micronaut.context.BeanContext context) {
    this.environment = environment;
    this.context = context;
  }

  @Override
  public DataSource onCreated(BeanCreatedEvent<DataSource> event) {
    DataSource dataSource = event.getBean();
    String name = event.getBeanIdentifier().getName();
    String database = databaseOf(name, environment);
    if (database == null) {
      return dataSource;
    }
    if (Databases.DEFAULT.equals(database)) {
      warnAboutSchemaGeneration();
    }
    Map<String, String> settings = Databases.settings(database, settings(environment));
    String prefix = Databases.DEFAULT.equals(database) ? "Migrax: " : "Migrax (" + database + "): ";
    Log.Sink previous = Log.setSink(new Slf4jSink());
    try {
      if (!StartupMigrations.migratesAtStartup(settings)) {
        LOG.info("{}{}", prefix, StartupMigrations.notMigrating(unwrap(dataSource), settings,
            environment.getClassLoader()));
      } else {
        try {
          int applied = StartupMigrations.migrate(unwrap(dataSource), settings,
              environment.getClassLoader(),
              MigraxListeners.find(context, settings, environment.getClassLoader(), database));
          LOG.info("{}{}", prefix, applied == 0 ? "schema is up to date"
              : "applied " + applied + " migration(s) to data source '" + name + "'");
        } catch (Exception e) {
          throw new IllegalStateException("Migrax could not migrate data source '" + name
              + "': " + e.getMessage(), e);
        }
      }
      StartupMigrations.checkDrift(unwrap(dataSource), settings, environment.getClassLoader(),
          database);
    } catch (IllegalStateException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Migrax could not check data source '" + name
          + "' for drift: " + e.getMessage(), e);
    } finally {
      Log.setSink(previous);
    }
    return dataSource;
  }

  /**
   * The database a data source belongs to: {@code default} for migrax.datasource (default
   * {@code default}), the name of the migrax.databases block whose data source it is, or null.
   */
  static String databaseOf(String dataSourceName, Environment environment) {
    String main = environment.getProperty("migrax.datasource", String.class).orElse("default");
    if (main.equals(dataSourceName)) {
      return Databases.DEFAULT;
    }
    Map<String, String> settings = settings(environment);
    for (String database : Databases.names(settings)) {
      if (Databases.dataSource(database, settings).equals(dataSourceName)) {
        return database;
      }
    }
    return null;
  }

  /** The data source name of a database ({@code datasources.<name>}). */
  static String dataSourceName(String database, Environment environment) {
    return Databases.DEFAULT.equals(database)
        ? environment.getProperty("migrax.datasource", String.class).orElse("default")
        : Databases.dataSource(database, settings(environment));
  }

  /** The databases: default first, then the migrax.databases blocks by name. */
  static List<String> databases(Environment environment) {
    List<String> names = new java.util.ArrayList<>(List.of(Databases.DEFAULT));
    names.addAll(Databases.names(settings(environment)));
    return names;
  }

  /** The {@code migrax.*} settings, with their full keys. */
  static Map<String, String> settings(Environment environment) {
    Map<String, String> settings = new LinkedHashMap<>();
    environment.getProperties("migrax", StringConvention.RAW).forEach((key, value) -> {
      if (value != null) {
        settings.put("migrax." + key, String.valueOf(value));
      }
    });
    return settings;
  }

  /**
   * Micronaut Data can wrap the DataSource in a transaction-aware delegate whose connections
   * need an open transaction; migrations use the real pool underneath.
   */
  static DataSource unwrap(DataSource dataSource) {
    for (String wrapper : DATA_SOURCE_WRAPPERS) {
      try {
        Class<?> type = Class.forName(wrapper, false, dataSource.getClass().getClassLoader());
        if (type.isInstance(dataSource)) {
          return (DataSource) type.getMethod("unwrapDataSource", DataSource.class)
              .invoke(null, dataSource);
        }
      } catch (ReflectiveOperationException | LinkageError ignored) {
        // That wrapper is not on the classpath.
      }
    }
    return dataSource;
  }

  /** Hibernate's update/create also changes the schema, so it drifts from the migrations. */
  private void warnAboutSchemaGeneration() {
    for (String key : List.of("jpa.default.properties.hibernate.hbm2ddl.auto",
        "jpa.default.properties.jakarta.persistence.schema-generation.database.action")) {
      String value = environment.getProperty(key, String.class).orElse("none").trim();
      if (!value.equalsIgnoreCase("none") && !value.equalsIgnoreCase("validate")) {
        LOG.warn("Migrax: {}={} lets Hibernate change the schema too, so the database drifts "
            + "from the migrations. Set it to none or validate.", key, value);
      }
    }
  }

  /** Sends Migrax core messages to SLF4J. */
  private static final class Slf4jSink implements Log.Sink {
    @Override
    public void debug(String message) {
      LOG.debug("Migrax: {}", message);
    }

    @Override
    public void info(String message) {
      LOG.info("Migrax: {}", message);
    }

    @Override
    public void warn(String message) {
      LOG.warn("Migrax: {}", message);
    }

    @Override
    public void error(String message) {
      LOG.error("Migrax: {}", message);
    }
  }
}
