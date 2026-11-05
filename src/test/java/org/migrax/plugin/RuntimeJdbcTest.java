package org.migrax.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeJdbcTest {
  private static final String HIDDEN = "the driver's message is hidden, because the URL may hold "
      + "a password. Set the user and password separately from the URL to see it";

  private static String message(String url, String driverMessage) {
    return RuntimeJdbc.failure(url, new SQLException(driverMessage)).getMessage();
  }

  /** A driver that throws what Connector/J throws for a raw % in a URL password. */
  public static final class TrippingDriver implements Driver {
    @Override
    public Connection connect(String url, Properties info) {
      if (url.contains("error")) {
        throw new NoClassDefFoundError("Could not initialize for: " + url);
      }
      throw new IllegalArgumentException("Illegal hex characters in: " + url);
    }

    @Override
    public boolean acceptsURL(String url) {
      return url.startsWith("jdbc:trip:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
      return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
      return 1;
    }

    @Override
    public int getMinorVersion() {
      return 0;
    }

    @Override
    public boolean jdbcCompliant() {
      return false;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }
  }

  private static URLClassLoader withTrippingDriver(Path folder) throws Exception {
    Path services = Files.createDirectories(folder.resolve("META-INF/services"));
    Files.writeString(services.resolve("java.sql.Driver"), TrippingDriver.class.getName());
    return new URLClassLoader(new URL[] {folder.toUri().toURL()},
        RuntimeJdbcTest.class.getClassLoader());
  }

  @Test
  void aUrlThatMayHoldAPasswordHidesTheDriversMessage() {
    for (String url : new String[] {"jdbc:mysql://user:SECRET@db:3306/shop",
        "jdbc:mysql://user:Head/Tail@db:3306/shop", "jdbc:mysql://u:abc@db/shop",
        "jdbc:sqlserver://user:8/Head+Tail==@h:1", "jdbc:mariadb://user:7319;a=Tail@h/db",
        "jdbc:postgresql://me@corp:Secret@db/shop", "jdbc:mysql://db/shop?password=hunter2&x=1",
        "jdbc:mysql://db/shop?password=abc", "jdbc:h2:tcp://db/x;PASSWORD=a;Qzxa\\Vbnm=1",
        "jdbc:sqlserver://db;accessToken={a;b}}c}", "jdbc:oracle:thin:scott/tiger@//host:1521/db",
        "jdbc:oracle:thin:scott/\"p@ss\"@h", "jdbc:oracle:thin:scott/tiger",
        "jdbc:postgresql://h:5432/db?ApplicationName=me@x"}) {
      assertTrue(message(url, "bad " + url).endsWith(": " + HIDDEN), url);
    }
  }

  @Test
  void aHiddenMessageKeepsTheCodesThatSayWhatFailed() {
    SQLException driver = new SQLException("refused", "08001", 7,
        new RuntimeException("quotes u:secret"));
    SQLException failure = RuntimeJdbc.failure("jdbc:mysql://u:secret@db/shop", driver);
    assertNull(failure.getCause());
    assertEquals("08001", failure.getSQLState());
    assertEquals(7, failure.getErrorCode());
    assertTrue(failure.getMessage().endsWith(HIDDEN + " (SQL state 08001)"),
        failure.getMessage());
  }

  @Test
  void aSqlStateThatIsNotOneIsLeftOut() {
    SQLException failure = RuntimeJdbc.failure("jdbc:mysql://u:secret@db/shop",
        new SQLException("refused", "u:secret"));
    assertNull(failure.getSQLState());
    assertFalse(failure.getMessage().contains("secret"), failure.getMessage());
  }

  @Test
  void aUrlWithoutAPasswordKeepsTheDriversMessageAndException() {
    SQLException driver = new SQLException("refused", "08001", 7);
    SQLException failure = RuntimeJdbc.failure("jdbc:mysql://db/shop", driver);
    assertSame(driver, failure.getCause());
    assertEquals("Failed to connect to database at jdbc:mysql://db/shop: refused",
        failure.getMessage());
    for (String url : new String[] {"jdbc:mysql://db:3306/shop?ssl=true",
        "jdbc:mysql://user@db:3306/shop", "jdbc:oracle:thin:@//host:1521/db",
        "jdbc:sqlserver://db:1433;database=d", "jdbc:h2:mem:test", "jdbc:sqlite:app.db"}) {
      assertTrue(message(url, "refused").endsWith(": refused"), url);
    }
  }

  @Test
  void aDriverThatTripsOverAPasswordInTheUrlShowsNoneOfIt(@TempDir Path folder) throws Exception {
    try (URLClassLoader loader = withTrippingDriver(folder)) {
      SQLException failure = assertThrows(SQLException.class, () -> RuntimeJdbc.connect(loader,
          "jdbc:trip://user:Qzxa%Vbnm@db/shop", null, null));
      assertTrue(failure.getMessage().endsWith(": " + HIDDEN), failure.getMessage());
      assertNull(failure.getCause());
      assertEquals("08001", failure.getSQLState(), "reported as a connection failure");
    }
  }

  @Test
  void aDriverErrorShowsNoneOfThePassword(@TempDir Path folder) throws Exception {
    try (URLClassLoader loader = withTrippingDriver(folder)) {
      SQLException failure = assertThrows(SQLException.class, () -> RuntimeJdbc.connect(loader,
          "jdbc:trip://user:Secret@error/shop", null, null));
      assertTrue(failure.getMessage().endsWith(": " + HIDDEN), failure.getMessage());
      assertThrows(NoClassDefFoundError.class,
          () -> RuntimeJdbc.connect(loader, "jdbc:trip://error/shop", null, null));
    }
  }

  @Test
  void aDriverThatTripsOverAPlainUrlStillThrows(@TempDir Path folder) throws Exception {
    try (URLClassLoader loader = withTrippingDriver(folder)) {
      assertThrows(IllegalArgumentException.class,
          () -> RuntimeJdbc.connect(loader, "jdbc:trip://db/shop", null, null));
    }
  }

  @Test
  void everythingAfterASecretValueIsHidden() {
    for (String[] url : new String[][] {
        {"jdbc:postgresql://db/shop?user=me&password=", "ab;Tail"},
        {"jdbc:mysql://db/shop?password=", "a&Tail"},
        {"jdbc:mysql://db/shop?password=", "a;b&ssl=true"},
        {"jdbc:mysql://db/shop?password=", "\"a;b\""},
        {"jdbc:h2:tcp://db/x;PASSWORD=", "7319;Tail=Other"},
        {"jdbc:sqlserver://db;password=", "a?b;database=d"},
        {"jdbc:sqlserver://db;password=", "a&b;database=d"},
        {"jdbc:sqlserver://db;password=", "{a;b&c};database=d"}}) {
      assertEquals(url[0] + "***", RuntimeJdbc.sanitizeUrl(url[0] + url[1]));
    }
    assertEquals("jdbc:mysql://db/shop?ssl=true",
        RuntimeJdbc.sanitizeUrl("jdbc:mysql://db/shop?ssl=true"));
    assertEquals("jdbc:mysql://db/shop?password=",
        RuntimeJdbc.sanitizeUrl("jdbc:mysql://db/shop?password="));
  }

  @Test
  void aSecretNameInsideTheUsersPasswordHidesAllOfIt() {
    for (String url : new String[] {"jdbc:mysql://u:abc;password=x@host",
        "jdbc:mysql://u:abc;key=1@host", "jdbc:mysql://u:abc;pwd=x@host",
        "jdbc:mysql://u:abc&token=b@host"}) {
      assertEquals("jdbc:mysql://u:***", RuntimeJdbc.sanitizeUrl(url));
    }
    assertEquals("jdbc:mysql://u:***@db/shop?password=***",
        RuntimeJdbc.sanitizeUrl("jdbc:mysql://u:abc@db/shop?password=x"));
  }

  @Test
  void anAtSignOrSlashInTheUserStillHidesThePassword() {
    assertEquals("jdbc:postgresql://me@corp:***@db:5432/shop",
        RuntimeJdbc.sanitizeUrl("jdbc:postgresql://me@corp:Secret@db:5432/shop"));
    assertEquals("jdbc:mysql://a/***@db", RuntimeJdbc.sanitizeUrl("jdbc:mysql://a/b:Secret@db"));
    assertEquals("jdbc:mysql://user@db:3306/shop",
        RuntimeJdbc.sanitizeUrl("jdbc:mysql://user@db:3306/shop"));
  }

  @Test
  void aMistypedSlashStillHidesThePassword() {
    assertEquals("jdbc:mysql:/***@db/shop",
        RuntimeJdbc.sanitizeUrl("jdbc:mysql:/root:TIGER9@db/shop"));
    assertEquals("jdbc:mysql:root:***@db/shop",
        RuntimeJdbc.sanitizeUrl("jdbc:mysql:root:TIGER9@db/shop"));
  }

  @Test
  void anAtSignInADatabaseWithoutAUserPartIsNotAPassword() {
    for (String url : new String[] {"jdbc:h2:mem:a@b", "jdbc:h2:mem:test;user=a@b",
        "jdbc:h2:file:C:/Users/jo@corp/db", "jdbc:sqlite:C:/me@x/app.db", "jdbc:x@y"}) {
      assertEquals(url, RuntimeJdbc.sanitizeUrl(url));
      assertTrue(message(url, "refused").endsWith(": refused"), url);
    }
  }

  /** The Oracle rule comes first: a secret setting with an @ used to skip it. */
  @Test
  void anOraclePasswordIsHiddenWhateverFollowsIt() {
    for (String url : new String[] {
        "jdbc:oracle:thin:scott/TIGER9@//127.0.0.1:1/svc?oracle.net.wallet_password=w@x",
        "jdbc:oracle:thin:scott/TIGER9@127.0.0.1:1/svc?oracle.net.wallet_password=w@x",
        "jdbc:oracle:thin:scott/TIGER9@//127.0.0.1:1/svc?token=a@b",
        "jdbc:oracle:thin:scott/TIGER9@127.0.0.1:1/svc?key=a@b",
        "jdbc:oracle:thin:\"a@b\"/TIGER9@db:1521/svc", "jdbc:oracle:thin:a;b/TIGER9@db:1521/svc",
        "jdbc:oracle:thin:scott/TIG\nER9@db:1521/svc", "jdbc:oracle:thin:scott/TIG\nER9"}) {
      String shown = RuntimeJdbc.sanitizeUrl(url);
      assertFalse(shown.contains("TIG") || shown.contains("ER9"), shown);
    }
  }

  @Test
  void aPasswordIsHiddenInMistypedAndWrappedUrls() {
    for (String url : new String[] {"jdbc:oracle:thin:scott:Tiger9@127.0.0.1:1/svc",
        "jdbc:oracle:thin:@scott/Tiger9@host", "jdbc:oracle:thin:@//scott/Tiger9@host",
        " jdbc:oracle:thin:scott/Tiger9@db:1521/svc", "\tjdbc:oracle:thin:scott/Tiger9@db/svc",
        "\"jdbc:oracle:thin:scott/Tiger9@db:1521/svc\"", "'jdbc:oracle:thin:scott/Tiger9@db/svc'",
        "jdbc:log4jdbc:oracle:thin:scott/Tiger9@//db:1521/svc",
        "jdbc:p6spy:oracle:thin:scott/Tiger9@//db:1521/svc",
        "jdbc:otel:oracle:thin:scott/Tiger9@//db:1521/svc",
        "jdbc:mysql:/root:Tiger9@db/shop?sslCert=file:///c:/x.pem",
        "jdbc:postgresql:/root:Tiger9@127.0.0.1:1//shop",
        "jdbc:mysql://root/Tiger9@127.0.0.1/shop", "jdbc:postgresql://root/Tiger9@db:5432/shop",
        " jdbc:mysql:root:Tiger9@db/shop", "jdbc:mysql:/root:Tig//er9@db/shop",
        "jdbc:mysql:/root:Tiger9@db/shop?x=//y@z",
        "jdbc:oracle:thin:root:Tiger9@db:1521/svc?x=a@b",
        "jdbc:oracle:thin:root:Tiger9@//db:1521/svc?TNS_ADMIN=/home/me@corp/w",
        "jdbc:oracle:thin:root:Tig/er9@db:1521/svc",
        "jdbc:p6spy:oracle:thin:root:Tiger9@db:1521/svc?x=a@b",
        "jdbc:log4jdbc:oracle:thin:root:Tiger9@//db:1521/svc?x=a@b",
        "jdbc:mysql://root/Tiger9@db:3306/shop?user=me@corp",
        "jdbc:mysql://root/Tig:er9@db/shop"}) {
      String shown = RuntimeJdbc.sanitizeUrl(url);
      assertFalse(shown.contains("Tiger9") || shown.contains("er9"), url);
      assertTrue(message(url, "bad " + url).endsWith(": " + HIDDEN), url);
    }
  }

  /** A / may stand for the : of user:password@, so an @ after it hides what is between. */
  @Test
  void anAtSignInASettingHidesMoreThanThePassword() {
    assertEquals("jdbc:postgresql://db/***@corp.com",
        RuntimeJdbc.sanitizeUrl("jdbc:postgresql://db/shop?user=me@corp.com"));
    assertEquals("jdbc:oracle:thin:***@b",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:@//host:1521/svc?x=a@b"));
  }

  @Test
  void anOracleUserStaysVisibleWhateverTheForm() {
    assertEquals("jdbc:log4jdbc:oracle:thin:scott/***@//db:1521/svc",
        RuntimeJdbc.sanitizeUrl("jdbc:log4jdbc:oracle:thin:scott/PW@//db:1521/svc"));
    assertEquals("jdbc:oracle:thin:scott/***@//db:1521/svc?password=***",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:scott/PW@//db:1521/svc?password=x"));
    assertEquals("jdbc:oracle:thin:@host:1521:sid",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:@host:1521:sid"));
  }

  @Test
  void anOracleUrlKeepsWhatIsNotAPassword() {
    assertEquals("jdbc:oracle:thin:scott/***",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:scott/PW"));
    assertEquals("jdbc:oracle:thin:scott/***@db:1521/svc",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:scott/PW@db:1521/svc"));
    assertEquals("jdbc:oracle:thin:***@host",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:me:x/PW@host"));
    assertEquals("jdbc:oracle:thin:@//host:1521/db",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:@//host:1521/db"));
    assertEquals("jdbc:oracle:thin:@host:1521/db",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:@host:1521/db"));
  }

  @Test
  void veryLongUrlsAreQuick() {
    String slashes = "jdbc:mysql:" + "/".repeat(500_000) + "@db";
    String oracle = "jdbc:oracle:a:".repeat(50_000);
    String secret = "jdbc:mysql://db/shop?password=" + "abcd;".repeat(100_000);
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      assertTrue(RuntimeJdbc.sanitizeUrl(slashes).endsWith("/***@db"));
      assertEquals(oracle, RuntimeJdbc.sanitizeUrl(oracle));
      assertTrue(message(secret, secret).endsWith(HIDDEN));
    });
  }
}
