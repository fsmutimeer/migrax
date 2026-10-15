package org.migrax.cli;

import org.migrax.dialect.Dialects;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeepDataTest {
  @Test
  void aRollbackRestoresDroppedTablesAndColumnsWithTheirData() throws Exception {
    try (Connection connection = DriverManager.getConnection(
        "jdbc:h2:mem:keep_data;DB_CLOSE_DELAY=-1")) {
      KeepDataScenario.run(connection, Dialects.byName("h2"));
    }
  }

  @Test
  void tablesWithoutAPrimaryKeyAreMatchedByAUniqueKeyOrTheWholeRow() {
    org.migrax.model.SchemaModel.Column code = new org.migrax.model.SchemaModel.Column("code",
        "varchar", false, 20, null, null, null, true, false, null, "varchar");
    org.migrax.model.SchemaModel.Column a = column("a", true);
    org.migrax.model.SchemaModel.Column b = column("b", false);
    org.migrax.model.SchemaModel.Column note = column("note", true);
    org.migrax.model.SchemaModel before = new org.migrax.model.SchemaModel(java.util.List.of(
        new org.migrax.model.SchemaModel.Table("coded", java.util.List.of(code, note), null,
            java.util.List.of(), java.util.List.of()),
        new org.migrax.model.SchemaModel.Table("loose", java.util.List.of(a, b, note), null,
            java.util.List.of(), java.util.List.of())));
    java.util.List<org.migrax.ops.Operation> forward = java.util.List.of(
        new org.migrax.ops.DropColumn("coded", "note"),
        new org.migrax.ops.DropColumn("loose", "note"));
    java.util.List<org.migrax.ops.Operation> reverse = java.util.List.of(
        new org.migrax.ops.AddColumn("coded", note), new org.migrax.ops.AddColumn("loose", note));

    KeepData.Plan plan = KeepData.apply(forward, reverse, before, Dialects.byName("postgresql"),
        "0007");
    assertEquals(java.util.List.of(
        "UPDATE coded SET note = (SELECT b.note FROM migrax_backup_0007_coded_note b WHERE "
            + "b.code = coded.code) WHERE EXISTS (SELECT 1 FROM "
            + "migrax_backup_0007_coded_note b WHERE b.code = coded.code)",
        "UPDATE loose SET note = (SELECT b.note FROM migrax_backup_0007_loose_note b WHERE "
            + "(b.a = loose.a OR b.a IS NULL AND loose.a IS NULL) AND b.b = loose.b LIMIT 1) "
            + "WHERE EXISTS (SELECT 1 FROM migrax_backup_0007_loose_note b WHERE (b.a = loose.a "
            + "OR b.a IS NULL AND loose.a IS NULL) AND b.b = loose.b)"), plan.restores());
    assertEquals(1, plan.warnings().size(), plan.warnings().toString());

    org.junit.jupiter.api.Assertions.assertTrue(KeepData.apply(forward, reverse, before,
        Dialects.byName("sqlserver"), "0007").restores().get(1)
        .contains("(SELECT TOP 1 b.note FROM"));
    org.junit.jupiter.api.Assertions.assertTrue(KeepData.apply(forward, reverse, before,
        Dialects.byName("oracle"), "0007").restores().get(1)
        .contains("AND b.b = loose.b) AND ROWNUM = 1)"));
  }

  private static org.migrax.model.SchemaModel.Column column(String name, boolean nullable) {
    return new org.migrax.model.SchemaModel.Column(name, "varchar", nullable, 20, null, null,
        null, false, false, null, "varchar");
  }

  @Test
  void backupNamesFitEveryDatabase() {
    assertEquals("migrax_backup_0005_orders_notes",
        KeepData.backupName("0005", "orders", "notes"));
    String long_ = KeepData.backupName("0005", "a_really_long_table_name_for_testing",
        "and_a_long_column_name_too");
    assertEquals(60, long_.length());
  }
}
