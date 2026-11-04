package org.migrax.plugin;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        {"jdbc:mysql://user:SE@CRET@db:3306/shop", "bad authority 'user:SE@CRET@db:3306'"}}) {
      String shown = message(failure[0], failure[1]);
      for (String piece : new String[] {"Head", "Tail", "abc", "CRET"}) {
        assertFalse(shown.contains(piece), shown);
      }
      assertTrue(shown.contains("Set the password separately"), shown);
    }
  }

  @Test
  void anAtSignInASettingKeepsTheDriversMessage() {
    for (String url : new String[] {"jdbc:mysql://db:3306/shop?email=a@b",
        "jdbc:sqlserver://x.database.windows.net:1433;database=d;user=me@x;password=secret1"}) {
      assertTrue(message(url, "Unknown host").endsWith(": Unknown host"), url);
    }
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
