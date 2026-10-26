package org.migrax.cli;

import org.migrax.dialect.Dialects;
import org.migrax.diff.Renames;
import org.migrax.diff.ZeroDowntime;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddColumn;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.DropColumn;
import org.migrax.ops.Operation;
import org.migrax.ops.RunSql;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZeroDowntimeTest {
  @Test
  void bothVersionsRunBetweenTheTwoMigrationsOnH2() throws Exception {
    try (Connection connection = DriverManager.getConnection(
        "jdbc:h2:mem:zero_downtime;DB_CLOSE_DELAY=-1")) {
      ZeroDowntimeScenario.run(connection, Dialects.byName("h2"));
    }
  }

  private static SchemaModel.Column column(String name, boolean nullable) {
    return new SchemaModel.Column(name, "varchar", nullable, 100, null, null, null, false, false,
        null, "varchar");
  }

  private static SchemaModel customer(SchemaModel.Column... columns) {
    List<SchemaModel.Column> all = new java.util.ArrayList<>();
    all.add(new SchemaModel.Column("id", "bigint", false, null, null, null, null, false, false,
        null, "bigint"));
    all.addAll(List.of(columns));
    return new SchemaModel(List.of(new SchemaModel.Table("customer", all,
        new SchemaModel.PrimaryKey(List.of("id"), "customer_pkey"), List.of(), List.of())));
  }

  @Test
  void aRenamedColumnIsCopiedAndKeptEqualUntilTheContract() {
    SchemaModel before = customer(column("email", false));
    SchemaModel after = customer(column("email_address", false));
    ZeroDowntime.Plan plan = ZeroDowntime.plan(before, after, List.of(),
        List.of(new Renames.ColumnRename("customer", "email", "email_address")),
        Dialects.byName("postgresql"));
    assertEquals(List.of(), plan.problems());
    List<Operation> expand = plan.expand();
    assertEquals(new AlterColumn("customer", column("email", false), column("email", true)),
        expand.get(0));
    assertEquals(new AddColumn("customer", column("email_address", true)), expand.get(1));
    String trigger = "migrax_sync_customer_email_address";
    // Oracle before 12.2 allows 30 characters.
    assertEquals("migrax_sync_customer_" + String.format("%08x",
        "customer.email_address".hashCode()), org.migrax.dialect.Dialect.syncTriggerName(
        "customer", "email_address", 30));
    assertEquals(30, org.migrax.dialect.Dialect.syncTriggerName("a_very_long_table_name",
        "and_a_long_column", 30).length());
    assertTrue(((RunSql) expand.get(2)).sql().startsWith("CREATE FUNCTION " + trigger
        + "() RETURNS trigger"), expand.toString());
    assertTrue(((RunSql) expand.get(3)).sql().startsWith("CREATE TRIGGER " + trigger
        + " BEFORE INSERT OR UPDATE ON customer"));
    assertEquals(new RunSql("UPDATE customer SET email_address = email", true), expand.get(4));
    assertEquals(List.of(
        new RunSql("DROP TRIGGER " + trigger + " ON customer", true),
        new RunSql("DROP FUNCTION " + trigger + "()", true),
        new AlterColumn("customer", column("email_address", true),
            column("email_address", false)),
        new DropColumn("customer", "email")), plan.contract());
  }

  @Test
  void renamesNeedATriggerOrAWritableView() {
    SchemaModel before = customer(column("email", false));
    SchemaModel after = customer(column("email_address", false));
    for (String database : List.of("h2", "sqlite", "cockroachdb", "sqlserver")) {
      ZeroDowntime.Plan plan = ZeroDowntime.plan(before, after, List.of(),
          List.of(new Renames.ColumnRename("customer", "email", "email_address")),
          Dialects.byName(database));
      assertEquals(1, plan.problems().size(), database);
      assertTrue(plan.problems().get(0).contains("needs a trigger"), plan.problems().get(0));
    }
    SchemaModel renamed = new SchemaModel(List.of(new SchemaModel.Table("client",
        before.tables().get(0).columns(), before.tables().get(0).primaryKey(), List.of(),
        List.of())));
    for (String database : List.of("h2", "sqlite", "cockroachdb")) {
      assertTrue(ZeroDowntime.plan(before, renamed,
          List.of(new Renames.TableRename("customer", "client")), List.of(),
          Dialects.byName(database)).problems().get(0).contains("needs a view"), database);
    }
    assertEquals(List.of(), ZeroDowntime.plan(before, renamed,
        List.of(new Renames.TableRename("customer", "client")), List.of(),
        Dialects.byName("sqlserver")).problems());
  }

  @Test
  void aChangedPrimaryKeyCantBeSplit() {
    SchemaModel before = customer(column("email", false));
    SchemaModel.Table table = before.tables().get(0);
    SchemaModel after = new SchemaModel(List.of(new SchemaModel.Table("customer",
        table.columns(), new SchemaModel.PrimaryKey(List.of("id", "email"), "customer_pkey"),
        List.of(), List.of())));
    assertTrue(ZeroDowntime.plan(before, after, List.of(), List.of(), Dialects.byName("h2"))
        .problems().get(0).contains("primary key of customer"));
  }

  @Test
  void onlyWhatTheOldVersionCanBreakWaits() {
    SchemaModel before = customer(column("email", true));
    SchemaModel after = customer(column("email", true), column("phone", true));
    ZeroDowntime.Plan plan = ZeroDowntime.plan(before, after, List.of(), List.of(),
        Dialects.byName("h2"));
    assertEquals(List.of(new AddColumn("customer", column("phone", true))), plan.expand());
    assertEquals(List.of(), plan.contract());
  }

  @Test
  void explainsMySqlsTriggerPrivilege() {
    String hint = Errors.hint(new java.sql.SQLException("Migration failed", new java.sql
        .SQLException("You do not have the SUPER privilege and binary logging is enabled (you "
        + "*might* want to use the less safe log_bin_trust_function_creators variable)")));
    assertTrue(hint.contains("SET GLOBAL log_bin_trust_function_creators = 1"), hint);
  }
}
