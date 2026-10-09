package io.migrax.cli;

import io.migrax.dialect.Dialect;
import io.migrax.diff.DatabaseBaseline;
import io.migrax.diff.Renames;
import io.migrax.diff.SnapshotStore;
import io.migrax.model.ModelExtractor;
import io.migrax.model.SchemaModel;
import io.migrax.ops.Operation;
import io.migrax.util.Log;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/**
 * The entity changes since the snapshot: the models before and after, and the operations
 * between them. Used by {@code generate}, {@code plan} and {@code check}.
 */
record EntityChanges(SchemaModel previous, SchemaModel current, List<Operation> operations,
                     List<Renames.TableRename> tableRenames,
                     List<Renames.ColumnRename> columnRenames, String source) {

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
        previous = DatabaseBaseline.read(connection, args.option("--schema"), current);
      }
      Log.info("No snapshot yet: using the current database schema as the baseline.");
    } else {
      previous = SchemaModel.empty();
    }
    current = DatabaseBaseline.align(current, previous, dialect);

    List<Renames.TableRename> tables =
        new ArrayList<>(Renames.parseTables(args.option("--rename-table")));
    SchemaModel renamedTables = Renames.applyTables(previous, tables);
    if (ask) {
      for (Renames.TableRename candidate : Renames.tableCandidates(renamedTables, current)) {
        if (confirmRename(context,
            "Did you rename table " + candidate.from() + " to " + candidate.to() + "?",
            "--rename-table " + candidate)) {
          tables.add(candidate);
        }
      }
    }
    renamedTables = Renames.applyTables(previous, tables);
    List<Renames.ColumnRename> columns =
        new ArrayList<>(Renames.parseColumns(args.option("--rename")));
    if (ask) {
      SchemaModel renamed = Renames.applyColumns(renamedTables, columns);
      for (Renames.ColumnRename candidate : Renames.columnCandidates(renamed, current)) {
        if (confirmRename(context, "Did you rename " + candidate.table() + "." + candidate.from()
            + " to " + candidate.table() + "." + candidate.to() + "?", "--rename " + candidate)) {
          columns.add(candidate);
        }
      }
    }
    List<Operation> operations = Renames.diff(previous, current, tables, columns);
    return new EntityChanges(previous, current, operations, tables, columns, extracted.source());
  }

  /** Asks about a rename, or prints how to answer it with an option when Migrax can't ask. */
  private static boolean confirmRename(CommandContext context, String question, String option)
      throws Exception {
    if (!context.interactive()) {
      context.out().println("Possible rename: " + question.replace("Did you rename ", "")
          .replace("?", "") + ". To keep the data, rerun with " + option + ".");
      return false;
    }
    return context.confirm(question);
  }
}
