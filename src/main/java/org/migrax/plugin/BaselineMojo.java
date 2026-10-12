package org.migrax.plugin;

import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * {@code mvn migrax:baseline}: records existing migrations as applied without running them
 * ({@code migrax baseline}).
 */
@Mojo(name = "baseline", requiresDependencyResolution = ResolutionScope.RUNTIME,
    threadSafe = true)
public final class BaselineMojo extends AbstractMigraxMojo {
  /** The last migration the database already has, e.g. 0006_add_index.sql. */
  @Parameter(property = "migrax.version")
  private String version;

  /** Only list the migrations that would be recorded. */
  @Parameter(property = "migrax.dryRun", defaultValue = "false")
  private boolean dryRun;

  /** Confirms the change to migration history. */
  @Parameter(property = "migrax.confirm", defaultValue = "false")
  private boolean confirm;

  @Override
  protected String command() {
    return "baseline";
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (version == null || version.isBlank()) {
      throw new MojoFailureException("Set -Dmigrax.version=<last migration already applied>.");
    }
    super.execute();
  }

  @Override
  protected void addArguments(List<String> args) {
    args.add(version);
    flag(args, "--dry-run", dryRun);
    flag(args, "--yes", confirm);
  }
}
