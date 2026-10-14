package org.migrax.diff;

import org.migrax.model.SchemaModel;
import org.migrax.ops.AddColumn;
import org.migrax.ops.AddForeignKey;
import org.migrax.ops.AddIndex;
import org.migrax.ops.AddPrimaryKey;
import org.migrax.ops.AddCheck;
import org.migrax.ops.AddUnique;
import org.migrax.ops.DropCheck;
import org.migrax.ops.SetComment;
import org.migrax.ops.DropUnique;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.CreateSequence;
import org.migrax.ops.CreateTable;
import org.migrax.ops.DropColumn;
import org.migrax.ops.DropForeignKey;
import org.migrax.ops.DropIndex;
import org.migrax.ops.DropPrimaryKey;
import org.migrax.ops.DropSequence;
import org.migrax.ops.DropTable;
import org.migrax.ops.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Compares two {@link SchemaModel} snapshots and calculates the ordered list of DDL
 * {@link Operation}s required to transform the earlier schema into the newer schema.
 *
 * <p><strong>Note on column renames:</strong> Column name changes between snapshots produce
 * a {@link org.migrax.ops.DropColumn} followed by an {@link org.migrax.ops.AddColumn} pair
 * (a destructive change). To preserve table data on production systems, review the generated
 * migration script and replace the drop/add pair with an explicit {@code RENAME COLUMN} operation
 * before applying.
 *
 * @since 0.1.0
 */
public final class DiffEngine {

  /**
   * Computes the ordered schema migration operations between two models.
   *
   * @param before the baseline schema model
   * @param after  the target schema model
   * @return immutable ordered list of migration operations
   * @since 0.1.0
   */
  public List<Operation> diff(SchemaModel before, SchemaModel after) {
    List<Operation> drops = new ArrayList<>();
    List<Operation> changes = new ArrayList<>();
    List<Operation> adds = new ArrayList<>();

    for (SchemaModel.Sequence sequence : after.sequences()) {
      if (before.sequence(sequence.name()) == null) {
        // A sequence that is a row in a table can only be added once the table exists.
        (sequence.inTable() ? adds : changes).add(new CreateSequence(sequence));
      }
    }

    for (SchemaModel.Table oldTable : before.tables()) {
      SchemaModel.Table newTable = after.table(oldTable.name());
      for (SchemaModel.ForeignKey oldKey : oldTable.foreignKeys()) {
        SchemaModel.ForeignKey newKey = newTable == null
            ? null
            : findForeignKey(newTable, oldKey.name());
        if (newKey == null || !newKey.equals(oldKey)) {
          drops.add(new DropForeignKey(oldTable.name(), oldKey.name()));
        }
      }
    }

    for (SchemaModel.Table newTable : after.tables()) {
      SchemaModel.Table oldTable = before.table(newTable.name());
      if (oldTable == null) {
        changes.add(new CreateTable(newTable));
        for (SchemaModel.Index index : newTable.indexes()) {
          adds.add(new AddIndex(newTable.name(), index));
        }
        for (SchemaModel.ForeignKey foreignKey : newTable.foreignKeys()) {
          adds.add(new AddForeignKey(newTable.name(), foreignKey));
        }
        comments(null, newTable, adds);
        continue;
      }

      // A check whose condition changed has a new name: the old one goes, the new one comes.
      for (SchemaModel.Check oldCheck : oldTable.checks()) {
        if (!newTable.checks().contains(oldCheck)) {
          drops.add(new DropCheck(newTable.name(), oldCheck.name()));
        }
      }
      for (SchemaModel.Check newCheck : newTable.checks()) {
        if (!oldTable.checks().contains(newCheck)) {
          adds.add(new AddCheck(newTable.name(), newCheck));
        }
      }
      comments(oldTable, newTable, adds);

      if (!samePrimaryKey(oldTable.primaryKey(), newTable.primaryKey())
          && oldTable.primaryKey() != null) {
        drops.add(new DropPrimaryKey(
            newTable.name(), oldTable.primaryKey().constraintName()));
      }

      for (SchemaModel.Index oldIndex : oldTable.indexes()) {
        SchemaModel.Index newIndex = findIndex(newTable, oldIndex.name());
        if (newIndex == null || !newIndex.equals(oldIndex)) {
          drops.add(new DropIndex(newTable.name(), oldIndex.name()));
        }
      }

      for (SchemaModel.Column oldColumn : oldTable.columns()) {
        if (newTable.column(oldColumn.name()) == null) {
          changes.add(new DropColumn(newTable.name(), oldColumn.name()));
        }
      }
      for (SchemaModel.Column newColumn : newTable.columns()) {
        SchemaModel.Column oldColumn = oldTable.column(newColumn.name());
        String uniqueName = SchemaModel.uniqueConstraintName(newTable.name(), newColumn.name());
        if (oldColumn == null) {
          changes.add(new AddColumn(newTable.name(), newColumn));
          if (newColumn.unique()) {
            adds.add(new AddUnique(newTable.name(), newColumn.name(), uniqueName));
          }
          continue;
        }
        if (!sameColumn(oldColumn, newColumn)) {
          changes.add(new AlterColumn(newTable.name(), oldColumn, newColumn));
        }
        if (!oldColumn.unique() && newColumn.unique()) {
          adds.add(new AddUnique(newTable.name(), newColumn.name(), uniqueName));
        } else if (oldColumn.unique() && !newColumn.unique()) {
          drops.add(new DropUnique(newTable.name(), newColumn.name(), uniqueName));
        }
      }

      if (!samePrimaryKey(oldTable.primaryKey(), newTable.primaryKey())
          && newTable.primaryKey() != null) {
        adds.add(new AddPrimaryKey(newTable.name(), newTable.primaryKey()));
      }

      for (SchemaModel.Index newIndex : newTable.indexes()) {
        SchemaModel.Index oldIndex = findIndex(oldTable, newIndex.name());
        if (oldIndex == null || !oldIndex.equals(newIndex)) {
          adds.add(new AddIndex(newTable.name(), newIndex));
        }
      }
      for (SchemaModel.ForeignKey newKey : newTable.foreignKeys()) {
        SchemaModel.ForeignKey oldKey = findForeignKey(oldTable, newKey.name());
        if (oldKey == null || !oldKey.equals(newKey)) {
          adds.add(new AddForeignKey(newTable.name(), newKey));
        }
      }
    }

    for (SchemaModel.Table oldTable : before.tables()) {
      if (after.table(oldTable.name()) == null) {
        changes.add(new DropTable(oldTable.name()));
      }
    }

    for (SchemaModel.Sequence sequence : before.sequences()) {
      if (after.sequence(sequence.name()) == null) {
        // A sequence that is a row in a table goes before its table can.
        (sequence.inTable() ? drops : changes).add(new DropSequence(sequence.name(), sequence));
      }
    }

    List<Operation> result = new ArrayList<>(drops.size() + changes.size() + adds.size());
    result.addAll(drops);
    result.addAll(changes);
    result.addAll(adds);
    return List.copyOf(result);
  }

  /** Comments that differ from the old table (all of them for a new table). */
  private static void comments(SchemaModel.Table oldTable, SchemaModel.Table newTable,
                               List<Operation> operations) {
    if (!Objects.equals(oldTable == null ? null : oldTable.comment(), newTable.comment())) {
      operations.add(new SetComment(newTable.name(), null, newTable.comment()));
    }
    for (SchemaModel.Column column : newTable.columns()) {
      SchemaModel.Column old = oldTable == null ? null : oldTable.column(column.name());
      if (!Objects.equals(old == null ? null : old.comment(), column.comment())) {
        operations.add(new SetComment(newTable.name(), column, column.comment()));
      }
    }
  }

  private static boolean samePrimaryKey(
      SchemaModel.PrimaryKey before, SchemaModel.PrimaryKey after) {
    return before == after || before != null && after != null
        && before.columns().equals(after.columns());
  }

  /**
   * Compares what ALTER COLUMN can change. Uniqueness is handled with separate constraint
   * operations, and the sequence behind an id is created or dropped on its own, so neither
   * counts here.
   */
  private static boolean sameColumn(SchemaModel.Column before, SchemaModel.Column after) {
    return before.name().equals(after.name())
        && before.nullable() == after.nullable()
        && Objects.equals(SchemaModel.effectiveLength(before), SchemaModel.effectiveLength(after))
        && Objects.equals(before.precision(), after.precision())
        && Objects.equals(before.scale(), after.scale())
        && before.identity() == after.identity()
        && Objects.equals(AlterColumn.normalizeDefault(before.defaultValue()),
            AlterColumn.normalizeDefault(after.defaultValue()))
        && Objects.equals(before.logicalType(), after.logicalType())
        && sameExplicitSqlType(before, after);
  }

  private static boolean sameExplicitSqlType(
      SchemaModel.Column before, SchemaModel.Column after) {
    boolean beforeExplicit = isExplicitSqlType(before);
    boolean afterExplicit = isExplicitSqlType(after);
    if (beforeExplicit != afterExplicit) {
      return false;
    }
    if (!beforeExplicit) {
      return true;
    }
    return normalizeSqlType(before.sqlType()).equals(normalizeSqlType(after.sqlType()));
  }

  private static boolean isExplicitSqlType(SchemaModel.Column column) {
    return column.sqlType() != null && column.logicalType() != null
        && !column.sqlType().equalsIgnoreCase(column.logicalType());
  }

  private static String normalizeSqlType(String sqlType) {
    return sqlType.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
  }

  private static SchemaModel.Index findIndex(SchemaModel.Table table, String name) {
    return table.indexes().stream()
        .filter(index -> index.name().equals(name))
        .findFirst()
        .orElse(null);
  }

  private static SchemaModel.ForeignKey findForeignKey(SchemaModel.Table table, String name) {
    return table.foreignKeys().stream()
        .filter(foreignKey -> foreignKey.name().equals(name))
        .findFirst()
        .orElse(null);
  }
}
