package org.migrax.plugin;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Locale;
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
  /**
   * Also behind up to two wrapper drivers (p6spy, h2:...), after a mistyped jdbc: (jbdc:) and
   * stray spaces or quotes, and without the driver type (oracle:scott/password@db). A fixed
   * number of wrappers: any number would make a long URL slow to read.
   */
  private static final String ORACLE_START =
      "(?is)^([^a-z]*[a-z]{2,8}:(?:[a-z0-9_-]+:){0,2}?oracle:(?:[a-z0-9]+:)?(?!@)";
  /**
   * An unquoted user holds no : or @: with one, the / may come after the password, as in
   * root:password@db:1521/svc. Such a URL falls to the general rule, which hides the user too.
   * A quoted user holds no /: in "scott/password"/x@db the quotes are around both.
   */
  private static final Pattern ORACLE_USER =
      Pattern.compile(ORACLE_START + "(?:\"[^\"/]*\"|[^/@:]*)/)(.+)@");
  /** A mistyped Oracle URL with user/password and no host. */
  private static final Pattern ORACLE_NO_HOST = Pattern.compile(ORACLE_START + "[^/]*/)(.+)");
  /**
   * A setting whose name (percent-decoded, any case) ends like this may hold a secret:
   * password=, MySQL's password1=, misspellings such as paswd= or pasword=, pass=, pwd=, pw=,
   * psw=, pswd=, sslpassphrase=, keyStoreSecret=, key=, token=, auth=, credentials=. Names that
   * only start alike (passwordless=) stay readable. Everything after the first such setting
   * counts as its value: a mistyped password can hold the ; or & that ends a setting, and the
   * text after it can't be told from the next setting.
   */
  private static final Pattern SECRET_NAME = Pattern.compile("(?:p[a@]?s{1,2}(?:w(?:o?r?d)?)?"
      + "|pa?s?s?wo?r?d|pw|psw|pswd|pwd|passphrase|secret|key|token|auth|credentials?)\\d*$");
  /** Where the user starts when // was mistyped; these databases have no user:password@ form. */
  private static final Pattern MISTYPED_START =
      Pattern.compile("(?i)^[^a-z]*[a-z]{2,8}:(?!h2:|sqlite:|derby:|hsqldb:)[a-z0-9_-]+:");
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
    // aadSecurePrincipalSecret= (any name ending in secret), and SQLCipher's key= in a SQLite URL;
    // also the short and spelled-out forms people type (passwd=, pass=, pw=, psw=, pswd=,
    // passphrase=, credentials=), which a driver may pass on even when it doesn't know them.
    // Before the user:password@ rule, so a password with @ in it can't be taken for the end of
    // that part. Hiding harmless settings by mistake is fine.
    int secret = secretValueStart(sanitized);
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
    } else if (lastAt < 0 && !oracle) {
      sanitized = withoutPortPassword(sanitized);
    }
    return sanitized;
  }

  /**
   * Where the value of the first setting that may hold a secret starts, or -1. A plain scan:
   * each = looks back over its own name only, so a long URL is read about twice.
   */
  private static int secretValueStart(String url) {
    for (int equals = url.indexOf('='); equals >= 0; equals = url.indexOf('=', equals + 1)) {
      int end = equals;
      while (end > 0 && Character.isWhitespace(url.charAt(end - 1))) {
        end--;
      }
      int start = end;
      while (start > 0 && isNameChar(url.charAt(start - 1))) {
        start--;
      }
      String name = decodePercent(url.substring(start, end)).toLowerCase(Locale.ROOT);
      if (SECRET_NAME.matcher(name).find()) {
        int value = equals + 1;
        while (value < url.length() && Character.isWhitespace(url.charAt(value))) {
          value++;
        }
        return value < url.length() ? value : -1;
      }
    }
    return -1;
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-' || c == '%';
  }

  /** %70assword is password: a driver may decode the name before it reads it. */
  private static String decodePercent(String name) {
    if (name.indexOf('%') < 0) {
      return name;
    }
    StringBuilder decoded = new StringBuilder();
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c == '%' && i + 2 < name.length()
          && Character.digit(name.charAt(i + 1), 16) >= 0
          && Character.digit(name.charAt(i + 2), 16) >= 0) {
        decoded.append((char) Integer.parseInt(name.substring(i + 1, i + 3), 16));
        i += 2;
      } else {
        decoded.append(c);
      }
    }
    return decoded.toString();
  }

  /**
   * //root:password/shop, with the @host forgotten: what stands where the port should be is not
   * a number, so it may be the password. Hidden up to the end of the host part.
   */
  private static String withoutPortPassword(String url) {
    int slashes = url.indexOf("//");
    if (slashes < 0) {
      return url;
    }
    int start = slashes + 2;
    int end = start;
    while (end < url.length() && "/?;".indexOf(url.charAt(end)) < 0) {
      end++;
    }
    int offset = start;
    for (String host : url.substring(start, end).split(",", -1)) {
      int colon = host.indexOf(':', host.lastIndexOf(']') + 1);
      if (colon >= 0 && !host.substring(colon + 1).chars().allMatch(Character::isDigit)) {
        return url.substring(0, offset + colon + 1) + "***" + url.substring(end);
      }
      offset += host.length() + 1;
    }
    return url;
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
    } catch (RuntimeException | Error e) {
      // A driver can trip over the URL without a SQLException, and its message quotes it too.
      // Running out of memory or stack says nothing about the URL.
      if (!(e instanceof VirtualMachineError) && holdsCredentials(url)) {
        throw new SQLException(failedAt(url) + HIDDEN_MESSAGE, CONNECTION_FAILURE);
      }
      throw e;
    }
    throw new SQLException(
        "No JDBC driver in the service runtime dependencies accepts URL: " + sanitizeUrl(url)
            + ". Ensure the database driver is a runtime dependency of the service.");
  }
}
