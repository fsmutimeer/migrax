package org.migrax.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.migrax.dialect.Dialects;
import org.migrax.model.SchemaModel;
import org.migrax.ops.DropColumn;
import org.migrax.ops.Operation;

class NamesTest {
  private static final String ESC = String.valueOf((char) 27);

  private static SchemaModel table(String table, String column) {
    return new SchemaModel(List.of(new SchemaModel.Table(table,
        List.of(new SchemaModel.Column(column, "VARCHAR", true, 10, null, null, null, false)),
        null, List.of(), List.of())));
  }

  @Test
  void namesWithLineBreaksOrControlCharactersAreRefused() {
    Names.check(table("orders", "note ü"), "the entities");
    UsageException error = assertThrows(UsageException.class,
        () -> Names.check(table("orders", "a\nDROP TABLE victim;--"), "db/schema.sql"));
    assertEquals(ErrorCode.CONTROL_CHARACTER_IN_NAME, error.code);
    assertEquals("MXE118", error.code.code);
    assertTrue(error.getMessage().contains("a\\nDROP TABLE victim;--"), error.getMessage());
    assertTrue(error.getMessage().startsWith("A column of table orders in db/schema.sql has"),
        error.getMessage());
    assertEquals("Rename it in db/schema.sql: SQL comments and messages can't show it safely.",
        error.hint);
    assertFalse(error.getMessage().contains("\n"), "the message stays on one line");

    assertThrows(UsageException.class,
        () -> Names.check(table("x" + ESC + "[2Jy", "id"), "the entities"));
    assertThrows(UsageException.class,
        () -> Names.check(table("orders", "a b"), "the entities"));
  }

  @Test
  void everyKindOfNameIsChecked() {
    String bad = "a\nb";
    SchemaModel.Column ok = new SchemaModel.Column("id", "BIGINT", false, null, null, null,
        null, false);
    SchemaModel.Column withSequence = new SchemaModel.Column("id", "BIGINT", false, null, null,
        null, null, false, false, bad, null, null);
    List<SchemaModel.Table> tables = List.of(
        new SchemaModel.Table("t", List.of(withSequence), null, List.of(), List.of()),
        new SchemaModel.Table("t", List.of(ok), new SchemaModel.PrimaryKey(List.of("id"), bad),
            List.of(), List.of()),
        new SchemaModel.Table("t", List.of(ok), new SchemaModel.PrimaryKey(List.of(bad)),
            List.of(), List.of()),
        new SchemaModel.Table("t", List.of(ok), null,
            List.of(new SchemaModel.Index(bad, List.of("id"), false)), List.of()),
        new SchemaModel.Table("t", List.of(ok), null,
            List.of(new SchemaModel.Index("i", List.of(bad), false)), List.of()),
        new SchemaModel.Table("t", List.of(ok), null, List.of(), List.of(
            new SchemaModel.ForeignKey(bad, List.of("id"), "u", List.of("id")))),
        new SchemaModel.Table("t", List.of(ok), null, List.of(), List.of(
            new SchemaModel.ForeignKey("f", List.of(bad), "u", List.of("id")))),
        new SchemaModel.Table("t", List.of(ok), null, List.of(), List.of(
            new SchemaModel.ForeignKey("f", List.of("id"), bad, List.of("id")))),
        new SchemaModel.Table("t", List.of(ok), null, List.of(), List.of(
            new SchemaModel.ForeignKey("f", List.of("id"), "u", List.of(bad)))),
        new SchemaModel.Table("t", List.of(ok), null, List.of(), List.of(),
            List.of(new SchemaModel.Check(bad, "id > 0")), null));
    for (SchemaModel.Table table : tables) {
      assertThrows(UsageException.class,
          () -> Names.check(new SchemaModel(List.of(table)), "x"), table.toString());
    }
    for (SchemaModel.Sequence sequence : List.of(
        new SchemaModel.Sequence(bad, 1L, 1L),
        new SchemaModel.Sequence("s", 1L, 1L, bad, "n", "v"),
        new SchemaModel.Sequence("s", 1L, 1L, "t", bad, "v"),
        new SchemaModel.Sequence("s", 1L, 1L, "t", "n", bad))) {
      assertThrows(UsageException.class,
          () -> Names.check(new SchemaModel(List.of(), List.of(sequence)), "x"),
          sequence.toString());
    }
    // Comments and check expressions are text, not names: line breaks are fine there.
    Names.check(new SchemaModel(List.of(new SchemaModel.Table("t", List.of(ok), null,
        List.of(), List.of(), List.of(new SchemaModel.Check("c", "id >\n 0")), "two\nlines"))),
        "x");
  }

  @Test
  void printableKeepsTextOnOneLine() {
    String plain = "orders.note";
    assertSame(plain, Names.printable(plain));
    assertEquals("a\\nb\\r\\tc\\u001b[2J", Names.printable("a\nb\r\tc" + ESC + "[2J"));
  }

  @Test
  void rollbackCommentsStayComments(@TempDir Path migrations) throws Exception {
    // Names.check stops such a name earlier; the rollback header doesn't rely on it.
    List<Operation> forward = List.of(new DropColumn("orders", "a\nDROP TABLE victim;--"));
    MigrationSql.writeRollback(migrations, "0002_drop.sql", Dialects.byName("h2"), List.of(),
        forward, SchemaModel.empty(), SchemaModel.empty());
    String rollback = Files.readString(migrations.resolve("rollback/0002_drop.sql"));
    for (String line : rollback.split("\n")) {
      assertTrue(line.isEmpty() || line.startsWith("--"), "not a comment: " + line);
    }
  }
}
