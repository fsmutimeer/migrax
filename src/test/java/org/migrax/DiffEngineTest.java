package org.migrax;

import org.migrax.diff.DiffEngine;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddColumn;
import org.migrax.ops.AddForeignKey;
import org.migrax.ops.DropColumn;
import org.migrax.ops.DropForeignKey;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.Operation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffEngineTest {
  @Test
  void detectsColumnAddAndTypeChange() {
    var old = new SchemaModel(List.of(new SchemaModel.Table(
        "users",
        List.of(new SchemaModel.Column("id", "bigint", false, null, null, null, null, false)),
        null,
        List.of(),
        List.of())));
    var now = new SchemaModel(List.of(new SchemaModel.Table(
        "users",
        List.of(
            new SchemaModel.Column("id", "bigint", false, null, null, null, null, false),
            new SchemaModel.Column("name", "varchar(255)", true, 255, null, null, null, false)),
        null,
        List.of(),
        List.of())));

    var operations = new DiffEngine().diff(old, now);

    assertEquals(1, operations.size());
    assertInstanceOf(AddColumn.class, operations.get(0));
  }

  @Test
  void detectsDefaultChangesButIgnoresWhitespace() {
    var before = column("varchar", "varchar", 255, "'legacy default'");
    var after = column("varchar", "varchar", 255, "'new default'");
    var operations = diffColumn(before, after);
    assertEquals(1, operations.size());
    assertTrue(((org.migrax.ops.AlterColumn) operations.get(0)).defaultChanged());

    assertTrue(diffColumn(before, column("varchar", "varchar", 255, " 'legacy default' "))
        .isEmpty());
  }

  @Test
  void detectsSemanticColumnTypeChanges() {
    var before = column("varchar", "varchar", 255, null);
    var after = column("integer", "integer", null, null);

    var operations = diffColumn(before, after);

    assertEquals(1, operations.size());
    assertInstanceOf(AlterColumn.class, operations.get(0));
  }

  @Test
  void detectsChangesToExplicitSqlColumnTypes() {
    var before = column("json", "jsonb", null, null);
    var after = column("json", "json", null, null);

    var operations = diffColumn(before, after);

    assertEquals(1, operations.size());
    assertInstanceOf(AlterColumn.class, operations.get(0));
  }

  private static SchemaModel.Column column(
      String logicalType, String sqlType, Integer length, String defaultValue) {
    return new SchemaModel.Column(
        "value", sqlType, true, length, null, null, defaultValue,
        false, false, null, logicalType);
  }

  private static List<org.migrax.ops.Operation> diffColumn(
      SchemaModel.Column before, SchemaModel.Column after) {
    var oldSchema = new SchemaModel(List.of(new SchemaModel.Table(
        "records", List.of(before), null, List.of(), List.of())));
    var newSchema = new SchemaModel(List.of(new SchemaModel.Table(
        "records", List.of(after), null, List.of(), List.of())));
    return new DiffEngine().diff(oldSchema, newSchema);
  }

  @Test
  void replacesChangedForeignKeyBeforeDroppingItsOldColumn() {
    var oldKey = new SchemaModel.ForeignKey(
        "fk_order_customer", List.of("customer_id"), "customers", List.of("id"));
    var changedKey = new SchemaModel.ForeignKey(
        "fk_order_customer", List.of("customer_ref"), "accounts", List.of("id"));
    var old = new SchemaModel(List.of(
        new SchemaModel.Table(
            "orders",
            List.of(new SchemaModel.Column("customer_id", "bigint", false, null, null, null, null, false)),
            null,
            List.of(),
            List.of(oldKey)),
        new SchemaModel.Table(
            "customers",
            List.of(new SchemaModel.Column("id", "bigint", false, null, null, null, null, false)),
            null,
            List.of(),
            List.of())));
    var now = new SchemaModel(List.of(
        new SchemaModel.Table(
            "orders",
            List.of(new SchemaModel.Column("customer_ref", "bigint", false, null, null, null, null, false)),
            null,
            List.of(),
            List.of(changedKey)),
        new SchemaModel.Table(
            "accounts",
            List.of(new SchemaModel.Column("id", "bigint", false, null, null, null, null, false)),
            null,
            List.of(),
            List.of())));

    var operations = new DiffEngine().diff(old, now);

    int dropForeignKey = indexOf(operations, DropForeignKey.class);
    int dropColumn = indexOf(operations, DropColumn.class);
    assertTrue(dropForeignKey < dropColumn);
    assertInstanceOf(AddForeignKey.class, operations.get(operations.size() - 1));
  }

  @Test
  void foreignKeyThatOnlyGetsANewNameIsNotDestructive() {
    var operations = new DiffEngine().diff(
        ordersWith(new SchemaModel.ForeignKey(
            "fk_orders_customer_id", List.of("customer_id"), "customers", List.of("id"))),
        ordersWith(new SchemaModel.ForeignKey(
            "fk_purchase_orders_customer_id", List.of("customer_id"), "customers",
            List.of("id"))));

    var drop = (DropForeignKey) operations.get(indexOf(operations, DropForeignKey.class));
    assertTrue(drop.renamed());
    assertTrue(operations.stream().noneMatch(Operation::destructive));
    assertInstanceOf(AddForeignKey.class, operations.get(operations.size() - 1));
  }

  @Test
  void foreignKeyWithANewNameAndANewTargetStaysDestructive() {
    var operations = new DiffEngine().diff(
        ordersWith(new SchemaModel.ForeignKey(
            "fk_orders_customer_id", List.of("customer_id"), "customers", List.of("id"))),
        ordersWith(new SchemaModel.ForeignKey(
            "fk_purchase_orders_customer_id", List.of("customer_id"), "customers",
            List.of("code"))));

    var drop = (DropForeignKey) operations.get(indexOf(operations, DropForeignKey.class));
    assertFalse(drop.renamed());
    assertTrue(drop.destructive());
  }

  @Test
  void foreignKeyThatIsRemovedStaysDestructive() {
    var operations = new DiffEngine().diff(
        ordersWith(new SchemaModel.ForeignKey(
            "fk_orders_customer_id", List.of("customer_id"), "customers", List.of("id"))),
        ordersWith());

    assertEquals(1, operations.size());
    assertTrue(operations.get(0).destructive());
  }

  /** customers(id, code) and purchase_orders(customer_id) with the given foreign keys. */
  private static SchemaModel ordersWith(SchemaModel.ForeignKey... keys) {
    var id = new SchemaModel.Column("id", "bigint", false, null, null, null, null, false);
    var code = new SchemaModel.Column("code", "bigint", false, null, null, null, null, false);
    var customerId = new SchemaModel.Column("customer_id", "bigint", false, null, null, null,
        null, false);
    return new SchemaModel(List.of(
        new SchemaModel.Table("customers", List.of(id, code), null, List.of(), List.of()),
        new SchemaModel.Table("purchase_orders", List.of(customerId), null, List.of(),
            List.of(keys))));
  }

  private static int indexOf(List<?> operations, Class<?> operationType) {
    for (int i = 0; i < operations.size(); i++) {
      if (operationType.isInstance(operations.get(i))) {
        return i;
      }
    }
    return -1;
  }
}
