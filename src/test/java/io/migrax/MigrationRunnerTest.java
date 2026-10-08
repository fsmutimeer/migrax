package io.migrax;

import io.migrax.runner.MigrationRunner;
import io.migrax.runner.DatabaseMigrationLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationRunnerTest {
  @TempDir
  Path migrations;

  @Test
  void splitsOnlyStatementDelimitersAndRecordsAppliedChecksum() throws Exception {
    Path migration = migrations.resolve("0001_initial.sql");
    Files.writeString(migration, """
        -- comment with ; semicolon
        CREATE TABLE notes (note_text VARCHAR(100));
        INSERT INTO notes VALUES ('first;value');
        /* block; comment */
        INSERT INTO notes VALUES ('second value');
        """);

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:migration-runner;DB_CLOSE_DELAY=-1")) {
      MigrationRunner runner = new MigrationRunner();
      assertEquals(1, runner.migrate(connection, List.of(migration)));
      assertEquals(0, runner.migrate(connection, List.of(migration)));

      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT COUNT(*) FROM notes")) {
        rows.next();
        assertEquals(2, rows.getInt(1));
      }
      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT COUNT(*) FROM migrax_history")) {
        rows.next();
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  @Test
  void migrationLockIsExclusiveWithinTheH2ProcessAndReleasedAfterClose() throws Exception {
    try (var first = DriverManager.getConnection("jdbc:h2:mem:migration-lock;DB_CLOSE_DELAY=-1");
         var second = DriverManager.getConnection("jdbc:h2:mem:migration-lock;DB_CLOSE_DELAY=-1")) {
      AutoCloseable held = DatabaseMigrationLock.acquire(first);
      var executor = Executors.newSingleThreadExecutor();
      try {
        boolean denied = executor.submit(() -> {
          try (AutoCloseable ignored = DatabaseMigrationLock.acquire(second)) {
            return false;
          } catch (SQLException expected) {
            return true;
          }
        }).get();
        assertTrue(denied);
      } finally {
        executor.shutdownNow();
      }
      held.close();

      try (AutoCloseable reacquired = DatabaseMigrationLock.acquire(second)) {
        assertFalse(second.isClosed());
      }
    }
  }

  @Test
  void refusesToRunWhenChecksumOfAppliedMigrationChanges() throws Exception {
    Path migration = migrations.resolve("0001_initial.sql");
    Files.writeString(migration, "CREATE TABLE checksummed (id INT PRIMARY KEY);");

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:migration-checksum;DB_CLOSE_DELAY=-1")) {
      MigrationRunner runner = new MigrationRunner();
      runner.migrate(connection, List.of(migration));
      Files.writeString(migration, "CREATE TABLE checksummed (id BIGINT PRIMARY KEY);");

      assertThrows(IllegalStateException.class, () -> runner.migrate(connection, List.of(migration)));
    }
  }

  @Test
  void refusesToContinueWhenAnAppliedMigrationFileWasDeleted() throws Exception {
    Path migration = migrations.resolve("0001_initial.sql");
    Files.writeString(migration, "CREATE TABLE retained (id INT PRIMARY KEY);");

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:migration-missing;DB_CLOSE_DELAY=-1")) {
      MigrationRunner runner = new MigrationRunner();
      runner.migrate(connection, List.of(migration));

      assertThrows(IllegalStateException.class, () -> runner.migrate(connection, List.of()));
    }
  }

  @Test
  void blocksFailedMigrationByDefaultAndResumesOnlyExplicitlyMarkedSql() throws Exception {
    Path migration = migrations.resolve("0001_resume.sql");
    Files.writeString(migration, """
        -- migrax:resume-safe
        CREATE TABLE IF NOT EXISTS resume_target (id INT PRIMARY KEY);
        MERGE INTO resume_target KEY(id) SELECT id FROM resume_source;
        """);

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:migration-resume;DB_CLOSE_DELAY=-1")) {
      MigrationRunner runner = new MigrationRunner();
      assertThrows(SQLException.class, () -> runner.migrate(connection, List.of(migration)));
      assertThrows(IllegalStateException.class, () -> runner.migrate(connection, List.of(migration)));

      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE resume_source (id INT PRIMARY KEY)");
        statement.execute("INSERT INTO resume_source VALUES (7)");
      }
      runner.migrate(connection, List.of(migration), true);
      runner.migrate(connection, List.of(migration));

      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT COUNT(*) FROM resume_target")) {
        rows.next();
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  @Test
  void repairRetryRequiresExplicitConfirmationAndMatchingFailedFile() throws Exception {
    Path migration = migrations.resolve("0001_repair.sql");
    Files.writeString(migration, "CREATE TABLE repaired (id INT); INSERT INTO missing_table VALUES (1);");

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:migration-repair;DB_CLOSE_DELAY=-1")) {
      MigrationRunner runner = new MigrationRunner();
      assertThrows(SQLException.class, () -> runner.migrate(connection, List.of(migration)));
      assertThrows(IllegalArgumentException.class,
          () -> runner.repair(connection, migration, "retry", false));

      try (var statement = connection.createStatement()) {
        statement.execute("DROP TABLE repaired");
      }
      runner.repair(connection, migration, "retry", true);
      Files.writeString(migration, "CREATE TABLE repaired (id INT);");
      runner.migrate(connection, List.of(migration));

      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT COUNT(*) FROM migrax_history")) {
        rows.next();
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  @Test
  void repairAppliedRequiresOperatorConfirmationAndSkipsVerifiedMigration() throws Exception {
    Path migration = migrations.resolve("0001_already_present.sql");
    Files.writeString(migration, "CREATE TABLE already_present (id INT PRIMARY KEY);");

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:migration-repair-applied;DB_CLOSE_DELAY=-1")) {
      try (var statement = connection.createStatement()) {
        statement.execute("CREATE TABLE already_present (id INT PRIMARY KEY)");
      }
      MigrationRunner runner = new MigrationRunner();
      assertThrows(SQLException.class, () -> runner.migrate(connection, List.of(migration)));
      runner.repair(connection, migration, "applied", true);
      runner.migrate(connection, List.of(migration));

      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT COUNT(*) FROM migrax_history")) {
        rows.next();
        assertEquals(1, rows.getInt(1));
      }
    }
  }

  @Test
  void rejectsUnterminatedSqlQuotesWithoutApplyingMigration() throws Exception {
    Path migration = migrations.resolve("0001_invalid.sql");
    Files.writeString(migration, "CREATE TABLE broken (value VARCHAR(20)); INSERT INTO broken VALUES ('unterminated);");

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:migration-invalid;DB_CLOSE_DELAY=-1")) {
      assertThrows(IllegalArgumentException.class, () -> new MigrationRunner().migrate(
          connection, List.of(migration)));
      try (var statement = connection.createStatement()) {
        assertThrows(SQLException.class, () -> statement.executeQuery("SELECT * FROM broken"));
      }
    }
  }

  @Test
  void sortsMigrationsNumericallySoTenDoesNotPrecedeTwo() throws Exception {
    Path m2 = migrations.resolve("2_create_table.sql");
    Files.writeString(m2, "CREATE TABLE authors (id BIGINT PRIMARY KEY, name VARCHAR(100));");

    Path m10 = migrations.resolve("10_add_column.sql");
    Files.writeString(m10, "ALTER TABLE authors ADD COLUMN bio VARCHAR(255);");

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:migration-numeric-sort;DB_CLOSE_DELAY=-1")) {
      MigrationRunner runner = new MigrationRunner();
      // Pass in reverse order (10 before 2); runner must sort numerically and apply 2 before 10
      assertEquals(2, runner.migrate(connection, List.of(m10, m2)));

      try (var statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT version FROM migrax_history ORDER BY applied_at")) {
        rows.next();
        assertEquals("2_create_table.sql", rows.getString(1));
        rows.next();
        assertEquals("10_add_column.sql", rows.getString(1));
      }
    }
  }
}
