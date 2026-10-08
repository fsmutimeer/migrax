package io.migrax.micronaut;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.core.naming.conventions.StringConvention;
import io.migrax.runner.StartupMigrations;
import io.migrax.util.Log;
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
 * {@code migrax.placeholders.<name>}.
 */
@Singleton
@Requires(property = "migrax.enabled", notEquals = "false")
public class MigraxDataSourceListener implements BeanCreatedEventListener<DataSource> {
  private static final Logger LOG = LoggerFactory.getLogger("io.migrax");
  private static final List<String> DATA_SOURCE_WRAPPERS = List.of(
      "io.micronaut.data.connection.jdbc.advice.DelegatingDataSource",
      "io.micronaut.transaction.jdbc.DelegatingDataSource");

  private final Environment environment;

  public MigraxDataSourceListener(Environment environment) {
    this.environment = environment;
  }

  @Override
  public DataSource onCreated(BeanCreatedEvent<DataSource> event) {
    DataSource dataSource = event.getBean();
    String name = event.getBeanIdentifier().getName();
    String wanted = environment.getProperty("migrax.datasource", String.class).orElse("default");
    if (!wanted.equals(name)) {
      return dataSource;
    }
    warnAboutSchemaGeneration();
    Map<String, String> settings = new LinkedHashMap<>();
    environment.getProperties("migrax", StringConvention.RAW).forEach((key, value) -> {
      if (value != null) {
        settings.put("migrax." + key, String.valueOf(value));
      }
    });
    Log.Sink previous = Log.setSink(new Slf4jSink());
    try {
      int applied = StartupMigrations.migrate(unwrap(dataSource), settings,
          environment.getClassLoader());
      LOG.info("Migrax: {}", applied == 0 ? "schema is up to date"
          : "applied " + applied + " migration(s) to data source '" + name + "'");
    } catch (Exception e) {
      throw new IllegalStateException("Migrax could not migrate data source '" + name + "': "
          + e.getMessage(), e);
    } finally {
      Log.setSink(previous);
    }
    return dataSource;
  }

  /**
   * Micronaut Data can wrap the DataSource in a transaction-aware delegate whose connections
   * need an open transaction; migrations use the real pool underneath.
   */
  private static DataSource unwrap(DataSource dataSource) {
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
