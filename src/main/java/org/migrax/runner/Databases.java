package org.migrax.runner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The databases an application migrates. The default one uses the {@code migrax.*} settings;
 * each further one has a block {@code migrax.databases.<name>.*}:
 * <pre>
 * migrax.databases.billing.datasource=billingDataSource   # default: the name itself
 * migrax.databases.billing.locations=classpath:sql/billing  # default: db/billing
 * migrax.databases.billing.java-package=com.shop.billing     # default: db.billing
 * migrax.databases.billing.schemas=tenant_a,tenant_b
 * migrax.databases.billing.placeholders.owner=app
 * </pre>
 * What belongs to one database (locations, schemas, placeholders, the Java migration package)
 * comes only from its own block. What applies to the whole application (the environment, the
 * lock timeout, resume, migrate-at-startup, metrics and tracing) is shared, and a block can
 * set it differently. The defaults never overlap with the default database's db/migration and
 * db.migration (Java migrations are found in sub-packages too), which is why the names
 * {@code default} and {@code migration} are reserved. The framework integrations migrate the
 * default database first, then the others by name.
 *
 * @since 0.15.0
 */
public final class Databases {
  /** The name of the database the {@code migrax.*} settings describe, in health and metrics. */
  public static final String DEFAULT = "default";

  private static final String PREFIX = "migrax.databases.";
  private static final Pattern KEY =
      Pattern.compile(Pattern.quote(PREFIX) + "([A-Za-z0-9_-]+)\\.(.+)");
  /** Settings of one database, never taken over from the default database. */
  private static final List<String> OWN = List.of("migrax.locations", "migrax.schemas",
      "migrax.java-package", "migrax.datasource");

  private Databases() {}

  /** The further databases, by name in alphabetical order; empty when there are none. */
  public static List<String> names(Map<String, String> settings) {
    TreeSet<String> names = new TreeSet<>();
    for (String key : settings.keySet()) {
      if (key.startsWith(PREFIX)) {
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) {
          throw new IllegalArgumentException("Unknown setting " + key + ": a database name"
              + " may contain letters, digits, '-' and '_', as in migrax.databases.billing."
              + "locations.");
        }
        names.add(checkName(matcher.group(1)));
      }
    }
    return List.copyOf(names);
  }

  /**
   * The name itself, when it can name a database; else an IllegalArgumentException that says
   * why.
   */
  public static String checkName(String name) {
    if (name == null || !name.matches("[A-Za-z0-9_-]+")) {
      throw new IllegalArgumentException("Database name '" + name + "': use letters, digits,"
          + " '-' and '_', as in migrax.databases.billing.locations.");
    }
    if (name.equals(DEFAULT) || name.equals("migration")) {
      throw new IllegalArgumentException("migrax.databases." + name + " is reserved: the"
          + " default database uses the migrax.* settings and db/migration.");
    }
    return name;
  }

  /**
   * The settings of one database, in the keys the default database uses
   * ({@code migrax.locations}, ...), so they can go to {@link StartupMigrations} or the Java
   * API unchanged. For {@link #DEFAULT}, the settings without the other databases' blocks.
   */
  public static Map<String, String> settings(String name, Map<String, String> settings) {
    Map<String, String> result = new LinkedHashMap<>();
    boolean other = !DEFAULT.equals(name);
    settings.forEach((key, value) -> {
      if (key.startsWith(PREFIX)) {
        return;
      }
      if (other && (OWN.contains(key) || key.startsWith("migrax.placeholders."))) {
        return;
      }
      result.put(key, value);
    });
    if (other) {
      result.put("migrax.locations", "db/" + name);
      result.put("migrax.java-package", "db." + name);
      String own = PREFIX + name + ".";
      settings.forEach((key, value) -> {
        if (key.startsWith(own)) {
          result.put("migrax." + key.substring(own.length()), value);
        }
      });
    }
    return result;
  }

  /**
   * What the framework calls the database's data source: the bean, named data source or JNDI
   * name in {@code migrax.databases.<name>.datasource}, or the name itself. Null for
   * {@link #DEFAULT}: each integration finds its default data source as before.
   */
  public static String dataSource(String name, Map<String, String> settings) {
    if (DEFAULT.equals(name)) {
      return null;
    }
    String value = settings.get(PREFIX + name + ".datasource");
    return value == null || value.isBlank() ? name : value.trim();
  }
}
