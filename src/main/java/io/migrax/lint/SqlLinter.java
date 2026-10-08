package io.migrax.lint;

import io.migrax.runner.SqlScript;

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
   */
  public record Finding(String code, Severity severity, String file, int statement,
                        String sql, String message, String fix) {}

  private static final String IDENT = "[\\w$#.\"`\\[\\]]+";
  private static final Pattern CREATE_TABLE =
      Pattern.compile("(?i)^CREATE\\s+(?:GLOBAL\\s+TEMPORARY\\s+|TEMPORARY\\s+)?TABLE\\s+"
          + "(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + IDENT + ")");
  private static final Pattern ALTER_TABLE =
      Pattern.compile("(?i)^ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?(" + IDENT + ")");
  private static final Pattern CREATE_INDEX = Pattern.compile(
      "(?i)^CREATE\\s+(UNIQUE\\s+)?INDEX\\s+(CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?"
          + "(?:" + IDENT + "\\s+)?ON\\s+(?:ONLY\\s+)?(" + IDENT + ")");
  private static final Pattern VOLATILE_DEFAULT = Pattern.compile(
      "(?i)DEFAULT\\s+(now\\(\\)|clock_timestamp\\(\\)|random\\(\\)|gen_random_uuid\\(\\)"
          + "|uuid_generate_v4\\(\\)|nextval\\(|timeofday\\(\\))");
  private static final Pattern IGNORE = Pattern.compile("(?i)migrax:lint-ignore\\s+([\\w, ]+)");
  private static final Pattern IGNORE_FILE =
      Pattern.compile("(?i)migrax:lint-ignore-file\\s+([\\w, ]+)");

  private SqlLinter() {}

  /**
   * Lints a migration script.
   *
   * @param file file name shown in findings
   * @param sql script text
   * @param dialect Migrax dialect id, e.g. postgresql
   */
  public static List<Finding> lint(String file, String sql, String dialect) {
    boolean postgres = "postgresql".equals(dialect);
    boolean transactional = !SqlScript.hasDirective(sql, "no-transaction");
    Set<String> fileIgnores = codes(IGNORE_FILE, sql);
    Set<String> createdTables = new HashSet<>();
    List<Finding> findings = new ArrayList<>();
    List<String> statements;
    try {
      statements = SqlScript.split(sql, "mysql".equals(dialect) || "mariadb".equals(dialect));
    } catch (IllegalArgumentException e) {
      findings.add(new Finding("MX000", Severity.ERROR, file, 0, "", e.getMessage(),
          "Close the quote or comment."));
      return findings;
    }

    for (int i = 0; i < statements.size(); i++) {
      String raw = statements.get(i);
      String text = SqlScript.stripComments(raw).replaceAll("\\s+", " ").trim();
      String upper = text.toUpperCase(Locale.ROOT);
      Set<String> ignores = new HashSet<>(fileIgnores);
      ignores.addAll(codes(IGNORE, raw));
      List<Finding> found = new ArrayList<>();
      int number = i + 1;

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
      if (index.find()) {
        boolean concurrent = index.group(2) != null;
        boolean onExisting = !createdTables.contains(normalize(index.group(3)));
        if (postgres && !concurrent && onExisting) {
          found.add(finding("MX001", Severity.WARNING, file, number, text,
              "Building this index blocks writes to " + normalize(index.group(3))
                  + " until it finishes.",
              "Use CREATE INDEX CONCURRENTLY in its own migration that starts with "
                  + "'-- migrax:no-transaction' ('migrax generate --safe' does this)."));
        }
        if (postgres && concurrent && transactional) {
          found.add(finding("MX012", Severity.ERROR, file, number, text,
              "CREATE INDEX CONCURRENTLY cannot run inside a transaction.",
              "Add a '-- migrax:no-transaction' line to this migration."));
        }
      }
      if (postgres && transactional
          && upper.matches("^(DROP INDEX CONCURRENTLY|REINDEX .*CONCURRENTLY|VACUUM).*")) {
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
      if (postgres && existing && upper.contains(" ADD ") && VOLATILE_DEFAULT.matcher(text).find()) {
        found.add(finding("MX011", Severity.WARNING, file, number, text,
            "A volatile default rewrites every row of " + table + " under an exclusive lock.",
            "Add the column without a default, set the default separately, then backfill "
                + "in batches."));
      }
      if (existing && (upper.matches(".* ALTER COLUMN \\S+ (SET DATA )?TYPE .*")
          || upper.matches(".* (MODIFY|CHANGE) (COLUMN )?.*")
          || "sqlserver".equals(dialect) && upper.matches(".* ALTER COLUMN \\S+ \\w+.*")
          && !upper.matches(".* ALTER COLUMN \\S+ (SET|DROP|ADD) .*"))) {
        found.add(finding("MX003", Severity.WARNING, file, number, text,
            "Changing a column type can rewrite or lock " + table + " and fails or truncates "
                + "values when the type gets narrower.",
            "Test on a production-size copy. For large tables add a new column, backfill it, "
                + "switch the application, then drop the old column."));
      }
      if (postgres && existing && upper.matches(".* ALTER COLUMN \\S+ SET NOT NULL.*")) {
        found.add(finding("MX004", Severity.WARNING, file, number, text,
            "SET NOT NULL scans all of " + table + " while holding an exclusive lock.",
            "First ADD CONSTRAINT ... CHECK (column IS NOT NULL) NOT VALID, then VALIDATE "
                + "CONSTRAINT in a later migration; PostgreSQL 12+ then sets NOT NULL without "
                + "a scan."));
      }
      if (postgres && existing && upper.contains(" FOREIGN KEY ")
          && upper.contains(" ADD CONSTRAINT ") && !upper.contains("NOT VALID")) {
        found.add(finding("MX005", Severity.WARNING, file, number, text,
            "Adding a foreign key checks every row while blocking writes to both tables.",
            "Add it with NOT VALID, then run ALTER TABLE ... VALIDATE CONSTRAINT in a later "
                + "migration ('migrax generate --safe' does this)."));
      }
      if (postgres && existing && upper.matches(".* ADD CONSTRAINT \\S+ UNIQUE .*")
          && !upper.contains("USING INDEX")) {
        found.add(finding("MX006", Severity.WARNING, file, number, text,
            "Adding a unique constraint builds its index while blocking writes to " + table + ".",
            "CREATE UNIQUE INDEX CONCURRENTLY first, then ADD CONSTRAINT ... UNIQUE USING "
                + "INDEX ('migrax generate --safe' does this)."));
      }
      if (postgres && existing && upper.matches(".* ADD CONSTRAINT \\S+ CHECK .*")
          && !upper.contains("NOT VALID")) {
        found.add(finding("MX014", Severity.WARNING, file, number, text,
            "Adding a check constraint scans " + table + " while holding an exclusive lock.",
            "Add it with NOT VALID, then VALIDATE CONSTRAINT in a later migration."));
      }
      if (postgres && existing && upper.matches(".* ADD (CONSTRAINT \\S+ )?PRIMARY KEY.*")) {
        found.add(finding("MX013", Severity.WARNING, file, number, text,
            "Adding a primary key builds an index while blocking writes to " + table + ".",
            "CREATE UNIQUE INDEX CONCURRENTLY, then ADD CONSTRAINT ... PRIMARY KEY USING INDEX."));
      }
      if (upper.matches("^DROP TABLE .*") || upper.matches(".* DROP (COLUMN )?(?!CONSTRAINT|INDEX|PRIMARY|FOREIGN|DEFAULT|NOT|IDENTITY|UNIQUE)\\S+.*")
          && upper.startsWith("ALTER TABLE")) {
        found.add(finding("MX007", Severity.WARNING, file, number, text,
            "This deletes data, and running application instances that still use it will fail.",
            "Deploy code that no longer uses it first, then drop it in a later release "
                + "(expand/contract)."));
      }
      if (upper.matches("^(RENAME TABLE|EXEC SP_RENAME).*")
          || upper.startsWith("ALTER TABLE") && upper.matches(".* RENAME (COLUMN |TO ).*")) {
        found.add(finding("MX008", Severity.WARNING, file, number, text,
            "Application instances still running the old code use the old name and will fail.",
            "Rename in steps: add the new name, write to both, switch reads, then remove the "
                + "old name. Or deploy during a maintenance window."));
      }
      if (upper.matches("^(UPDATE|DELETE FROM|DELETE) .*") && !upper.contains(" WHERE ")) {
        found.add(finding("MX009", Severity.WARNING, file, number, text,
            "This changes every row of the table in one transaction.",
            "Add a WHERE clause, or update large tables in batches."));
      }
      if (upper.startsWith("TRUNCATE")) {
        found.add(finding("MX010", Severity.WARNING, file, number, text,
            "TRUNCATE deletes all rows and cannot be undone by a rollback script.",
            "Make sure this is intended and backed up."));
      }
      if (("sqlserver".equals(dialect) || "oracle".equals(dialect)) && index.matches()
          && !upper.contains("ONLINE")) {
        found.add(finding("MX015", Severity.INFO, file, number, text,
            "Index builds lock the table unless they run online.",
            "Use WITH (ONLINE = ON) on SQL Server Enterprise or ONLINE on Oracle Enterprise "
                + "for large tables."));
      }
      for (Finding finding : found) {
        if (!ignores.contains(finding.code())) {
          findings.add(finding);
        }
      }
    }
    return findings;
  }

  private static Finding finding(String code, Severity severity, String file, int statement,
                                 String sql, String message, String fix) {
    String shortSql = sql.length() > 140 ? sql.substring(0, 137) + "..." : sql;
    return new Finding(code, severity, file, statement, shortSql, message, fix);
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
