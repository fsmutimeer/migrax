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

  /**
   * This model with table and column names spelled as in {@code reference} where they differ
   * only in letter case. Used for a schema read from a database that folds unquoted names
   * (MySQL with lower_case_table_names, PostgreSQL, H2, Oracle): there {@code categories_seq} is
   * the table an entity maps as {@code categories_SEQ}. Names with more than one case-insensitive
   * match are left alone.
   */
  public SchemaModel withNameCaseFrom(SchemaModel reference) {
    Map<String, String> tableNames = new HashMap<>();
    for (Table table : tables) {
      String match = caseMatch(table.name(), tables.stream().map(Table::name).toList(),
          reference.tables().stream().map(Table::name).toList());
      if (match != null) {
        tableNames.put(table.name(), match);
      }
    }
    Map<String, Map<String, String>> columnNames = new HashMap<>();
    for (Table table : tables) {
      Table ref = reference.table(tableNames.getOrDefault(table.name(), table.name()));
      Map<String, String> renamed = new HashMap<>();
      if (ref != null) {
        List<String> own = table.columns().stream().map(Column::name).toList();
        List<String> theirs = ref.columns().stream().map(Column::name).toList();
        for (String column : own) {
          String match = caseMatch(column, own, theirs);
          if (match != null) {
            renamed.put(column, match);
          }
        }
      }
      columnNames.put(table.name(), renamed);
    }
    List<Table> result = new ArrayList<>();
    for (Table table : tables) {
      Map<String, String> cols = columnNames.get(table.name());
      java.util.function.Function<List<String>, List<String>> map =
          names -> names.stream().map(n -> cols.getOrDefault(n, n)).toList();
      List<Column> columns = table.columns().stream()
          .map(c -> !cols.containsKey(c.name()) ? c : new Column(cols.get(c.name()), c.sqlType(),
              c.nullable(), c.length(), c.precision(), c.scale(), c.defaultValue(), c.unique(),
              c.identity(), c.sequenceName(), c.logicalType()))
          .toList();
      PrimaryKey key = table.primaryKey() == null ? null
          : new PrimaryKey(map.apply(table.primaryKey().columns()),
              table.primaryKey().constraintName());
      List<Index> indexes = table.indexes().stream()
          .map(i -> new Index(i.name(), map.apply(i.columns()), i.unique())).toList();
      List<ForeignKey> keys = table.foreignKeys().stream().map(k -> {
        Map<String, String> target = columnNames.getOrDefault(k.referencedTable(), Map.of());
        return new ForeignKey(k.name(), map.apply(k.columns()),
            tableNames.getOrDefault(k.referencedTable(), k.referencedTable()),
            k.referencedColumns().stream().map(n -> target.getOrDefault(n, n)).toList());
      }).toList();
      result.add(new Table(tableNames.getOrDefault(table.name(), table.name()), columns, key,
          indexes, keys));
    }
    return new SchemaModel(result, sequences);
  }

  /**
   * This model, read from a database, with the sequences of {@code entities} that already exist
   * there: as a real sequence (named in {@code existingSequences}, lower case), or as the
   * one-column {@code next_val} table that databases without sequences use (MySQL). Such a table
   * becomes the sequence. Other database sequences are left out on purpose, because many belong
   * to identity columns and must never be dropped.
   */
  public SchemaModel withSequencesFrom(SchemaModel entities, Set<String> existingSequences) {
    List<Table> keptTables = new ArrayList<>(tables);
    List<Sequence> found = new ArrayList<>(sequences);
    for (Sequence sequence : entities.sequences()) {
      if (found.stream().anyMatch(s -> s.name().equalsIgnoreCase(sequence.name()))) {
        continue;
      }
      Table emulated = keptTables.stream()
          .filter(t -> t.name().equalsIgnoreCase(sequence.name()) && t.columns().size() == 1
              && t.columns().get(0).name().equalsIgnoreCase("next_val"))
          .findFirst().orElse(null);
      if (emulated != null) {
        keptTables.remove(emulated);
        found.add(sequence);
      } else if (existingSequences.contains(sequence.name().toLowerCase(Locale.ROOT))) {
        found.add(sequence);
      }
    }
    return new SchemaModel(keptTables, found);
  }

  /**
   * The one name in {@code theirs} equal to {@code name} ignoring case, when {@code theirs} has no
   * exact match and both lists have exactly one such name; otherwise null.
   */
  private static String caseMatch(String name, List<String> own, List<String> theirs) {
    if (theirs.contains(name)) {
      return null;
    }
    List<String> matches = theirs.stream().filter(n -> n.equalsIgnoreCase(name)).toList();
    long ownMatches = own.stream().filter(n -> n.equalsIgnoreCase(name)).count();
    return matches.size() == 1 && ownMatches == 1 ? matches.get(0) : null;
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
    return withCompatibleTypesFrom(previous, (existing, wanted) -> false);
  }

  /**
   * Like {@link #withCompatibleTypesFrom(SchemaModel)}, and also keeps the existing type where
   * {@code sameSqlType} says both columns get the same SQL type in the target database, for
   * example {@code Instant} and {@code LocalDateTime} are both {@code datetime(6)} on MySQL.
   */
  public SchemaModel withCompatibleTypesFrom(
      SchemaModel previous, java.util.function.BiPredicate<Column, Column> sameSqlType) {
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
        columns.add(existing != null
            && (compatibleType(existing, column) || sameSqlType.test(existing, column))
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
