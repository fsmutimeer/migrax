package org.migrax.cli;

import org.migrax.dialect.Dialects;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

class RenameScenarioTest {
  @Test
  void renamesKeepTheRowsAndTheKeysAndRollBack() throws Exception {
    try (Connection connection = DriverManager.getConnection(
        "jdbc:h2:mem:renames;DB_CLOSE_DELAY=-1")) {
      RenameScenario.run(connection, Dialects.byName("h2"));
    }
  }

  /** SQLite rebuilds a table for a key change: in a rollback, under the name it gets back. */
  @Test
  void onSqliteToo() throws Exception {
    Path file = Files.createTempFile("migrax-renames-", ".db");
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      RenameScenario.run(connection, Dialects.byName("sqlite"));
    } finally {
      Files.deleteIfExists(file);
      Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".migrax-lock"));
    }
  }
}
