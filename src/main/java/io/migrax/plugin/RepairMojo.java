package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:repair}: fixes history after a failed migration ({@code migrax repair}). */
@Mojo(name = "repair", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class RepairMojo extends AbstractMigraxMojo {
  /** The failed migration, e.g. 0002_add_column.sql. */
  @Parameter(property = "migrax.version")
  private String version;

  /** applied or retry. */
  @Parameter(property = "migrax.action")
  private String action;

  /** Confirms the change to migration history. */
  @Parameter(property = "migrax.confirm", defaultValue = "false")
  private boolean confirm;

  @Override
  protected String command() {
    return "repair";
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (version == null || version.isBlank()) {
      throw new MojoFailureException("Set -Dmigrax.version=<migration file>.");
    }
    super.execute();
  }

  @Override
  protected void addArguments(List<String> args) {
    args.add(version);
    option(args, "--action", action);
    flag(args, "--yes", confirm);
  }
}
