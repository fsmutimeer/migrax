package io.migrax.util;

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
          out.println("debug: " + message);
        }
      }

      @Override
      public void info(String message) {
        out.println(message);
      }

      @Override
      public void warn(String message) {
        err.println("warning: " + message);
      }

      @Override
      public void error(String message) {
        err.println("error: " + message);
      }
    };
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
