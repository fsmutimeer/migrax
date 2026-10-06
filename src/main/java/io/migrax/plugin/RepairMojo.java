package io.migrax.plugin;

import io.migrax.runner.MigrationRunner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

@Mojo(name = "repair", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class RepairMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(property = "migrax.dir", defaultValue = "${project.basedir}")
  private File directory;

  @Parameter(property = "migrax.url")
  private String url;

  @Parameter(property = "migrax.user")
  private String user;

  @Parameter(property = "migrax.password")
  private String password;

  @Parameter(property = "migrax.locations")
  private String locations;

  @Parameter(property = "migrax.version")
  private String version;

  @Parameter(property = "migrax.action")
  private String action;

  @Parameter(property = "migrax.confirm", defaultValue = "false")
  private boolean confirm;

  @Override
  public void execute() throws MojoExecutionException {
    if (!confirm) {
      throw new MojoExecutionException(
          "Repair requires -Dmigrax.confirm=true after manually verifying the database state.");
    }
    if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9_.-]*\\.sql")) {
      throw new MojoExecutionException(
          "Set -Dmigrax.version to the failed migration filename (for example 0002_add_column.sql).");
    }
    if (!"applied".equalsIgnoreCase(action) && !"retry".equalsIgnoreCase(action)) {
      throw new MojoExecutionException(
          "Set -Dmigrax.action=applied only when all changes succeeded, or retry only after "
              + "manually restoring the database to its pre-migration state.");
    }

    try {
      ProjectDatabaseConfig.Credentials credentials = ProjectDatabaseConfig.load(
          project.getBasedir().toPath().resolve("src/main/resources"),
          Map.of("url", nonNull(url), "user", nonNull(user), "password", nonNull(password),
              "locations", nonNull(locations)));
      if (credentials.url() == null || credentials.url().isBlank()) {
        throw new MojoExecutionException("No JDBC URL configured for repair.");
      }
      Path migrationDirectory = ProjectDatabaseConfig.resolveMigrationDirectory(
          credentials.locations(), directory.toPath(),
          project.getBasedir().toPath().resolve("src/main/resources"));
      Path migrationFile = migrationDirectory.resolve(version);
      if (!Files.isRegularFile(migrationFile)) {
        throw new MojoExecutionException(
            "Migration file is missing: " + migrationFile
                + ". Restore the original file before repairing migration history.");
      }
      List<URL> runtimeUrls = project.getRuntimeClasspathElements().stream()
          .map(path -> {
            try {
              return Path.of(path).toUri().toURL();
            } catch (Exception e) {
              throw new IllegalStateException("Invalid runtime classpath entry: " + path, e);
            }
          })
          .toList();
      ClassLoader previous = Thread.currentThread().getContextClassLoader();
      try (URLClassLoader runtimeLoader =
               new URLClassLoader(runtimeUrls.toArray(URL[]::new), previous)) {
        Thread.currentThread().setContextClassLoader(runtimeLoader);
        try (Connection connection = RuntimeJdbc.connect(
            runtimeLoader, credentials.url(), credentials.user(), credentials.password())) {
          new MigrationRunner().repair(connection, migrationFile, action, confirm);
        }
      } finally {
        Thread.currentThread().setContextClassLoader(previous);
      }
      getLog().warn("Migration history repaired for " + version + " using action '" + action
          + "'. Verify the database and record this operation in your deployment audit log.");
    } catch (MojoExecutionException e) {
      throw e;
    } catch (Exception e) {
      throw new MojoExecutionException("Could not repair migration history.", e);
    }
  }

  private static String nonNull(String value) {
    return value == null ? "" : value;
  }

}
