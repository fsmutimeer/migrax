package org.migrax.cli;

import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialects;
import org.migrax.plugin.RuntimeJdbc;

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
    return "migrax init [--json]";
  }

  @Override
  public String description() {
    return """
        Creates the .migrax folder and the migration folder, then shows the entity package,
        database, dialect and naming Migrax detected. Safe to run more than once.
        A folder without a build file or migrax.properties belongs to the nearest folder above
        it that has one: init sets up that folder and says so.""";
  }

  @Override
  public List<String> options() {
    return List.of("--json", "--package", "--locations", "--naming");
  }

  /**
   * With --json: {@code root}, {@code parentFolder} (true when --dir or the current folder is
   * below the folder set up), {@code buildTool}, {@code package}, {@code framework},
   * {@code naming}, {@code database} (password hidden), {@code dialect}, {@code migrations},
   * {@code migrationsCreated}, {@code snapshot}, {@code snapshotExists} and {@code cache}.
   */
  @Override
  public boolean jsonResult() {
    return true;
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
    Path requested = Path.of(context.args().option("--dir", ".")).toAbsolutePath().normalize();
    if (Files.isRegularFile(requested)) {
      requested = requested.getParent();
    }
    boolean parentFolder = !requested.equals(project.root().toAbsolutePath().normalize());
    String buildTool = switch (ProjectBuild.detect(project.root())) {
      case MAVEN -> "Maven";
      case GRADLE -> "Gradle";
      case NONE -> null;
    };
    String database = project.hasUrl()
        ? RuntimeJdbc.sanitizeUrl(project.credentials().url()) : null;
    String dialect = project.hasUrl() ? dialectOrMessage(project.credentials().url()) : null;
    context.result("root", project.root().toAbsolutePath().toString());
    context.result("parentFolder", parentFolder);
    context.result("buildTool", buildTool);
    context.result("package", packageName);
    context.result("framework", project.framework().label());
    context.result("naming", project.naming().id());
    context.result("database", database);
    context.result("dialect", dialect);
    context.result("migrations", project.display(migrations));
    context.result("migrationsCreated", created);
    context.result("snapshot", project.display(project.snapshot()));
    context.result("snapshotExists", Files.exists(project.snapshot()));
    context.result("cache", project.display(state));

    out.println("Migrax is set up in " + project.root().toAbsolutePath());
    if (parentFolder) {
      out.println("(" + requested + " has no pom.xml, build.gradle or migrax.properties, so the "
          + "project folder above it was set up.)");
    }
    out.println();
    row(out, "Build tool", buildTool != null ? buildTool
        : "none found (set MIGRAX_CLASSPATH to your compiled classes and jars)");
    row(out, "Entity package", packageName == null
        ? "not found: pass --package or set MIGRAX_PACKAGE" : packageName);
    row(out, "Framework", project.framework().label());
    row(out, "Naming", project.naming().id() + " (set migrax.naming to change)");
    row(out, "Database", database != null ? database
        : "not configured: set " + project.urlSetting() + ", MIGRAX_DATABASE_URL or --url");
    row(out, "Dialect", dialect != null ? dialect : "detected from the database URL");
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
