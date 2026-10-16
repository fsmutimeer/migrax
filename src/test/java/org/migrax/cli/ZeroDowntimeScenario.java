package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.diff.DiffEngine;
import org.migrax.diff.Renames;
import org.migrax.diff.ZeroDowntime;
import org.migrax.model.SchemaModel;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;
import org.migrax.verify.SchemaComparator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code generate --zero-downtime} end to end on a real database. After the expand migration,
 * the old and the new application version write at the same time: the old one through the old
 * column and table names, the new one through the new names, and each sees the other's rows.
 * Then the contract migration runs, and both rollback scripts bring back the old schema.
 *
 * <p>The changes: a renamed column (where the database has the trigger), a renamed table (where
 * its views accept writes), a dropped NOT NULL column, a new NOT NULL column, a narrowed and a
 * widened column. Shared by the H2 unit test and the integration test on every database.
 */
public final class ZeroDowntimeScenario {
  private ZeroDowntimeScenario() {}

  private static SchemaModel.Column column(String name, String type, boolean nullable,
                                           Integer length) {
    return new SchemaModel.Column(name, type, nullable, length, null, null, null, false, false,
        null, type);
  }

  private static SchemaModel model(boolean after, boolean renameColumn, boolean renameTable) {
    List<SchemaModel.Column> customer = new ArrayList<>();
    customer.add(column("id", "bigint", false, null));
    customer.add(column(after && renameColumn ? "email_address" : "email", "varchar", false,
        100));
    customer.add(column("nickname", "varchar", true, after ? 30 : 50));
    customer.add(column("score", after ? "bigint" : "integer", true, null));
    if (after) {
      customer.add(column("tier", "varchar", false, 10));
    } else {
      customer.add(column("legacy", "varchar", false, 20));
    }
    String note = after && renameTable ? "zd_memo" : "zd_note";
    return new SchemaModel(List.of(
        new SchemaModel.Table("zd_customer", customer,
            new SchemaModel.PrimaryKey(List.of("id"), "zd_customer_pkey"), List.of(), List.of()),
        new SchemaModel.Table(note, List.of(column("id", "bigint", false, null),
            column("body", "varchar", true, 200)),
            new SchemaModel.PrimaryKey(List.of("id"), "zd_note_pkey"), List.of(), List.of())));
  }

  public static void run(Connection connection, Dialect dialect) throws Exception {
    boolean renameColumn = !dialect.syncColumns("t", "a", "b").isEmpty();
    boolean renameTable = dialect.updatableViews();
    String email = renameColumn ? "email_address" : "email";
    String memo = renameTable ? "zd_memo" : "zd_note";
    SchemaModel before = model(false, renameColumn, renameTable);
    SchemaModel after = model(true, renameColumn, renameTable);
    List<Renames.TableRename> tables = renameTable
        ? List.of(new Renames.TableRename("zd_note", "zd_memo")) : List.of();
    List<Renames.ColumnRename> columns = renameColumn
        ? List.of(new Renames.ColumnRename("zd_customer", "email", "email_address")) : List.of();

    Path folder = Files.createTempDirectory("migrax-zero-downtime");
    Path setup = folder.resolve("0051_setup.sql");
    Files.writeString(setup, MigrationSql.render(dialect,
        new DiffEngine().diff(SchemaModel.empty(), before), null, SchemaModel.empty(), before));
    MigrationRunner runner = new MigrationRunner();
    runner.migrate(connection, List.of(setup));
    execute(connection, "INSERT INTO zd_customer (id, email, nickname, score, legacy) VALUES "
        + "(1, 'one@example.com', 'one', 1, 'L1')",
        "INSERT INTO zd_note (id, body) VALUES (1, 'first')");

    ZeroDowntime.Plan plan = ZeroDowntime.plan(before, after, tables, columns, dialect);
    assertEquals(List.of(), plan.problems(), dialect.id());
    assertEquals(1, plan.notes().stream().filter(n -> n.startsWith("zd_customer.tier")).count(),
        dialect.id() + ": " + plan.notes());
    Path expand = folder.resolve("0052_change_expand.sql");
    Path contract = folder.resolve("0053_change_contract.sql");
    String expandSql = MigrationSql.render(dialect, plan.expand(), null, before, plan.middle());
    String contractSql = MigrationSql.render(dialect, plan.contract(), null, plan.middle(),
        after);
    Files.writeString(expand, expandSql);
    Files.writeString(contract, contractSql);
    MigrationSql.writeRollback(folder, expand.getFileName().toString(), dialect,
        plan.expandReverse(), plan.expand(), plan.middle(), plan.rolledBack());
    MigrationSql.writeRollback(folder, contract.getFileName().toString(), dialect,
        plan.contractReverse(), plan.contract(), after, plan.middle());
    try {
      runner.migrate(connection, List.of(setup, expand));
    } catch (Exception e) {
      throw new AssertionError(dialect.id() + " expand failed:\n" + expandSql, e);
    }

    // Both versions at once. The old one doesn't know tier and the new names; the new one
    // doesn't know legacy and the old names.
    execute(connection,
        "INSERT INTO zd_customer (id, email, nickname, score, legacy) VALUES "
            + "(2, 'two@example.com', 'two', 2, 'L2')",
        "UPDATE zd_customer SET email = 'one@example.org' WHERE id = 1",
        "INSERT INTO zd_note (id, body) VALUES (2, 'from the old version')",
        "INSERT INTO zd_customer (id, " + email + ", nickname, score, tier) VALUES "
            + "(3, 'three@example.com', 'three', 3000000000, 'gold')",
        "INSERT INTO " + memo + " (id, body) VALUES (3, 'from the new version')");
    if (renameColumn) {
      execute(connection, "UPDATE zd_customer SET email_address = 'three@example.org' "
          + "WHERE id = 3");
      assertEquals("1|one@example.org|one@example.org;2|two@example.com|two@example.com;"
          + "3|three@example.org|three@example.org", rows(connection,
          "SELECT id, email, email_address FROM zd_customer ORDER BY id"),
          dialect.id() + ": both versions see each other's emails\n" + expandSql);
    }
    assertEquals("1;2;3", rows(connection, "SELECT id FROM zd_note ORDER BY id"), dialect.id());
    assertEquals("1;2;3", rows(connection, "SELECT id FROM " + memo + " ORDER BY id"),
        dialect.id());

    // What the note asks for: a value for the rows the old version wrote.
    execute(connection, "UPDATE zd_customer SET tier = 'basic' WHERE tier IS NULL");
    try {
      runner.migrate(connection, List.of(setup, expand, contract));
    } catch (Exception e) {
      throw new AssertionError(dialect.id() + " contract failed:\n" + contractSql, e);
    }
    SchemaModel migrated = own(org.migrax.plugin.DatabaseSchemaReader.read(connection));
    assertEquals(List.of(), SchemaComparator.compare(after, migrated, dialect.id()).stream()
        .map(Object::toString).toList(), dialect.id() + ": after the contract\n" + contractSql);
    assertEquals("1|one@example.org|basic;2|two@example.com|basic;3|"
        + (renameColumn ? "three@example.org" : "three@example.com") + "|gold",
        rows(connection, "SELECT id, " + email + ", tier FROM zd_customer ORDER BY id"),
        dialect.id());

    String contractRollback = Files.readString(folder.resolve("rollback")
        .resolve(contract.getFileName().toString()));
    String expandRollback = Files.readString(folder.resolve("rollback")
        .resolve(expand.getFileName().toString()));
    try {
      runner.rollback(connection, contract.getFileName().toString(),
          Migration.ofSql(contract.getFileName().toString(), contractRollback),
          MigrationRunner.Options.defaults());
      if (renameColumn) {
        assertEquals("1|one@example.org", rows(connection,
            "SELECT id, email FROM zd_customer WHERE id = 1"), dialect.id());
      }
      // Rolling the expand back narrows score again: a value only bigint holds must go first.
      execute(connection, "UPDATE zd_customer SET score = 3 WHERE id = 3");
      if ("oracle".equals(dialect.id())) {
        // Oracle makes a number column smaller only when it is empty (ORA-01440).
        execute(connection, "UPDATE zd_customer SET score = NULL");
      }
      runner.rollback(connection, expand.getFileName().toString(),
          Migration.ofSql(expand.getFileName().toString(), expandRollback),
          MigrationRunner.Options.defaults());
    } catch (Exception e) {
      throw new AssertionError(dialect.id() + " rollback failed:\n" + contractRollback + "\n"
          + expandRollback, e);
    }
    // legacy comes back without values for the new version's rows, so it stays nullable.
    List<String> differences = SchemaComparator.compare(plan.rolledBack(),
        own(org.migrax.plugin.DatabaseSchemaReader.read(connection)), dialect.id()).stream()
        .map(Object::toString).toList();
    assertTrue(plan.rolledBack().table("zd_customer").column("legacy").nullable());
    assertFalse(plan.rolledBack().table("zd_customer").column("email").nullable());
    assertEquals(List.of(), differences, dialect.id() + ": after both rollbacks\n"
        + contractRollback + "\n" + expandRollback);
    assertFalse(rows(connection, "SELECT email FROM zd_customer WHERE id = 3").isEmpty());

    execute(connection, "DROP TABLE zd_customer", "DROP TABLE zd_note",
        "DELETE FROM migrax_history WHERE version IN ('0051_setup.sql')");
    assertTrue(runner.applied(connection).stream()
        .noneMatch(m -> m.version().startsWith("005")), dialect.id());
  }

  /** The scenario's own tables: the database may hold other tests' tables. */
  private static SchemaModel own(SchemaModel model) {
    return new SchemaModel(model.tables().stream()
        .filter(t -> t.name().toLowerCase(java.util.Locale.ROOT).startsWith("zd_")).toList(),
        List.of());
  }

  private static void execute(Connection connection, String... statements) throws Exception {
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(true);
    try (Statement statement = connection.createStatement()) {
      for (String sql : statements) {
        try {
          statement.execute(sql);
        } catch (Exception e) {
          throw new AssertionError(sql, e);
        }
      }
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  private static String rows(Connection connection, String sql) throws Exception {
    List<String> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      int count = result.getMetaData().getColumnCount();
      while (result.next()) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
          values.add(String.valueOf(result.getObject(i)));
        }
        rows.add(String.join("|", values));
      }
    }
    return String.join(";", rows);
  }
}
