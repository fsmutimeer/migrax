package io.migrax.plugin;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * Dynamic JDBC connection factory using the runtime class loader.
 *
 * @since 0.1.0
 */
public final class RuntimeJdbc {
  private RuntimeJdbc() {}

  /**
   * Sanitizes a JDBC URL by masking any cleartext passwords embedded in user credentials
   * or connection parameters.
   *
   * @param url the JDBC URL to sanitize
   * @return sanitized URL with passwords masked
   * @since 0.1.0
   */
  public static String sanitizeUrl(String url) {
    if (url == null) {
      return "";
    }
    String sanitized = url.replaceAll("(?i)(//[^:]+:)[^@]+@", "$1***@");
    sanitized = sanitized.replaceAll("(?i)(password=)[^&;]+", "$1***");
    return sanitized;
  }

  public static Connection connect(
      ClassLoader runtimeLoader, String url, String user, String password) throws SQLException {
    Properties properties = new Properties();
    if (user != null && !user.isBlank()) {
      properties.setProperty("user", user);
    }
    if (password != null && !password.isBlank()) {
      properties.setProperty("password", password);
    }

    try {
      for (Driver driver : ServiceLoader.load(Driver.class, runtimeLoader)) {
        if (driver.acceptsURL(url)) {
          Connection connection = driver.connect(url, properties);
          if (connection != null) {
            return connection;
          }
        }
      }
    } catch (SQLException e) {
      throw new SQLException(
          "Failed to connect to database at " + sanitizeUrl(url) + ": " + e.getMessage(),
          e.getSQLState(),
          e.getErrorCode(),
          e);
    }
    throw new SQLException(
        "No JDBC driver in the service runtime dependencies accepts URL: " + sanitizeUrl(url)
            + ". Ensure the database driver is a runtime dependency of the service.");
  }
}
