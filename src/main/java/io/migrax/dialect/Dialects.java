package io.migrax.dialect;

import java.util.Locale;

/**
 * Factory for creating dialect instances based on JDBC connection URLs.
 *
 * @since 0.1.0
 */
public final class Dialects {

  private Dialects() {}

  /**
   * Resolves the appropriate {@link Dialect} implementation for the given JDBC URL.
   *
   * @param url JDBC connection URL
   * @return matching Dialect instance
   * @throws IllegalArgumentException if the URL is null or unrecognized
   * @since 0.1.0
   */
  public static Dialect fromJdbcUrl(String url) {
    if (url == null) {
      throw new IllegalArgumentException("JDBC URL is required");
    }
    String u = url.toLowerCase(Locale.ROOT);
    if (u.startsWith("jdbc:postgresql:")) return new PostgresDialect();
    if (u.startsWith("jdbc:mysql:")) return new MySqlDialect();
    if (u.startsWith("jdbc:mariadb:")) return new MariaDbDialect();
    if (u.startsWith("jdbc:sqlserver:")) return new SqlServerDialect();
    if (u.startsWith("jdbc:oracle:")) return new OracleDialect();
    if (u.startsWith("jdbc:h2:")) return new H2Dialect();
    int scheme = u.indexOf(':', "jdbc:".length());
    throw new IllegalArgumentException("Unsupported database in JDBC URL '"
        + (scheme > 0 ? url.substring(0, scheme + 1) : "jdbc:") + "...'. Supported: "
        + String.join(", ", NAMES) + ".");
  }

  /** Dialect names accepted by {@link #byName(String)}. */
  public static final java.util.List<String> NAMES =
      java.util.List.of("postgresql", "mysql", "mariadb", "sqlserver", "oracle", "h2");

  /**
   * Resolves a dialect by name, for example {@code postgresql} or {@code mysql}.
   *
   * @param name dialect name; {@code postgres} and {@code mssql} are accepted aliases
   * @return matching Dialect instance
   * @throws IllegalArgumentException if the name is unknown
   * @since 0.1.0
   */
  public static Dialect byName(String name) {
    return switch (name == null ? "" : name.trim().toLowerCase(Locale.ROOT)) {
      case "postgres", "postgresql", "pg" -> new PostgresDialect();
      case "mysql" -> new MySqlDialect();
      case "mariadb" -> new MariaDbDialect();
      case "sqlserver", "mssql" -> new SqlServerDialect();
      case "oracle" -> new OracleDialect();
      case "h2" -> new H2Dialect();
      default -> throw new IllegalArgumentException(
          "Unknown dialect '" + name + "'. Supported: " + String.join(", ", NAMES) + ".");
    };
  }
}
