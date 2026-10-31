package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.diff.DatabaseBaseline;
import org.migrax.diff.Renames;
import org.migrax.diff.SnapshotStore;
import org.migrax.model.ModelExtractor;
import org.migrax.model.SchemaModel;
import org.migrax.ops.Operation;
import org.migrax.util.Log;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The entity changes since the snapshot: the models before and after, and the operations
 * between them. Used by {@code generate}, {@code plan} and {@code check}.
 *
 * @param entities table name to the entity class that owns it; empty when the reader does not
 *     tell
 */
record EntityChanges(SchemaModel previous, SchemaModel current, List<Operation> operations,
                     List<Renames.TableRename> tableRenames,
                     List<Renames.ColumnRename> columnRenames, String source,
                     Map<String, String> entities) {
  /** Questions asked about one dropped column before Migrax treats it as dropped. */
  private static final int MAX_RENAME_QUESTIONS = 3;
  /** Option values a shell passes on unchanged. */
  private static final java.util.regex.Pattern SHELL_SAFE =
      java.util.regex.Pattern.compile("[A-Za-z0-9_.=-]+");

  /**
   * Compares the entities with the snapshot (or the database on the first run), asking about
   * likely renames.
   *
   * @param useDatabaseBaseline read the database when there is no snapshot yet
   * @param ask ask whether likely renames are renames
   */
  static EntityChanges read(CommandContext context, Dialect dialect, boolean useDatabaseBaseline,
                            boolean ask) throws Exception {
    Project project = context.project();
    Args args = context.args();
    ModelExtractor.Result extracted = project.extract(dialect);
    SchemaModel current = extracted.model();
    boolean hasSnapshot = Files.exists(project.snapshot());
    SchemaModel previous;
    if (hasSnapshot) {
      previous = SnapshotStore.load(project.snapshot());
    } else if (useDatabaseBaseline) {
      try (Connection connection = project.connect()) {
        // A schema file is read from a database exactly as it is, so the baseline is too.
        previous = DatabaseBaseline.read(connection, args.option("--schema"), current,
            project.schemaFile() != null);
      }
      Log.info("No snapshot yet: using the current database schema as the baseline.");
    } else {
      previous = SchemaModel.empty();
    }
    current = DatabaseBaseline.align(current, previous, dialect);
    if (project.schemaFile() != null) {
      current = SchemaFile.withNamesFrom(current, previous);
    }

    List<Renames.TableRename> tables =
        new ArrayList<>(Renames.parseTables(args.option("--rename-table")));
    SchemaModel renamedTables = Renames.applyTables(previous, tables);
    if (ask) {
      for (Renames.TableRename candidate : Renames.tableCandidates(renamedTables, current)) {
        if (confirmRename(context,
            "Did you rename table " + candidate.from() + " to " + candidate.to() + "?",
            "--rename-table " + shellWord(candidate.toString()))) {
          tables.add(candidate);
        }
      }
    }
    renamedTables = Renames.applyTables(previous, tables);
    List<Renames.ColumnRename> columns =
        new ArrayList<>(Renames.parseColumns(args.option("--rename")));
    if (ask) {
      SchemaModel renamed = Renames.applyColumns(renamedTables, columns);
      Set<String> taken = new HashSet<>();
      for (List<Renames.ColumnRename> choices : Renames.columnChoices(renamed, current)) {
        // After a "no", the next likely new name is asked about (at most three questions per
        // column); without a terminal only the most likely one is printed.
        int asked = 0;
        for (Renames.ColumnRename candidate : choices) {
          String target = candidate.table() + "." + candidate.to();
          if (taken.contains(target)) {
            continue;
          }
          boolean yes = confirmRename(context, "Did you rename " + candidate.table() + "."
              + candidate.from() + " to " + target + "?",
              "--rename " + shellWord(candidate.toString()));
          if (yes || !context.interactive()) {
            if (yes) {
              columns.add(candidate);
            }
            taken.add(target);
            break;
          }
          if (++asked == MAX_RENAME_QUESTIONS) {
            break;
          }
        }
      }
    }
    List<Operation> operations = Renames.diff(previous, current, tables, columns);
    return new EntityChanges(previous, current, operations, tables, columns, extracted.source(),
        extracted.entities());
  }

  /**
   * An option value as it can be pasted into a shell: unchanged when it has only letters,
   * digits and _ . = -, otherwise in single quotes, so a name like a$(b) is not run.
   */
  static String shellWord(String value) {
    if (SHELL_SAFE.matcher(value).matches()) {
      return value;
    }
    return "'" + value.replace("'", "'\\''") + "'";
  }

  /** Asks about a rename, or prints how to answer it with an option when Migrax can't ask. */
  private static boolean confirmRename(CommandContext context, String question, String option)
      throws Exception {
    if (context.interactive()) {
      boolean yes = context.confirm(question);
      if (yes || context.interactive()) {
        return yes;
      }
      // The input ended without an answer: print the hint as when Migrax can't ask.
    }
    context.out().println("Possible rename: " + question.replace("Did you rename ", "")
        .replace("?", "") + ". To keep the data, rerun with " + option + ".");
    return false;
  }
}
