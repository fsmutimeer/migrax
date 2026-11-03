package org.migrax;

import org.migrax.diff.DiffEngine;
import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddColumn;
import org.migrax.ops.Operation;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.runner.SqlScript;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Golden test of {@link DatabaseSchemaReader}: creates a fixture of tricky tables on a
 * database, reads them back, and compares every detail of what the reader returns with a file
 * recorded from the reader as it is. The reader holds many small fixes for single database
 * versions; this file shows any change to what it reads, on every database.
 *
 * <p>The expected files are in {@code src/test/resources/org/migrax/schema-reader}. A missing
 * file fails the test; run with {@code -Dmigrax.golden.record=true} to write it, then review
 * it before committing. Never record again to make a failing test pass: find out why the
 * output changed.
 *
 * <p>Not covered, because Migrax's model can't express them: unnamed checks, descending,
 * partial and expression indexes, and foreign key actions.
 *
 * <p>The files pin what the reader returns, so that a change to it can't go unnoticed.
 */
final class SchemaReaderGolden {
  static final Path EXPECTED = Path.of("src", "test", "resources", "org", "migrax",
      "schema-reader");

  /** The second schema; a database without one records that it has none. */
  private static final String OTHER_SCHEMA = "golden_b";

  /**
   * Names the database makes up for unnamed constraints and indexes, with a number or hash
   * that changes between runs; replaced by {@code <generated>} in the recorded output. Case is
   * ignored: the reader lowercases names the database stored in upper case.
   */
  private static final Pattern GENERATED_NAME = Pattern.compile("\\b("
      // Oracle constraints.
      + "SYS_C\\d+"
      // HSQLDB indexes, primary keys and constraints.
      + "|SYS_IDX_[A-Za-z0-9_]*?\\d+|SYS_PK_\\d+|SYS_CT_\\d+"
      // Derby constraints and indexes, with a UUID after the number for primary keys.
      + "|SQL\\d{6,}(-[0-9A-F]{8}(-[0-9A-F]{4}){3}-[0-9A-F]{12})?"
      // H2 constraints.
      + "|CONSTRAINT_[0-9A-F]+"
      // SQL Server constraints: PK__table__hash.
      + "|(PK|UQ|FK|DF|CK)__[A-Za-z0-9_]+__[0-9A-F]{8,})\\b", Pattern.CASE_INSENSITIVE);

  /** A database error code such as ORA-20000, kept instead of the driver's whole message. */
  private static final Pattern ERROR_CODE = Pattern.compile("\\b[A-Z]{2,5}-\\d{4,5}\\b");

  private SchemaReaderGolden() {}

  /**
   * Creates the fixture, reads it, compares with the expected file of {@code engine}, and drops
   * the fixture again.
   *
   * @param connection a connection to an empty database (Migrax's own tables are ignored)
   * @param engine the name of the expected file, without extension
   */
  static void check(Connection connection, String engine) throws Exception {
    Dialect dialect = Dialects.forConnection(connection).orElseThrow();
    SchemaModel fixture = fixture(dialect);
    // A run that was stopped may have left the fixture behind.
    dropAll(connection, dialect, fixture);
    String actual;
    try {
      execute(connection, dialect, render(dialect, SchemaModel.empty(), fixture));
      execute(connection, "CREATE VIEW golden_view AS SELECT id, code FROM golden_parent");
      String optional = optionalColumns(connection, dialect, !fixture.sequences().isEmpty());
      actual = describe(connection, dialect, optional) + quotedSection(connection, dialect);
    } finally {
      dropAll(connection, dialect, fixture);
    }
    List<String> left = new ArrayList<>();
    for (String table : DatabaseSchemaReader.objectNames(connection, null, "TABLE")) {
      if (table.toLowerCase(Locale.ROOT).startsWith("golden")) {
        left.add(table);
      }
    }
    assertEquals(List.of(), left, engine + ": fixture tables left after the golden test");

    Path file = EXPECTED.resolve(engine + ".txt");
    if (Boolean.getBoolean("migrax.golden.record")) {
      Files.createDirectories(EXPECTED);
      Files.writeString(file, actual, StandardCharsets.UTF_8);
      return;
    }
    if (!Files.exists(file)) {
      fail("No expected schema-reader output for " + engine + " in " + file
          + ". Record it with -Dmigrax.golden.record=true and review it. Output:\n" + actual);
    }
    assertEquals(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"), actual,
        engine + ": the schema reader's output changed");
  }

  /** A file name for an engine's display name: "MySQL 8 (x=1)" becomes "mysql-8-x-1". */
  static String fileName(String engine) {
    return engine.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9.]+", "-")
        .replaceAll("^-|-$", "");
  }

  /** Everything the reader returns for the fixture, one line per detail. */
  private static String describe(Connection connection, Dialect dialect, String optional)
      throws Exception {
    StringBuilder out = new StringBuilder();
    out.append("# dialect ").append(dialect.id()).append('\n');
    out.append("# foldsNames ").append(DatabaseSchemaReader.foldsNames(connection)).append('\n');
    out.append("\n## optional columns\n").append(optional);
    out.append("\n## read\n");
    model(out, DatabaseSchemaReader.read(connection));
    out.append("\n## read exact\n");
    model(out, DatabaseSchemaReader.read(connection, null, true));
    out.append("\n## views\n");
    for (String view : DatabaseSchemaReader.objectNames(connection, null, "VIEW")) {
      // Only the fixture's: servers have views of their own (SQL Server's spt_values).
      if (view.toLowerCase(Locale.ROOT).startsWith("golden")) {
        out.append(view).append('\n');
      }
    }
    out.append("\n## sequences\n");
    // Sorted: the reader returns a set, whose order means nothing.
    for (String sequence : new java.util.TreeSet<>(DatabaseSchemaReader.sequenceNames(
        connection))) {
      if (sequence.toLowerCase(Locale.ROOT).startsWith("golden")) {
        out.append(sequence).append('\n');
      }
    }
    out.append("\n## other schema\n");
    out.append(otherSchema(connection, dialect));
    return mask(out.toString());
  }

  /**
   * Columns with defaults not every database accepts, added one by one; the list says which
   * the database took, and the reads show how they come back.
   */
  private static String optionalColumns(Connection connection, Dialect dialect,
                                        boolean sequence) {
    List<SchemaModel.Column> columns = new ArrayList<>(List.of(
        column("opt_flag", "boolean", true, null, null, null, "TRUE", false, false, null),
        column("opt_text", "varchar", true, 10, null, null, "'x'", false, false, null),
        column("opt_created", "timestamp", true, null, null, null, "CURRENT_TIMESTAMP", false,
            false, null),
        column("opt_day", "date", true, null, null, null, "CURRENT_DATE", false, false, null)));
    if (sequence) {
      columns.add(column("opt_ticket_pg", "bigint", true, null, null, null,
          "nextval('golden_seq')", false, false, null));
      columns.add(column("opt_ticket_ora", "bigint", true, null, null, null,
          "golden_seq.nextval", false, false, null));
      columns.add(column("opt_ticket_std", "bigint", true, null, null, null,
          "NEXT VALUE FOR golden_seq", false, false, null));
    }
    StringBuilder out = new StringBuilder();
    for (SchemaModel.Column column : columns) {
      try {
        execute(connection, dialect, dialect.render(new AddColumn("golden_parent", column)));
        out.append(column.name()).append(" added\n");
      } catch (SQLException e) {
        rollbackQuietly(connection);
        out.append(column.name()).append(" rejected\n");
      }
    }
    return out.toString();
  }

  /**
   * The second schema read by name, created with {@code CREATE SCHEMA} (or
   * {@code CREATE DATABASE} where schemas are databases); "none" where that fails. Its table
   * has the name of a default-schema table with other columns, so reading the wrong schema
   * shows.
   */
  private static String otherSchema(Connection connection, Dialect dialect) throws Exception {
    String kind = dialect.schemasAreDatabases() ? "DATABASE" : "SCHEMA";
    try {
      execute(connection, "CREATE " + kind + " " + OTHER_SCHEMA);
    } catch (SQLException e) {
      rollbackQuietly(connection);
      // Oracle's schemas are users.
      try {
        execute(connection, "CREATE USER " + OTHER_SCHEMA + " IDENTIFIED BY golden"
            + " DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS");
      } catch (SQLException notAUser) {
        rollbackQuietly(connection);
        return "none\n";
      }
    }
    try {
      String table = "CREATE TABLE " + OTHER_SCHEMA + ".golden_parent (other_id INTEGER"
          + " NOT NULL PRIMARY KEY, label VARCHAR(30))";
      try {
        execute(connection, table);
      } catch (SQLException e) {
        throw new SQLException(dialect.id() + ": fixture statement failed: " + table, e);
      }
      StringBuilder out = new StringBuilder();
      out.append("tables ").append(DatabaseSchemaReader.objectNames(connection, OTHER_SCHEMA,
          "TABLE")).append('\n');
      out.append("# read\n");
      model(out, DatabaseSchemaReader.read(connection, OTHER_SCHEMA));
      out.append("# read exact\n");
      model(out, DatabaseSchemaReader.read(connection, OTHER_SCHEMA, true));
      // Its sequence, listed by the schema's name (clean drops what this lists).
      try {
        execute(connection, "CREATE SEQUENCE " + OTHER_SCHEMA + ".golden_b_seq");
        out.append("sequences ")
            .append(DatabaseSchemaReader.sequencesIn(connection, OTHER_SCHEMA)).append('\n');
      } catch (SQLException e) {
        rollbackQuietly(connection);
        out.append("sequences: none created\n");
      }
      return out.toString();
    } finally {
      dropOtherSchema(connection, dialect);
    }
  }

  /** The quoted table, created and read alone (the other fixture tables still exist). */
  private static String quotedSection(Connection connection, Dialect dialect) throws Exception {
    StringBuilder out = new StringBuilder("\n## quoted name\n");
    execute(connection, dialect, render(dialect, SchemaModel.empty(), quoted()));
    SchemaModel read;
    try {
      read = DatabaseSchemaReader.read(connection);
    } catch (SQLException | RuntimeException e) {
      out.append("error ").append(errorCode(e)).append('\n');
      return mask(out.toString());
    }
    model(out, new SchemaModel(read.tables().stream().filter(table -> SchemaModel
        .bareName(table.name()).equalsIgnoreCase("GoldenQuoted")).toList(), List.of()));
    return mask(out.toString());
  }

  /** The database's error code, or the exception's class when the message has none. */
  private static String errorCode(Exception e) {
    Matcher code = ERROR_CODE.matcher(String.valueOf(e.getMessage()));
    return code.find() ? code.group() : e.getClass().getSimpleName();
  }

  private static void model(StringBuilder out, SchemaModel model) {
    for (SchemaModel.Table table : model.tables()) {
      out.append("table ").append(table.name()).append(" comment=").append(table.comment())
          .append('\n');
      for (SchemaModel.Column column : table.columns()) {
        out.append("  ").append(column).append('\n');
      }
      out.append("  ").append(table.primaryKey()).append('\n');
      for (SchemaModel.Index index : table.indexes()) {
        out.append("  ").append(index).append('\n');
      }
      for (SchemaModel.ForeignKey key : table.foreignKeys()) {
        out.append("  ").append(key).append('\n');
      }
      for (SchemaModel.Check check : table.checks()) {
        out.append("  ").append(check).append('\n');
      }
    }
    for (SchemaModel.Sequence sequence : model.sequences()) {
      out.append("sequence ").append(sequence).append('\n');
    }
  }

  private static String mask(String text) {
    return GENERATED_NAME.matcher(text).replaceAll("<generated>");
  }

  /**
   * The fixture: the kinds of columns, defaults, keys, indexes, checks, comments and names
   * the reader has special cases for, written by the dialect as Migrax writes migrations.
   */
  static SchemaModel fixture(Dialect dialect) {
    List<SchemaModel.Column> parent = List.of(
        column("id", "bigint", false, null, null, null, null, false, true, null),
        column("code", "varchar", false, 20, null, null, null, true, false, "Code's text"),
        column("name", "nvarchar", true, 100, null, null, "'it''s'", false, false, null),
        column("amount", "decimal", true, null, 12, 3, "1.50", false, false, null),
        column("ratio", "double", true, null, null, null, null, false, false, null),
        column("small", "float", true, null, null, null, null, false, false, null),
        column("counter", "integer", false, null, null, null, "42", false, false, null),
        column("negative", "integer", true, null, null, null, "-1", false, false, null),
        column("big", "bigint", true, null, null, null, null, false, false, null),
        column("flag", "boolean", true, null, null, null, null, false, false, null),
        column("born", "date", true, null, null, null, null, false, false, null),
        column("at_time", "time", true, null, null, null, null, false, false, null),
        column("created", "timestamp", true, null, null, null, null, false, false, null),
        column("zoned", "timestamptz", true, null, null, null, null, false, false, null),
        column("body", "text", true, null, null, null, null, false, false, null),
        column("doc", "json", true, null, null, null, null, false, false, null),
        column("uid", "uuid", true, null, null, null, null, false, false, null),
        column("bin", "varbinary", true, 16, null, null, null, false, false, null),
        column("big_text", "clob", true, null, null, null, null, false, false, null),
        column("big_bin", "blob", true, null, null, null, null, false, false, null),
        column("manager_id", "bigint", true, null, null, null, null, false, false, null));
    SchemaModel.Table parentTable = new SchemaModel.Table("golden_parent", parent,
        new SchemaModel.PrimaryKey(List.of("id"), null),
        List.of(new SchemaModel.Index("golden_parent_name_idx", List.of("name"), false),
            new SchemaModel.Index("golden_parent_big_uq", List.of("big", "negative"), true)),
        List.of(new SchemaModel.ForeignKey("golden_parent_manager_fk", List.of("manager_id"),
            "golden_parent", List.of("id"))),
        List.of(new SchemaModel.Check("golden_counter_positive", "counter >= 0"),
            new SchemaModel.Check("golden_counter_range", "counter > negative")),
        "Parent's table");
    SchemaModel.Table child = new SchemaModel.Table("golden_child", List.of(
        column("parent_id", "bigint", false, null, null, null, null, false, false, null),
        column("line", "integer", false, null, null, null, null, false, false, null),
        column("note", "varchar", true, null, null, null, null, false, false, "A note")),
        new SchemaModel.PrimaryKey(List.of("parent_id", "line"), null),
        List.of(new SchemaModel.Index("golden_child_note_idx", List.of("note"), false)),
        List.of(new SchemaModel.ForeignKey("golden_child_parent_fk", List.of("parent_id"),
            "golden_parent", List.of("id"))), List.of(), null);
    SchemaModel.Table grandchild = new SchemaModel.Table("golden_grandchild", List.of(
        column("parent_id", "bigint", false, null, null, null, null, false, false, null),
        column("line", "integer", false, null, null, null, null, false, false, null),
        column("seq", "integer", false, null, null, null, null, false, false, null)),
        new SchemaModel.PrimaryKey(List.of("parent_id", "line", "seq"), null), List.of(),
        List.of(new SchemaModel.ForeignKey("golden_grandchild_child_fk",
            List.of("parent_id", "line"), "golden_child", List.of("parent_id", "line"))),
        List.of(), null);
    // An integer (not bigint) identity.
    SchemaModel.Table counted = new SchemaModel.Table("golden_counted", List.of(
        column("id", "integer", false, null, null, null, null, false, true, null),
        column("label", "varchar", true, 20, null, null, null, false, false, null)),
        new SchemaModel.PrimaryKey(List.of("id"), null), List.of(), List.of(), List.of(), null);
    // No primary key: CockroachDB adds a hidden row id column.
    SchemaModel.Table noKey = new SchemaModel.Table("golden_nokey", List.of(
        column("label", "varchar", true, 20, null, null, null, false, false, null),
        column("amount", "integer", true, null, null, null, null, false, false, null)),
        null, List.of(), List.of(), List.of(), null);
    List<SchemaModel.Sequence> sequences = dialect.hibernateSequencesAreTables() ? List.of()
        : List.of(new SchemaModel.Sequence("golden_seq", 1L, 50L));
    return new SchemaModel(List.of(parentTable, child, grandchild, counted, noKey), sequences);
  }

  /**
   * A table with an exact (quoted) mixed-case name, read on its own. Oracle's driver used to
   * fail reading its indexes when asked for exact statistics; a database error is recorded.
   */
  static SchemaModel quoted() {
    return new SchemaModel(List.of(new SchemaModel.Table("\"GoldenQuoted\"", List.of(
        column("id", "integer", false, null, null, null, null, false, false, null),
        column("\"Value\"", "varchar", true, 10, null, null, null, false, false, null)),
        new SchemaModel.PrimaryKey(List.of("id"), null), List.of(), List.of(), List.of(),
        null)), List.of());
  }

  private static SchemaModel.Column column(String name, String type, boolean nullable,
                                           Integer length, Integer precision, Integer scale,
                                           String defaultValue, boolean unique,
                                           boolean identity, String comment) {
    return new SchemaModel.Column(name, type, nullable, length, precision, scale, defaultValue,
        unique, identity, null, type, comment);
  }

  /** Drops everything the test creates; what doesn't exist is skipped. */
  private static void dropAll(Connection connection, Dialect dialect, SchemaModel fixture) {
    dropQuietly(connection, "DROP VIEW golden_view");
    for (String sql : split(dialect, render(dialect, quoted(), SchemaModel.empty()))) {
      dropQuietly(connection, sql);
    }
    for (String sql : split(dialect, render(dialect, fixture, SchemaModel.empty()))) {
      dropQuietly(connection, sql);
    }
    dropOtherSchema(connection, dialect);
  }

  private static void dropOtherSchema(Connection connection, Dialect dialect) {
    String kind = dialect.schemasAreDatabases() ? "DATABASE" : "SCHEMA";
    dropQuietly(connection, "DROP TABLE " + OTHER_SCHEMA + ".golden_parent");
    dropQuietly(connection, "DROP SEQUENCE " + OTHER_SCHEMA + ".golden_b_seq");
    dropQuietly(connection, "DROP SEQUENCE " + OTHER_SCHEMA + ".golden_b_seq RESTRICT");
    // Derby needs RESTRICT, SQL Server and MySQL reject it; the second try is a no-op.
    dropQuietly(connection, "DROP " + kind + " " + OTHER_SCHEMA);
    dropQuietly(connection, "DROP " + kind + " " + OTHER_SCHEMA + " RESTRICT");
    // Oracle's schemas are users. Where schemas are databases, a user of that name is an account.
    if (!dialect.schemasAreDatabases()) {
      dropQuietly(connection, "DROP USER " + OTHER_SCHEMA + " CASCADE");
    }
  }

  private static String render(Dialect dialect, SchemaModel before, SchemaModel after) {
    List<Operation> operations = dialect.prepare(new DiffEngine().diff(before, after), before,
        after);
    StringBuilder sql = new StringBuilder();
    for (Operation operation : operations) {
      sql.append(dialect.render(operation)).append(";\n");
    }
    return sql.toString();
  }

  private static List<String> split(Dialect dialect, String sql) {
    return SqlScript.split(sql, dialect.backslashEscapes());
  }

  private static void execute(Connection connection, Dialect dialect, String sql)
      throws SQLException {
    for (String statement : split(dialect, sql)) {
      try {
        execute(connection, statement);
      } catch (SQLException e) {
        throw new SQLException(dialect.id() + ": fixture statement failed: " + statement, e);
      }
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
    if (!connection.getAutoCommit()) {
      connection.commit();
    }
  }

  private static void dropQuietly(Connection connection, String sql) {
    try {
      execute(connection, sql);
    } catch (SQLException e) {
      rollbackQuietly(connection);
    }
  }

  private static void rollbackQuietly(Connection connection) {
    try {
      if (!connection.getAutoCommit()) {
        connection.rollback();
      }
    } catch (SQLException ignored) {
      // Nothing more to undo.
    }
  }
}
