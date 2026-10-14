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
  /** {@code GO}, or {@code GO 5} to run the batch five times. */
  private static final Pattern GO_LINE = Pattern.compile("(?i)\\s*GO(?:\\s+(\\d+))?\\s*");
  private static final Pattern SLASH_LINE = Pattern.compile("\\s*/\\s*");
  private static final Pattern DELIMITER_LINE = Pattern.compile("(?i)DELIMITER\\s+(\\S+)");
  private static final Pattern PLSQL_BLOCK = Pattern.compile("(?is)(?:CREATE\\s+(?:OR\\s+REPLACE"
      + "\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE)"
      + "|DECLARE|BEGIN)\\b");

  /** A statement that creates a procedure, function, trigger or event, whose body has semicolons. */
  private static final Pattern ROUTINE = Pattern.compile("(?is)CREATE\\s+(?:OR\\s+(?:REPLACE|ALTER)"
      + "\\s+)?(?:DEFINER\\s*=\\s*\\S+\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?"
      + "(?:PROCEDURE|PROC|FUNCTION|TRIGGER|EVENT)\\b");
  /** Words after BEGIN that start a transaction, not a block. */
  private static final java.util.Set<String> NOT_A_BLOCK =
      java.util.Set.of("TRAN", "TRANSACTION", "DISTRIBUTED", "WORK");
  /** Words after END that close a block BEGIN and CASE don't count. */
  private static final java.util.Set<String> OTHER_ENDS =
      java.util.Set.of("IF", "LOOP", "WHILE", "REPEAT", "FOR");
  /** Words after IS that make it a comparison ({@code IS NULL}), not the start of a body. */
  private static final java.util.Set<String> COMPARISONS =
      java.util.Set.of("NULL", "NOT", "TRUE", "FALSE", "UNKNOWN", "DISTINCT", "JSON");

  private SqlScript() {}

  /** The word starting at {@code i}, or an empty string. */
  private static String wordAt(String sql, int i) {
    int end = i;
    while (end < sql.length()
        && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
      end++;
    }
    return sql.substring(i, end);
  }

  /** The next word after {@code i}, skipping whitespace, upper case; empty when none. */
  private static String nextWord(String sql, int i) {
    int start = i;
    while (start < sql.length() && Character.isWhitespace(sql.charAt(start))) {
      start++;
    }
    return wordAt(sql, start).toUpperCase(Locale.ROOT);
  }

  /** The first character after {@code i} that is not whitespace, or 0. */
  private static char nextChar(String sql, int i) {
    int start = i;
    while (start < sql.length() && Character.isWhitespace(sql.charAt(start))) {
      start++;
    }
    return start < sql.length() ? sql.charAt(start) : 0;
  }

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
   *       inside it don't split; {@code GO 5} runs the batch five times;</li>
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
    // Without separators, a routine's body ends where its BEGIN ... END blocks close: the open
    // BEGIN and CASE count, and whether declarations (AS, IS, DECLARE) still wait for a BEGIN.
    boolean findsBodies = !batches && !slashBlocks;
    boolean routine = false;
    int depth = 0;
    boolean declarations = false;
    boolean closedBlock = false;

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
          int before = statements.size();
          addStatement(statements, current);
          Matcher go = GO_LINE.matcher(line);
          if (batches && go.matches() && go.group(1) != null && statements.size() > before) {
            String batch = statements.get(statements.size() - 1);
            for (int repeat = Integer.parseInt(go.group(1)); repeat > 1; repeat--) {
              statements.add(batch);
            }
          }
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
        routine = findsBodies && delimiter.equals(";")
            && ROUTINE.matcher(sql).region(i, sql.length()).lookingAt();
        depth = 0;
        declarations = false;
        closedBlock = false;
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
      } else if (routine && Character.isLetter(ch) && (i == 0
          || !(Character.isLetterOrDigit(sql.charAt(i - 1)) || sql.charAt(i - 1) == '_'
              || sql.charAt(i - 1) == '@' || sql.charAt(i - 1) == '.'))) {
        String word = wordAt(sql, i);
        String upper = word.toUpperCase(Locale.ROOT);
        int after = i + word.length();
        if (upper.equals("BEGIN") && !NOT_A_BLOCK.contains(nextWord(sql, after))) {
          depth++;
          declarations = false;
        } else if (upper.equals("CASE")) {
          depth++;
        } else if (upper.equals("END") && depth > 0
            && !OTHER_ENDS.contains(nextWord(sql, after))) {
          depth--;
          closedBlock = depth == 0;
        } else if (depth == 0 && !closedBlock && (upper.equals("AS") || upper.equals("DECLARE")
            || upper.equals("IS") && !COMPARISONS.contains(nextWord(sql, after)))) {
          // A body follows, unless it is quoted text (PostgreSQL's AS $$ ... $$).
          char first = nextChar(sql, after);
          declarations = first != '$' && first != '\'' && first != '"';
        }
        current.append(word);
        i = after - 1;
      } else if (ch == ';' && delimiter.equals(";") && !batches && !plsqlBlock
          && !(routine && (depth > 0 || declarations))) {
        if (routine && closedBlock) {
          // PL/SQL needs the semicolon after END; the other databases accept it.
          current.append(';');
        }
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

  /** Whether a statement creates a procedure, function, trigger or event. */
  public static boolean isRoutine(String statement) {
    return ROUTINE.matcher(stripComments(statement).strip()).lookingAt();
  }

  /** Whether a statement is a PL/SQL block, which Oracle's tools end with a / line. */
  static boolean isPlsqlBlock(String statement) {
    return PLSQL_BLOCK.matcher(stripComments(statement).strip()).lookingAt();
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
