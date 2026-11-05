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
  /** Also behind a wrapper driver, and after stray spaces or quotes. */
  private static final String ORACLE_START =
      "(?is)^([^a-z]*jdbc:(?:[a-z0-9]+:)?oracle:[a-z0-9]+:(?!@)";
  /**
   * An unquoted user holds no : or @: with one, the / may come after the password, as in
   * root:password@db:1521/svc. Such a URL falls to the general rule, which hides the user too.
   */
  private static final Pattern ORACLE_USER =
      Pattern.compile(ORACLE_START + "(?:\"[^\"]*\"|[^/@:]*)/)(.+)@");
  /** A mistyped Oracle URL with user/password and no host. */
  private static final Pattern ORACLE_NO_HOST = Pattern.compile(ORACLE_START + "[^/]*/)(.+)");
  /**
   * Everything after the first of these counts as its value: a mistyped password can hold the ;
   * or & that ends a setting, and the text after it can't be told from the next setting.
   */
  private static final Pattern SECRET_NAME =
      Pattern.compile("(?i)(?:password\\d*|pwd|secret|key|token)\\s*=\\s*");
  /** Where the user starts when // was mistyped; these databases have no user:password@ form. */
  private static final Pattern MISTYPED_START =
      Pattern.compile("(?i)^[^a-z]*jdbc:(?!h2:|sqlite:|derby:|hsqldb:)[a-z0-9]+:");
  private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");
  /** SQL state class 08 is a connection failure, whatever tripped the driver. */
  private static final String CONNECTION_FAILURE = "08001";
  private static final String HIDDEN_MESSAGE = "the driver's message is hidden, because the URL "
      + "may hold a password. Set the user and password separately from the URL to see it";

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
    // Oracle's thin URL can carry user/password@ right after the driver type; the password may
    // be quoted and then contain @. First, so nothing below can stop it from running. Both
    // patterns are anchored, so a long URL is read once, not from every start (seconds).
    int lastAt = url.lastIndexOf('@');
    String sanitized = lastAt >= 0
        ? ORACLE_USER.matcher(url.substring(0, lastAt + 1)).replaceAll("$1***@")
            + url.substring(lastAt + 1)
        : ORACLE_NO_HOST.matcher(url).replaceAll("$1***");
    // The password is masked: the user:password@ rule is skipped so the Oracle user stays visible.
    boolean oracle = !sanitized.equals(url);
    // password=, MySQL's password1= to password3=, SQL Server's pwd=, keyStoreSecret= and
    // aadSecurePrincipalSecret= (any name ending in secret), and SQLCipher's key= in a SQLite URL.
    // Before the user:password@ rule, so a password with @ in it can't be taken for the end of
    // that part. Hiding harmless settings by mistake is fine.
    Matcher name = SECRET_NAME.matcher(sanitized);
    int secret = name.find() && name.end() < sanitized.length() ? name.end() : -1;
    // The name may be inside a user:password@ part, and cutting there would lose the @ that
    // marks the end of that password below.
    int inUserInfo = oracle ? -1 : passwordStart(sanitized, sanitized.lastIndexOf('@'));
    if (secret >= 0 && inUserInfo >= 0 && inUserInfo < secret
        && sanitized.indexOf('@', secret) >= 0) {
      return sanitized.substring(0, inUserInfo) + "***";
    }
    if (secret >= 0) {
      sanitized = sanitized.substring(0, secret) + "***";
    }
    // //user:password@host. The password may contain ;, / or a raw @ (Connector/J accepts
    // them), so it runs to the last @. At worst this hides more than the password, such as the
    // host when a query value contains @.
    lastAt = sanitized.lastIndexOf('@');
    int password = oracle ? -1 : passwordStart(sanitized, lastAt);
    if (password >= 0) {
      sanitized = sanitized.substring(0, password) + "***" + sanitized.substring(lastAt);
    }
    return sanitized;
  }

  /**
   * Where the password of user:password@ starts, or -1. The user starts after a // before the @
   * or, unless // follows it, after jdbc:name:, for a mistyped // (a later // can be in the
   * password). The earlier result wins. At worst this hides more than the password, such as the
   * path of //db/shop?user=me@server or the user of a mistyped /root:password@db. A plain search:
   * a pattern would scan a long URL from every // in it.
   */
  private static int passwordStart(String url, int lastAt) {
    int slashes = url.indexOf("//");
    int found = slashes >= 0 && slashes < lastAt ? afterUser(url, slashes + 2, lastAt) : -1;
    Matcher mistyped = MISTYPED_START.matcher(url);
    if (mistyped.lookingAt() && !url.startsWith("//", mistyped.end())) {
      int other = afterUser(url, mistyped.end(), lastAt);
      found = other >= 0 && (found < 0 || other < found) ? other : found;
    }
    return found;
  }

  /**
   * The user may hold an @ (me@server), so the first : or / ends it, whichever comes first: a /
   * may have been typed for the :, and a later : is then a port or in the password.
   */
  private static int afterUser(String url, int user, int lastAt) {
    int colon = url.indexOf(':', user);
    int slash = url.indexOf('/', user);
    int end = Math.min(colon < 0 ? Integer.MAX_VALUE : colon,
        slash < 0 ? Integer.MAX_VALUE : slash);
    return end < lastAt - 1 ? end + 1 : -1;
  }

  /** Whether the URL may hold a password: then nothing a driver says about it is shown. */
  private static boolean holdsCredentials(String url) {
    return !url.equals(sanitizeUrl(url));
  }

  /**
   * The connection failure to report. A driver that can't parse a URL quotes it, or a piece of it
   * in its own form (cut, joined, in another case), so no search can take a password out of its
   * message. When the URL may hold one, the message and the exception stay behind.
   */
  static SQLException failure(String url, SQLException e) {
    if (!holdsCredentials(url)) {
      return new SQLException(failedAt(url) + e.getMessage(), e.getSQLState(), e.getErrorCode(), e);
    }
    String state = e.getSQLState();
    boolean known = state != null && SQL_STATE.matcher(state).matches();
    return new SQLException(
        failedAt(url) + HIDDEN_MESSAGE + (known ? " (SQL state " + state + ")" : ""),
        known ? state : null, e.getErrorCode());
  }

  private static String failedAt(String url) {
    return "Failed to connect to database at " + sanitizeUrl(url) + ": ";
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
    } catch (RuntimeException | LinkageError | AssertionError e) {
      // A driver can trip over the URL without a SQLException, and its message quotes it too.
      if (holdsCredentials(url)) {
        throw new SQLException(failedAt(url) + HIDDEN_MESSAGE, CONNECTION_FAILURE);
      }
      throw e;
    }
    throw new SQLException(
        "No JDBC driver in the service runtime dependencies accepts URL: " + sanitizeUrl(url)
            + ". Ensure the database driver is a runtime dependency of the service.");
  }
}
