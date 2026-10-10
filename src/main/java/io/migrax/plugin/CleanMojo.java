package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:clean}: drops every table, view and sequence ({@code migrax clean}). */
@Mojo(name = "clean", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class CleanMojo extends AbstractMigraxMojo {
  /** Confirms dropping everything; without it the goal fails. */
  @Parameter(property = "migrax.confirm", defaultValue = "false")
  private boolean confirm;

  /** Only lists what would be dropped. */
  @Parameter(property = "migrax.dryRun", defaultValue = "false")
  private boolean dryRun;

  @Override
  protected String command() {
    return "clean";
  }

  @Override
  protected void addArguments(List<String> args) {
    flag(args, "--yes", confirm);
    flag(args, "--dry-run", dryRun);
  }
}
