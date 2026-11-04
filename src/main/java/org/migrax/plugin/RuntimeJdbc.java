package org.migrax.plugin;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dynamic JDBC connection factory using the runtime class loader.
 *
 * @since 0.1.0
 */
public final class RuntimeJdbc {
  private static final Pattern ORACLE_USER =
      Pattern.compile("(?i)(jdbc:oracle:[a-z0-9]+:[^/@:;]*/)(\"[^\"]*\"|.+)@");
  private static final Pattern SECRET_VALUE = Pattern.compile(
      "(?i)((?:password\\d*|pwd|secret|key|token)\\s*=\\s*)(\\{(?:[^}]++|\\}\\})*+\\}|[^&;]+)");
  private static final Pattern USER_INFO = Pattern.compile("//[^:/?#;@]*:");
  /** A port and settings, not a password: the @ is in a value (user=me@server, email=a@b). */
  private static final Pattern HOST_PORT_SETTINGS = Pattern.compile("\\d+[/;?].*=.*");
  private static final String HIDDEN_MESSAGE = "the driver's message is hidden, because the "
      + "password in the URL has a character the URL itself uses (@ / ? # , : ;). Set the "
      + "password separately from the URL instead";

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
    // password=, MySQL's password1= to password3=, SQL Server's pwd=, keyStoreSecret= and
    // aadSecurePrincipalSecret= (any name ending in secret),
    // SQLCipher's key= in a SQLite URL, and SQL Server's braced values, which may contain ; (a
    // closing brace inside is written }}). First, so a password with @ in it can't be taken
    // for the end of a user:password@ part below. Possessive quantifiers: a very long value
    // must not overflow the stack. Hiding a harmless setting by mistake is fine.
    String sanitized = SECRET_VALUE.matcher(url).replaceAll("$1***");
    // The two rules below end at an @, so they search only up to the last one: searched to the
    // end of a long URL without an @, they would scan the rest of it from every start (seconds).
    // Oracle's thin URL can carry user/password@ right after the driver type; the password may
    // be quoted and then contain @.
    int lastAt = sanitized.lastIndexOf('@');
    if (lastAt >= 0) {
      sanitized = ORACLE_USER.matcher(sanitized.substring(0, lastAt + 1)).replaceAll("$1***@")
          + sanitized.substring(lastAt + 1);
    }
    // //user:password@host. The password may contain ;, / or a raw @ (Connector/J accepts
    // them), so it runs to the last @; a password= value is already *** by now, so its @ can't
    // be that one. At worst this hides more than the password, such as the host when a query
    // value contains @.
    lastAt = sanitized.lastIndexOf('@');
    if (lastAt >= 0) {
      Matcher userInfo = USER_INFO.matcher(sanitized).region(0, lastAt);
      if (userInfo.find() && userInfo.end() < lastAt) {
        sanitized = sanitized.substring(0, userInfo.end()) + "***" + sanitized.substring(lastAt);
      }
    }
    return sanitized;
  }

  /**
   * The connection failure to report. A driver that can't parse a URL quotes it, so what the URL
   * holds is taken out of the message, and the driver's exception doesn't travel on: its message,
   * or one it wraps, still has it.
   */
  static SQLException failure(String url, SQLException e) {
    boolean credentials = !url.equals(sanitizeUrl(url));
    String message = credentials ? withoutCredentials(url, e.getMessage()) : e.getMessage();
    return new SQLException("Failed to connect to database at " + sanitizeUrl(url) + ": " + message,
        e.getSQLState(), e.getErrorCode(), credentials ? null : e);
  }

  /** The message without the URL's secrets, found as {@link #sanitizeUrl} finds them. */
  private static String withoutCredentials(String url, String message) {
    if (message == null) {
      return null;
    }
    String cleaned = message;
    Matcher values = SECRET_VALUE.matcher(url);
    while (values.find()) {
      cleaned = hide(cleaned, values.group(2));
    }
    int lastAt = url.lastIndexOf('@');
    if (lastAt >= 0) {
      Matcher oracle = ORACLE_USER.matcher(url.substring(0, lastAt + 1));
      if (oracle.find()) {
        if (!oracle.group(2).startsWith("\"") && splits(oracle.group(2))) {
          return HIDDEN_MESSAGE;
        }
        cleaned = hide(cleaned, oracle.group(2));
      }
      Matcher userInfo = USER_INFO.matcher(url).region(0, lastAt);
      if (userInfo.find() && userInfo.end() < lastAt
          && !HOST_PORT_SETTINGS.matcher(url.substring(userInfo.end(), lastAt)).matches()) {
        String password = url.substring(userInfo.end(), lastAt);
        if (splits(password)) {
          return HIDDEN_MESSAGE;
        }
        cleaned = hide(hide(cleaned, url.substring(userInfo.start() + 2, lastAt)), password);
      }
    }
    return cleaned;
  }

  /** Whether a driver may cut the URL inside the password and quote only a piece of it. */
  private static boolean splits(String password) {
    return password.chars().anyMatch(character -> "@/?#,:;".indexOf(character) >= 0);
  }

  /** A very short secret isn't searched for: it would match all over the message. */
  private static String hide(String message, String secret) {
    return secret.length() < 4 ? message : message.replace(secret, "***");
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
      throw failure(url, e);
    }
    throw new SQLException(
        "No JDBC driver in the service runtime dependencies accepts URL: " + sanitizeUrl(url)
            + ". Ensure the database driver is a runtime dependency of the service.");
  }
}
