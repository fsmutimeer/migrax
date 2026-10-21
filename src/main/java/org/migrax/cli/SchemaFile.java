package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.model.ModelExtractor;
import org.migrax.model.SchemaModel;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.plugin.RuntimeJdbc;
import org.migrax.runner.SqlScript;
import org.migrax.util.Json;
import org.migrax.util.Log;
import org.migrax.verify.ScratchDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The desired schema written as SQL ({@code migrax.schema-file}), for projects without JPA
 * entities. Migrax applies the file to a throwaway database and reads the tables back, so the
 * rest of {@code generate} works as it does for entities. What it read is kept in
 * {@code schema-model.json} next to the snapshot: while the file is unchanged, commands (such
 * as {@code check} in CI) use it without starting a database.
 */
final class SchemaFile {
  static final String CACHE = "schema-model.json";

  /**
   * Part of the cache key. Raise it whenever {@link DatabaseSchemaReader} or the naming below
   * reads the same file differently, so caches written by older versions are read again.
   */
  private static final int READER_VERSION = 1;

  /** Names databases make up for unnamed constraints and indexes. */
  private static final Pattern MADE_UP_NAME = Pattern.compile("(?i)sys_c\\d+"
      + "|sys_(pk|fk|ct|idx|ac|uq)_.*\\d+|(constraint|primary_key|constraint_index)_[0-9a-f]+"
      // Derby: SQL0000000183-ce684bc2-01a1-5da5-0497-00001e742478.
      + "|sql\\d{6,}(-[0-9a-f]+)*|(pk|fk|uq|ck|df)__.+__[0-9a-f]{8,}"
      // MySQL and TiDB number them in table order, so adding one renumbers the rest.
      + "|.+_ibfk_\\d+|.+_chk_\\d+");
  private static final Pattern SEQUENCE = Pattern.compile("(?i)\\bCREATE\\s+SEQUENCE\\b");
  private static final Pattern ON_UPDATE = Pattern.compile("(?i)\\bON\\s+UPDATE\\s+CURRENT_");
  private static final Pattern QUALIFIED_TABLE = Pattern.compile(
      "(?i)\\bCREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?[\\w\"`\\[\\]]+\\s*\\.\\s*[\\w\"`\\[]");

  private SchemaFile() {}

  /** The schema the file describes, from the cache when nothing that matters changed. */
  static ModelExtractor.Result read(Project project, Path file, Dialect dialect)
      throws Exception {
    String source = project.display(file);
    if (!Files.isRegularFile(file)) {
      throw new UsageException(ErrorCode.SCHEMA_FILE_FAILED,
          "Schema file not found: " + source + ".",
          "Check migrax.schema-file (or --schema-file); a relative path starts at the project "
              + "folder.");
    }
    String sql = SqlScript.substitute(Files.readString(file, StandardCharsets.UTF_8),
        project.runOptions().placeholders());
    String key = sha256(sql + "\n" + dialect.id() + "\n" + dialect.serverVersion() + "\n"
        + READER_VERSION);
    Path cache = project.state().resolve(CACHE);
    SchemaModel cached = cached(cache, key);
    if (cached != null) {
      Log.debug("Read {} from {} (unchanged since it was last read).", source,
          project.display(cache));
      return new ModelExtractor.Result(cached, source);
    }
    if (SEQUENCE.matcher(sql).find()) {
      Log.warn("{} creates sequences, which Migrax doesn't read from a schema file yet: create "
          + "them in a migration of your own (migrax new).", source);
    }
    if (ON_UPDATE.matcher(sql).find()) {
      Log.warn("{} uses ON UPDATE CURRENT_TIMESTAMP, which Migrax doesn't read from a schema "
          + "file yet: add it to the generated migration yourself.", source);
    }
    if (QUALIFIED_TABLE.matcher(sql).find()) {
      Log.warn("{} creates tables in another schema; Migrax reads the default schema only.",
          source);
    }
    SchemaModel model = withOwnNames(readThroughDatabase(project, source, sql, dialect));
    Files.createDirectories(project.state());
    Files.writeString(cache, "{\n  \"schemaFile\": " + Json.q(source) + ",\n  \"sha256\": "
        + Json.q(key) + ",\n" + Json.write(model, dialect.id()).substring(2));
    return new ModelExtractor.Result(model, source);
  }

  /** The cached model when it was read from this very file and setup, else null. */
  private static SchemaModel cached(Path cache, String key) {
    try {
      if (!Files.isRegularFile(cache)) {
        return null;
      }
      String json = Files.readString(cache);
      return key.equals(Json.rootString(json, "sha256")) ? Json.parse(json) : null;
    } catch (Exception e) {
      Log.debug("Ignoring {}: {}", cache, e.getMessage());
      return null;
    }
  }

  private static SchemaModel readThroughDatabase(Project project, String source, String sql,
                                                 Dialect dialect) throws Exception {
    ClassLoader loader = project.loader(false);
    String scratchUrl = project.setting("--scratch-url", "scratch-url");
    boolean provided = !Project.blank(scratchUrl);
    ScratchDatabase database;
    if (provided) {
      // A scratch database usually lives on the same server, so it gets the same credentials.
      database = ScratchDatabase.existing(scratchUrl, project.credentials().user(),
          project.credentials().password());
    } else {
      database = startScratch(project, source, dialect, loader);
    }
    try (database;
         Connection connection = RuntimeJdbc.connect(loader, database.url(), database.user(),
             database.password())) {
      if (provided && !CleanCommand.isEmpty(connection, null)) {
        throw new UsageException(ErrorCode.SCHEMA_FILE_FAILED,
            "The database at --scratch-url is not empty.",
            "Migrax applies " + source + " to it, reads the tables back and empties it again, "
                + "so it must start empty. Use a database of its own for this.");
      }
      SchemaModel model = null;
      try {
        apply(connection, source, sql, dialect);
        model = DatabaseSchemaReader.read(connection, null, true);
        Log.info("Read {}: {} table(s).", source, model.tables().size());
      } finally {
        if (provided) {
          emptyAgain(connection, dialect, project, model != null);
        }
      }
      return model;
    }
  }

  /**
   * Empties the --scratch-url database again. When reading the file already failed, that error
   * is the one the user needs, so a failure here is only logged.
   */
  private static void emptyAgain(Connection connection, Dialect dialect, Project project,
                                 boolean read) throws Exception {
    try {
      CleanCommand.empty(connection, dialect, null, project.lockTimeout());
    } catch (Exception e) {
      if (read) {
        throw e;
      }
      Log.warn("Could not empty the --scratch-url database again: {}", Errors.describe(e));
    }
  }

  private static ScratchDatabase startScratch(Project project, String source, Dialect dialect,
                                              ClassLoader loader) throws Exception {
    try {
      String image = project.setting("--image", "scratch-image");
      return ScratchDatabase.start(dialect.id(),
          Project.blank(image) ? dialect.scratchImage() : image, loader);
    } catch (IllegalStateException e) {
      String hint = "Start Docker, pass --scratch-url with an empty database, or commit "
          + CACHE + " from a machine that has Docker: while " + source
          + " is unchanged, Migrax reads it from there.";
      if (String.valueOf(e.getMessage()).startsWith("Docker is not available")) {
        throw new UsageException(ErrorCode.SCHEMA_FILE_FAILED, "Migrax reads " + source
            + " by applying it to a throwaway " + dialect.id() + " database, which needs "
            + "Docker, and Docker is not available.", hint);
      }
      throw new UsageException(ErrorCode.SCHEMA_FILE_FAILED, "Could not start a throwaway "
          + dialect.id() + " database to read " + source + ": " + e.getMessage(), hint);
    }
  }

  /** Runs the file statement by statement, naming the one that fails and its line. */
  private static void apply(Connection connection, String source, String sql, Dialect dialect)
      throws Exception {
    boolean backslashEscapes = "mysql".equals(dialect.id()) || "mariadb".equals(dialect.id())
        || "tidb".equals(dialect.id());
    List<String> statements;
    try {
      statements = SqlScript.split(sql, backslashEscapes);
    } catch (IllegalArgumentException e) {
      throw new UsageException(ErrorCode.SCHEMA_FILE_FAILED, source + " can't be split into "
          + "statements: " + e.getMessage(), "Close the quote or comment it names.");
    }
    connection.setAutoCommit(true);
    int searchFrom = 0;
    int at = -1;
    String previous = null;
    for (int i = 0; i < statements.size(); i++) {
      String statement = statements.get(i);
      // GO n repeats a batch: it stays on the line where it was found.
      if (!statement.equals(previous)) {
        String first = firstLine(statement);
        at = sql.indexOf(first, searchFrom);
        if (at >= 0) {
          searchFrom = at + first.length();
        }
      }
      previous = statement;
      try (Statement jdbc = connection.createStatement()) {
        jdbc.execute(statement);
      } catch (java.sql.SQLException e) {
        String line = at < 0 ? "" : " (line " + (lineBreaks(sql, at) + 1) + ")";
        throw new UsageException(ErrorCode.SCHEMA_FILE_FAILED, source + ", statement " + (i + 1)
            + line + " fails on " + dialect.id() + ": " + Errors.describe(e),
            "Migrax applies the file to an empty " + dialect.id() + " database to read it. "
                + "Fix the statement so it runs there; views, functions and triggers belong in "
                + "repeatable migrations instead.");
      }
    }
  }

  private static String firstLine(String statement) {
    String first = statement.strip().lines().findFirst().orElse("");
    return first.length() > 60 ? first.substring(0, 60) : first;
  }

  private static long lineBreaks(String text, int end) {
    return text.substring(0, end).chars().filter(c -> c == '\n').count();
  }

  /**
   * Replaces the names a database makes up for unnamed constraints (Oracle's SYS_C..., SQL
   * Server's FK__orders__... with a random end) with Migrax's own readable ones, so reading the
   * file again on a new throwaway database gives the same names.
   */
  static SchemaModel withOwnNames(SchemaModel model) {
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (SchemaModel.Table t : model.tables()) {
      SchemaModel.PrimaryKey key = t.primaryKey() == null ? null
          : new SchemaModel.PrimaryKey(t.primaryKey().columns(),
              own(t.primaryKey().constraintName(), primaryKeyName(t)));
      List<SchemaModel.Index> indexes = t.indexes().stream()
          .map(i -> new SchemaModel.Index(own(i.name(), indexName(t.name(), i)), i.columns(),
              i.unique())).toList();
      List<SchemaModel.ForeignKey> keys = t.foreignKeys().stream()
          .map(k -> new SchemaModel.ForeignKey(own(k.name(), foreignKeyName(t.name(), k)),
              k.columns(), k.referencedTable(), k.referencedColumns())).toList();
      List<SchemaModel.Check> checks = t.checks().stream()
          .map(c -> new SchemaModel.Check(own(c.name(),
              SchemaModel.checkConstraintName(t.name(), c.expression())), c.expression()))
          .toList();
      tables.add(new SchemaModel.Table(t.name(), t.columns(), key, indexes, keys, checks,
          t.comment()));
    }
    return new SchemaModel(tables, model.sequences());
  }

  /**
   * The desired schema with the existing database's names for constraints Migrax named itself
   * when the database already has the same constraint under another name, so the first
   * generate against an existing database doesn't drop and add them again.
   */
  static SchemaModel withNamesFrom(SchemaModel desired, SchemaModel existing) {
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (SchemaModel.Table t : desired.tables()) {
      SchemaModel.Table old = existing.table(t.name());
      if (old == null) {
        tables.add(t);
        continue;
      }
      SchemaModel.PrimaryKey key = t.primaryKey();
      if (key != null && old.primaryKey() != null
          && same(key.columns(), old.primaryKey().columns())
          && primaryKeyName(t).equals(key.constraintName())) {
        key = new SchemaModel.PrimaryKey(key.columns(), old.primaryKey().constraintName());
      }
      List<SchemaModel.Index> indexes = renamed(t.indexes(), i -> !i.name().equals(
          indexName(t.name(), i)) ? i : old.indexes().stream()
          .filter(o -> o.unique() == i.unique() && same(o.columns(), i.columns()))
          .findFirst().map(o -> new SchemaModel.Index(o.name(), i.columns(), i.unique()))
          .orElse(i));
      List<SchemaModel.ForeignKey> keys = renamed(t.foreignKeys(), k -> !k.name().equals(
          foreignKeyName(t.name(), k)) ? k : old.foreignKeys().stream()
          .filter(o -> same(o.columns(), k.columns())
              && o.referencedTable().equalsIgnoreCase(k.referencedTable())
              && same(o.referencedColumns(), k.referencedColumns()))
          .findFirst().map(o -> new SchemaModel.ForeignKey(o.name(), k.columns(),
              k.referencedTable(), k.referencedColumns()))
          .orElse(k));
      List<SchemaModel.Check> checks = renamed(t.checks(), c -> !c.name().equals(
          SchemaModel.checkConstraintName(t.name(), c.expression())) ? c : old.checks().stream()
          .filter(o -> SchemaModel.sameCheckCondition(o.expression(), c.expression()))
          .findFirst().map(o -> new SchemaModel.Check(o.name(), c.expression()))
          .orElse(c));
      tables.add(new SchemaModel.Table(t.name(), t.columns(), key, indexes, keys, checks,
          t.comment()));
    }
    return new SchemaModel(tables, desired.sequences());
  }

  private static <T> List<T> renamed(List<T> items, UnaryOperator<T> rename) {
    return items.stream().map(rename).toList();
  }

  private static boolean same(List<String> a, List<String> b) {
    return a.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()
        .equals(b.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList());
  }

  private static String own(String name, String readable) {
    return name == null || MADE_UP_NAME.matcher(name).matches() ? readable : name;
  }

  private static String primaryKeyName(SchemaModel.Table table) {
    return SchemaModel.boundedName((table.name() + "_pkey").toLowerCase(Locale.ROOT));
  }

  private static String indexName(String table, SchemaModel.Index index) {
    return SchemaModel.boundedName(((index.unique() ? "uk_" : "ix_") + table + "_"
        + String.join("_", index.columns())).toLowerCase(Locale.ROOT));
  }

  private static String foreignKeyName(String table, SchemaModel.ForeignKey key) {
    return SchemaModel.boundedName(("fk_" + table + "_" + String.join("_", key.columns()))
        .toLowerCase(Locale.ROOT));
  }

  private static String sha256(String text) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(text.getBytes(StandardCharsets.UTF_8)));
  }
}
