package org.migrax;

import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;
import org.migrax.runner.Precondition;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code -- migrax:require} lines: checked before a migration runs, in migrate and --sql. */
class PreconditionsTest {
  private static int counter;

  private static Connection database() throws SQLException {
    return DriverManager.getConnection("jdbc:h2:mem:preconditions_" + (++counter)
        + ";DB_CLOSE_DELAY=-1");
  }

  private static Migration sql(String name, String sql) {
    return Migration.ofSql(name, sql);
  }

  private static int migrate(Connection connection, List<Migration> migrations)
      throws Exception {
    return new MigrationRunner().migrateAll(connection, migrations,
        MigrationRunner.Options.defaults());
  }

  private static List<String> history(Connection connection) throws SQLException {
    return new MigrationRunner().applied(connection).stream()
        .map(MigrationRunner.AppliedMigration::version).toList();
  }

  private static boolean tableExists(Connection connection, String table) throws SQLException {
    try (var tables = connection.getMetaData().getTables(null, null, table, null)) {
      return tables.next();
    }
  }

  @Test
  void linesAreRead() {
    List<Precondition> read = Precondition.of(sql("0001_a.sql", """
        -- migrax:require table-exists app.customer
        -- MIGRAX:REQUIRE column-missing customer.email else skip
        -- migrax:require sql select count(*) = 0 from customer where note is null else fail
        -- migrax:requires-nothing is not a precondition
        SELECT 1;
        """));
    assertEquals(List.of(
        new Precondition(Precondition.Check.TABLE_EXISTS, "app.customer", false),
        new Precondition(Precondition.Check.COLUMN_MISSING, "customer.email", true),
        new Precondition(Precondition.Check.SQL,
            "select count(*) = 0 from customer where note is null", false)), read);
  }

  @Test
  void wrongLinesNameTheMigration() {
    for (String line : List.of("-- migrax:require table-there x", "-- migrax:require sql",
        "-- migrax:require column-exists email", "-- migrax:require table-exists a b")) {
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> Precondition.of(sql("0001_a.sql", line + "\nSELECT 1;")));
      assertTrue(error.getMessage().startsWith("Migration '0001_a.sql': "), error.getMessage());
    }
  }

  @Test
  void aWrongLineStopsTheRunBeforeAnythingRuns() throws Exception {
    try (Connection connection = database()) {
      List<Migration> migrations = List.of(sql("0001_t.sql", "CREATE TABLE t (id INT);"),
          sql("0002_u.sql", "-- migrax:require table-exist t\nCREATE TABLE u (id INT);"));
      assertThrows(IllegalArgumentException.class, () -> migrate(connection, migrations));
      assertFalse(tableExists(connection, "T"));
    }
  }

  @Test
  void anUnmetConditionStopsTheRunAtThatMigration() throws Exception {
    try (Connection connection = database()) {
      List<Migration> migrations = List.of(sql("0001_t.sql", "CREATE TABLE t (id INT);"),
          sql("0002_u.sql", "-- migrax:require table-exists legacy\nCREATE TABLE u (id INT);"));
      IllegalStateException error = assertThrows(IllegalStateException.class,
          () -> migrate(connection, migrations));
      assertTrue(error.getMessage().startsWith("Migration '0002_u.sql' requires table-exists "
          + "legacy, which is not met here."), error.getMessage());
      assertEquals(List.of("0001_t.sql"), history(connection));
      assertFalse(tableExists(connection, "U"));
      // Nothing was recorded as failed: once the table is there, the next run applies it.
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE legacy (id INT)");
      }
      assertEquals(1, migrate(connection, migrations));
      assertTrue(tableExists(connection, "U"));
    }
  }

  @Test
  void conditionsSeeTheMigrationsBeforeThem() throws Exception {
    try (Connection connection = database()) {
      assertEquals(2, migrate(connection, List.of(
          sql("0001_t.sql", "CREATE TABLE customer (id INT, note VARCHAR(10));"),
          sql("0002_u.sql", """
              -- migrax:require table-exists CUSTOMER
              -- migrax:require column-exists customer.note
              -- migrax:require column-missing customer.email
              -- migrax:require table-missing customer_email
              -- migrax:require sql select count(*) = 0 from customer
              ALTER TABLE customer ADD COLUMN email VARCHAR(50);
              """))));
    }
  }

  @Test
  void elseSkipRecordsTheMigrationWithoutRunningIt() throws Exception {
    try (Connection connection = database()) {
      List<Migration> migrations = List.of(
          sql("0001_t.sql", "CREATE TABLE customer (id INT);"),
          sql("0002_seed.sql", """
              -- migrax:require sql select count(*) from customer where id = ${first} else skip
              DELETE FROM customer;
              """),
          sql("0003_u.sql", "CREATE TABLE u (id INT);"));
      int applied = new MigrationRunner().migrateAll(connection, migrations,
          new MigrationRunner.Options(false, Map.of("first", "1")));
      assertEquals(2, applied);
      assertEquals(List.of("0001_t.sql", "0002_seed.sql", "0003_u.sql"), history(connection));
      assertEquals(MigrationRunner.State.APPLIED,
          new MigrationRunner().statusOf(connection, migrations).get(1).state());
    }
  }

  @Test
  void statusShowsTheConditionsOfPendingMigrations() throws Exception {
    try (Connection connection = database()) {
      var status = new MigrationRunner().statusOf(connection, List.of(
          sql("0001_t.sql", "CREATE TABLE t (id INT);"),
          sql("0002_u.sql", "-- migrax:require table-exists t\n"
              + "-- migrax:require table-missing u else skip\nCREATE TABLE u (id INT);")));
      assertEquals("", status.get(0).detail());
      assertEquals("requires table-exists t; requires table-missing u, else skipped",
          status.get(1).detail());
    }
  }

  @Test
  void theSqlScriptChecksTheFirstMigrationAndStopsBeforeLaterOnes() throws Exception {
    try (Connection connection = database()) {
      MigrationRunner runner = new MigrationRunner();
      Migration table = sql("0001_t.sql", "CREATE TABLE t (id INT);");
      migrate(connection, List.of(table));
      Migration skipped = sql("0002_s.sql", "-- migrax:require table-exists legacy else skip\n"
          + "DROP TABLE legacy;");
      Migration checked = sql("0003_c.sql", "-- migrax:require table-exists t\n"
          + "ALTER TABLE t ADD COLUMN note VARCHAR(10);");
      Migration later = sql("0004_l.sql", "-- migrax:require column-exists t.note\n"
          + "ALTER TABLE t DROP COLUMN note;");
      String script = runner.script(connection, List.of(table, skipped, checked, later),
          MigrationRunner.Options.defaults());
      assertTrue(script.contains("-- 0002_s.sql: skipped, table-exists legacy is not met; "
          + "recorded without running\nINSERT INTO migrax_history"), script);
      assertFalse(script.contains("DROP TABLE legacy"), script);
      // 0003 comes after a skip, which changes nothing, so its condition is checked now.
      assertTrue(script.contains("-- 0003_c.sql\n-- requires table-exists t: met when this "
          + "script was written\nBEGIN;\n-- migrax:require table-exists t\n"
          + "ALTER TABLE t ADD COLUMN note"), script);
      assertTrue(script.contains("-- Stopped before 0004_l.sql: its preconditions can only be "
          + "checked\n-- after the migrations before it ran.\n-- After this script, write the "
          + "next one with 'migrax migrate --sql'.\n"), script);
      assertFalse(script.contains("DROP COLUMN note"), script);

      Migration failing = sql("0002_s.sql", "-- migrax:require table-exists legacy\nSELECT 1;");
      IllegalStateException error = assertThrows(IllegalStateException.class,
          () -> runner.script(connection, List.of(table, failing),
              MigrationRunner.Options.defaults()));
      assertTrue(error.getMessage().contains("requires table-exists legacy"), error.getMessage());
    }
  }
}
