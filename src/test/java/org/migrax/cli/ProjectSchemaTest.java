package org.migrax.cli;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
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

  @Test
  void aDestructiveCommandUsesTheStoredSchemaName() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:stored_schema")) {
      connection.createStatement().execute("CREATE SCHEMA TEN");
      assertEquals("TEN", Project.useStoredSchema(connection, "ten"));
      assertEquals("TEN", connection.getSchema());
    }
  }

  @Test
  void aSwitchThatLandsInAnotherSchemaIsRefused() throws Exception {
    try (Connection h2 = DriverManager.getConnection("jdbc:h2:mem:folded_schema")) {
      h2.createStatement().execute("CREATE SCHEMA APP");
      // As Oracle does with a schema stored as "app" next to APP.
      Connection folding = (Connection) Proxy.newProxyInstance(
          getClass().getClassLoader(), new Class<?>[] {Connection.class},
          (proxy, method, args) -> "getSchema".equals(method.getName()) ? "OTHER"
              : method.invoke(h2, args));
      UsageException refused = assertThrows(UsageException.class,
          () -> Project.useStoredSchema(folding, "APP"));
      assertEquals("--schema APP names schema APP, but the connection switched to OTHER.",
          refused.getMessage());
    }
  }
}
