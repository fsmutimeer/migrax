package org.migrax;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.model.SchemaModel;
import org.migrax.ops.CreateTable;
import org.migrax.verify.SchemaComparator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Names that keep their letter case: written quoted, compared with the database without. */
class ExactNamesTest {
  private static SchemaModel model(String table, String column, String child) {
    SchemaModel.Column id = new SchemaModel.Column("id", "bigint", false, null, null, null,
        null, false);
    SchemaModel.Column value = new SchemaModel.Column(column, "varchar", true, null, null,
        null, null, false);
    return new SchemaModel(List.of(
        new SchemaModel.Table(table, List.of(id, value),
            new SchemaModel.PrimaryKey(List.of("id")), List.of(), List.of()),
        new SchemaModel.Table(child, List.of(new SchemaModel.Column(column, "bigint", false,
            null, null, null, null, false)), null, List.of(),
            List.of(new SchemaModel.ForeignKey("fk_item", List.of(column), table,
                List.of("id"))))));
  }

  @Test
  void writesExactNamesQuotedAsTheyAre() {
    SchemaModel model = model("\"customers\"", "\"Mail\"", "item");
    assertEquals("CREATE TABLE \"customers\" (id bigint NOT NULL, \"Mail\" varchar(255), "
        + "CONSTRAINT customers_pkey PRIMARY KEY (id))",
        Dialects.byName("h2").render(new CreateTable(model.tables().get(0))));
    assertTrue(Dialects.byName("mysql").render(new CreateTable(model.tables().get(0)))
        .startsWith("CREATE TABLE `customers` (id bigint NOT NULL, `Mail` varchar(255)"));
    // Plain names are written as before.
    assertEquals("customers", Dialects.byName("h2").identifier("customers"));
  }

  @Test
  void buildsConstraintNamesWithoutTheQuotes() {
    assertEquals("customers_pkey", SchemaModel.primaryKeyConstraintName("\"customers\""));
    assertEquals("uk_customers_mail", SchemaModel.uniqueConstraintName("\"Customers\"",
        "\"Mail\""));
    assertTrue(SchemaModel.checkConstraintName("\"customers\"", "a > 0")
        .startsWith("ck_customers_"));
    assertEquals("customers_pkey", SchemaModel.primaryKeyConstraintName("customers"));
  }

  @Test
  void matchesTheNamesTheDatabaseReturns() {
    // H2 returns a quoted lower-case name as it is, an unquoted one in upper case (read as
    // lower case): both are the entities' exact names.
    SchemaModel expected = model("\"customers\"", "\"mail\"", "item");
    SchemaModel database = model("customers", "mail", "item");
    assertEquals(List.of(), SchemaComparator.compare(expected, database, "h2"));

    SchemaModel named = database.withNameCaseFrom(expected);
    assertEquals("\"customers\"", named.tables().get(0).name());
    assertEquals("\"mail\"", named.tables().get(0).columns().get(1).name());
    assertEquals("\"customers\"", named.tables().get(1).foreignKeys().get(0).referencedTable());

    // Without exact names, nothing changes.
    SchemaModel plain = model("customers", "mail", "item");
    assertEquals(plain, plain.withNameCaseFrom(model("customers", "mail", "item")));
    assertEquals(1, SchemaComparator.compare(plain, model("clients", "mail", "item"), "h2")
        .stream().filter(d -> d.kind().equals("missing_table")).count());
  }

  @Test
  void knowsWhatEachDatabaseDoesWithUnquotedNames() {
    for (String upper : List.of("h2", "oracle", "hsqldb", "derby")) {
      assertEquals(Dialect.NameCase.UPPER, Dialects.byName(upper).unquotedNames(), upper);
    }
    for (String lower : List.of("postgresql", "cockroachdb", "yugabytedb")) {
      assertEquals(Dialect.NameCase.LOWER, Dialects.byName(lower).unquotedNames(), lower);
    }
    for (String kept : List.of("mysql", "mariadb", "tidb", "sqlserver", "sqlite")) {
      assertEquals(Dialect.NameCase.KEPT, Dialects.byName(kept).unquotedNames(), kept);
    }
  }
}
