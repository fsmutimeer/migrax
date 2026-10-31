package org.migrax;

import org.migrax.dialect.MySqlDialect;
import org.migrax.dialect.PostgresDialect;
import org.migrax.dialect.SqlServerDialect;
import org.migrax.diff.Renames;
import org.migrax.lint.SqlLinter;
import org.migrax.model.SchemaModel;
import org.migrax.ops.Operation;
import org.migrax.ops.RenameColumn;
import org.migrax.ops.RenameTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinterAndRenamesTest {

  private static Set<String> codes(String sql, String dialect) {
    return SqlLinter.lint("test.sql", sql, dialect).stream().map(SqlLinter.Finding::code)
        .collect(Collectors.toSet());
  }

  @Test
  void flagsPostgresLockingStatementsOnExistingTables() {
    assertEquals(Set.of("MX001"), codes("CREATE INDEX i ON orders (customer_id);", "postgresql"));
    assertEquals(Set.of("MX005"), codes(
        "ALTER TABLE orders ADD CONSTRAINT fk FOREIGN KEY (c) REFERENCES customer (id);",
        "postgresql"));
    assertEquals(Set.of("MX004"), codes(
        "ALTER TABLE orders ALTER COLUMN total SET NOT NULL;", "postgresql"));
    assertEquals(Set.of("MX006"), codes(
        "ALTER TABLE orders ADD CONSTRAINT uk UNIQUE (code);", "postgresql"));
    assertTrue(codes("ALTER TABLE orders ADD COLUMN created_at timestamp DEFAULT now();",
        "postgresql").contains("MX011"));
  }

  @Test
  void warnsAboutTablesWithoutTheirSchemaInASqlServerProjectWithSchemas() {
    java.util.function.Function<String, Set<String>> tenants = sql ->
        SqlLinter.lint("test.sql", sql, "sqlserver", true).stream()
            .map(SqlLinter.Finding::code).collect(Collectors.toSet());
    assertEquals(Set.of("MX017"), tenants.apply("CREATE TABLE book (id INT PRIMARY KEY);"));
    assertEquals(Set.of("MX017"), tenants.apply("INSERT INTO book (id) VALUES (1);"));
    assertEquals(Set.of("MX017"), tenants.apply("CREATE TABLE ${migrax.schema}.book "
        + "(id INT, author_id INT REFERENCES author (id));"));
    assertEquals(Set.of(), tenants.apply("""
        CREATE TABLE ${migrax.schema}.book (id INT PRIMARY KEY,
          author_id INT REFERENCES ${migrax.schema}.author (id));
        CREATE INDEX ix_book ON ${migrax.schema}.book (author_id);
        INSERT INTO [${migrax.schema}].book (id) VALUES (1);
        CREATE TABLE #scratch (id INT);
        """));
    assertEquals(Set.of(),
        tenants.apply("-- migrax:lint-ignore MX017\nCREATE TABLE book (id INT);"));
    // Only SQL Server projects that set migrax.schemas need it.
    assertEquals(Set.of(), codes("CREATE TABLE book (id INT PRIMARY KEY);", "sqlserver"));
    assertEquals(Set.of(), SqlLinter.lint("test.sql", "CREATE TABLE book (id INT);",
        "postgresql", true).stream().map(SqlLinter.Finding::code).collect(Collectors.toSet()));
  }

  @Test
  void warnsWhenARoutineBodyTakesInTheStatementsAfterIt() {
    // A BEGIN without its END: the CREATE TABLE ends up inside the procedure.
    assertEquals(Set.of("MX016"), codes("""
        CREATE PROCEDURE p()
        BEGIN
          INSERT INTO t VALUES (1);
        CREATE TABLE u (id INT);
        """, "mysql"));
    // Closed bodies, one-statement triggers and PostgreSQL's quoted bodies are fine.
    assertEquals(Set.of(), codes("""
        CREATE PROCEDURE p()
        BEGIN
        INSERT INTO t VALUES (1);
        END;
        CREATE TRIGGER g BEFORE INSERT ON t FOR EACH ROW SET NEW.id = 1;
        """, "mysql"));
    assertEquals(Set.of(), codes("""
        CREATE FUNCTION f() RETURNS void AS $$
        BEGIN
          INSERT INTO t VALUES (1);
        END;
        $$ LANGUAGE plpgsql;
        """, "postgresql"));
  }

  @Test
  void acceptsTheSafeForms() {
    assertEquals(Set.of(), codes("-- migrax:no-transaction\n"
        + "CREATE INDEX CONCURRENTLY i ON orders (customer_id);", "postgresql"));
    assertEquals(Set.of(), codes("ALTER TABLE orders ADD CONSTRAINT fk FOREIGN KEY (c) "
        + "REFERENCES customer (id) NOT VALID;", "postgresql"));
    assertEquals(Set.of("MX012"), codes("CREATE INDEX CONCURRENTLY i ON orders (c);",
        "postgresql"));
  }

  @Test
  void aSqliteTableRebuildIsNotADropOrRename() {
    assertEquals(Set.of(), codes("CREATE TABLE migrax_new_orders (id int, c int NOT NULL);\n"
        + "INSERT INTO migrax_new_orders (id, c) SELECT id, c FROM orders;\n"
        + "DROP TABLE orders;\n"
        + "ALTER TABLE migrax_new_orders RENAME TO orders;", "sqlite"));
    assertEquals(Set.of("MX007"), codes("DROP TABLE orders;", "sqlite"));
  }

  @Test
  void ignoresTablesCreatedInTheSameMigration() {
    assertEquals(Set.of(), codes("CREATE TABLE orders (id int, c int NOT NULL);\n"
        + "CREATE INDEX i ON orders (c);\n"
        + "ALTER TABLE orders ADD CONSTRAINT fk FOREIGN KEY (c) REFERENCES customer (id);",
        "postgresql"));
  }

  @Test
  void flagsDataLossAndBreakingChangesOnEveryDatabase() {
    assertEquals(Set.of("MX002"), codes("ALTER TABLE t ADD COLUMN c varchar(10) NOT NULL;",
        "mysql"));
    assertEquals(Set.of("MX007"), codes("ALTER TABLE t DROP COLUMN c;", "mysql"));
    assertEquals(Set.of("MX007"), codes("DROP TABLE t;", "sqlserver"));
    assertEquals(Set.of("MX008"), codes("ALTER TABLE t RENAME COLUMN a TO b;", "oracle"));
    assertEquals(Set.of("MX008"), codes("EXEC sp_rename 't.a', 'b', 'COLUMN';", "sqlserver"));
    assertEquals(Set.of("MX009"), codes("DELETE FROM t;", "h2"));
    assertEquals(Set.of(), codes("DELETE FROM t WHERE id = 1;", "h2"));
    assertEquals(Set.of("MX003"), codes("ALTER TABLE t MODIFY COLUMN c varchar(5);", "mysql"));
    assertFalse(codes("ALTER TABLE t DROP CONSTRAINT fk_x;", "postgresql").contains("MX007"));
    assertFalse(codes("ALTER TABLE t DROP FOREIGN KEY fk_x;", "mysql").contains("MX007"));
  }

  @Test
  void honoursIgnoreComments() {
    assertEquals(Set.of(), codes("-- migrax:lint-ignore MX007\nDROP TABLE t;", "h2"));
    assertEquals(Set.of(), codes("-- migrax:lint-ignore-file MX007, MX009\n"
        + "DROP TABLE t;\nDELETE FROM u;", "h2"));
  }

  private static SchemaModel.Column column(String name, String type) {
    return new SchemaModel.Column(name, type, true, null, null, null, null, false, false, null,
        type);
  }

  private static SchemaModel model(SchemaModel.Table... tables) {
    return new SchemaModel(List.of(tables));
  }

  private static SchemaModel.Table table(String name, SchemaModel.Column... columns) {
    return new SchemaModel.Table(name, List.of(columns),
        new SchemaModel.PrimaryKey(List.of("id"), name + "_pkey"), List.of(), List.of());
  }

  @Test
  void proposesColumnAndTableRenames() {
    SchemaModel before = model(table("customer", column("id", "bigint"),
        column("email", "varchar")));
    SchemaModel after = model(table("customer", column("id", "bigint"),
        column("contact_email", "varchar")));
    assertEquals(List.of(new Renames.ColumnRename("customer", "email", "contact_email")),
        Renames.columnCandidates(before, after));
    // A different type is not a rename.
    assertTrue(Renames.columnCandidates(before, model(table("customer", column("id", "bigint"),
        column("contact_email", "integer")))).isEmpty());

    SchemaModel renamedTable = model(table("client", column("id", "bigint"),
        column("email", "varchar")));
    assertEquals(List.of(new Renames.TableRename("customer", "client")),
        Renames.tableCandidates(before, renamedTable));
  }

  @Test
  void proposesCaseStyleRenamesFirstEvenWhenTheTypeChanged() {
    // A database created with snake_case columns, entities now using camelCase names.
    SchemaModel before = model(table("audit", column("id", "bigint"),
        column("created_at", "timestamp"), column("event_type", "varchar"),
        column("stream_id", "varchar")));
    SchemaModel after = model(table("audit", column("id", "bigint"),
        column("streamId", "varchar"), column("eventType", "varchar"),
        column("createdAt", "timestamptz")));
    assertEquals(Set.of(new Renames.ColumnRename("audit", "created_at", "createdAt"),
            new Renames.ColumnRename("audit", "event_type", "eventType"),
            new Renames.ColumnRename("audit", "stream_id", "streamId")),
        Set.copyOf(Renames.columnCandidates(before, after)));
  }

  @Test
  void proposesTheMostSimilarNewColumnFirstAndKeepsTheOthersAsChoices() {
    // name -> fullName while a country column is added: country comes first in the table.
    SchemaModel before = model(table("customers", column("id", "bigint"),
        sized("name", 100)));
    SchemaModel after = model(table("customers", column("id", "bigint"),
        sized("country", 2), sized("full_name", 100)));
    assertEquals(List.of(new Renames.ColumnRename("customers", "name", "full_name")),
        Renames.columnCandidates(before, after));
    assertEquals(List.of(List.of(new Renames.ColumnRename("customers", "name", "full_name"),
            new Renames.ColumnRename("customers", "name", "country"))),
        Renames.columnChoices(before, after));

    // Shared words count too (phone_number -> mobileNumber), and so does the same size.
    SchemaModel words = model(table("customers", column("id", "bigint"),
        sized("note", 30), sized("mobileNumber", 30)));
    assertEquals(List.of(new Renames.ColumnRename("customers", "phone_number", "mobileNumber")),
        Renames.columnCandidates(model(table("customers", column("id", "bigint"),
            sized("phone_number", 30))), words));
    SchemaModel sizes = model(table("customers", column("id", "bigint"),
        sized("label", 20), sized("title", 100)));
    assertEquals(List.of(new Renames.ColumnRename("customers", "name", "title")),
        Renames.columnCandidates(before, sizes));
  }

  @Test
  void twoDroppedColumnsNeverGetTheSameCandidate() {
    SchemaModel before = model(table("customers", column("id", "bigint"),
        column("first", "varchar"), column("second", "varchar")));
    SchemaModel after = model(table("customers", column("id", "bigint"),
        column("one", "varchar"), column("two", "varchar")));
    assertEquals(List.of(new Renames.ColumnRename("customers", "first", "one"),
            new Renames.ColumnRename("customers", "second", "two")),
        Renames.columnCandidates(before, after));
  }

  private static SchemaModel.Column sized(String name, int length) {
    return new SchemaModel.Column(name, "varchar", true, length, null, null, null, false, false,
        null, "varchar");
  }

  @Test
  void renamesBecomeRenameOperationsAndReverseCleanly() {
    SchemaModel before = model(table("customer", column("id", "bigint"),
        column("email", "varchar")));
    SchemaModel after = model(new SchemaModel.Table("client",
        List.of(column("id", "bigint"), column("contact_email", "varchar")),
        new SchemaModel.PrimaryKey(List.of("id"), "customer_pkey"), List.of(), List.of()));
    List<Renames.TableRename> tables = Renames.parseTables("customer=client");
    List<Renames.ColumnRename> columns = Renames.parseColumns("client.email=contact_email");

    List<Operation> forward = Renames.diff(before, after, tables, columns);
    assertEquals(List.of(new RenameTable("customer", "client"),
        new RenameColumn("client", "email", "contact_email")), forward);
    List<Operation> reverse = Renames.reverse(before, after, tables, columns);
    assertEquals(List.of(new RenameColumn("client", "contact_email", "email"),
        new RenameTable("client", "customer")), reverse);

    assertEquals("ALTER TABLE customer RENAME TO client",
        new PostgresDialect().render(forward.get(0)));
    assertEquals("RENAME TABLE customer TO client", new MySqlDialect().render(forward.get(0)));
    assertEquals("EXEC sp_rename 'customer', 'client'",
        new SqlServerDialect().render(forward.get(0)));
  }
}
