package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddCheck;
import org.migrax.ops.AddColumn;
import org.migrax.ops.AddForeignKey;
import org.migrax.ops.AddIndex;
import org.migrax.ops.AddPrimaryKey;
import org.migrax.ops.AddUnique;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.CreateTable;
import org.migrax.ops.DropColumn;
import org.migrax.ops.DropForeignKey;
import org.migrax.ops.DropTable;
import org.migrax.ops.Operation;
import org.migrax.ops.RenameTable;
import org.migrax.ops.RunSql;
import org.migrax.ops.SetComment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code generate --keep-data}: a migration that drops data keeps it in backup tables, and its
 * rollback script puts it back. A dropped table is renamed to its backup, so the rollback
 * renames it back with its rows, keys and indexes. A dropped column, or a column whose type
 * change loses data, is first copied with the table's primary key into a backup table, and the
 * rollback fills it again from there, row by row.
 */
final class KeepData {
  /** Prefix of every backup table, which drift and the first generate leave alone. */
  static final String PREFIX = "migrax_backup_";

  private KeepData() {}

  /**
   * The migration and its rollback, rewritten to keep data. The rollback runs in phases, each
   * prepared for the database on its own (SQLite rebuilds tables at the end of a phase): the
   * structure, with kept NOT NULL columns still nullable ({@code reverse}, ending at
   * {@code middle}); the values ({@code restores}); NOT NULL again ({@code finish}); and the
   * column backups dropped ({@code cleanup}).
   *
   * @param forward the migration's operations
   * @param reverse the rollback's structure operations
   * @param middle the schema after {@code reverse}
   * @param restores statements that put kept values back
   * @param finish operations that make restored columns NOT NULL again
   * @param cleanup statements that drop the column backups
   * @param kept the forward operations whose data the rollback restores
   * @param notes what the rollback script's header says about it
   * @param warnings changes whose data can't be kept (no primary key)
   */
  record Plan(List<Operation> forward, List<Operation> reverse, SchemaModel middle,
              List<String> restores, List<Operation> finish, List<String> cleanup,
              List<Operation> kept, List<String> notes, List<String> warnings) {}

  /** A column whose values sit in a backup table. */
  private record KeptColumn(String table, SchemaModel.Column column, List<String> key,
                            String backup) {}

  static Plan apply(List<Operation> forward, List<Operation> reverse, SchemaModel before,
                    Dialect dialect, String number) {
    List<Operation> newForward = new ArrayList<>();
    List<Operation> kept = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    Map<String, String> keptTables = new LinkedHashMap<>();
    List<KeptColumn> keptColumns = new ArrayList<>();
    for (Operation operation : forward) {
      if (operation instanceof DropTable drop) {
        String backup = backupName(number, drop.table(), null);
        // The migration has already dropped the table's own foreign keys (so they can't stop
        // later migrations from dropping the tables they point to); the rollback adds them back.
        newForward.add(new RenameTable(drop.table(), backup));
        keptTables.put(drop.table(), backup);
        kept.add(operation);
        notes.add("Restores table " + drop.table() + " with its rows from " + backup + ".");
        continue;
      }
      SchemaModel.Column column = null;
      String table = null;
      if (operation instanceof DropColumn drop) {
        table = drop.table();
        SchemaModel.Table old = before.table(table);
        column = old == null ? null : old.column(drop.column());
      } else if (operation instanceof AlterColumn alter && alter.destructive()) {
        table = alter.table();
        column = alter.before();
      }
      if (column != null) {
        SchemaModel.Table old = before.table(table);
        SchemaModel.PrimaryKey key = old == null ? null : old.primaryKey();
        if (key == null || key.columns().isEmpty() || key.columns().contains(column.name())) {
          warnings.add("--keep-data can't keep " + table + "." + column.name()
              + ": the table has no primary key to match its rows by.");
        } else {
          String backup = backupName(number, table, column.name());
          List<String> copied = new ArrayList<>(key.columns());
          copied.add(column.name());
          newForward.add(new RunSql(dialect.copyTable(backup, table, copied), true));
          keptColumns.add(new KeptColumn(table, column, key.columns(), backup));
          kept.add(operation);
          notes.add("Restores the values of " + table + "." + column.name() + " from " + backup
              + ".");
        }
      }
      newForward.add(operation);
    }

    boolean sqlite = "sqlite".equals(dialect.id());
    if (sqlite) {
      // SQLite drops a foreign key by rebuilding the table from the model, which a backup isn't
      // in: a kept table keeps its foreign keys (SQLite migrates with them switched off).
      newForward.removeIf(o -> o instanceof DropForeignKey key
          && keptTables.containsKey(key.table()));
    }
    List<Operation> newReverse = new ArrayList<>();
    List<Operation> finish = new ArrayList<>();
    List<SchemaModel.Column> madeNullable = new ArrayList<>();
    for (Operation operation : reverse) {
      if (operation instanceof CreateTable create
          && keptTables.containsKey(create.table().name())) {
        newReverse.add(new RenameTable(keptTables.get(create.table().name()),
            create.table().name()));
        continue;
      }
      String table = Reports.table(operation);
      boolean partOfKeptTable = table != null && keptTables.containsKey(table)
          && (operation instanceof AddIndex || operation instanceof AddUnique
              || operation instanceof AddPrimaryKey || operation instanceof SetComment
              || operation instanceof AddCheck || sqlite && operation instanceof AddForeignKey);
      if (partOfKeptTable) {
        continue; // The renamed table still has them.
      }
      if (operation instanceof AddColumn add && !add.column().nullable()) {
        KeptColumn keptColumn = keptColumns.stream().filter(k -> k.table().equals(add.table())
            && k.column().name().equals(add.column().name())).findFirst().orElse(null);
        if (keptColumn != null) {
          // Added nullable, filled from the backup, then made NOT NULL again.
          newReverse.add(new AddColumn(add.table(), nullable(add.column(), true)));
          finish.add(new AlterColumn(add.table(), nullable(add.column(), true), add.column()));
          madeNullable.add(add.column());
          continue;
        }
      }
      newReverse.add(operation);
    }
    List<String> restores = keptColumns.stream().map(column -> restore(dialect, column))
        .toList();
    List<String> cleanup = keptColumns.stream()
        .map(column -> "DROP TABLE " + dialect.tableName(column.backup())).toList();
    return new Plan(newForward, newReverse, withNullable(before, keptColumns, madeNullable),
        restores, finish, cleanup, kept, notes, warnings);
  }

  /** The schema with the given kept columns nullable: where the rollback's structure ends. */
  private static SchemaModel withNullable(SchemaModel model, List<KeptColumn> kept,
                                          List<SchemaModel.Column> columns) {
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (SchemaModel.Table table : model.tables()) {
      List<SchemaModel.Column> changed = table.columns().stream()
          .map(c -> columns.contains(c) && kept.stream().anyMatch(k -> k.table()
              .equals(table.name()) && k.column().name().equals(c.name()))
              ? nullable(c, true) : c)
          .toList();
      tables.add(new SchemaModel.Table(table.name(), changed, table.primaryKey(),
          table.indexes(), table.foreignKeys(), table.checks(), table.comment()));
    }
    return new SchemaModel(tables, model.sequences());
  }

  /**
   * Puts the column's values back where the primary key matches. The subqueries refer to the
   * updated table by its name alone, which works when the dialect puts a schema in front.
   */
  private static String restore(Dialect dialect, KeptColumn kept) {
    String table = dialect.identifier(kept.table());
    String backup = dialect.tableName(kept.backup());
    String column = dialect.identifier(kept.column().name());
    String match = String.join(" AND ", kept.key().stream()
        .map(k -> "b." + dialect.identifier(k) + " = " + table + "." + dialect.identifier(k))
        .toList());
    return "UPDATE " + dialect.tableName(kept.table()) + " SET " + column + " = (SELECT b."
        + column + " FROM " + backup
        + " b WHERE " + match + ") WHERE EXISTS (SELECT 1 FROM " + backup + " b WHERE " + match
        + ")";
  }

  private static SchemaModel.Column nullable(SchemaModel.Column c, boolean nullable) {
    return new SchemaModel.Column(c.name(), c.sqlType(), nullable, c.length(), c.precision(),
        c.scale(), c.defaultValue(), c.unique(), c.identity(), c.sequenceName(),
        c.logicalType(), c.comment());
  }

  /**
   * {@code migrax_backup_<number>_<table>[_<column>]} in lower case; shortened with a hash
   * when it would be longer than every database accepts.
   */
  static String backupName(String number, String table, String column) {
    String name = (PREFIX + number + "_" + table + (column == null ? "" : "_" + column))
        .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    if (name.length() <= 60) {
      return name;
    }
    return name.substring(0, 51) + "_" + String.format(Locale.ROOT, "%08x", name.hashCode());
  }
}
