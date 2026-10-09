package io.migrax;

import io.migrax.api.JavaMigration;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationRunner;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunnerFeaturesTest {
  private static int counter;

  private static Connection database() throws SQLException {
    return DriverManager.getConnection("jdbc:h2:mem:runner_features_" + (++counter)
        + ";DB_CLOSE_DELAY=-1");
  }

  private static long count(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private static List<String> history(Connection connection) throws SQLException {
    return new MigrationRunner().applied(connection).stream()
        .map(MigrationRunner.AppliedMigration::version).toList();
  }

  private static Migration sql(String name, String sql) {
    return Migration.ofSql(name, sql);
  }

  private static int migrate(Connection connection, List<Migration> migrations)
      throws Exception {
    return new MigrationRunner().migrateAll(connection, migrations,
        MigrationRunner.Options.defaults());
  }

  @Test
  void ordersDottedVersionsNumerically() {
    List<String> names = new ArrayList<>(List.of("V1.10__c.sql", "V1.2__b.sql", "V1__a.sql",
        "0003_x.sql", "V2__d.sql"));
    names.sort(MigrationRunner::compareMigrationFilenames);
    assertEquals(List.of("V1__a.sql", "V1.2__b.sql", "V1.10__c.sql", "V2__d.sql", "0003_x.sql"),
        names);
  }

  @Test
  void repeatableMigrationsRunAgainWhenTheyChange() throws Exception {
    try (Connection connection = database()) {
      Migration table = sql("0001_t.sql", "CREATE TABLE t (id INT);");
      migrate(connection, List.of(table, sql("R__view.sql", "CREATE OR REPLACE VIEW v AS SELECT id FROM t;")));
      assertEquals(0, migrate(connection, List.of(table,
          sql("R__view.sql", "CREATE OR REPLACE VIEW v AS SELECT id FROM t;"))));
      assertEquals(1, migrate(connection, List.of(table,
          sql("R__view.sql", "CREATE OR REPLACE VIEW v AS SELECT id, id * 2 AS twice FROM t;"))));
      assertEquals(0, count(connection, "SELECT COUNT(twice) FROM v"));
      var status = new MigrationRunner().statusOf(connection, List.of(table,
          sql("R__view.sql", "CREATE OR REPLACE VIEW v AS SELECT 1 AS one;")));
      assertEquals(MigrationRunner.State.OUTDATED, status.get(1).state());
    }
  }

  @Test
  void callbacksAndPlaceholdersRun() throws Exception {
    try (Connection connection = database()) {
      List<Migration> migrations = List.of(
          sql("beforeMigrate.sql", "CREATE TABLE IF NOT EXISTS audit (event VARCHAR(50));"),
          sql("afterEachMigrate.sql", "INSERT INTO audit VALUES ('each');"),
          sql("0001_t.sql", "CREATE TABLE ${table} (id INT);"),
          sql("0002_u.sql", "INSERT INTO ${table} VALUES (1);"));
      new MigrationRunner().migrateAll(connection, migrations,
          new MigrationRunner.Options(false, Map.of("table", "widgets")));
      assertEquals(1, count(connection, "SELECT COUNT(*) FROM widgets"));
      assertEquals(2, count(connection, "SELECT COUNT(*) FROM audit"));
      assertEquals(List.of("0001_t.sql", "0002_u.sql"), history(connection));
    }
  }

  /** A Java migration for the test. */
  public static final class V0002__InsertRow implements JavaMigration {
    @Override
    public void migrate(Connection connection) throws Exception {
      try (var statement = connection.createStatement()) {
        statement.executeUpdate("INSERT INTO t VALUES (42)");
      }
    }

    @Override
    public void rollback(Connection connection) throws Exception {
      try (var statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM t WHERE id = 42");
      }
    }
  }

  @Test
  void javaMigrationsRunInVersionOrderAndRollBack() throws Exception {
    try (Connection connection = database()) {
      Migration java = Migration.ofJava(new V0002__InsertRow());
      migrate(connection, List.of(java, sql("0001_t.sql", "CREATE TABLE t (id INT);")));
      assertEquals(1, count(connection, "SELECT COUNT(*) FROM t WHERE id = 42"));
      assertEquals(List.of("0001_t.sql", "V0002__InsertRow"), history(connection));

      new MigrationRunner().rollback(connection, "V0002__InsertRow", java,
          MigrationRunner.Options.defaults());
      assertEquals(0, count(connection, "SELECT COUNT(*) FROM t"));
      assertEquals(List.of("0001_t.sql"), history(connection));
    }
  }

  @Test
  void rollbackScriptsRevertAndUnrecord() throws Exception {
    try (Connection connection = database()) {
      migrate(connection, List.of(sql("0001_t.sql", "CREATE TABLE t (id INT);")));
      new MigrationRunner().rollback(connection, "0001_t.sql", sql("0001_t.sql", "DROP TABLE t;"),
          MigrationRunner.Options.defaults());
      assertTrue(history(connection).isEmpty());
      assertThrows(SQLException.class, () -> count(connection, "SELECT COUNT(*) FROM t"));
      assertThrows(IllegalStateException.class, () -> new MigrationRunner().rollback(connection,
          "0001_t.sql", sql("0001_t.sql", "DROP TABLE t;"), MigrationRunner.Options.defaults()));
    }
  }

  private static final Migration A = sql("0001_a.sql", "CREATE TABLE a (id INT);");
  private static final Migration B = sql("0002_b.sql", "CREATE TABLE b (id INT);");
  private static final Migration SQUASH = sql("0002_squashed_0001_0002.sql",
      "-- migrax:replaces 0001_a.sql,0002_b.sql\nCREATE TABLE a (id INT);\nCREATE TABLE b (id INT);");

  @Test
  void squashRunsOnFreshDatabasesAndRecordsTheOriginals() throws Exception {
    try (Connection connection = database()) {
      assertEquals(1, migrate(connection, List.of(A, B, SQUASH)));
      assertEquals(List.of("0001_a.sql", "0002_b.sql", "0002_squashed_0001_0002.sql"),
          history(connection));
      // Later the originals are deleted: nothing is missing.
      assertEquals(0, migrate(connection, List.of(SQUASH)));
    }
  }

  @Test
  void squashIsOnlyRecordedWhereTheOriginalsWereApplied() throws Exception {
    try (Connection connection = database()) {
      migrate(connection, List.of(A, B));
      assertEquals(0, migrate(connection, List.of(A, B, SQUASH)));
      assertTrue(history(connection).contains("0002_squashed_0001_0002.sql"));
      assertEquals(0, migrate(connection, List.of(SQUASH)));
    }
  }

  @Test
  void partlyAppliedSquashKeepsUsingTheOriginals() throws Exception {
    try (Connection connection = database()) {
      migrate(connection, List.of(A));
      assertEquals(1, migrate(connection, List.of(A, B, SQUASH)));
      assertEquals(0, count(connection, "SELECT COUNT(*) FROM b"));
      assertTrue(history(connection).contains("0002_squashed_0001_0002.sql"));
    }
  }

  @Test
  void partlyAppliedSquashWithoutTheOriginalsFails() throws Exception {
    try (Connection connection = database()) {
      migrate(connection, List.of(A));
      assertThrows(IllegalStateException.class, () -> migrate(connection, List.of(A, SQUASH)));
    }
  }

  @Test
  void markAppliedRecordsWithoutRunning() throws Exception {
    try (Connection connection = database()) {
      assertEquals(1, new MigrationRunner().markApplied(connection,
          List.of(sql("V1__init.sql", "CREATE TABLE never_run (id INT);"))));
      assertEquals(List.of("V1__init.sql"), history(connection));
      assertThrows(SQLException.class, () -> count(connection, "SELECT COUNT(*) FROM never_run"));
    }
  }
}
