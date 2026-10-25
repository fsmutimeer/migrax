package org.migrax.dialect;

import org.migrax.model.SchemaModel;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.Operation;
import org.migrax.ops.RenameColumn;
import org.migrax.ops.RenameTable;
import org.migrax.ops.RunSql;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oracle database dialect (12c and newer).
 *
 * <p>Names are written unquoted, so Oracle stores them in upper case, exactly like the
 * unquoted names Hibernate sends in its queries.
 *
 * @since 0.1.0
 */
public final class OracleDialect extends AbstractDialect {
  /**
   * The start of the new column a smaller NUMBER goes through ({@link #prepare}), short enough
   * for Oracle's 30-character names. SqlLinter recognizes it.
   */
  private static final String TEMPORARY_COLUMN = "migrax_tmp_";

  private static final Set<String> RESERVED = Set.of(
      "access", "add", "audit", "char", "cluster", "comment", "compress", "connect", "current",
      "date", "decimal", "exclusive", "file", "float", "identified", "immediate", "increment",
      "index", "initial", "integer", "level", "lock", "long", "maxextents", "minus", "mlslabel",
      "mode", "modify", "noaudit", "nocompress", "nowait", "number", "of", "offline", "online",
      "option", "pctfree", "prior", "privileges", "public", "raw", "rename", "resource",
      "revoke", "row", "rowid", "rownum", "rows", "session", "share", "size", "smallint",
      "start", "successful", "synonym", "sysdate", "trigger", "uid", "validate", "varchar",
      "varchar2", "view", "whenever");

  /** Oracle 23ai has a boolean type, which Hibernate uses there. */
  private static final int NATIVE_BOOLEAN = 23;

  /** The server's version, or null when unknown. */
  private final Integer major;
  private final Integer minor;

  /** The dialect for a server of unknown version: booleans are {@code number(1,0)}. */
  public OracleDialect() {
    this(null, null);
  }

  private OracleDialect(Integer major, Integer minor) {
    this.major = major;
    this.minor = minor;
  }

  @Override
  public String id() {
    return "oracle";
  }

  @Override
  public boolean dependsOnServerVersion() {
    return true;
  }

  @Override
  public Dialect forServer(int major, int minor) {
    return new OracleDialect(major, minor);
  }

  @Override
  public java.util.Map<String, String> serverSettings() {
    return major == null ? java.util.Map.of() : java.util.Map.of(
        "jakarta.persistence.database-product-name", "Oracle",
        "jakarta.persistence.database-major-version", String.valueOf(major),
        "jakarta.persistence.database-minor-version", String.valueOf(minor));
  }

  @Override
  public String serverVersion() {
    return major == null ? null : major + "." + minor;
  }

  /** Oracle 23ai for a 23ai project; Oracle XE 21 otherwise, which has no boolean type. */
  @Override
  public String scratchImage() {
    return nativeBoolean() ? "gvenzl/oracle-free:slim-faststart"
        : "gvenzl/oracle-xe:21-slim-faststart";
  }

  private boolean nativeBoolean() {
    return major != null && major >= NATIVE_BOOLEAN;
  }

  @Override
  protected Set<String> reservedWords() {
    return RESERVED;
  }

  @Override
  protected String sequenceDefault(String name) {
    return q(name) + ".NEXTVAL";
  }

  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "varchar" -> "varchar2(" + length(c) + " char)";
      case "nvarchar" -> "nvarchar2(" + length(c) + ")";
      case "text", "clob" -> "clob";
      case "varbinary" -> "raw(" + length(c) + ")";
      case "integer" -> "number(10,0)";
      case "bigint" -> "number(19,0)";
      case "boolean" -> nativeBoolean() ? "boolean" : "number(1,0)";
      case "decimal" -> numeric("number", c, "number(38,2)");
      case "double" -> "float(53)";
      case "float" -> "float(24)";
      case "uuid" -> "raw(16)";
      case "date", "time" -> "date";
      case "timestamp" -> "timestamp(6)";
      case "timestamptz" -> "timestamp(6) with time zone";
      case "blob" -> "blob";
      case "json" -> "json";
      default -> null;
    };
  }

  @Override
  public NameCase unquotedNames() {
    return NameCase.UPPER;
  }

  @Override
  public String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /** ROWNUM works on every Oracle version; FETCH FIRST needs 12c. */
  @Override
  public String firstValue(String value, String from, String condition) {
    return "SELECT " + value + " FROM " + from + " WHERE (" + condition + ") AND ROWNUM = 1";
  }

  @Override
  protected String addColumn(String table, SchemaModel.Column column) {
    return "ALTER TABLE " + q(table) + " ADD (" + column(column) + ")";
  }

  /**
   * Oracle rejects MODIFY ... NULL or NOT NULL when the nullability does not change
   * (ORA-01451, ORA-01442), so it is written only when it changes.
   */
  @Override
  protected String alterColumn(AlterColumn change) {
    SchemaModel.Column after = change.after();
    StringBuilder definition = new StringBuilder(q(after.name()));
    if (change.typeChanged() || change.sizeChanged()
        || !change.nullabilityChanged() && !change.defaultChanged()) {
      definition.append(' ').append(renderType(after));
    }
    if (change.defaultChanged()) {
      definition.append(" DEFAULT ").append(after.defaultValue() == null
          || after.defaultValue().isBlank() ? "NULL" : after.defaultValue());
    }
    if (change.nullabilityChanged()) {
      definition.append(after.nullable() ? " NULL" : " NOT NULL");
    }
    return "ALTER TABLE " + q(change.table()) + " MODIFY (" + definition + ")";
  }

  /**
   * Oracle makes a NUMBER smaller (fewer digits before or after the point) only while the
   * column is empty (ORA-01440). Such a change goes through a new column instead: first a check
   * that every value fits unchanged, which stops the migration before anything is changed; then
   * the new column, the table locked against writes while the values are copied, the old column
   * dropped and the new one given its name, default, NOT NULL and comment. The table is
   * rewritten and the column moves to its end. A column in a key, index, unique constraint or
   * check, or an identity column, keeps the plain MODIFY, which Oracle refuses once the column
   * has values: dropping it would drop those too.
   */
  @Override
  public List<Operation> prepare(List<Operation> operations, SchemaModel before,
                                 SchemaModel after) {
    List<Operation> result = new ArrayList<>();
    for (Operation operation : operations) {
      int[] from = operation instanceof AlterColumn x ? number(x.before()) : null;
      int[] to = operation instanceof AlterColumn x ? number(x.after()) : null;
      if (from != null && to != null && smaller(from, to)
          && !constrained((AlterColumn) operation, operations, before, after)) {
        result.add(new RunSql(throughNewColumn((AlterColumn) operation, to), false));
      } else {
        result.add(operation);
      }
    }
    return result;
  }

  /** {@code number(p,s)}, as Oracle gets it. */
  private static final Pattern NUMBER = Pattern.compile("(?i)number\\((\\d+),(-?\\d+)\\)");

  /**
   * The precision and scale of the type Oracle gets for a column, or null when it isn't a
   * sized NUMBER (an unsized decimal is number(38,2), an integer number(10,0)).
   */
  private int[] number(SchemaModel.Column column) {
    Matcher type = NUMBER.matcher(renderType(column).replace(" ", ""));
    return type.matches()
        ? new int[] {Integer.parseInt(type.group(1)), Integer.parseInt(type.group(2))}
        : null;
  }

  /** Fewer decimals, or fewer digits before the point. */
  private static boolean smaller(int[] from, int[] to) {
    return to[1] < from[1] || to[0] - to[1] < from[0] - from[1];
  }

  /**
   * Whether dropping the column would take something with it, in the schema before or after
   * this migration (before, under the names it had: renames in the same migration come first).
   * Unknown counts as yes.
   */
  private static boolean constrained(AlterColumn change, List<Operation> operations,
                                     SchemaModel before, SchemaModel after) {
    SchemaModel.Column from = change.before();
    SchemaModel.Column to = change.after();
    SchemaModel.Table now = after.table(change.table());
    if (now == null || from.identity() || to.identity() || from.unique() || to.unique()
        || uses(after, now, to.name())) {
      return true;
    }
    Set<String> tables = new HashSet<>(Set.of(change.table()));
    Set<String> columns = new HashSet<>(List.of(to.name(), from.name()));
    for (int i = operations.size() - 1; i >= 0; i--) {
      if (operations.get(i) instanceof RenameTable x && tables.contains(x.to())) {
        tables.add(x.from());
      } else if (operations.get(i) instanceof RenameColumn x && columns.contains(x.to())) {
        columns.add(x.from());
      }
    }
    boolean found = false;
    for (String table : tables) {
      SchemaModel.Table old = before.table(table);
      if (old == null) {
        continue;
      }
      for (String column : columns) {
        if (old.column(column) != null) {
          found = true;
          if (uses(before, old, column)) {
            return true;
          }
        }
      }
    }
    return !found;
  }

  /** Whether a key, index or check of the model uses the table's column. */
  private static boolean uses(SchemaModel model, SchemaModel.Table table, String column) {
    String lower = column.toLowerCase(Locale.ROOT);
    return table.primaryKey() != null && table.primaryKey().columns().contains(column)
        || table.indexes().stream().anyMatch(i -> i.columns().contains(column))
        || table.foreignKeys().stream().anyMatch(k -> k.columns().contains(column))
        || table.checks().stream()
            .anyMatch(c -> c.expression().toLowerCase(Locale.ROOT).contains(lower))
        || model.tables().stream().flatMap(other -> other.foreignKeys().stream())
            .anyMatch(k -> k.referencedTable().equals(table.name())
                && k.referencedColumns().contains(column));
  }

  /** The statements of a smaller NUMBER through a new column (see {@link #prepare}). */
  private String throughNewColumn(AlterColumn change, int[] type) {
    SchemaModel.Column after = change.after();
    String table = q(change.table());
    String column = q(after.name());
    String copy = TEMPORARY_COLUMN + String.format(Locale.ROOT, "%08x",
        (change.table() + "." + after.name()).toLowerCase(Locale.ROOT).hashCode());
    int precision = type[0];
    int scale = type[1];
    // Without its default: a sequence default would spend a value on every row it copies.
    SchemaModel.Column added = new SchemaModel.Column(copy, after.sqlType(), true,
        after.length(), after.precision(), after.scale(), null, false, false, null,
        after.logicalType(), null);
    String target = "NUMBER(" + precision + "," + scale + ")";
    String where = change.table() + "." + after.name();
    List<String> statements = new ArrayList<>(List.of(
        "-- Oracle makes a NUMBER smaller only while it is empty: " + after.name()
            + " is moved through a new\n-- column, which rewrites the table (writes wait "
            + "while the values are copied) and puts\n-- the column last. First, every value "
            + "must fit unchanged.\n"
            + "DECLARE\n  leftover NUMBER;\n  not_fitting NUMBER;\nBEGIN\n"
            + "  -- A run that stopped after adding the new column left it behind: drop it while "
            + "the\n  -- column itself still holds the values.\n"
            + "  SELECT COUNT(*) INTO leftover FROM user_tab_columns\n"
            + "    WHERE UPPER(table_name) = UPPER('" + change.table() + "')\n"
            + "    AND UPPER(column_name) IN (UPPER('" + copy + "'), UPPER('" + after.name()
            + "'));\n"
            + "  IF leftover = 2 THEN\n"
            + "    EXECUTE IMMEDIATE 'ALTER TABLE " + table + " DROP COLUMN " + copy + "';\n"
            + "  END IF;\n"
            + "  SELECT COUNT(*) INTO not_fitting FROM " + table + " WHERE " + column
            + " IS NOT NULL\n    AND (ROUND(" + column + ", " + scale + ") <> " + column
            + " OR ABS(" + column + ") >= POWER(10, " + (precision - scale) + "));\n"
            + "  IF not_fitting > 0 THEN\n"
            + "    RAISE_APPLICATION_ERROR(-20001, 'Migrax: ' || not_fitting || ' value(s) of "
            + where + " do not fit " + target + "; nothing was changed. Fix or delete them, "
            + "or keep the larger type.');\n"
            + "  END IF;\nEND",
        "ALTER TABLE " + table + " ADD (" + column(added) + ")",
        "LOCK TABLE " + table + " IN EXCLUSIVE MODE",
        "UPDATE " + table + " SET " + copy + " = " + column,
        "ALTER TABLE " + table + " DROP COLUMN " + column,
        "ALTER TABLE " + table + " RENAME COLUMN " + copy + " TO " + column));
    String defaultValue = after.defaultValue() != null && !after.defaultValue().isBlank()
        ? after.defaultValue()
        : after.sequenceName() != null && !after.identity()
            ? sequenceDefault(after.sequenceName()) : null;
    if (defaultValue != null || !after.nullable()) {
      statements.add("ALTER TABLE " + table + " MODIFY (" + column
          + (defaultValue != null ? " DEFAULT " + defaultValue : "")
          + (after.nullable() ? "" : " NOT NULL") + ")");
    }
    if (after.comment() != null) {
      statements.add(columnComment(change.table(), after, after.comment()));
    }
    return String.join(";\n", statements);
  }

  @Override
  protected String dropPrimaryKey(String table, String constraintName) {
    return "ALTER TABLE " + q(table) + " DROP PRIMARY KEY";
  }

  @Override
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name);
  }

  /**
   * An exclusive DBMS_LOCK lock held by the session; the migration account needs EXECUTE on
   * DBMS_LOCK.
   */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    int key = Math.floorMod(lockKey(resource), 1_073_741_823) + 1;
    try (CallableStatement statement =
             connection.prepareCall("{? = call DBMS_LOCK.REQUEST(?, 6, 0, FALSE)}")) {
      statement.registerOutParameter(1, Types.INTEGER);
      statement.setInt(2, key);
      statement.execute();
      int status = statement.getInt(1);
      if (status == 1) {
        throw new MigrationLockHeldException("Another Migrax process is applying migrations.");
      }
      if (status != 0 && status != 4) {
        throw new SQLException("Oracle migration lock request failed with status " + status
            + ". The service account may need EXECUTE on DBMS_LOCK.");
      }
    }
    return () -> {
      try (CallableStatement statement =
               connection.prepareCall("{? = call DBMS_LOCK.RELEASE(?)}")) {
        statement.registerOutParameter(1, Types.INTEGER);
        statement.setInt(2, key);
        statement.execute();
        int status = statement.getInt(1);
        if (status != 0) {
          throw new SQLException("Oracle migration lock release failed with status " + status);
        }
      }
    };
  }

  /** Oracle has no NULL comment: an empty one removes it. */
  @Override
  protected String commentValue(String comment) {
    return comment == null ? "''" : literal(comment);
  }

  /**
   * One trigger; an update copies the column the statement sets, so large objects, which
   * can't be compared, work too.
   */
  @Override
  public java.util.List<String> syncColumns(String table, String oldColumn, String newColumn) {
    String name = Dialect.syncTriggerName(table, newColumn, 30);
    String a = identifier(oldColumn);
    String b = identifier(newColumn);
    return java.util.List.of("CREATE TRIGGER " + name + " BEFORE INSERT OR UPDATE ON "
        + tableName(table) + " FOR EACH ROW\n"
        + "BEGIN\n"
        + "  IF INSERTING THEN\n"
        + "    IF :NEW." + b + " IS NULL THEN :NEW." + b + " := :NEW." + a + "; END IF;\n"
        + "    IF :NEW." + a + " IS NULL THEN :NEW." + a + " := :NEW." + b + "; END IF;\n"
        + "  ELSIF UPDATING('" + storedName(newColumn) + "') THEN\n"
        + "    :NEW." + a + " := :NEW." + b + ";\n"
        + "  ELSIF UPDATING('" + storedName(oldColumn) + "') THEN\n"
        + "    :NEW." + b + " := :NEW." + a + ";\n"
        + "  END IF;\n"
        + "END");
  }

  /** A column's name as Oracle stores it: upper case unless it is written quoted. */
  private String storedName(String column) {
    return identifier(column).startsWith("\"") ? column
        : column.toUpperCase(java.util.Locale.ROOT);
  }

  @Override
  public java.util.List<String> dropSyncColumns(String table, String oldColumn,
                                                String newColumn) {
    return java.util.List.of("DROP TRIGGER " + Dialect.syncTriggerName(table, newColumn, 30));
  }

  @Override
  public boolean updatableViews() {
    return true;
  }

  /** DDL statements commit at once. */
  @Override
  public boolean transactionalDdl() {
    return false;
  }
}
