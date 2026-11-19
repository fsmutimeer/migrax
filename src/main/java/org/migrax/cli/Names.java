package org.migrax.cli;

import org.migrax.model.SchemaModel;
import org.migrax.util.Log;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Names with line breaks or other control characters. Migrax writes names into SQL comment
 * lines and terminal messages, where a line break would end the comment (the rest of the name
 * would run as SQL) and an escape sequence would control the terminal. No database name needs
 * them, so {@link #check} refuses a model that has one before anything is written.
 */
final class Names {
  private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");

  private Names() {}

  /** Throws MXE118 when a name in {@code model} (read from {@code source}) has one. */
  static void check(SchemaModel model, String source) {
    for (SchemaModel.Table table : model.tables()) {
      check(table.name(), "A table", source);
      String in = "table " + table.name();
      for (SchemaModel.Column column : table.columns()) {
        check(column.name(), "A column of " + in, source);
        check(column.sequenceName(), "A sequence of " + in, source);
      }
      if (table.primaryKey() != null) {
        check(table.primaryKey().constraintName(), "The primary key of " + in, source);
        checkAll(table.primaryKey().columns(), "The primary key of " + in, source);
      }
      for (SchemaModel.Index index : table.indexes()) {
        check(index.name(), "An index of " + in, source);
        checkAll(index.columns(), "An index of " + in, source);
      }
      for (SchemaModel.ForeignKey key : table.foreignKeys()) {
        check(key.name(), "A foreign key of " + in, source);
        checkAll(key.columns(), "A foreign key of " + in, source);
        check(key.referencedTable(), "A foreign key of " + in, source);
        checkAll(key.referencedColumns(), "A foreign key of " + in, source);
      }
      for (SchemaModel.Check constraint : table.checks()) {
        check(constraint.name(), "A check constraint of " + in, source);
      }
    }
    for (SchemaModel.Sequence sequence : model.sequences()) {
      check(sequence.name(), "A sequence", source);
      String of = "Sequence " + sequence.name();
      check(sequence.table(), of, source);
      check(sequence.nameColumn(), of, source);
      check(sequence.valueColumn(), of, source);
    }
  }

  /**
   * {@code text} on one line: line breaks and tabs shown as {@code \n}, {@code \r},
   * {@code \t}, other control characters as {@link Log#terminalText} shows them.
   */
  static String printable(String text) {
    if (text == null || !CONTROL.matcher(text).find()) {
      return text;
    }
    return Log.terminalText(
        text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
  }

  private static void checkAll(List<String> names, String what, String source) {
    for (String name : names) {
      check(name, what, source);
    }
  }

  private static void check(String name, String what, String source) {
    if (name != null && CONTROL.matcher(name).find()) {
      String shown = printable(name);
      throw new UsageException(ErrorCode.CONTROL_CHARACTER_IN_NAME,
          what + " in " + source + " has a name with a line break or control character: "
              + shown + ".",
          "Rename it in " + source + ": SQL comments and messages can't show it safely.");
    }
  }
}
