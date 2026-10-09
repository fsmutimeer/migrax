package io.migrax.diff;

import io.migrax.model.SchemaModel;
import io.migrax.ops.Operation;
import io.migrax.ops.RenameColumn;
import io.migrax.ops.RenameTable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns "drop + add" into renames, so data is kept: Migrax asks "Did you rename ...?" first.
 *
 * <p>Confirmed renames are applied to the previous model first; the remaining differences are
 * then diffed normally. The result is {@code RENAME} operations followed by any real changes.
 *
 * @since 0.1.0
 */
public final class Renames {
  private Renames() {}

  /** A table rename. */
  public record TableRename(String from, String to) {
    @Override
    public String toString() {
      return from + "=" + to;
    }
  }

  /** A column rename within a table (the table's name after any table rename). */
  public record ColumnRename(String table, String from, String to) {
    @Override
    public String toString() {
      return table + "." + from + "=" + to;
    }
  }

  /** Parses {@code old=new} pairs separated by commas. */
  public static List<TableRename> parseTables(String value) {
    List<TableRename> renames = new ArrayList<>();
    for (String pair : split(value)) {
      String[] parts = pair.split("=", 2);
      if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
        throw new IllegalArgumentException(
            "Table renames look like old_table=new_table, got '" + pair + "'.");
      }
      renames.add(new TableRename(parts[0].trim(), parts[1].trim()));
    }
    return renames;
  }

  /** Parses {@code table.old=new} pairs separated by commas. */
  public static List<ColumnRename> parseColumns(String value) {
    List<ColumnRename> renames = new ArrayList<>();
    for (String pair : split(value)) {
      String[] parts = pair.split("=", 2);
      int dot = parts[0].lastIndexOf('.');
      if (parts.length != 2 || dot <= 0 || parts[1].isBlank()) {
        throw new IllegalArgumentException(
            "Column renames look like table.old_column=new_column, got '" + pair + "'.");
      }
      String to = parts[1].trim();
      int targetDot = to.lastIndexOf('.');
      renames.add(new ColumnRename(parts[0].substring(0, dot).trim(),
          parts[0].substring(dot + 1).trim(), targetDot >= 0 ? to.substring(targetDot + 1) : to));
    }
    return renames;
  }

  private static List<String> split(String value) {
    List<String> parts = new ArrayList<>();
    if (value != null) {
      for (String part : value.split(",")) {
        if (!part.isBlank()) {
          parts.add(part.trim());
        }
      }
    }
    return parts;
  }

  /**
   * Tables that disappeared while a similar table appeared: at least half of the columns share
   * names and types.
   */
  public static List<TableRename> tableCandidates(SchemaModel previous, SchemaModel current) {
    List<TableRename> candidates = new ArrayList<>();
    Set<String> used = new HashSet<>();
    for (SchemaModel.Table old : previous.tables()) {
      if (current.table(old.name()) != null) {
        continue;
      }
      SchemaModel.Table best = null;
      double bestScore = 0.5;
      for (SchemaModel.Table now : current.tables()) {
        if (previous.table(now.name()) != null || used.contains(now.name())) {
          continue;
        }
        double score = similarity(old, now);
        if (score >= bestScore) {
          bestScore = score;
          best = now;
        }
      }
      if (best != null) {
        used.add(best.name());
        candidates.add(new TableRename(old.name(), best.name()));
      }
    }
    return candidates;
  }

  private static double similarity(SchemaModel.Table a, SchemaModel.Table b) {
    int shared = 0;
    for (SchemaModel.Column column : a.columns()) {
      SchemaModel.Column other = b.column(column.name());
      if (other != null && sameType(column, other)) {
        shared++;
      }
    }
    return (double) shared / Math.max(a.columns().size(), b.columns().size());
  }

  /**
   * Columns that disappeared from a table while a column of the same type appeared. Each
   * dropped column is proposed at most once.
   */
  public static List<ColumnRename> columnCandidates(SchemaModel previous, SchemaModel current) {
    List<ColumnRename> candidates = new ArrayList<>();
    for (SchemaModel.Table now : current.tables()) {
      SchemaModel.Table old = previous.table(now.name());
      if (old == null) {
        continue;
      }
      Set<String> used = new HashSet<>();
      Set<String> matched = new HashSet<>();
      // First pass: the same name in another case style (created_at -> createdAt), even when
      // the type changed too; the type change then follows the rename as an ALTER.
      for (SchemaModel.Column dropped : old.columns()) {
        if (now.column(dropped.name()) != null) {
          continue;
        }
        for (SchemaModel.Column added : now.columns()) {
          if (old.column(added.name()) == null && !used.contains(added.name())
              && looseName(dropped.name()).equals(looseName(added.name()))) {
            used.add(added.name());
            matched.add(dropped.name());
            candidates.add(new ColumnRename(now.name(), dropped.name(), added.name()));
            break;
          }
        }
      }
      // Second pass: any new column of the same type.
      for (SchemaModel.Column dropped : old.columns()) {
        if (now.column(dropped.name()) != null || matched.contains(dropped.name())) {
          continue;
        }
        for (SchemaModel.Column added : now.columns()) {
          if (old.column(added.name()) == null && !used.contains(added.name())
              && sameType(dropped, added)) {
            used.add(added.name());
            candidates.add(new ColumnRename(now.name(), dropped.name(), added.name()));
            break;
          }
        }
      }
    }
    return candidates;
  }

  /** A column name without case or underscores, so created_at and createdAt compare equal. */
  private static String looseName(String name) {
    return name.replace("_", "").toLowerCase(Locale.ROOT);
  }

  private static boolean sameType(SchemaModel.Column a, SchemaModel.Column b) {
    return Objects.equals(a.logicalType(), b.logicalType())
        && Objects.equals(normalize(a.sqlType()), normalize(b.sqlType()));
  }

  private static String normalize(String type) {
    return type == null ? null : type.trim().toLowerCase(Locale.ROOT).replace(" ", "");
  }

  /** The previous model with tables renamed, including foreign keys that point at them. */
  public static SchemaModel applyTables(SchemaModel model, List<TableRename> renames) {
    if (renames.isEmpty()) {
      return model;
    }
    Map<String, String> names = new java.util.HashMap<>();
    for (TableRename rename : renames) {
      if (model.table(rename.from()) == null) {
        throw new IllegalArgumentException("Cannot rename table '" + rename.from()
            + "': it is not in the previous schema.");
      }
      names.put(rename.from(), rename.to());
    }
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (SchemaModel.Table table : model.tables()) {
      List<SchemaModel.ForeignKey> keys = table.foreignKeys().stream()
          .map(k -> new SchemaModel.ForeignKey(k.name(), k.columns(),
              names.getOrDefault(k.referencedTable(), k.referencedTable()), k.referencedColumns()))
          .toList();
      tables.add(new SchemaModel.Table(names.getOrDefault(table.name(), table.name()),
          table.columns(), table.primaryKey(), table.indexes(), keys));
    }
    return new SchemaModel(tables, model.sequences());
  }

  /** The previous model with columns renamed, including keys and indexes that use them. */
  public static SchemaModel applyColumns(SchemaModel model, List<ColumnRename> renames) {
    SchemaModel result = model;
    for (ColumnRename rename : renames) {
      SchemaModel.Table target = result.table(rename.table());
      if (target == null || target.column(rename.from()) == null) {
        throw new IllegalArgumentException("Cannot rename column '" + rename.table() + "."
            + rename.from() + "': it is not in the previous schema.");
      }
      List<SchemaModel.Table> tables = new ArrayList<>();
      for (SchemaModel.Table table : result.tables()) {
        boolean own = table.name().equals(rename.table());
        List<SchemaModel.Column> columns = table.columns().stream()
            .map(c -> own && c.name().equals(rename.from())
                ? new SchemaModel.Column(rename.to(), c.sqlType(), c.nullable(), c.length(),
                    c.precision(), c.scale(), c.defaultValue(), c.unique(), c.identity(),
                    c.sequenceName(), c.logicalType())
                : c)
            .toList();
        SchemaModel.PrimaryKey key = table.primaryKey() == null || !own ? table.primaryKey()
            : new SchemaModel.PrimaryKey(swap(table.primaryKey().columns(), rename),
                table.primaryKey().constraintName());
        List<SchemaModel.Index> indexes = table.indexes().stream()
            .map(i -> own ? new SchemaModel.Index(i.name(), swap(i.columns(), rename), i.unique())
                : i)
            .toList();
        List<SchemaModel.ForeignKey> keys = table.foreignKeys().stream()
            .map(k -> new SchemaModel.ForeignKey(k.name(),
                own ? swap(k.columns(), rename) : k.columns(), k.referencedTable(),
                k.referencedTable().equals(rename.table())
                    ? swap(k.referencedColumns(), rename) : k.referencedColumns()))
            .toList();
        tables.add(new SchemaModel.Table(table.name(), columns, key, indexes, keys));
      }
      result = new SchemaModel(tables, result.sequences());
    }
    return result;
  }

  private static List<String> swap(List<String> names, ColumnRename rename) {
    return names.stream().map(n -> n.equals(rename.from()) ? rename.to() : n).toList();
  }

  /**
   * Forward operations: renames first, then the normal diff from the renamed previous model.
   */
  public static List<Operation> diff(SchemaModel previous, SchemaModel current,
                                     List<TableRename> tables, List<ColumnRename> columns) {
    SchemaModel renamed = applyColumns(applyTables(previous, tables), columns);
    List<Operation> operations = new ArrayList<>();
    tables.forEach(t -> operations.add(new RenameTable(t.from(), t.to())));
    columns.forEach(c -> operations.add(new RenameColumn(c.table(), c.from(), c.to())));
    operations.addAll(new DiffEngine().diff(renamed, current));
    return operations;
  }

  /** Rollback operations: undo the changes first, then the renames in reverse order. */
  public static List<Operation> reverse(SchemaModel previous, SchemaModel current,
                                        List<TableRename> tables, List<ColumnRename> columns) {
    SchemaModel renamed = applyColumns(applyTables(previous, tables), columns);
    List<Operation> operations = new ArrayList<>(new DiffEngine().diff(current, renamed));
    for (int i = columns.size() - 1; i >= 0; i--) {
      ColumnRename c = columns.get(i);
      operations.add(new RenameColumn(c.table(), c.to(), c.from()));
    }
    for (int i = tables.size() - 1; i >= 0; i--) {
      TableRename t = tables.get(i);
      operations.add(new RenameTable(t.to(), t.from()));
    }
    return operations;
  }
}
