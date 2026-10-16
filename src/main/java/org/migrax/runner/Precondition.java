package org.migrax.runner;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A condition a SQL migration needs before it runs, written as a comment line:
 *
 * <pre>
 * -- migrax:require table-exists customer
 * -- migrax:require column-missing customer.email else skip
 * -- migrax:require sql select count(*) = 0 from customer where email is null
 * </pre>
 *
 * <p>Checks: {@code table-exists}, {@code table-missing}, {@code column-exists},
 * {@code column-missing} (table or {@code schema.table}, column as {@code table.column}), and
 * {@code sql <query>}, which holds when the query returns a row whose first value is not NULL,
 * false or 0. {@code else fail} (the default) stops the run with an error; {@code else skip}
 * records the migration as applied without running it.
 *
 * @param check what is checked
 * @param argument the table, column or query
 * @param skip whether an unmet condition skips the migration instead of failing the run
 * @since 0.13.0
 */
public record Precondition(Check check, String argument, boolean skip) {

  /** What a precondition checks. */
  public enum Check {
    TABLE_EXISTS, TABLE_MISSING, COLUMN_EXISTS, COLUMN_MISSING, SQL;

    String word() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }

  private static final String PREFIX = "-- migrax:require";
  private static final Pattern ELSE =
      Pattern.compile("(?i)^(.*?)\\s+else\\s+(skip|fail)\\s*;?\\s*$", Pattern.DOTALL);

  /**
   * The preconditions of a migration, in file order; empty for Java migrations and SQL without
   * {@code -- migrax:require} lines.
   *
   * @throws IllegalArgumentException when a line can't be read, naming the migration
   */
  public static List<Precondition> of(Migration migration) {
    List<Precondition> preconditions = new ArrayList<>();
    if (migration.sql() == null) {
      return preconditions;
    }
    for (String line : migration.sql().lines().toList()) {
      String trimmed = line.trim();
      if (trimmed.length() >= PREFIX.length()
          && trimmed.substring(0, PREFIX.length()).equalsIgnoreCase(PREFIX)
          && (trimmed.length() == PREFIX.length()
              || Character.isWhitespace(trimmed.charAt(PREFIX.length())))) {
        try {
          preconditions.add(parse(trimmed.substring(PREFIX.length()).trim()));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException("Migration '" + migration.version() + "': "
              + e.getMessage() + " in '" + trimmed + "'.", e);
        }
      }
    }
    return preconditions;
  }

  static Precondition parse(String text) {
    boolean skip = false;
    Matcher otherwise = ELSE.matcher(text);
    if (otherwise.matches()) {
      text = otherwise.group(1).trim();
      skip = otherwise.group(2).equalsIgnoreCase("skip");
    }
    int space = text.indexOf(' ');
    String word = (space < 0 ? text : text.substring(0, space)).toLowerCase(Locale.ROOT);
    String argument = space < 0 ? "" : text.substring(space + 1).trim();
    Check check = null;
    for (Check candidate : Check.values()) {
      if (candidate.word().equals(word)) {
        check = candidate;
      }
    }
    if (check == null) {
      throw new IllegalArgumentException("unknown check '" + word + "' (use table-exists, "
          + "table-missing, column-exists, column-missing or sql)");
    }
    if (argument.isEmpty()) {
      throw new IllegalArgumentException(check.word() + " needs "
          + (check == Check.SQL ? "a query" : check.name().startsWith("TABLE")
              ? "a table name" : "a column as table.column"));
    }
    if ((check == Check.COLUMN_EXISTS || check == Check.COLUMN_MISSING)
        && argument.lastIndexOf('.') <= 0) {
      throw new IllegalArgumentException(check.word() + " needs a column as table.column");
    }
    if (check != Check.SQL && argument.contains(" ")) {
      throw new IllegalArgumentException(check.word() + " takes one name");
    }
    return new Precondition(check, argument, skip);
  }

  /** The condition as written, without the else part, e.g. {@code table-exists customer}. */
  public String describe() {
    return check.word() + " " + argument;
  }

  /**
   * Whether the condition holds on this connection now.
   *
   * @param placeholders values for {@code ${name}} in a query
   */
  boolean holds(Connection connection, Map<String, String> placeholders) throws SQLException {
    return switch (check) {
      case TABLE_EXISTS -> tableExists(connection, argument);
      case TABLE_MISSING -> !tableExists(connection, argument);
      case COLUMN_EXISTS -> columnExists(connection, argument);
      case COLUMN_MISSING -> !columnExists(connection, argument);
      case SQL -> queryHolds(connection, SqlScript.substitute(argument, placeholders));
    };
  }

  private static boolean queryHolds(Connection connection, String query) throws SQLException {
    String sql = query.strip().replaceAll(";\\s*$", "");
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql)) {
      if (!result.next()) {
        return false;
      }
      Object value = result.getObject(1);
      if (value == null || Boolean.FALSE.equals(value)) {
        return false;
      }
      if (value instanceof Number number) {
        return new java.math.BigDecimal(number.toString()).signum() != 0;
      }
      return true;
    }
  }

  private static boolean tableExists(Connection connection, String name) throws SQLException {
    String[] parts = split(name);
    return find(connection, parts[0], parts[1], null);
  }

  private static boolean columnExists(Connection connection, String name) throws SQLException {
    int dot = name.lastIndexOf('.');
    String[] parts = split(name.substring(0, dot));
    return find(connection, parts[0], parts[1], name.substring(dot + 1));
  }

  /** Schema (or null) and table of {@code table} or {@code schema.table}. */
  private static String[] split(String name) {
    int dot = name.indexOf('.');
    return dot < 0 ? new String[]{null, name}
        : new String[]{name.substring(0, dot), name.substring(dot + 1)};
  }

  /**
   * Looks a table (column null) or column up in the metadata, as written, upper case and lower
   * case, since databases fold unquoted names differently.
   */
  private static boolean find(Connection connection, String schema, String table, String column)
      throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    // Databases with catalogs instead of schemas (MySQL family) take the qualifier as catalog.
    boolean catalogs = !metadata.supportsSchemasInTableDefinitions();
    String catalog = catalogs ? schema : connection.getCatalog();
    String owner = catalogs ? null : schema == null ? currentSchema(connection) : schema;
    if (catalogs && catalog == null) {
      catalog = connection.getCatalog();
    }
    for (String[] names : variants(owner, table, column)) {
      if (column == null) {
        // '_' is a pattern character for the metadata: compare the names found.
        try (ResultSet tables = metadata.getTables(catalog, names[0], names[1],
            new String[]{"TABLE", "BASE TABLE"})) {
          while (tables.next()) {
            if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
              return true;
            }
          }
        }
      } else {
        try (ResultSet columns = metadata.getColumns(catalog, names[0], names[1], names[2])) {
          while (columns.next()) {
            if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  private static List<String[]> variants(String schema, String table, String column) {
    List<String[]> variants = new ArrayList<>();
    variants.add(new String[]{schema, table, column});
    variants.add(new String[]{upper(schema), upper(table), upper(column)});
    variants.add(new String[]{lower(schema), lower(table), lower(column)});
    return variants;
  }

  private static String upper(String name) {
    return name == null ? null : name.toUpperCase(Locale.ROOT);
  }

  private static String lower(String name) {
    return name == null ? null : name.toLowerCase(Locale.ROOT);
  }

  private static String currentSchema(Connection connection) {
    String named = TargetSchema.named(connection);
    if (named != null) {
      return named;
    }
    try {
      String schema = connection.getSchema();
      return schema == null || schema.isBlank() ? null : schema;
    } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
      return null;
    }
  }
}
