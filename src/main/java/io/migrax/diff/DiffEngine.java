package io.migrax.diff;

import io.migrax.model.SchemaModel;
import io.migrax.ops.AddColumn;
import io.migrax.ops.AddForeignKey;
import io.migrax.ops.AddIndex;
import io.migrax.ops.AddPrimaryKey;
import io.migrax.ops.AlterColumn;
import io.migrax.ops.CreateSequence;
import io.migrax.ops.CreateTable;
import io.migrax.ops.DropColumn;
import io.migrax.ops.DropForeignKey;
import io.migrax.ops.DropIndex;
import io.migrax.ops.DropPrimaryKey;
import io.migrax.ops.DropSequence;
import io.migrax.ops.DropTable;
import io.migrax.ops.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class DiffEngine {
  public List<Operation> diff(SchemaModel before, SchemaModel after) {
    List<Operation> drops = new ArrayList<>();
    List<Operation> changes = new ArrayList<>();
    List<Operation> adds = new ArrayList<>();

    for (SchemaModel.Sequence sequence : after.sequences()) {
      if (before.sequence(sequence.name()) == null) {
        changes.add(new CreateSequence(sequence));
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
        continue;
      }

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
        if (oldColumn == null) {
          changes.add(new AddColumn(newTable.name(), newColumn));
        } else if (!sameColumn(oldColumn, newColumn)) {
          changes.add(new AlterColumn(newTable.name(), oldColumn, newColumn));
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
        changes.add(new DropSequence(sequence.name()));
      }
    }

    List<Operation> result = new ArrayList<>(drops.size() + changes.size() + adds.size());
    result.addAll(drops);
    result.addAll(changes);
    result.addAll(adds);
    return List.copyOf(result);
  }

  private static boolean samePrimaryKey(
      SchemaModel.PrimaryKey before, SchemaModel.PrimaryKey after) {
    return before == after || before != null && after != null
        && before.columns().equals(after.columns());
  }

  private static boolean sameColumn(SchemaModel.Column before, SchemaModel.Column after) {
    return before.name().equals(after.name())
        && before.nullable() == after.nullable()
        && Objects.equals(before.length(), after.length())
        && Objects.equals(before.precision(), after.precision())
        && Objects.equals(before.scale(), after.scale())
        && before.unique() == after.unique()
        && before.identity() == after.identity()
        && Objects.equals(before.sequenceName(), after.sequenceName())
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
