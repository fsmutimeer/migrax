package io.migrax;

import io.migrax.runner.MigrationRunner;
import io.migrax.runner.DatabaseMigrationLock;
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
            "cockroachdb/cockroach:v24.3.11")));
  }

  private static DynamicTest database(
      String name, java.util.function.Supplier<JdbcDatabaseContainer<?>> createContainer) {
    return DynamicTest.dynamicTest(name, () -> {
      try (JdbcDatabaseContainer<?> container = createContainer.get()) {
        container.start();
        if (container instanceof OracleContainer oracle) {
          grantOracleLockPermission(oracle);
        }
        exerciseRunner(container);
        exerciseGeneratedSchema(container);
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
  private static void exerciseGeneratedSchema(JdbcDatabaseContainer<?> container)
      throws Exception {
    Path migrations = Files.createTempDirectory("migrax-generated-it");
    try (Connection connection = DriverManager.getConnection(
        container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
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
      io.migrax.dialect.Dialect dialect =
          io.migrax.dialect.Dialects.forConnection(connection).orElseThrow();
      HibernateValidationTest.generateAndApply(connection, io.migrax.model.NamingStrategy.SPRING,
          dialect, migrations.resolve("0100_entities.sql"));
      // The schema reader (used by drift and the first generate) must see exactly the
      // schema Migrax created on this engine.
      var expected = HibernateValidationTest.model(io.migrax.model.NamingStrategy.SPRING,
          dialect, io.migrax.model.ModelExtractor.Mode.ANNOTATIONS);
      var actual = io.migrax.plugin.DatabaseSchemaReader.read(connection);
      assertEquals(List.of(), io.migrax.verify.SchemaComparator.compare(expected, actual,
          dialect.id()).stream().map(Object::toString).toList(), dialect.id());
      // The first generate of a project compares the entities with the database. The database
      // already matches them, so it must find nothing to change.
      var previous = io.migrax.diff.DatabaseBaseline.read(connection, null, expected);
      var current = io.migrax.diff.DatabaseBaseline.align(expected, previous, dialect);
      assertEquals(List.of(), io.migrax.diff.Renames.diff(previous, current, List.of(), List.of())
          .stream().map(dialect::render).toList(), dialect.id() + ": first generate");
      // Most projects are read through Hibernate's own mapping, not annotation scanning.
      var hibernate = HibernateValidationTest.model(io.migrax.model.NamingStrategy.SPRING,
          dialect, io.migrax.model.ModelExtractor.Mode.HIBERNATE);
      var fromDatabase = io.migrax.diff.DatabaseBaseline.read(connection, null, hibernate);
      assertEquals(List.of(), io.migrax.diff.Renames.diff(fromDatabase,
              io.migrax.diff.DatabaseBaseline.align(hibernate, fromDatabase, dialect),
              List.of(), List.of()).stream().map(dialect::render).toList(),
          dialect.id() + ": first generate with Hibernate's mapping");
    }
    try (org.hibernate.SessionFactory factory = HibernateValidationTest.hibernate(
        container.getJdbcUrl(), container.getUsername(), container.getPassword(), true)) {
      HibernateValidationTest.persistAndQuery(factory);
    }
  }

  private static void exerciseRunner(JdbcDatabaseContainer<?> container) throws Exception {
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

    try (Connection connection = DriverManager.getConnection(
        container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
      try (Connection competingConnection = DriverManager.getConnection(
          container.getJdbcUrl(), container.getUsername(), container.getPassword());
           AutoCloseable lock = DatabaseMigrationLock.acquire(connection)) {
        assertThrows(SQLException.class,
            () -> DatabaseMigrationLock.acquire(competingConnection));
      }
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
}
