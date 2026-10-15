package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.diff.DiffEngine;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code generate --keep-data} end to end on a real database: a dropped table, a dropped
 * nullable and a dropped NOT NULL column and a narrowed column, migrated and rolled back. Every
 * row and value must be back, with the NOT NULL and the foreign key. Shared by the H2 unit test
 * and the 8-database integration test.
 */
public final class KeepDataScenario {
  private KeepDataScenario() {}

  private static SchemaModel.Column column(String name, String type, boolean nullable,
                                           Integer length) {
    return new SchemaModel.Column(name, type, nullable, length, null, null, null, false, false,
        null, type);
  }

  private static SchemaModel model(boolean withChild, boolean withColumns) {
    List<SchemaModel.Table> tables = new ArrayList<>();
    tables.add(new SchemaModel.Table("kd_parent", List.of(column("id", "bigint", false, null)),
        new SchemaModel.PrimaryKey(List.of("id"), "kd_parent_pkey"), List.of(), List.of()));
    if (withChild) {
      tables.add(new SchemaModel.Table("kd_child", List.of(column("id", "bigint", false, null),
          column("parent_id", "bigint", true, null), column("note", "varchar", true, 100)),
          new SchemaModel.PrimaryKey(List.of("id"), "kd_child_pkey"), List.of(),
          List.of(new SchemaModel.ForeignKey("fk_kd_child_parent", List.of("parent_id"),
              "kd_parent", List.of("id")))));
    }
    List<SchemaModel.Column> item = new ArrayList<>(List.of(column("id", "bigint", false, null)));
    if (withColumns) {
      item.add(column("name", "varchar", false, 100));
      item.add(column("remark", "varchar", true, 200));
    }
    item.add(column("grade", "varchar", true, withColumns ? 100 : 10));
    tables.add(new SchemaModel.Table("kd_item", item,
        new SchemaModel.PrimaryKey(List.of("id"), "kd_item_pkey"), List.of(), List.of()));
    return new SchemaModel(tables);
  }

  public static void run(Connection connection, Dialect dialect) throws Exception {
    run(connection, dialect, null);
  }

  /**
   * The scenario in a schema of a multi-tenant project: the connection is pointed at it, and on
   * SQL Server the dialect names it in every statement.
   *
   * @param schema the schema, or null for the connection's own
   */
  public static void run(Connection connection, Dialect dialect, String schema)
      throws Exception {
    String in = schema == null ? "" : schema + ".";
    if (schema != null) {
      org.migrax.runner.TargetSchema.use(connection, schema);
    }
    SchemaModel before = model(true, true);
    SchemaModel after = model(false, false);
    Path folder = Files.createTempDirectory("migrax-keep-data");
    Path setup = folder.resolve("0041_setup.sql");
    Files.writeString(setup, MigrationSql.render(dialect,
        new DiffEngine().diff(SchemaModel.empty(), before), null, SchemaModel.empty(), before));
    MigrationRunner runner = new MigrationRunner();
    runner.migrate(connection, List.of(setup));
    execute(connection, "INSERT INTO " + in + "kd_parent (id) VALUES (1)",
        "INSERT INTO " + in + "kd_child (id, parent_id, note) VALUES (10, 1, 'child note')",
        "INSERT INTO " + in + "kd_item (id, name, remark, grade) VALUES (1, 'first', "
            + "'remark one', 'S')",
        "INSERT INTO " + in + "kd_item (id, name, remark, grade) VALUES (2, 'second', NULL, "
            + "'M')");

    List<Operation> forward = new DiffEngine().diff(before, after);
    List<Operation> reverse = new DiffEngine().diff(after, before);
    KeepData.Plan plan = KeepData.apply(forward, reverse, before, dialect, "0042");
    assertEquals(List.of(), plan.warnings());
    Path migration = folder.resolve("0042_drop.sql");
    String forwardSql = MigrationSql.render(dialect, plan.forward(), null, before, after);
    MigrationSql.writeRollback(folder, "0042_drop.sql", dialect, plan, forward, after, before);
    String reverseSql = Files.readString(folder.resolve("rollback/0042_drop.sql"));
    Files.writeString(migration, forwardSql);
    try {
      runner.migrate(connection, List.of(setup, migration));
    } catch (Exception e) {
      throw new AssertionError(dialect.id() + " migration failed:\n" + forwardSql, e);
    }
    List<String> backups = org.migrax.plugin.DatabaseSchemaReader.objectNames(connection,
        schema, "TABLE").stream().map(String::toLowerCase)
        .filter(name -> name.startsWith("migrax_backup_0042_")).sorted().toList();
    assertEquals(List.of("migrax_backup_0042_kd_child", "migrax_backup_0042_kd_item_grade",
        "migrax_backup_0042_kd_item_name",
        "migrax_backup_0042_kd_item_remark"), backups,
        dialect.id() + ":\n" + forwardSql);

    try {
      runner.rollback(connection, "0042_drop.sql", Migration.ofSql("0042_drop.sql", reverseSql),
          MigrationRunner.Options.defaults());
    } catch (Exception e) {
      throw new AssertionError(dialect.id() + " rollback failed:\n" + reverseSql, e);
    }
    assertEquals("10|1|child note", row(connection,
        "SELECT id, parent_id, note FROM " + in + "kd_child"), dialect.id() + ":\n" + reverseSql);
    assertEquals("1|first|remark one|S;2|second|null|M", rows(connection,
        "SELECT id, name, remark, grade FROM " + in + "kd_item ORDER BY id"), dialect.id());
    assertEquals(List.of(), org.migrax.plugin.DatabaseSchemaReader.objectNames(connection,
        schema, "TABLE").stream().filter(name -> name.toLowerCase().startsWith("migrax_backup_0042_"))
        .toList(), dialect.id() + ": backups after the rollback");
    SchemaModel restored = org.migrax.plugin.DatabaseSchemaReader.read(connection, schema);
    SchemaModel.Table item = table(restored, "kd_item");
    assertTrue(!column(item, "name").nullable(), dialect.id() + ": NOT NULL restored");
    assertEquals(1, table(restored, "kd_child").foreignKeys().size(),
        dialect.id() + ": foreign key restored");

    execute(connection, "DROP TABLE " + in + "kd_child", "DROP TABLE " + in + "kd_item",
        "DROP TABLE " + in + "kd_parent", "DELETE FROM " + in + "migrax_history WHERE version "
            + "IN ('0041_setup.sql', '0042_drop.sql')");
  }

  private static SchemaModel.Table table(SchemaModel model, String name) {
    return model.tables().stream().filter(t -> t.name().equalsIgnoreCase(name)).findFirst()
        .orElseThrow();
  }

  private static SchemaModel.Column column(SchemaModel.Table table, String name) {
    return table.columns().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst()
        .orElseThrow();
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

  private static String row(Connection connection, String sql) throws Exception {
    return rows(connection, sql);
  }

  private static String rows(Connection connection, String sql) throws Exception {
    List<String> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      int columns = result.getMetaData().getColumnCount();
      while (result.next()) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= columns; i++) {
          values.add(String.valueOf(result.getObject(i)));
        }
        rows.add(String.join("|", values));
      }
    }
    return String.join(";", rows);
  }
}
