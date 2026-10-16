package org.migrax.diff;

import org.migrax.dialect.Dialect;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.Operation;
import org.migrax.ops.RunSql;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Splits entity changes into two migrations that let the old and the new application version
 * run at the same time ({@code generate --zero-downtime}).
 *
 * <p>The <b>expand</b> migration runs with the new release, while old instances still run. It
 * only adds and relaxes: new tables and columns (NOT NULL ones still nullable), wider types,
 * fewer constraints. A renamed column is added under its new name, filled from the old one and
 * kept equal to it by a trigger; a renamed table keeps its old name as a view.
 *
 * <p>The <b>contract</b> migration runs with the next release, when no old instance is left. It
 * removes and tightens: drops tables and columns (and the triggers and views), sets NOT NULL,
 * narrows types and adds constraints that old instances could break.
 *
 * <p>Both are diffs to and from a <b>middle</b> schema that the two versions can share.
 *
 * @since 0.11.0
 */
public final class ZeroDowntime {

  /**
   * The two migrations.
   *
   * @param middle the schema after the expand migration
   * @param expand the expand migration
   * @param expandReverse its rollback
   * @param rolledBack the schema after that rollback: the previous one, except that columns
   *     the new version doesn't write stay nullable (its rows have no value for them)
   * @param contract the contract migration; empty when nothing waits for the old version
   * @param contractReverse its rollback
   * @param notes things the developer must check or do, for the migration headers
   * @param problems changes this database can't split; nothing is written when there are any
   */
  public record Plan(SchemaModel middle, List<Operation> expand, List<Operation> expandReverse,
                     SchemaModel rolledBack,
                     List<Operation> contract, List<Operation> contractReverse,
                     List<String> notes, List<String> problems) {
    public Plan {
      expand = List.copyOf(expand);
      expandReverse = List.copyOf(expandReverse);
      contract = List.copyOf(contract);
      contractReverse = List.copyOf(contractReverse);
      notes = List.copyOf(notes);
      problems = List.copyOf(problems);
    }
  }

  private ZeroDowntime() {}

  /** Plans the two migrations from {@code previous} to {@code current}. */
  public static Plan plan(SchemaModel previous, SchemaModel current,
                          List<Renames.TableRename> tableRenames,
                          List<Renames.ColumnRename> columnRenames, Dialect dialect) {
    List<String> notes = new ArrayList<>();
    List<String> problems = new ArrayList<>();
    if (!tableRenames.isEmpty() && !dialect.updatableViews()) {
      problems.add("Renaming table " + tableRenames.get(0).from() + " needs a view with its old "
          + "name that accepts writes while the old version runs, which " + dialect.id()
          + " doesn't have.");
    }
    for (Renames.ColumnRename rename : columnRenames) {
      if (dialect.syncColumns(rename.table(), rename.from(), rename.to()).isEmpty()) {
        problems.add("Renaming " + rename.table() + "." + rename.from() + " needs a trigger that "
            + "keeps " + rename.from() + " and " + rename.to() + " equal while the old version "
            + "runs, which Migrax can't write for " + dialect.id() + ".");
      }
    }
    SchemaModel renamed = Renames.applyTables(previous, tableRenames);
    SchemaModel middle = middle(renamed, current, columnRenames, notes, problems);

    List<Operation> expand = new ArrayList<>(Renames.diff(previous, middle, tableRenames,
        List.of()));
    List<Operation> expandReverse = new ArrayList<>();
    List<Operation> contract = new ArrayList<>();
    List<Operation> contractReverse = new ArrayList<>(new DiffEngine().diff(current, middle));

    // Renamed columns: kept equal by a trigger, then filled from the old column.
    for (Renames.ColumnRename rename : columnRenames) {
      List<String> sync = dialect.syncColumns(rename.table(), rename.from(), rename.to());
      List<String> unsync = dialect.dropSyncColumns(rename.table(), rename.from(), rename.to());
      String table = dialect.tableName(rename.table());
      String from = dialect.identifier(rename.from());
      String to = dialect.identifier(rename.to());
      sync.forEach(sql -> expand.add(new RunSql(sql, true)));
      expand.add(new RunSql("UPDATE " + table + " SET " + to + " = " + from, true));
      unsync.forEach(sql -> expandReverse.add(new RunSql(sql, true)));
      unsync.forEach(sql -> contract.add(new RunSql(sql, true)));
      sync.forEach(sql -> contractReverse.add(new RunSql(sql, true)));
      contractReverse.add(new RunSql("UPDATE " + table + " SET " + from + " = " + to, true));
    }
    // Renamed tables: the old name stays as a view over the new table.
    for (Renames.TableRename rename : tableRenames) {
      SchemaModel.Table old = previous.table(rename.from());
      String view = "CREATE VIEW " + dialect.tableName(rename.from()) + " AS SELECT "
          + String.join(", ", old.columns().stream()
              .map(c -> dialect.identifier(c.name())).toList())
          + " FROM " + dialect.tableName(rename.to());
      String drop = "DROP VIEW " + dialect.tableName(rename.from());
      expand.add(new RunSql(view, true));
      expandReverse.add(0, new RunSql(drop, true));
      contract.add(0, new RunSql(drop, true));
      contractReverse.add(new RunSql(view, true));
    }
    SchemaModel rolledBack = keepNullable(previous, renamed, current, tableRenames,
        columnRenames);
    for (SchemaModel.Table table : previous.tables()) {
      for (SchemaModel.Column column : table.columns()) {
        if (!column.nullable() && rolledBack.table(table.name()).column(column.name())
            .nullable()) {
          expandReverse.add(0, new RunSql("-- " + table.name() + "." + column.name()
              + " stays nullable: rows the new version wrote have no value for it", true));
        }
      }
    }
    expandReverse.addAll(Renames.reverse(rolledBack, middle, tableRenames, List.of()));
    List<Operation> tighten = new DiffEngine().diff(middle, current);
    contract.addAll(tighten);
    if (tighten.isEmpty() && contract.isEmpty()) {
      contractReverse.clear();
    }
    for (Operation operation : tighten) {
      if (operation instanceof AlterColumn alter && alter.nullabilityChanged()
          && !alter.after().nullable()) {
        SchemaModel.Table before = renamed.table(alter.table());
        boolean newColumn = before == null || before.column(alter.after().name()) == null;
        boolean renamedColumn = columnRenames.stream().anyMatch(r -> r.table()
            .equals(alter.table()) && r.to().equals(alter.after().name()));
        if (newColumn && !renamedColumn) {
          notes.add(alter.table() + "." + alter.after().name() + " is NOT NULL in the contract "
              + "migration. Rows written by the old version have no value: fill them before it "
              + "runs, for example in the expand migration (UPDATE " + alter.table() + " SET "
              + alter.after().name() + " = ... WHERE " + alter.after().name() + " IS NULL).");
        }
      }
    }
    return new Plan(middle, expand, expandReverse, rolledBack, contract, contractReverse,
        notes, problems);
  }

  /**
   * The previous schema with the columns the new version drops nullable, under their previous
   * table names. Renamed columns keep NOT NULL: the trigger fills them.
   */
  private static SchemaModel keepNullable(SchemaModel previous, SchemaModel renamed,
                                          SchemaModel current,
                                          List<Renames.TableRename> tableRenames,
                                          List<Renames.ColumnRename> columnRenames) {
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (SchemaModel.Table table : previous.tables()) {
      String now = tableRenames.stream().filter(r -> r.from().equals(table.name()))
          .map(Renames.TableRename::to).findFirst().orElse(table.name());
      SchemaModel.Table target = current.table(now);
      if (target == null || renamed.table(now) == null) {
        tables.add(table);
        continue;
      }
      List<SchemaModel.Column> columns = table.columns().stream()
          .map(c -> target.column(c.name()) == null && !c.nullable()
              && columnRenames.stream().noneMatch(r -> r.table().equals(now)
                  && r.from().equals(c.name())) ? with(c, true, c.unique()) : c).toList();
      tables.add(new SchemaModel.Table(table.name(), columns, table.primaryKey(),
          table.indexes(), table.foreignKeys(), table.checks(), table.comment()));
    }
    return new SchemaModel(tables, previous.sequences());
  }

  /** The schema both versions can use. */
  private static SchemaModel middle(SchemaModel previous, SchemaModel current,
                                    List<Renames.ColumnRename> columnRenames,
                                    List<String> notes, List<String> problems) {
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (SchemaModel.Table old : previous.tables()) {
      SchemaModel.Table now = current.table(old.name());
      if (now == null) {
        tables.add(old); // Dropped in the contract migration.
        continue;
      }
      Map<String, String> renames = new HashMap<>();
      columnRenames.stream().filter(r -> r.table().equals(old.name()))
          .forEach(r -> renames.put(r.from(), r.to()));
      if (!samePrimaryKey(old, now, renames)) {
        problems.add("The primary key of " + old.name() + " changes. Change it in a migration of "
            + "its own, without --zero-downtime.");
      }
      tables.add(middleTable(old, now, renames, notes));
    }
    for (SchemaModel.Table now : current.tables()) {
      if (previous.table(now.name()) == null) {
        tables.add(now);
      }
    }
    List<SchemaModel.Sequence> sequences = new ArrayList<>(previous.sequences());
    for (SchemaModel.Sequence sequence : current.sequences()) {
      if (previous.sequence(sequence.name()) == null) {
        sequences.add(sequence);
      }
    }
    return new SchemaModel(tables, sequences);
  }

  private static SchemaModel.Table middleTable(SchemaModel.Table old, SchemaModel.Table now,
                                               Map<String, String> renames, List<String> notes) {
    List<SchemaModel.Column> columns = new ArrayList<>();
    for (SchemaModel.Column column : old.columns()) {
      SchemaModel.Column target = now.column(column.name());
      if (target == null) {
        // Dropped, or renamed: the old version still writes it, the new one doesn't.
        columns.add(with(column, true, column.unique()));
      } else {
        columns.add(middleColumn(old.name(), column, target, notes));
      }
    }
    for (SchemaModel.Column column : now.columns()) {
      if (old.column(column.name()) != null) {
        continue;
      }
      boolean renameTarget = renames.containsValue(column.name());
      boolean required = !column.nullable() && column.defaultValue() == null
          && !column.identity();
      // The old version doesn't write new columns, and a uniqueness rule could reject the
      // NULLs it leaves (SQL Server allows one).
      columns.add(with(column, column.nullable() || required || renameTarget, false));
    }
    List<SchemaModel.Index> indexes = new ArrayList<>();
    for (SchemaModel.Index index : old.indexes()) {
      SchemaModel.Index target = find(now.indexes(), index.name());
      // A unique index the new version doesn't have goes now: it could reject its rows.
      if (!index.unique() || index.equals(target)) {
        indexes.add(index);
      }
    }
    for (SchemaModel.Index index : now.indexes()) {
      if (!index.unique() && find(old.indexes(), index.name()) == null) {
        indexes.add(index);
      }
    }
    List<SchemaModel.ForeignKey> keys = old.foreignKeys().stream()
        .filter(now.foreignKeys()::contains).toList();
    List<SchemaModel.Check> checks = old.checks().stream().filter(now.checks()::contains)
        .toList();
    return new SchemaModel.Table(now.name(), columns, old.primaryKey(), indexes, keys, checks,
        now.comment());
  }

  /** A column both versions use: relaxed now, tightened later. */
  private static SchemaModel.Column middleColumn(String table, SchemaModel.Column old,
                                                 SchemaModel.Column now, List<String> notes) {
    boolean widens = widens(old, now);
    SchemaModel.Column type = widens ? now : old;
    if (!widens) {
      notes.add(table + "." + now.name() + " keeps its old type until the contract migration: "
          + "the old version may still write values that don't fit the new one.");
    }
    return new SchemaModel.Column(now.name(), type.sqlType(), old.nullable() || now.nullable(),
        type.length(), type.precision(), type.scale(), now.defaultValue(),
        old.unique() && now.unique(), type.identity(), now.sequenceName(), type.logicalType(),
        now.comment());
  }

  private static final List<List<String>> LADDERS = List.of(
      List.of("tinyint", "smallint", "integer", "bigint"),
      List.of("float", "double"),
      List.of("varchar", "text"),
      List.of("nvarchar", "text"));

  /**
   * True when every value the old type holds fits the new one: the same type with no smaller
   * size, or a larger type of the same kind (integer to bigint, varchar to text).
   */
  static boolean widens(SchemaModel.Column old, SchemaModel.Column now) {
    AlterColumn change = new AlterColumn("", old, now);
    if (!change.destructive()) {
      return true;
    }
    if (old.identity() != now.identity() || explicit(old) || explicit(now)) {
      return false;
    }
    for (List<String> ladder : LADDERS) {
      int from = ladder.indexOf(old.logicalType());
      int to = ladder.indexOf(now.logicalType());
      if (from >= 0 && to > from) {
        return true;
      }
    }
    return false;
  }

  private static boolean explicit(SchemaModel.Column column) {
    return column.sqlType() != null && !column.sqlType().equalsIgnoreCase(column.logicalType());
  }

  private static SchemaModel.Column with(SchemaModel.Column c, boolean nullable, boolean unique) {
    return new SchemaModel.Column(c.name(), c.sqlType(), nullable, c.length(), c.precision(),
        c.scale(), c.defaultValue(), unique, c.identity(), c.sequenceName(), c.logicalType(),
        c.comment());
  }

  /** The same key columns, none of them renamed. */
  private static boolean samePrimaryKey(SchemaModel.Table old, SchemaModel.Table now,
                                        Map<String, String> renames) {
    List<String> before = old.primaryKey() == null ? List.of() : old.primaryKey().columns();
    List<String> after = now.primaryKey() == null ? List.of() : now.primaryKey().columns();
    return before.equals(after) && before.stream().noneMatch(renames::containsKey);
  }

  private static SchemaModel.Index find(List<SchemaModel.Index> indexes, String name) {
    return indexes.stream().filter(i -> Objects.equals(i.name(), name)).findFirst()
        .orElse(null);
  }
}
