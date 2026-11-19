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
  /**
   * A statement that starts, ends or undoes a transaction (upper case, one space between words):
   * BEGIN of a transaction (a block's BEGIN is a routine), START TRANSACTION, COMMIT, ROLLBACK.
   * Not a rollback to a savepoint: ROLLBACK ... TO, or SQL Server's ROLLBACK TRAN savepoint.
   */
  private static final Pattern TRANSACTION_CONTROL = Pattern.compile("^(?:BEGIN\\b.*"
      + "|START TRANSACTION\\b.*|COMMIT\\b.*"
      + "|ROLLBACK\\b(?! (?:WORK |TRANSACTION |TRAN )?TO\\b| TRAN(?:SACTION)? (?!AND\\b)\\S).*)$");
  /**
   * PostgreSQL's and SQLite's END, a COMMIT. Elsewhere a lone END is the rest of a block that was
   * cut at a semicolon, so it counts only after a BEGIN or START TRANSACTION in the same file.
   */
  private static final Pattern END_TRANSACTION = Pattern.compile("^END(?: WORK| TRANSACTION)?$");
  /**
   * A transaction command at the start of a statement or after a {@code ;} inside one, where a
   * driver would send it on its own.
   */
  private static final Pattern MAY_CONTROL_TRANSACTION = Pattern.compile("(?i)(?:^|;)\\s*"
      + "(?:(?:COMMIT|END|ROLLBACK|ABORT)(?:\\s+(?:WORK|TRANSACTION|AND\\s+(?:NO\\s+)?CHAIN))*"
      + "\\s*(?:;|\\z)|BEGIN\\b|START\\s+TRANSACTION\\b|PREPARE\\s+TRANSACTION\\b"
      + "|COMMIT\\s+PREPARED\\b)");
  /** A PostgreSQL dollar-quoted body, such as {@code $$ ... $$} or {@code $fn$ ... $fn$}. */
  private static final Pattern DOLLAR_QUOTED =
      Pattern.compile("(?s)\\$([A-Za-z_][A-Za-z0-9_]*|)\\$.*?\\$\\1\\$");
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");
  /** {@code GO}, or {@code GO 5} to run the batch five times. */
  private static final Pattern GO_LINE = Pattern.compile("(?i)\\s*GO(?:\\s+(\\d+))?\\s*");
  private static final Pattern SLASH_LINE = Pattern.compile("\\s*/\\s*");
  private static final Pattern DELIMITER_LINE = Pattern.compile("(?i)DELIMITER\\s+(\\S+)");
  private static final Pattern PLSQL_BLOCK = Pattern.compile("(?is)(?:CREATE\\s+(?:OR\\s+REPLACE"
      + "\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(?:PROCEDURE|FUNCTION|PACKAGE|TRIGGER|TYPE)"
      + "|DECLARE|BEGIN)\\b");

  /**
   * A statement that creates a procedure, function, trigger, event, package or type body, whose
   * body has semicolons.
   */
  private static final Pattern ROUTINE = Pattern.compile("(?is)CREATE\\s+(?:OR\\s+(?:REPLACE|ALTER)"
      + "\\s+)?(?:DEFINER\\s*=\\s*\\S+\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?"
      + "(?:PROCEDURE|PROC|FUNCTION|TRIGGER|EVENT|PACKAGE|TYPE\\s+BODY)\\b");
  /** An Oracle package or type body: its AS or IS opens a block that its last END closes. */
  private static final Pattern PACKAGE = Pattern.compile("(?is)CREATE\\s+(?:OR\\s+REPLACE\\s+)?"
      + "(?:(?:NON)?EDITIONABLE\\s+)?(?:PACKAGE|TYPE\\s+BODY)\\b");
  /** Words after BEGIN that start a transaction, not a block. */
  private static final java.util.Set<String> NOT_A_BLOCK =
      java.util.Set.of("TRAN", "TRANSACTION", "DISTRIBUTED", "WORK");
  /** Words after a statement's first BEGIN that start a transaction (PostgreSQL, SQLite). */
  private static final java.util.Set<String> TRANSACTION_MODES = java.util.Set.of(
      "ISOLATION", "READ", "NOT", "DEFERRABLE", "DEFERRED", "IMMEDIATE", "EXCLUSIVE");
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

  /**
   * Whether the statement at {@code i} is an anonymous block: {@code DECLARE} (not SQL Server's
   * {@code DECLARE @variable}) or {@code BEGIN} that doesn't start a transaction.
   */
  private static boolean anonymousBlock(String sql, int i) {
    String first = wordAt(sql, i).toUpperCase(Locale.ROOT);
    int after = i + first.length();
    if (first.equals("DECLARE")) {
      return nextChar(sql, after) != '@';
    }
    if (!first.equals("BEGIN")) {
      return false;
    }
    String second = nextWord(sql, after);
    return !second.isEmpty() && !NOT_A_BLOCK.contains(second)
        && !TRANSACTION_MODES.contains(second);
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
    // In a package or type body, the procedures and functions declared directly in it: whether
    // one's header is open (until its IS, AS or semicolon), and how many bodies wait for BEGIN.
    // Any other BEGIN there starts the package's own code, which shares the package's END.
    boolean packageBody = false;
    boolean subprogramHeader = false;
    int pendingBodies = 0;

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
            && (ROUTINE.matcher(sql).region(i, sql.length()).lookingAt()
                || anonymousBlock(sql, i));
        packageBody = routine && PACKAGE.matcher(sql).region(i, sql.length()).lookingAt();
        depth = 0;
        declarations = false;
        closedBlock = false;
        subprogramHeader = false;
        pendingBodies = 0;
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

      if (ch == ';' && depth == 1) {
        subprogramHeader = false;
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
        if (packageBody && depth == 1 && (upper.equals("PROCEDURE") || upper.equals("FUNCTION"))) {
          subprogramHeader = true;
        } else if (packageBody && depth == 1 && subprogramHeader
            && (upper.equals("IS") || upper.equals("AS"))) {
          subprogramHeader = false;
          pendingBodies++;
        } else if (packageBody && depth == 1 && upper.equals("BEGIN") && pendingBodies == 0) {
          // The package's own code: no block of its own.
          subprogramHeader = false;
        } else if (upper.equals("BEGIN") && !NOT_A_BLOCK.contains(nextWord(sql, after))) {
          depth++;
          declarations = false;
          if (packageBody && depth == 2) {
            pendingBodies--;
          }
        } else if (upper.equals("CASE")) {
          depth++;
        } else if (upper.equals("END") && depth > 0
            && !OTHER_ENDS.contains(nextWord(sql, after))) {
          depth--;
          closedBlock = depth == 0;
        } else if (depth == 0 && !closedBlock && (upper.equals("AS") || upper.equals("DECLARE")
            || upper.equals("IS") && !COMPARISONS.contains(nextWord(sql, after)))) {
          // A body follows, unless it is quoted text (PostgreSQL's AS $$ ... $$). A package's
          // AS or IS opens the block its last END closes.
          char first = nextChar(sql, after);
          if (packageBody && !upper.equals("DECLARE")) {
            depth++;
          } else {
            declarations = first != '$' && first != '\'' && first != '"';
          }
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

  /**
   * Whether a statement creates a procedure, function, trigger, event, package or type body, or
   * is an anonymous block.
   */
  public static boolean isRoutine(String statement) {
    String text = stripComments(statement).strip();
    return ROUTINE.matcher(text).lookingAt() || anonymousBlock(text, 0);
  }

  /**
   * Whether a statement starts, ends or undoes a transaction ({@code BEGIN}, {@code START
   * TRANSACTION}, {@code COMMIT}, {@code ROLLBACK}, and {@code END} once {@code opened} says a
   * BEGIN came before). A routine's or block's BEGIN does not count; neither does a rollback to
   * a savepoint.
   *
   * @since 0.19.0
   */
  public static boolean isTransactionControl(String statement, boolean opened) {
    if (isRoutine(statement)) {
      return false;
    }
    String upper = stripComments(statement).replaceAll("\\s+", " ").trim()
        .toUpperCase(Locale.ROOT);
    return TRANSACTION_CONTROL.matcher(upper).matches()
        || opened && END_TRANSACTION.matcher(upper).matches();
  }

  /** Whether a transaction-control statement opens a transaction (BEGIN, START TRANSACTION). */
  public static boolean opensTransaction(String statement) {
    return stripComments(statement).strip().toUpperCase(Locale.ROOT)
        .matches("(?s)(?:BEGIN|START\\s+TRANSACTION)\\b.*");
  }

  /**
   * Whether statements about to be sent may commit, roll back or start a transaction of their
   * own; then a failure may leave the ones before that committed. A lone {@code END} (a COMMIT
   * on PostgreSQL) counts, and so does such a command at the start of a line or after a
   * {@code ;} inside a statement: the driver may split text that this class's splitter could
   * not (PostgreSQL's {@code E'...'} strings). Counting too much only records a failure.
   */
  static boolean hasTransactionControl(List<String> statements) {
    for (String statement : statements) {
      // Routines too: the splitter may have taken the statements after one for its body.
      String outsideBodies = DOLLAR_QUOTED.matcher(stripComments(statement)).replaceAll(" ");
      if (isTransactionControl(statement, true)
          || MAY_CONTROL_TRANSACTION.matcher(outsideBodies).find()
          || MAY_CONTROL_TRANSACTION.matcher(postgresCode(statement)).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * {@code statement} with the text of its strings, quoted names, comments and dollar-quoted
   * bodies replaced by spaces, read the way PostgreSQL and its driver read it: a backslash
   * escapes the next character in {@code E'...'} strings, block comments nest, and a {@code $}
   * right after a letter, digit or {@code _} belongs to a name ({@code x$$}), so it starts no
   * body. What is left is the code the driver may split at {@code ;}.
   */
  static String postgresCode(String statement) {
    StringBuilder code = new StringBuilder(statement.length());
    int length = statement.length();
    int i = 0;
    while (i < length) {
      char ch = statement.charAt(i);
      char next = i + 1 < length ? statement.charAt(i + 1) : 0;
      char before = i > 0 ? statement.charAt(i - 1) : 0;
      int end;
      if (ch == '-' && next == '-') {
        end = i + 2;
        while (end < length && statement.charAt(end) != '\n' && statement.charAt(end) != '\r') {
          end++;
        }
      } else if (ch == '/' && next == '*') {
        end = blockCommentEnd(statement, i);
      } else if (ch == '\'') {
        boolean escapes = (before == 'E' || before == 'e')
            && (i < 2 || !isNameCharacter(statement.charAt(i - 2)));
        end = quotedEnd(statement, i, '\'', escapes);
      } else if (ch == '"') {
        end = quotedEnd(statement, i, '"', false);
      } else if (ch == '$' && !isNameCharacter(before)) {
        Matcher body = DOLLAR_QUOTED.matcher(statement).region(i, length);
        end = body.lookingAt() ? body.end() : i + 1;
        if (end == i + 1) {
          code.append(ch);
          i++;
          continue;
        }
      } else {
        code.append(ch);
        i++;
        continue;
      }
      code.append(" ".repeat(end - i));
      i = end;
    }
    return code.toString();
  }

  private static boolean isNameCharacter(char ch) {
    return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$';
  }

  /** The index after a nested block comment that starts at {@code start}. */
  private static int blockCommentEnd(String text, int start) {
    int depth = 0;
    int i = start;
    while (i < text.length() - 1) {
      if (text.charAt(i) == '/' && text.charAt(i + 1) == '*') {
        depth++;
        i += 2;
      } else if (text.charAt(i) == '*' && text.charAt(i + 1) == '/') {
        i += 2;
        if (--depth == 0) {
          return i;
        }
      } else {
        i++;
      }
    }
    return text.length();
  }

  /** The index after a quoted text that starts at {@code start}; doubled quotes stay in it. */
  private static int quotedEnd(String text, int start, char quote, boolean backslashEscapes) {
    int i = start + 1;
    while (i < text.length()) {
      char ch = text.charAt(i);
      if (backslashEscapes && ch == '\\') {
        i += 2;
      } else if (ch == quote) {
        if (i + 1 < text.length() && text.charAt(i + 1) == quote) {
          i += 2;
        } else {
          return i + 1;
        }
      } else {
        i++;
      }
    }
    return text.length();
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
