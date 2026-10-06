package io.migrax.plugin;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.Dialects;
import io.migrax.diff.DiffEngine;
import io.migrax.diff.SnapshotStore;
import io.migrax.model.JpaExtractor;
import io.migrax.model.SchemaModel;
import io.migrax.ops.Operation;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mojo(name = "generate", requiresDependencyResolution = org.apache.maven.plugins.annotations.ResolutionScope.RUNTIME,
    threadSafe = true)
@Execute(phase = LifecyclePhase.COMPILE)
public final class GenerateMojo extends AbstractMojo {
  private static final Pattern NUMBERED_MIGRATION = Pattern.compile("^(\\d+)_.*");

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(property = "migrax.dir", defaultValue = "${project.basedir}")
  private File directory;

  @Parameter(property = "migrax.package", defaultValue = "${project.groupId}")
  private String basePackage;

  @Parameter(property = "migrax.url")
  private String url;

  @Parameter(property = "migrax.user")
  private String user;

  @Parameter(property = "migrax.password")
  private String password;

  @Parameter(property = "migrax.locations")
  private String locations;

  @Parameter(property = "migrax.allowDestructive", defaultValue = "false")
  private boolean allowDestructive;

  @Override
  public void execute() throws MojoExecutionException {
    try {
      ProjectDatabaseConfig.Credentials credentials = ProjectDatabaseConfig.load(
          project.getBasedir().toPath().resolve("src/main/resources"),
          Map.of("url", nonNull(url), "user", nonNull(user), "password", nonNull(password),
              "locations", nonNull(locations)));
      if (credentials.url() == null || credentials.url().isBlank()) {
        throw new MojoExecutionException(
            "No JDBC URL found. Configure application.properties/application.yml, "
                + "MIGRAX_DATABASE_URL, or -Dmigrax.url.");
      }
      String scanPackage = basePackage == null || basePackage.isBlank()
          ? project.getGroupId()
          : basePackage;
      if (scanPackage == null || scanPackage.isBlank()) {
        throw new MojoExecutionException(
            "Could not infer the entity package. Set -Dmigrax.package=<base-package>.");
      }

      List<URL> runtimeUrls = project.getRuntimeClasspathElements().stream()
          .map(GenerateMojo::toUrl)
          .toList();
      ClassLoader previous = Thread.currentThread().getContextClassLoader();
      try (URLClassLoader runtimeLoader =
               new URLClassLoader(runtimeUrls.toArray(URL[]::new), previous)) {
        Thread.currentThread().setContextClassLoader(runtimeLoader);
        SchemaModel current = new JpaExtractor().extract(scanPackage);
        if (current.tables().isEmpty()) {
          throw new MojoExecutionException(
              "No JPA entities found under package '" + scanPackage
                  + "'. Set -Dmigrax.package to the package containing your entities.");
        }
        Path snapshot = SnapshotStore.projectSnapshot(directory.toPath());
        SchemaModel baseline = null;
        if (!Files.exists(snapshot)) {
          try (Connection connection = RuntimeJdbc.connect(
              runtimeLoader, credentials.url(), credentials.user(), credentials.password())) {
            baseline = DatabaseSchemaReader.read(connection);
            getLog().info("No snapshot found; using the connected database schema as the baseline.");
          }
        }
        createMigration(current, credentials.url(), baseline,
            ProjectDatabaseConfig.resolveMigrationDirectory(
                credentials.locations(), directory.toPath(),
                project.getBasedir().toPath().resolve("src/main/resources")),
            allowDestructive);
      } finally {
        Thread.currentThread().setContextClassLoader(previous);
      }
    } catch (MojoExecutionException e) {
      throw e;
    } catch (Exception e) {
      throw new MojoExecutionException("Could not generate a migration.", e);
    }
  }

  private void createMigration(
      SchemaModel current, String jdbcUrl, SchemaModel databaseBaseline, Path migrations,
      boolean destructiveAllowed)
      throws Exception {
    Path root = directory.toPath();
    Path snapshot = SnapshotStore.projectSnapshot(root);
    boolean hasSnapshot = Files.exists(snapshot);
    SchemaModel previous = hasSnapshot
        ? SnapshotStore.load(snapshot)
        : databaseBaseline == null ? SchemaModel.empty() : databaseBaseline;
    current = current.withPrimaryKeyNamesFrom(previous);
    List<Operation> operations = new DiffEngine().diff(previous, current);
    if (operations.isEmpty()) {
      getLog().info("No schema changes detected.");
      if (!hasSnapshot) {
        SnapshotStore.save(snapshot, current);
        getLog().info("Saved the current entity schema as the baseline snapshot.");
      }
      return;
    }
    if (!destructiveAllowed && operations.stream().anyMatch(Operation::destructive)) {
      throw new MojoExecutionException(
          "Destructive schema changes detected. Review the change, then rerun with "
              + "\"-Dmigrax.allowDestructive=true\" to generate it "
              + "(PowerShell requires the quotes).");
    }

    Dialect dialect = Dialects.fromJdbcUrl(jdbcUrl);
    Files.createDirectories(migrations);
    String name = nextMigrationName(migrations, previous.tables().isEmpty());
    StringBuilder sql = new StringBuilder("-- Generated by Migrax\n\n");
    for (Operation operation : operations) {
      sql.append(dialect.render(operation)).append(";\n");
    }
    Path migrationFile = migrations.resolve(name + ".sql");
    Files.writeString(migrationFile, sql);
    SnapshotStore.save(snapshot, current);
    getLog().info("Generated " + migrationFile.toAbsolutePath()
        + " with " + operations.size() + " operation(s). Review it before applying.");
  }

  private static String nextMigrationName(Path migrations, boolean baselineIsEmpty) throws Exception {
    int latest = 0;
    try (var files = Files.list(migrations)) {
      for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".sql")).toList()) {
        String fileName = file.getFileName().toString();
        Matcher matcher = NUMBERED_MIGRATION.matcher(fileName.substring(0, fileName.length() - 4));
        if (matcher.matches()) {
          latest = Math.max(latest, Integer.parseInt(matcher.group(1)));
        }
      }
    }
    if (latest == 0 && baselineIsEmpty) {
      return "0001_initial";
    }
    return String.format(Locale.ROOT, "%04d_auto_%s", latest + 1,
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
  }

  private static URL toUrl(String path) {
    try {
      return Path.of(path).toUri().toURL();
    } catch (Exception e) {
      throw new IllegalStateException("Invalid runtime classpath entry: " + path, e);
    }
  }

  private static String nonNull(String value) {
    return value == null ? "" : value;
  }
}
