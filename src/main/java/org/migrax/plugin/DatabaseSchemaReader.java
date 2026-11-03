package org.migrax.plugin;

import org.migrax.model.SchemaModel;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.dialect.SchemaCatalog;
import org.migrax.util.Log;

public final class DatabaseSchemaReader {

  private DatabaseSchemaReader() {}

  public static SchemaModel read(Connection connection) throws SQLException {
    return read(connection, null);
  }

  public static SchemaModel read(Connection connection, String explicitSchema) throws SQLException {
    return read(connection, explicitSchema, false);
  }

  /**
   * Reads the schema; with {@code exact}, as the database has it, for comparing it with another
   * database read the same way (a schema file and its baseline): the columns' defaults are read
   * in a form that can be written back as SQL, and MySQL's timestamp and datetime columns keep
   * their exact type. Otherwise defaults are left out and types are the general ones entities
   * declare: databases report defaults in their own form, which never matches the entities'.
   *
   * @since 0.17.0
   */
  public static SchemaModel read(Connection connection, String explicitSchema,
                                 boolean exact) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    SchemaCatalog schemaCatalog = schemaCatalog(connection);
    String catalog = schemaCatalog.catalogFor(connection, explicitSchema);
    String schema = resolveSchema(connection, metadata, explicitSchema, () -> schemaCatalog);
    Log.debug("Reading database schema metadata for catalog '{}', schema '{}'", catalog, schema);
    List<SchemaModel.Table> tables = new ArrayList<>();
    List<String> names = new ArrayList<>();
    try (ResultSet result = metadata.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
      while (result.next()) {
        String name = result.getString("TABLE_NAME");
        String lower = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        if (name == null || lower.startsWith("migrax_") || lower.equals("flyway_schema_history")
            || lower.equals("databasechangelog") || lower.equals("databasechangeloglock")
            // SQL Server's master database reports these system tables as user tables.
            || lower.startsWith("spt_") || lower.equals("msreplication_options")
            // SQLite's own tables (row id counters, statistics).
            || lower.startsWith("sqlite_")) {
          continue;
        }
        names.add(name);
      }
    }
    for (String name : names) {
      if (!isHibernateHelperTable(name, names)) {
        tables.add(readTable(metadata, schemaCatalog, catalog, schema, name, exact));
      }
    }
    tables = withChecksAndComments(connection, schemaCatalog, catalog, schema, tables);
    boolean upperCase = metadata.storesUpperCaseIdentifiers();
    List<SchemaModel.Table> normalized = new ArrayList<>();
    for (SchemaModel.Table table : tables) {
      normalized.add(foldUniqueIndexes(upperCase ? lowerCaseNames(table) : table));
    }
    normalized.sort(Comparator.comparing(SchemaModel.Table::name));
    return new SchemaModel(normalized);
  }

  /**
   * H2 and Oracle store unquoted names in upper case. Entity names are compared in lower case,
   * so upper-case names are lowercased; names that were created quoted in mixed or lower case
   * keep their spelling.
   */
  private static SchemaModel.Table lowerCaseNames(SchemaModel.Table table) {
    List<SchemaModel.Column> columns = table.columns().stream()
        .map(c -> new SchemaModel.Column(fold(c.name()), c.sqlType(), c.nullable(), c.length(),
            c.precision(), c.scale(), c.defaultValue(), c.unique(), c.identity(),
            fold(c.sequenceName()), c.logicalType(), c.comment()))
        .toList();
    SchemaModel.PrimaryKey key = table.primaryKey() == null ? null
        : new SchemaModel.PrimaryKey(fold(table.primaryKey().columns()),
            fold(table.primaryKey().constraintName()));
    List<SchemaModel.Index> indexes = table.indexes().stream()
        .map(i -> new SchemaModel.Index(fold(i.name()), fold(i.columns()), i.unique()))
        .toList();
    List<SchemaModel.ForeignKey> keys = table.foreignKeys().stream()
        .map(k -> new SchemaModel.ForeignKey(fold(k.name()), fold(k.columns()),
            fold(k.referencedTable()), fold(k.referencedColumns())))
        .toList();
    List<SchemaModel.Check> checks = table.checks().stream()
        .map(c -> new SchemaModel.Check(fold(c.name()), c.expression())).toList();
    return new SchemaModel.Table(fold(table.name()), columns, key, indexes, keys, checks,
        table.comment());
  }

  private static String fold(String name) {
    return name != null && name.equals(name.toUpperCase(java.util.Locale.ROOT))
        ? name.toLowerCase(java.util.Locale.ROOT)
        : name;
  }

  private static List<String> fold(List<String> names) {
    return names.stream().map(DatabaseSchemaReader::fold).toList();
  }

  /**
   * A single-column unique index or constraint is how a unique column looks in the database.
   * Recording it as the column's unique flag makes the baseline match the entity model.
   */
  private static SchemaModel.Table foldUniqueIndexes(SchemaModel.Table table) {
    List<SchemaModel.Index> remaining = new ArrayList<>();
    java.util.Set<String> uniqueColumns = new java.util.HashSet<>();
    for (SchemaModel.Index index : table.indexes()) {
      if (index.unique() && index.columns().size() == 1
          && table.column(index.columns().get(0)) != null) {
        uniqueColumns.add(index.columns().get(0));
      } else {
        remaining.add(index);
      }
    }
    if (uniqueColumns.isEmpty()) {
      return table;
    }
    List<SchemaModel.Column> columns = table.columns().stream()
        .map(c -> uniqueColumns.contains(c.name())
            ? new SchemaModel.Column(c.name(), c.sqlType(), c.nullable(), c.length(),
                c.precision(), c.scale(), c.defaultValue(), true, c.identity(),
                c.sequenceName(), c.logicalType(), c.comment())
            : c)
        .toList();
    return new SchemaModel.Table(table.name(), columns, table.primaryKey(), remaining,
        table.foreignKeys(), table.checks(), table.comment());
  }

  /**
   * The connection's dialect's catalog; the standard JDBC one for an unknown database, or when
   * the dialect can't be told (telling some apart asks the server for its version). The reading
   * then goes on without checks and comments rather than failing.
   */
  private static SchemaCatalog schemaCatalog(Connection connection) {
    try {
      return Dialects.forConnection(connection).map(Dialect::schemaCatalog)
          .orElseGet(SchemaCatalog::new);
    } catch (SQLException e) {
      Log.debug("Could not tell the database's dialect: {}", e.getMessage());
      return new SchemaCatalog();
    }
  }

  /** The catalog is only looked up when neither the caller nor the connection names a schema. */
  private static String resolveSchema(Connection connection, DatabaseMetaData metadata,
                                      String explicitSchema, Supplier<SchemaCatalog> catalog) {
    if (explicitSchema != null && !explicitSchema.isBlank()) {
      return storedSchemaName(metadata, explicitSchema.trim());
    }
    String schema = null;
    try {
      schema = connection.getSchema();
    } catch (Throwable ignored) {}
    if (schema != null && !schema.isBlank()) {
      return schema;
    }
    try {
      return catalog.get().defaultSchema(metadata);
    } catch (Throwable ignored) {}
    return null;
  }

  /**
   * The schema's name as the database stores it. A schema created without quotes is stored in
   * upper case on H2, Derby, HSQLDB and Oracle, so {@code app} is found as {@code APP}. On any
   * database, an exact match wins, then the one schema whose name differs only in case; a name
   * that matches several schemas when case is ignored, or none, stays as it is.
   */
  private static String storedSchemaName(DatabaseMetaData metadata, String name) {
    List<String> sameIgnoringCase = new ArrayList<>();
    try (ResultSet schemas = metadata.getSchemas()) {
      while (schemas.next()) {
        String stored = schemas.getString("TABLE_SCHEM");
        if (name.equals(stored)) {
          return name;
        }
        if (name.equalsIgnoreCase(stored)) {
          sameIgnoringCase.add(stored);
        }
      }
    } catch (SQLException | RuntimeException e) {
      return name;
    }
    return sameIgnoringCase.size() == 1 ? sameIgnoringCase.get(0) : name;
  }

  // ------------------------------------------------------------------ checks and comments

  /** The tables with the check constraints and comments the database has. */
  private static List<SchemaModel.Table> withChecksAndComments(Connection connection,
      SchemaCatalog schemaCatalog, String catalog, String schema, List<SchemaModel.Table> tables)
      throws SQLException {
    SchemaCatalog.Details details = new SchemaCatalog.Details();
    schemaCatalog.readChecksAndComments(connection, catalog, schema,
        tables.stream().map(SchemaModel.Table::name).toList(), details);
    List<SchemaModel.Table> result = new ArrayList<>();
    for (SchemaModel.Table table : tables) {
      List<SchemaModel.Column> columns = table.columns().stream()
          .map(c -> new SchemaModel.Column(c.name(), c.sqlType(), c.nullable(), c.length(),
              c.precision(), c.scale(), c.defaultValue(), c.unique(), c.identity(),
              c.sequenceName(), c.logicalType(), details.comment(table.name(), c.name())))
          .toList();
      result.add(new SchemaModel.Table(table.name(), columns, table.primaryKey(),
          table.indexes(), table.foreignKeys(), details.checks(table.name()),
          details.comment(table.name(), null)));
    }
    return result;
  }

  /** Defaults that hand out ids: sequences (Oracle: "APP"."ISEQ$$_7".nextval), row ids. */
  private static final Pattern GENERATED_DEFAULT = Pattern.compile(
      "(?is).*(nextval\\(|\\.nextval\\b|next value for|unique_rowid\\().*");
  /** MySQL's and MariaDB's unsigned integer types, as their drivers name them. */
  private static final Pattern UNSIGNED_INTEGER = Pattern.compile(
      "(tiny|small|medium|big)?int(eger)? unsigned");

  /**
   * A column default as SQL that can be written into a migration, or null when there is none or
   * it belongs to an identity or sequence.
   */
  static String columnDefault(SchemaCatalog schemaCatalog, String logicalType, String raw) {
    if (raw == null || raw.isBlank() || GENERATED_DEFAULT.matcher(raw).matches()) {
      return null;
    }
    String value = schemaCatalog.columnDefault(logicalType, raw.strip());
    return value.isEmpty() || "NULL".equalsIgnoreCase(value) ? null : value;
  }

  private static SchemaModel.Table readTable(DatabaseMetaData metadata,
      SchemaCatalog schemaCatalog, String catalog, String schema, String table, boolean exact)
      throws SQLException {
    List<SchemaModel.Column> columns = new ArrayList<>();
    List<String> declaredInteger = new ArrayList<>();
    try (ResultSet result = metadata.getColumns(catalog, schema, table, "%")) {
      while (result.next()) {
        // Oracle returns the default as a LONG, readable only before any later column.
        String rawDefault = optionalString(result, "COLUMN_DEF");
        String databaseType = result.getString("TYPE_NAME");
        String logicalType = logicalType(databaseType, result.getInt("DATA_TYPE"));
        Integer size = result.getInt("COLUMN_SIZE");
        if (result.wasNull()) {
          size = null;
        }
        int scaleValue = result.getInt("DECIMAL_DIGITS");
        boolean noScale = result.wasNull() || scaleValue == 0;
        // Oracle reports every integer type as NUMBER(p,0).
        if ("decimal".equals(logicalType) && "number".equalsIgnoreCase(databaseType)
            && noScale && size != null) {
          logicalType = size == 1 ? "boolean" : size <= 10 ? "integer"
              : size <= 19 ? "bigint" : logicalType;
        }
        String typeLower = databaseType == null ? "" : databaseType.toLowerCase(java.util.Locale.ROOT);
        // SQL Server's varchar(max), nvarchar(max) and varbinary(max) report a length of about 2^30
        // or 2^31: they are the large-object types, which entities map as @Lob or text.
        if (size != null && size >= 1_073_741_823 && (typeLower.equals("varchar")
            || typeLower.equals("nvarchar") || typeLower.equals("varbinary"))) {
          logicalType = typeLower.equals("varbinary") ? "blob" : "text";
          size = null;
        }
        // MySQL has no UUID type: Hibernate stores UUIDs as binary(16).
        if (typeLower.equals("binary") && size != null && size == 16) {
          logicalType = "uuid";
        }
        boolean textual = "varchar".equals(logicalType) || "nvarchar".equals(logicalType)
            || "varbinary".equals(logicalType);
        Integer length = textual && size != null && size != 255 ? size : null;
        Integer precision = "decimal".equals(logicalType) ? size : null;
        Integer scale = "decimal".equals(logicalType) && !result.wasNull() ? scaleValue : null;
        if (schemaCatalog.precisionIncludesScale() && precision != null && scale != null) {
          precision = precision - scale;
        }
        if (schemaCatalog.integerPrimaryKeyIsRowId() && "integer".equalsIgnoreCase(databaseType)) {
          declaredInteger.add(result.getString("COLUMN_NAME"));
        }
        // Hibernate's default BigDecimal column is numeric(38,2), which entities leave unsized.
        if (precision != null && precision == 38 && scale != null && scale == 2) {
          precision = null;
          scale = null;
        }
        // Databases report defaults in their own normalized form (for example
        // 'x'::character varying), which would never match entity defaults; leave them out.
        String autoIncrement = optionalString(result, "IS_AUTOINCREMENT");
        String defaultValue = exact && !"YES".equalsIgnoreCase(autoIncrement)
            ? columnDefault(schemaCatalog, logicalType, rawDefault)
            : null;
        if (isHiddenRowId(result.getString("COLUMN_NAME"), rawDefault)) {
          continue;
        }
        // A fixed-length binary(n) is not a varbinary: keep its real type, which is what
        // Hibernate writes for byte arrays and serialized values on some databases.
        String sqlType = typeLower.equals("binary") && "varbinary".equals(logicalType)
            && size != null ? "binary(" + size + ")" : logicalType;
        if (exact && (typeLower.equals("timestamp") || typeLower.equals("datetime"))) {
          String exactType = schemaCatalog.exactDateTimeType(typeLower,
              noScale ? null : scaleValue, size);
          if (exactType != null) {
            sqlType = exactType;
          }
        }
        // MySQL's unsigned integers hold other values than the signed ones: read exactly, they
        // keep their type.
        if (exact && UNSIGNED_INTEGER.matcher(typeLower).matches()) {
          sqlType = typeLower;
        }
        columns.add(new SchemaModel.Column(
            result.getString("COLUMN_NAME"),
            sqlType,
            result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
            length,
            precision,
            scale,
            defaultValue,
            false,
            "YES".equalsIgnoreCase(autoIncrement),
            defaultSequence(rawDefault),
            logicalType));
      }
    }

    SchemaModel.PrimaryKey key = readPrimaryKey(metadata, catalog, schema, table);
    // Without its columns (CockroachDB's hidden row id, left out above) there is no key.
    SchemaModel.PrimaryKey primaryKey = key != null && key.columns().stream().allMatch(
        name -> columns.stream().anyMatch(c -> c.name().equalsIgnoreCase(name))) ? key : null;
    if (schemaCatalog.integerPrimaryKeyIsRowId() && primaryKey != null
        && primaryKey.columns().size() == 1
        && declaredInteger.stream().anyMatch(c -> c.equalsIgnoreCase(primaryKey.columns().get(0)))) {
      List<SchemaModel.Column> withRowId = rowIdAsIdentity(columns, primaryKey.columns().get(0));
      columns.clear();
      columns.addAll(withRowId);
    }
    List<SchemaModel.ForeignKey> foreignKeys =
        readForeignKeys(metadata, schemaCatalog, catalog, schema, table);
    List<SchemaModel.Index> indexes =
        readIndexes(metadata, schemaCatalog, catalog, schema, table).stream()
        .filter(index -> !backsForeignKey(index, foreignKeys))
        .toList();
    return new SchemaModel.Table(table, columns, primaryKey, indexes, foreignKeys);
  }

  /**
   * In SQLite a primary key declared exactly {@code integer} is the row id: it fills itself like
   * an identity column, although the driver doesn't report it as auto-increment.
   */
  private static List<SchemaModel.Column> rowIdAsIdentity(List<SchemaModel.Column> columns,
                                                          String key) {
    return columns.stream().map(c -> !c.name().equalsIgnoreCase(key) ? c
        : new SchemaModel.Column(c.name(), c.sqlType(), c.nullable(), c.length(), c.precision(),
            c.scale(), c.defaultValue(), c.unique(), true, c.sequenceName(), c.logicalType()))
        .toList();
  }

  /**
   * Hibernate's own helper tables for bulk updates and deletes, {@code HTE_<table>} (Hibernate 6
   * and 7) or {@code HT_<table>} (Hibernate 5), which it creates at startup on some databases
   * such as CockroachDB. Only names whose table also exists count, so user tables stay.
   */
  private static boolean isHibernateHelperTable(String name, List<String> names) {
    String lower = name.toLowerCase(Locale.ROOT);
    String rest = lower.startsWith("hte_") ? lower.substring(4)
        : lower.startsWith("ht_") ? lower.substring(3) : null;
    return rest != null && names.stream().anyMatch(other -> other.equalsIgnoreCase(rest));
  }

  /**
   * CockroachDB gives a table without a primary key a hidden {@code rowid} column, filled by
   * {@code unique_rowid()}, as its primary key. Entities never declare it.
   */
  private static boolean isHiddenRowId(String column, String defaultValue) {
    return "rowid".equalsIgnoreCase(column) && defaultValue != null
        && defaultValue.toLowerCase(Locale.ROOT).contains("unique_rowid()");
  }

  /**
   * MySQL and MariaDB create an index for every foreign key that has none, named like the key;
   * H2 does the same, naming it like the key plus {@code _INDEX_} and a number. It belongs to
   * the foreign key, not to the entity model, so it is not reported as an index (otherwise the
   * first generate, which compares with the database, would drop it).
   */
  private static boolean backsForeignKey(SchemaModel.Index index,
                                         List<SchemaModel.ForeignKey> foreignKeys) {
    return !index.unique() && foreignKeys.stream().anyMatch(key ->
        key.columns().equals(index.columns()) && (key.name().equalsIgnoreCase(index.name())
            || index.name().toLowerCase(Locale.ROOT).matches(
                Pattern.quote(key.name().toLowerCase(Locale.ROOT)) + "_index_[0-9a-f]+")));
  }

  /**
   * Every object of a type ({@code TABLE} or {@code VIEW}) in the schema, Migrax's own tables
   * included, as the database spells the names. SQLite's internal tables and Oracle's recycle
   * bin are left out.
   *
   * @param schema the schema, or null for the connection's default
   * @since 0.2.0
   */
  public static List<String> objectNames(Connection connection, String schema, String type)
      throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    List<String> names = new ArrayList<>();
    // Only a named schema needs the dialect; looking it up can cost a query.
    String catalog = schema == null || schema.isBlank() ? connection.getCatalog()
        : schemaCatalog(connection).catalogFor(connection, schema);
    try (ResultSet result = metadata.getTables(catalog,
        resolveSchema(connection, metadata, schema, () -> schemaCatalog(connection)), "%",
        new String[]{type})) {
      while (result.next()) {
        String name = result.getString("TABLE_NAME");
        if (name != null && !name.toLowerCase(Locale.ROOT).startsWith("sqlite_")
            && !name.startsWith("BIN$")) {
          names.add(name);
        }
      }
    }
    return names;
  }

  /**
   * The sequences of one schema, as the database spells the names; empty when the database has
   * none. The first listing the database understands answers: the standard view (PostgreSQL,
   * CockroachDB, H2, SQL Server); MariaDB's, in the schema's database; Oracle's, for the
   * schema's owner, or the login's own when the owner list can't be read; Derby's.
   *
   * @param schema the schema, or null for the connection's default
   * @since 0.2.0
   */
  public static List<String> sequencesIn(Connection connection, String schema)
      throws SQLException {
    String resolved = resolveSchema(connection, connection.getMetaData(), schema,
        () -> schemaCatalog(connection));
    String database = schema == null || schema.isBlank() ? connection.getCatalog()
        : schemaCatalog(connection).catalogFor(connection, schema);
    for (String[] listing : List.of(
        new String[] {"SELECT sequence_name FROM information_schema.sequences WHERE "
            + "UPPER(sequence_schema) = UPPER(?)", resolved},
        new String[] {"SELECT table_name FROM information_schema.tables WHERE table_type = "
            + "'SEQUENCE' AND table_schema = ?", database},
        new String[] {"SELECT sequence_name FROM all_sequences WHERE sequence_owner = ? AND "
            + "sequence_name NOT LIKE 'ISEQ$$%'", resolved},
        // Only reached on Oracle when all_sequences can't be read.
        new String[] {"SELECT sequence_name FROM user_sequences WHERE sequence_name NOT LIKE "
            + "'ISEQ$$%'", null},
        new String[] {"SELECT q.SEQUENCENAME FROM SYS.SYSSEQUENCES q JOIN SYS.SYSSCHEMAS s ON "
            + "s.SCHEMAID = q.SCHEMAID WHERE UPPER(s.SCHEMANAME) = UPPER(?)", resolved})) {
      String sql = listing[0];
      String parameter = listing[1];
      if (sql.contains("?") && parameter == null) {
        continue;
      }
      List<String> names = new ArrayList<>();
      try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
        if (sql.contains("?")) {
          statement.setString(1, parameter);
        }
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            names.add(result.getString(1));
          }
        }
        if (names.isEmpty() && schema != null && !schema.isBlank()) {
          // A login without rights on another schema sees none of its sequences either.
          Log.debug("No sequences visible in schema '{}' ({})", resolved, sql);
        }
        return names;
      } catch (SQLException e) {
        Log.debug("Sequence listing not available ({}): {}", sql, e.getMessage());
        if (!connection.getAutoCommit()) {
          connection.rollback();
        }
      }
    }
    return List.of();
  }

  /**
   * Names of the sequences in the database, in lower case; empty when the database has none or
   * they can't be listed. Tries the standard view (PostgreSQL, H2, SQL Server), then MariaDB's and
   * Oracle's own listings.
   */
  public static java.util.Set<String> sequenceNames(Connection connection) {
    java.util.Set<String> names = new java.util.HashSet<>();
    for (String sql : List.of(
        "SELECT sequence_name FROM information_schema.sequences",
        "SELECT table_name FROM information_schema.tables WHERE table_type = 'SEQUENCE' "
            + "AND table_schema = DATABASE()",
        "SELECT sequence_name FROM user_sequences",
        "SELECT SEQUENCENAME FROM SYS.SYSSEQUENCES")) {
      try (java.sql.Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery(sql)) {
        while (result.next()) {
          String name = result.getString(1);
          if (name != null) {
            names.add(name.toLowerCase(java.util.Locale.ROOT));
          }
        }
      } catch (SQLException e) {
        // This database doesn't have that listing; try the next one.
        Log.debug("Sequence listing not available ({}): {}", sql, e.getMessage());
      }
    }
    return names;
  }

  /**
   * True when the database treats unquoted table names case-insensitively, by storing them in
   * one case: MySQL with lower_case_table_names set (the Windows and macOS default), PostgreSQL,
   * H2 and Oracle.
   */
  public static boolean foldsNames(Connection connection) {
    try {
      DatabaseMetaData metadata = connection.getMetaData();
      return metadata.storesLowerCaseIdentifiers() || metadata.storesUpperCaseIdentifiers();
    } catch (SQLException e) {
      return false;
    }
  }

  private static SchemaModel.PrimaryKey readPrimaryKey(
      DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
    Map<Short, String> columns = new LinkedHashMap<>();
    String constraintName = null;
    try (ResultSet result = metadata.getPrimaryKeys(catalog, schema, table)) {
      while (result.next()) {
        columns.put(result.getShort("KEY_SEQ"), result.getString("COLUMN_NAME"));
        if (constraintName == null) {
          constraintName = result.getString("PK_NAME");
        }
      }
    }
    if (columns.isEmpty()) {
      return null;
    }
    // MySQL, MariaDB and TiDB call every primary key PRIMARY, a name no statement may use.
    return new SchemaModel.PrimaryKey(columns.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .toList(), "PRIMARY".equals(constraintName) ? null : constraintName);
  }

  private static List<SchemaModel.Index> readIndexes(DatabaseMetaData metadata,
      SchemaCatalog schemaCatalog, String catalog, String schema, String table)
      throws SQLException {
    Map<String, List<IndexedColumn>> grouped = new LinkedHashMap<>();
    Map<String, Boolean> unique = new LinkedHashMap<>();
    List<String> primaryKeyIndexes = new ArrayList<>();
    Map<Short, String> primaryKeyColumns = new LinkedHashMap<>();
    try (ResultSet primaryKeys = metadata.getPrimaryKeys(catalog, schema, table)) {
      while (primaryKeys.next()) {
        String primaryKeyName = primaryKeys.getString("PK_NAME");
        if (primaryKeyName != null) {
          primaryKeyIndexes.add(primaryKeyName);
        }
        primaryKeyColumns.put(primaryKeys.getShort("KEY_SEQ"), primaryKeys.getString("COLUMN_NAME"));
      }
    }
    List<String> orderedPrimaryKey = primaryKeyColumns.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(Map.Entry::getValue)
        .toList();
    java.util.Set<String> uniqueKeys =
        schemaCatalog.uniqueConstraintIndexes(metadata.getConnection(), schema, table);
    java.util.Set<String> foreignKeyIndexes =
        schemaCatalog.foreignKeyIndexes(metadata.getConnection(), schema, table);
    // approximate=true: only names and columns are read, so the database needn't compute
    // statistics (Oracle's driver gathers them, and fails on a quoted mixed-case table).
    try (ResultSet result = metadata.getIndexInfo(catalog, schema, table, false, true)) {
      while (result.next()) {
        String name = result.getString("INDEX_NAME");
        String column = result.getString("COLUMN_NAME");
        if (name == null || column == null || primaryKeyIndexes.contains(name)
            || foreignKeyIndexes.contains(name)) {
          continue;
        }
        grouped.computeIfAbsent(name, ignored -> new ArrayList<>())
            .add(new IndexedColumn(result.getShort("ORDINAL_POSITION"), column));
        unique.put(name, !result.getBoolean("NON_UNIQUE") || uniqueKeys.contains(name));
      }
    }
    List<SchemaModel.Index> indexes = new ArrayList<>();
    for (var entry : grouped.entrySet()) {
      List<String> columns = entry.getValue().stream()
          .sorted(Comparator.comparingInt(IndexedColumn::position))
          .map(IndexedColumn::name)
          .toList();
      if (unique.getOrDefault(entry.getKey(), false) && columns.equals(orderedPrimaryKey)) {
        continue;
      }
      indexes.add(new SchemaModel.Index(entry.getKey(),
          columns,
          unique.getOrDefault(entry.getKey(), false)));
    }
    return indexes;
  }

  private static List<SchemaModel.ForeignKey> readForeignKeys(DatabaseMetaData metadata,
      SchemaCatalog schemaCatalog, String catalog, String schema, String table)
      throws SQLException {
    Map<String, List<ForeignKeyColumn>> grouped = new LinkedHashMap<>();
    Map<String, String> targetTables = new LinkedHashMap<>();
    try (ResultSet result = schemaCatalog.importedKeys(metadata, catalog, schema, table)) {
      while (result.next()) {
        String name = result.getString("FK_NAME");
        if (name == null) {
          name = "fk_" + table + "_" + result.getString("FKCOLUMN_NAME");
        }
        String fkName = name;
        grouped.computeIfAbsent(fkName, ignored -> new ArrayList<>()).add(
            new ForeignKeyColumn(result.getShort("KEY_SEQ"),
                result.getString("FKCOLUMN_NAME"), result.getString("PKCOLUMN_NAME")));
        targetTables.put(fkName, result.getString("PKTABLE_NAME"));
      }
    }
    List<SchemaModel.ForeignKey> keys = new ArrayList<>();
    for (var entry : grouped.entrySet()) {
      List<ForeignKeyColumn> columns = entry.getValue().stream()
          .sorted(Comparator.comparingInt(ForeignKeyColumn::position))
          .toList();
      keys.add(new SchemaModel.ForeignKey(
          entry.getKey(),
          columns.stream().map(ForeignKeyColumn::name).toList(),
          targetTables.get(entry.getKey()),
          columns.stream().map(ForeignKeyColumn::referencedName).toList()));
    }
    return keys;
  }

  private static String logicalType(String typeName, int jdbcType) {
    if (typeName != null) {
      String type = typeName.toLowerCase(java.util.Locale.ROOT);
      if (type.contains("json")) return "json";
      // H2 names its LOB types BINARY LARGE OBJECT and CHARACTER LARGE OBJECT.
      if (type.contains("large object")) return type.contains("char") ? "clob" : "blob";
      if (type.contains("uuid") || type.equals("uniqueidentifier")) return "uuid";
      if (type.contains("time zone") || type.contains("datetimeoffset")
          || type.equals("timestamptz")) return "timestamptz";
      if (type.contains("clob")) return "clob";
      // Derby's binary strings: VARCHAR () FOR BIT DATA.
      if (type.contains("for bit data")) return "varbinary";
      if (type.contains("text")) return "text";
      if (type.equals("oid")) return "blob";
      if (type.startsWith("datetime")) return "timestamp";
      if (type.startsWith("nvarchar") || type.startsWith("nchar")) return "nvarchar";
      if (type.contains("char") || type.equals("enum")) return "varchar";
      // PostgreSQL reports int8/int4/int2 and bigserial; the JDBC type settles the size.
      if (type.contains("int") || type.contains("serial")) {
        return jdbcType == java.sql.Types.BIGINT || type.contains("big") || type.equals("int8")
            ? "bigint" : "integer";
      }
      if (type.equals("float8")) return "double";
      if (type.contains("decimal") || type.contains("numeric")) return "decimal";
      if (type.contains("bool") || type.equals("bit")) return "boolean";
      if (type.contains("timestamp") || type.equals("datetime")) return "timestamp";
      if (type.equals("date")) return "date";
      if (type.equals("time")) return "time";
      if (type.contains("double")) return "double";
      if (type.contains("float") || type.contains("real")) return "float";
      if (type.contains("blob")) return "blob";
      if (type.contains("binary") || type.equals("bytea") || type.equals("raw")) return "varbinary";
      if (type.contains("uuid")) return "uuid";
    }
    return switch (jdbcType) {
      case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER -> "integer";
      case java.sql.Types.BIGINT -> "bigint";
      case java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> "decimal";
      case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> "boolean";
      case java.sql.Types.DATE -> "date";
      case java.sql.Types.TIME, java.sql.Types.TIME_WITH_TIMEZONE -> "time";
      case java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> "timestamp";
      case java.sql.Types.DOUBLE -> "double";
      case java.sql.Types.FLOAT, java.sql.Types.REAL -> "float";
      case java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY ->
          "varbinary";
      case java.sql.Types.BLOB -> "blob";
      case java.sql.Types.CLOB, java.sql.Types.NCLOB -> "clob";
      default -> "varchar";
    };
  }

  /**
   * The sequence a column default takes values from, such as {@code customer_seq} in
   * PostgreSQL's {@code nextval('customer_seq'::regclass)}; null for other defaults.
   */
  static String defaultSequence(String columnDefault) {
    if (columnDefault == null) {
      return null;
    }
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?i)^nextval\\('([^']+)'(?:::regclass)?\\)$")
        .matcher(columnDefault.trim());
    if (!matcher.matches()) {
      return null;
    }
    // Possibly schema-qualified and quoted: public."Orders_SEQ"
    String name = matcher.group(1);
    return name.substring(name.lastIndexOf('.') + 1).replace("\"", "");
  }

  private static String optionalString(ResultSet result, String column) {
    try {
      return result.getString(column);
    } catch (SQLException ignored) {
      return null;
    }
  }

  private record IndexedColumn(short position, String name) {}

  private record ForeignKeyColumn(short position, String name, String referencedName) {}
}
