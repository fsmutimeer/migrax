package io.migrax.cli;

import io.migrax.diff.DiffEngine;
import io.migrax.runner.MigrationRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Locale;

/** What a running command works with: its arguments, the console, the project and services. */
final class CommandContext {
  private final Args args;
  private final PrintStream out;
  private final PrintStream err;
  private final BufferedReader in;
  private final Project project;
  private final Services services;

  /**
   * @param in answers to questions, or null when Migrax must not ask
   * @param project the project, or null for commands that don't need one
   */
  CommandContext(Args args, PrintStream out, PrintStream err, BufferedReader in, Project project,
                 Services services) {
    this.args = args;
    this.out = out;
    this.err = err;
    this.in = in;
    this.project = project;
    this.services = services;
  }

  Args args() {
    return args;
  }

  /** Command output. */
  PrintStream out() {
    return out;
  }

  /** Errors, and progress messages in {@code --json} mode. */
  PrintStream err() {
    return err;
  }

  /** Whether Migrax may ask questions (a console is attached and --no-input is not set). */
  boolean interactive() {
    return in != null;
  }

  Project project() {
    if (project == null) {
      throw new IllegalStateException("This command does not use a project");
    }
    return project;
  }

  /** The migration runner, waiting for the lock as long as --lock-timeout says. */
  MigrationRunner migrationRunner() {
    return project == null ? services.migrationRunner()
        : services.migrationRunner().withLockTimeout(project.lockTimeout());
  }

  DiffEngine diffEngine() {
    return services.diffEngine();
  }

  /** Asks a yes/no question; false when Migrax can't ask. */
  boolean confirm(String question) throws IOException {
    if (in == null) {
      return false;
    }
    out.print(question + " [y/N] ");
    out.flush();
    String answer = in.readLine();
    return answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
  }
}
