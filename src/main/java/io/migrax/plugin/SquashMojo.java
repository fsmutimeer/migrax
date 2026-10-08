package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:squash}: combines old migrations into one ({@code migrax squash}). */
@Mojo(name = "squash", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class SquashMojo extends AbstractMigraxMojo {
  /** Squash up to and including this migration. */
  @Parameter(property = "migrax.to", required = true)
  private String to;

  /** Squash to the resulting schema only. */
  @Parameter(property = "migrax.optimize", defaultValue = "false")
  private boolean optimize;

  @Override
  protected String command() {
    return "squash";
  }

  @Override
  protected void addArguments(List<String> args) {
    option(args, "--to", to);
    flag(args, "--optimize", optimize);
  }
}
