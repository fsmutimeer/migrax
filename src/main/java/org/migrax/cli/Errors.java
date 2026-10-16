package org.migrax.cli;

/** How Migrax reports unexpected errors on the console. */
final class Errors {
  private Errors() {}

  /** One-line error text: the message plus distinct cause messages. */
  static String describe(Throwable error) {
    return org.migrax.util.Messages.describe(error);
  }

  /** Advice for well-known failures, or null. */
  static String hint(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof org.migrax.dialect.MigrationLockHeldException) {
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
