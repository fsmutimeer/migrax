package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.diff.Renames;
import org.migrax.model.SchemaModel;
import org.migrax.ops.Operation;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renames end to end on a real database, step by step as a project goes through them: a column
 * rename, a table rename with a foreign key (whose generated name changes), a rename of the
 * table the key points at, a rename of the key's own column, and a rename next to wider columns
 * (whose rollback makes a decimal smaller again, with rows in it).
 * Each step is generated the way {@code generate --rename/--rename-table} does it, migrated,
 * and checked: no step may be destructive, the rows keep their values and the key keeps
 * pointing at the right rows. Then every step is rolled back, newest first, and the first
 * schema and its rows must be back. Shared by the H2 unit test and the integration test on
 * every database.
 */
public final class RenameScenario {
  private RenameScenario() {}

  /** One step: the model after it and the renames that lead there. */
  private record Step(String name, SchemaModel model, List<Renames.TableRename> tables,
                      List<Renames.ColumnRename> columns) {}

  private static SchemaModel.Column column(String name, String type, boolean nullable,
                                           Integer length, Integer precision, Integer scale) {
    return new SchemaModel.Column(name, type, nullable, length, precision, scale, null, false,
        false, null, type);
  }

  private static SchemaModel.Column id() {
    return column("id", "bigint", false, null, null, null);
  }

  /**
   * The two tables after a step.
   *
   * @param customers the customer table's name
   * @param nameColumn the customer name column
   * @param orders the order table's name
   * @param keyColumn the order's column that points at the customer
   * @param total the order total column
   * @param precision its precision
   * @param email the e-mail column's length
   */
  private static SchemaModel model(String customers, String nameColumn, String orders,
                                   String keyColumn, String total, int precision,
                                   int email) {
    SchemaModel.Table customerTable = new SchemaModel.Table(customers,
        List.of(id(), column("email", "varchar", false, email, null, null),
            column(nameColumn, "varchar", false, 100, null, null)),
        new SchemaModel.PrimaryKey(List.of("id"), "rs_customer_pkey"), List.of(), List.of());
    // Named like Hibernate names keys: after the table and the column.
    SchemaModel.ForeignKey key = new SchemaModel.ForeignKey("fk_" + orders + "_" + keyColumn,
        List.of(keyColumn), customers, List.of("id"));
    SchemaModel.Table orderTable = new SchemaModel.Table(orders,
        List.of(id(), column(keyColumn, "bigint", false, null, null, null),
            column(total, "decimal", false, null, precision, 2)),
        new SchemaModel.PrimaryKey(List.of("id"), "rs_order_pkey"), List.of(), List.of(key));
    return new SchemaModel(List.of(customerTable, orderTable));
  }

  private static List<Step> steps() {
    return List.of(
        new Step("0061_create", model("rs_customer", "name", "rs_order", "customer_id",
            "total", 12, 150), List.of(), List.of()),
        new Step("0062_rename_column", model("rs_customer", "full_name", "rs_order",
            "customer_id", "total", 12, 150), List.of(),
            List.of(new Renames.ColumnRename("rs_customer", "name", "full_name"))),
        new Step("0063_rename_table", model("rs_customer", "full_name", "rs_purchase",
            "customer_id", "total", 12, 150),
            List.of(new Renames.TableRename("rs_order", "rs_purchase")), List.of()),
        new Step("0064_rename_referenced_table", model("rs_client", "full_name", "rs_purchase",
            "customer_id", "total", 12, 150),
            List.of(new Renames.TableRename("rs_customer", "rs_client")), List.of()),
        new Step("0065_rename_key_column", model("rs_client", "full_name", "rs_purchase",
            "client_id", "total", 12, 150), List.of(),
            List.of(new Renames.ColumnRename("rs_purchase", "customer_id", "client_id"))),
        new Step("0066_rename_and_widen", model("rs_client", "full_name", "rs_purchase",
            "client_id", "amount", 14, 200), List.of(),
            List.of(new Renames.ColumnRename("rs_purchase", "total", "amount"))));
  }

  public static void run(Connection connection, Dialect dialect) throws Exception {
    Path folder = Files.createTempDirectory("migrax-renames");
    MigrationRunner runner = new MigrationRunner();
    List<Path> files = new ArrayList<>();
    List<String> rollbacks = new ArrayList<>();
    SchemaModel previous = SchemaModel.empty();
    for (Step step : steps()) {
      List<Operation> forward = Renames.diff(previous, step.model(), step.tables(),
          step.columns());
      List<Operation> reverse = Renames.reverse(previous, step.model(), step.tables(),
          step.columns());
      if (!previous.tables().isEmpty()) {
        assertFalse(forward.stream().anyMatch(Operation::destructive),
            dialect.id() + " " + step.name() + ": destructive " + forward);
      }
      String sql = MigrationSql.render(dialect, forward, null, previous, step.model());
      rollbacks.add(MigrationSql.render(dialect, reverse, null, step.model(), previous));
      Path file = folder.resolve(step.name() + ".sql");
      Files.writeString(file, sql);
      files.add(file);
      try {
        runner.migrate(connection, files);
      } catch (Exception e) {
        throw new AssertionError(dialect.id() + " " + step.name() + " failed:\n" + sql, e);
      }
      if (previous.tables().isEmpty()) {
        execute(connection,
            "INSERT INTO rs_customer (id, email, name) VALUES (1, 'ada@example.com', 'Ada')",
            "INSERT INTO rs_customer (id, email, name) VALUES (2, 'alan@example.com', 'Alan')",
            "INSERT INTO rs_order (id, customer_id, total) VALUES (10, 1, 120.00)",
            "INSERT INTO rs_order (id, customer_id, total) VALUES (11, 1, 35.50)",
            "INSERT INTO rs_order (id, customer_id, total) VALUES (12, 2, 80.00)");
      }
      assertRows(connection, dialect, step, step.model(), sql);
      previous = step.model();
    }

    List<Step> steps = steps();
    for (int i = steps.size() - 1; i >= 1; i--) {
      String name = steps.get(i).name() + ".sql";
      try {
        runner.rollback(connection, name, Migration.ofSql(name, rollbacks.get(i)),
            MigrationRunner.Options.defaults());
      } catch (Exception e) {
        throw new AssertionError(dialect.id() + " rollback of " + name + " failed:\n"
            + rollbacks.get(i), e);
      }
      assertRows(connection, dialect, steps.get(i - 1), steps.get(i - 1).model(),
          rollbacks.get(i));
    }

    execute(connection, "DROP TABLE rs_order", "DROP TABLE rs_customer",
        "DELETE FROM migrax_history WHERE version LIKE '006%'");
  }

  /** The rows read through the step's names, joined over the key, and the key itself. */
  private static void assertRows(Connection connection, Dialect dialect, Step step,
                                 SchemaModel model, String sql) throws Exception {
    SchemaModel.Table customers = model.tables().get(0);
    SchemaModel.Table orders = model.tables().get(1);
    String name = customers.columns().get(2).name();
    String key = orders.columns().get(1).name();
    String total = orders.columns().get(2).name();
    String where = dialect.id() + " " + step.name() + ":\n" + sql;
    assertEquals("10|Ada|120.00;11|Ada|35.50;12|Alan|80.00", rows(connection,
        "SELECT o.id, c." + name + ", o." + total + " FROM " + orders.name() + " o JOIN "
            + customers.name() + " c ON c.id = o." + key + " ORDER BY o.id"), where);
    SchemaModel read = org.migrax.plugin.DatabaseSchemaReader.read(connection, null);
    SchemaModel.Table table = read.tables().stream()
        .filter(t -> t.name().equalsIgnoreCase(orders.name())).findFirst().orElseThrow();
    SchemaModel.ForeignKey expected = orders.foreignKeys().get(0);
    assertTrue(table.foreignKeys().stream().anyMatch(k ->
            k.name().equalsIgnoreCase(expected.name())
                && k.referencedTable().equalsIgnoreCase(expected.referencedTable())
                && lower(k.columns()).equals(lower(expected.columns()))),
        where + "\nforeign keys: " + table.foreignKeys());
    // The key still guards the rows: an order for a customer that doesn't exist is refused.
    assertTrue(refused(connection, dialect, orders.name(), "INSERT INTO " + orders.name()
        + " (id, " + key + ", " + total + ") VALUES (99, 404, 1.00)"),
        where + "\nthe foreign key lets a bad row in");
  }

  private static List<String> lower(List<String> names) {
    return names.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList();
  }

  private static boolean refused(Connection connection, Dialect dialect, String table,
                                 String insert) throws Exception {
    if ("sqlite".equals(dialect.id())) {
      execute(connection, "PRAGMA foreign_keys = ON"); // off by default on each connection
    }
    try {
      execute(connection, insert);
    } catch (java.sql.SQLException expected) {
      return true;
    }
    execute(connection, "DELETE FROM " + table + " WHERE id = 99");
    return false;
  }

  private static void execute(Connection connection, String... statements) throws Exception {
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        statement.execute(sql);
      }
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  private static String rows(Connection connection, String sql) throws Exception {
    List<String> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      int columns = result.getMetaData().getColumnCount();
      while (result.next()) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= columns; i++) {
          // Databases hand numbers back as long, decimal or double: compare them as written.
          Object value = result.getObject(i);
          values.add(!(value instanceof Number) ? String.valueOf(value)
              : i == columns ? new java.math.BigDecimal(value.toString())
                  .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
              : String.valueOf(((Number) value).longValue()));
        }
        rows.add(String.join("|", values));
      }
    }
    return String.join(";", rows);
  }
}
