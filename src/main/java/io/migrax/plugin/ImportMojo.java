package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:import}: adopts a database managed by Flyway or Liquibase ({@code migrax import}). */
@Mojo(name = "import", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class ImportMojo extends AbstractMigraxMojo {
  /** flyway or liquibase. */
  @Parameter(property = "migrax.from", required = true)
  private String from;

  /** Flyway history table. */
  @Parameter(property = "migrax.table")
  private String table;

  @Override
  protected String command() {
    return "import";
  }

  @Override
  protected void addArguments(List<String> args) {
    args.add(from);
    option(args, "--table", table);
  }
}
