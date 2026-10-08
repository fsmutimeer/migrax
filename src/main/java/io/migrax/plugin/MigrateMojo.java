package io.migrax.plugin;

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

  /** Only list and lint pending migrations. */
  @Parameter(property = "migrax.dryRun", defaultValue = "false")
  private boolean dryRun;

  @Override
  protected String command() {
    return "migrate";
  }

  @Override
  protected void addArguments(List<String> args) {
    flag(args, "--resume", resume);
    flag(args, "--dry-run", dryRun);
  }
}
