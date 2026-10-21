package org.migrax.plugin;

import org.migrax.model.SchemaModel;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
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
    String catalog = connection.getCatalog();
    String schema = resolveSchema(connection, metadata, explicitSchema);
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
        tables.add(readTable(metadata, catalog, schema, name, exact));
      }
    }
    tables = withChecksAndComments(connection, catalog, schema, tables);
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

  private static String resolveSchema(
      Connection connection, DatabaseMetaData metadata, String explicitSchema) {
    if (explicitSchema != null && !explicitSchema.isBlank()) {
      return explicitSchema.trim();
    }
    String schema = null;
    try {
      schema = connection.getSchema();
    } catch (Throwable ignored) {}
    if (schema != null && !schema.isBlank()) {
      return schema;
    }
    try {
      String productName = metadata.getDatabaseProductName();
      if (productName != null) {
        String lower = productName.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("oracle")) {
          String user = metadata.getUserName();
          if (user != null && !user.isBlank()) {
            return user.toUpperCase(java.util.Locale.ROOT);
          }
        } else if (lower.contains("microsoft") || lower.contains("sql server")) {
          return "dbo";
        } else if (lower.contains("postgres")) {
          return "public";
        }
      }
    } catch (Throwable ignored) {}
    return null;
  }

  // ------------------------------------------------------------------ checks and comments

  /** The schema and table HyperSQL puts before each column in a check: {@code PUBLIC.T.}. */
  private static final Pattern HSQLDB_QUALIFIER = Pattern.compile(
      "\"?[A-Za-z_][A-Za-z0-9_$]*\"?\\.\"?[A-Za-z_][A-Za-z0-9_$]*\"?\\.(?=[\"A-Za-z_])");

  /** A check in TiDB's SHOW CREATE TABLE: {@code CONSTRAINT `name` CHECK ((condition))}. */
  private static final Pattern TIDB_CHECK = Pattern.compile(
      "(?m)CONSTRAINT `([^`]+)` CHECK \\((.*)\\)(?: /\\*.*\\*/)?(?: NOT ENFORCED)?,?$");
  private static final Pattern SQLITE_CHECK = Pattern.compile(
      "(?i)CONSTRAINT\\s+[\"`\\[]?([A-Za-z0-9_$]+)[\"`\\]]?\\s+CHECK\\s*\\(");
  private static final Pattern SQLITE_UNNAMED_CHECK = Pattern.compile("(?i)\\bCHECK\\s*\\(");

  /**
   * The tables with the check constraints and comments the database has. JDBC has no portable
   * way to list either, so each database is asked in its own catalog; a database that can't
   * answer (an older version, missing rights) reports none.
   */
  private static List<SchemaModel.Table> withChecksAndComments(
      Connection connection, String catalog, String schema, List<SchemaModel.Table> tables)
      throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    // table -> checks, table -> comment, table.column -> comment (names as the database has them)
    Map<String, List<SchemaModel.Check>> checks = new LinkedHashMap<>();
    Map<String, String> comments = new LinkedHashMap<>();
    String owner = schema;
    if (product.contains("postgres") || product.contains("cockroach")) {
      String namespace = schema == null ? "current_schema()" : "?";
      for (String[] row : query(connection, "SELECT rel.relname, con.conname, "
          + "pg_get_constraintdef(con.oid) FROM pg_constraint con JOIN pg_class rel ON rel.oid = "
          + "con.conrelid JOIN pg_namespace n ON n.oid = rel.relnamespace WHERE con.contype = 'c' "
          + "AND n.nspname = " + namespace, schema)) {
        addCheck(checks, row[0], row[1], unwrapCheck(row[2]));
      }
      for (String[] row : query(connection, "SELECT c.relname, NULL, obj_description(c.oid, "
          + "'pg_class') FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE "
          + "c.relkind = 'r' AND n.nspname = " + namespace + " UNION ALL SELECT c.relname, "
          + "a.attname, col_description(c.oid, a.attnum) FROM pg_class c JOIN pg_namespace n ON "
          + "n.oid = c.relnamespace JOIN pg_attribute a ON a.attrelid = c.oid WHERE c.relkind = "
          + "'r' AND a.attnum > 0 AND n.nspname = " + namespace, schema, schema)) {
        addComment(comments, row);
      }
    } else if (product.contains("mysql") || product.contains("mariadb")) {
      owner = catalog;
      // TiDB doesn't list checks in TABLE_CONSTRAINTS; its own view has their tables.
      boolean tidb = String.valueOf(connection.getMetaData().getDatabaseProductVersion())
          .contains("TiDB");
      if (tidb && query(connection, "SELECT 1 FROM information_schema.TABLES WHERE "
          + "TABLE_SCHEMA = 'INFORMATION_SCHEMA' AND TABLE_NAME = 'TIDB_CHECK_CONSTRAINTS'")
          .isEmpty()) {
        // Older TiDB versions tell which table a check belongs to only in SHOW CREATE TABLE.
        for (SchemaModel.Table table : tables) {
          for (String[] row : query(connection, "SHOW CREATE TABLE `"
              + table.name().replace("`", "``") + "`")) {
            java.util.regex.Matcher check = TIDB_CHECK.matcher(row.length > 1 ? row[1] : "");
            while (check.find()) {
              addCheck(checks, table.name(), check.group(1), unwrapCheck(check.group(2)));
            }
          }
        }
        tidb = false;
      }
      for (String[] row : query(connection, tidb
          ? "SELECT TABLE_NAME, CONSTRAINT_NAME, CHECK_CLAUSE FROM "
              + "information_schema.TIDB_CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = ?"
          : "SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, "
          + "cc.CHECK_CLAUSE FROM information_schema.TABLE_CONSTRAINTS tc JOIN "
          + "information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA = "
          + "tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME WHERE "
          + "tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = ?", owner)) {
        // MariaDB's json type is longtext with a json_valid() check of its own.
        if (row[2] != null && !row[2].toLowerCase(Locale.ROOT).contains("json_valid(")) {
          addCheck(checks, row[0], row[1], unwrapCheck(unescape(row[2])));
        }
      }
      for (String[] row : query(connection, "SELECT TABLE_NAME, NULL, TABLE_COMMENT FROM "
          + "information_schema.TABLES WHERE TABLE_SCHEMA = ? UNION ALL SELECT TABLE_NAME, "
          + "COLUMN_NAME, COLUMN_COMMENT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = ?",
          owner, owner)) {
        addComment(comments, row);
      }
    } else if (product.contains("sql server")) {
      String namespace = schema == null ? "SCHEMA_NAME()" : "?";
      for (String[] row : query(connection, "SELECT t.name, cc.name, cc.definition FROM "
          + "sys.check_constraints cc JOIN sys.tables t ON t.object_id = cc.parent_object_id "
          + "WHERE SCHEMA_NAME(t.schema_id) = " + namespace, schema)) {
        addCheck(checks, row[0], row[1], unwrapCheck(row[2]));
      }
      for (String[] row : query(connection, "SELECT t.name, c.name, CAST(ep.value AS "
          + "nvarchar(4000)) FROM sys.extended_properties ep JOIN sys.tables t ON t.object_id = "
          + "ep.major_id LEFT JOIN sys.columns c ON c.object_id = ep.major_id AND c.column_id = "
          + "ep.minor_id WHERE ep.class = 1 AND ep.name = 'MS_Description' AND "
          + "SCHEMA_NAME(t.schema_id) = " + namespace, schema)) {
        addComment(comments, row);
      }
    } else if (product.contains("oracle")) {
      for (String[] row : query(connection, "SELECT table_name, constraint_name, "
          + "search_condition_vc FROM all_constraints WHERE constraint_type = 'C' AND NOT "
          + "(generated = 'GENERATED NAME' AND search_condition_vc LIKE '\"%\" IS NOT NULL') "
          + "AND owner = ?", owner)) {
        addCheck(checks, row[0], row[1], row[2]);
      }
      for (String[] row : query(connection, "SELECT table_name, NULL, comments FROM "
          + "all_tab_comments WHERE owner = ? UNION ALL SELECT table_name, column_name, comments "
          + "FROM all_col_comments WHERE owner = ?", owner, owner)) {
        addComment(comments, row);
      }
    } else if (product.contains("h2")) {
      for (String[] row : query(connection, "SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, "
          + "cc.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN "
          + "INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA = "
          + "tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME WHERE "
          + "tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = ?", owner)) {
        addCheck(checks, row[0], row[1], row[2]);
      }
      for (String[] row : query(connection, "SELECT TABLE_NAME, NULL, REMARKS FROM "
          + "INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? UNION ALL SELECT TABLE_NAME, "
          + "COLUMN_NAME, REMARKS FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ?",
          owner, owner)) {
        addComment(comments, row);
      }
    } else if (product.contains("derby")) {
      // Derby keeps no comments.
      for (String[] row : query(connection, "SELECT t.TABLENAME, c.CONSTRAINTNAME, "
          + "k.CHECKDEFINITION FROM SYS.SYSCONSTRAINTS c JOIN SYS.SYSCHECKS k ON "
          + "k.CONSTRAINTID = c.CONSTRAINTID JOIN SYS.SYSTABLES t ON t.TABLEID = c.TABLEID "
          + "JOIN SYS.SYSSCHEMAS s ON s.SCHEMAID = t.SCHEMAID WHERE s.SCHEMANAME = "
          + (owner == null ? "CURRENT SCHEMA" : "?"), owner)) {
        addCheck(checks, row[0], row[1], row[2]);
      }
    } else if (product.contains("hsql")) {
      String namespace = owner == null ? "CURRENT_SCHEMA" : "?";
      for (String[] row : query(connection, "SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, "
          + "cc.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN "
          + "INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA = "
          + "tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME WHERE "
          + "tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = " + namespace, owner)) {
        // NOT NULL columns are checks of their own (SYS_CT_...: COLUMN IS NOT NULL).
        if (row[2] != null && !(row[1].startsWith("SYS_CT_")
            && row[2].toUpperCase(Locale.ROOT).endsWith(" IS NOT NULL"))) {
          // HyperSQL names every column with its schema and table: PUBLIC.CUSTOMER.BALANCE>=0.
          addCheck(checks, row[0], row[1], HSQLDB_QUALIFIER.matcher(row[2]).replaceAll(""));
        }
      }
      for (String[] row : query(connection, "SELECT OBJECT_NAME, COLUMN_NAME, COMMENT FROM "
          + "INFORMATION_SCHEMA.SYSTEM_COMMENTS WHERE OBJECT_TYPE IN ('TABLE', 'COLUMN') AND "
          + "OBJECT_SCHEMA = " + namespace, owner)) {
        addComment(comments, row);
      }
    } else if (product.contains("sqlite")) {
      // SQLite keeps no comments; its checks are only in the table's CREATE statement.
      for (String[] row : query(connection,
          "SELECT name, NULL, sql FROM sqlite_master WHERE type = 'table'")) {
        if (row[2] == null) {
          continue;
        }
        java.util.Set<Integer> named = new java.util.HashSet<>();
        java.util.regex.Matcher matcher = SQLITE_CHECK.matcher(row[2]);
        while (matcher.find()) {
          named.add(matcher.end() - 1);
          addCheck(checks, row[0], matcher.group(1), parenthesized(row[2], matcher.end() - 1));
        }
        // A check without a name has none in SQLite either; it gets the one Migrax would give.
        java.util.regex.Matcher unnamed = SQLITE_UNNAMED_CHECK.matcher(row[2]);
        while (unnamed.find()) {
          if (!named.contains(unnamed.end() - 1)) {
            String condition = parenthesized(row[2], unnamed.end() - 1).strip();
            addCheck(checks, row[0], SchemaModel.checkConstraintName(row[0], condition),
                condition);
          }
        }
      }
    }
    List<SchemaModel.Table> result = new ArrayList<>();
    for (SchemaModel.Table table : tables) {
      String key = table.name().toLowerCase(Locale.ROOT);
      List<SchemaModel.Column> columns = table.columns().stream()
          .map(c -> new SchemaModel.Column(c.name(), c.sqlType(), c.nullable(), c.length(),
              c.precision(), c.scale(), c.defaultValue(), c.unique(), c.identity(),
              c.sequenceName(), c.logicalType(),
              comments.get(key + "." + c.name().toLowerCase(Locale.ROOT))))
          .toList();
      result.add(new SchemaModel.Table(table.name(), columns, table.primaryKey(),
          table.indexes(), table.foreignKeys(), checks.getOrDefault(key, List.of()),
          comments.get(key)));
    }
    return result;
  }

  private static void addCheck(Map<String, List<SchemaModel.Check>> checks, String table,
                               String name, String expression) {
    if (table != null && name != null) {
      checks.computeIfAbsent(table.toLowerCase(Locale.ROOT), t -> new ArrayList<>())
          .add(new SchemaModel.Check(name, expression == null ? "" : expression.strip()));
    }
  }

  private static void addComment(Map<String, String> comments, String[] row) {
    if (row[0] != null && row[2] != null && !row[2].isBlank()) {
      comments.put(row[0].toLowerCase(Locale.ROOT)
          + (row[1] == null ? "" : "." + row[1].toLowerCase(Locale.ROOT)), row[2]);
    }
  }

  /**
   * A check condition as MySQL reports it, with its quotes escaped
   * ({@code email <> _utf8mb4\'\'}), back as SQL. Other databases report no backslashes there.
   */
  private static String unescape(String condition) {
    if (condition.indexOf('\\') < 0) {
      return condition;
    }
    StringBuilder sql = new StringBuilder(condition.length());
    for (int i = 0; i < condition.length(); i++) {
      char ch = condition.charAt(i);
      if (ch == '\\' && i + 1 < condition.length()) {
        ch = condition.charAt(++i);
      }
      sql.append(ch);
    }
    return sql.toString();
  }

  /** The condition of {@code CHECK (condition)} as databases report it. */
  private static String unwrapCheck(String definition) {
    if (definition == null) {
      return null;
    }
    String text = definition.strip();
    if (text.regionMatches(true, 0, "CHECK", 0, 5)) {
      text = text.substring(5).strip();
    }
    return text;
  }

  /** The text inside the parentheses that open at {@code open}. */
  private static String parenthesized(String sql, int open) {
    int depth = 0;
    for (int i = open; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      if (ch == '(') {
        depth++;
      } else if (ch == ')' && --depth == 0) {
        return sql.substring(open + 1, i);
      }
    }
    return sql.substring(open + 1);
  }

  /** Rows of a catalog query as strings; none when the database can't answer it. */
  private static List<String[]> query(Connection connection, String sql, String... params) {
    List<String[]> rows = new ArrayList<>();
    try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
      int index = 1;
      for (String param : params) {
        if (sql.chars().filter(c -> c == '?').count() >= index) {
          statement.setString(index++, param);
        }
      }
      try (ResultSet result = statement.executeQuery()) {
        int count = result.getMetaData().getColumnCount();
        while (result.next()) {
          String[] row = new String[count];
          for (int i = 0; i < count; i++) {
            row[i] = result.getString(i + 1);
          }
          rows.add(row);
        }
      }
    } catch (SQLException e) {
      Log.debug("Could not read checks or comments: {}", e.getMessage());
      if (!autoCommit(connection)) {
        try {
          connection.rollback();
        } catch (SQLException ignored) {
          // The read still goes on without them.
        }
      }
    }
    return rows;
  }

  /** A PostgreSQL or CockroachDB type cast: ::character varying, :::INT8, ::numeric(10,2). */
  private static final Pattern POSTGRES_CAST = Pattern.compile(
      "(?i):{2,3}\\s*[a-z_][a-z0-9_ ]*(\\(\\s*\\d+(\\s*,\\s*\\d+)?\\s*\\))?(\\[])?");
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
  static String columnDefault(String product, String logicalType, String raw) {
    if (raw == null || raw.isBlank() || GENERATED_DEFAULT.matcher(raw).matches()) {
      return null;
    }
    String value = raw.strip();
    String database = String.valueOf(product).toLowerCase(Locale.ROOT);
    if (database.contains("postgres") || database.contains("cockroach")) {
      value = withoutCasts(value).strip();
    } else if (database.contains("microsoft")) {
      // ('x'), ((0)), (getdate()): SQL Server wraps every default in parentheses.
      while (value.startsWith("(") && value.endsWith(")") && wrapsAll(value)) {
        value = value.substring(1, value.length() - 1).strip();
      }
    } else if (database.contains("mysql") && !value.startsWith("'") && !value.endsWith(")")
        && ("varchar".equals(logicalType) || "nvarchar".equals(logicalType)
            || "char".equals(logicalType) || "text".equals(logicalType))) {
      // MySQL reports a text default without its quotes. A text column's default is a literal
      // unless it is an expression, which ends in a parenthesis: (uuid()).
      value = "'" + value.replace("'", "''") + "'";
    }
    return value.isEmpty() || "NULL".equalsIgnoreCase(value) ? null : value;
  }

  /** The default without type casts, leaving text inside quotes as it is. */
  private static String withoutCasts(String value) {
    // Parts at odd positions are inside quotes; '' inside a literal adds an empty part.
    String[] parts = value.split("'", -1);
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < parts.length; i++) {
      if (i > 0) {
        result.append('\'');
      }
      result.append(i % 2 == 0 ? POSTGRES_CAST.matcher(parts[i]).replaceAll("") : parts[i]);
    }
    return result.toString();
  }

  /** Whether the parentheses at both ends belong together: (a) + (b) is not wrapped. */
  private static boolean wrapsAll(String text) {
    int depth = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      depth += c == '(' ? 1 : c == ')' ? -1 : 0;
      if (depth == 0 && i < text.length() - 1) {
        return false;
      }
    }
    return true;
  }

  private static boolean autoCommit(Connection connection) {
    try {
      return connection.getAutoCommit();
    } catch (SQLException e) {
      return true;
    }
  }

  private static SchemaModel.Table readTable(DatabaseMetaData metadata, String catalog,
      String schema, String table, boolean exact) throws SQLException {
    List<SchemaModel.Column> columns = new ArrayList<>();
    boolean sqlite = String.valueOf(metadata.getDatabaseProductName())
        .toLowerCase(Locale.ROOT).contains("sqlite");
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
        // SQLite's driver reports numeric(10,2) with a size of 12: precision plus scale.
        if (sqlite && precision != null && scale != null) {
          precision = precision - scale;
        }
        if (sqlite && "integer".equalsIgnoreCase(databaseType)) {
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
            ? columnDefault(metadata.getDatabaseProductName(), logicalType,
                rawDefault)
            : null;
        if (isHiddenRowId(result.getString("COLUMN_NAME"), rawDefault)) {
          continue;
        }
        // A fixed-length binary(n) is not a varbinary: keep its real type, which is what
        // Hibernate writes for byte arrays and serialized values on some databases.
        String sqlType = typeLower.equals("binary") && "varbinary".equals(logicalType)
            && size != null ? "binary(" + size + ")" : logicalType;
        // MySQL's timestamp is stored in UTC and its datetime isn't, and CURRENT_TIMESTAMP must
        // have the column's precision: read exactly, they keep their own type, written in full.
        if (exact && (typeLower.equals("timestamp") || typeLower.equals("datetime"))
            && String.valueOf(metadata.getDatabaseProductName()).toLowerCase(Locale.ROOT)
                .matches(".*(mysql|mariadb).*")) {
          // MariaDB's driver leaves the scale out and puts the fraction into the size: 20 + n.
          int fraction = !noScale ? scaleValue : size != null && size > 19 ? size - 20 : 0;
          sqlType = typeLower + "(" + fraction + ")";
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
    if (sqlite && primaryKey != null && primaryKey.columns().size() == 1
        && declaredInteger.stream().anyMatch(c -> c.equalsIgnoreCase(primaryKey.columns().get(0)))) {
      List<SchemaModel.Column> withRowId = rowIdAsIdentity(columns, primaryKey.columns().get(0));
      columns.clear();
      columns.addAll(withRowId);
    }
    List<SchemaModel.ForeignKey> foreignKeys = readForeignKeys(metadata, catalog, schema, table);
    List<SchemaModel.Index> indexes = readIndexes(metadata, catalog, schema, table).stream()
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
    try (ResultSet result = metadata.getTables(connection.getCatalog(),
        resolveSchema(connection, metadata, schema), "%", new String[]{type})) {
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
   * none. Tries the standard view (PostgreSQL, CockroachDB, H2, SQL Server), then MariaDB's
   * and Oracle's.
   *
   * @param schema the schema, or null for the connection's default
   * @since 0.2.0
   */
  public static List<String> sequencesIn(Connection connection, String schema)
      throws SQLException {
    String resolved = resolveSchema(connection, connection.getMetaData(), schema);
    for (String sql : List.of(
        "SELECT sequence_name FROM information_schema.sequences WHERE UPPER(sequence_schema) = "
            + "UPPER(?)",
        "SELECT table_name FROM information_schema.tables WHERE table_type = 'SEQUENCE' "
            + "AND table_schema = DATABASE()",
        "SELECT sequence_name FROM user_sequences WHERE sequence_name NOT LIKE 'ISEQ$$%'",
        "SELECT q.SEQUENCENAME FROM SYS.SYSSEQUENCES q JOIN SYS.SYSSCHEMAS s ON s.SCHEMAID = "
            + "q.SCHEMAID WHERE UPPER(s.SCHEMANAME) = UPPER(?)")) {
      if (sql.contains("?") && resolved == null) {
        continue;
      }
      List<String> names = new ArrayList<>();
      try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
        if (sql.contains("?")) {
          statement.setString(1, resolved);
        }
        try (ResultSet result = statement.executeQuery()) {
          while (result.next()) {
            names.add(result.getString(1));
          }
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

  /**
   * The indexes Derby makes for a table's constraints of a type: {@code U} for unique keys (on
   * a nullable column the constraint does the checking and its index reports itself as not
   * unique), {@code F} for foreign keys (indexes the entities don't declare).
   */
  private static java.util.Set<String> derbyConstraintIndexes(DatabaseMetaData metadata,
                                                              String schema, String table,
                                                              String type)
      throws SQLException {
    if (!String.valueOf(metadata.getDatabaseProductName()).toLowerCase(Locale.ROOT)
        .contains("derby")) {
      return java.util.Set.of();
    }
    java.util.Set<String> names = new java.util.HashSet<>();
    for (String[] row : query(metadata.getConnection(), "SELECT g.CONGLOMERATENAME FROM "
        + "SYS.SYSCONSTRAINTS c JOIN " + (type.equals("F") ? "SYS.SYSFOREIGNKEYS" : "SYS.SYSKEYS")
        + " k ON k.CONSTRAINTID = c.CONSTRAINTID "
        + "JOIN SYS.SYSCONGLOMERATES g ON g.CONGLOMERATEID = k.CONGLOMERATEID "
        + "JOIN SYS.SYSTABLES t ON t.TABLEID = c.TABLEID "
        + "JOIN SYS.SYSSCHEMAS s ON s.SCHEMAID = t.SCHEMAID "
        + "WHERE c.TYPE = '" + type + "' AND t.TABLENAME = ? AND s.SCHEMANAME = "
        + (schema == null ? "CURRENT SCHEMA" : "?"), table, schema)) {
      names.add(row[0]);
    }
    return names;
  }

  private static List<SchemaModel.Index> readIndexes(
      DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
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
    java.util.Set<String> uniqueKeys = derbyConstraintIndexes(metadata, schema, table, "U");
    java.util.Set<String> foreignKeyIndexes =
        derbyConstraintIndexes(metadata, schema, table, "F");
    try (ResultSet result = metadata.getIndexInfo(catalog, schema, table, false, false)) {
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

  /** YugabyteDB answers through the PostgreSQL driver; its version says "-YB-". */
  private static boolean yugabyte(DatabaseMetaData metadata) throws SQLException {
    return String.valueOf(metadata.getDatabaseProductVersion()).contains("-YB-");
  }

  /**
   * A table's foreign keys straight from {@code pg_constraint}, with the columns the driver's
   * {@code getImportedKeys} returns. YugabyteDB sometimes leaves keys on other than the first
   * column out of the driver's query (it joins the key arrays with {@code generate_series}),
   * while {@code pg_constraint} has them.
   */
  private static ResultSet yugabyteForeignKeys(Connection connection, String schema,
                                               String table) throws SQLException {
    PreparedStatement statement = connection.prepareStatement("SELECT c.conname AS fk_name, "
        + "a.attname AS fkcolumn_name, ra.attname AS pkcolumn_name, r.relname AS pktable_name, "
        + "k.n AS key_seq FROM pg_catalog.pg_constraint c "
        + "JOIN pg_catalog.pg_class t ON t.oid = c.conrelid "
        + "JOIN pg_catalog.pg_namespace ns ON ns.oid = t.relnamespace "
        + "JOIN pg_catalog.pg_class r ON r.oid = c.confrelid "
        + "CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS k(fk, pk, n) "
        + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.fk "
        + "JOIN pg_catalog.pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = k.pk "
        + "WHERE c.contype = 'f' AND t.relname = ? "
        + "AND ns.nspname = coalesce(?, current_schema()) ORDER BY c.conname, k.n");
    try {
      statement.setString(1, table);
      statement.setString(2, schema);
      statement.closeOnCompletion();
      return statement.executeQuery();
    } catch (SQLException | RuntimeException e) {
      statement.close();
      throw e;
    }
  }

  private static List<SchemaModel.ForeignKey> readForeignKeys(
      DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
    Map<String, List<ForeignKeyColumn>> grouped = new LinkedHashMap<>();
    Map<String, String> targetTables = new LinkedHashMap<>();
    try (ResultSet result = yugabyte(metadata)
        ? yugabyteForeignKeys(metadata.getConnection(), schema, table)
        : metadata.getImportedKeys(catalog, schema, table)) {
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
