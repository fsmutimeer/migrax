package io.migrax.model;

import java.util.*;

public record SchemaModel(List<Table> tables, List<Sequence> sequences) {
  public SchemaModel { tables=List.copyOf(tables); sequences=List.copyOf(sequences); }
  public SchemaModel(List<Table> tables){ this(tables, List.of()); }
  public static SchemaModel empty(){ return new SchemaModel(List.of(), List.of()); }
  public Table table(String name){ return tables.stream().filter(t->t.name().equals(name)).findFirst().orElse(null); }
  public Sequence sequence(String name){ return sequences.stream().filter(s->s.name().equals(name)).findFirst().orElse(null); }
  public SchemaModel withPrimaryKeyNamesFrom(SchemaModel metadata) {
    List<Table> namedTables = tables.stream().map(table -> {
      PrimaryKey key = table.primaryKey();
      if (key == null) {
        return table;
      }
      PrimaryKey source = metadata.table(table.name()) == null
          ? null
          : metadata.table(table.name()).primaryKey();
      boolean existingKey = source != null && key.columns().equals(source.columns());
      String constraintName = existingKey
          ? source.constraintName()
          : primaryKeyConstraintName(table.name());
      return new Table(table.name(), table.columns(),
          new PrimaryKey(key.columns(), constraintName), table.indexes(), table.foreignKeys());
    }).toList();
    return new SchemaModel(namedTables, sequences);
  }

  /** Length that matters for comparison: 255 when unset for sized types, null otherwise. */
  public static Integer effectiveLength(Column column) {
    String logical = column.logicalType() == null ? "" : column.logicalType();
    boolean sized = logical.equals("varchar") || logical.equals("nvarchar")
        || logical.equals("varbinary");
    if (!sized) {
      return null;
    }
    Integer length = column.length();
    return length != null ? length : Integer.valueOf(255);
  }

  /**
   * Keeps the existing column type from {@code previous} where Hibernate's schema validation
   * accepts it for the new mapping, so switching how entities are read does not create type
   * changes: a smaller integer type stored in a wider existing column, and an enum type stored
   * in an existing character column.
   */
  public SchemaModel withCompatibleTypesFrom(SchemaModel previous) {
    List<Table> result = new ArrayList<>();
    for (Table table : tables) {
      Table old = previous.table(table.name());
      if (old == null) {
        result.add(table);
        continue;
      }
      List<Column> columns = new ArrayList<>();
      for (Column column : table.columns()) {
        Column existing = old.column(column.name());
        columns.add(existing != null && compatibleType(existing, column)
            ? new Column(column.name(), existing.sqlType(), column.nullable(), existing.length(),
                existing.precision(), existing.scale(), column.defaultValue(), column.unique(),
                column.identity(), column.sequenceName(), existing.logicalType())
            : column);
      }
      result.add(new Table(table.name(), columns, table.primaryKey(), table.indexes(),
          table.foreignKeys()));
    }
    return new SchemaModel(result, sequences);
  }

  private static boolean compatibleType(Column existing, Column wanted) {
    int existingRank = integralRank(existing);
    int wantedRank = integralRank(wanted);
    if (existingRank > 0 && wantedRank > 0) {
      return existingRank >= wantedRank;
    }
    String wantedType = typeText(wanted);
    String existingLogical = existing.logicalType() == null ? "" : existing.logicalType();
    if (wantedType.startsWith("enum") && (existingLogical.equals("varchar")
        || existingLogical.equals("nvarchar"))) {
      return true;
    }
    return false;
  }

  private static String typeText(Column column) {
    String sql = column.sqlType() == null ? "" : column.sqlType();
    return sql.trim().toLowerCase(Locale.ROOT);
  }

  /** 1 tinyint, 2 smallint, 3 integer, 4 bigint; 0 for other types. */
  private static int integralRank(Column column) {
    String type = typeText(column).replace(" ", "");
    String logical = column.logicalType() == null ? "" : column.logicalType();
    if (type.equals("tinyint") || type.equals("number(3,0)")) return 1;
    if (type.equals("smallint") || type.equals("number(5,0)")) return 2;
    if (type.equals("bigint") || type.equals("number(19,0)")) return 4;
    if (type.equals("integer") || type.equals("int") || type.equals("number(10,0)")) return 3;
    if (logical.equals("bigint")) return 4;
    if (logical.equals("integer") && type.equals(logical)) return 3;
    return 0;
  }

  /**
   * Keeps the names of primary keys, foreign keys and indexes from {@code previous} when only
   * their names differ, so a naming change does not drop and recreate constraints.
   */
  public SchemaModel withConstraintNamesFrom(SchemaModel previous) {
    SchemaModel named = withPrimaryKeyNamesFrom(previous);
    List<Table> result = new ArrayList<>();
    for (Table table : named.tables()) {
      Table old = previous.table(table.name());
      if (old == null) {
        result.add(table);
        continue;
      }
      List<ForeignKey> keys = new ArrayList<>();
      for (ForeignKey key : table.foreignKeys()) {
        ForeignKey match = old.foreignKeys().stream()
            .filter(k -> k.name().equals(key.name())).findFirst()
            .or(() -> old.foreignKeys().stream()
                .filter(k -> k.columns().equals(key.columns())
                    && k.referencedTable().equals(key.referencedTable())
                    && k.referencedColumns().equals(key.referencedColumns()))
                .findFirst())
            .orElse(null);
        keys.add(match != null && match.columns().equals(key.columns())
            && match.referencedTable().equals(key.referencedTable())
            && match.referencedColumns().equals(key.referencedColumns())
            ? new ForeignKey(match.name(), key.columns(), key.referencedTable(),
                key.referencedColumns())
            : key);
      }
      List<Index> indexes = new ArrayList<>();
      for (Index index : table.indexes()) {
        Index match = old.indexes().stream()
            .filter(i -> i.columns().equals(index.columns()) && i.unique() == index.unique())
            .findFirst().orElse(null);
        boolean sameNameExists = old.indexes().stream().anyMatch(i -> i.name().equals(index.name()));
        indexes.add(match != null && !sameNameExists
            ? new Index(match.name(), index.columns(), index.unique()) : index);
      }
      result.add(new Table(table.name(), table.columns(), table.primaryKey(), indexes, keys));
    }
    return new SchemaModel(result, named.sequences());
  }

  public static String primaryKeyConstraintName(String tableName) {
    String candidate = tableName + "_pkey";
    if (candidate.length() <= 30) {
      return candidate;
    }
    return candidate.substring(0, 21) + "_"
        + String.format(java.util.Locale.ROOT, "%08x", tableName.hashCode());
  }
  /** Shortens a constraint name to at most 30 characters (Oracle before 12.2) with a hash. */
  public static String boundedName(String candidate) {
    if (candidate.length() <= 30) {
      return candidate;
    }
    return candidate.substring(0, 21) + "_"
        + String.format(java.util.Locale.ROOT, "%08x", candidate.hashCode());
  }

  /** Stable name for a single-column unique constraint, at most 30 characters for Oracle. */
  public static String uniqueConstraintName(String tableName, String columnName) {
    String candidate = "uk_" + tableName + "_" + columnName;
    if (candidate.length() <= 30) {
      return candidate;
    }
    return candidate.substring(0, 21) + "_"
        + String.format(java.util.Locale.ROOT, "%08x", (tableName + "." + columnName).hashCode());
  }

  public record Table(String name, List<Column> columns, PrimaryKey primaryKey, List<Index> indexes, List<ForeignKey> foreignKeys) {
    public Table { columns=List.copyOf(columns); indexes=List.copyOf(indexes); foreignKeys=List.copyOf(foreignKeys); }
    public Column column(String name){return columns.stream().filter(c->c.name().equals(name)).findFirst().orElse(null);}
  }
  public record Column(String name, String sqlType, boolean nullable, Integer length, Integer precision, Integer scale, String defaultValue, boolean unique, boolean identity, String sequenceName, String logicalType) {
    public Column(String name,String sqlType,boolean nullable,Integer length,Integer precision,Integer scale,String defaultValue,boolean unique){this(name,sqlType,nullable,length,precision,scale,defaultValue,unique,false,null,sqlType);}
  }
  public record PrimaryKey(List<String> columns, String constraintName){
    public PrimaryKey { columns = List.copyOf(columns); }
    public PrimaryKey(List<String> columns) { this(columns, null); }
  }
  public record Index(String name, List<String> columns, boolean unique){ public Index{columns=List.copyOf(columns);} }
  public record ForeignKey(String name, List<String> columns, String referencedTable, List<String> referencedColumns){ public ForeignKey{columns=List.copyOf(columns); referencedColumns=List.copyOf(referencedColumns);} }
  public record Sequence(String name, Long initialValue, Long allocationSize){ }
}
