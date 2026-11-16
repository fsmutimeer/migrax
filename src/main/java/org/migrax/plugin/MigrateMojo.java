package org.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:migrate}: applies pending migrations ({@code migrax migrate}). */
@Mojo(name = "migrate", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class MigrateMojo extends AbstractMigraxMojo {
  /** Re-run a failed migration marked '-- migrax:resume-safe'. */
  @Parameter(property = "migrax.resume", defaultValue = "false")
  private boolean resume;

  /** Apply versioned migrations only up to and including this one. */
  @Parameter(property = "migrax.to")
  private String to;

  /** Print the SQL script of the pending migrations instead of applying them. */
  @Parameter(property = "migrax.sql", defaultValue = "false")
  private boolean sql;

  /** Only list and lint pending migrations. */
  @Parameter(property = "migrax.dryRun", defaultValue = "false")
  private boolean dryRun;

  /** How long a statement waits for a locked table (PostgreSQL), such as 30s; 0 for no limit. */
  @Parameter(property = "migrax.tableLockTimeout")
  private String tableLockTimeout;

  @Override
  protected String command() {
    return "migrate";
  }

  @Override
  protected void addArguments(List<String> args) {
    option(args, "--to", to);
    flag(args, "--sql", sql);
    flag(args, "--resume", resume);
    flag(args, "--dry-run", dryRun);
    option(args, "--table-lock-timeout", tableLockTimeout);
  }
}
