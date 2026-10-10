package io.migrax.cli;

import static io.migrax.cli.ExitCode.ERROR;
import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.Dialects;
import io.migrax.model.ModelExtractor;
import io.migrax.model.NamingStrategy;
import io.migrax.plugin.ProjectDatabaseConfig;
import io.migrax.plugin.RuntimeJdbc;
import io.migrax.runner.DatabaseMigrationLock;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;

import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code migrax doctor}: check Java, build, entities and the database connection. */
final class DoctorCommand implements Command {

  @Override
  public String name() {
    return "doctor";
  }

  @Override
  public Group group() {
    return Group.GETTING_STARTED;
  }

  @Override
  public String summary() {
    return "Check Java, build, entities and the database connection";
  }

  @Override
  public String usage() {
    return "migrax doctor";
  }

  @Override
  public String description() {
    return """
        Runs every check Migrax needs and explains how to fix anything that fails.
        Exits with code 1 when a check fails.""";
  }

  @Override
  public List<String> options() {
    return List.of("--package", "--url", "--user", "--password", "--classpath", "--no-build",
        "--extractor");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    Report doctor = new Report(out);
    out.println("Migrax " + Version.current() + " doctor for " + project.root().toAbsolutePath());
    out.println();

    int javaVersion = Runtime.version().feature();
    doctor.check(javaVersion >= 17, "Java " + System.getProperty("java.version"),
        "Migrax needs Java 17 or newer.");
    ProjectBuild.Tool tool = ProjectBuild.detect(project.root());
    doctor.check(tool != ProjectBuild.Tool.NONE || ProjectContext.explicitClasspath(args.raw) != null,
        "Build tool: " + tool.name().toLowerCase(Locale.ROOT),
        "No pom.xml or build.gradle found. Run inside the service folder, or set MIGRAX_CLASSPATH.");
    String packageName = project.packageNameOrNull();
    doctor.check(packageName != null, "Entity package: " + packageName,
        "Pass --package <name> or set MIGRAX_PACKAGE.");
    doctor.info("Framework: " + project.framework().label());
    doctor.info("Naming: " + project.naming().id()
        + " (must match Hibernate; set migrax.naming=spring|jpa|jpa-snake|micronaut to change)");
    NamingStrategy fresh = project.frameworkNaming();
    if (!fresh.equals(project.naming())) {
      doctor.warn("The snapshot was made with '" + project.naming().id() + "' naming, but "
          + project.framework().label()
          + " uses '" + fresh.id() + "'. Migrax keeps '" + project.naming().id()
          + "' so it matches your tables; if the application cannot find columns, set "
          + "migrax.naming=" + fresh.id() + " and generate a migration that renames them.");
    }

    URLClassLoader loader = null;
    try {
      loader = project.loader(true);
      doctor.ok("Project compiled and dependencies resolved");
    } catch (Exception e) {
      doctor.fail("Project build: " + Errors.describe(e), "Fix the build, or pass --no-build.");
    }

    if (loader != null) {
      String jpa = loadable(loader, "jakarta.persistence.Entity") ? "jakarta.persistence"
          : loadable(loader, "javax.persistence.Entity") ? "javax.persistence" : null;
      doctor.check(jpa != null, "JPA API found: " + jpa,
          "Neither jakarta.persistence nor javax.persistence is on the project classpath.");
      String hibernate = ModelExtractor.hibernateVersion(loader);
      if (hibernate != null) {
        if (ModelExtractor.readsMapping(hibernate)) {
          doctor.ok("Hibernate " + hibernate + ": entities are read from Hibernate's own mapping");
        } else {
          doctor.advise("Hibernate " + hibernate + " is older than 5.4, which Migrax cannot read",
              List.of("Migrax scans the JPA annotations instead and follows Hibernate 6 defaults,",
                  "which differ from yours: for example Hibernate " + hibernate + " expects one",
                  "shared hibernate_sequence, and some column types differ. Upgrade to Hibernate",
                  "5.4+ (Spring Boot 2.2+), or check every generated migration and run",
                  "'migrax verify' before applying it."));
        }
      } else {
        doctor.info("Hibernate not found: entities are read by scanning JPA annotations");
      }
      if (packageName != null && jpa != null) {
        try {
          Dialect dialect = project.dialect(true,
              new PrintStream(java.io.OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8));
          ModelExtractor.Result result = project.extract(dialect);
          doctor.check(!result.model().tables().isEmpty(),
              "Entities: " + result.model().tables().size() + " table(s) under " + packageName
                  + " (" + result.source() + ")",
              "No @Entity classes found under " + packageName + ". Check --package.");
        } catch (Exception e) {
          doctor.fail("Entities: " + Errors.describe(e), null);
        }
      }
    }

    Path migrations = project.migrations();
    List<Path> files = MigrationLoader.sqlFiles(migrations);
    doctor.ok("Migrations: " + files.size() + " file(s) in " + project.display(migrations));
    for (ProjectDatabaseConfig.SchemaGeneration setting
        : ProjectDatabaseConfig.schemaGenerationSettings(project.resources(), project.config())) {
      adviseSchemaGeneration(doctor, project, setting);
    }
    Map<String, List<String>> duplicates =
        DuplicateMigrations.unresolved(project, context.migrationRunner());
    if (!duplicates.isEmpty()) {
      doctor.fail("Migrations share numbers: " + duplicates.values(), "Run 'migrax merge'.");
    }
    if (Files.exists(project.snapshot())) {
      doctor.ok("Snapshot: " + project.display(project.snapshot()));
    } else {
      doctor.info("Snapshot: none yet; the first generate uses the database as baseline");
    }

    if (!project.hasUrl()) {
      doctor.fail("Database URL not configured",
          "Set " + project.urlSetting() + ", MIGRAX_DATABASE_URL, or pass --url.");
    } else if (loader != null) {
      doctor.ok("Database URL: " + RuntimeJdbc.sanitizeUrl(project.credentials().url()));
      try (Connection connection = project.connect()) {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        // CockroachDB answers through the PostgreSQL driver, which reports "PostgreSQL 13".
        boolean cockroach = Dialects.forConnection(connection)
            .filter(d -> d.id().equals("cockroachdb")).isPresent();
        doctor.ok("Connected: " + (cockroach ? "CockroachDB (PostgreSQL protocol)" : product
            + " " + metadata.getDatabaseProductVersion())
            + " (driver " + metadata.getDriverVersion() + ")");
        // Take and release the migration lock, as migrate does.
        try (AutoCloseable lock = DatabaseMigrationLock.acquire(connection)) {
          if (product.toLowerCase(Locale.ROOT).contains("h2")) {
            doctor.warn("Locking: H2 locks only within one process; use it for development only");
          } else {
            doctor.ok("Locking: supported");
          }
        } catch (Exception e) {
          doctor.fail("Locking: " + Errors.describe(e), null);
        }
        long pending = context.migrationRunner().statusOf(connection, project.migrationsToRun(true))
            .stream().filter(row -> row.state() == MigrationRunner.State.PENDING).count();
        doctor.info("Pending migrations: " + pending);
      } catch (Exception e) {
        doctor.fail("Database connection: " + Errors.describe(e),
            "Check the URL, user, password and that the JDBC driver is a project dependency.");
      }
    }

    out.println();
    if (doctor.failures == 0) {
      out.println(doctor.warnings == 0 ? "All checks passed."
          : "All checks passed, with " + doctor.warnings + " warning(s) above.");
      return OK;
    }
    out.println(doctor.failures + " check(s) failed.");
    return ERROR;
  }

  private static boolean loadable(ClassLoader loader, String name) {
    try {
      Class.forName(name, false, loader);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /**
   * Explains a Hibernate auto-DDL setting (ddl-auto=update and the like): where it is, what it
   * does to a Migrax-managed database, and the exact line to write instead.
   */
  private static void adviseSchemaGeneration(Report doctor, Project project,
                                             ProjectDatabaseConfig.SchemaGeneration setting) {
    List<String> details = new ArrayList<>();
    String where = setting.file() == null ? "(set outside the resources folder, e.g. a system "
        + "property or environment variable)"
        : "in src/main/resources/" + setting.file() + ", line " + setting.line();
    details.add("Found:  " + setting.key() + " = " + setting.value());
    details.add("        " + where);
    if (setting.key().startsWith("%")) {
      details.add("        (only when the '" + setting.key().substring(1,
          setting.key().indexOf('.')) + "' profile is active)");
    }
    details.add("");
    if (setting.deletesData()) {
      details.add("Why:    '" + setting.value() + "' makes Hibernate drop and recreate tables "
          + "when the application");
      details.add("        starts, which DELETES ALL DATA in them. With Migrax, migrations "
          + "create the tables.");
    } else {
      details.add("Why:    Hibernate adds tables and columns itself when the application "
          + "starts. Migrations");
      details.add("        then fail with \"already exists\", and the database drifts from what "
          + "your migrations");
      details.add("        describe, so other environments end up different.");
    }
    details.add("");
    String original = originalLine(project, setting);
    if (original != null) {
      details.add("Fix:    change   " + original);
      details.add("        to       " + replaceValue(original, setting, "none"));
    } else {
      details.add("Fix:    set " + setting.key() + " to none");
    }
    if (setting.acceptsValidate()) {
      details.add("        (or validate: Hibernate then checks the schema at startup without "
          + "changing it)");
    }
    doctor.advise("Hibernate will change your database schema by itself", details);
  }

  private static String originalLine(Project project,
                                     ProjectDatabaseConfig.SchemaGeneration setting) {
    if (setting.file() == null) {
      return null;
    }
    try {
      List<String> lines = Files.readAllLines(project.resources().resolve(setting.file()));
      return setting.line() >= 1 && setting.line() <= lines.size()
          ? lines.get(setting.line() - 1).trim() : null;
    } catch (Exception e) {
      return null;
    }
  }

  /** The same line with the setting's value replaced, keeping its key and format. */
  static String replaceValue(String line, ProjectDatabaseConfig.SchemaGeneration setting,
                             String value) {
    if (line.startsWith("<")) {
      return line.replaceFirst("value=\"[^\"]*\"", "value=\"" + value + "\"");
    }
    int equals = line.indexOf('=');
    int colon = line.indexOf(':');
    int separator = equals >= 0 && (colon < 0 || equals < colon) ? equals : colon;
    if (separator < 0) {
      return setting.key() + "=" + value;
    }
    // Keep the spacing the line used after '=' or ':' (YAML needs at least one space).
    int start = separator + 1;
    while (start < line.length() && line.charAt(start) == ' ') {
      start++;
    }
    String spacing = line.substring(separator + 1, start);
    if (spacing.isEmpty() && line.charAt(separator) == ':') {
      spacing = " ";
    }
    return line.substring(0, separator + 1) + spacing + value;
  }

  /** The checklist doctor prints, counting failures and warnings. */
  private static final class Report {
    private final PrintStream out;
    int failures;
    int warnings;

    Report(PrintStream out) {
      this.out = out;
    }

    /** A warning with a title and indented detail lines. */
    void advise(String title, List<String> details) {
      warnings++;
      out.println("  [warn]  " + title);
      details.forEach(line -> out.println(line.isEmpty() ? "" : "          " + line));
    }

    void check(boolean passed, String success, String fix) {
      if (passed) {
        ok(success);
      } else {
        fail(success.replaceAll(": null$", "") + " - not found", fix);
      }
    }

    void ok(String message) {
      out.println("  [ok]    " + message);
    }

    void info(String message) {
      out.println("  [info]  " + message);
    }

    void warn(String message) {
      out.println("  [warn]  " + message);
    }

    void fail(String message, String fix) {
      failures++;
      out.println("  [FAIL]  " + message);
      if (fix != null) {
        out.println("          " + fix);
      }
    }
  }
}
