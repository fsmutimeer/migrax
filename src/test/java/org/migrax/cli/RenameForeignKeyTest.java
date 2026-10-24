package org.migrax.cli;

import org.migrax.dialect.CockroachDialect;
import org.migrax.dialect.Dialect;
import org.migrax.dialect.H2Dialect;
import org.migrax.dialect.MySqlDialect;
import org.migrax.dialect.PostgresDialect;
import org.migrax.dialect.SqliteDialect;
import org.migrax.dialect.YugabyteDialect;
import org.migrax.diff.DiffEngine;
import org.migrax.diff.Migrations;
import org.migrax.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenameForeignKeyTest {
  private static final SchemaModel.Column ID =
      new SchemaModel.Column("id", "bigint", false, null, null, null, null, false);
  private static final SchemaModel.Column CUSTOMER_ID =
      new SchemaModel.Column("customer_id", "bigint", false, null, null, null, null, false);
  private static final SchemaModel.Table CUSTOMERS =
      new SchemaModel.Table("customers", List.of(ID), null, List.of(), List.of());

  private static SchemaModel.Table orders(String table, String keyName) {
    return new SchemaModel.Table(table, List.of(CUSTOMER_ID), null, List.of(),
        List.of(new SchemaModel.ForeignKey(keyName, List.of("customer_id"), "customers",
            List.of("id"))));
  }

  /** customers(id) and purchase_orders(customer_id) with one foreign key of the given name. */
  private static SchemaModel model(String keyName) {
    return new SchemaModel(List.of(CUSTOMERS, orders("purchase_orders", keyName)));
  }

  private static String sql(Dialect dialect, SchemaModel before, SchemaModel after) {
    return MigrationSql.render(dialect, new DiffEngine().diff(before, after), null, before,
        after);
  }

  @Test
  void renamesTheKeyWhereTheDatabaseCan() {
    var before = model("fk_orders_customer");
    var after = model("fk_purchase_orders_cu");
    String postgres = sql(new PostgresDialect(), before, after);
    assertTrue(postgres.contains("ALTER TABLE purchase_orders RENAME CONSTRAINT "
        + "fk_orders_customer TO fk_purchase_orders_cu;"), postgres);
    assertFalse(postgres.contains("DROP CONSTRAINT"), postgres);
    assertFalse(postgres.contains("ADD CONSTRAINT"), postgres);

    String h2 = sql(new H2Dialect(), before, after);
    assertTrue(h2.contains("RENAME CONSTRAINT"), h2);
    assertFalse(h2.contains("ADD CONSTRAINT"), h2);

    // CockroachDB and YugabyteDB inherit PostgreSQL's statement (run on both by hand).
    assertNotNull(new CockroachDialect().renameForeignKey("t", "a", "b"));
    assertNotNull(new YugabyteDialect().renameForeignKey("t", "a", "b"));
  }

  @Test
  void dropsAndAddsTheKeyWhereTheDatabaseCant() {
    String mysql = sql(new MySqlDialect(), model("fk_orders_customer"),
        model("fk_purchase_orders_cu"));
    assertFalse(mysql.contains("RENAME CONSTRAINT"), mysql);
    assertTrue(mysql.contains("DROP FOREIGN KEY fk_orders_customer;\n"), mysql);
    assertTrue(mysql.contains("ADD CONSTRAINT fk_purchase_orders_cu FOREIGN KEY"), mysql);
  }

  @Test
  void sqliteRebuildsTheTable() {
    String sqlite = sql(new SqliteDialect(), model("fk_orders_customer"),
        model("fk_purchase_orders_cu"));
    assertFalse(sqlite.contains("RENAME CONSTRAINT"), sqlite);
    assertTrue(sqlite.contains("fk_purchase_orders_cu"), sqlite);
  }

  @Test
  void theRollbackRenamesItBack() {
    String back = sql(new PostgresDialect(), model("fk_purchase_orders_cu"),
        model("fk_orders_customer"));
    assertTrue(back.contains("RENAME CONSTRAINT fk_purchase_orders_cu TO fk_orders_customer;"),
        back);
  }

  @Test
  void theRenameComesAfterEveryDrop() {
    // H2 keeps constraint names per schema: another table's key that is dropped in the same
    // migration may still hold the new name, so the rename has to wait for that drop.
    var before = new SchemaModel(List.of(CUSTOMERS, orders("purchase_orders", "fk_old"),
        orders("invoices", "fk_new")));
    var invoicesWithoutKey = new SchemaModel.Table("invoices", List.of(CUSTOMER_ID), null,
        List.of(), List.of());
    var after = new SchemaModel(List.of(CUSTOMERS, orders("purchase_orders", "fk_new"),
        invoicesWithoutKey));
    List<String> statements = new ArrayList<>(List.of(sql(new H2Dialect(), before, after)
        .split("\n")));
    int drop = indexOf(statements, "DROP CONSTRAINT fk_new");
    int rename = indexOf(statements, "RENAME CONSTRAINT fk_old TO fk_new");
    assertTrue(drop >= 0 && rename > drop, String.join("\n", statements));
  }

  @Test
  void theSummarySaysRename() {
    var operations = new DiffEngine().diff(model("fk_orders_customer"),
        model("fk_purchase_orders_cu"));
    assertEquals(List.of("rename foreign key fk_orders_customer to fk_purchase_orders_cu on "
        + "purchase_orders"), operations.stream().map(Migrations::summary).toList());
  }

  private static int indexOf(List<String> lines, String text) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(text)) {
        return i;
      }
    }
    return -1;
  }
}
