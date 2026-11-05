package org.migrax.plugin;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
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
      Pattern.compile("(?i)^(jdbc:oracle:[a-z0-9]+:[^/@;]*/)(.+)@");
  /** A mistyped Oracle URL with user/password and no host. */
  private static final Pattern ORACLE_NO_HOST =
      Pattern.compile("(?i)^(jdbc:oracle:[a-z0-9]+:[^/@;]*/)(.+)");
  private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"");
  /**
   * Everything after the first of these counts as its value: a mistyped password can hold the ;
   * or & that ends a setting, and the text after it can't be told from the next setting.
   */
  private static final Pattern SECRET_NAME =
      Pattern.compile("(?i)(?:password\\d*|pwd|secret|key|token)\\s*=\\s*");
  /**
   * What follows //host: when the last @ may be in a setting (user=me@server), and not after a
   * password. A password can look the same, so every piece of it comes out of the message.
   */
  private static final Pattern PORT_AND_SETTING = Pattern.compile(
      "(?i)\\d{1,5}(?:[,/][^;?]*)?[;?&](?:.*[;&])?[a-z][a-z0-9_.-]*=[^;&]*");
  /** The characters a driver cuts a URL at, or rewrites, and those that end a setting. */
  private static final Pattern PIECE_END = Pattern.compile("[@/?#,:;\"\\\\&=]+");
  private static final String HIDDEN_MESSAGE = "the driver's message is hidden, because the URL "
      + "may hold a password with a character the URL itself uses. Set the password separately "
      + "from the URL instead";
  /** Longer than any real URL part: the settings pattern needn't run on it. */
  private static final int LONGEST_SETTINGS = 2048;

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
    // aadSecurePrincipalSecret= (any name ending in secret), and SQLCipher's key= in a SQLite URL.
    // First, so a password with @ in it can't be taken for the end of a user:password@ part
    // below. Hiding harmless settings by mistake is fine.
    Matcher name = SECRET_NAME.matcher(url);
    int secret = name.find() && name.end() < url.length() ? name.end() : -1;
    // The name may be inside a user:password@ part, and cutting there would lose the @ that
    // marks the start of that password below.
    int inUserInfo = passwordStart(url, url.lastIndexOf('@'));
    if (secret >= 0 && inUserInfo >= 0 && inUserInfo < secret
        && url.indexOf('@', secret) >= 0) {
      return url.substring(0, inUserInfo) + "***";
    }
    String sanitized = secret < 0 ? url : url.substring(0, secret) + "***";
    // Oracle's thin URL can carry user/password@ right after the driver type; the password may
    // be quoted and then contain @. Both Oracle patterns are anchored, so a long URL is read
    // once, not from every start (seconds).
    int lastAt = sanitized.lastIndexOf('@');
    if (lastAt >= 0) {
      sanitized = ORACLE_USER.matcher(sanitized.substring(0, lastAt + 1)).replaceAll("$1***@")
          + sanitized.substring(lastAt + 1);
    } else {
      sanitized = ORACLE_NO_HOST.matcher(sanitized).replaceAll("$1***");
    }
    // //user:password@host. The password may contain ;, / or a raw @ (Connector/J accepts
    // them), so it runs to the last @. At worst this hides more than the password, such as the
    // host when a query value contains @.
    lastAt = sanitized.lastIndexOf('@');
    int password = passwordStart(sanitized, lastAt);
    if (password >= 0) {
      sanitized = sanitized.substring(0, password) + "***" + sanitized.substring(lastAt);
    }
    return sanitized;
  }

  /**
   * Where the password of //user:password@ starts, or -1. The user may hold an @ (me@server) or
   * a /, so the first : after // ends it. At worst this hides more than the password. A plain
   * search: a pattern would scan a long URL from every // in it.
   */
  private static int passwordStart(String url, int lastAt) {
    int slashes = url.indexOf("//");
    int colon = slashes < 0 ? -1 : url.indexOf(':', slashes + 2);
    return colon >= 0 && colon + 1 < lastAt ? colon + 1 : -1;
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

  /**
   * The message without the URL's secrets, found as {@link #sanitizeUrl} finds them. This reads
   * the URL before masking: when the last @ is in a masked value, it hides more than that does.
   */
  private static String withoutCredentials(String url, String message) {
    if (message == null) {
      return null;
    }
    String cleaned = message;
    Matcher name = SECRET_NAME.matcher(url);
    if (name.find() && name.end() < url.length()) {
      String value = url.substring(name.end());
      cleaned = hide(hide(cleaned, url.substring(name.start())), value);
      if (PIECE_END.matcher(value).find()) {
        if (value.length() > LONGEST_SETTINGS) {
          return HIDDEN_MESSAGE;
        }
        cleaned = withoutPieces(cleaned, value);
      }
    }
    int lastAt = url.lastIndexOf('@');
    Matcher oracle = lastAt >= 0 ? ORACLE_USER.matcher(url.substring(0, lastAt + 1))
        : ORACLE_NO_HOST.matcher(url);
    if (oracle.find()) {
      String password = oracle.group(2);
      boolean quoted = QUOTED.matcher(password).matches();
      if (!quoted && splits(password)) {
        return HIDDEN_MESSAGE;
      }
      cleaned = hide(cleaned, password);
      if (quoted) {
        cleaned = hide(cleaned, password.substring(1, password.length() - 1));
      }
    }
    int start = passwordStart(url, lastAt);
    if (start >= 0) {
      String password = url.substring(start, lastAt);
      boolean cut = splits(password);
      if (cut && (password.length() > LONGEST_SETTINGS
          || !PORT_AND_SETTING.matcher(password).matches())) {
        return HIDDEN_MESSAGE;
      }
      cleaned = hide(hide(cleaned, url.substring(url.indexOf("//") + 2, lastAt)), password);
      if (cut) {
        cleaned = withoutPieces(cleaned, password);
      }
    }
    return cleaned;
  }

  /** The message without any whole word of the text, however short: a driver may quote one. */
  private static String withoutPieces(String message, String text) {
    String cleaned = message;
    for (String piece : new LinkedHashSet<>(List.of(PIECE_END.split(text)))) {
      if (!piece.isEmpty()) {
        cleaned = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(piece) + "(?![a-z0-9])",
            Pattern.CASE_INSENSITIVE).matcher(cleaned).replaceAll("***");
      }
    }
    return cleaned;
  }

  /** Whether a driver may cut the URL inside the password, or rewrite it, before quoting it. */
  private static boolean splits(String password) {
    return password.chars().anyMatch(character -> "@/?#,:;\"\\".indexOf(character) >= 0);
  }

  /**
   * A very short secret isn't searched for: it would match all over the message. Any case, since
   * a driver may change it when it quotes a setting.
   */
  private static String hide(String message, String secret) {
    return secret.length() < 4 ? message
        : Pattern.compile(Pattern.quote(secret), Pattern.CASE_INSENSITIVE).matcher(message)
            .replaceAll("***");
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
