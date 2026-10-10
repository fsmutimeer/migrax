package io.migrax;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.Dialects;
import io.migrax.diff.DiffEngine;
import io.migrax.model.SchemaModel;
import io.migrax.ops.Operation;
import io.migrax.plugin.DatabaseSchemaReader;
import io.migrax.runner.MigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQLite's table rebuilds, run on a real SQLite database with foreign keys enforced. */
class SqliteDialectTest {
  private final Dialect dialect = Dialects.byName("sqlite");

  @TempDir
  Path folder;

  private static SchemaModel.Column column(String name, String type, boolean nullable,
                                           Integer length) {
    return new SchemaModel.Column(name, type, nullable, length, null, null, null, false, false,
        null, type);
  }

  private static SchemaModel.Column id() {
    return new SchemaModel.Column("id", "bigint", false, null, null, null, null, false, true, null,
        "bigint");
  }

  private static SchemaModel.Table customer(SchemaModel.Column name) {
    return new SchemaModel.Table("customer", List.of(id(), name),
        new SchemaModel.PrimaryKey(List.of("id"), "pk_customer"),
        List.of(new SchemaModel.Index("idx_customer_name", List.of("name"), false)), List.of());
  }

  private static SchemaModel.Table orders(List<SchemaModel.ForeignKey> keys) {
    return new SchemaModel.Table("orders",
        List.of(id(), column("customer_id", "bigint", true, null), column("note", "varchar", true,
            40)),
        new SchemaModel.PrimaryKey(List.of("id"), "pk_orders"), List.of(), keys);
  }

  private static final SchemaModel.ForeignKey ORDER_CUSTOMER = new SchemaModel.ForeignKey(
      "fk_orders_customer", List.of("customer_id"), "customer", List.of("id"));

  private Connection connect() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty("foreign_keys", "true");
    return DriverManager.getConnection("jdbc:sqlite:" + folder.resolve("shop.db"), properties);
  }

  /** Writes the migration from {@code before} to {@code after} as generate does, and runs it. */
  private void migrate(Connection connection, String name, SchemaModel before, SchemaModel after)
      throws Exception {
    List<Operation> operations = new DiffEngine().diff(before, after);
    StringBuilder sql = new StringBuilder();
    for (Operation operation : dialect.prepare(operations, before, after)) {
      sql.append(dialect.render(operation)).append(";\n");
    }
    Files.writeString(folder.resolve(name), sql);
    List<Path> files;
    try (var paths = Files.list(folder)) {
      files = paths.filter(f -> f.toString().endsWith(".sql")).sorted().toList();
    }
    new MigrationRunner().migrate(connection, files);
  }

  private static String query(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : null;
    }
  }

  @Test
  void rebuildsATableOthersReferToAndKeepsItsRows() throws Exception {
    SchemaModel first = new SchemaModel(List.of(customer(column("name", "varchar", true, 20)),
        orders(List.of(ORDER_CUSTOMER))), List.of());
    // name becomes longer and NOT NULL: SQLite can only do that by rebuilding customer.
    SchemaModel second = new SchemaModel(List.of(customer(column("name", "varchar", false, 80)),
        orders(List.of(ORDER_CUSTOMER))), List.of());
    try (Connection connection = connect()) {
      migrate(connection, "0001_initial.sql", SchemaModel.empty(), first);
      try (Statement statement = connection.createStatement()) {
        statement.execute("INSERT INTO customer (name) VALUES ('Ada')");
        statement.execute("INSERT INTO orders (customer_id, note) VALUES (1, 'first')");
      }
      migrate(connection, "0002_alter_customer_name.sql", first, second);

      assertEquals("Ada", query(connection, "SELECT name FROM customer WHERE id = 1"));
      assertEquals("1", query(connection, "SELECT customer_id FROM orders"));
      assertEquals("1", query(connection, "PRAGMA foreign_keys"),
          "the lock restores foreign key enforcement");
      SchemaModel.Table rebuilt = DatabaseSchemaReader.read(connection).table("customer");
      assertFalse(rebuilt.column("name").nullable());
      assertEquals(80, rebuilt.column("name").length());
      assertTrue(rebuilt.column("id").identity(), "the integer primary key is still the row id");
      assertEquals(List.of("idx_customer_name"),
          rebuilt.indexes().stream().map(SchemaModel.Index::name).toList());
      // The row id still fills itself.
      try (Statement statement = connection.createStatement()) {
        statement.execute("INSERT INTO customer (name) VALUES ('Grace')");
      }
      assertEquals("2", query(connection, "SELECT id FROM customer WHERE name = 'Grace'"));
      assertTrue(Files.readString(folder.resolve("0002_alter_customer_name.sql"))
          .contains("rebuilt with its rows"));
    }
  }

  @Test
  void aRebuildThatBreaksAForeignKeyFailsAndChangesNothing() throws Exception {
    SchemaModel first = new SchemaModel(List.of(customer(column("name", "varchar", true, 20)),
        orders(List.of())), List.of());
    SchemaModel second = new SchemaModel(List.of(customer(column("name", "varchar", true, 20)),
        orders(List.of(ORDER_CUSTOMER))), List.of());
    try (Connection connection = connect()) {
      migrate(connection, "0001_initial.sql", SchemaModel.empty(), first);
      try (Statement statement = connection.createStatement()) {
        statement.execute("INSERT INTO orders (customer_id, note) VALUES (42, 'no such customer')");
      }
      SQLException failure = assertThrows(SQLException.class,
          () -> migrate(connection, "0002_add_order_customer.sql", first, second));
      assertTrue(String.valueOf(failure.getCause()).contains("rebuilt_table_breaks_a_foreign_key"),
          String.valueOf(failure.getCause()));
      assertEquals(List.of(), DatabaseSchemaReader.read(connection).table("orders").foreignKeys(),
          "the failed rebuild was rolled back");
      assertEquals("no such customer", query(connection, "SELECT note FROM orders"));
    }
  }

  @Test
  void newTablesGetTheirForeignKeysInCreateTable() {
    SchemaModel schema = new SchemaModel(List.of(customer(column("name", "varchar", true, 20)),
        orders(List.of(ORDER_CUSTOMER))), List.of());
    List<Operation> operations = dialect.prepare(
        new DiffEngine().diff(SchemaModel.empty(), schema), SchemaModel.empty(), schema);
    String sql = String.join(";\n", operations.stream().map(dialect::render).toList());
    assertTrue(sql.contains("CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES "
        + "customer (id))"), sql);
    assertFalse(sql.contains("ALTER TABLE orders ADD CONSTRAINT"), sql);
    assertTrue(sql.contains("id integer NOT NULL"), "identity ids are the row id: " + sql);
  }
}
