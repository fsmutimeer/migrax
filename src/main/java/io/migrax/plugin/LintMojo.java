package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:lint}: finds locking and risky SQL in migrations ({@code migrax lint}). */
@Mojo(name = "lint", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class LintMojo extends AbstractMigraxMojo {
  /** Lint every migration, not only pending ones. */
  @Parameter(property = "migrax.all", defaultValue = "false")
  private boolean all;

  /** Fail on warnings too. */
  @Parameter(property = "migrax.strict", defaultValue = "false")
  private boolean strict;

  @Override
  protected String command() {
    return "lint";
  }

  @Override
  protected void addArguments(List<String> args) {
    flag(args, "--all", all);
    flag(args, "--strict", strict);
  }
}
