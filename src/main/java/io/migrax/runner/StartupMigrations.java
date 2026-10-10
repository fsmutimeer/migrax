package io.migrax.runner;

import io.migrax.util.Log;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Applies the migrations packaged with an application when it starts. Framework integrations
 * (Quarkus, Micronaut, Helidon) collect the {@code migrax.*} settings from their own
 * configuration and call {@link #migrate}.
 *
 * <p>Settings: {@code migrax.locations} (default {@code db/migration}),
 * {@code migrax.java-package}, {@code migrax.resume}, {@code migrax.schemas} (comma-separated),
 * {@code migrax.lock-timeout} (for example {@code 2m}: how long to wait while another instance
 * migrates) and {@code migrax.placeholders.<name>}.
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
    String location = setting(settings, "migrax.locations", "db/migration");
    String javaPackage = setting(settings, "migrax.java-package",
        MigrationLoader.DEFAULT_JAVA_PACKAGE);
    boolean resume = Boolean.parseBoolean(setting(settings, "migrax.resume", "false"));
    List<String> schemas = schemas(settings);
    Map<String, String> placeholders = new LinkedHashMap<>();
    settings.forEach((key, value) -> {
      if (key.startsWith("migrax.placeholders.") && value != null) {
        placeholders.put(key.substring("migrax.placeholders.".length()), value);
      }
    });

    List<Migration> migrations = migrations(location, javaPackage, loader);
    if (migrations.isEmpty()) {
      Log.info("No migrations found in {}.", location);
      return 0;
    }
    String lockTimeout = setting(settings, "migrax.lock-timeout", "");
    MigrationRunner runner = new MigrationRunner().withLockTimeout(lockTimeout.isBlank()
        ? DatabaseMigrationLock.configuredTimeout()
        : DatabaseMigrationLock.parseTimeout(lockTimeout));
    try (Connection connection = dataSource.getConnection()) {
      MigrationRunner.Options options = new MigrationRunner.Options(resume, placeholders);
      if (schemas.isEmpty()) {
        return runner.migrateAll(connection, migrations, options);
      }
      int applied = 0;
      for (String schema : schemas) {
        useSchema(connection, schema);
        applied += runner.migrateAll(connection, migrations, options);
      }
      return applied;
    }
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
    List<Migration> migrations = migrations(setting(settings, "migrax.locations", "db/migration"),
        setting(settings, "migrax.java-package", MigrationLoader.DEFAULT_JAVA_PACKAGE), loader);
    return status(dataSource, migrations, schemas(settings));
  }

  /**
   * The status of given migrations, for integrations that find the migrations themselves.
   *
   * @param schemas schemas to check one after another; empty for the default schema
   * @since 0.3.0
   */
  public static Status status(DataSource dataSource, List<Migration> migrations,
                              List<String> schemas) throws Exception {
    int applied = 0;
    int pending = 0;
    int problems = 0;
    try (Connection connection = dataSource.getConnection()) {
      for (String schema : schemas.isEmpty() ? java.util.Collections.<String>singletonList(null)
          : schemas) {
        if (schema != null) {
          useSchema(connection, schema);
        }
        for (MigrationRunner.MigrationStatus row
            : new MigrationRunner().statusOf(connection, migrations)) {
          switch (row.state()) {
            case APPLIED -> applied++;
            case PENDING, OUTDATED -> pending++;
            default -> problems++;
          }
        }
      }
    }
    return new Status(applied, pending, problems);
  }

  private static List<Migration> migrations(String location, String javaPackage,
                                            ClassLoader loader) throws Exception {
    List<Migration> migrations = new ArrayList<>(ClasspathMigrations.load(loader, location));
    migrations.addAll(MigrationLoader.javaMigrations(loader, javaPackage));
    return migrations;
  }

  private static List<String> schemas(Map<String, String> settings) {
    List<String> schemas = new ArrayList<>();
    for (String schema : setting(settings, "migrax.schemas", "").split(",")) {
      if (!schema.isBlank()) {
        schemas.add(schema.trim());
      }
    }
    return schemas;
  }

  private static void useSchema(Connection connection, String schema) throws Exception {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    if (product.contains("mysql") || product.contains("mariadb")) {
      connection.setCatalog(schema);
    } else {
      connection.setSchema(schema);
    }
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

  private static String setting(Map<String, String> settings, String key, String fallback) {
    String value = settings.get(key);
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
