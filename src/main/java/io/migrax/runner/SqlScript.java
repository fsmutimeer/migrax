package io.migrax.runner;

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
   */
  public static List<String> split(String sql, boolean backslashEscapes) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    String dollarQuote = null;
    boolean lineComment = false;
    boolean blockComment = false;

    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

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

      if (ch == '-' && next == '-') {
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
      } else if (ch == ';') {
        addStatement(statements, current);
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
