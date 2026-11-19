package org.migrax.util;

import java.io.PrintStream;
import java.util.Objects;

/**
 * Minimal dependency-free logger used by the Migrax core.
 *
 * <p>The CLI installs a console sink and the Maven plugin routes messages to Maven's own log.
 * Keeping the core free of logging libraries means the CLI jar runs with no extra runtime
 * dependencies and never clashes with the logging setup of the inspected application.
 *
 * @since 0.1.0
 */
public final class Log {

  /** Destination for log messages. */
  public interface Sink {
    void debug(String message);

    void info(String message);

    void warn(String message);

    void error(String message);
  }

  private static final char LINE_SEPARATOR = 0x2028;
  private static final char PARAGRAPH_SEPARATOR = 0x2029;

  private static volatile Sink sink = console(System.out, System.err, false);

  private Log() {}

  /** Replaces the active sink and returns the previous one so callers can restore it. */
  public static Sink setSink(Sink newSink) {
    Sink previous = sink;
    sink = Objects.requireNonNull(newSink, "sink");
    return previous;
  }

  /** Returns a console sink; debug messages are printed only when {@code verbose} is true. */
  public static Sink console(PrintStream out, PrintStream err, boolean verbose) {
    return new Sink() {
      @Override
      public void debug(String message) {
        if (verbose) {
          out.println("debug: " + terminalText(message));
        }
      }

      @Override
      public void info(String message) {
        out.println(terminalText(message));
      }

      @Override
      public void warn(String message) {
        err.println("warning: " + terminalText(message));
      }

      @Override
      public void error(String message) {
        err.println("error: " + terminalText(message));
      }
    };
  }

  /**
   * {@code text} for a terminal: control characters other than line breaks ({@code \n},
   * {@code \r\n}) and tabs (an escape sequence or a lone {@code \r} in a table name, say) are
   * shown as a backslash, {@code u} and four hex digits instead of controlling it.
   *
   * @since 0.19.0
   */
  public static String terminalText(String text) {
    if (text == null) {
      return null;
    }
    int i = 0;
    while (i < text.length() && !controls(text, i)) {
      i++;
    }
    if (i == text.length()) {
      return text;
    }
    StringBuilder result = new StringBuilder(text.length() + 8).append(text, 0, i);
    for (; i < text.length(); i++) {
      char c = text.charAt(i);
      if (controls(text, i)) {
        result.append(String.format("\\u%04x", (int) c));
      } else {
        result.append(c);
      }
    }
    return result.toString();
  }

  private static boolean controls(String text, int i) {
    char c = text.charAt(i);
    if (c == '\r') {
      return i + 1 == text.length() || text.charAt(i + 1) != '\n';
    }
    return c != '\n' && c != '\t'
        && (Character.getType(c) == Character.CONTROL || c == LINE_SEPARATOR
            || c == PARAGRAPH_SEPARATOR);
  }

  public static void debug(String pattern, Object... args) {
    sink.debug(format(pattern, args));
  }

  public static void info(String pattern, Object... args) {
    sink.info(format(pattern, args));
  }

  public static void warn(String pattern, Object... args) {
    sink.warn(format(pattern, args));
  }

  public static void error(String pattern, Object... args) {
    sink.error(format(pattern, args));
  }

  /** Replaces each {@code {}} placeholder with the next argument, SLF4J style. */
  static String format(String pattern, Object... args) {
    if (args == null || args.length == 0) {
      return pattern;
    }
    StringBuilder result = new StringBuilder(pattern.length() + 32);
    int argument = 0;
    int start = 0;
    int marker;
    while ((marker = pattern.indexOf("{}", start)) >= 0 && argument < args.length) {
      result.append(pattern, start, marker).append(args[argument++]);
      start = marker + 2;
    }
    return result.append(pattern, start, pattern.length()).toString();
  }
}
