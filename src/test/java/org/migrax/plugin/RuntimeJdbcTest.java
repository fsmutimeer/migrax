package org.migrax.plugin;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class RuntimeJdbcTest {
  private static String message(String url, String driverMessage) {
    return RuntimeJdbc.failure(url, new SQLException(driverMessage)).getMessage();
  }

  @Test
  void aDriverMessageLosesTheUrlsCredentials() {
    String mysql = "jdbc:mysql://user:SE@CRET@db:3306/shop";
    assertEquals("Failed to connect to database at jdbc:mysql://user:***@db:3306/shop: "
        + "bad authority '***@db:3306'", message(mysql, "bad authority 'user:SE@CRET@db:3306'"));
    assertFalse(message(mysql, "bad value SE@CRET").contains("CRET"));
    assertFalse(message("jdbc:oracle:thin:scott/tiger@//host:1521/db", "bad tiger")
        .contains("tiger"));
    assertFalse(message("jdbc:oracle:thin:scott/SE@CRET@host", "bad SE@CRET").contains("CRET"));
    assertFalse(message("jdbc:mysql://db/shop?password=hunter2&x=1", "bad hunter2")
        .contains("hunter2"));
    assertFalse(message("jdbc:sqlserver://db;accessToken={a;b}}c}", "bad {a;b}}c}")
        .contains("a;b"));
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
