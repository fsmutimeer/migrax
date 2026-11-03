package org.migrax.dialect;

import org.migrax.model.SchemaModel;
import org.migrax.util.Log;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What reading a live schema needs to know about one database beyond standard JDBC metadata:
 * the default schema, where check constraints and comments are kept, how column defaults are
 * reported, and a few quirks of the drivers. A dialect returns one from
 * {@link Dialect#schemaCatalog()}. This class gives the standard JDBC behavior; a database
 * that differs overrides the hooks it needs.
 *
 * @since 0.19.0
 */
public class SchemaCatalog {

  /** A catalog with the standard JDBC behavior. */
  public SchemaCatalog() {}

  /**
   * The schema to read when neither the caller nor the connection names one.
   *
   * @param metadata the connection's metadata
   * @return the schema, or null to read without one (the default)
   * @throws SQLException when the metadata can't be read
   */
  public String defaultSchema(DatabaseMetaData metadata) throws SQLException {
    return null;
  }

  /**
   * Adds the check constraints and comments the database has. JDBC has no portable way to list
   * either, so each database is asked in its own catalog; a database that can't answer (an older
   * version, missing rights) reports none. The default reports none.
   *
   * @param connection the connection
   * @param catalog the connection's catalog
   * @param schema the schema read, or null for the connection's default
   * @param tables the names of the tables read, as the database spells them
   * @param details where the checks and comments go
   * @throws SQLException when the database can't be asked
   */
  public void readChecksAndComments(Connection connection, String catalog, String schema,
                                    List<String> tables, Details details) throws SQLException {
  }

  /**
   * A column default as SQL that can be written into a migration. The default returns it as
   * the driver reports it.
   *
   * @param logicalType the column's type as Migrax names it, such as {@code varchar}
   * @param value the default as the driver reports it, stripped, never empty, never one that
   *     takes values from a sequence or identity
   * @return the default as SQL
   */
  public String columnDefault(String logicalType, String value) {
    return value;
  }

  /**
   * Whether the driver reports a decimal's size as its precision plus its scale. False by
   * default.
   *
   * @return true when the scale is part of the reported size
   */
  public boolean precisionIncludesScale() {
    return false;
  }

  /**
   * Whether a single-column primary key declared exactly {@code integer} is the row id, which
   * fills itself like an identity column although the driver doesn't report it as
   * auto-increment. False by default.
   *
   * @return true when such a key is an identity column
   */
  public boolean integerPrimaryKeyIsRowId() {
    return false;
  }

  /**
   * The exact type of a {@code timestamp} or {@code datetime} column, read for comparing it with
   * another database read the same way, or null to keep the general type (the default).
   *
   * @param type the type name in lower case: {@code timestamp} or {@code datetime}
   * @param scale the reported scale, or null when there is none or it is 0
   * @param size the reported size, or null
   * @return the exact type, or null
   */
  public String exactDateTimeType(String type, Integer scale, Integer size) {
    return null;
  }

  /**
   * Indexes the database made for a table's unique constraints whose index reports itself as
   * not unique. None by default.
   *
   * @param connection the connection
   * @param schema the schema read, or null for the connection's default
   * @param table the table, as the database spells it
   * @return the index names
   * @throws SQLException when the database can't be asked
   */
  public Set<String> uniqueConstraintIndexes(Connection connection, String schema, String table)
      throws SQLException {
    return Set.of();
  }

  /**
   * Indexes the database made for a table's foreign keys, which the entities don't declare.
   * None by default.
   *
   * @param connection the connection
   * @param schema the schema read, or null for the connection's default
   * @param table the table, as the database spells it
   * @return the index names
   * @throws SQLException when the database can't be asked
   */
  public Set<String> foreignKeyIndexes(Connection connection, String schema, String table)
      throws SQLException {
    return Set.of();
  }

  /**
   * A table's foreign key columns, with the columns of
   * {@link DatabaseMetaData#getImportedKeys} ({@code FK_NAME}, {@code FKCOLUMN_NAME},
   * {@code PKCOLUMN_NAME}, {@code PKTABLE_NAME}, {@code KEY_SEQ}). The default asks the driver.
   *
   * @param metadata the connection's metadata
   * @param catalog the connection's catalog
   * @param schema the schema read, or null for the connection's default
   * @param table the table, as the database spells it
   * @return the rows; the caller closes them
   * @throws SQLException when they can't be read
   */
  public ResultSet importedKeys(DatabaseMetaData metadata, String catalog, String schema,
                                String table) throws SQLException {
    return metadata.getImportedKeys(catalog, schema, table);
  }

  /**
   * Rows of a catalog query as strings; none when the database can't answer it.
   *
   * @param connection the connection
   * @param sql the query; a parameter beyond its last {@code ?} is left out
   * @param params the values of its {@code ?} parameters
   * @return the rows
   */
  protected static List<String[]> query(Connection connection, String sql, String... params) {
    List<String[]> rows = new ArrayList<>();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
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

  /**
   * The condition of {@code CHECK (condition)} as databases report it.
   *
   * @param definition the check's definition, or null
   * @return the condition, or null
   */
  protected static String unwrapCheck(String definition) {
    if (definition == null) {
      return null;
    }
    String text = definition.strip();
    if (text.regionMatches(true, 0, "CHECK", 0, 5)) {
      text = text.substring(5).strip();
    }
    return text;
  }

  private static boolean autoCommit(Connection connection) {
    try {
      return connection.getAutoCommit();
    } catch (SQLException e) {
      return true;
    }
  }

  /**
   * The check constraints and comments found for the tables read. Table and column names are
   * matched in any case.
   */
  public static final class Details {

    // table -> checks, table -> comment, table.column -> comment (names in lower case)
    private final Map<String, List<SchemaModel.Check>> checks = new LinkedHashMap<>();
    private final Map<String, String> comments = new LinkedHashMap<>();

    /** No checks or comments yet. */
    public Details() {}

    /**
     * Adds a check constraint; left out without a table or a name.
     *
     * @param table the table
     * @param name the constraint's name
     * @param expression its condition, or null for an empty one
     */
    public void addCheck(String table, String name, String expression) {
      if (table != null && name != null) {
        checks.computeIfAbsent(table.toLowerCase(Locale.ROOT), t -> new ArrayList<>())
            .add(new SchemaModel.Check(name, expression == null ? "" : expression.strip()));
      }
    }

    /**
     * Adds a comment; left out without a table or with a blank text.
     *
     * @param table the table
     * @param column the column, or null for the table's own comment
     * @param text the comment
     */
    public void addComment(String table, String column, String text) {
      if (table != null && text != null && !text.isBlank()) {
        comments.put(table.toLowerCase(Locale.ROOT)
            + (column == null ? "" : "." + column.toLowerCase(Locale.ROOT)), text);
      }
    }

    /**
     * The checks of a table.
     *
     * @param table the table
     * @return its checks, in the order found, unmodifiable; empty when it has none
     */
    public List<SchemaModel.Check> checks(String table) {
      return List.copyOf(checks.getOrDefault(table.toLowerCase(Locale.ROOT), List.of()));
    }

    /**
     * A comment.
     *
     * @param table the table
     * @param column the column, or null for the table's own comment
     * @return the comment, or null
     */
    public String comment(String table, String column) {
      return comments.get(table.toLowerCase(Locale.ROOT)
          + (column == null ? "" : "." + column.toLowerCase(Locale.ROOT)));
    }
  }
}
