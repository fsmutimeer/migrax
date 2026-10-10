package io.migrax;

import io.migrax.dialect.MySqlDialect;
import io.migrax.dialect.PostgresDialect;
import io.migrax.dialect.SqlServerDialect;
import io.migrax.diff.Renames;
import io.migrax.lint.SqlLinter;
import io.migrax.model.SchemaModel;
import io.migrax.ops.Operation;
import io.migrax.ops.RenameColumn;
import io.migrax.ops.RenameTable;
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
