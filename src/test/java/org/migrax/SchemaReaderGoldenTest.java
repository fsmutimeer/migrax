package org.migrax;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

/**
 * The schema reader's golden test on H2, which runs in memory with the unit tests; the
 * other databases run it in {@code DatabaseEngineIT}.
 */
class SchemaReaderGoldenTest {

  @Test
  void readsTheFixtureOnH2AsRecorded() throws Exception {
    try (Connection connection = DriverManager.getConnection(
        "jdbc:h2:mem:migrax_golden;DB_CLOSE_DELAY=-1", "sa", "")) {
      SchemaReaderGolden.check(connection, "h2");
    }
  }
}
