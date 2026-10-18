package org.migrax;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.dialect.PostgresDialect;
import org.migrax.diff.DiffEngine;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddCheck;
import org.migrax.ops.CreateTable;
import org.migrax.ops.DropCheck;
import org.migrax.ops.Operation;
import org.migrax.ops.SetComment;
import org.migrax.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Check constraints and comments: diff, SQL for each database, snapshot. */
class ChecksAndCommentsTest {
  private static final SchemaModel.Column ID =
      new SchemaModel.Column("id", "bigint", false, null, null, null, null, false, false, null,
          "bigint");

  private static SchemaModel.Column price(String comment) {
    return new SchemaModel.Column("price", "integer", false, null, null, null, null, false,
        false, null, "integer", comment);
  }

  private static SchemaModel model(String condition, String tableComment, String columnComment) {
    List<SchemaModel.Check> checks = condition == null ? List.of() : List.of(
        new SchemaModel.Check(SchemaModel.checkConstraintName("product", condition), condition));
    return new SchemaModel(List.of(new SchemaModel.Table("product",
        List.of(ID, price(columnComment)), new SchemaModel.PrimaryKey(List.of("id"), "product_pkey"),
        List.of(), List.of(), checks, tableComment)));
  }

  @Test
  void newTablesGetTheirChecksInlineAndCommentsAfterwards() {
    List<Operation> operations = new DiffEngine().diff(SchemaModel.empty(),
        model("price >= 0", "Things we sell", "In cents"));
    assertEquals(3, operations.size(), operations.toString());
    assertTrue(operations.get(0) instanceof CreateTable);
    Dialect postgres = Dialects.byName("postgresql");
    String name = SchemaModel.checkConstraintName("product", "price >= 0");
    assertTrue(name.matches("ck_product_[0-9a-f]{8}"), name);
    assertEquals("CREATE TABLE product (id bigint NOT NULL, price integer NOT NULL, CONSTRAINT "
        + "product_pkey PRIMARY KEY (id), CONSTRAINT " + name + " CHECK (price >= 0))",
        postgres.render(operations.get(0)));
    assertEquals("COMMENT ON TABLE product IS 'Things we sell'",
        postgres.render(operations.get(1)));
    assertEquals("COMMENT ON COLUMN product.price IS 'In cents'",
        postgres.render(operations.get(2)));
  }

  @Test
  void aChangedConditionDropsTheOldCheckAndAddsTheNewOne() {
    List<Operation> operations = new DiffEngine().diff(model("price >= 0", null, null),
        model("price > 0", null, null));
    assertEquals(2, operations.size(), operations.toString());
    assertTrue(operations.get(0) instanceof DropCheck);
    assertTrue(operations.get(1) instanceof AddCheck);
    assertFalse(operations.stream().anyMatch(Operation::destructive));
    // The rollback is the reverse diff.
    List<Operation> back = new DiffEngine().diff(model("price > 0", null, null),
        model("price >= 0", null, null));
    assertEquals(((DropCheck) operations.get(0)).name(), ((AddCheck) back.get(1)).check().name());
  }

  /** Databases store conditions their own way; only a different meaning is a change. */
  @Test
  void spacingAndOuterParenthesesAreNotAChange() {
    SchemaModel.Check stored = new SchemaModel.Check("ck_c", "((balance >= 0))");
    assertTrue(SchemaModel.sameCheck(stored, new SchemaModel.Check("CK_C", "balance >= 0")));
    assertTrue(SchemaModel.sameCheck(new SchemaModel.Check("ck_c", "(a > 0) and (b > 0)"),
        new SchemaModel.Check("ck_c", "(a > 0)  and (b > 0)")));
    assertFalse(SchemaModel.sameCheck(new SchemaModel.Check("ck_c", "(a > 0) and (b > 0)"),
        new SchemaModel.Check("ck_c", "a > 0) and (b > 0")));
    assertFalse(SchemaModel.sameCheck(new SchemaModel.Check("ck_c", "(a or b) and c"),
        new SchemaModel.Check("ck_c", "a or (b and c)")));
    assertFalse(SchemaModel.sameCheck(new SchemaModel.Check("ck_c", "name <> 'a b'"),
        new SchemaModel.Check("ck_c", "name <> 'ab'")));
  }

  @Test
  void eachDatabaseGetsItsOwnSql() {
    SchemaModel.Check check = new SchemaModel.Check("ck_product_1", "price >= 0");
    Operation add = new AddCheck("product", check);
    Operation drop = new DropCheck("product", "ck_product_1");
    Operation table = new SetComment("product", null, "It's sold");
    Operation column = new SetComment("product", price("In cents"), "In cents");
    Operation removed = new SetComment("product", price(null), null);

    Dialect postgres = Dialects.byName("postgresql");
    assertEquals("ALTER TABLE product ADD CONSTRAINT ck_product_1 CHECK (price >= 0)",
        postgres.render(add));
    assertEquals("ALTER TABLE product DROP CONSTRAINT ck_product_1", postgres.render(drop));
    assertEquals("COMMENT ON TABLE product IS 'It''s sold'", postgres.render(table));
    assertEquals("COMMENT ON COLUMN product.price IS NULL", postgres.render(removed));

    Dialect mysql = Dialects.byName("mysql");
    assertEquals("ALTER TABLE product DROP CHECK ck_product_1", mysql.render(drop));
    assertEquals("ALTER TABLE product COMMENT = 'It''s sold'", mysql.render(table));
    assertEquals("ALTER TABLE product MODIFY COLUMN price int NOT NULL COMMENT 'In cents'",
        mysql.render(column));
    assertEquals("ALTER TABLE product MODIFY COLUMN price int NOT NULL",
        mysql.render(removed));
    assertEquals("ALTER TABLE product DROP CONSTRAINT ck_product_1",
        Dialects.byName("mariadb").render(drop));

    assertEquals("COMMENT ON COLUMN product.price IS ''",
        Dialects.byName("oracle").render(removed));
    assertEquals("COMMENT ON TABLE product IS 'It''s sold'", Dialects.byName("h2").render(table));

    String sqlServer = Dialects.byName("sqlserver").render(column);
    assertTrue(sqlServer.startsWith("DECLARE @schema sysname = SCHEMA_NAME() IF EXISTS ("),
        sqlServer);
    assertTrue(sqlServer.contains("EXEC sp_updateextendedproperty @name = N'MS_Description', "
        + "@value = N'In cents'"), sqlServer);
    assertTrue(sqlServer.contains("ELSE EXEC sp_addextendedproperty"), sqlServer);
    assertFalse(sqlServer.contains(";"), sqlServer);
    assertTrue(Dialects.byName("sqlserver").render(removed)
        .contains("EXEC sp_dropextendedproperty @name = N'MS_Description'"));
  }

  @Test
  void safeModeAddsChecksWithoutBlockingWrites() {
    Operation add = new AddCheck("product", new SchemaModel.Check("ck_product_1", "price >= 0"));
    assertEquals("ALTER TABLE product ADD CONSTRAINT ck_product_1 CHECK (price >= 0) NOT VALID;\n"
        + "ALTER TABLE product VALIDATE CONSTRAINT ck_product_1",
        ((PostgresDialect) Dialects.byName("postgresql")).renderOnline(add));
  }

  @Test
  void mysqlKeepsColumnCommentsInTheColumnDefinition() {
    Dialect mysql = Dialects.byName("mysql");
    SchemaModel after = model(null, null, "In cents");
    List<Operation> prepared = mysql.prepare(new DiffEngine().diff(SchemaModel.empty(), after),
        SchemaModel.empty(), after);
    assertEquals(1, prepared.size(), prepared.toString());
    assertTrue(mysql.render(prepared.get(0)).contains("price int NOT NULL COMMENT 'In cents'"));
  }

  @Test
  void sqliteRebuildsForChecksAndSkipsComments() {
    Dialect sqlite = Dialects.byName("sqlite");
    SchemaModel before = model(null, null, null);
    SchemaModel after = model("price >= 0", "Things we sell", "In cents");
    List<Operation> prepared = sqlite.prepare(new DiffEngine().diff(before, after), before, after);
    String sql = String.join("\n", prepared.stream().map(sqlite::render).toList());
    assertTrue(sql.contains("CHECK (price >= 0)"), sql);
    assertFalse(sql.contains("COMMENT"), sql);
  }

  private static List<SchemaModel.Check> customerChecks(
      org.migrax.model.ModelExtractor.Mode mode, java.util.Map<String, String> settings)
      throws Exception {
    return org.migrax.model.ModelExtractor.extract(Thread.currentThread().getContextClassLoader(),
        "org.migrax.hibernate.fixtures", org.migrax.model.NamingStrategy.SPRING,
        Dialects.byName("postgresql"), settings, mode).model().table("customer").checks();
  }

  @Test
  void bothReadersFindTheDeclaredChecksAndEnumChecksOnlyWhenAskedFor() throws Exception {
    var hibernate = org.migrax.model.ModelExtractor.Mode.HIBERNATE;
    var annotations = org.migrax.model.ModelExtractor.Mode.ANNOTATIONS;
    List<String> declared = List.of("balance >= 0", "email <> ''");
    assertEquals(declared, customerChecks(hibernate, java.util.Map.of()).stream()
        .map(SchemaModel.Check::expression).toList());
    assertEquals(declared, customerChecks(annotations, java.util.Map.of()).stream()
        .map(SchemaModel.Check::expression).toList());

    var on = java.util.Map.of("migrax.enum-checks", "true");
    List<SchemaModel.Check> fromHibernate = customerChecks(hibernate, on);
    assertTrue(fromHibernate.stream().anyMatch(c -> c.expression().equals("status between 0 and 1")),
        fromHibernate.toString());
    assertEquals(new java.util.HashSet<>(fromHibernate),
        new java.util.HashSet<>(customerChecks(annotations, on)));
  }

  @Test
  void checksHibernateDerivesForDiscriminatorsAndListOrderAreLeftOut() throws Exception {
    // Hibernate's @Check: the Hibernate reader and the annotation scan read it.
    for (var mode : List.of(org.migrax.model.ModelExtractor.Mode.HIBERNATE,
        org.migrax.model.ModelExtractor.Mode.ANNOTATIONS)) {
      SchemaModel model = org.migrax.model.ModelExtractor.extract(
          Thread.currentThread().getContextClassLoader(), "org.migrax.hibernate.fixtures",
          org.migrax.model.NamingStrategy.SPRING, Dialects.byName("postgresql"),
          java.util.Map.of(), mode).model();
      java.util.Map<String, List<String>> checks = new java.util.TreeMap<>();
      model.tables().stream().filter(t -> !t.checks().isEmpty()).forEach(t -> checks.put(
          t.name(), t.checks().stream().map(SchemaModel.Check::expression).toList()));
      assertEquals(java.util.Map.of("customer", List.of("balance >= 0", "email <> ''")), checks,
          mode.toString());
    }
  }

  @Test
  void conditionsCompareTheWayDatabasesStoreThem() {
    String entity = "balance >= 0";
    for (String stored : List.of("((balance >= (0)::numeric))", "([balance]>=(0))",
        "(`balance` >= 0)", "\"BALANCE\" >= 0", "(balance >= 0:::DECIMAL)", "balance>=0")) {
      assertTrue(SchemaModel.sameCheckCondition(entity, stored), stored);
    }
    assertTrue(SchemaModel.sameCheckCondition("status != 'X'",
        "((status)::text <> 'X'::character varying)"));
    assertFalse(SchemaModel.sameCheckCondition(entity, "balance > 0"));
    assertFalse(SchemaModel.sameCheckCondition(entity, "price >= 0"));
  }

  @Test
  void anUnnamedCheckHibernateCreatedIsNotAddedTwice() throws Exception {
    try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
        "jdbc:h2:mem:unnamed_checks;DB_CLOSE_DELAY=-1");
         java.sql.Statement statement = connection.createStatement()) {
      // How Hibernate writes an unnamed @Check: the database names it (CONSTRAINT_8 in H2).
      statement.execute("CREATE TABLE product (id bigint NOT NULL, price integer NOT NULL, "
          + "CONSTRAINT product_pkey PRIMARY KEY (id), CHECK (price >= 0))");
      SchemaModel entities = model("price >= 0", null, null);
      Dialect h2 = Dialects.byName("h2");
      SchemaModel previous = org.migrax.diff.DatabaseBaseline.read(connection, null, entities);
      String databaseName = previous.table("product").checks().get(0).name();
      assertFalse(databaseName.startsWith("ck_"), databaseName);
      SchemaModel current = org.migrax.diff.DatabaseBaseline.align(entities, previous, h2);
      assertEquals(List.of(), new DiffEngine().diff(previous, current), "first generate");

      // Later generates compare with the snapshot, which keeps the database's name.
      assertEquals(List.of(), new DiffEngine().diff(previous,
          org.migrax.diff.DatabaseBaseline.align(entities, previous, h2)), "later generate");
      // A changed condition drops the check under the database's name.
      SchemaModel changed = org.migrax.diff.DatabaseBaseline.align(
          model("price > 0", null, null), previous, h2);
      List<Operation> operations = new DiffEngine().diff(previous, changed);
      assertEquals(databaseName, ((DropCheck) operations.get(0)).name(), operations.toString());
      assertTrue(operations.get(1) instanceof AddCheck, operations.toString());
      statement.execute("DROP TABLE product");
    }
  }

  @Test
  void anExplicitlyNamedCheckIsNeverRenamed() {
    SchemaModel previous = model("price >= 0", null, null);
    SchemaModel.Check named = new SchemaModel.Check("ck_price", "price >= 0");
    SchemaModel entities = new SchemaModel(List.of(new SchemaModel.Table("product",
        List.of(ID, price(null)), new SchemaModel.PrimaryKey(List.of("id"), "product_pkey"),
        List.of(), List.of(), List.of(named), null)));
    SchemaModel aligned = org.migrax.diff.DatabaseBaseline.align(entities, previous,
        Dialects.byName("postgresql"));
    assertEquals("ck_price", aligned.table("product").checks().get(0).name());
  }

  @Test
  void checksAndCommentsTheEntitiesDontDeclareAreLeftAlone() throws Exception {
    try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
        "jdbc:h2:mem:unmanaged_checks;DB_CLOSE_DELAY=-1");
         java.sql.Statement statement = connection.createStatement()) {
      // Like a database Hibernate created: an enum check and comments Migrax doesn't manage.
      statement.execute("CREATE TABLE product (id bigint NOT NULL, price integer NOT NULL, "
          + "CONSTRAINT product_pkey PRIMARY KEY (id), CONSTRAINT product_price_check "
          + "CHECK (price BETWEEN 0 AND 3))");
      statement.execute("COMMENT ON TABLE product IS 'Written by hand'");
      statement.execute("COMMENT ON COLUMN product.price IS 'Also by hand'");
      SchemaModel entities = model(null, null, null);
      Dialect h2 = Dialects.byName("h2");
      SchemaModel previous = org.migrax.diff.DatabaseBaseline.read(connection, null, entities);
      SchemaModel current = org.migrax.diff.DatabaseBaseline.align(entities, previous, h2);
      assertEquals(List.of(), new DiffEngine().diff(previous, current), "first generate");
      assertEquals(List.of(), org.migrax.verify.SchemaComparator.compare(entities,
          org.migrax.plugin.DatabaseSchemaReader.read(connection), "h2"), "drift");

      // Declared ones are compared: a missing check and a different comment are reported.
      SchemaModel declared = model("price >= 0", "Things we sell", null);
      assertEquals(2, org.migrax.verify.SchemaComparator.compare(declared,
          org.migrax.plugin.DatabaseSchemaReader.read(connection), "h2").size());
      statement.execute("DROP TABLE product");
    }
  }

  @Test
  void snapshotsKeepChecksAndCommentsAndStayUnchangedWithout() throws Exception {
    SchemaModel full = model("price >= 0", "Things we sell", "In cents");
    assertEquals(full, Json.parse(Json.write(full)));
    String plain = Json.write(model(null, null, null));
    assertFalse(plain.contains("checks") || plain.contains("comment"), plain);
    assertTrue(plain.contains("\"foreignKeys\": []\n    }"), plain);
  }
}
