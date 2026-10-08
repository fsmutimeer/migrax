package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:plan}: previews the SQL that generate would write ({@code migrax plan}). */
@Mojo(name = "plan", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public final class PlanMojo extends AbstractMigraxMojo {
  /** Show affected table sizes. */
  @Parameter(property = "migrax.impact", defaultValue = "false")
  private boolean impact;

  @Override
  protected String command() {
    return "plan";
  }

  @Override
  protected void addArguments(List<String> args) {
    flag(args, "--impact", impact);
  }
}
