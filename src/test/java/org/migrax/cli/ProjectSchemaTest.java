package org.migrax.cli;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectSchemaTest {
  @Test
  void aSchemaOnADatabaseWithoutSchemasIsAUsageError() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
      UsageException refused = assertThrows(UsageException.class,
          () -> Project.useSchema(connection, "app"));
      assertEquals("SQLite has no schemas.", refused.getMessage());
      assertEquals("Remove --schema/--schemas: the URL already names the database.",
          refused.hint);
    }
  }
}
