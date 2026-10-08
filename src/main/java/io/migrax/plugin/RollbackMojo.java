package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:rollback}: undoes applied migrations with their rollback scripts ({@code migrax rollback}). */
@Mojo(name = "rollback", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class RollbackMojo extends AbstractMigraxMojo {
  /** Number of migrations to roll back. */
  @Parameter(property = "migrax.steps")
  private String steps;

  /** Last migration to keep. */
  @Parameter(property = "migrax.to")
  private String to;

  /** Only show what would be rolled back. */
  @Parameter(property = "migrax.dryRun", defaultValue = "false")
  private boolean dryRun;

  /** Confirms the rollback. */
  @Parameter(property = "migrax.confirm", defaultValue = "false")
  private boolean confirm;

  @Override
  protected String command() {
    return "rollback";
  }

  @Override
  protected void addArguments(List<String> args) {
    option(args, "--steps", steps);
    option(args, "--to", to);
    flag(args, "--dry-run", dryRun);
    flag(args, "--yes", confirm);
  }
}
