package org.migrax.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddColumn;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.DropColumn;
import org.migrax.ops.DropTable;
import org.junit.jupiter.api.Test;

class KeepDataQuestionTest {
  private static SchemaModel.Column text(int length) {
    return new SchemaModel.Column("note", "VARCHAR", true, length, null, null, null, false);
  }

  private static CommandContext answering(String input, String... args) {
    PrintStream out = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    return new CommandContext(Args.parse(args), out, out,
        input == null ? null : new BufferedReader(new StringReader(input)), null, null);
  }

  @Test
  void dropsAndNarrowedColumnsLoseTheirDataOnRollback() {
    assertTrue(MigrationSql.losesData(new DropTable("orders")));
    assertTrue(MigrationSql.losesData(new DropColumn("orders", "note")));
    assertTrue(MigrationSql.losesData(new AlterColumn("orders", text(100), text(50))));
    assertFalse(MigrationSql.losesData(new AlterColumn("orders", text(50), text(100))));
    assertFalse(MigrationSql.losesData(new AddColumn("orders", text(50))));
  }

  @Test
  void aQuestionThatDefaultsToYesNeedsAClearNo() throws Exception {
    assertTrue(answering("\n").confirm("Keep?", true), "Enter takes the default");
    assertTrue(answering("y\n").confirm("Keep?", true));
    assertTrue(answering("ok\n").confirm("Keep?", true), "only a no leaves the default");
    assertFalse(answering("n\n").confirm("Keep?", true));
    assertFalse(answering("No\n").confirm("Keep?", true));
    assertFalse(answering("").confirm("Keep?", true), "input that ends answers nothing");
    assertFalse(answering(null).confirm("Keep?", true), "no input: Migrax can't ask");

    assertFalse(answering("\n").confirm("Delete?"), "[y/N] keeps its default");
    assertFalse(answering("ok\n").confirm("Delete?"));
    assertTrue(answering("yes\n").confirm("Delete?"));
  }

  @Test
  void jsonNeverAsks() {
    assertTrue(answering("\n").asksQuestions());
    assertFalse(answering("\n", "--json").asksQuestions());
    assertFalse(answering(null).asksQuestions());
  }
}
