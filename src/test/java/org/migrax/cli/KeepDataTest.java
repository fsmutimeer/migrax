package org.migrax.cli;

import org.migrax.dialect.Dialects;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeepDataTest {
  @Test
  void aRollbackRestoresDroppedTablesAndColumnsWithTheirData() throws Exception {
    try (Connection connection = DriverManager.getConnection(
        "jdbc:h2:mem:keep_data;DB_CLOSE_DELAY=-1")) {
      KeepDataScenario.run(connection, Dialects.byName("h2"));
    }
  }

  @Test
  void backupNamesFitEveryDatabase() {
    assertEquals("migrax_backup_0005_orders_notes",
        KeepData.backupName("0005", "orders", "notes"));
    String long_ = KeepData.backupName("0005", "a_really_long_table_name_for_testing",
        "and_a_long_column_name_too");
    assertEquals(60, long_.length());
  }
}
