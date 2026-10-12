package org.migrax.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL script helpers: statement splitting, {@code -- migrax:} directives and placeholders.
 *
 * @since 0.1.0
 */
public final class SqlScript {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
  private static final Pattern GO_LINE = Pattern.compile("(?i)\\s*GO\\s*");
  private static final Pattern SLASH_LINE = Pattern.compile("\\s*/\\s*");
  private static final Pattern DELIMITER_LINE = Pattern.compile("(?i)DELIMITER\\s+(\\S+)");
  private static final Pattern PLSQL_BLOCK = Pattern.compile("(?is)(?:CREATE\\s+(?:OR\\s+REPLACE"
      + "\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE)"
      + "|DECLARE|BEGIN)\\b");

  private SqlScript() {}

  /** True when a standalone {@code -- migrax:<name>} line is present. */
  public static boolean hasDirective(String sql, String name) {
    String wanted = "-- migrax:" + name;
    return sql.lines().anyMatch(line -> line.trim().equalsIgnoreCase(wanted));
  }

  /** Values of {@code -- migrax:<name> <value>} lines, e.g. the list after replaces. */
  public static List<String> directiveValues(String sql, String name) {
    String prefix = "-- migrax:" + name.toLowerCase(Locale.ROOT) + " ";
    List<String> values = new ArrayList<>();
    for (String line : sql.lines().toList()) {
      String trimmed = line.trim();
      if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix)) {
        for (String value : trimmed.substring(prefix.length()).split(",")) {
          if (!value.isBlank()) {
            values.add(value.trim());
          }
        }
      }
    }
    return values;
  }

  /**
   * Replaces {@code ${name}} with configured placeholder values. Unknown placeholders are left
   * as written, so SQL that happens to contain {@code ${...}} keeps working.
   */
  public static String substitute(String sql, Map<String, String> placeholders) {
    if (placeholders == null || placeholders.isEmpty() || !sql.contains("${")) {
      return sql;
    }
    Matcher matcher = PLACEHOLDER.matcher(sql);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String value = placeholders.get(matcher.group(1));
      matcher.appendReplacement(result,
          Matcher.quoteReplacement(value != null ? value : matcher.group()));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  /**
   * Splits a script into statements on semicolons outside quotes, comments and PostgreSQL
   * dollar-quoted bodies. Statements that contain only comments are dropped.
   *
   * <p>Scripts with stored procedures, functions and triggers use the separators of each
   * database's own tools:
   * <ul>
   *   <li>a line with only {@code GO} (SQL Server) ends a batch, which runs whole; semicolons
   *       inside it don't split;</li>
   *   <li>a {@code DELIMITER $$} line (MySQL, MariaDB) changes the delimiter until the next
   *       {@code DELIMITER} line; those lines are not sent;</li>
   *   <li>in a script with a line holding only {@code /} (Oracle), a PL/SQL block ({@code CREATE
   *       PROCEDURE}, {@code FUNCTION}, {@code PACKAGE}, {@code TRIGGER}, {@code TYPE}, or an
   *       anonymous {@code BEGIN} or {@code DECLARE} block) ends at that line, not at its
   *       semicolons;</li>
   *   <li>a {@code -- migrax:no-split} line sends the whole script as one statement.</li>
   * </ul>
   */
  public static List<String> split(String sql, boolean backslashEscapes) {
    if (hasDirective(sql, "no-split")) {
      List<String> whole = new ArrayList<>();
      addStatement(whole, new StringBuilder(sql));
      return whole;
    }
    boolean batches = sql.lines().anyMatch(line -> GO_LINE.matcher(line).matches());
    boolean slashBlocks = sql.lines().anyMatch(line -> SLASH_LINE.matcher(line).matches());
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    String dollarQuote = null;
    boolean lineComment = false;
    boolean blockComment = false;
    String delimiter = ";";
    // Whether the current statement has text besides comments, and whether it is PL/SQL.
    boolean started = false;
    boolean plsqlBlock = false;

    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
      boolean code = !lineComment && !blockComment && dollarQuote == null && quote == 0;

      if (code && (i == 0 || sql.charAt(i - 1) == '\n')) {
        int end = sql.indexOf('\n', i);
        end = end < 0 ? sql.length() : end + 1;
        String line = sql.substring(i, end).strip();
        Matcher delimiterLine = DELIMITER_LINE.matcher(line);
        boolean separator = (batches && GO_LINE.matcher(line).matches())
            || (slashBlocks && SLASH_LINE.matcher(line).matches());
        // DELIMITER is a command only between statements, not a column inside one.
        boolean newDelimiter = !started && delimiterLine.matches();
        if (separator || newDelimiter) {
          addStatement(statements, current);
          started = false;
          plsqlBlock = false;
          if (newDelimiter) {
            delimiter = delimiterLine.group(1);
          }
          i = end - 1;
          continue;
        }
      }
      if (code && !started && !Character.isWhitespace(ch) && !(ch == '-' && next == '-')
          && !(ch == '/' && next == '*')) {
        started = true;
        plsqlBlock = slashBlocks && PLSQL_BLOCK.matcher(sql).region(i, sql.length()).lookingAt();
      }

      if (lineComment) {
        current.append(ch);
        if (ch == '\n' || ch == '\r') {
          lineComment = false;
        }
        continue;
      }
      if (blockComment) {
        current.append(ch);
        if (ch == '*' && next == '/') {
          current.append(next);
          i++;
          blockComment = false;
        }
        continue;
      }
      if (dollarQuote != null) {
        if (sql.startsWith(dollarQuote, i)) {
          current.append(dollarQuote);
          i += dollarQuote.length() - 1;
          dollarQuote = null;
        } else {
          current.append(ch);
        }
        continue;
      }
      if (quote != 0) {
        current.append(ch);
        if (backslashEscapes && ch == '\\' && quote == '\'' && next != 0) {
          current.append(next);
          i++;
        } else if (ch == quote) {
          if (next == quote) {
            current.append(next);
            i++;
          } else {
            quote = 0;
          }
        }
        continue;
      }

      if (!delimiter.equals(";") && sql.startsWith(delimiter, i)) {
        addStatement(statements, current);
        started = false;
        i += delimiter.length() - 1;
      } else if (ch == '-' && next == '-') {
        current.append(ch).append(next);
        i++;
        lineComment = true;
      } else if (ch == '/' && next == '*') {
        current.append(ch).append(next);
        i++;
        blockComment = true;
      } else if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
        current.append(ch);
        quote = ch == '[' ? ']' : ch;
      } else if (ch == '$') {
        int delimiterEnd = sql.indexOf('$', i + 1);
        if (delimiterEnd >= 0) {
          String candidate = sql.substring(i, delimiterEnd + 1);
          if (candidate.matches("\\$[A-Za-z0-9_]*\\$")) {
            dollarQuote = candidate;
            current.append(candidate);
            i = delimiterEnd;
          } else {
            current.append(ch);
          }
        } else {
          current.append(ch);
        }
      } else if (ch == ';' && delimiter.equals(";") && !batches && !plsqlBlock) {
        addStatement(statements, current);
        started = false;
      } else {
        current.append(ch);
      }
    }
    addStatement(statements, current);
    if (quote != 0 || blockComment || dollarQuote != null) {
      throw new IllegalArgumentException("Unterminated quote or comment in SQL migration.");
    }
    return statements;
  }

  private static void addStatement(List<String> statements, StringBuilder current) {
    String statement = current.toString().trim();
    if (!statement.isEmpty() && !stripComments(statement).isBlank()) {
      statements.add(statement);
    }
    current.setLength(0);
  }

  /** The statement without comments, for checks such as linting. */
  public static String stripComments(String statement) {
    StringBuilder result = new StringBuilder();
    char quote = 0;
    for (int i = 0; i < statement.length(); i++) {
      char ch = statement.charAt(i);
      char next = i + 1 < statement.length() ? statement.charAt(i + 1) : 0;
      if (quote != 0) {
        result.append(ch);
        if (ch == quote) {
          quote = 0;
        }
      } else if (ch == '-' && next == '-') {
        int end = statement.indexOf('\n', i);
        if (end < 0) {
          break;
        }
        i = end - 1;
      } else if (ch == '/' && next == '*') {
        int end = statement.indexOf("*/", i + 2);
        if (end < 0) {
          break;
        }
        i = end + 1;
        result.append(' ');
      } else {
        if (ch == '\'' || ch == '"' || ch == '`') {
          quote = ch;
        }
        result.append(ch);
      }
    }
    return result.toString();
  }
}
