package io.migrax.cli;

/** The exit codes of {@code migrax}. */
final class ExitCode {
  static final int OK = 0;
  static final int ERROR = 1;
  /** {@code check} and {@code drift} found differences. */
  static final int CHANGES_DETECTED = 2;

  private ExitCode() {}
}
