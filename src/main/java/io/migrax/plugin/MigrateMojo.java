package io.migrax.plugin;

import io.migrax.runner.MigrationRunner;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.net.URL;
import java.net.URLClassLoader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;
import java.util.List;

@Mojo(name = "migrate", requiresDependencyResolution = ResolutionScope.RUNTIME, threadSafe = true)
public final class MigrateMojo extends AbstractMojo {
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

  @Parameter(property = "migrax.resume", defaultValue = "false")
  private boolean resume;

  @Override
  public void execute() throws MojoExecutionException {
    ProjectDatabaseConfig.Credentials credentials;
    try {
      credentials = ProjectDatabaseConfig.load(
          project.getBasedir().toPath().resolve("src/main/resources"),
          Map.of("url", nonNull(url), "user", nonNull(user), "password", nonNull(password),
              "locations", nonNull(locations)));
    } catch (Exception e) {
      throw new MojoExecutionException("Could not read the project's database configuration.", e);
    }
    String jdbcUrl = credentials.url();
    String jdbcUser = credentials.user();
    String jdbcPassword = credentials.password();
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      throw new MojoExecutionException(
          "No JDBC URL found. Configure the datasource in application.properties/application.yml, "
              + "or set MIGRAX_DATABASE_URL or -Dmigrax.url.");
    }

    Path migrationsDirectory;
    try {
      migrationsDirectory = ProjectDatabaseConfig.resolveMigrationDirectory(
          credentials.locations(), directory.toPath(),
          project.getBasedir().toPath().resolve("src/main/resources"));
    } catch (IllegalArgumentException e) {
      throw new MojoExecutionException("Invalid Migrax migration location.", e);
    }
    try {
      List<Path> migrations;
      if (Files.isDirectory(migrationsDirectory)) {
        try (var files = Files.list(migrationsDirectory)) {
          migrations = files
              .filter(path -> path.getFileName().toString().endsWith(".sql"))
              .sorted()
              .toList();
        }
      } else {
        migrations = List.of();
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
      int appliedCount;
      try (URLClassLoader runtimeLoader =
               new URLClassLoader(runtimeUrls.toArray(URL[]::new), previous)) {
        Thread.currentThread().setContextClassLoader(runtimeLoader);
        try (Connection connection = RuntimeJdbc.connect(
            runtimeLoader, jdbcUrl, jdbcUser, jdbcPassword)) {
          appliedCount = new MigrationRunner().migrate(connection, migrations, resume);
        }
      } finally {
        Thread.currentThread().setContextClassLoader(previous);
      }
      getLog().info("Found " + migrations.size() + " migration file(s); applied " + appliedCount
          + " new migration(s) from " + migrationsDirectory.toAbsolutePath()
          + " (" + (migrations.size() - appliedCount) + " already applied).");
    } catch (Exception e) {
      throw new MojoExecutionException("Could not apply Migrax SQL migrations.", e);
    }
  }

  private static String nonNull(String value) {
    return value == null ? "" : value;
  }

}
