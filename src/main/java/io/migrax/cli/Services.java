package io.migrax.cli;

import io.migrax.diff.DiffEngine;
import io.migrax.runner.MigrationRunner;

/**
 * The engines commands work with. Commands get them from {@link CommandContext} instead of
 * creating them, so tests and embedding code can pass their own.
 */
record Services(MigrationRunner migrationRunner, DiffEngine diffEngine) {

  static Services standard() {
    return new Services(new MigrationRunner(), new DiffEngine());
  }
}
