package io.migrax.cli;

import io.migrax.dialect.MigrationLockHeldException;

/**
 * Stable codes for errors, shown as {@code error[MXE101]: ...} and in {@code --json} output, so
 * scripts and AI assistants can act on a failure without reading its text. Codes never change
 * meaning; new situations get new codes. The docs list them in Reference > Errors and JSON.
 */
enum ErrorCode {
  UNEXPECTED("MXE000", "Unexpected error"),
  USAGE("MXE001", "Wrong command, option or input"),
  LOCK_HELD("MXE101", "Another process holds the migration lock"),
  MIGRATION_FAILED("MXE102", "A migration failed"),
  NEEDS_REPAIR("MXE103", "A failed migration must be repaired first"),
  CHECKSUM_CHANGED("MXE104", "An applied migration file was changed"),
  APPLIED_FILE_MISSING("MXE105", "An applied migration file is missing"),
  NO_DRIVER("MXE106", "No JDBC driver for the database URL"),
  CANNOT_CONNECT("MXE107", "Cannot connect to the database"),
  DESTRUCTIVE_CHANGES("MXE108", "Destructive changes need confirmation"),
  DUPLICATE_NUMBERS("MXE109", "Migrations share numbers"),
  NO_ENTITIES("MXE110", "No entities found"),
  NO_DOCKER("MXE111", "Docker is needed for verify"),
  CLEAN_DISABLED("MXE112", "clean is disabled"),
  NO_DATABASE_URL("MXE113", "No database URL configured");

  final String code;
  final String title;

  ErrorCode(String code, String title) {
    this.code = code;
    this.title = title;
  }

  /** The code for an error Migrax reports. */
  static ErrorCode of(Throwable error) {
    if (error instanceof UsageException usage) {
      return usage.code;
    }
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof MigrationLockHeldException) {
        return LOCK_HELD;
      }
      String message = current.getMessage() == null ? "" : current.getMessage();
      if (message.contains("failed and was not recorded as applied")) {
        return MIGRATION_FAILED;
      }
      if (message.contains("has an incomplete/failed attempt")
          || message.contains("was edited after failure")) {
        return NEEDS_REPAIR;
      }
      if (message.startsWith("Checksum changed for applied migration")) {
        return CHECKSUM_CHANGED;
      }
      if (message.startsWith("Applied migration file(s) are missing")) {
        return APPLIED_FILE_MISSING;
      }
      if (message.contains("No JDBC driver")) {
        return NO_DRIVER;
      }
      if (message.startsWith("Docker is not available")) {
        return NO_DOCKER;
      }
      // SQL state class 08: connection exceptions, the same on every database.
      if (current instanceof java.sql.SQLException sql && sql.getSQLState() != null
          && sql.getSQLState().startsWith("08")) {
        return CANNOT_CONNECT;
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return UNEXPECTED;
  }
}
