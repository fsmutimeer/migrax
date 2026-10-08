package io.migrax.plugin;

import java.util.List;

import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:generate}: writes a migration for entity changes ({@code migrax generate}). */
@Mojo(name = "generate", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public final class GenerateMojo extends AbstractMigraxMojo {
  /** Allow drops and narrowing type changes. */
  @Parameter(property = "migrax.allowDestructive", defaultValue = "false")
  private boolean allowDestructive;

  /** Migration file name; defaults to the next number and a description. */
  @Parameter(property = "migrax.name")
  private String name;

  /** Column renames, e.g. customer.email=contact_email (comma-separated). */
  @Parameter(property = "migrax.renames")
  private String renames;

  /** Table renames, e.g. client=customer (comma-separated). */
  @Parameter(property = "migrax.renameTables")
  private String renameTables;

  /** Build indexes and constraints without blocking writes (PostgreSQL). */
  @Parameter(property = "migrax.safe", defaultValue = "false")
  private boolean safe;

  /** Database schema to read the first baseline from. */
  @Parameter(property = "migrax.schema")
  private String schema;

  @Override
  protected String command() {
    return "generate";
  }

  @Override
  protected void addArguments(List<String> args) {
    flag(args, "--allow-destructive", allowDestructive);
    flag(args, "--safe", safe);
    option(args, "--name", name);
    option(args, "--rename", renames);
    option(args, "--rename-table", renameTables);
    option(args, "--schema", schema);
  }
}
