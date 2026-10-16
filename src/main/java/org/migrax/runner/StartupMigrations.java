package org.migrax.runner;

import org.migrax.api.Migrax;
import org.migrax.util.Log;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Applies the migrations packaged with an application when it starts. Framework integrations
 * (Quarkus, Micronaut, Helidon) collect the {@code migrax.*} settings from their own
 * configuration and call {@link #migrate}.
 *
 * <p>Settings: {@code migrax.locations} (default {@code db/migration}; several folders
 * separated by commas),
 * {@code migrax.java-package}, {@code migrax.resume}, {@code migrax.schemas} (comma-separated),
 * {@code migrax.lock-timeout} (for example {@code 2m}: how long to wait while another instance
 * migrates), {@code migrax.env} (runs migrations marked {@code -- migrax:env <name>}) and
 * {@code migrax.placeholders.<name>}.
 *
 * @since 0.1.0
 */
public final class StartupMigrations {
  private StartupMigrations() {}

  /**
   * Runs the pending migrations.
   *
   * @param settings {@code migrax.*} settings by full key
   * @return the number of migrations applied
   */
  public static int migrate(DataSource dataSource, Map<String, String> settings,
                            ClassLoader loader) throws Exception {
    return migrate(dataSource, settings, loader, List.of());
  }

  /**
   * Runs the pending migrations, telling the listeners (metrics, tracing) about them.
   *
   * @since 0.13.0
   */
  public static int migrate(DataSource dataSource, Map<String, String> settings,
                            ClassLoader loader,
                            List<? extends org.migrax.api.MigrationListener> listeners)
      throws Exception {
    return Migrax.configure().dataSource(dataSource).classLoader(loader).settings(settings)
        .listeners(listeners).load().migrate().applied();
  }

  /**
   * Whether the database is up to date with the application's migrations, for health checks.
   *
   * @param applied migrations applied (in every schema)
   * @param pending migrations not applied yet, including repeatable ones whose content changed
   * @param problems failed, edited after being applied, or applied but missing from the
   *     application; each needs a person
   * @since 0.3.0
   */
  public record Status(int applied, int pending, int problems) {
    /** Nothing pending and nothing wrong. */
    public boolean upToDate() {
      return pending == 0 && problems == 0;
    }
  }

  /**
   * Reads the migration status without changing anything, with the same settings as
   * {@link #migrate}.
   *
   * @since 0.3.0
   */
  public static Status status(DataSource dataSource, Map<String, String> settings,
                              ClassLoader loader) throws Exception {
    Migrax.Status status = configure(dataSource, settings, loader).status();
    return new Status(status.applied(), status.pending(), status.problems());
  }

  private static Migrax configure(DataSource dataSource, Map<String, String> settings,
                                  ClassLoader loader) throws Exception {
    return Migrax.configure().dataSource(dataSource).classLoader(loader).settings(settings)
        .load();
  }

  /**
   * The status of given migrations, for integrations that find the migrations themselves.
   *
   * @param schemas schemas to check one after another; empty for the default schema
   * @since 0.3.0
   */
  public static Status status(DataSource dataSource, List<Migration> migrations,
                              List<String> schemas) throws Exception {
    return status(dataSource, migrations, schemas, null);
  }

  /**
   * The status of given migrations in an environment ({@code migrax.env}): migrations marked
   * for other environments count as neither pending nor a problem.
   *
   * @param environment the environment name, or null for none
   * @since 0.5.0
   */
  public static Status status(DataSource dataSource, List<Migration> migrations,
                              List<String> schemas, String environment) throws Exception {
    int applied = 0;
    int pending = 0;
    int problems = 0;
    try (Connection connection = dataSource.getConnection()) {
      for (String schema : schemas.isEmpty() ? java.util.Collections.<String>singletonList(null)
          : schemas) {
        if (schema != null) {
          TargetSchema.use(connection, schema);
        }
        for (MigrationRunner.MigrationStatus row
            : new MigrationRunner().withEnvironment(environment)
                .statusOf(connection, migrations)) {
          switch (row.state()) {
            case APPLIED -> applied++;
            case PENDING, OUTDATED -> pending++;
            case SKIPPED -> { }
            default -> problems++;
          }
        }
      }
    }
    return new Status(applied, pending, problems);
  }

  /**
   * The (jta-)data-source the first persistence unit in META-INF/persistence.xml names, or
   * null. Lets Helidon and Jakarta EE integrations migrate the data source JPA uses.
   */
  public static String persistenceUnitDataSource(ClassLoader loader) {
    try (java.io.InputStream in = loader.getResourceAsStream("META-INF/persistence.xml")) {
      if (in == null) {
        return null;
      }
      javax.xml.parsers.DocumentBuilderFactory factory =
          javax.xml.parsers.DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      org.w3c.dom.Document document = factory.newDocumentBuilder().parse(in);
      for (String tag : List.of("jta-data-source", "non-jta-data-source")) {
        org.w3c.dom.NodeList nodes = document.getElementsByTagNameNS("*", tag);
        if (nodes.getLength() > 0 && !nodes.item(0).getTextContent().isBlank()) {
          return nodes.item(0).getTextContent().trim();
        }
      }
      return null;
    } catch (Exception e) {
      Log.warn("Could not read META-INF/persistence.xml: {}", e.getMessage());
      return null;
    }
  }
}
