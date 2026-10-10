package io.migrax.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** How Migrax reports unexpected errors on the console. */
final class Errors {
  private Errors() {}

  /** One-line error text: the message plus distinct cause messages. */
  static String describe(Throwable error) {
    List<String> parts = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Throwable current = error; current != null && parts.size() < 3;
         current = current.getCause()) {
      String message = current.getMessage();
      if (message == null || message.isBlank()) {
        message = current.getClass().getSimpleName();
      }
      if (current instanceof LinkageError) {
        message = current.getClass().getSimpleName() + ": " + message;
      }
      message = message.strip();
      boolean repeated = false;
      for (String previous : seen) {
        if (previous.contains(message) || message.contains(previous) && previous.length() > 20) {
          repeated = true;
          break;
        }
      }
      if (!repeated) {
        seen.add(message);
        parts.add(message);
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return String.join(System.lineSeparator() + "  caused by: ", parts);
  }

  /** Advice for well-known failures, or null. */
  static String hint(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof io.migrax.dialect.MigrationLockHeldException) {
        return "When several instances start together, let them wait for each other with "
            + "--lock-timeout 2m (or MIGRAX_LOCK_TIMEOUT).";
      }
      if (current instanceof NoClassDefFoundError || current instanceof ClassNotFoundException) {
        return "A class from your project or its dependencies could not be loaded. "
            + "Run 'migrax doctor', or try again with --refresh.";
      }
      if (current instanceof java.sql.SQLException sql
          && sql.getMessage() != null && sql.getMessage().contains("No JDBC driver")) {
        return "Add the database driver as a runtime dependency of the project, or run "
            + "'migrax doctor'. Without a project, pass --classpath <driver.jar> or put the jar "
            + "in the drivers folder (MIGRAX_DRIVERS).";
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return null;
  }
}
