package org.migrax;

import org.migrax.runner.Migration;
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
  /**
   * Every engine, or with {@code -Dmigrax.it.engines=PostgreSQL,Oracle} only those whose name
   * contains one of the given words.
   */
  @TestFactory
  Stream<DynamicTest> appliesAndTracksMigrationsOnSupportedEngines() {
    String only = System.getProperty("migrax.it.engines", "");
    return engines().filter(test -> only.isBlank() || java.util.Arrays.stream(only.split(","))
        .anyMatch(word -> test.getDisplayName().toLowerCase(java.util.Locale.ROOT)
            .contains(word.trim().toLowerCase(java.util.Locale.ROOT))));
  }

  private Stream<DynamicTest> engines() {
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
        // Oracle 23ai has a boolean type, which Hibernate uses there.
        database("Oracle Free 23ai", () -> new org.testcontainers.oracle.OracleContainer(
            "gvenzl/oracle-free:23-slim-faststart")),
        database("CockroachDB 24.3", () -> new CockroachContainer(
            "cockroachdb/cockroach:v24.3.11")),
        // Reached with the PostgreSQL driver, as most applications do.
        database("YugabyteDB 2026.1", () -> new YugabyteWithPostgresDriver(
            "yugabytedb/yugabyte:2026.1.2.0-b137")
            .withDatabaseName("migrax_test").withUsername("test").withPassword("test")),
        database("TiDB 8.5", () -> new org.testcontainers.tidb.TiDBContainer(
            "pingcap/tidb:v8.5.3")),
        // The 7.5 long-term line.
        database("TiDB 7.5", () -> new org.testcontainers.tidb.TiDBContainer(
            "pingcap/tidb:v7.5.6")),
        // The 2024.2 long-term line: PostgreSQL 11 based, no advisory locks.
        database("YugabyteDB 2024.2", () -> new YugabyteWithPostgresDriver(
            "yugabytedb/yugabyte:2024.2.11.0-b36")
            .withDatabaseName("migrax_test").withUsername("test").withPassword("test")),
        DynamicTest.dynamicTest("Derby 10.16", () -> {
          Database derby = new Database("jdbc:derby:memory:migrax_it;create=true", "", "");
          exerciseRunner(derby);
          exerciseGeneratedSchema(derby);
          exerciseProcedures(derby);
          exerciseCheckAndCommentChanges(derby);
          exerciseUnnamedCheck(derby);
          exerciseKeepData(derby);
          exerciseZeroDowntime(derby);
          exerciseScriptTimestamps(derby);
          exerciseSchemas(derby);
        }),
        // In memory: every connection of this JVM shares the database by its name.
        DynamicTest.dynamicTest("HSQLDB 2.7", () -> {
          Database hsqldb = new Database("jdbc:hsqldb:mem:migrax_it", "SA", "");
          exerciseRunner(hsqldb);
          exerciseGeneratedSchema(hsqldb);
          exerciseProcedures(hsqldb);
          exerciseCheckAndCommentChanges(hsqldb);
          exerciseUnnamedCheck(hsqldb);
          exerciseKeepData(hsqldb);
          exerciseZeroDowntime(hsqldb);
          exerciseScriptTimestamps(hsqldb);
          exerciseSchemas(hsqldb);
        }),
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
            exerciseZeroDowntime(sqlite);
          } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".migrax-lock"));
          }
        }));
  }

  /** YugabyteDB through the PostgreSQL driver instead of its own. */
  private static final class YugabyteWithPostgresDriver
      extends org.testcontainers.containers.YugabyteDBYSQLContainer {
    YugabyteWithPostgresDriver(String image) {
      super(image);
    }

    @Override
    public String getDriverClassName() {
      return "org.postgresql.Driver";
    }

    @Override
    public String getJdbcUrl() {
      return super.getJdbcUrl().replaceFirst("^jdbc:yugabytedb:", "jdbc:postgresql:");
    }
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
        if (container.getDockerImageName().contains("gvenzl/oracle")) {
          grantOracleLockPermission(container);
        }
        if (container instanceof MySQLContainer<?> mysql) {
          allowMySqlTriggers(mysql);
        }
        if (container instanceof org.testcontainers.tidb.TiDBContainer) {
          // Off by default: TiDB parses check constraints and ignores them.
          try (Connection root = Database.of(container).connect();
               Statement statement = root.createStatement()) {
            statement.execute("SET GLOBAL tidb_enable_check_constraint = ON");
          }
        }
        exerciseRunner(Database.of(container));
        exerciseGeneratedSchema(Database.of(container));
        exerciseProcedures(Database.of(container));
        exerciseCheckAndCommentChanges(Database.of(container));
        exerciseUnnamedCheck(Database.of(container));
        exerciseCockroachTypeChange(Database.of(container));
        exerciseKeepData(Database.of(container));
        exerciseZeroDowntime(Database.of(container));
        exerciseScriptTimestamps(Database.of(container));
        exerciseSchemas(Database.of(container));
      }
    });
  }

  /**
   * MySQL with binary logging (its default) lets only SUPER users create triggers, unless
   * log_bin_trust_function_creators is set, as on most managed servers that allow triggers.
   */
  private static void allowMySqlTriggers(MySQLContainer<?> container) throws Exception {
    try (Connection root = DriverManager.getConnection(container.getJdbcUrl(), "root",
        container.getPassword()); Statement statement = root.createStatement()) {
      statement.execute("SET GLOBAL log_bin_trust_function_creators = 1");
    }
  }

  private static void grantOracleLockPermission(JdbcDatabaseContainer<?> container)
      throws Exception {
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
      // HyperSQL: Hibernate keeps Instant as timestamp, while annotation scanning (for
      // projects without a JPA provider) writes timestamp with time zone, so the schema comes
      // from Hibernate's mapping there, as it does in a Hibernate project.
      org.migrax.model.ModelExtractor.Mode mode = dialect.id().equals("hsqldb")
          ? org.migrax.model.ModelExtractor.Mode.HIBERNATE
          : org.migrax.model.ModelExtractor.Mode.ANNOTATIONS;
      HibernateValidationTest.generateAndApply(connection, org.migrax.model.NamingStrategy.SPRING,
          dialect, migrations.resolve("0100_entities.sql"), mode);
      // The schema reader (used by drift and the first generate) must see exactly the
      // schema Migrax created on this engine.
      var expected = HibernateValidationTest.model(org.migrax.model.NamingStrategy.SPRING,
          dialect, mode);
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
      HibernateValidationTest.persistAndQuery(factory, !validate, !yugabyte(container));
    }
    exerciseDiff(container);
    exerciseClean(container);
    exerciseApi(container);
  }

  /**
   * What migrax diff does, on each engine: the generated schema is read, the database emptied
   * and rebuilt from the changes diff finds, and then diff must find nothing.
   */
  private static void exerciseDiff(Database database) throws Exception {
    org.migrax.dialect.Dialect dialect;
    org.migrax.model.SchemaModel source;
    try (Connection connection = database.connect()) {
      dialect = org.migrax.dialect.Dialects.forConnection(connection).orElseThrow();
      source = org.migrax.plugin.DatabaseSchemaReader.read(connection);
    }
    clean(database);
    Path file = Files.createTempDirectory("migrax-diff-it").resolve("0001_diff.sql");
    try (Connection connection = database.connect()) {
      org.migrax.model.SchemaModel empty = org.migrax.plugin.DatabaseSchemaReader.read(connection);
      String sql = dialect.prepare(new org.migrax.diff.DiffEngine().diff(empty, source), empty,
              source).stream().map(operation -> dialect.render(operation) + ";\n")
          .collect(java.util.stream.Collectors.joining());
      Files.writeString(file, sql);
      try {
        new MigrationRunner().migrate(connection, List.of(file));
      } catch (Exception e) {
        throw new AssertionError(dialect.id() + ": rebuilding failed:\n" + sql, e);
      }
      org.migrax.model.SchemaModel rebuilt =
          org.migrax.plugin.DatabaseSchemaReader.read(connection);
      assertEquals(List.of(), new org.migrax.diff.DiffEngine().diff(rebuilt, source).stream()
          .map(dialect::render).toList(), dialect.id() + ": diff after rebuilding\n" + sql);
    }
  }

  /** migrax clean leaves nothing behind: tables, foreign keys, views, sequences, history. */
  private static void exerciseClean(Database database) throws Exception {
    try (Connection connection = database.connect();
         Statement statement = connection.createStatement()) {
      statement.execute("CREATE VIEW customer_names AS SELECT full_name FROM customer");
    }
    String out = clean(database);
    try (Connection connection = database.connect()) {
      assertEquals(List.of(), org.migrax.plugin.DatabaseSchemaReader.objectNames(connection, null,
          "TABLE"), "tables after clean: " + out);
      assertEquals(List.of(), org.migrax.plugin.DatabaseSchemaReader.objectNames(connection, null,
          "VIEW"), "views after clean");
      assertEquals(List.of(), org.migrax.plugin.DatabaseSchemaReader.sequencesIn(connection, null),
          "sequences after clean");
    }
  }

  private static boolean tidb(Database database) throws SQLException {
    try (Connection connection = database.connect()) {
      return org.migrax.dialect.Dialects.forConnection(connection)
          .filter(d -> d.id().equals("tidb")).isPresent();
    }
  }

  private static boolean yugabyte(Database database) throws SQLException {
    try (Connection connection = database.connect()) {
      return org.migrax.dialect.Dialects.forConnection(connection)
          .filter(d -> d.id().equals("yugabytedb")).isPresent();
    }
  }

  /** Runs migrax clean; returns its output. */
  private static String clean(Database database) throws Exception {
    Path project = Files.createTempDirectory("migrax-clean-it");
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
    int code = org.migrax.cli.Main.run(new String[] {"clean", "--yes", "--url", database.url(),
        "--user", database.user(), "--password", database.password(), "--dir",
        project.toString(), "--no-build"}, new java.io.PrintStream(out, true),
        new java.io.PrintStream(err, true));
    assertEquals(0, code, out + "\n" + err);
    return out.toString();
  }

  /**
   * The Java API on each engine: applies the generated migration, finds that the entities match
   * (read through Hibernate's mapping), tests the rollback script and rolls back.
   */
  private static void exerciseApi(Database database) throws Exception {
    Path folder = Files.createTempDirectory("migrax-api-it");
    org.migrax.dialect.Dialect dialect;
    try (Connection connection = database.connect()) {
      dialect = org.migrax.dialect.Dialects.forConnection(connection).orElseThrow();
    }
    var model = HibernateValidationTest.model(org.migrax.model.NamingStrategy.SPRING, dialect,
        org.migrax.model.ModelExtractor.Mode.HIBERNATE);
    Files.writeString(folder.resolve("0001_entities.sql"),
        HibernateValidationTest.generate(org.migrax.model.NamingStrategy.SPRING, dialect,
            org.migrax.model.ModelExtractor.Mode.HIBERNATE));
    Files.createDirectories(folder.resolve("rollback"));
    Files.writeString(folder.resolve("rollback/0001_entities.sql"),
        new org.migrax.diff.DiffEngine().diff(model, org.migrax.model.SchemaModel.empty())
            .stream().map(dialect::render).map(statement -> statement + ";")
            .collect(java.util.stream.Collectors.joining("\n")));
    org.migrax.api.Migrax migrax = org.migrax.api.Migrax.configure()
        .url(database.url(), database.user(), database.password())
        .locations("filesystem:" + folder).javaPackage(null).projectDirectory(folder)
        .entities("org.migrax.hibernate.fixtures").naming("spring").load();
    assertEquals(1, migrax.migrate().applied(), dialect.id());
    var validation = migrax.validate();
    assertEquals(List.of(), validation.problems(), dialect.id());
    assertEquals("Hibernate", validation.entities().split(" ")[0], dialect.id());
    var rollbacks = migrax.testRollbacks();
    assertEquals("ok (1 rolled back and re-applied)", rollbacks.summary(), dialect.id());
    assertEquals(List.of("0001_entities.sql"), migrax.rollback(1), dialect.id());
    assertEquals(1, migrax.status().pending(), dialect.id());
    clean(database);
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
      exercisePreconditions(connection);
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
   * {@code -- migrax:require} lines find tables and columns however the database folds names,
   * run queries, and skip a migration whose 'else skip' condition is not met. Leaves the
   * database as it found it.
   */
  private static void exercisePreconditions(Connection connection) throws Exception {
    List<Migration> migrations = List.of(
        Migration.ofSql("0001_pre_probe.sql", """
            CREATE TABLE pre_probe (id INTEGER PRIMARY KEY, note VARCHAR(10));
            INSERT INTO pre_probe (id, note) VALUES (1, 'one');
            """),
        Migration.ofSql("0002_pre_second.sql", """
            -- migrax:require table-exists pre_probe
            -- migrax:require table-exists PRE_PROBE
            -- migrax:require column-exists pre_probe.note
            -- migrax:require column-missing pre_probe.email
            -- migrax:require table-missing pre_absent
            -- migrax:require sql select count(*) from pre_probe where note = 'one'
            CREATE TABLE pre_second (id INTEGER PRIMARY KEY);
            """),
        Migration.ofSql("0003_pre_skipped.sql", """
            -- migrax:require table-exists pre_absent else skip
            DROP TABLE pre_absent;
            """));
    MigrationRunner runner = new MigrationRunner();
    assertEquals(2, runner.migrateAll(connection, migrations, MigrationRunner.Options.defaults()));
    assertEquals(List.of("0001_pre_probe.sql", "0002_pre_second.sql", "0003_pre_skipped.sql"),
        runner.applied(connection).stream().map(MigrationRunner.AppliedMigration::version)
            .toList());
    Migration unmet = Migration.ofSql("0004_pre_unmet.sql",
        "-- migrax:require column-exists pre_probe.email\nDROP TABLE pre_second;\n");
    List<Migration> withUnmet = new java.util.ArrayList<>(migrations);
    withUnmet.add(unmet);
    assertThrows(IllegalStateException.class,
        () -> runner.migrateAll(connection, withUnmet, MigrationRunner.Options.defaults()));
    try (Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE pre_second");
      statement.execute("DROP TABLE pre_probe");
      statement.execute("DELETE FROM migrax_history");
    }
    if (!connection.getAutoCommit()) {
      connection.commit();
    }
  }

  /**
   * Procedures and triggers written the way each database's own tools expect them: GO batches
   * (SQL Server), DELIMITER (MySQL, MariaDB), a / line after PL/SQL (Oracle, also used for the
   * SQLite trigger) and dollar quotes (PostgreSQL). The migration runs, then the procedure or
   * trigger is used.
   */
  private static void exerciseProcedures(Database database) throws Exception {
    if (tidb(database) || database.url().startsWith("jdbc:derby:")) {
      return; // TiDB has no stored procedures; Derby's are Java methods.
    }
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
    } else if (url.startsWith("jdbc:hsqldb:")) {
      script = """
          CREATE TABLE note_log (id INT PRIMARY KEY, note VARCHAR(100));
          CREATE PROCEDURE add_note(IN p_note VARCHAR(100))
          MODIFIES SQL DATA
          BEGIN ATOMIC
            INSERT INTO note_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, p_note FROM note_log;
          END;
          """;
      call = "CALL add_note('a; b')";
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
      boolean oracle = url.startsWith("jdbc:oracle:");
      if (oracle) {
        // A package, its body and an anonymous block, also without / lines.
        Path pkg = folder.resolve("0003_package.sql");
        Files.writeString(pkg, """
            CREATE OR REPLACE PACKAGE notes AS
              PROCEDURE add(p_note IN VARCHAR2);
            END notes;
            CREATE OR REPLACE PACKAGE BODY notes AS
              PROCEDURE add(p_note IN VARCHAR2) IS
              BEGIN
                add_note2(p_note || '!');
              END add;
            END notes;
            DECLARE
              note VARCHAR2(10) := 'pkg';
            BEGIN
              notes.add(note);
            END;
            """);
        files.add(pkg);
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
      if (oracle) {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery(
                 "SELECT COUNT(*) FROM note_log2 WHERE note = 'pkg!'")) {
          rows.next();
          assertEquals(1, rows.getInt(1), "package and anonymous block");
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
      boolean comments = !dialect.id().equals("sqlite") && !dialect.id().equals("derby");
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
  /**
   * A {@code migrate --sql} script run in a session with another time zone (as psql uses the
   * server's zone) records its migrations at the same time of day as Migrax records its own.
   */
  private static void exerciseScriptTimestamps(Database database) throws Exception {
    try (Connection connection = database.connect();
         Connection other = database.connect()) {
      if (!connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT)
          .contains("postgresql")) {
        return;
      }
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM migrax_history");
      }
      Path folder = Files.createTempDirectory("migrax-timestamps-it");
      Path first = folder.resolve("0001_first_stamp.sql");
      Files.writeString(first, "CREATE TABLE first_stamp (id INT);\n");
      MigrationRunner runner = new MigrationRunner();
      runner.migrate(connection, List.of(first));
      String script = runner.script(connection, List.of(
          org.migrax.runner.Migration.ofSql("0001_first_stamp.sql",
              Files.readString(first)),
          org.migrax.runner.Migration.ofSql("0002_second_stamp.sql",
              "CREATE TABLE second_stamp (id INT);\n")),
          MigrationRunner.Options.defaults());
      try (Statement statement = other.createStatement()) {
        statement.execute("SET TIME ZONE 'Pacific/Kiritimati'");
        for (String sql : org.migrax.runner.SqlScript.split(script, false)) {
          statement.execute(sql);
        }
      }
      try (Statement statement = connection.createStatement();
           var rows = statement.executeQuery("SELECT version, applied_at FROM migrax_history "
               + "WHERE version LIKE '%_stamp.sql' ORDER BY version")) {
        List<java.time.LocalDateTime> times = new java.util.ArrayList<>();
        while (rows.next()) {
          times.add(rows.getTimestamp(2).toLocalDateTime());
        }
        assertEquals(2, times.size());
        long minutes = java.time.Duration.between(times.get(0), times.get(1)).abs().toMinutes();
        org.junit.jupiter.api.Assertions.assertTrue(minutes < 10, times.toString());
      }
    }
  }

  /**
   * --schemas: the same migrations in two schemas, each with its own history, through migrate,
   * status, rollback, migrate --sql and clean. SQL Server can't switch schemas, so there the
   * generated migration names them with ${migrax.schema}; PostgreSQL switches the search path.
   * The default schema stays untouched.
   */
  private static void exerciseSchemas(Database database) throws Exception {
    org.migrax.dialect.Dialect dialect;
    try (Connection connection = database.connect()) {
      dialect = org.migrax.dialect.Dialects.forConnection(connection).orElseThrow();
    }
    boolean sqlServer = dialect instanceof org.migrax.dialect.SqlServerDialect;
    if (!sqlServer && !"postgresql".equals(dialect.id())) {
      return;
    }
    List<String> tenants = List.of("tenant_a", "tenant_b");
    List<String> defaultSchema;
    try (Connection connection = database.connect();
         Statement statement = connection.createStatement()) {
      for (String tenant : tenants) {
        statement.execute("CREATE SCHEMA " + tenant);
      }
      defaultSchema = org.migrax.plugin.DatabaseSchemaReader.objectNames(connection, null,
          "TABLE");
    }
    Path project = Files.createTempDirectory("migrax-schemas-it");
    Path migrations = project.resolve("migrations");
    Files.createDirectories(migrations.resolve("rollback"));
    org.migrax.dialect.Dialect generating = sqlServer
        ? ((org.migrax.dialect.SqlServerDialect) dialect).inSchema("${migrax.schema}")
        : dialect;
    Files.writeString(migrations.resolve("0001_entities.sql"), HibernateValidationTest.generate(
        org.migrax.model.NamingStrategy.SPRING, generating,
        org.migrax.model.ModelExtractor.Mode.ANNOTATIONS));
    Files.writeString(migrations.resolve("0002_notes.sql"), """
        CREATE TABLE ${migrax.schema}.note (id INT PRIMARY KEY, body VARCHAR(100));
        INSERT INTO ${migrax.schema}.note (id, body) VALUES (1, 'in ${migrax.schema}');
        """);
    Files.writeString(migrations.resolve("rollback/0002_notes.sql"),
        "DROP TABLE ${migrax.schema}.note;\n");
    java.util.function.Function<String[], String> cli = args -> {
      List<String> all = new java.util.ArrayList<>(List.of(args));
      all.addAll(List.of("--schemas", String.join(",", tenants), "--url", database.url(),
          "--user", database.user(), "--password", database.password(), "--dir",
          project.toString(), "--no-build"));
      if (!args[0].equals("clean")) {
        all.addAll(List.of("--locations", migrations.toString()));
      }
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
      int code = org.migrax.cli.Main.run(all.toArray(String[]::new),
          new java.io.PrintStream(out, true), new java.io.PrintStream(err, true));
      assertEquals(0, code, all + "\n" + out + "\n" + err);
      return out.toString();
    };

    cli.apply(new String[] {"migrate"});
    assertSchemas(database, tenants, 2, true);
    String status = cli.apply(new String[] {"status"});
    org.junit.jupiter.api.Assertions.assertFalse(status.toLowerCase(java.util.Locale.ROOT)
        .contains("pending"), status);

    cli.apply(new String[] {"rollback", "--yes"});
    assertSchemas(database, tenants, 1, false);

    // The script a DBA would run: one part per schema, the history in each.
    String script = cli.apply(new String[] {"migrate", "--sql"});
    for (String tenant : tenants) {
      org.junit.jupiter.api.Assertions.assertTrue(script.contains(sqlServer
          ? "INSERT INTO [" + tenant + "].migrax_history" : "SET search_path TO " + tenant),
          script);
    }
    try (Connection connection = database.connect();
         Statement statement = connection.createStatement()) {
      for (String sql : org.migrax.runner.SqlScript.split(script, false)) {
        statement.execute(sql);
      }
    }
    assertSchemas(database, tenants, 2, true);

    // generate --keep-data in a schema: backups, restores and renames name it on SQL Server.
    try (Connection connection = database.connect()) {
      // The scenario brings its own migrations; the history of the ones above would be missing.
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM tenant_b.migrax_history");
      }
      org.migrax.cli.KeepDataScenario.run(connection, generating, "tenant_b");
    }

    cli.apply(new String[] {"clean", "--yes"});
    try (Connection connection = database.connect()) {
      for (String tenant : tenants) {
        assertEquals(List.of(), org.migrax.plugin.DatabaseSchemaReader.objectNames(connection,
            tenant, "TABLE"), tenant + " after clean");
      }
      assertEquals(defaultSchema, org.migrax.plugin.DatabaseSchemaReader.objectNames(connection,
          null, "TABLE"), "default schema after clean");
      try (Statement statement = connection.createStatement()) {
        for (String tenant : tenants) {
          statement.execute("DROP SCHEMA " + tenant);
        }
      }
    }
  }

  /** Each schema has its history, its own note (or none), and the default schema has none. */
  private static void assertSchemas(Database database, List<String> tenants, int applied,
                                    boolean notes) throws Exception {
    try (Connection connection = database.connect();
         Statement statement = connection.createStatement()) {
      for (String tenant : tenants) {
        try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + tenant
            + ".migrax_history")) {
          rows.next();
          assertEquals(applied, rows.getInt(1), tenant + " history");
        }
        if (notes) {
          try (var rows = statement.executeQuery("SELECT body FROM " + tenant + ".note")) {
            rows.next();
            assertEquals("in " + tenant, rows.getString(1));
          }
        }
        assertEquals(notes, org.migrax.plugin.DatabaseSchemaReader.objectNames(connection,
            tenant, "TABLE").stream().anyMatch("note"::equalsIgnoreCase), tenant + " note");
      }
      assertEquals(false, org.migrax.plugin.DatabaseSchemaReader.objectNames(connection, null,
          "TABLE").stream().anyMatch("note"::equalsIgnoreCase), "note in the default schema");
    }
  }

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

  /** generate --zero-downtime: both application versions between expand and contract. */
  private static void exerciseZeroDowntime(Database database) throws Exception {
    try (Connection connection = database.connect()) {
      org.migrax.cli.ZeroDowntimeScenario.run(connection,
          org.migrax.dialect.Dialects.forConnection(connection).orElseThrow());
    }
  }
}
