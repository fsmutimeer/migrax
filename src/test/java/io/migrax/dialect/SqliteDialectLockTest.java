package io.migrax.dialect;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where SQLite's migration lock file goes. */
class SqliteDialectLockTest {
  @Test
  void findsTheDatabaseFileOfAUrl() {
    assertEquals(Path.of("data/shop.db").toAbsolutePath(),
        SqliteDialect.databaseFile("jdbc:sqlite:data/shop.db?foreign_keys=true"));
    assertEquals(Path.of("shop.db").toAbsolutePath(),
        SqliteDialect.databaseFile("jdbc:sqlite:file:shop.db"));
    assertNull(SqliteDialect.databaseFile("jdbc:sqlite::memory:"));
    assertNull(SqliteDialect.databaseFile("jdbc:sqlite:file:shared?mode=memory&cache=shared"));
  }
}
