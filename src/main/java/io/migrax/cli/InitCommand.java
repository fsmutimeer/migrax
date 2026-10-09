package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialects;
import io.migrax.plugin.RuntimeJdbc;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@code migrax init}: set up Migrax in this project. */
final class InitCommand implements Command {

  @Override
  public String name() {
    return "init";
  }

  @Override
  public Group group() {
    return Group.GETTING_STARTED;
  }

  @Override
  public String summary() {
    return "Set up Migrax in this project";
  }

  @Override
  public String usage() {
    return "migrax init";
  }

  @Override
  public String description() {
    return """
        Creates the .migrax folder and the migration folder, then shows the entity package,
        database, dialect and naming Migrax detected. Safe to run more than once.""";
  }

  @Override
  public List<String> options() {
    return List.of("--package", "--locations", "--naming");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    PrintStream out = context.out();
    Path state = ProjectBuild.ensureStateDirectory(project.root());
    Path migrations = project.migrations();
    boolean created = !Files.isDirectory(migrations);
    Files.createDirectories(migrations);
    String packageName = project.packageNameOrNull();

    out.println("Migrax is set up in " + project.root().toAbsolutePath());
    out.println();
    row(out, "Build tool", switch (ProjectBuild.detect(project.root())) {
      case MAVEN -> "Maven";
      case GRADLE -> "Gradle";
      case NONE -> "none found (set MIGRAX_CLASSPATH to your compiled classes and jars)";
    });
    row(out, "Entity package", packageName == null
        ? "not found: pass --package or set MIGRAX_PACKAGE" : packageName);
    row(out, "Framework", project.framework().label());
    row(out, "Naming", project.naming().id() + " (set migrax.naming to change)");
    row(out, "Database", project.hasUrl()
        ? RuntimeJdbc.sanitizeUrl(project.credentials().url())
        : "not configured: set " + project.urlSetting() + ", MIGRAX_DATABASE_URL or --url");
    row(out, "Dialect", project.hasUrl() ? dialectOrMessage(project.credentials().url())
        : "detected from the database URL");
    row(out, "Migrations", project.display(migrations) + (created ? " (created)" : ""));
    row(out, "Snapshot", project.display(project.snapshot())
        + (Files.exists(project.snapshot()) ? "" : " (written by the first generate)"));
    row(out, "Local cache", project.display(state) + " (git-ignored files only)");
    out.println();
    out.println("Next steps:");
    out.println("  migrax doctor      check that everything is ready");
    out.println("  migrax generate    create the first migration");
    out.println("  migrax migrate     apply it to the database");
    out.println("Commit the migration files and .migrax/snapshot.json.");
    return OK;
  }

  private static void row(PrintStream out, String label, String value) {
    out.printf("  %-15s %s%n", label, value);
  }

  private static String dialectOrMessage(String url) {
    try {
      return Dialects.fromJdbcUrl(url).id();
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }
}
