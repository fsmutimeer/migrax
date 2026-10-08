package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:drift}: compares the live database with the expected schema ({@code migrax drift}). */
@Mojo(name = "drift", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class DriftMojo extends AbstractMigraxMojo {
  /** Compare with the entities instead of the snapshot. */
  @Parameter(property = "migrax.entities", defaultValue = "false")
  private boolean entities;

  /** Database schema to read. */
  @Parameter(property = "migrax.schema")
  private String schema;

  @Override
  protected String command() {
    return "drift";
  }

  @Override
  protected void addArguments(List<String> args) {
    flag(args, "--entities", entities);
    option(args, "--schema", schema);
  }
}
