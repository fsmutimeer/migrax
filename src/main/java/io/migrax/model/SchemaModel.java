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

  public static String primaryKeyConstraintName(String tableName) {
    String candidate = tableName + "_pkey";
    if (candidate.length() <= 30) {
      return candidate;
    }
    return candidate.substring(0, 21) + "_"
        + String.format(java.util.Locale.ROOT, "%08x", tableName.hashCode());
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
