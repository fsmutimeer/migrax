package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:new}: creates an empty SQL or Java migration ({@code migrax new}). */
@Mojo(name = "new", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class NewMojo extends AbstractMigraxMojo {
  /** Name of the migration. */
  @Parameter(property = "migrax.name", required = true)
  private String name;

  /** Create a Java migration. */
  @Parameter(property = "migrax.java", defaultValue = "false")
  private boolean java;

  @Override
  protected String command() {
    return "new";
  }

  @Override
  protected void addArguments(List<String> args) {
    args.add(name);
    flag(args, "--java", java);
  }
}
