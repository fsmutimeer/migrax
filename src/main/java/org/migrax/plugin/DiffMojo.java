package org.migrax.plugin;

import java.util.List;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

/** {@code mvn migrax:diff}: compares the schemas of two databases ({@code migrax diff}). */
@Mojo(name = "diff", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class DiffMojo extends AbstractMigraxMojo {
  /** JDBC URL of the source database, whose schema the project's database should match. */
  @Parameter(property = "migrax.source")
  private String source;

  /** User of the source database (or MIGRAX_SOURCE_USER). */
  @Parameter(property = "migrax.sourceUser")
  private String sourceUser;

  /** Print the SQL script only. */
  @Parameter(property = "migrax.sql", defaultValue = "false")
  private boolean sql;

  /** Database schema to read. */
  @Parameter(property = "migrax.schema")
  private String schema;

  @Override
  protected String command() {
    return "diff";
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (source == null || source.isBlank()) {
      throw new MojoFailureException("Set -Dmigrax.source=<JDBC URL of the source database>.");
    }
    super.execute();
  }

  @Override
  protected void addArguments(List<String> args) {
    args.add(source);
    option(args, "--source-user", sourceUser);
    flag(args, "--sql", sql);
    option(args, "--schema", schema);
  }
}
