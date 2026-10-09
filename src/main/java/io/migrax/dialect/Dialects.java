package io.migrax.dialect;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ServiceLoader;

/**
 * Finds the {@link Dialect} for a name or JDBC URL among the dialects registered in
 * {@code META-INF/services/io.migrax.dialect.Dialect}. Adding a database needs no change here.
 *
 * @since 0.1.0
 */
public final class Dialects {

  /** Dialect names accepted by {@link #byName(String)}, in registration order. */
  public static final List<String> NAMES = available().stream().map(Dialect::id).toList();

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
    for (Dialect dialect : available()) {
      if (dialect.acceptsUrl(u)) {
        return dialect;
      }
    }
    int scheme = u.indexOf(':', "jdbc:".length());
    throw new IllegalArgumentException("Unsupported database in JDBC URL '"
        + (scheme > 0 ? url.substring(0, scheme + 1) : "jdbc:") + "...'. Supported: "
        + String.join(", ", NAMES) + ".");
  }

  /**
   * Resolves a dialect by name, for example {@code postgresql} or {@code mysql}.
   *
   * @param name dialect name or one of its {@link Dialect#aliases() aliases}, such as
   *     {@code postgres} or {@code mssql}
   * @return matching Dialect instance
   * @throws IllegalArgumentException if the name is unknown
   * @since 0.1.0
   */
  public static Dialect byName(String name) {
    String wanted = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    for (Dialect dialect : available()) {
      if (dialect.id().equals(wanted) || dialect.aliases().contains(wanted)) {
        return dialect;
      }
    }
    throw new IllegalArgumentException(
        "Unknown dialect '" + name + "'. Supported: " + String.join(", ", NAMES) + ".");
  }

  /** New instances of every registered dialect. */
  private static List<Dialect> available() {
    List<Dialect> dialects = new ArrayList<>();
    ServiceLoader.load(Dialect.class, Dialect.class.getClassLoader()).forEach(dialects::add);
    return dialects;
  }
}
