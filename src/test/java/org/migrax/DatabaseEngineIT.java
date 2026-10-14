package org.migrax;

import org.migrax.runner.MigrationRunner;
import org.migrax.runner.DatabaseMigrationLock;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.OracleContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseEngineIT {
  @TestFactory
  Stream<DynamicTest> appliesAndTracksMigrationsOnSupportedEngines() {
    return Stream.of(
        database("MySQL 8", () -> new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("migrax_test").withUsername("test").withPassword("test")),
        // How MySQL runs on Windows and macOS: table names are stored in lower case.
        database("MySQL 8 (lower_case_table_names=1)", () -> new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("migrax_test").withUsername("test").withPassword("test")
            .withCommand("--lower-case-table-names=1")),
        database("MariaDB 11", () -> new MariaDBContainer<>("mariadb:11.4")
            .withDatabaseName("migrax_test").withUsername("test").withPassword("test")),
        database("PostgreSQL 17", () -> new PostgreSQLContainer<>("postgres:17")
            .withDatabaseName("migrax_test").withUsername("test").withPassword("test")),
        database("SQL Server 2022", () -> new MSSQLServerContainer<>(
            "mcr.microsoft.com/mssql/server:2022-latest").acceptLicense()),
        database("Oracle XE 21", () -> new OracleContainer("gvenzl/oracle-xe:21-slim-faststart")),
        database("CockroachDB 24.3", () -> new CockroachContainer(
            "cockroachdb/cockroach:v24.3.11")),
        DynamicTest.dynamicTest("SQLite", () -> {
          Path file = Files.createTempFile("migrax-it-", ".db");
          try {
            Database sqlite = new Database("jdbc:sqlite:" + file, "", "");
            exerciseRunner(sqlite);
            exerciseGeneratedSchema(sqlite);
            exerciseProcedures(sqlite);
            exerciseCheckAndCommentChanges(sqlite);
            exerciseUnnamedCheck(sqlite);
            exerciseKeepData(sqlite);
          } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".migrax-lock"));
          }
        }));
  }

  /** Where a test database is reached. */
  private record Database(String url, String user, String password) {
    static Database of(JdbcDatabaseContainer<?> container) {
      return new Database(container.getJdbcUrl(), container.getUsername(),
          container.getPassword());
    }

    Connection connect() throws SQLException {
      return DriverManager.getConnection(url, user, password);
    }
  }

  private static DynamicTest database(
      String name, java.util.function.Supplier<JdbcDatabaseContainer<?>> createContainer) {
    return DynamicTest.dynamicTest(name, () -> {
      try (JdbcDatabaseContainer<?> container = createContainer.get()) {
        container.start();
        if (container instanceof OracleContainer oracle) {
          grantOracleLockPermission(oracle);
        }
        exerciseRunner(Database.of(container));
        exerciseGeneratedSchema(Database.of(container));
        exerciseProcedures(Database.of(container));
        exerciseCheckAndCommentChanges(Database.of(container));
        exerciseUnnamedCheck(Database.of(container));
        exerciseCockroachTypeChange(Database.of(container));
        exerciseKeepData(Database.of(container));
      }
    });
  }

  private static void grantOracleLockPermission(OracleContainer container) throws Exception {
    String username = container.getUsername().toUpperCase(java.util.Locale.ROOT);
    java.util.Properties properties = new java.util.Properties();
    properties.setProperty("user", "sys");
    properties.setProperty("password", container.getPassword());
    properties.setProperty("internal_logon", "sysdba");
    try (Connection admin = DriverManager.getConnection(container.getJdbcUrl(), properties);
         Statement statement = admin.createStatement()) {
      statement.execute("GRANT EXECUTE ON SYS.DBMS_LOCK TO " + username);
    }
  }

  /**
   * The end-to-end proof for each engine: apply the migration Migrax generates for the fixture
   * entities, then start Hibernate with schema validation and save and load rows.
   */
  private static void exerciseGeneratedSchema(Database container) throws Exception {
    Path migrations = Files.createTempDirectory("migrax-generated-it");
    try (Connection connection = container.connect()) {
      // Start from a clean history; the runner checks above recorded their own files.
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM migrax_history");
        statement.executeUpdate("DELETE FROM migrax_failures");
      }
      for (String probe : List.of("migration_probe", "partial_probe")) {
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DROP TABLE " + probe);
        } catch (SQLException absent) {
          // Engines with transactional DDL rolled the failed probe back.
        }
      }
      // From the server, not the URL: CockroachDB is reached with the PostgreSQL driver.
      org.migrax.dialect.Dialect dialect =
          org.migrax.dialect.Dialects.forConnection(connection).orElseThrow();
      HibernateValidationTest.generateAndApply(connection, org.migrax.model.NamingStrategy.SPRING,
          dialect, migrations.resolve("0100_entities.sql"));
      // The schema reader (used by drift and the first generate) must see exactly the
      // schema Migrax created on this engine.
      var expected = HibernateValidationTest.model(org.migrax.model.NamingStrategy.SPRING,
          dialect, org.migrax.model.ModelExtractor.Mode.ANNOTATIONS);
      var actual = org.migrax.plugin.DatabaseSchemaReader.read(connection);
      assertEquals(List.of(), org.migrax.verify.SchemaComparator.compare(expected, actual,
          dialect.id()).stream().map(Object::toString).toList(), dialect.id());
      // The first generate of a project compares the entities with the database. The database
      // already matches them, so it must find nothing to change.
      var previous = org.migrax.diff.DatabaseBaseline.read(connection, null, expected);
      var current = org.migrax.diff.DatabaseBaseline.align(expected, previous, dialect);
      assertEquals(List.of(), org.migrax.diff.Renames.diff(previous, current, List.of(), List.of())
          .stream().map(dialect::render).toList(), dialect.id() + ": first generate");
      // Most projects are read through Hibernate's own mapping, not annotation scanning.
      var hibernate = HibernateValidationTest.model(org.migrax.model.NamingStrategy.SPRING,
          dialect, org.migrax.model.ModelExtractor.Mode.HIBERNATE);
      var fromDatabase = org.migrax.diff.DatabaseBaseline.read(connection, null, hibernate);
      assertEquals(List.of(), org.migrax.diff.Renames.diff(fromDatabase,
              org.migrax.diff.DatabaseBaseline.align(hibernate, fromDatabase, dialect),
              List.of(), List.of()).stream().map(dialect::render).toList(),
          dialect.id() + ": first generate with Hibernate's mapping");
    }
    // Hibernate's validator rejects the integer row-id columns its own SQLite dialect creates
    // for Long ids; on SQLite, saving and loading rows is the proof.
    boolean validate = !container.url().startsWith("jdbc:sqlite:");
    try (org.hibernate.SessionFactory factory = HibernateValidationTest.hibernate(
        container.url(), container.user(), container.password(), true, validate)) {
      HibernateValidationTest.persistAndQuery(factory, !validate);
    }
    exerciseClean(container);
  }

  /** migrax clean leaves nothing behind: tables, foreign keys, views, sequences, history. */
  private static void exerciseClean(Database database) throws Exception {
    try (Connection connection = database.connect();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE VIEW customer_names AS SELECT full_name FROM customer");
    }
    Path project = Files.createTempDirectory("migrax-clean-it");
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
    int code = org.migrax.cli.Main.run(new String[] {"clean", "--yes", "--url", database.url(),
        "--user", database.user(), "--password", database.password(), "--dir",
        project.toString(), "--no-build"}, new java.io.PrintStream(out, true),
        new java.io.PrintStream(err, true));
    assertEquals(0, code, out + "\n" + err);
    try (Connection connection = database.connect()) {
      assertEquals(List.of(), org.migrax.plugin.DatabaseSchemaReader.objectNames(connection, null,
          "TABLE"), "tables after clean: " + out);
      assertEquals(List.of(), org.migrax.plugin.DatabaseSchemaReader.objectNames(connection, null,
          "VIEW"), "views after clean");
      assertEquals(List.of(), org.migrax.plugin.DatabaseSchemaReader.sequencesIn(connection, null),
          "sequences after clean");
    }
  }

  /**
   * --lock-timeout: while another connection holds the lock for about a second, a second
   * process waits for it instead of failing, and a too short wait fails with a clear message.
   */
  private static void waitsForTheLock(Database database) throws Exception {
    try (Connection holder = database.connect(); Connection waiter = database.connect()) {
      AutoCloseable held = DatabaseMigrationLock.acquire(holder);
      Thread releaser = new Thread(() -> {
        try {
          Thread.sleep(1200);
          held.close();
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      });
      long start = System.nanoTime();
      releaser.start();
      try (AutoCloseable lock = DatabaseMigrationLock.acquire(waiter,
          java.time.Duration.ofSeconds(30))) {
        org.junit.jupiter.api.Assertions.assertTrue(System.nanoTime() - start > 500_000_000L);
      }
      releaser.join();

      try (AutoCloseable again = DatabaseMigrationLock.acquire(holder)) {
        var error = assertThrows(org.migrax.dialect.MigrationLockHeldException.class,
            () -> DatabaseMigrationLock.acquire(waiter, java.time.Duration.ofMillis(800)));
        org.junit.jupiter.api.Assertions.assertTrue(
            error.getMessage().contains("Waited 800ms"), error.getMessage());
      }
    }
  }

  private static void exerciseRunner(Database container) throws Exception {
    Path migrations = Files.createTempDirectory("migrax-db-it");
    Path initial = migrations.resolve("0001_initial.sql");
    Files.writeString(initial, """
        CREATE TABLE migration_probe (id INTEGER PRIMARY KEY, content VARCHAR(100));
        INSERT INTO migration_probe (id, content) VALUES (1, 'semicolon; inside');
        """);
    Path failed = migrations.resolve("0002_failure.sql");
    Files.writeString(failed, """
        CREATE TABLE partial_probe (id INTEGER PRIMARY KEY);
        INSERT INTO migrax_missing_table VALUES (1);
        """);

    try (Connection connection = container.connect()) {
      try (Connection competingConnection = container.connect();
           AutoCloseable lock = DatabaseMigrationLock.acquire(connection)) {
        assertThrows(SQLException.class,
            () -> DatabaseMigrationLock.acquire(competingConnection));
      }
      waitsForTheLock(container);
      MigrationRunner runner = new MigrationRunner();
      runner.migrate(connection, List.of(initial));
      runner.migrate(connection, List.of(initial));

      try (Statement statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT COUNT(*) FROM migration_probe")) {
        rows.next();
        assertEquals(1, rows.getInt(1));
      }

      assertThrows(SQLException.class, () -> runner.migrate(connection, List.of(initial, failed)));
      assertThrows(IllegalStateException.class,
          () -> runner.migrate(connection, List.of(initial, failed)));

      Files.writeString(initial, """
          CREATE TABLE migration_probe (id INTEGER PRIMARY KEY, content VARCHAR(101));
          INSERT INTO migration_probe (id, content) VALUES (1, 'semicolon; inside');
          """);
      assertThrows(IllegalStateException.class, () -> runner.migrate(connection, List.of(initial)));
    }
  }

  /**
   * Procedures and triggers written the way each database's own tools expect them: GO batches
   * (SQL Server), DELIMITER (MySQL, MariaDB), a / line after PL/SQL (Oracle, also used for the
   * SQLite trigger) and dollar quotes (PostgreSQL). The migration runs, then the procedure or
   * trigger is used.
   */
  private static void exerciseProcedures(Database database) throws Exception {
    String url = database.url();
    String script;
    String call;
    if (url.startsWith("jdbc:sqlserver:")) {
      script = """
          CREATE TABLE note_log (id INT PRIMARY KEY, note VARCHAR(100));
          GO
          CREATE PROCEDURE add_note @note VARCHAR(100) AS
          BEGIN
            SET NOCOUNT ON;
            INSERT INTO note_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, @note FROM note_log;
          END;
          GO
          """;
      call = "EXEC add_note 'a; b'";
    } else if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) {
      script = """
          CREATE TABLE note_log (id INT PRIMARY KEY, note VARCHAR(100));
          DELIMITER $$
          CREATE PROCEDURE add_note(IN p_note VARCHAR(100))
          BEGIN
            DECLARE next_id INT;
            SELECT COALESCE(MAX(id), 0) + 1 INTO next_id FROM note_log;
            INSERT INTO note_log (id, note) VALUES (next_id, p_note);
          END$$
          DELIMITER ;
          """;
      call = "CALL add_note('a; b')";
    } else if (url.startsWith("jdbc:oracle:")) {
      script = """
          CREATE TABLE note_log (id NUMBER PRIMARY KEY, note VARCHAR2(100));
          CREATE OR REPLACE PROCEDURE add_note(p_note IN VARCHAR2) AS
          BEGIN
            INSERT INTO note_log (id, note) SELECT NVL(MAX(id), 0) + 1, p_note FROM note_log;
          END;
          /
          """;
      call = "BEGIN add_note('a; b'); END;";
    } else if (url.startsWith("jdbc:sqlite:")) {
      script = """
          CREATE TABLE note_log (id INTEGER PRIMARY KEY, note VARCHAR(100));
          CREATE TABLE note_source (note VARCHAR(100));
          CREATE TRIGGER add_note AFTER INSERT ON note_source
          BEGIN
            INSERT INTO note_log (id, note)
              SELECT COALESCE(MAX(id), 0) + 1, NEW.note FROM note_log;
          END;
          /
          """;
      call = "INSERT INTO note_source VALUES ('a; b')";
    } else {
      boolean cockroach;
      try (Connection connection = database.connect()) {
        cockroach = org.migrax.dialect.Dialects.forConnection(connection)
            .filter(d -> d.id().equals("cockroachdb")).isPresent();
      }
      if (cockroach) {
        return;
      }
      script = """
          CREATE TABLE note_log (id INTEGER PRIMARY KEY, note VARCHAR(100));
          CREATE FUNCTION add_note(p_note VARCHAR) RETURNS void AS $$
          BEGIN
            INSERT INTO note_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, p_note FROM note_log;
          END;
          $$ LANGUAGE plpgsql;
          """;
      call = "SELECT add_note('a; b')";
    }
    Path folder = Files.createTempDirectory("migrax-procedures-it");
    Path file = folder.resolve("0001_procedures.sql");
    Files.writeString(file, script);
    try (Connection connection = database.connect()) {
      new MigrationRunner().migrate(connection, List.of(file));
      try (Statement statement = connection.createStatement()) {
        statement.execute(call);
      }
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
      try (Statement statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT note FROM note_log")) {
        org.junit.jupiter.api.Assertions.assertTrue(rows.next(), url);
        assertEquals("a; b", rows.getString(1), url);
      }

      // The same script without GO, / or DELIMITER lines: Migrax finds where each body ends.
      String plain = script.lines()
          .filter(line -> !line.strip().matches("(?i)GO|/|DELIMITER\\s+\\S+"))
          .map(line -> line.replace("END$$", "END;"))
          .collect(java.util.stream.Collectors.joining("\n"))
          .replace("note_log", "note_log2").replace("add_note", "add_note2")
          .replace("note_source", "note_source2");
      Path plainFile = folder.resolve("0002_procedures_without_separators.sql");
      Files.writeString(plainFile, plain);
      List<Path> files = new java.util.ArrayList<>(List.of(file, plainFile));
      boolean sqlServer = url.startsWith("jdbc:sqlserver:");
      if (sqlServer) {
        Path repeat = folder.resolve("0003_repeat.sql");
        Files.writeString(repeat, "EXEC add_note2 'r'\nGO 3\n");
        files.add(repeat);
      }
      try {
        new MigrationRunner().migrate(connection, files);
      } catch (Exception e) {
        throw new AssertionError(url + " failed:\n" + plain, e);
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute(call.replace("add_note", "add_note2").replace("note_source",
            "note_source2"));
      }
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
      try (Statement statement = connection.createStatement();
           var rows = statement.executeQuery(
               "SELECT COUNT(*) FROM note_log2 WHERE note = 'a; b'")) {
        rows.next();
        assertEquals(1, rows.getInt(1), url);
      }
      if (sqlServer) {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM note_log2 WHERE note = 'r'")) {
          rows.next();
          assertEquals(3, rows.getInt(1), "GO 3");
        }
      }
    }
  }

  /**
   * Checks and comments change on a table that exists: a changed condition, a check added
   * later, a comment changed and one removed, then everything removed. After each migration the
   * database must have exactly the checks and comments of the model.
   */
  private static void exerciseCheckAndCommentChanges(Database database) throws Exception {
    org.migrax.model.SchemaModel first = probe(List.of("price >= 0"), "Things we sell",
        "In cents", null);
    org.migrax.model.SchemaModel second = probe(List.of("price > 0", "discount <= 100"),
        "Products we sell", null, "Percent");
    org.migrax.model.SchemaModel third = probe(List.of(), null, null, null);
    Path folder = Files.createTempDirectory("migrax-checks-it");
    List<Path> files = new java.util.ArrayList<>();
    try (Connection connection = database.connect()) {
      org.migrax.dialect.Dialect dialect =
          org.migrax.dialect.Dialects.forConnection(connection).orElseThrow();
      boolean comments = !dialect.id().equals("sqlite");
      // Start from a clean history; the steps above recorded their own files (CockroachDB
      // skips the procedures, so after clean it has no history tables).
      for (String table : List.of("migrax_history", "migrax_failures")) {
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM " + table);
        } catch (SQLException absent) {
          // Created by the first migration below.
        }
      }
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
      org.migrax.model.SchemaModel before = org.migrax.model.SchemaModel.empty();
      int version = 9001;
      for (org.migrax.model.SchemaModel after : List.of(first, second, third)) {
        List<org.migrax.ops.Operation> operations = dialect.prepare(
            new org.migrax.diff.DiffEngine().diff(before, after), before, after);
        String sql = operations.stream().map(o -> dialect.render(o) + ";\n")
            .collect(java.util.stream.Collectors.joining());
        Path file = folder.resolve(version++ + "_checks.sql");
        Files.writeString(file, sql);
        files.add(file);
        try {
          new MigrationRunner().migrate(connection, files);
        } catch (Exception e) {
          throw new AssertionError(dialect.id() + " failed:\n" + sql, e);
        }
        org.migrax.model.SchemaModel.Table expected = after.table("price_probe");
        org.migrax.model.SchemaModel.Table actual =
            org.migrax.plugin.DatabaseSchemaReader.read(connection).table("price_probe");
        assertEquals(expected.checks().stream().map(c -> c.name().toLowerCase(
                java.util.Locale.ROOT)).sorted().toList(),
            actual.checks().stream().map(c -> c.name().toLowerCase(java.util.Locale.ROOT))
                .sorted().toList(), dialect.id() + " checks after:\n" + sql);
        if (comments) {
          assertEquals(expected.comment(), actual.comment(), dialect.id() + ":\n" + sql);
          for (org.migrax.model.SchemaModel.Column column : expected.columns()) {
            assertEquals(column.comment(), actual.column(column.name()).comment(),
                dialect.id() + " " + column.name() + ":\n" + sql);
          }
        }
        before = after;
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute("DROP TABLE price_probe");
      }
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
    }
  }

  /** The probe table with the given check conditions and comments. */
  private static org.migrax.model.SchemaModel probe(List<String> conditions, String comment,
                                                     String priceComment,
                                                     String discountComment) {
    org.migrax.model.SchemaModel.Column id = new org.migrax.model.SchemaModel.Column("id",
        "bigint", false, null, null, null, null, false, false, null, "bigint");
    org.migrax.model.SchemaModel.Column price = new org.migrax.model.SchemaModel.Column("price",
        "integer", false, null, null, null, null, false, false, null, "integer", priceComment);
    org.migrax.model.SchemaModel.Column discount = new org.migrax.model.SchemaModel.Column(
        "discount", "integer", true, null, null, null, null, false, false, null, "integer",
        discountComment);
    List<org.migrax.model.SchemaModel.Check> checks = conditions.stream()
        .map(c -> new org.migrax.model.SchemaModel.Check(
            org.migrax.model.SchemaModel.checkConstraintName("price_probe", c), c))
        .toList();
    return new org.migrax.model.SchemaModel(List.of(new org.migrax.model.SchemaModel.Table(
        "price_probe", List.of(id, price, discount),
        new org.migrax.model.SchemaModel.PrimaryKey(List.of("id"), "price_probe_pkey"),
        List.of(), List.of(), checks, comment)));
  }

  /**
   * A database Hibernate created: its unnamed check got a name from the database, and the
   * condition comes back in the database's own words. The first generate must recognize it
   * and neither add it again nor drop it.
   */
  private static void exerciseUnnamedCheck(Database database) throws Exception {
    try (Connection connection = database.connect()) {
      org.migrax.dialect.Dialect dialect =
          org.migrax.dialect.Dialects.forConnection(connection).orElseThrow();
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE unnamed_probe (id INT NOT NULL PRIMARY KEY, "
            + "balance DECIMAL(12,2), CHECK (balance >= 0))");
      }
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
      org.migrax.model.SchemaModel.Column id = new org.migrax.model.SchemaModel.Column("id",
          "integer", false, null, null, null, null, false, false, null, "integer");
      org.migrax.model.SchemaModel.Column balance = new org.migrax.model.SchemaModel.Column(
          "balance", "decimal", true, null, 12, 2, null, false, false, null, "decimal");
      org.migrax.model.SchemaModel entities = new org.migrax.model.SchemaModel(List.of(
          new org.migrax.model.SchemaModel.Table("unnamed_probe", List.of(id, balance),
              new org.migrax.model.SchemaModel.PrimaryKey(List.of("id")), List.of(), List.of(),
              List.of(new org.migrax.model.SchemaModel.Check(
                  org.migrax.model.SchemaModel.checkConstraintName("unnamed_probe",
                      "balance >= 0"), "balance >= 0")), null)));
      var previous = org.migrax.diff.DatabaseBaseline.read(connection, null, entities);
      var current = org.migrax.diff.DatabaseBaseline.align(entities, previous, dialect);
      List<String> checkChanges = new org.migrax.diff.DiffEngine().diff(previous, current)
          .stream().filter(o -> o instanceof org.migrax.ops.AddCheck
              || o instanceof org.migrax.ops.DropCheck).map(dialect::render).toList();
      assertEquals(List.of(), checkChanges, dialect.id() + ": database has "
          + previous.table("unnamed_probe"));
      try (Statement statement = connection.createStatement()) {
        statement.execute("DROP TABLE unnamed_probe");
      }
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
    }
  }

  /**
   * CockroachDB narrows a column type only outside a transaction with its experimental
   * setting; the generated migration must say so and run.
   */
  private static void exerciseCockroachTypeChange(Database database) throws Exception {
    try (Connection connection = database.connect()) {
      org.migrax.dialect.Dialect dialect =
          org.migrax.dialect.Dialects.forConnection(connection).orElseThrow();
      if (!dialect.id().equals("cockroachdb")) {
        return;
      }
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE type_probe (id INT8 PRIMARY KEY, n INT8, s VARCHAR(20))");
        statement.execute("INSERT INTO type_probe VALUES (1, 5, 'abc')");
      }
      var n = new org.migrax.model.SchemaModel.Column("n", "bigint", true, null, null, null,
          null, false, false, null, "bigint");
      var s = new org.migrax.model.SchemaModel.Column("s", "varchar", true, 20, null, null,
          null, false, false, null, "varchar");
      List<org.migrax.ops.Operation> operations = dialect.prepare(List.of(
          new org.migrax.ops.AlterColumn("type_probe", n, new org.migrax.model.SchemaModel.Column(
              "n", "integer", true, null, null, null, null, false, false, null, "integer")),
          new org.migrax.ops.AlterColumn("type_probe", s, new org.migrax.model.SchemaModel.Column(
              "s", "varchar", true, 10, null, null, null, false, false, null, "varchar"))),
          org.migrax.model.SchemaModel.empty(), org.migrax.model.SchemaModel.empty());
      String sql = operations.stream().map(o -> dialect.render(o) + ";\n")
          .collect(java.util.stream.Collectors.joining());
      Path folder = Files.createTempDirectory("migrax-cockroach-types");
      Path file = folder.resolve("0001_narrow.sql");
      Files.writeString(file, sql);
      for (String table : List.of("migrax_history", "migrax_failures")) {
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM " + table);
        } catch (SQLException absent) {
          // Created by the migration below.
        }
      }
      try {
        new MigrationRunner().migrate(connection, List.of(file));
      } catch (Exception e) {
        throw new AssertionError("cockroachdb failed:\n" + sql, e);
      }
      try (Statement statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT column_name, data_type, "
               + "character_maximum_length FROM information_schema.columns WHERE table_name = "
               + "'type_probe' AND column_name IN ('n', 's') ORDER BY column_name")) {
        rows.next();
        assertEquals("integer", rows.getString(2), sql);
        rows.next();
        assertEquals(10, rows.getInt(3), sql);
      }
      try (Statement statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT n, s FROM type_probe")) {
        rows.next();
        assertEquals(5, rows.getInt(1));
        assertEquals("abc", rows.getString(2));
        statement.execute("DROP TABLE type_probe");
      }
    }
  }

  /** generate --keep-data: migrate, roll back, and every row and value is back. */
  private static void exerciseKeepData(Database database) throws Exception {
    try (Connection connection = database.connect()) {
      for (String table : List.of("migrax_history", "migrax_failures")) {
        try (Statement statement = connection.createStatement()) {
          statement.executeUpdate("DELETE FROM " + table);
        } catch (SQLException absent) {
          // Created by the first migration of the scenario.
        }
      }
      if (!connection.getAutoCommit()) {
        connection.commit();
      }
      org.migrax.cli.KeepDataScenario.run(connection,
          org.migrax.dialect.Dialects.forConnection(connection).orElseThrow());
    }
  }
}
