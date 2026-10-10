package io.migrax.verify;

import io.migrax.model.SchemaModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Compares an expected schema with one read from a database and lists real differences:
 * missing or extra tables and columns, nullability, type families, keys and unique columns.
 *
 * <p>Type spellings vary between databases and drivers, so types are compared by family
 * (text, integer, decimal, time, binary, boolean); a wider integer column is accepted for a
 * narrower mapping, as Hibernate accepts it.
 *
 * @since 0.1.0
 */
public final class SchemaComparator {
  private static final Set<String> IGNORED_TABLES = Set.of(
      "migrax_history", "migrax_failures", "flyway_schema_history", "databasechangelog",
      "databasechangeloglock");

  private SchemaComparator() {}

  /** One difference. */
  public record Difference(String kind, String object, String detail) {
    @Override
    public String toString() {
      return object + ": " + detail;
    }
  }

  public static List<Difference> compare(SchemaModel expected, SchemaModel actual,
                                         String dialect) {
    List<Difference> differences = new ArrayList<>();
    Set<String> sequenceTables = new HashSet<>();
    // Databases without sequences, where Hibernate keeps the next value in a table.
    if ("mysql".equals(dialect) || "sqlite".equals(dialect)) {
      expected.sequences().forEach(s -> sequenceTables.add(key(s.name())));
    }
    for (SchemaModel.Table table : expected.tables()) {
      SchemaModel.Table found = find(actual, table.name());
      if (found == null) {
        differences.add(new Difference("missing_table", table.name(),
            "table is missing from the database"));
        continue;
      }
      compareTable(table, found, dialect, differences);
    }
    for (SchemaModel.Table table : actual.tables()) {
      String name = key(table.name());
      if (find(expected, table.name()) == null && !IGNORED_TABLES.contains(name)
          && !sequenceTables.contains(name)) {
        differences.add(new Difference("extra_table", table.name(),
            "table exists in the database but not in the expected schema"));
      }
    }
    return differences;
  }

  private static void compareTable(SchemaModel.Table expected, SchemaModel.Table actual,
                                   String dialect, List<Difference> differences) {
    String table = expected.name();
    Set<String> primaryKey = new HashSet<>();
    if (expected.primaryKey() != null) {
      expected.primaryKey().columns().forEach(c -> primaryKey.add(key(c)));
    }
    for (SchemaModel.Column column : expected.columns()) {
      SchemaModel.Column found = column(actual, column.name());
      String object = table + "." + column.name();
      if (found == null) {
        differences.add(new Difference("missing_column", object,
            "column is missing from the database"));
        continue;
      }
      if (!primaryKey.contains(key(column.name())) && column.nullable() != found.nullable()) {
        differences.add(new Difference("nullability", object, "expected "
            + (column.nullable() ? "NULL" : "NOT NULL") + " but the database has "
            + (found.nullable() ? "NULL" : "NOT NULL")));
      }
      String typeProblem = typeProblem(column, found, dialect);
      if (typeProblem != null) {
        differences.add(new Difference("type", object, typeProblem));
      }
      if (column.unique() && !found.unique() && !primaryKey.contains(key(column.name()))) {
        differences.add(new Difference("unique", object,
            "expected a unique constraint, but the database has none"));
      }
    }
    for (SchemaModel.Column column : actual.columns()) {
      if (column(expected, column.name()) == null) {
        differences.add(new Difference("extra_column", table + "." + column.name(),
            "column exists in the database but not in the expected schema"));
      }
    }
    List<String> expectedKey = expected.primaryKey() == null ? List.of()
        : expected.primaryKey().columns().stream().map(SchemaComparator::key).toList();
    List<String> actualKey = actual.primaryKey() == null ? List.of()
        : actual.primaryKey().columns().stream().map(SchemaComparator::key).toList();
    if (!expectedKey.equals(actualKey)) {
      differences.add(new Difference("primary_key", table, "expected primary key "
          + expectedKey + " but the database has " + actualKey));
    }
    Set<String> actualKeys = new HashSet<>();
    actual.foreignKeys().forEach(k -> actualKeys.add(signature(k)));
    for (SchemaModel.ForeignKey key : expected.foreignKeys()) {
      if (!actualKeys.contains(signature(key))) {
        differences.add(new Difference("missing_foreign_key", table, "foreign key "
            + key.columns() + " -> " + key.referencedTable() + " is missing"));
      }
    }
    Set<String> expectedKeys = new HashSet<>();
    expected.foreignKeys().forEach(k -> expectedKeys.add(signature(k)));
    for (SchemaModel.ForeignKey key : actual.foreignKeys()) {
      if (!expectedKeys.contains(signature(key))) {
        differences.add(new Difference("extra_foreign_key", table, "foreign key "
            + key.columns() + " -> " + key.referencedTable() + " is not expected"));
      }
    }
  }

  private static String signature(SchemaModel.ForeignKey key) {
    return key.columns().stream().map(SchemaComparator::key).toList() + "->"
        + key(key.referencedTable());
  }

  private static String typeProblem(SchemaModel.Column expected, SchemaModel.Column actual,
                                    String dialect) {
    String want = family(expected, dialect);
    String have = family(actual, dialect);
    if (want.equals("unknown") || have.equals("unknown")) {
      return null;
    }
    // SQLite stores every integer in up to 8 bytes, whatever the declared type.
    if (want.equals("integer") && have.equals("integer") && "sqlite".equals(dialect)) {
      return null;
    }
    if (want.equals("integer") && have.equals("integer")) {
      int wantRank = rank(expected);
      int haveRank = rank(actual);
      return haveRank >= wantRank ? null : "expected " + label(expected)
          + " but the database column is narrower (" + label(actual) + ")";
    }
    if (want.equals("text") && have.equals("text")) {
      Long wantLength = textLength(expected);
      Long haveLength = textLength(actual);
      return wantLength == null || haveLength == null || haveLength >= wantLength ? null
          : "expected " + sized(expected) + " but the database column is shorter ("
              + sized(actual) + ")";
    }
    if (want.equals("number") && have.equals("number")
        && "decimal".equals(expected.logicalType()) && "decimal".equals(actual.logicalType())
        && expected.precision() != null && actual.precision() != null) {
      int wantScale = expected.scale() == null ? 0 : expected.scale();
      int haveScale = actual.scale() == null ? 0 : actual.scale();
      return actual.precision() >= expected.precision() && haveScale >= wantScale ? null
          : "expected " + sized(expected) + " but the database column is narrower ("
              + sized(actual) + ")";
    }
    if (want.equals(have)) {
      return null;
    }
    return "expected a " + want + " type (" + label(expected) + ") but the database has a "
        + have + " type (" + label(actual) + ")";
  }

  private static String family(SchemaModel.Column column, String dialect) {
    String logical = column.logicalType() == null ? "" : column.logicalType().toLowerCase(Locale.ROOT);
    String sql = column.sqlType() == null ? "" : column.sqlType().toLowerCase(Locale.ROOT);
    if (sql.startsWith("enum")) {
      return "text";
    }
    return switch (logical) {
      case "varchar", "nvarchar", "text", "json" -> "text";
      case "clob" -> "postgresql".equals(dialect) ? "unknown" : "text";
      case "integer", "bigint" -> "integer";
      case "decimal", "double", "float" -> "number";
      case "boolean" -> "boolean";
      case "date", "time", "timestamp", "timestamptz" -> "time";
      case "varbinary" -> "binary";
      case "blob" -> "postgresql".equals(dialect) ? "unknown" : "binary";
      default -> "unknown";
    };
  }

  /**
   * Maximum characters of a text column: the declared length of varchar (255 when not given,
   * as in JPA), unlimited for text and json, null when unknown (enums).
   */
  private static Long textLength(SchemaModel.Column column) {
    String sql = column.sqlType() == null ? "" : column.sqlType().toLowerCase(Locale.ROOT);
    if (sql.startsWith("enum")) {
      return null;
    }
    return switch (column.logicalType() == null ? "" : column.logicalType()) {
      // H2 and PostgreSQL report unbounded text as varchar(2147483647); 0 or -1 is unknown.
      case "varchar", "nvarchar" -> column.length() == null ? Long.valueOf(255L)
          : column.length() >= 1_000_000_000 ? Long.valueOf(Long.MAX_VALUE)
          : column.length() > 0 ? Long.valueOf(column.length()) : null;
      case "text", "json", "clob" -> Long.MAX_VALUE;
      default -> null;
    };
  }

  private static String sized(SchemaModel.Column column) {
    Long length = textLength(column);
    if (length != null && length != Long.MAX_VALUE) {
      return label(column) + "(" + length + ")";
    }
    if (column.precision() != null) {
      return label(column) + "(" + column.precision()
          + (column.scale() == null ? "" : "," + column.scale()) + ")";
    }
    return label(column);
  }

  private static int rank(SchemaModel.Column column) {
    String sql = column.sqlType() == null ? "" : column.sqlType().toLowerCase(Locale.ROOT)
        .replace(" ", "");
    if (sql.equals("tinyint") || sql.equals("number(3,0)")) return 1;
    if (sql.equals("smallint") || sql.equals("number(5,0)")) return 2;
    return "bigint".equals(column.logicalType()) ? 4 : 3;
  }

  private static String label(SchemaModel.Column column) {
    return column.sqlType() != null && !column.sqlType().equalsIgnoreCase(column.logicalType())
        ? column.sqlType() : column.logicalType();
  }

  private static SchemaModel.Table find(SchemaModel model, String name) {
    for (SchemaModel.Table table : model.tables()) {
      if (key(table.name()).equals(key(name))) {
        return table;
      }
    }
    return null;
  }

  private static SchemaModel.Column column(SchemaModel.Table table, String name) {
    for (SchemaModel.Column column : table.columns()) {
      if (key(column.name()).equals(key(name))) {
        return column;
      }
    }
    return null;
  }

  private static String key(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
