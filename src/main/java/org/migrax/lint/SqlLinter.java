package org.migrax.lint;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.runner.SqlScript;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds migration statements that lock tables for a long time, fail on tables with data, or
 * break application instances that are still running, and suggests safer alternatives.
 *
 * <p>Suppress a finding with a {@code -- migrax:lint-ignore MX007} comment right before the
 * statement, or for the whole file with {@code -- migrax:lint-ignore-file MX007}.
 *
 * @since 0.1.0
 */
public final class SqlLinter {
  /** Finding severity. */
  public enum Severity { ERROR, WARNING, INFO }

  /**
   * One problem.
   *
   * @param statement 1-based statement number in the file
   * @param line 1-based line where the statement's SQL starts, or 0 when unknown (since
   *     0.20.0)
   * @param safeSql the safer SQL written with the statement's own names, ready to copy, or null
   *     (since 0.20.0)
   */
  public record Finding(String code, Severity severity, String file, int statement,
                        String sql, String message, String fix, int line, String safeSql) {

    /**
     * A finding without a line or safe SQL, as before 0.20.0.
     *
     * @since 0.20.0
     */
    public Finding(String code, Severity severity, String file, int statement, String sql,
                   String message, String fix) {
      this(code, severity, file, statement, sql, message, fix, 0, null);
    }
  }

  /** A name, maybe qualified or quoted, or with a placeholder such as ${migrax.schema}. */
  private static final String IDENT = "[\\w$#.\"`\\[\\]{}]+";
  /** A routine body that closed: it ends with END, or END and the routine's name. */
  private static final Pattern ENDS_WITH_END = Pattern.compile("(?is).*\\bEND(?:\\s+\\w+)?\\s*;?\\s*");
  private static final Pattern DOLLAR_QUOTED = Pattern.compile("(?s)\\$(\\w*)\\$.*?\\$\\1\\$");
  private static final Pattern STRING = Pattern.compile("'(?:[^']|'')*'");
  /**
   * A string or a quoted name: their spaces and line breaks are part of them. Safe SQL is only
   * written for PostgreSQL, where names are quoted with ".
   */
  private static final Pattern QUOTED = Pattern.compile(STRING.pattern() + "|\"[^\"]*\"");

  /**
   * Whether a procedure, function or trigger took in the statements after it: its text holds
   * semicolons outside quotes but doesn't end with END.
   */
  private static boolean swallowsStatements(String statement) {
    String text = STRING.matcher(DOLLAR_QUOTED.matcher(SqlScript.stripComments(statement))
        .replaceAll("")).replaceAll("''").strip();
    String body = text.endsWith(";") ? text.substring(0, text.length() - 1) : text;
    return body.contains(";") && !ENDS_WITH_END.matcher(text).matches();
  }
  private static final Pattern CREATE_TABLE =
      Pattern.compile("(?i)^CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+|TEMPORARY\\s+)?TABLE\\s+"
          + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + IDENT + ")");
  private static final Pattern ALTER_TABLE =
      Pattern.compile("(?i)^ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?(" + IDENT + ")");
  private static final Pattern CREATE_INDEX = Pattern.compile(
      "(?i)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?"
          + "(?:" + IDENT + "\\s+)?ON\\s+(?:ONLY\\s+)?(" + IDENT + ")");
  /** The ONLINE option of an index build, as a word (not part of a name), and not set OFF. */
  private static final Pattern ONLINE = Pattern.compile("\\bONLINE\\b(?!\\s*=\\s*OFF\\b)");
  /** The table or sequence a statement creates, changes or writes to. */
  private static final Pattern TABLE_STATEMENT = Pattern.compile("(?i)^(?:CREATE\\s+TABLE"
      + "|ALTER\\s+TABLE|DROP\\s+TABLE(?:\\s+IF\\s+EXISTS)?|TRUNCATE\\s+TABLE"
      + "|CREATE\\s+(?:UNIQUE\\s+)?(?:(?:NON)?CLUSTERED\\s+)?INDEX\\s+\\S+\\s+ON"
      + "|INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|(?:CREATE|ALTER|DROP)\\s+SEQUENCE)"
      + "\\s+([^\\s(]+)");
  private static final Pattern REFERENCES = Pattern.compile("(?i)\\bREFERENCES\\s+([^\\s(]+)");
  private static final Pattern VOLATILE_DEFAULT = Pattern.compile(
      "(?i)DEFAULT\\s+(now\\(\\)|clock_timestamp\\(\\)|random\\(\\)|gen_random_uuid\\(\\)"
          + "|uuid_generate_v4\\(\\)|nextval\\(|timeofday\\(\\))");
  private static final String ALTER_HEAD =
      "(?i)^ALTER TABLE (?:IF EXISTS )?(?:ONLY )?(" + IDENT + ") ";
  private static final Pattern SET_NOT_NULL = Pattern.compile(ALTER_HEAD
      + "ALTER (?:COLUMN )?(" + IDENT + ") SET NOT NULL$");
  /** ADD CONSTRAINT name FOREIGN KEY / CHECK / UNIQUE / PRIMARY KEY, and what follows. */
  private static final Pattern ADD_CONSTRAINT = Pattern.compile(ALTER_HEAD
      + "ADD CONSTRAINT (" + IDENT + ") (FOREIGN KEY|CHECK|UNIQUE|PRIMARY KEY)\\b ?(.*)$");
  private static final Pattern ADD_PRIMARY_KEY = Pattern.compile(ALTER_HEAD
      + "ADD PRIMARY KEY ?(\\([^()]*\\))$");
  private static final Pattern COLUMN_LIST = Pattern.compile("^\\([^()]*\\)$");
  /** A second action in one ALTER TABLE: the safe SQL is then left to the author. */
  private static final Pattern MORE_ACTIONS = Pattern.compile("(?i),\\s*(?:ADD|DROP|ALTER"
      + "|VALIDATE|RENAME|SET|RESET|OWNER|ENABLE|DISABLE|INHERIT|NO|CLUSTER|REPLICA|FORCE"
      + "|ATTACH|DETACH)\\b");
  private static final Pattern CREATE_INDEX_HEAD =
      Pattern.compile("(?i)^(CREATE (?:UNIQUE )?INDEX) (?!CONCURRENTLY\\b)");
  private static final Pattern REINDEX_HEAD = Pattern.compile(
      "(?i)^(REINDEX (?:\\([^)]*\\) )?(?:INDEX|TABLE|SCHEMA|DATABASE)) (?!CONCURRENTLY\\b)");
  private static final Pattern DROP_INDEX_HEAD =
      Pattern.compile("(?i)^(DROP INDEX) (?!CONCURRENTLY\\b)");
  private static final Pattern REFRESH_HEAD =
      Pattern.compile("(?i)^(REFRESH MATERIALIZED VIEW) (?!CONCURRENTLY\\b)");
  private static final Pattern DETACH = Pattern.compile(ALTER_HEAD
      + "DETACH PARTITION (" + IDENT + ")( CONCURRENTLY| FINALIZE)?$");
  private static final String NO_TRANSACTION = "-- migrax:no-transaction\n";
  private static final String LATER = "-- In a later migration:\n";
  private static final Pattern IGNORE = Pattern.compile("(?i)migrax:lint-ignore\\s+([\\w, ]+)");
  private static final Pattern IGNORE_FILE =
      Pattern.compile("(?i)migrax:lint-ignore-file\\s+([\\w, ]+)");

  private SqlLinter() {}

  /**
   * {@code ALTER TABLE t RENAME COLUMN migrax_tmp_... TO c}: the last step of Oracle's smaller
   * NUMBER through a new column, which copied c's values first.
   */
  private static final Pattern NEW_COLUMN_RENAME = Pattern.compile(
      "(?i)^ALTER TABLE \"?(\\w+)\"? RENAME COLUMN \"?migrax_tmp_\\w+\"? TO \"?(\\w+)\"?\\s*$");

  /** {@code UPDATE t SET migrax_tmp_... = c}: the copy before that rename. */
  private static final Pattern NEW_COLUMN_COPY = Pattern.compile(
      "(?i)^UPDATE \"?(\\w+)\"? SET \"?migrax_tmp_\\w+\"? = \"?(\\w+)\"?\\s*$");

  /** {@code ALTER TABLE t DROP COLUMN c}, with both names captured. */
  private static final Pattern DROP_COLUMN = Pattern.compile(
      "(?i)^ALTER TABLE \"?(\\w+)\"? DROP COLUMN \"?(\\w+)\"?\\s*$");

  /** {@code ALTER TABLE migrax_new_x RENAME TO x}: the last step of a SQLite table rebuild. */
  private static final Pattern REBUILD_RENAME = Pattern.compile(
      "(?i)^ALTER TABLE \"?migrax_new_\\w+\"? RENAME TO \"?(\\w+)\"?\\s*$");

  /**
   * Lints a migration script.
   *
   * @param file file name shown in findings
   * @param sql script text
   * @param dialect Migrax dialect id, e.g. postgresql
   */
  public static List<Finding> lint(String file, String sql, String dialect) {
    return lint(file, sql, dialect, false);
  }

  /**
   * Lints a migration script of a project that may run its migrations once per schema.
   *
   * @param file file name shown in findings
   * @param sql script text
   * @param dialect Migrax dialect id, e.g. postgresql
   * @param schemas whether the project sets {@code migrax.schemas}: on SQL Server, every table
   *     then needs its schema in front (MX017)
   * @since 0.7.0
   */
  public static List<Finding> lint(String file, String sql, String dialect, boolean schemas) {
    // Exact ids only: an alias (postgres) or an unknown id gets the checks that hold on every
    // database, as it always has.
    Dialect database = Dialects.NAMES.contains(dialect) ? Dialects.byName(dialect) : null;
    boolean namedSchemas = schemas && database != null && !database.switchesSchemas();
    boolean lockChecks = database != null && database.lintsLockingDdl();
    boolean alterColumnType = database != null && database.changesTypesWithAlterColumn();
    boolean onlineIndexes = database != null && database.hasOnlineIndexOption();
    boolean throughNewColumn = database != null && database.changesTypesThroughNewColumn();
    boolean transactional = !SqlScript.hasDirective(sql, "no-transaction");
    Set<String> fileIgnores = codes(IGNORE_FILE, sql);
    Set<String> createdTables = new HashSet<>();
    // A BEGIN or START TRANSACTION was written in this migration: a later END is its COMMIT.
    boolean openedTransaction = false;
    List<Finding> findings = new ArrayList<>();
    List<String> statements;
    try {
      statements = SqlScript.split(sql, database != null && database.backslashEscapes());
    } catch (IllegalArgumentException e) {
      findings.add(new Finding("MX000", Severity.ERROR, file, 0, "", e.getMessage(),
          "Close the quote or comment."));
      return findings;
    }

    // Tables a SQLite rebuild replaces with a copy of themselves: dropping the original and
    // renaming the copy back keeps the rows and the name.
    Set<String> rebuilt = new HashSet<>();
    for (String statement : statements) {
      Matcher copy = REBUILD_RENAME.matcher(SqlScript.stripComments(statement).trim());
      if (copy.find()) {
        rebuilt.add(normalize(copy.group(1)));
      }
    }
    // Columns Oracle moved through a new column: the old one goes only after its values were
    // copied into the new one, which then takes its name.
    Set<String> copied = new HashSet<>();
    Set<String> moved = new HashSet<>();
    for (String statement : throughNewColumn ? statements : List.<String>of()) {
      String text = SqlScript.stripComments(statement).replaceAll("\\s+", " ").trim();
      Matcher copy = NEW_COLUMN_COPY.matcher(text);
      Matcher column = NEW_COLUMN_RENAME.matcher(text);
      if (copy.find()) {
        copied.add(normalize(copy.group(1)) + "." + normalize(copy.group(2)));
      } else if (column.find()
          && copied.contains(normalize(column.group(1)) + "." + normalize(column.group(2)))) {
        moved.add(normalize(column.group(1)) + "." + normalize(column.group(2)));
      }
    }

    int[] lines = statementLines(sql, statements);
    for (int i = 0; i < statements.size(); i++) {
      String raw = statements.get(i);
      String text = SqlScript.stripComments(raw).replaceAll("\\s+", " ").trim();
      String upper = text.toUpperCase(Locale.ROOT);
      Set<String> ignores = new HashSet<>(fileIgnores);
      ignores.addAll(codes(IGNORE, raw));
      List<Finding> found = new ArrayList<>();
      int number = i + 1;

      if (SqlScript.isRoutine(raw) && swallowsStatements(raw) && !ignores.contains("MX016")) {
        findings.add(finding("MX016", Severity.WARNING, file, number, text,
            "This procedure, function, trigger or block seems to include the statements after it: "
                + "its body has an unmatched BEGIN, or it has none.",
            "Check that every BEGIN has its END, or end the body with GO (SQL Server), a "
                + "DELIMITER line (MySQL, MariaDB) or a line with only / (Oracle, SQLite)."));
      }
      if (namedSchemas && !ignores.contains("MX017")) {
        String unqualified = unqualifiedTable(text);
        if (unqualified != null) {
          findings.add(finding("MX017", Severity.WARNING, file, number, text,
              "Table " + unqualified + " has no schema in front. SQL Server can't switch "
                  + "schemas, so with migrax.schemas it is the login's default schema's table.",
              "Write ${migrax.schema}." + unqualified + ": Migrax fills in each schema."));
        }
      }
      boolean control = transactional
          && SqlScript.isTransactionControl(raw, openedTransaction);
      if (control && SqlScript.opensTransaction(raw)) {
        openedTransaction = true;
      }
      if (control && !ignores.contains("MX018")) {
        // Migrax runs the statements and records the migration in one transaction.
        findings.add(finding("MX018", Severity.WARNING, file, number, text,
            "Migrax already runs this migration in one transaction and records it there. A "
                + "transaction of its own breaks that: after a COMMIT, a failure can leave the "
                + "statements before it applied and the migration not recorded; a ROLLBACK "
                + "undoes them while the migration is still recorded as applied.",
            "Remove it: Migrax commits the migration once all of it succeeded. For statements "
                + "that cannot run in a transaction, add a '-- migrax:no-transaction' line "
                + "instead."));
      }
      Matcher create = CREATE_TABLE.matcher(text);
      if (create.find()) {
        createdTables.add(normalize(create.group(1)));
        continue;
      }
      String table = null;
      Matcher alter = ALTER_TABLE.matcher(text);
      if (alter.find()) {
        table = normalize(alter.group(1));
      }
      boolean existing = table != null && !createdTables.contains(table);

      Matcher index = CREATE_INDEX.matcher(text);
      boolean indexOnExisting = false;
      if (index.find()) {
        boolean concurrent = index.group(2) != null;
        boolean onExisting = !createdTables.contains(normalize(index.group(3)));
        indexOnExisting = onExisting;
        if (lockChecks && !concurrent && onExisting) {
          found.add(finding("MX001", Severity.WARNING, file, number, text,
              "Building this index blocks writes to " + normalize(index.group(3))
                  + " until it finishes.",
              "Use CREATE INDEX CONCURRENTLY in its own migration that starts with "
                  + "'-- migrax:no-transaction' ('migrax generate --safe' does this).",
              concurrently(CREATE_INDEX_HEAD, text)));
        }
        if (lockChecks && concurrent && transactional) {
          found.add(finding("MX012", Severity.ERROR, file, number, text,
              "CREATE INDEX CONCURRENTLY cannot run inside a transaction.",
              "Add a '-- migrax:no-transaction' line to this migration."));
        }
      }
      if (lockChecks && transactional
          && (upper.matches("^(DROP INDEX CONCURRENTLY|REINDEX .*CONCURRENTLY|VACUUM).*")
              || upper.matches("^ALTER TABLE .* DETACH PARTITION \\S+ CONCURRENTLY;?$"))) {
        found.add(finding("MX012", Severity.ERROR, file, number, text,
            "This statement cannot run inside a transaction.",
            "Add a '-- migrax:no-transaction' line to this migration."));
      }

      if (existing && upper.matches(".* ADD (COLUMN )?.*") && !upper.contains(" ADD CONSTRAINT")
          && upper.contains(" NOT NULL") && !upper.contains(" DEFAULT ")
          && !upper.contains("IDENTITY") && !upper.contains("AUTO_INCREMENT")
          && !upper.contains(" GENERATED ")) {
        found.add(finding("MX002", Severity.WARNING, file, number, text,
            "Adding a NOT NULL column without a default fails when " + table + " has rows.",
            "Add a DEFAULT, or add the column as nullable, backfill it, then make it NOT NULL."));
      }
      if (lockChecks && existing && upper.contains(" ADD ")
          && VOLATILE_DEFAULT.matcher(text).find()) {
        found.add(finding("MX011", Severity.WARNING, file, number, text,
            "A volatile default rewrites every row of " + table + " under an exclusive lock.",
            "Add the column without a default, set the default separately, then backfill "
                + "in batches."));
      }
      if (existing && (upper.matches(".* ALTER COLUMN \\S+ (SET DATA )?TYPE .*")
          || upper.matches(".* (MODIFY|CHANGE) (COLUMN )?.*")
              // Oracle's MODIFY of only the default or NULL / NOT NULL keeps the type.
              && !upper.matches(".* MODIFY \\(\\S+( DEFAULT \\S+)?( (NOT )?NULL)?\\)")
          || alterColumnType && upper.matches(".* ALTER COLUMN \\S+ \\w+.*")
          && !upper.matches(".* ALTER COLUMN \\S+ (SET|DROP|ADD) .*"))) {
        found.add(finding("MX003", Severity.WARNING, file, number, text,
            "Changing a column type can rewrite or lock " + table + " and fails or truncates "
                + "values when the type gets narrower.",
            "Test on a production-size copy. For large tables add a new column, backfill it, "
                + "switch the application, then drop the old column."));
      }
      if (lockChecks && existing && upper.matches(".* ALTER COLUMN \\S+ SET NOT NULL.*")) {
        found.add(finding("MX004", Severity.WARNING, file, number, text,
            "SET NOT NULL scans all of " + table + " while holding an exclusive lock.",
            "First ADD CONSTRAINT ... CHECK (column IS NOT NULL) NOT VALID, then VALIDATE "
                + "CONSTRAINT in a later migration; PostgreSQL 12+ then sets NOT NULL without "
                + "a scan.", setNotNullSafely(text)));
      }
      if (lockChecks && existing && upper.contains(" FOREIGN KEY ")
          && upper.contains(" ADD CONSTRAINT ") && !upper.contains("NOT VALID")) {
        found.add(finding("MX005", Severity.WARNING, file, number, text,
            "Adding a foreign key checks every row while blocking writes to both tables.",
            "Add it with NOT VALID, then run ALTER TABLE ... VALIDATE CONSTRAINT in a later "
                + "migration ('migrax generate --safe' does this).", notValid(text)));
      }
      if (lockChecks && existing && upper.matches(".* ADD CONSTRAINT \\S+ UNIQUE .*")
          && !upper.contains("USING INDEX")) {
        found.add(finding("MX006", Severity.WARNING, file, number, text,
            "Adding a unique constraint builds its index while blocking writes to " + table + ".",
            "CREATE UNIQUE INDEX CONCURRENTLY first, then ADD CONSTRAINT ... UNIQUE USING "
                + "INDEX ('migrax generate --safe' does this).", usingIndex(text)));
      }
      if (lockChecks && existing && upper.matches(".* ADD CONSTRAINT \\S+ CHECK .*")
          && !upper.contains("NOT VALID")) {
        found.add(finding("MX014", Severity.WARNING, file, number, text,
            "Adding a check constraint scans " + table + " while holding an exclusive lock.",
            "Add it with NOT VALID, then VALIDATE CONSTRAINT in a later migration.",
            notValid(text)));
      }
      if (lockChecks && existing && upper.matches(".* ADD (CONSTRAINT \\S+ )?PRIMARY KEY.*")
          && !upper.contains("USING INDEX")) {
        found.add(finding("MX013", Severity.WARNING, file, number, text,
            "Adding a primary key builds an index while blocking writes to " + table + ".",
            "CREATE UNIQUE INDEX CONCURRENTLY, then ADD CONSTRAINT ... PRIMARY KEY USING INDEX.",
            usingIndex(text)));
      }
      boolean rebuildDrop = upper.matches("^DROP TABLE .*")
          && rebuilt.contains(normalize(text.substring("DROP TABLE ".length()).trim()));
      Matcher dropColumn = DROP_COLUMN.matcher(text);
      boolean movedDrop = dropColumn.matches() && moved.contains(
          normalize(dropColumn.group(1)) + "." + normalize(dropColumn.group(2)));
      if (!rebuildDrop && upper.matches("^DROP TABLE .*") || !movedDrop && upper.matches(
              ".* DROP (COLUMN )?(?!CONSTRAINT|INDEX|PRIMARY|FOREIGN|DEFAULT|NOT|IDENTITY|UNIQUE)"
                  + "\\S+.*")
          && upper.startsWith("ALTER TABLE")) {
        found.add(finding("MX007", Severity.WARNING, file, number, text,
            "This deletes data, and running application instances that still use it will fail.",
            "Deploy code that no longer uses it first, then drop it in a later release "
                + "(expand/contract: 'migrax generate --zero-downtime' does this)."));
      }
      Matcher renamed = NEW_COLUMN_RENAME.matcher(text);
      boolean rebuildCopy = REBUILD_RENAME.matcher(text).find() || (renamed.find()
          && moved.contains(normalize(renamed.group(1)) + "." + normalize(renamed.group(2))));
      if (!rebuildCopy && (upper.matches("^(RENAME TABLE|EXEC SP_RENAME).*")
          || upper.startsWith("ALTER TABLE") && upper.matches(".* RENAME (COLUMN |TO ).*")
          || upper.matches("^ALTER (SCHEMA|VIEW|MATERIALIZED VIEW|SEQUENCE) .* RENAME TO .*"))) {
        found.add(finding("MX008", Severity.WARNING, file, number, text,
            "Application instances still running the old code use the old name and will fail.",
            "Rename in steps: add the new name, write to both, switch reads, then remove the "
                + "old name ('migrax generate --zero-downtime' does this). Or deploy during a "
                + "maintenance window."));
      }
      if (upper.matches("^UPDATE .* SET MIGRAX_TMP_[0-9A-F]+ = .*")) {
        // Oracle's smaller NUMBER, copied into a new column: one warning for the whole move.
        found.add(finding("MX003", Severity.WARNING, file, number, text,
            "Making the column smaller rewrites " + table + " through a new column, which "
                + "takes time and space on a large table; while the old column is dropped, "
                + "writes to the table fail for a moment.",
            "Test on a production-size copy and run it when the table is quiet."));
      } else if (upper.matches("^(UPDATE|DELETE FROM|DELETE) .*") && !upper.contains(" WHERE ")) {
        found.add(finding("MX009", Severity.WARNING, file, number, text,
            "This changes every row of the table in one transaction.",
            "Add a WHERE clause, or update large tables in batches."));
      }
      if (upper.startsWith("TRUNCATE")) {
        found.add(finding("MX010", Severity.WARNING, file, number, text,
            "TRUNCATE deletes all rows and cannot be undone by a rollback script.",
            "Make sure this is intended and backed up."));
      }
      if (lockChecks) {
        lintPostgresLocks(found, file, number, text, upper);
      }
      if (onlineIndexes && indexOnExisting && !ONLINE.matcher(upper).find()) {
        found.add(finding("MX015", Severity.INFO, file, number, text,
            "Index builds lock the table unless they run online.",
            "Use WITH (ONLINE = ON) on SQL Server Enterprise or ONLINE on Oracle Enterprise "
                + "for large tables."));
      }
      boolean literalSpacing = hasSpacedLiteral(raw);
      for (Finding finding : found) {
        if (literalSpacing && finding.safeSql() != null) {
          // The safe SQL is built from the statement with its spaces collapsed.
          finding = new Finding(finding.code(), finding.severity(), finding.file(),
              finding.statement(), finding.sql(), finding.message(), finding.fix(), 0, null);
        }
        if (!ignores.contains(finding.code())) {
          findings.add(finding);
        }
      }
    }
    List<Finding> located = new ArrayList<>();
    for (Finding f : findings) {
      int line = f.statement() >= 1 && f.statement() <= lines.length ? lines[f.statement() - 1]
          : 0;
      located.add(new Finding(f.code(), f.severity(), f.file(), f.statement(), f.sql(),
          f.message(), f.fix(), line, f.safeSql()));
    }
    return located;
  }

  /**
   * PostgreSQL statements that have a form that doesn't block (MX020), and statements that
   * rewrite a whole table under an exclusive lock (MX021).
   */
  private static void lintPostgresLocks(List<Finding> found, String file, int number,
                                        String text, String upper) {
    if (upper.startsWith("REINDEX ") && !upper.contains("CONCURRENTLY")
        && !upper.matches("^REINDEX (\\([^)]*\\) )?SYSTEM .*")) {
      found.add(finding("MX020", Severity.WARNING, file, number, text,
          "REINDEX blocks writes to the table, and queries that use the index, until it "
              + "finishes.",
          "Use REINDEX ... CONCURRENTLY (PostgreSQL 12+) in a migration that starts with "
              + "'-- migrax:no-transaction'.", concurrently(REINDEX_HEAD, text)));
    } else if (upper.startsWith("DROP INDEX ") && !upper.startsWith("DROP INDEX CONCURRENTLY")) {
      // CONCURRENTLY takes one index and no CASCADE.
      boolean oneIndex = !text.contains(",") && !upper.matches(".*\\bCASCADE\\b.*");
      found.add(finding("MX020", Severity.WARNING, file, number, text,
          "DROP INDEX waits for every query on the table and then blocks all of them until it "
              + "is done.",
          "Use DROP INDEX CONCURRENTLY in a migration that starts with "
              + "'-- migrax:no-transaction'. On a small, quiet table the lock is short: then "
              + "'-- migrax:lint-ignore MX020' accepts it.",
          oneIndex ? concurrently(DROP_INDEX_HEAD, text) : null));
    } else if (upper.startsWith("REFRESH MATERIALIZED VIEW ")
        && !upper.startsWith("REFRESH MATERIALIZED VIEW CONCURRENTLY")
        // WITH NO DATA only empties the view: the lock is short, and CONCURRENTLY can't.
        && !upper.matches(".*\\bWITH NO DATA\\b.*")) {
      found.add(finding("MX020", Severity.WARNING, file, number, text,
          "Refreshing a materialized view blocks reads of it until the refresh finishes.",
          "Use REFRESH MATERIALIZED VIEW CONCURRENTLY; the view needs a unique index on "
              + "plain columns for that.", concurrently(REFRESH_HEAD, text, false)));
    } else {
      Matcher detach = DETACH.matcher(bare(text));
      if (detach.find() && detach.group(3) == null) {
        found.add(finding("MX020", Severity.WARNING, file, number, text,
            "Detaching a partition blocks every query on " + normalize(detach.group(1))
                + " until it is done.",
            "Use DETACH PARTITION ... CONCURRENTLY (PostgreSQL 14+) in a migration that starts "
                + "with '-- migrax:no-transaction'.",
            NO_TRANSACTION + bare(text) + " CONCURRENTLY;"));
      }
    }
    if (upper.matches("^VACUUM (\\([^)]*\\bFULL\\b[^)]*\\)|FULL\\b).*")
        || upper.matches("^CLUSTER\\b.*")
        || upper.matches("^ALTER (TABLE|INDEX|MATERIALIZED VIEW) .* SET (TABLESPACE|LOGGED"
            + "|UNLOGGED)\\b.*")) {
      found.add(finding("MX021", Severity.WARNING, file, number, text,
          "This rewrites the whole table while holding an exclusive lock: reads and writes "
              + "wait until it finishes.",
          "Run it in a maintenance window, or rebuild the table online with an extension such "
              + "as pg_repack (for VACUUM FULL and CLUSTER)."));
    }
  }

  /** Whether a string or quoted name holds a line break or several spaces in a row. */
  private static boolean hasSpacedLiteral(String raw) {
    Matcher literal = QUOTED.matcher(SqlScript.stripComments(raw));
    while (literal.find()) {
      if (literal.group().matches("(?s).*(\\s\\s|[\\r\\n\\t]).*")) {
        return true;
      }
    }
    return false;
  }

  /** The statement without its closing semicolon. */
  private static String bare(String text) {
    String trimmed = text.strip();
    return trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1).strip() : trimmed;
  }

  /** The statement with CONCURRENTLY after its head, in a no-transaction migration. */
  private static String concurrently(Pattern head, String text) {
    return concurrently(head, text, true);
  }

  private static String concurrently(Pattern head, String text, boolean noTransaction) {
    Matcher matcher = head.matcher(bare(text));
    if (!matcher.find()) {
      return null;
    }
    return (noTransaction ? NO_TRANSACTION : "")
        + matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1)) + " CONCURRENTLY ")
        + ";";
  }

  /** SET NOT NULL through a NOT VALID check, validated later. */
  private static String setNotNullSafely(String text) {
    Matcher matcher = SET_NOT_NULL.matcher(bare(text));
    if (!matcher.find()) {
      return null;
    }
    String table = matcher.group(1);
    String column = matcher.group(2);
    String check = constraintName(normalize(table) + "_" + normalize(column) + "_not_null");
    String alter = "ALTER TABLE " + table + " ";
    return alter + "ADD CONSTRAINT " + check + " CHECK (" + column + " IS NOT NULL) NOT VALID;\n"
        + LATER
        + alter + "VALIDATE CONSTRAINT " + check + ";\n"
        + "-- migrax:lint-ignore MX004\n"
        + alter + "ALTER COLUMN " + column + " SET NOT NULL;\n"
        + alter + "DROP CONSTRAINT " + check + ";";
  }

  /** A foreign key or check added NOT VALID, validated later. */
  private static String notValid(String text) {
    String statement = bare(text);
    Matcher matcher = ADD_CONSTRAINT.matcher(statement);
    if (!matcher.find() || MORE_ACTIONS.matcher(statement).find()
        || !matcher.group(3).toUpperCase(Locale.ROOT).matches("FOREIGN KEY|CHECK")) {
      return null;
    }
    return statement + " NOT VALID;\n" + LATER
        + "ALTER TABLE " + matcher.group(1) + " VALIDATE CONSTRAINT " + matcher.group(2) + ";";
  }

  /** A unique constraint or primary key on an index built CONCURRENTLY first. */
  private static String usingIndex(String text) {
    String statement = bare(text);
    String table;
    String name;
    String kind;
    String columns;
    Matcher named = ADD_CONSTRAINT.matcher(statement);
    Matcher primary = ADD_PRIMARY_KEY.matcher(statement);
    if (named.find()) {
      table = named.group(1);
      name = named.group(2);
      kind = named.group(3).toUpperCase(Locale.ROOT);
      columns = named.group(4).strip();
      if (!kind.equals("UNIQUE") && !kind.equals("PRIMARY KEY")
          || !COLUMN_LIST.matcher(columns).matches()) {
        return null;
      }
    } else if (primary.find()) {
      table = primary.group(1);
      name = constraintName(normalize(table) + "_pkey");
      kind = "PRIMARY KEY";
      columns = primary.group(2);
    } else {
      return null;
    }
    return NO_TRANSACTION
        + "CREATE UNIQUE INDEX CONCURRENTLY " + name + " ON " + table + " " + columns + ";\n"
        + "-- In the next migration, without no-transaction:\n"
        + "ALTER TABLE " + table + " ADD CONSTRAINT " + name + " " + kind + " USING INDEX "
        + name + ";";
  }

  /** PostgreSQL keeps the first 63 bytes of a name. */
  private static String constraintName(String name) {
    return name.length() > 63 ? name.substring(0, 63) : name;
  }

  /**
   * The line each statement's SQL starts on, after the comments before it; 0 when the statement
   * can't be found in the file as written (a placeholder or a rewritten batch).
   */
  static int[] statementLines(String sql, List<String> statements) {
    int[] lines = new int[statements.size()];
    int from = 0;
    int line = 1;
    int counted = 0;
    for (int i = 0; i < statements.size(); i++) {
      String raw = statements.get(i).strip();
      int at = raw.isEmpty() ? -1 : sql.indexOf(raw, from);
      if (at < 0) {
        continue;
      }
      int start = at;
      for (String part : raw.split("\n", -1)) {
        String trimmed = part.strip();
        if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
          start = Math.max(at, sql.indexOf(trimmed, start));
          break;
        }
        start += part.length() + 1;
      }
      for (int c = counted; c < start; c++) {
        if (sql.charAt(c) == '\n') {
          line++;
        }
      }
      counted = start;
      lines[i] = line;
      from = at + raw.length();
    }
    return lines;
  }

  /**
   * The first table a statement creates, changes, writes to or references without a schema in
   * front, or null. Temporary tables ({@code #t}) and table variables ({@code @t}) don't count.
   */
  private static String unqualifiedTable(String text) {
    List<String> names = new ArrayList<>();
    Matcher target = TABLE_STATEMENT.matcher(text);
    if (target.find()) {
      names.add(target.group(1));
    }
    Matcher references = REFERENCES.matcher(text);
    while (references.find()) {
      names.add(references.group(1));
    }
    return names.stream().filter(name -> !name.contains(".") && !name.startsWith("#")
        && !name.startsWith("@")).findFirst().orElse(null);
  }

  private static Finding finding(String code, Severity severity, String file, int statement,
                                 String sql, String message, String fix) {
    return finding(code, severity, file, statement, sql, message, fix, null);
  }

  private static Finding finding(String code, Severity severity, String file, int statement,
                                 String sql, String message, String fix, String safeSql) {
    String shortSql = sql.length() > 140 ? sql.substring(0, 137) + "..." : sql;
    return new Finding(code, severity, file, statement, shortSql, message, fix, 0, safeSql);
  }

  private static Set<String> codes(Pattern pattern, String text) {
    Set<String> codes = new HashSet<>();
    Matcher matcher = pattern.matcher(text);
    while (matcher.find()) {
      for (String code : matcher.group(1).split("[,\\s]+")) {
        if (!code.isBlank()) {
          codes.add(code.trim().toUpperCase(Locale.ROOT));
        }
      }
    }
    return codes;
  }

  private static String normalize(String name) {
    String bare = name.replaceAll("[\"`\\[\\]]", "");
    int dot = bare.lastIndexOf('.');
    return (dot >= 0 ? bare.substring(dot + 1) : bare).toLowerCase(Locale.ROOT);
  }
}
