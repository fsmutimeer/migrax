package org.migrax.plugin;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeJdbcTest {
  private static String message(String url, String driverMessage) {
    return RuntimeJdbc.failure(url, new SQLException(driverMessage)).getMessage();
  }

  @Test
  void aDriverMessageLosesTheUrlsCredentials() {
    assertEquals("Failed to connect to database at jdbc:mysql://user:***@db:3306/shop: "
        + "bad authority '***@db:3306'",
        message("jdbc:mysql://user:SECRET@db:3306/shop", "bad authority 'user:SECRET@db:3306'"));
    assertFalse(message("jdbc:oracle:thin:scott/tiger@//host:1521/db", "bad tiger")
        .contains("tiger"));
    assertFalse(message("jdbc:mysql://db/shop?password=hunter2&x=1", "bad hunter2")
        .contains("hunter2"));
    assertFalse(message("jdbc:sqlserver://db;accessToken={a;b}}c}", "bad {a;b}}c}")
        .contains("a;b"));
  }

  /** The messages the real drivers give: each quotes only a piece of the password. */
  @Test
  void aPasswordADriverCanSplitHidesItsWholeMessage() {
    for (String[] failure : new String[][] {
        {"jdbc:mysql://user:Head/Tail@db:3306/shop", "bad host:port pair 'user:Head'"},
        {"jdbc:mariadb://user:Head?Tail@db:3306/shop", "Incorrect port value : Head"},
        {"jdbc:oracle:thin:scott/Head@Tail@127.0.0.1:1/db", "ORA-17868: Unknown host: Tail@127"},
        {"jdbc:h2:tcp://user:abc,Tail@127.0.0.1:1/x", "For input string: abc"},
        {"jdbc:mysql://user:SE@CRET@db:3306/shop", "bad authority 'user:SE@CRET@db:3306'"},
        {"jdbc:sqlserver://user:8/Head+Tail==@h:1", "The port number 8/Head+Tail==@h:1 is bad"},
        {"jdbc:mariadb://user:7319/a=Tail@h/db", "Socket fail to connect to user:7319"},
        {"jdbc:h2:tcp://user:Head\"Tail@h/x", "bad Head\"\"Tail"},
        {"jdbc:oracle:thin:scott/Head/Tail", "bad Head"},
        {"jdbc:oracle:thin:scott/\"Head\"@\"Tail\"@h", "bad \"Tail\""}}) {
      String shown = message(failure[0], failure[1]);
      for (String piece : new String[] {"Head", "Tail", "abc", "CRET", "7319"}) {
        assertFalse(shown.contains(piece), shown);
      }
      assertTrue(shown.contains("Set the password separately"), shown);
    }
  }

  @Test
  void anAtSignInAUserSettingKeepsTheDriversMessage() {
    for (String url : new String[] {"jdbc:mysql://db:3306/shop?email=a@b",
        "jdbc:postgresql://x.azure.com:5432/db?user=me@x&sslmode=require",
        "jdbc:sqlserver://x.database.windows.net:1433;database=d;user=me@x;password=secret1"}) {
      assertTrue(message(url, "Unknown host").endsWith(": Unknown host"), url);
    }
  }

  @Test
  void anAtSignInAnySettingKeepsTheDriversMessageWithoutThePieces() {
    String shown = message("jdbc:postgresql://h:5432/db?ApplicationName=me@x",
        "Connection to h:5432 refused for me");
    assertTrue(shown.endsWith(": Connection to h:*** refused for ***"), shown);
    assertTrue(message("jdbc:postgresql://h1:1,h2:2/shop?user=me@x", "refused")
        .endsWith(": refused"));
  }

  /** The text could be a password, and each of these drivers quotes a piece of one. */
  @Test
  void aPasswordShapedLikeSettingsLosesEveryPiece() {
    for (String[] failure : new String[][] {
        {"jdbc:mariadb://user:7319;a=Tail@h/db", "Incorrect port value : 7319;a=Tail@h"},
        {"jdbc:mariadb://user:7319;a=Tail@h/db", "Socket fail to connect to user:7319"},
        {"jdbc:mysql://user:12;user=Tail@h/db", "bad port 12, bad Tail"},
        {"jdbc:mysql://user:7319?Head=Tail@h/db", "Unsupported setting HEAD"}}) {
      String shown = message(failure[0], failure[1]);
      for (String piece : new String[] {"7319", "12", "Tail", "Head", "HEAD"}) {
        assertFalse(shown.substring(shown.indexOf("***@")).contains(piece), shown);
      }
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
  void aShortSecretValueLeavesTheMessage() {
    String shown = message("jdbc:mysql://db/shop?password=abc", "Cannot parse ?password=abc");
    assertFalse(shown.contains("abc"), shown);
    shown = message("jdbc:mysql://db/shop?password=ab;x=cd", "bad ab, bad cd");
    assertTrue(shown.endsWith(": bad ***, bad ***"), shown);
  }

  @Test
  void aPieceOfASecretValueLeavesTheMessage() {
    for (String piece : new String[] {"abcd", "efgh", "ijkl"}) {
      String shown = message("jdbc:mysql://db/shop?password=abcd/efgh#ijkl", "bad " + piece);
      assertFalse(shown.contains(piece), shown);
    }
  }

  @Test
  void aQuotedOraclePasswordLeavesTheMessageWithoutItsQuotes() {
    String shown = message("jdbc:oracle:thin:u/\"p@ss\"@h", "bad p@ss");
    assertFalse(shown.contains("p@ss"), shown);
  }

  @Test
  void aDriverMessageLosesEveryPartOfWhatFollowsASecret() {
    String shown = message("jdbc:h2:tcp://db/x;PASSWORD=7319;Qzxa=Tail",
        "Unsupported connection setting \"QZXA\", value Tail");
    assertFalse(shown.contains("QZXA") || shown.contains("Tail"), shown);
    shown = message("jdbc:mysql://db/shop?password=Head&Tail", "bad Head, bad Tail");
    assertFalse(shown.contains("Head") || shown.contains("Tail"), shown);
  }

  @Test
  void anAtSignOrSlashInTheUserStillHidesThePassword() {
    assertEquals("jdbc:postgresql://me@corp:***@db:5432/shop",
        RuntimeJdbc.sanitizeUrl("jdbc:postgresql://me@corp:Secret@db:5432/shop"));
    assertEquals("jdbc:mysql://a/b:***@db", RuntimeJdbc.sanitizeUrl("jdbc:mysql://a/b:Secret@db"));
    assertEquals("jdbc:mysql://user@db:3306/shop",
        RuntimeJdbc.sanitizeUrl("jdbc:mysql://user@db:3306/shop"));
    String shown = message("jdbc:postgresql://me@corp:Secret@db/shop", "bad me@corp:Secret@db");
    assertFalse(shown.contains("Secret"), shown);
    assertNull(RuntimeJdbc.failure("jdbc:postgresql://me@corp:Secret@db/shop",
        new SQLException("refused")).getCause());
  }

  @Test
  void anOracleUrlWithoutAHostStillHidesThePassword() {
    assertEquals("jdbc:oracle:thin:scott/***",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:scott/PW"));
    assertEquals("jdbc:oracle:thin:me:x/***@host",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:me:x/PW@host"));
    assertEquals("jdbc:oracle:thin:@//host:1521/db",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:@//host:1521/db"));
    assertEquals("jdbc:oracle:thin:@host:1521/db",
        RuntimeJdbc.sanitizeUrl("jdbc:oracle:thin:@host:1521/db"));
  }

  @Test
  void aVeryLongUrlOfSlashesIsQuick() {
    String url = "jdbc:mysql:" + "/".repeat(500_000) + "@db";
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
        () -> assertEquals(url, RuntimeJdbc.sanitizeUrl(url)));
  }

  @Test
  void otherVeryLongUrlsAreQuick() {
    String oracle = "jdbc:oracle:a:".repeat(50_000);
    String settings = "jdbc:mysql://u:1/" + "&".repeat(200_000) + "@db";
    String secret = "jdbc:mysql://db/shop?password=" + "abcd;".repeat(100_000);
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
      assertEquals(oracle, RuntimeJdbc.sanitizeUrl(oracle));
      message(oracle + "@db", "refused");
      assertTrue(message(settings, "refused").contains("Set the password separately"));
      assertTrue(message(secret, secret).contains("Set the password separately"));
    });
  }

  @Test
  void aQuotedOraclePasswordKeepsTheDriversMessage() {
    assertEquals("Failed to connect to database at jdbc:oracle:thin:scott/***@host: refused",
        message("jdbc:oracle:thin:scott/\"p@w\"@host", "refused"));
  }

  @Test
  void aVeryShortPasswordDoesNotEmptyTheMessage() {
    assertEquals("Failed to connect to database at jdbc:mysql://u:***@db/shop: bad database name",
        message("jdbc:mysql://u:a@db/shop", "bad database name"));
  }

  @Test
  void theDriversExceptionOnlyTravelsOnWithoutCredentialsInTheUrl() {
    SQLException driver = new SQLException("refused", "08001", 7,
        new RuntimeException("quotes u:secret"));
    SQLException failure = RuntimeJdbc.failure("jdbc:mysql://u:secret@db/shop", driver);
    assertNull(failure.getCause());
    assertEquals("08001", failure.getSQLState());
    assertEquals(7, failure.getErrorCode());
    assertSame(driver, RuntimeJdbc.failure("jdbc:mysql://db/shop", driver).getCause());
    assertEquals("Failed to connect to database at jdbc:mysql://db/shop: refused",
        RuntimeJdbc.failure("jdbc:mysql://db/shop", driver).getMessage());
  }
}
