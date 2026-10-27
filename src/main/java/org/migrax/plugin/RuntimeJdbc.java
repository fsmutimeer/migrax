package org.migrax.plugin;

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
    // password=, MySQL's password1= to password3=, SQL Server's pwd=, and SQL Server's braced
    // values, which may contain ; (a closing brace inside is written }}). First, so a password
    // with @ in it can't be taken for the end of a user:password@ part below. Possessive
    // quantifiers: a very long value must not overflow the stack.
    String sanitized = url.replaceAll(
        "(?i)((?:password\\d*|pwd)\\s*=\\s*)(\\{(?:[^}]++|\\}\\})*+\\}|[^&;]+)", "$1***");
    // Oracle's thin URL can carry user/password@ right after the driver type; the password may
    // be quoted and then contain @.
    sanitized = sanitized.replaceAll(
        "(?i)(jdbc:oracle:[a-z0-9]+:[^/@:;]*/)(?:\"[^\"]*\"|[^@]+)@", "$1***@");
    // //user:password@host. The password may contain ; or / (Connector/J accepts them), so it
    // runs to the next @; a password= value is already *** by now, so its @ can't be that one.
    // At worst this hides more than the password, such as a query value containing @.
    sanitized = sanitized.replaceAll("(?i)(//[^:/?#;@]+:)[^@]+@", "$1***@");
    return sanitized;
  }

  public static Connection connect(
      ClassLoader runtimeLoader, String url, String user, String password) throws SQLException {
    return connect(runtimeLoader, url, user, password, new Properties());
  }

  /**
   * Connects with extra driver properties, such as a connect timeout. Which one wins when the
   * URL sets the same property depends on the driver.
   *
   * @since 0.17.2
   */
  public static Connection connect(ClassLoader runtimeLoader, String url, String user,
      String password, Properties extra) throws SQLException {
    Properties properties = new Properties();
    if (extra != null) {
      properties.putAll(extra);
    }
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
