package org.migrax.cli;

import static org.migrax.cli.ExitCode.ERROR;
import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.model.ModelExtractor;
import org.migrax.model.NamingStrategy;
import org.migrax.plugin.ProjectDatabaseConfig;
import org.migrax.plugin.RuntimeJdbc;
import org.migrax.runner.DatabaseMigrationLock;
import org.migrax.runner.MigrationLoader;
import org.migrax.runner.MigrationRunner;

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
  public boolean jsonResult() {
    return true;
  }

  @Override
  public List<String> options() {
    return List.of("--json", "--env", "--package", "--url", "--user", "--password",
        "--password-file", "--classpath", "--no-build", "--extractor", "--schema-file",
        "--scratch-url", "--database");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    Report doctor = new Report(out);
    out.println("Migrax " + Version.current() + " doctor for " + project.root().toAbsolutePath()
        + (project.database() == null ? "" : ", database " + project.database()));
    out.println();

    int javaVersion = Runtime.version().feature();
    doctor.check(javaVersion >= 17, "Java " + System.getProperty("java.version"),
        "Migrax needs Java 17 or newer.");
    ProjectBuild.Tool tool = ProjectBuild.detect(project.root());
    String packageName = project.packageNameOrNull();
    List<Path> files = project.migrationFiles();
    // Migrations but no build file and no entity package: a project that writes its migrations
    // by hand (another language, or the container image), so the Java checks don't apply. A Java
    // project opened in the wrong folder has no migrations there and still fails them.
    // With a schema file, the desired schema comes from SQL, so the Java checks don't apply
    // either.
    Path schemaFile = project.schemaFile();
    boolean withoutJava = schemaFile != null
        || tool == ProjectBuild.Tool.NONE && packageName == null && !files.isEmpty();
    boolean entitiesHaveChecks = false;
    if (schemaFile != null) {
      doctor.info("Schema file instead of entities: " + project.display(schemaFile));
    } else if (withoutJava) {
      doctor.info("No pom.xml, build.gradle or entity package: checking migrations and the "
          + "database only");
    } else {
      doctor.check(tool != ProjectBuild.Tool.NONE
              || ProjectContext.explicitClasspath(args.raw) != null,
          "Build tool: " + tool.name().toLowerCase(Locale.ROOT),
          "No pom.xml or build.gradle found. Run inside the service folder, or set "
              + "MIGRAX_CLASSPATH.");
      doctor.check(packageName != null, "Entity package: " + packageName,
          project.database() == null ? "Pass --package <name> or set MIGRAX_PACKAGE."
              : "Set migrax.databases." + project.database() + ".package or pass --package.");
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
    }

    URLClassLoader loader = null;
    try {
      // Without Java this only loads the JDBC drivers; there is nothing to compile.
      loader = project.loader(true);
      if (!withoutJava) {
        doctor.ok("Project compiled and dependencies resolved");
      }
    } catch (Exception e) {
      doctor.fail("Project build: " + Errors.describe(e), "Fix the build, or pass --no-build.");
    }

    if (loader != null && !withoutJava) {
      String jpa = loadable(loader, "jakarta.persistence.Entity") ? "jakarta.persistence"
          : loadable(loader, "javax.persistence.Entity") ? "javax.persistence" : null;
      Library library = library(loader, packageName, jpa, project.extractorMode());
      if (library != null) {
        doctor.check(library.version() != null, library.version() == null
                ? library.name() + " found"
                : (library.name() + " " + library.version()).trim()
                    + ": entities are read from " + library.name() + "'s own mapping",
            "--extractor " + library.extractor() + " needs " + library.name()
                + " on the project classpath.");
      } else {
        doctor.check(jpa != null, "JPA API found: " + jpa,
            "Neither jakarta.persistence nor javax.persistence is on the project classpath.");
        String hibernate = ModelExtractor.hibernateVersion(loader);
        if (hibernate != null) {
          if (ModelExtractor.readsMapping(hibernate)) {
            doctor.ok("Hibernate " + hibernate
                + ": entities are read from Hibernate's own mapping");
          } else {
            doctor.advise("Hibernate " + hibernate + " is older than 4.0, which Migrax cannot read",
                List.of("Migrax scans the JPA annotations instead and follows Hibernate 6 defaults,",
                    "which differ from yours: for example Hibernate " + hibernate + " expects one",
                    "shared hibernate_sequence, and some column types differ. Upgrade to Hibernate",
                    "4.0+, or check every generated migration and run 'migrax verify' before",
                    "applying it."));
          }
        } else {
          doctor.info("Hibernate not found: entities are read by scanning JPA annotations");
        }
      }
      if (packageName != null && (jpa != null || library != null)) {
        try {
          Dialect dialect = project.dialect(true,
              new PrintStream(java.io.OutputStream.nullOutputStream(), false, StandardCharsets.UTF_8));
          ModelExtractor.Result result = project.extract(dialect);
          entitiesHaveChecks = result.model().tables().stream()
              .anyMatch(t -> !t.checks().isEmpty());
          doctor.check(!result.model().tables().isEmpty(),
              "Entities: " + result.model().tables().size() + " table(s) under " + packageName
                  + " (" + result.source() + ")",
              library != null ? "No " + library.entities() + " found under " + packageName
                  + ": " + library.name() + " needs " + library.needs() + " there. Check "
                  + "--package."
                  : "No @Entity classes found under " + packageName + ". Check --package.");
        } catch (Exception e) {
          doctor.fail("Entities: " + Errors.describe(e), null);
        }
      }
    }

    if (loader != null && schemaFile != null && !project.hasUrl()
        && !Files.exists(project.snapshot())) {
      doctor.info("Schema file: not read yet; configure the database URL so Migrax knows which "
          + "database to read it with");
    } else if (loader != null && schemaFile != null) {
      try {
        Dialect dialect = project.dialect(false,
            new PrintStream(java.io.OutputStream.nullOutputStream(), false,
                StandardCharsets.UTF_8));
        ModelExtractor.Result result = project.extract(dialect);
        entitiesHaveChecks = result.model().tables().stream()
            .anyMatch(t -> !t.checks().isEmpty());
        doctor.check(!result.model().tables().isEmpty(),
            "Schema file: " + result.model().tables().size() + " table(s) in " + result.source(),
            result.source() + " has no CREATE TABLE statements.");
      } catch (Exception e) {
        doctor.fail("Schema file: " + Errors.describe(e), null);
      }
    }

    doctor.ok("Migrations: " + files.size() + " file(s) in " + project.displayMigrations());
    for (ProjectDatabaseConfig.SchemaGeneration setting
        : ProjectDatabaseConfig.schemaGenerationSettings(project.resources(), project.config())) {
      adviseSchemaGeneration(doctor, project, setting);
    }
    Map<String, List<String>> duplicates =
        DuplicateMigrations.unresolved(project, context.migrationRunner());
    if (!duplicates.isEmpty()) {
      doctor.fail("Migrations share numbers: " + duplicates.values(),
          DuplicateMigrations.advice(duplicates));
    }
    if (Files.exists(project.snapshot())) {
      doctor.ok("Snapshot: " + project.display(project.snapshot()));
    } else if (!withoutJava) {
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
        // CockroachDB and YugabyteDB answer through the PostgreSQL driver, which reports
        // "PostgreSQL".
        String version = metadata.getDatabaseProductVersion();
        String server = Dialects.forConnection(connection).map(d -> switch (d.id()) {
          case "cockroachdb" -> "CockroachDB (PostgreSQL protocol)";
          case "yugabytedb" -> "YugabyteDB, PostgreSQL " + version + " protocol";
          case "tidb" -> "TiDB " + version.replaceFirst(".*-TiDB-", "") + " (MySQL protocol)";
          default -> null;
        }).orElse(null);
        doctor.ok("Connected: " + (server != null ? server : product + " " + version)
            + " (driver " + metadata.getDriverVersion() + ")");
        if (entitiesHaveChecks && ignoresChecks(product, metadata.getDatabaseProductVersion())) {
          doctor.warn("Check constraints: MySQL before 8.0.16 accepts them but doesn't enforce "
              + "them; the entities' @Check conditions are not checked on this server");
        }
        if (entitiesHaveChecks && version.contains("TiDB") && !tidbChecksEnabled(connection)) {
          doctor.warn("Check constraints: TiDB ignores them while tidb_enable_check_constraint "
              + "is OFF; the entities' @Check conditions are not checked on this server. Set it "
              + "with SET GLOBAL tidb_enable_check_constraint = ON");
        }
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
        doctor.fail("Database connection: " + Errors.describe(e), withoutJava
            ? "Check the URL, user, password and that the JDBC driver is in MIGRAX_DRIVERS."
            : "Check the URL, user, password and that the JDBC driver is a project dependency.");
      }
    }

    out.println();
    context.result("checks", doctor.checks);
    context.result("failures", doctor.failures);
    context.result("warnings", doctor.warnings);
    if (doctor.failures == 0) {
      out.println(doctor.warnings == 0 ? "All checks passed."
          : "All checks passed, with " + doctor.warnings + " warning(s) above.");
      return OK;
    }
    out.println(doctor.failures + " check(s) failed.");
    return ERROR;
  }

  /** A library whose own mapping is read instead of JPA's, and how doctor talks about it. */
  private record Library(String name, String version, String extractor, String entities,
                         String needs) {}

  /**
   * The library the entities are read through instead of JPA, as the reader decides, or null; a
   * project with such a library and without the JPA API uses it even before it has entities.
   */
  private static Library library(ClassLoader loader, String packageName, String jpa,
                                 ModelExtractor.Mode mode) {
    String springVersion = ModelExtractor.springDataJdbcVersion(loader);
    String micronautVersion = ModelExtractor.micronautDataVersion(loader);
    Library spring = new Library("Spring Data JDBC", springVersion, "spring-data-jdbc",
        "aggregates", "@Table classes or repositories");
    Library micronaut = new Library("Micronaut Data", micronautVersion, "micronaut-data",
        "entities", "@MappedEntity classes");
    if (mode == ModelExtractor.Mode.SPRING_DATA_JDBC) {
      return spring;
    }
    if (mode == ModelExtractor.Mode.MICRONAUT_DATA) {
      return micronaut;
    }
    if (mode != ModelExtractor.Mode.AUTO) {
      return null;
    }
    try {
      if (packageName != null && ModelExtractor.readsSpringDataJdbc(loader, packageName, mode)) {
        return spring;
      }
      if (packageName != null && ModelExtractor.readsMicronautData(loader, packageName, mode)) {
        return micronaut;
      }
    } catch (Exception e) {
      // The entity check below reports what is wrong with the package.
    }
    if (jpa != null) {
      return null;
    }
    return springVersion != null ? spring : micronautVersion != null ? micronaut : null;
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
  /** MySQL before 8.0.16 parses check constraints and ignores them. MariaDB enforces them. */
  static boolean ignoresChecks(String product, String version) {
    if (!product.toLowerCase(Locale.ROOT).contains("mysql") || version == null
        || version.toLowerCase(Locale.ROOT).contains("mariadb") || version.contains("TiDB")) {
      return false;
    }
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)").matcher(version);
    if (!matcher.find()) {
      return false;
    }
    int major = Integer.parseInt(matcher.group(1));
    int minor = Integer.parseInt(matcher.group(2));
    int patch = Integer.parseInt(matcher.group(3));
    return major < 8 || major == 8 && minor == 0 && patch < 16;
  }

  /** TiDB enforces check constraints only with tidb_enable_check_constraint (7.2 and newer). */
  private static boolean tidbChecksEnabled(Connection connection) {
    try (java.sql.Statement statement = connection.createStatement();
         java.sql.ResultSet result = statement.executeQuery(
             "SELECT @@tidb_enable_check_constraint")) {
      return result.next() && result.getInt(1) == 1;
    } catch (java.sql.SQLException e) {
      return false;
    }
  }

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
    /** Every check, for --json. */
    final List<Object> checks = new java.util.ArrayList<>();

    private void record(String status, String message, String fix) {
      checks.add(JsonOut.object("status", status, "message", message, "fix", fix));
    }

    Report(PrintStream out) {
      this.out = out;
    }

    /** A warning with a title and indented detail lines. */
    void advise(String title, List<String> details) {
      warnings++;
      record("warn", title, String.join("\n", details).strip());
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
      record("ok", message, null);
      out.println("  [ok]    " + message);
    }

    void info(String message) {
      record("info", message, null);
      out.println("  [info]  " + message);
    }

    void warn(String message) {
      record("warn", message, null);
      out.println("  [warn]  " + message);
    }

    void fail(String message, String fix) {
      failures++;
      record("fail", message, fix);
      out.println("  [FAIL]  " + message);
      if (fix != null) {
        out.println("          " + fix);
      }
    }
  }
}
