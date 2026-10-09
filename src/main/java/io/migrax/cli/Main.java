package io.migrax.cli;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.Dialects;
import io.migrax.dialect.PostgresDialect;
import io.migrax.diff.Migrations;
import io.migrax.diff.Renames;
import io.migrax.diff.SnapshotStore;
import io.migrax.importer.Importer;
import io.migrax.lint.SqlLinter;
import io.migrax.model.ModelExtractor;
import io.migrax.model.NamingStrategy;
import io.migrax.model.SchemaModel;
import io.migrax.ops.AddColumn;
import io.migrax.ops.AddForeignKey;
import io.migrax.ops.AddIndex;
import io.migrax.ops.AddPrimaryKey;
import io.migrax.ops.AddUnique;
import io.migrax.ops.AlterColumn;
import io.migrax.ops.CreateTable;
import io.migrax.ops.DropColumn;
import io.migrax.ops.DropForeignKey;
import io.migrax.ops.DropIndex;
import io.migrax.ops.DropPrimaryKey;
import io.migrax.ops.DropTable;
import io.migrax.ops.DropUnique;
import io.migrax.ops.Operation;
import io.migrax.ops.RenameColumn;
import io.migrax.ops.RenameTable;
import io.migrax.plugin.DatabaseSchemaReader;
import io.migrax.plugin.ProjectDatabaseConfig;
import io.migrax.plugin.RuntimeJdbc;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import io.migrax.runner.SqlScript;
import io.migrax.util.Json;
import io.migrax.util.Log;
import io.migrax.verify.SchemaComparator;
import io.migrax.verify.ScratchDatabase;
import io.migrax.verify.TableStats;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command line interface: {@code migrax <command> [options]}.
 *
 * <p>Exit codes: 0 success, 1 error, 2 differences found ({@code check}, {@code drift}).
 */
public final class Main {
  static final int OK = 0;
  static final int ERROR = 1;
  static final int CHANGES_DETECTED = 2;

  private static final Set<String> VALUE_OPTIONS = Set.of(
      "--package", "--dir", "--locations", "--dialect", "--url", "--user", "--password",
      "--classpath", "--name", "--schema", "--action", "--naming", "--extractor", "--rename",
      "--rename-table", "--to", "--steps", "--image", "--java-package", "--schemas", "--table");
  private static final Set<String> FLAG_OPTIONS = Set.of(
      "--allow-destructive", "--resume", "--dry-run", "--verbose", "-v", "--no-build",
      "--refresh", "--yes", "-y", "--help", "-h", "--version", "-V", "--json", "--safe",
      "--impact", "--strict", "--no-input", "--optimize", "--java", "--entities",
      "--skip-rollbacks", "--all");

  private static final Map<String, Command> COMMANDS = new LinkedHashMap<>();
  private static final Map<String, String> OPTION_HELP = new LinkedHashMap<>();

  static {
    command("init", List.of(), "Set up Migrax in this project", "migrax init",
        """
        Creates the .migrax folder and the migration folder, then shows the entity package,
        database, dialect and naming Migrax detected. Safe to run more than once.""",
        "--package --locations --naming");
    command("doctor", List.of(), "Check Java, build, entities and the database connection",
        "migrax doctor",
        """
        Runs every check Migrax needs and explains how to fix anything that fails.
        Exits with code 1 when a check fails.""",
        "--package --url --user --password --classpath --no-build --extractor");
    command("generate", List.of("makemigrations"), "Create a migration from entity changes",
        "migrax generate [--name <name>] [--allow-destructive] [--safe]",
        """
        Compiles the project if needed, compares the entities with .migrax/snapshot.json and
        writes a numbered SQL file plus a rollback script (rollback/<file>). On the first run,
        with no snapshot, the current database schema is the baseline.
        When a column or table seems renamed, Migrax asks (or pass --rename / --rename-table)
        so the data is kept. Drops need --allow-destructive. --safe (PostgreSQL) builds indexes
        and constraints on existing tables without blocking writes, in a second migration.
        Always review the generated SQL; Migrax lints it for you.""",
        "--name --allow-destructive --safe --rename --rename-table --no-input --dialect "
            + "--package --naming --extractor --url --user --password --schema --locations "
            + "--classpath --no-build --refresh");
    command("plan", List.of("sqlmigrate"), "Preview the SQL that generate would write",
        "migrax plan [--impact] [--json]",
        """
        Shows the operations and SQL for current entity changes without writing files.
        --impact adds the estimated size of each affected table from the database.""",
        "--impact --json --dialect --rename --rename-table --package --naming --extractor "
            + "--url --classpath --no-build --refresh");
    command("migrate", List.of(), "Apply pending migrations to the database",
        "migrax migrate [--dry-run] [--resume] [--schemas a,b]",
        """
        Applies pending SQL and Java migrations in order, then new or changed repeatable
        R__*.sql migrations, with beforeMigrate/afterMigrate callbacks and ${placeholders}.
        --dry-run lists and lints pending migrations without changing the database.
        --schemas runs the migrations once per schema (multi-tenant).
        --resume re-runs a failed migration only if it is marked '-- migrax:resume-safe'.""",
        "--dry-run --resume --schemas --url --user --password --locations --java-package "
            + "--classpath --no-build");
    command("status", List.of("showmigrations"), "Show applied and pending migrations",
        "migrax status [--json]",
        """
        Lists migrations as applied [X], pending [ ] or a problem [!], without changing the
        database. Exits with code 1 when a migration failed, changed or is missing.""",
        "--json --schemas --url --user --password --locations --java-package --classpath "
            + "--no-build");
    command("rollback", List.of(), "Undo applied migrations with their rollback scripts",
        "migrax rollback [--steps n | --to <migration>] [--dry-run] --yes",
        """
        Runs the rollback script of the newest applied migration (or several) and removes it
        from the history. generate writes rollback scripts to rollback/<file>; Java migrations
        roll back with JavaMigration.rollback. Restores structure, not deleted data.""",
        "--steps --to --dry-run --yes --schemas --url --user --password --locations "
            + "--classpath --no-build");
    command("check", List.of(), "Fail when entities changed without a migration (for CI)",
        "migrax check [--json]",
        """
        Exits with code 2 when the entities differ from .migrax/snapshot.json, and with 1 when
        two migrations share a number (run 'migrax merge').""",
        "--json --package --naming --extractor --classpath --no-build --refresh");
    command("lint", List.of(), "Find locking and risky SQL in migrations",
        "migrax lint [files...] [--all] [--strict] [--json]",
        """
        Checks pending migrations (or the given files, or --all) for statements that block
        tables, fail on existing data, or break running application instances, and suggests
        safe alternatives. Exits with 1 on errors, or on warnings with --strict.
        Silence a finding with '-- migrax:lint-ignore MX001' before the statement.""",
        "--all --strict --json --dialect --url --locations");
    command("verify", List.of(), "Apply all migrations to a throwaway database and validate",
        "migrax verify [--url <scratch-db>] [--skip-rollbacks]",
        """
        Starts a throwaway database (H2 in memory, or Docker for other engines), applies every
        migration, then checks the result: Hibernate's schema validation when Hibernate is in
        the project, otherwise a structural comparison with the entities. It also rolls every
        migration back and forward again to prove the rollback scripts work.""",
        "--url --user --password --image --skip-rollbacks --json --dialect --package --naming "
            + "--extractor --locations --classpath --no-build");
    command("drift", List.of(), "Compare the live database with the expected schema",
        "migrax drift [--entities] [--json]",
        """
        Reads the database schema and reports manual changes: missing or extra tables,
        columns, keys and unique constraints, nullability and type differences. Compares
        with the snapshot (what migrations produce) or, with --entities, the entities.
        Exits with code 2 when drift is found.""",
        "--entities --json --schema --url --user --password --package --naming --extractor "
            + "--classpath --no-build");
    command("import", List.of(), "Adopt a database managed by Flyway or Liquibase",
        "migrax import flyway|liquibase",
        """
        flyway: records every migration in flyway_schema_history as applied, so Migrax
        continues where Flyway stopped; V*__ and R__ files keep working.
        liquibase: writes a baseline migration with the current schema and records it.
        Afterwards run 'migrax generate' to start managing changes, and remove Flyway or
        Liquibase from the project.""",
        "--table --name --schema --url --user --password --locations --dialect --classpath "
            + "--no-build");
    command("squash", List.of("squashmigrations"), "Combine old migrations into one",
        "migrax squash --to <migration> [--optimize]",
        """
        Writes one migration that replaces all migrations up to --to. Databases that already
        applied them record it without running it; new databases run just the squashed file.
        --optimize writes only the resulting schema (data statements are dropped).
        Delete the old files once every environment has run migrate.""",
        "--to --name --optimize --dialect --locations");
    command("merge", List.of(), "Fix migrations that two branches numbered the same",
        "migrax merge",
        """
        Renumbers migrations that share a number after a git merge (the one added later moves
        to the next free number) and rebuilds a snapshot that has merge conflicts.""",
        "--dialect --package --naming --extractor --url --locations --classpath --no-build");
    command("new", List.of(), "Create an empty SQL or Java migration",
        "migrax new <name> [--java]",
        """
        Creates the next numbered SQL file for hand-written changes such as data migrations,
        with an empty rollback script. --java creates a JavaMigration class instead.""",
        "--java --java-package --locations");
    command("repair", List.of(), "Fix migration history after a failed or deleted migration",
        "migrax repair <migration>... --action applied|retry|forget --yes",
        """
        Use only after inspecting the database.
          --action applied  every statement took effect: record the migration as applied
          --action retry    you restored the database: clear the failure so it runs again
          --action forget   you deleted applied migration files on purpose: remove them from
                            the history (the database keeps their changes); several at once
        --yes confirms the change to migration history.""",
        "--action --yes --url --user --password --locations --classpath --no-build");
    command("inspect", List.of(), "Print the schema read from your entities as JSON",
        "migrax inspect", "Prints the schema model Migrax builds from the entities.",
        "--package --naming --extractor --dialect --classpath --no-build --refresh");
    command("sql", List.of(), "Print a migration file", "migrax sql <migration>",
        "Prints a migration file from the migration folder.", "--locations");
    command("version", List.of(), "Print the Migrax version", "migrax version",
        "Prints the Migrax version.", "");
    command("help", List.of(), "Show help for a command", "migrax help [command]",
        "Shows general help, or help for one command.", "");

    OPTION_HELP.put("--dir", "--dir <path>            Project folder (default: current folder)");
    OPTION_HELP.put("--package", "--package <name>        Entity package (default: MIGRAX_PACKAGE or pom groupId)");
    OPTION_HELP.put("--name", "--name <name>           Migration file name (default: next number + description)");
    OPTION_HELP.put("--allow-destructive", "--allow-destructive     Allow drops and narrowing type changes");
    OPTION_HELP.put("--safe", "--safe                  Non-blocking indexes and constraints (PostgreSQL)");
    OPTION_HELP.put("--rename", "--rename t.old=new      Treat a column change as a rename (comma-separated)");
    OPTION_HELP.put("--rename-table", "--rename-table old=new  Treat a table change as a rename");
    OPTION_HELP.put("--no-input", "--no-input              Never ask questions");
    OPTION_HELP.put("--dialect", "--dialect <name>        " + String.join(", ", Dialects.NAMES));
    OPTION_HELP.put("--naming", "--naming <strategy>     spring, jpa, jpa-snake or micronaut (default: detected)");
    OPTION_HELP.put("--extractor", "--extractor <mode>      auto, hibernate or annotations (default: auto)");
    OPTION_HELP.put("--url", "--url <jdbc-url>        Database URL (default: application config)");
    OPTION_HELP.put("--user", "--user <name>           Database user");
    OPTION_HELP.put("--password", "--password <secret>     Database password (prefer env vars)");
    OPTION_HELP.put("--schema", "--schema <name>         Database schema to read");
    OPTION_HELP.put("--schemas", "--schemas <a,b>         Run for each schema (multi-tenant)");
    OPTION_HELP.put("--locations", "--locations <path>      Migration folder, e.g. filesystem:db/sql");
    OPTION_HELP.put("--java-package", "--java-package <pkg>    Package of Java migrations (default: db.migration)");
    OPTION_HELP.put("--classpath", "--classpath <paths>     Extra classpath; skips Maven/Gradle resolution");
    OPTION_HELP.put("--no-build", "--no-build              Do not run Maven/Gradle; use compiled classes");
    OPTION_HELP.put("--refresh", "--refresh               Re-resolve dependencies even if cached");
    OPTION_HELP.put("--dry-run", "--dry-run               Show what would happen without doing it");
    OPTION_HELP.put("--resume", "--resume                Re-run a failed resume-safe migration");
    OPTION_HELP.put("--steps", "--steps <n>             Number of migrations to roll back");
    OPTION_HELP.put("--to", "--to <migration>        Last migration to keep / squash up to");
    OPTION_HELP.put("--action", "--action <action>       applied, retry or forget");
    OPTION_HELP.put("--yes", "--yes, -y               Confirm without asking");
    OPTION_HELP.put("--json", "--json                  Machine-readable output");
    OPTION_HELP.put("--impact", "--impact                Show affected table sizes (needs the database)");
    OPTION_HELP.put("--strict", "--strict                Fail on warnings too");
    OPTION_HELP.put("--all", "--all                   Lint every migration, not only pending ones");
    OPTION_HELP.put("--image", "--image <image>         Docker image for the throwaway database");
    OPTION_HELP.put("--skip-rollbacks", "--skip-rollbacks        Do not test rollback scripts");
    OPTION_HELP.put("--entities", "--entities              Compare with the entities instead of the snapshot");
    OPTION_HELP.put("--table", "--table <name>          Flyway history table (default: flyway_schema_history)");
    OPTION_HELP.put("--optimize", "--optimize              Squash to the resulting schema only");
    OPTION_HELP.put("--java", "--java                  Create a Java migration");
  }

  private final Args args;
  private final PrintStream out;
  private final PrintStream err;
  private final BufferedReader in;

  private Main(Args args, PrintStream out, PrintStream err, BufferedReader in) {
    this.args = args;
    this.out = out;
    this.err = err;
    this.in = in;
  }

  public static void main(String[] args) {
    // Only in the standalone CLI process: run() also executes inside Maven and inside
    // applications (Spring Boot starter), whose logging must stay as configured.
    quietHibernateLogging(List.of(args).contains("--verbose") || List.of(args).contains("-v"));
    BufferedReader in = System.console() == null ? null
        : new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    System.exit(run(args, System.out, System.err, in));
  }

  /** Runs one command without asking questions and returns its exit code. */
  public static int run(String[] rawArgs, PrintStream out, PrintStream err) {
    return run(rawArgs, out, err, null);
  }

  /**
   * Runs one command and returns its exit code. Never calls {@link System#exit}.
   *
   * @param in answers to questions, or null when Migrax must not ask
   */
  /**
   * Hides Hibernate's boot banner and hints, which it logs while Migrax reads the mapping;
   * Migrax reports real mapping problems itself. JBoss Logging is pointed at java.util.logging,
   * because otherwise it picks the application's SLF4J/Logback (Micronaut, Spring), whose
   * default configuration prints every Hibernate DEBUG line.
   */
  static void quietHibernateLogging(boolean verbose) {
    HIBERNATE_LOG.setLevel(verbose ? null : java.util.logging.Level.SEVERE);
    if (System.getProperty("org.jboss.logging.provider") == null) {
      System.setProperty("org.jboss.logging.provider", "jdk");
    }
  }

  /** Strong reference so the level set in {@link #quietHibernateLogging} is kept. */
  private static final java.util.logging.Logger HIBERNATE_LOG =
      java.util.logging.Logger.getLogger("org.hibernate");

  public static int run(String[] rawArgs, PrintStream out, PrintStream err, BufferedReader in) {
    List<String> raw = List.of(rawArgs);
    boolean verbose = raw.contains("--verbose") || raw.contains("-v");
    boolean json = raw.contains("--json");
    // In --json mode progress messages go to stderr so stdout stays parseable.
    Log.Sink previous = Log.setSink(Log.console(json ? err : out, err, verbose));
    try {
      Args args = Args.parse(rawArgs);
      return new Main(args, out, err, args.flag("--no-input") ? null : in).dispatch();
    } catch (UsageException e) {
      err.println("error: " + e.getMessage());
      err.println(e.hint != null ? e.hint : "Run 'migrax help' to see the available commands.");
      return ERROR;
    } catch (Throwable e) {
      err.println("error: " + describe(e));
      String hint = hint(e);
      if (hint != null) {
        err.println(hint);
      }
      if (verbose) {
        e.printStackTrace(err);
      } else {
        err.println("Run again with --verbose for details.");
      }
      return ERROR;
    } finally {
      Log.setSink(previous);
    }
  }

  private int dispatch() throws Exception {
    if (args.flag("--version", "-V")) {
      return version();
    }
    if (args.command == null) {
      printHelp();
      return OK;
    }
    Command command = resolveCommand(args.command);
    if (args.flag("--help", "-h")) {
      printCommandHelp(command);
      return OK;
    }
    if (command.name.equals("help")) {
      return help();
    }
    if (command.name.equals("version")) {
      return version();
    }
    try (Project project = new Project(args, err)) {
      return switch (command.name) {
        case "init" -> init(project);
        case "doctor" -> doctor(project);
        case "generate" -> generate(project);
        case "plan" -> plan(project);
        case "migrate" -> migrate(project);
        case "status" -> status(project);
        case "rollback" -> rollback(project);
        case "check" -> check(project);
        case "lint" -> lint(project);
        case "verify" -> verify(project);
        case "drift" -> drift(project);
        case "import" -> importDatabase(project);
        case "squash" -> squash(project);
        case "merge" -> merge(project);
        case "new" -> newMigration(project);
        case "repair" -> repair(project);
        case "inspect" -> inspect(project);
        case "sql" -> sql(project);
        default -> throw new IllegalStateException("Unhandled command " + command.name);
      };
    }
  }

  // ================================================================== basics

  private int help() {
    if (args.positionals.size() > 1) {
      printCommandHelp(resolveCommand(args.positionals.get(1)));
    } else {
      printHelp();
    }
    return OK;
  }

  private int version() {
    out.println("migrax " + versionString());
    return OK;
  }

  private int init(Project project) throws Exception {
    Path state = ProjectBuild.ensureStateDirectory(project.root());
    Path migrations = project.migrations();
    boolean created = !Files.isDirectory(migrations);
    Files.createDirectories(migrations);
    String packageName = project.packageNameOrNull();

    out.println("Migrax is set up in " + project.root().toAbsolutePath());
    out.println();
    row("Build tool", switch (ProjectBuild.detect(project.root())) {
      case MAVEN -> "Maven";
      case GRADLE -> "Gradle";
      case NONE -> "none found (set MIGRAX_CLASSPATH to your compiled classes and jars)";
    });
    row("Entity package", packageName == null
        ? "not found: pass --package or set MIGRAX_PACKAGE" : packageName);
    row("Framework", project.framework().label());
    row("Naming", project.naming().id() + " (set migrax.naming to change)");
    row("Database", project.hasUrl()
        ? RuntimeJdbc.sanitizeUrl(project.credentials().url())
        : "not configured: set " + project.urlSetting() + ", MIGRAX_DATABASE_URL or --url");
    row("Dialect", project.hasUrl() ? dialectOrMessage(project.credentials().url())
        : "detected from the database URL");
    row("Migrations", project.display(migrations) + (created ? " (created)" : ""));
    row("Snapshot", project.display(project.snapshot())
        + (Files.exists(project.snapshot()) ? "" : " (written by the first generate)"));
    row("Local cache", project.display(state) + " (git-ignored files only)");
    out.println();
    out.println("Next steps:");
    out.println("  migrax doctor      check that everything is ready");
    out.println("  migrax generate    create the first migration");
    out.println("  migrax migrate     apply it to the database");
    out.println("Commit the migration files and .migrax/snapshot.json.");
    return OK;
  }

  private int doctor(Project project) throws Exception {
    Doctor doctor = new Doctor();
    out.println("Migrax " + versionString() + " doctor for " + project.root().toAbsolutePath());
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
      doctor.fail("Project build: " + describe(e), "Fix the build, or pass --no-build.");
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
          doctor.fail("Entities: " + describe(e), null);
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
    Map<String, List<String>> duplicates = unresolvedDuplicates(project);
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
        doctor.ok("Connected: " + product + " " + metadata.getDatabaseProductVersion()
            + " (driver " + metadata.getDriverVersion() + ")");
        String lower = product.toLowerCase(Locale.ROOT);
        if (lower.contains("h2")) {
          doctor.warn("Locking: H2 locks only within one process; use it for development only");
        } else if (lower.contains("oracle")) {
          doctor.info("Locking: Oracle needs EXECUTE on DBMS_LOCK for the migration account");
        } else if (lower.contains("postgres") || lower.contains("mysql")
            || lower.contains("mariadb") || lower.contains("sql server")) {
          doctor.ok("Locking: supported");
        } else {
          doctor.fail("Locking: " + product + " is not supported", null);
        }
        long pending = new MigrationRunner().statusOf(connection, project.migrationsToRun(true))
            .stream().filter(row -> row.state() == MigrationRunner.State.PENDING).count();
        doctor.info("Pending migrations: " + pending);
      } catch (Exception e) {
        doctor.fail("Database connection: " + describe(e),
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

  // ================================================================== generate & plan

  /** The models and operations for the current entity changes. */
  private record Changes(SchemaModel previous, SchemaModel current, List<Operation> operations,
                         List<Renames.TableRename> tableRenames,
                         List<Renames.ColumnRename> columnRenames, String source) {}

  /**
   * Compares the entities with the snapshot (or the database on the first run), asking about
   * likely renames.
   *
   * @param useDatabaseBaseline read the database when there is no snapshot yet
   */
  private Changes changes(Project project, Dialect dialect, boolean useDatabaseBaseline,
                          boolean ask) throws Exception {
    ModelExtractor.Result extracted = project.extract(dialect);
    SchemaModel current = extracted.model();
    boolean hasSnapshot = Files.exists(project.snapshot());
    SchemaModel previous;
    if (hasSnapshot) {
      previous = SnapshotStore.load(project.snapshot());
    } else if (useDatabaseBaseline) {
      try (Connection connection = project.connect()) {
        previous = DatabaseSchemaReader.read(connection, args.option("--schema"))
            .withSequencesFrom(current, DatabaseSchemaReader.sequenceNames(connection));
        if (DatabaseSchemaReader.foldsNames(connection)) {
          previous = previous.withNameCaseFrom(current);
        }
      }
      Log.info("No snapshot yet: using the current database schema as the baseline.");
    } else {
      previous = SchemaModel.empty();
    }
    current = current.withConstraintNamesFrom(previous)
        .withCompatibleTypesFrom(previous, (existing, wanted) -> sameType(dialect, existing, wanted));

    List<Renames.TableRename> tables =
        new ArrayList<>(Renames.parseTables(args.option("--rename-table")));
    SchemaModel renamedTables = Renames.applyTables(previous, tables);
    if (ask) {
      for (Renames.TableRename candidate : Renames.tableCandidates(renamedTables, current)) {
        if (confirm("Did you rename table " + candidate.from() + " to " + candidate.to() + "?",
            "--rename-table " + candidate)) {
          tables.add(candidate);
        }
      }
    }
    renamedTables = Renames.applyTables(previous, tables);
    List<Renames.ColumnRename> columns =
        new ArrayList<>(Renames.parseColumns(args.option("--rename")));
    if (ask) {
      SchemaModel renamed = Renames.applyColumns(renamedTables, columns);
      for (Renames.ColumnRename candidate : Renames.columnCandidates(renamed, current)) {
        if (confirm("Did you rename " + candidate.table() + "." + candidate.from() + " to "
            + candidate.table() + "." + candidate.to() + "?", "--rename " + candidate)) {
          columns.add(candidate);
        }
      }
    }
    List<Operation> operations = Renames.diff(previous, current, tables, columns);
    return new Changes(previous, current, operations, tables, columns, extracted.source());
  }

  /** Asks a yes/no question, or prints how to answer it with an option when Migrax can't ask. */
  private boolean confirm(String question, String option) throws Exception {
    if (in == null) {
      out.println("Possible rename: " + question.replace("Did you rename ", "").replace("?", "")
          + ". To keep the data, rerun with " + option + ".");
      return false;
    }
    out.print(question + " [y/N] ");
    out.flush();
    String answer = in.readLine();
    return answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
  }

  private int generate(Project project) throws Exception {
    Path migrations = project.migrations();
    requireNoDuplicates(project);
    boolean hasSnapshot = Files.exists(project.snapshot());
    if (!hasSnapshot && !project.hasUrl()) {
      throw new UsageException("No database URL found, and there is no snapshot yet.",
          "The first generate reads the current database as its baseline. Set "
              + project.urlSetting() + ", MIGRAX_DATABASE_URL, or --url.");
    }
    Dialect dialect = project.dialect(false, out);
    Changes changes = changes(project, dialect, true, true);
    if (changes.current().tables().isEmpty()) {
      throw new UsageException("No JPA entities found under package '" + project.packageName() + "'.",
          "Pass --package <name> or set MIGRAX_PACKAGE. Run 'migrax doctor' to diagnose.");
    }
    Log.debug("Entities read from {}.", changes.source());
    List<Operation> operations = changes.operations();
    if (operations.isEmpty()) {
      out.println("No changes detected.");
      if (!hasSnapshot) {
        SnapshotStore.save(project.snapshot(), changes.current(), dialect.id(),
            recordedNaming(project));
        out.println("Saved the entity schema as the baseline snapshot: "
            + project.display(project.snapshot()));
      }
      return OK;
    }
    List<Operation> destructive = operations.stream().filter(Operation::destructive).toList();
    if (!destructive.isEmpty() && !args.flag("--allow-destructive")) {
      printOperations(operations, dialect, null);
      throw new UsageException(destructive.size()
          + " destructive change(s) detected (marked [DESTRUCTIVE] above).",
          "Review them, then run 'migrax generate --allow-destructive'. If a column or table "
              + "was renamed, pass --rename table.old=new or --rename-table old=new instead.");
    }
    Migrations.warnings(operations, changes.previous()).forEach(Log::warn);

    // --safe on PostgreSQL: indexes and constraints on existing tables go to a second,
    // non-transactional migration that builds them without blocking writes.
    List<Operation> main = new ArrayList<>(operations);
    List<Operation> online = new ArrayList<>();
    if (args.flag("--safe")) {
      if (!(dialect instanceof PostgresDialect)) {
        Log.warn("--safe applies to PostgreSQL only; generating a normal migration.");
      } else {
        Set<String> newTables = new HashSet<>();
        operations.stream().filter(o -> o instanceof CreateTable)
            .forEach(o -> newTables.add(((CreateTable) o).table().name()));
        for (Operation operation : operations) {
          String table = table(operation);
          boolean existing = table != null && !newTables.contains(table);
          if (existing && (operation instanceof AddIndex || operation instanceof AddUnique
              || operation instanceof AddForeignKey)) {
            online.add(operation);
          }
        }
        main.removeAll(online);
      }
    }

    Files.createDirectories(migrations);
    String name = migrationName(migrations, changes.previous(), main.isEmpty() ? online : main);
    Path file = migrations.resolve(name + ".sql");
    if (Files.exists(file)) {
      throw new UsageException("Migration already exists: " + project.display(file),
          "Choose another --name.");
    }
    List<Path> written = new ArrayList<>();
    List<Operation> reverse = Renames.reverse(changes.previous(), changes.current(),
        changes.tableRenames(), changes.columnRenames());
    if (!main.isEmpty()) {
      Files.writeString(file, render(dialect, main, null));
      written.add(file);
      List<Operation> mainReverse = new ArrayList<>(reverse);
      mainReverse.removeIf(o -> undoesAny(o, online));
      writeRollback(migrations, file.getFileName().toString(), dialect, mainReverse, main);
      SnapshotStore.save(SnapshotStore.historySnapshot(project.root(), name),
          withoutOnline(changes.current(), online), dialect.id());
    }
    Path onlineFile = null;
    if (!online.isEmpty()) {
      String onlineName = main.isEmpty() ? name
          : Migrations.nextName(migrations, false, online) + "_online";
      onlineFile = migrations.resolve(onlineName + ".sql");
      PostgresDialect postgres = (PostgresDialect) dialect;
      StringBuilder sql = new StringBuilder("-- Generated by Migrax " + versionString()
          + " for postgresql (--safe)\n-- migrax:no-transaction\n"
          + "-- Builds indexes and constraints without blocking writes; each statement commits "
          + "on its own.\n\n");
      for (Operation operation : online) {
        sql.append(postgres.renderOnline(operation)).append(";\n");
      }
      Files.writeString(onlineFile, sql);
      written.add(onlineFile);
      List<Operation> onlineReverse = reverse.stream().filter(o -> undoesAny(o, online)).toList();
      writeRollback(migrations, onlineFile.getFileName().toString(), dialect, onlineReverse,
          online);
      SnapshotStore.save(SnapshotStore.historySnapshot(project.root(), onlineName),
          changes.current(), dialect.id());
    }
    SnapshotStore.save(project.snapshot(), changes.current(), dialect.id(),
            recordedNaming(project));

    for (Path path : written) {
      out.println("Created " + project.display(path) + ":");
      List<Operation> listed = path.equals(onlineFile) ? online : main;
      for (Operation operation : listed) {
        out.println("  - " + Migrations.summary(operation));
      }
    }
    out.println("Rollback script" + (written.size() > 1 ? "s" : "") + " written to "
        + project.display(migrations.resolve(MigrationLoader.ROLLBACK_FOLDER)) + ".");
    for (Path path : written) {
      printFindings(SqlLinter.lint(project.display(path), Files.readString(path), dialect.id()));
    }
    out.println("Review the SQL, then run 'migrax migrate'.");
    return OK;
  }

  private String migrationName(Path migrations, SchemaModel previous, List<Operation> operations)
      throws Exception {
    String name = args.option("--name");
    if (Project.blank(name)) {
      return Migrations.nextName(migrations, previous.tables().isEmpty(), operations);
    }
    name = name.trim();
    if (name.endsWith(".sql")) {
      name = name.substring(0, name.length() - ".sql".length());
    }
    if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
      throw new UsageException("Invalid migration name '" + name + "'.",
          "Use letters, digits, '_', '.' and '-', for example 0002_add_email.");
    }
    // A plain --name gets the next number, so the file
    // is ordered and applied; 'migrate' skips SQL files without a version.
    if (MigrationRunner.versionParts(name + ".sql").isEmpty()) {
      name = String.format(Locale.ROOT, "%04d_%s", nextNumber(migrations), name);
    }
    return name;
  }

  private static String render(Dialect dialect, List<Operation> operations, String header) {
    StringBuilder sql = new StringBuilder("-- Generated by Migrax ").append(versionString())
        .append(" for ").append(dialect.id()).append("\n");
    if (header != null) {
      sql.append(header);
    }
    sql.append("\n");
    for (Operation operation : operations) {
      sql.append(dialect.render(operation)).append(";\n");
    }
    return sql.toString();
  }

  private static void writeRollback(Path migrations, String fileName, Dialect dialect,
                                    List<Operation> reverse, List<Operation> forward)
      throws Exception {
    Path folder = migrations.resolve(MigrationLoader.ROLLBACK_FOLDER);
    Files.createDirectories(folder);
    StringBuilder header = new StringBuilder("-- Rollback for ").append(fileName)
        .append(". 'migrax rollback' runs it.\n");
    for (Operation operation : forward) {
      String subject = Migrations.summary(operation).replaceFirst("^\\S+ \\S+ ", "");
      if (operation instanceof DropColumn || operation instanceof DropTable
          || operation instanceof AlterColumn alter && alter.destructive()) {
        header.append("-- WARNING: restores the structure of ").append(subject)
            .append(" but not its data.\n");
      } else if (operation instanceof AddColumn || operation instanceof CreateTable) {
        header.append("-- Note: deletes data written to ").append(subject)
            .append(" since the migration ran.\n");
      }
    }
    String sql = reverse.isEmpty()
        ? "-- Generated by Migrax " + versionString() + " for " + dialect.id() + "\n" + header
            + "-- Nothing to undo.\n"
        : render(dialect, reverse, header.toString());
    Files.writeString(folder.resolve(fileName), sql);
  }

  /** True when a rollback operation undoes one of the given forward operations. */
  private static boolean undoesAny(Operation reverse, List<Operation> forward) {
    for (Operation operation : forward) {
      if (reverse instanceof DropIndex drop && operation instanceof AddIndex add
          && drop.name().equals(add.index().name())) {
        return true;
      }
      if (reverse instanceof DropUnique drop && operation instanceof AddUnique add
          && drop.name().equals(add.name())) {
        return true;
      }
      if (reverse instanceof DropForeignKey drop && operation instanceof AddForeignKey add
          && drop.name().equals(add.fk().name())) {
        return true;
      }
    }
    return false;
  }

  /** The model as it is after the main migration of a --safe split. */
  private static SchemaModel withoutOnline(SchemaModel model, List<Operation> online) {
    if (online.isEmpty()) {
      return model;
    }
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (SchemaModel.Table table : model.tables()) {
      List<SchemaModel.Index> indexes = table.indexes().stream().filter(i -> online.stream()
          .noneMatch(o -> o instanceof AddIndex a && a.table().equals(table.name())
              && a.index().name().equals(i.name()))).toList();
      List<SchemaModel.ForeignKey> keys = table.foreignKeys().stream().filter(k -> online.stream()
          .noneMatch(o -> o instanceof AddForeignKey a && a.table().equals(table.name())
              && a.fk().name().equals(k.name()))).toList();
      List<SchemaModel.Column> columns = table.columns().stream().map(c -> online.stream()
          .anyMatch(o -> o instanceof AddUnique u && u.table().equals(table.name())
              && u.column().equals(c.name()))
          ? new SchemaModel.Column(c.name(), c.sqlType(), c.nullable(), c.length(), c.precision(),
              c.scale(), c.defaultValue(), false, c.identity(), c.sequenceName(), c.logicalType())
          : c).toList();
      tables.add(new SchemaModel.Table(table.name(), columns, table.primaryKey(), indexes, keys));
    }
    return new SchemaModel(tables, model.sequences());
  }

  private int plan(Project project) throws Exception {
    Dialect dialect = project.dialect(true, args.flag("--json") ? err : out);
    Changes changes = changes(project, dialect, project.hasUrl(), false);
    if (!Files.exists(project.snapshot()) && !project.hasUrl() && !args.flag("--json")) {
      out.println("No snapshot yet: comparing with an empty schema. 'migrax generate' will "
          + "use the database as the baseline instead.");
    }
    Map<String, TableStats> stats = new HashMap<>();
    if (args.flag("--impact") && !changes.operations().isEmpty()) {
      try (Connection connection = project.connect()) {
        for (Operation operation : changes.operations()) {
          String table = table(operation);
          if (table != null && !(operation instanceof CreateTable)) {
            stats.computeIfAbsent(table, t -> TableStats.estimate(connection, t));
          }
        }
      }
    }
    String sql = render(dialect, changes.operations(), null);
    List<SqlLinter.Finding> findings = SqlLinter.lint("plan", sql, dialect.id());
    if (args.flag("--json")) {
      List<Object> operations = new ArrayList<>();
      for (Operation operation : changes.operations()) {
        TableStats table = stats.get(table(operation));
        operations.add(JsonOut.object("kind", operation.kind(),
            "summary", Migrations.summary(operation), "sql", dialect.render(operation),
            "destructive", operation.destructive(), "table", table(operation),
            "rows", table == null ? null : table.rows(),
            "bytes", table == null ? null : table.bytes()));
      }
      out.println(JsonOut.write(JsonOut.object("dialect", dialect.id(),
          "source", changes.source(), "operations", operations,
          "lint", findingsJson(findings))));
      return OK;
    }
    if (changes.operations().isEmpty()) {
      out.println("No changes detected.");
      return OK;
    }
    printOperations(changes.operations(), dialect, stats);
    printFindings(findings);
    return OK;
  }

  private void printOperations(List<Operation> operations, Dialect dialect,
                               Map<String, TableStats> stats) {
    out.println(operations.size() + " change(s) for " + dialect.id() + ":");
    for (int i = 0; i < operations.size(); i++) {
      Operation operation = operations.get(i);
      out.printf("%3d. %s%s%n", i + 1, Migrations.summary(operation),
          operation.destructive() ? "  [DESTRUCTIVE]" : "");
      for (String line : dialect.render(operation).split("\n")) {
        out.println("     " + line + (line.endsWith(";") ? "" : ";"));
      }
      TableStats table = stats == null ? null : stats.get(table(operation));
      if (table != null) {
        out.println("     impact: " + table(operation) + " " + table.describe());
      }
    }
  }

  /** The table an operation touches, or null. */
  static String table(Operation o) {
    if (o instanceof CreateTable x) return x.table().name();
    if (o instanceof DropTable x) return x.table();
    if (o instanceof AddColumn x) return x.table();
    if (o instanceof DropColumn x) return x.table();
    if (o instanceof AlterColumn x) return x.table();
    if (o instanceof RenameColumn x) return x.table();
    if (o instanceof RenameTable x) return x.from();
    if (o instanceof AddIndex x) return x.table();
    if (o instanceof DropIndex x) return x.table();
    if (o instanceof AddForeignKey x) return x.table();
    if (o instanceof DropForeignKey x) return x.table();
    if (o instanceof AddUnique x) return x.table();
    if (o instanceof DropUnique x) return x.table();
    if (o instanceof AddPrimaryKey x) return x.table();
    if (o instanceof DropPrimaryKey x) return x.table();
    return null;
  }

  private int check(Project project) throws Exception {
    Map<String, List<String>> duplicates = unresolvedDuplicates(project);
    Dialect dialect = project.dialect(true, err);
    Changes changes = changes(project, dialect, project.hasUrl(), false);
    if (args.flag("--json")) {
      out.println(JsonOut.write(JsonOut.object(
          "ok", duplicates.isEmpty() && changes.operations().isEmpty(),
          "changes", changes.operations().stream().map(Migrations::summary).toList(),
          "conflicts", new ArrayList<>(duplicates.values()))));
    } else {
      if (!duplicates.isEmpty()) {
        err.println("Migrations share numbers: " + duplicates.values()
            + ". Run 'migrax merge'.");
      }
      if (changes.operations().isEmpty()) {
        if (duplicates.isEmpty()) {
          out.println("OK: entities match the snapshot.");
        }
      } else {
        err.println(changes.operations().size() + " entity change(s) have no migration:");
        for (Operation operation : changes.operations()) {
          err.println("  - " + Migrations.summary(operation));
        }
        err.println("Run 'migrax generate' and commit the result.");
      }
    }
    if (!duplicates.isEmpty()) {
      return ERROR;
    }
    return changes.operations().isEmpty() ? OK : CHANGES_DETECTED;
  }

  private int inspect(Project project) throws Exception {
    Dialect dialect = project.dialect(true, err);
    out.println(Json.write(project.extract(dialect).model()));
    return OK;
  }

  // ================================================================== migrate & history

  private int migrate(Project project) throws Exception {
    project.requireUrl();
    Path folder = project.migrations();
    List<Migration> migrations = project.migrationsToRun(true);
    if (migrations.isEmpty() && !hasHistory(project)) {
      out.println("No migrations in " + project.display(folder) + ". Run 'migrax generate' first.");
      return OK;
    }
    long versioned = migrations.stream().filter(m -> m.kind() == Migration.Kind.VERSIONED).count();
    MigrationRunner runner = new MigrationRunner();
    try (Connection connection = project.connect()) {
      for (String schema : schemasOrDefault(project)) {
        if (schema != null) {
          Project.useSchema(connection, schema);
          out.println("Schema " + schema + ":");
        }
        if (args.flag("--dry-run")) {
          List<MigrationRunner.MigrationStatus> pending = runner.statusOf(connection, migrations)
              .stream().filter(row -> row.state() == MigrationRunner.State.PENDING
                  || row.state() == MigrationRunner.State.OUTDATED).toList();
          if (pending.isEmpty()) {
            out.println("Nothing to migrate: all " + versioned + " migration(s) are applied.");
            continue;
          }
          out.println(pending.size() + " migration(s) would be applied:");
          pending.forEach(row -> out.println("  " + row.version()));
          String dialect = Dialects.fromJdbcUrl(project.credentials().url()).id();
          for (MigrationRunner.MigrationStatus row : pending) {
            Path file = folder.resolve(row.version());
            if (Files.isRegularFile(file)) {
              printFindings(SqlLinter.lint(project.display(file), Files.readString(file), dialect));
            }
          }
          continue;
        }
        int applied = runner.migrateAll(connection, migrations, project.runOptions());
        if (applied == 0) {
          out.println("Nothing to migrate: all " + versioned + " migration(s) are applied.");
        } else {
          out.println("Applied " + applied + " migration(s); " + versioned + " total in "
              + project.display(folder) + ".");
        }
      }
      return OK;
    }
  }

  private static List<String> schemasOrDefault(Project project) throws Exception {
    List<String> schemas = project.schemas();
    List<String> result = new ArrayList<>();
    if (schemas.isEmpty()) {
      result.add(null);
    } else {
      result.addAll(schemas);
    }
    return result;
  }

  private int status(Project project) throws Exception {
    project.requireUrl();
    Path folder = project.migrations();
    List<Migration> migrations = project.migrationsToRun(true);
    boolean problems = false;
    List<Object> json = new ArrayList<>();
    try (Connection connection = project.connect()) {
      for (String schema : schemasOrDefault(project)) {
        if (schema != null) {
          Project.useSchema(connection, schema);
        }
        List<MigrationRunner.MigrationStatus> rows =
            new MigrationRunner().statusOf(connection, migrations);
        problems |= rows.stream().anyMatch(row -> row.state() == MigrationRunner.State.FAILED
            || row.state() == MigrationRunner.State.CHANGED
            || row.state() == MigrationRunner.State.MISSING);
        if (args.flag("--json")) {
          json.add(JsonOut.object("schema", schema, "migrations", rows.stream().map(row ->
              (Object) JsonOut.object("version", row.version(), "state", row.state(),
                  "detail", row.detail())).toList()));
          continue;
        }
        out.println("Migrations in " + project.display(folder) + " for "
            + RuntimeJdbc.sanitizeUrl(project.credentials().url())
            + (schema == null ? "" : ", schema " + schema));
        if (rows.isEmpty()) {
          out.println("  (none) Run 'migrax generate' to create the first migration.");
          continue;
        }
        int width = rows.stream().mapToInt(row -> row.version().length()).max().orElse(10);
        Map<MigrationRunner.State, Integer> counts = new LinkedHashMap<>();
        for (MigrationRunner.MigrationStatus row : rows) {
          counts.merge(row.state(), 1, Integer::sum);
          String mark = switch (row.state()) {
            case APPLIED -> "[X]";
            case PENDING -> "[ ]";
            case OUTDATED -> "[~]";
            default -> "[!]";
          };
          String detail = switch (row.state()) {
            case APPLIED, OUTDATED -> row.detail();
            case PENDING -> row.detail().isEmpty() ? "pending" : "pending: " + row.detail();
            default -> row.state().name() + ": " + row.detail();
          };
          out.printf("  %s %-" + width + "s  %s%n", mark, row.version(), detail);
        }
        List<String> summary = new ArrayList<>();
        counts.forEach((state, count) ->
            summary.add(count + " " + state.name().toLowerCase(Locale.ROOT)));
        out.println(String.join(", ", summary) + ".");
      }
    }
    if (args.flag("--json")) {
      out.println(JsonOut.write(json.size() == 1 ? json.get(0) : json));
    } else if (problems) {
      err.println("Some migrations need attention. See 'migrax help repair'.");
    }
    return problems ? ERROR : OK;
  }

  private int rollback(Project project) throws Exception {
    project.requireUrl();
    Path folder = project.migrations();
    List<Migration> migrations = project.migrationsToRun(true);
    Map<String, Migration> java = new HashMap<>();
    migrations.stream().filter(Migration::isJava).forEach(m -> java.put(m.version(), m));
    MigrationRunner runner = new MigrationRunner();
    try (Connection connection = project.connect()) {
      for (String schema : schemasOrDefault(project)) {
        if (schema != null) {
          Project.useSchema(connection, schema);
          out.println("Schema " + schema + ":");
        }
        List<MigrationRunner.AppliedMigration> applied = runner.applied(connection).stream()
            .filter(m -> !m.version().startsWith("R__")).toList();
        List<MigrationRunner.AppliedMigration> targets = new ArrayList<>();
        String to = args.option("--to");
        if (!Project.blank(to)) {
          String keep = resolveVersion(to, applied.stream()
              .map(MigrationRunner.AppliedMigration::version).toList());
          for (MigrationRunner.AppliedMigration migration : applied) {
            if (MigrationRunner.compareMigrationFilenames(migration.version(), keep) > 0) {
              targets.add(0, migration);
            }
          }
        } else {
          int steps;
          try {
            steps = Integer.parseInt(args.option("--steps", "1"));
          } catch (NumberFormatException e) {
            throw new UsageException("--steps needs a number.", null);
          }
          for (int i = applied.size() - 1; i >= 0 && targets.size() < steps; i--) {
            targets.add(applied.get(i));
          }
        }
        if (targets.isEmpty()) {
          out.println("Nothing to roll back.");
          continue;
        }
        List<Migration> scripts = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (MigrationRunner.AppliedMigration target : targets) {
          if (java.containsKey(target.version())) {
            scripts.add(java.get(target.version()));
            continue;
          }
          Path script = MigrationLoader.rollbackScript(folder, target.version());
          if (script == null) {
            missing.add(target.version());
          } else {
            scripts.add(Migration.ofSql(target.version(), Files.readString(script)));
          }
        }
        if (!missing.isEmpty()) {
          throw new UsageException("No rollback script for " + String.join(", ", missing) + ".",
              "Write one in " + project.display(folder.resolve(MigrationLoader.ROLLBACK_FOLDER))
                  + "/<same file name>, then run rollback again.");
        }
        out.println((args.flag("--dry-run") ? "Would roll back " : "Rolling back ")
            + targets.size() + " migration(s), newest first:");
        targets.forEach(t -> out.println("  " + t.version()));
        if (args.flag("--dry-run")) {
          continue;
        }
        if (!args.flag("--yes", "-y")
            && !confirmAction("Roll back now? Data written to removed objects is lost.")) {
          throw new UsageException("Rollback needs confirmation.",
              "Back up the database, then rerun with --yes.");
        }
        for (int i = 0; i < targets.size(); i++) {
          runner.rollback(connection, targets.get(i).version(), scripts.get(i),
              project.runOptions());
        }
        out.println("Rolled back " + targets.size() + " migration(s).");
      }
    }
    return OK;
  }

  private boolean confirmAction(String question) throws Exception {
    if (in == null) {
      return false;
    }
    out.print(question + " [y/N] ");
    out.flush();
    String answer = in.readLine();
    return answer != null && answer.trim().toLowerCase(Locale.ROOT).startsWith("y");
  }

  private static String resolveVersion(String wanted, List<String> versions) {
    for (String version : versions) {
      if (version.equals(wanted) || version.equals(wanted + ".sql")) {
        return version;
      }
    }
    for (String version : versions) {
      if (version.startsWith(wanted)) {
        return version;
      }
    }
    throw new UsageException("Migration '" + wanted + "' is not applied here.",
        "Run 'migrax status' to see applied migrations.");
  }

  /**
   * Whether the database recorded applied migrations. With an empty folder that means the files
   * were deleted, and migrating must report them instead of saying there is nothing to do.
   */
  private static boolean hasHistory(Project project) throws Exception {
    try (Connection connection = project.connect()) {
      return !new MigrationRunner().applied(connection).isEmpty();
    }
  }

  /** True when the dialect writes the same SQL type for both columns. */
  private static boolean sameType(Dialect dialect, SchemaModel.Column existing,
                                  SchemaModel.Column wanted) {
    try {
      return dialect.columnType(existing).equalsIgnoreCase(dialect.columnType(wanted));
    } catch (RuntimeException e) {
      return false; // a type this dialect can't write: let the normal comparison decide
    }
  }

  private int repair(Project project) throws Exception {
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing the migration to repair.",
          "Usage: migrax repair <migration>... --action applied|retry|forget --yes");
    }
    String action = args.option("--action");
    if (Project.blank(action)) {
      throw new UsageException("Missing --action.",
          "Use --action applied (all statements took effect), --action retry (you restored "
              + "the database) or --action forget (you deleted the file on purpose).");
    }
    if (!args.flag("--yes", "-y")) {
      throw new UsageException("Repair changes migration history and needs confirmation.",
          "Inspect and back up the database first, then rerun with --yes.");
    }
    project.requireUrl();
    List<String> names = args.positionals.subList(1, args.positionals.size());
    if (action.trim().equalsIgnoreCase("forget")) {
      return forget(project, names);
    }
    if (names.size() > 1) {
      throw new UsageException("Repair one failed migration at a time.",
          "Only --action forget accepts several migrations.");
    }
    String wanted = names.get(0);
    Migration migration = null;
    for (Migration candidate : project.migrationsToRun(true)) {
      if (candidate.version().equals(wanted) || candidate.version().equals(wanted + ".sql")) {
        migration = candidate;
      }
    }
    if (migration == null) {
      migration = Migration.load(migrationFile(project, wanted));
    }
    try (Connection connection = project.connect()) {
      new MigrationRunner().repair(connection, migration, action, true);
    }
    out.println("Repaired history for " + migration.version() + " (action: "
        + action.trim().toLowerCase(Locale.ROOT) + "). Keep a record of this repair.");
    return OK;
  }

  /** Removes deleted migration files from the history; refuses files that still exist. */
  private int forget(Project project, List<String> wanted) throws Exception {
    List<String> versions = new ArrayList<>();
    for (String name : wanted) {
      String version = name.endsWith(".sql") || name.startsWith("V") ? name : name + ".sql";
      for (Migration migration : project.migrationsToRun(true)) {
        if (migration.version().equals(name) || migration.version().equals(version)) {
          throw new UsageException(migration.version() + " still exists in "
              + project.display(project.migrations()) + ".",
              "Forget only migrations whose files you deleted: 'migrate' would run it again. "
                  + "To undo it instead, use 'migrax rollback'.");
        }
      }
      versions.add(version);
    }
    MigrationRunner runner = new MigrationRunner();
    try (Connection connection = project.connect()) {
      for (String version : versions) {
        if (runner.forget(connection, version, true)) {
          out.println("Removed " + version + " from the migration history.");
        } else {
          out.println(version + " is not in the migration history; nothing to forget.");
        }
      }
    }
    out.println("The database keeps the changes these migrations made. "
        + "Run 'migrax status' to check the history.");
    return OK;
  }

  private int sql(Project project) throws Exception {
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing the migration file.", "Usage: migrax sql <migration>");
    }
    out.print(Files.readString(migrationFile(project, args.positionals.get(1))));
    return OK;
  }

  private static Path migrationFile(Project project, String name) throws Exception {
    Path migrations = project.migrations();
    for (Path candidate : List.of(migrations.resolve(name), migrations.resolve(name + ".sql"),
        project.root().resolve(name), Path.of(name))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new UsageException("Migration file not found: " + name,
        "Run 'migrax status' to list migrations in " + project.display(migrations) + ".");
  }

  // ================================================================== quality

  private int lint(Project project) throws Exception {
    String dialect = project.dialect(true, args.flag("--json") ? err : out).id();
    Path folder = project.migrations();
    List<Path> files = new ArrayList<>();
    if (args.positionals.size() > 1) {
      for (String name : args.positionals.subList(1, args.positionals.size())) {
        files.add(migrationFile(project, name));
      }
    } else {
      List<Path> all = MigrationLoader.sqlFiles(folder);
      if (args.flag("--all") || !project.hasUrl()) {
        files.addAll(all);
      } else {
        try (Connection connection = project.connect()) {
          Set<String> pending = new HashSet<>();
          new MigrationRunner().status(connection, all).stream()
              .filter(r -> r.state() == MigrationRunner.State.PENDING
                  || r.state() == MigrationRunner.State.OUTDATED)
              .forEach(r -> pending.add(r.version()));
          all.stream().filter(f -> pending.contains(f.getFileName().toString()))
              .forEach(files::add);
        } catch (Exception e) {
          Log.warn("Could not read pending migrations ({}); linting all files.", describe(e));
          files.addAll(all);
        }
      }
    }
    List<SqlLinter.Finding> findings = new ArrayList<>();
    for (Path file : files) {
      findings.addAll(SqlLinter.lint(project.display(file), Files.readString(file), dialect));
    }
    boolean errors = findings.stream().anyMatch(f -> f.severity() == SqlLinter.Severity.ERROR);
    boolean warnings = findings.stream().anyMatch(f -> f.severity() == SqlLinter.Severity.WARNING);
    if (args.flag("--json")) {
      out.println(JsonOut.write(JsonOut.object("files", files.size(),
          "findings", findingsJson(findings))));
    } else {
      printFindings(findings);
      out.println(files.size() + " file(s) checked, " + findings.size() + " finding(s).");
    }
    return errors || warnings && args.flag("--strict") ? ERROR : OK;
  }

  private static List<Object> findingsJson(List<SqlLinter.Finding> findings) {
    return findings.stream().map(f -> (Object) JsonOut.object("code", f.code(),
        "severity", f.severity(), "file", f.file(), "statement", f.statement(),
        "message", f.message(), "fix", f.fix(), "sql", f.sql())).toList();
  }

  private void printFindings(List<SqlLinter.Finding> findings) {
    for (SqlLinter.Finding finding : findings) {
      out.println(finding.file() + ", statement " + finding.statement() + ": "
          + finding.severity().name().toLowerCase(Locale.ROOT) + " " + finding.code());
      out.println("  " + finding.sql());
      out.println("  " + finding.message());
      out.println("  Fix: " + finding.fix());
    }
  }

  private int verify(Project project) throws Exception {
    Dialect dialect = project.dialect(false, out);
    URLClassLoader loader = project.loader(true);
    List<Migration> migrations = project.migrationsToRun(true);
    String packageName = project.packageName();
    boolean json = args.flag("--json");
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("dialect", dialect.id());
    boolean ok;

    String url = args.option("--url");
    try (ScratchDatabase database = !Project.blank(url)
        // Without --user/--password, use the configured credentials (env vars, application
        // config): a scratch database usually lives on the same server.
        ? ScratchDatabase.existing(url,
            Project.blank(args.option("--user")) ? project.credentials().user()
                : args.option("--user"),
            Project.blank(args.option("--password")) ? project.credentials().password()
                : args.option("--password"))
        : ScratchDatabase.start(dialect.id(), args.option("--image"), loader)) {
      try (Connection connection = RuntimeJdbc.connect(loader, database.url(), database.user(),
          database.password())) {
        if (!Project.blank(url) && !DatabaseSchemaReader.read(connection).tables().isEmpty()
            && !args.flag("--yes", "-y")) {
          throw new UsageException("The database at --url is not empty.",
              "verify needs an empty scratch database; pass --yes if you are sure it may be "
                  + "changed.");
        }
        MigrationRunner runner = new MigrationRunner();
        int applied = runner.migrateAll(connection, migrations, project.runOptions());
        report.put("applied", applied);
        if (!json) {
          out.println("Applied " + applied + " migration(s) to a fresh " + dialect.id()
              + " database.");
        }

        String hibernate = ModelExtractor.hibernateVersion(loader);
        List<String> problems = new ArrayList<>();
        if (ModelExtractor.readsMapping(hibernate)
            && project.extractorMode() != ModelExtractor.Mode.ANNOTATIONS
            && ModelExtractor.matchingEntities(loader, packageName, hibernate)) {
          String error = ModelExtractor.validateWithHibernate(loader, packageName,
              project.naming(), dialect, project.hibernateSettings(), database.url(),
              database.user(), database.password());
          if (error != null) {
            problems.add(error);
          }
          report.put("validation", "Hibernate " + hibernate + " schema validation");
        } else {
          SchemaModel expected = project.extract(dialect).model();
          SchemaModel actual = DatabaseSchemaReader.read(connection);
          SchemaComparator.compare(expected, actual, dialect.id())
              .forEach(d -> problems.add(d.toString()));
          report.put("validation", "structural comparison");
        }
        report.put("problems", problems);
        if (!json) {
          if (problems.isEmpty()) {
            out.println("Schema matches the entities (" + report.get("validation") + ").");
          } else {
            out.println("The migrated schema does not match the entities:");
            problems.forEach(p -> out.println("  - " + p));
          }
        }
        ok = problems.isEmpty();

        if (!args.flag("--skip-rollbacks")) {
          String rollbacks = verifyRollbacks(connection, runner, project, migrations);
          report.put("rollbacks", rollbacks);
          if (!json) {
            out.println("Rollback scripts: " + rollbacks + ".");
          }
          ok &= !rollbacks.startsWith("failed");
        }
      }
    }
    report.put("ok", ok);
    if (json) {
      out.println(JsonOut.write(report));
    } else {
      out.println(ok ? "Verified." : "Verification failed.");
    }
    return ok ? OK : ERROR;
  }

  /**
   * Rolls back the newest migrations that have rollback scripts (all of them when every one
   * has a script) and applies them again; returns a short result.
   */
  private String verifyRollbacks(Connection connection, MigrationRunner runner, Project project,
                                 List<Migration> migrations) throws Exception {
    Path folder = project.migrations();
    Map<String, Migration> java = new HashMap<>();
    migrations.stream().filter(Migration::isJava).forEach(m -> java.put(m.version(), m));
    List<MigrationRunner.AppliedMigration> applied = runner.applied(connection).stream()
        .filter(m -> !m.version().startsWith("R__")).toList();
    List<String> versions = new ArrayList<>();
    List<Migration> scripts = new ArrayList<>();
    String firstWithout = null;
    for (int i = applied.size() - 1; i >= 0; i--) {
      String version = applied.get(i).version();
      Path script = MigrationLoader.rollbackScript(folder, version);
      if (java.containsKey(version)) {
        scripts.add(java.get(version));
      } else if (script != null) {
        scripts.add(Migration.ofSql(version, Files.readString(script)));
      } else {
        firstWithout = version;
        break;
      }
      versions.add(version);
    }
    if (scripts.isEmpty()) {
      return firstWithout == null ? "skipped (no migrations)"
          : "skipped (" + firstWithout + " has no rollback script)";
    }
    try {
      SchemaModel before = DatabaseSchemaReader.read(connection);
      for (int i = 0; i < scripts.size(); i++) {
        runner.rollback(connection, versions.get(i), scripts.get(i), project.runOptions());
      }
      if (firstWithout == null) {
        SchemaModel left = DatabaseSchemaReader.read(connection);
        if (!left.tables().isEmpty()) {
          return "failed: tables left after rolling everything back: "
              + left.tables().stream().map(SchemaModel.Table::name).toList();
        }
      }
      runner.migrateAll(connection, migrations, project.runOptions());
      List<SchemaComparator.Difference> changed = SchemaComparator.compare(before,
          DatabaseSchemaReader.read(connection), project.dialect(false, err).id());
      if (!changed.isEmpty()) {
        return "failed: the schema differs after rolling back and re-applying: "
            + changed.stream().map(SchemaComparator.Difference::toString).toList();
      }
      return "ok (" + scripts.size() + " rolled back and re-applied"
          + (firstWithout == null ? "" : "; older ones from " + firstWithout
              + " down have no rollback script") + ")";
    } catch (Exception e) {
      return "failed: " + describe(e);
    }
  }

  private int drift(Project project) throws Exception {
    project.requireUrl();
    Dialect dialect = project.dialect(false, out);
    SchemaModel expected;
    String against;
    if (args.flag("--entities")) {
      expected = project.extract(dialect).model();
      against = "entities";
    } else {
      if (!Files.exists(project.snapshot())) {
        throw new UsageException("There is no snapshot to compare with.",
            "Run 'migrax generate' first, or pass --entities.");
      }
      expected = SnapshotStore.load(project.snapshot());
      against = "snapshot";
    }
    List<SchemaComparator.Difference> differences;
    long pending;
    try (Connection connection = project.connect()) {
      differences = SchemaComparator.compare(expected,
          DatabaseSchemaReader.read(connection, args.option("--schema")), dialect.id());
      pending = new MigrationRunner().statusOf(connection, project.migrationsToRun(true)).stream()
          .filter(r -> r.state() == MigrationRunner.State.PENDING).count();
    }
    if (args.flag("--json")) {
      out.println(JsonOut.write(JsonOut.object("against", against, "pending", pending,
          "differences", differences.stream().map(d -> (Object) JsonOut.object(
              "kind", d.kind(), "object", d.object(), "detail", d.detail())).toList())));
    } else {
      if (pending > 0) {
        out.println(pending + " migration(s) are pending; differences they cover are expected.");
      }
      if (differences.isEmpty()) {
        out.println("No drift: the database matches the " + against + ".");
      } else {
        out.println(differences.size() + " difference(s) between the database and the "
            + against + ":");
        differences.forEach(d -> out.println("  - " + d));
        out.println("Someone changed the database outside migrations. Write a migration for "
            + "intended changes ('migrax new <name>'), or revert them.");
      }
    }
    return differences.isEmpty() ? OK : CHANGES_DETECTED;
  }

  // ================================================================== adoption & maintenance

  private int importDatabase(Project project) throws Exception {
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing what to import from.",
          "Usage: migrax import flyway  or  migrax import liquibase");
    }
    String source = args.positionals.get(1).toLowerCase(Locale.ROOT);
    project.requireUrl();
    Path folder = project.migrations();
    Importer.Report report;
    try (Connection connection = project.connect()) {
      switch (source) {
        case "flyway" -> report = Importer.flyway(connection, folder, args.option("--table"));
        case "liquibase" -> {
          Dialect dialect = project.dialect(false, out);
          String name = args.option("--name");
          if (Project.blank(name)) {
            name = String.format(Locale.ROOT, "%04d_liquibase_baseline", nextNumber(folder));
          }
          report = Importer.liquibase(connection, dialect, folder, name, args.option("--schema"));
        }
        default -> throw new UsageException("Unknown source '" + source + "'.",
            "Use 'migrax import flyway' or 'migrax import liquibase'.");
      }
    }
    report.notes().forEach(out::println);
    out.println("Recorded " + report.recorded() + " migration(s) as applied.");
    out.println("Next: disable " + (source.equals("flyway") ? "Flyway (spring.flyway.enabled=false)"
        : "Liquibase (spring.liquibase.enabled=false)") + ", then run 'migrax generate'; its "
        + "first run uses the current database as the baseline.");
    return OK;
  }

  /** The next migration number after the highest one in the folder. */
  private static int nextNumber(Path folder) throws Exception {
    int highest = 0;
    for (Path file : MigrationLoader.sqlFiles(folder)) {
      List<java.math.BigInteger> version = MigrationRunner.versionParts(file.getFileName().toString());
      if (!version.isEmpty()) {
        highest = Math.max(highest, version.get(0).intValue());
      }
    }
    return highest + 1;
  }

  private int squash(Project project) throws Exception {
    String to = args.option("--to");
    if (Project.blank(to)) {
      throw new UsageException("Missing --to.", "Usage: migrax squash --to <migration>");
    }
    Path folder = project.migrations();
    List<Path> files = MigrationLoader.sqlFiles(folder).stream()
        .filter(p -> {
          String name = p.getFileName().toString();
          return !name.startsWith("R__") && !Migration.CALLBACKS.contains(name);
        }).toList();
    String target = resolveFile(to, files.stream().map(p -> p.getFileName().toString()).toList());
    List<Path> range = files.stream().filter(p -> MigrationRunner.compareMigrationFilenames(
        p.getFileName().toString(), target) <= 0).toList();
    if (range.size() < 2) {
      throw new UsageException("Nothing to squash: " + target + " is the first migration.",
          "Pick a later migration with --to.");
    }
    List<String> replaces = new ArrayList<>();
    StringBuilder body = new StringBuilder();
    for (Path file : range) {
      String name = file.getFileName().toString();
      String sql = Files.readString(file);
      if (SqlScript.hasDirective(sql, "no-transaction")) {
        throw new UsageException(name + " must run outside a transaction, so it cannot be "
            + "squashed.", "Squash up to the migration before it.");
      }
      replaces.addAll(SqlScript.directiveValues(sql, "replaces"));
      replaces.add(name);
      body.append("\n-- ---- ").append(name).append(" ----\n");
      for (String line : sql.split("\n", -1)) {
        if (!line.trim().toLowerCase(Locale.ROOT).startsWith("-- migrax:replaces")) {
          body.append(line).append("\n");
        }
      }
    }
    if (args.flag("--optimize")) {
      Path history = SnapshotStore.historySnapshot(project.root(), target);
      if (!Files.exists(history)) {
        throw new UsageException("--optimize needs " + project.display(history) + ".",
            "It is written by 'migrax generate'. Squash without --optimize instead.");
      }
      Dialect dialect = project.dialect(false, out);
      body.setLength(0);
      body.append("\n-- Resulting schema only; data statements of the replaced migrations are "
          + "not included.\n");
      for (Operation operation : new io.migrax.diff.DiffEngine().diff(SchemaModel.empty(),
          SnapshotStore.load(history))) {
        body.append(dialect.render(operation)).append(";\n");
      }
    }
    Pattern leading = Pattern.compile("^[vV]?(\\d+)");
    Matcher firstNumber = leading.matcher(range.get(0).getFileName().toString());
    String first = firstNumber.find() ? firstNumber.group(1) : "0";
    Matcher number = leading.matcher(target);
    String prefix = number.find() ? number.group(1) : "0";
    String name = Project.blank(args.option("--name"))
        ? prefix + "_squashed_" + first + "_" + prefix
        : args.option("--name").replaceFirst("\\.sql$", "");
    Path file = folder.resolve(name + ".sql");
    if (Files.exists(file)) {
      throw new UsageException("Migration already exists: " + project.display(file), null);
    }
    List<String> unique = new ArrayList<>(new LinkedHashSet<>(replaces));
    Files.writeString(file, "-- Squashed by Migrax " + versionString() + " from "
        + unique.size() + " migrations.\n-- migrax:replaces " + String.join(",", unique)
        + "\n" + body);

    List<String> rollbackParts = new ArrayList<>();
    for (int i = range.size() - 1; i >= 0; i--) {
      Path script = MigrationLoader.rollbackScript(folder, range.get(i).getFileName().toString());
      if (script == null) {
        rollbackParts = null;
        break;
      }
      rollbackParts.add("-- ---- rollback of " + range.get(i).getFileName() + " ----\n"
          + Files.readString(script));
    }
    if (rollbackParts != null) {
      Path rollback = folder.resolve(MigrationLoader.ROLLBACK_FOLDER).resolve(file.getFileName());
      Files.writeString(rollback, String.join("\n", rollbackParts));
    }
    out.println("Created " + project.display(file) + " replacing " + range.size()
        + " migration(s), " + range.get(0).getFileName() + " to " + target + ".");
    out.println("Databases that applied them record it without running it; new databases run "
        + "only this file.");
    out.println("Delete the old files once every environment has run 'migrax migrate'.");
    return OK;
  }

  private static String resolveFile(String wanted, List<String> names) {
    for (String name : names) {
      if (name.equals(wanted) || name.equals(wanted + ".sql")) {
        return name;
      }
    }
    for (String name : names) {
      if (name.startsWith(wanted)) {
        return name;
      }
    }
    throw new UsageException("Migration '" + wanted + "' not found.",
        "Run 'migrax status' to list migrations.");
  }

  /** Migration numbers used by more than one versioned file, excluding squashed ranges. */
  static Map<String, List<String>> duplicateNumbers(Path folder) throws Exception {
    List<Path> files = MigrationLoader.sqlFiles(folder);
    Set<String> replaced = new HashSet<>();
    for (Path file : files) {
      replaced.addAll(SqlScript.directiveValues(Files.readString(file), "replaces"));
    }
    Map<String, List<String>> groups = new TreeMap<>();
    for (Path file : files) {
      String name = file.getFileName().toString();
      if (name.startsWith("R__") || Migration.CALLBACKS.contains(name) || replaced.contains(name)) {
        continue;
      }
      List<java.math.BigInteger> version = MigrationRunner.versionParts(name);
      if (!version.isEmpty()) {
        groups.computeIfAbsent(version.toString(), k -> new ArrayList<>()).add(name);
      }
    }
    groups.values().removeIf(list -> list.size() < 2);
    return groups;
  }

  /**
   * Duplicate numbers that still need 'migrax merge': groups whose files are all applied in the
   * configured database are settled history (they run in filename order) and are left out.
   * Without a reachable database every group is reported.
   */
  private Map<String, List<String>> unresolvedDuplicates(Project project) throws Exception {
    Map<String, List<String>> duplicates = duplicateNumbers(project.migrations());
    if (duplicates.isEmpty() || !project.hasUrl()) {
      return duplicates;
    }
    Set<String> applied = appliedVersions(project);
    duplicates.values().removeIf(applied::containsAll);
    return duplicates;
  }

  /**
   * Explains a Hibernate auto-DDL setting (ddl-auto=update and the like): where it is, what it
   * does to a Migrax-managed database, and the exact line to write instead.
   */
  private static void adviseSchemaGeneration(Doctor doctor, Project project,
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

  /** The naming id to record in the snapshot, or null for a custom combination. */
  private static String recordedNaming(Project project) throws Exception {
    String id = project.naming().id();
    try {
      NamingStrategy.parse(id);
      return id;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Set<String> appliedVersions(Project project) {
    Set<String> applied = new HashSet<>();
    try (Connection connection = project.connect()) {
      new MigrationRunner().applied(connection).forEach(m -> applied.add(m.version()));
    } catch (Exception e) {
      Log.debug("Could not read the migration history: {}", describe(e));
    }
    return applied;
  }

  private void requireNoDuplicates(Project project) throws Exception {
    Map<String, List<String>> duplicates = unresolvedDuplicates(project);
    if (!duplicates.isEmpty()) {
      throw new UsageException("Migrations share numbers: " + duplicates.values() + ".",
          "This usually follows a git merge of two branches. Run 'migrax merge'.");
    }
  }

  private int merge(Project project) throws Exception {
    Path folder = project.migrations();
    Map<String, List<String>> duplicates = duplicateNumbers(folder);
    Set<String> applied = new HashSet<>();
    if (!duplicates.isEmpty() && project.hasUrl()) {
      try (Connection connection = project.connect()) {
        new MigrationRunner().applied(connection).forEach(m -> applied.add(m.version()));
      } catch (Exception e) {
        Log.warn("Could not read the migration history ({}); renaming anyway.", describe(e));
      }
    }
    int settled = 0;
    for (Iterator<List<String>> groups = duplicates.values().iterator(); groups.hasNext(); ) {
      List<String> group = groups.next();
      if (applied.containsAll(group)) {
        out.println(String.join(" and ", group) + " are all applied already; they run in "
            + "filename order, so they are left as they are.");
        groups.remove();
        settled++;
      }
    }
    int highest = 0;
    int width = 4;
    Pattern leading = Pattern.compile("^(\\d+)_");
    for (Path file : MigrationLoader.sqlFiles(folder)) {
      Matcher matcher = leading.matcher(file.getFileName().toString());
      if (matcher.find()) {
        highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        width = Math.max(width, matcher.group(1).length());
      }
    }
    int renamed = 0;
    for (List<String> group : duplicates.values()) {
      // Applied files keep their names; of the rest, the one added first keeps the number.
      List<String> ordered = new ArrayList<>(group);
      ordered.sort(Comparator.comparing((String name) -> !applied.contains(name))
          .thenComparingLong(name -> addedAt(project, folder.resolve(name))));
      for (String name : ordered.subList(1, ordered.size())) {
        if (applied.contains(name)) {
          throw new UsageException(name + " is already applied in the configured database.",
              "Renaming it would break that history. Rename the other migration by hand.");
        }
        Matcher matcher = Pattern.compile("^(\\d+)(_.*)$").matcher(name);
        if (!matcher.matches()) {
          throw new UsageException("Cannot renumber " + name + " automatically.",
              "Rename it to the next free number by hand.");
        }
        String newName = String.format(Locale.ROOT, "%0" + width + "d", ++highest)
            + matcher.group(2);
        Files.move(folder.resolve(name), folder.resolve(newName));
        Path rollback = MigrationLoader.rollbackScript(folder, name);
        if (rollback != null) {
          Files.move(rollback, rollback.resolveSibling(newName));
        }
        Path history = SnapshotStore.historySnapshot(project.root(), name);
        if (Files.exists(history)) {
          Files.move(history, SnapshotStore.historySnapshot(project.root(), newName));
        }
        out.println("Renamed " + name + " to " + newName + ".");
        renamed++;
      }
    }
    boolean rebuilt = false;
    if (Files.exists(project.snapshot())) {
      try {
        SnapshotStore.load(project.snapshot());
      } catch (IllegalStateException e) {
        Dialect dialect = project.dialect(false, out);
        SnapshotStore.save(project.snapshot(), project.extract(dialect).model(), dialect.id(),
            recordedNaming(project));
        out.println("Rebuilt " + project.display(project.snapshot())
            + " from the merged entities (it had merge conflicts).");
        rebuilt = true;
      }
    }
    if (renamed == 0 && !rebuilt) {
      if (settled > 0) {
        return OK;
      }
      out.println("Nothing to merge: migration numbers are unique and the snapshot is valid.");
      return OK;
    }
    out.println("Next: run 'migrax check' (no changes expected) and 'migrax verify' to prove the "
        + "merged migrations produce the entity schema.");
    return OK;
  }

  /** When git first added the file, or its modification time. */
  private static long addedAt(Project project, Path file) {
    try {
      Process process = new ProcessBuilder("git", "log", "--diff-filter=A", "--format=%ct", "-1",
          "--", project.root().relativize(file.toAbsolutePath()).toString())
          .directory(project.root().toFile()).redirectErrorStream(true).start();
      process.getOutputStream().close();
      String output = new String(process.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8).trim();
      if (process.waitFor() == 0 && output.matches("\\d+")) {
        return Long.parseLong(output) * 1000;
      }
    } catch (java.io.IOException ignored) {
      // Not a git checkout: fall back to the file time.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (java.io.IOException e) {
      return Long.MAX_VALUE;
    }
  }

  private int newMigration(Project project) throws Exception {
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing the migration name.", "Usage: migrax new <name> [--java]");
    }
    String description = args.positionals.get(1).trim().toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    if (description.isEmpty()) {
      throw new UsageException("The migration name needs letters or digits.", null);
    }
    Path folder = project.migrations();
    Files.createDirectories(folder);
    int next = nextNumber(folder) - 1;
    String javaPackage = project.javaPackage();
    Path javaFolder = project.root().resolve("src/main/java").resolve(javaPackage.replace('.', '/'));
    if (Files.isDirectory(javaFolder)) {
      Pattern javaVersion = Pattern.compile("^V(\\d+)__");
      try (var stream = Files.list(javaFolder)) {
        for (Path file : stream.toList()) {
          Matcher matcher = javaVersion.matcher(file.getFileName().toString());
          if (matcher.find()) {
            next = Math.max(next, Integer.parseInt(matcher.group(1)));
          }
        }
      }
    }
    next++;
    String number = String.format(Locale.ROOT, "%04d", next);
    if (args.flag("--java")) {
      StringBuilder camel = new StringBuilder();
      for (String part : description.split("_")) {
        if (!part.isEmpty()) {
          camel.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
      }
      String className = "V" + number + "__" + camel;
      Files.createDirectories(javaFolder);
      Path file = javaFolder.resolve(className + ".java");
      Files.writeString(file, """
          package PACKAGE_NAME;

          import io.migrax.api.JavaMigration;
          import java.sql.Connection;

          /** Migration MIGRATION_NUMBER, written by hand. */
          public class CLASS_NAME implements JavaMigration {
            @Override
            public void migrate(Connection connection) throws Exception {
              try (var statement = connection.createStatement()) {
                // statement.executeUpdate("UPDATE ...");
              }
            }

            @Override
            public void rollback(Connection connection) throws Exception {
              // Undo migrate(), or delete this method if it cannot be undone.
            }
          }
          """.replace("PACKAGE_NAME", javaPackage).replace("MIGRATION_NUMBER", number)
          .replace("CLASS_NAME", className));
      out.println("Created " + project.display(file) + ".");
      out.println("Add io.migrax:migrax as a provided dependency so the class compiles; it runs "
          + "in version order with the SQL migrations.");
      return OK;
    }
    String name = number + "_" + description + ".sql";
    Path file = folder.resolve(name);
    Files.writeString(file, "-- " + description.replace('_', ' ') + "\n"
        + "-- Written by hand: Migrax does not add these changes to the entity snapshot.\n\n");
    Path rollback = folder.resolve(MigrationLoader.ROLLBACK_FOLDER).resolve(name);
    Files.createDirectories(rollback.getParent());
    Files.writeString(rollback, "-- Rollback for " + name + ". 'migrax rollback' runs it.\n\n");
    out.println("Created " + project.display(file) + " and " + project.display(rollback) + ".");
    return OK;
  }

  // ================================================================== helpers

  private void row(String label, String value) {
    out.printf("  %-15s %s%n", label, value);
  }

  private static String dialectOrMessage(String url) {
    try {
      return Dialects.fromJdbcUrl(url).id();
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }

  private static boolean loadable(ClassLoader loader, String name) {
    try {
      Class.forName(name, false, loader);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  static String versionString() {
    String version = Main.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }

  /** One-line error text: the message plus distinct cause messages. */
  static String describe(Throwable error) {
    List<String> parts = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (Throwable current = error; current != null && parts.size() < 3;
         current = current.getCause()) {
      String message = current.getMessage();
      if (message == null || message.isBlank()) {
        message = current.getClass().getSimpleName();
      }
      if (current instanceof LinkageError) {
        message = current.getClass().getSimpleName() + ": " + message;
      }
      message = message.strip();
      boolean repeated = false;
      for (String previous : seen) {
        if (previous.contains(message) || message.contains(previous) && previous.length() > 20) {
          repeated = true;
          break;
        }
      }
      if (!repeated) {
        seen.add(message);
        parts.add(message);
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return String.join(System.lineSeparator() + "  caused by: ", parts);
  }

  private static String hint(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause()) {
      if (current instanceof NoClassDefFoundError || current instanceof ClassNotFoundException) {
        return "A class from your project or its dependencies could not be loaded. "
            + "Run 'migrax doctor', or try again with --refresh.";
      }
      if (current instanceof java.sql.SQLException sql
          && sql.getMessage() != null && sql.getMessage().contains("No JDBC driver")) {
        return "Add the database driver as a runtime dependency of the project, "
            + "or run 'migrax doctor'.";
      }
      if (current.getCause() == current) {
        break;
      }
    }
    return null;
  }

  private Command resolveCommand(String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    for (Command command : COMMANDS.values()) {
      if (command.name.equals(normalized) || command.aliases.contains(normalized)) {
        return command;
      }
    }
    List<String> names = new ArrayList<>();
    for (Command command : COMMANDS.values()) {
      names.add(command.name);
      names.addAll(command.aliases);
    }
    String suggestion = closest(normalized, names);
    throw new UsageException("Unknown command '" + name + "'.",
        (suggestion == null ? "" : "Did you mean 'migrax " + suggestion + "'? ")
            + "Run 'migrax help' to see the available commands.");
  }

  private void printHelp() {
    out.println("Migrax " + versionString()
        + " - automatic database migrations for JPA/Hibernate projects");
    out.println();
    out.println("Usage: migrax <command> [options]");
    out.println();
    out.println("Getting started:");
    printCommands(List.of("init", "doctor", "import"));
    out.println();
    out.println("Everyday:");
    printCommands(List.of("generate", "migrate", "status", "plan", "rollback", "new"));
    out.println();
    out.println("Safety and CI:");
    printCommands(List.of("check", "lint", "verify", "drift"));
    out.println();
    out.println("Maintenance:");
    printCommands(List.of("squash", "merge", "repair", "inspect", "sql", "version", "help"));
    out.println();
    out.println("Common options:");
    out.println("  --dir <path>          Project folder (default: current folder)");
    out.println("  --no-build            Do not run Maven/Gradle; use already-compiled classes");
    out.println("  --json                Machine-readable output (plan, status, check, lint, ...)");
    out.println("  --verbose, -v         Show debug output and stack traces");
    out.println();
    out.println("Run Migrax from your service folder. It compiles the project and finds its");
    out.println("dependencies and database settings (application.properties/yml) by itself.");
    out.println("Run 'migrax help <command>' for details.");
  }

  private void printCommands(List<String> names) {
    for (String name : names) {
      Command command = COMMANDS.get(name);
      String aliases = command.aliases.isEmpty() ? ""
          : " (alias: " + String.join(", ", command.aliases) + ")";
      out.printf("  %-12s %s%s%n", name, command.summary, aliases);
    }
  }

  private void printCommandHelp(Command command) {
    out.println("Usage: " + command.usage);
    if (!command.aliases.isEmpty()) {
      out.println("Alias: " + String.join(", ", command.aliases));
    }
    out.println();
    out.println(command.description.strip());
    List<String> options = command.options.isBlank() ? List.of()
        : List.of(command.options.trim().split("\\s+"));
    if (!options.isEmpty()) {
      out.println();
      out.println("Options:");
      for (String option : options) {
        String text = OPTION_HELP.get(option);
        if (text != null) {
          out.println("  " + text);
        }
      }
      out.println("  " + OPTION_HELP.get("--dir"));
    }
  }

  private static void command(String name, List<String> aliases, String summary, String usage,
                              String description, String options) {
    COMMANDS.put(name, new Command(name, aliases, summary, usage, description, options));
  }

  static String closest(String input, List<String> candidates) {
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : candidates) {
      int distance = distance(input, candidate);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    return best != null && (bestDistance <= 2 || best.startsWith(input) && input.length() >= 3)
        ? best : null;
  }

  private static int distance(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
            previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }

  // ================================================================== types

  private record Command(String name, List<String> aliases, String summary, String usage,
                         String description, String options) {}

  /** An expected user error: printed as one line plus a hint, without a stack trace. */
  static final class UsageException extends RuntimeException {
    final String hint;

    UsageException(String message, String hint) {
      super(message);
      this.hint = hint;
    }
  }

  private final class Doctor {
    int failures;
    int warnings;

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

  /** Parsed command line: command, positionals, options and flags. */
  static final class Args {
    final String[] raw;
    final String command;
    final List<String> positionals;
    private final Map<String, String> options;
    private final Set<String> flags;

    private Args(String[] raw, List<String> positionals, Map<String, String> options,
                 Set<String> flags) {
      this.raw = raw;
      this.positionals = positionals;
      this.command = positionals.isEmpty() ? null : positionals.get(0);
      this.options = options;
      this.flags = flags;
    }

    static Args parse(String[] raw) {
      List<String> positionals = new ArrayList<>();
      Map<String, String> options = new LinkedHashMap<>();
      Set<String> flags = new LinkedHashSet<>();
      for (int i = 0; i < raw.length; i++) {
        String token = raw[i];
        if (!token.startsWith("-") || token.equals("-")) {
          positionals.add(token);
          continue;
        }
        String key = token;
        String value = null;
        int equals = token.indexOf('=');
        if (token.startsWith("--") && equals > 0) {
          key = token.substring(0, equals);
          value = token.substring(equals + 1);
        }
        if (VALUE_OPTIONS.contains(key)) {
          if (value == null) {
            if (i + 1 >= raw.length || raw[i + 1].startsWith("--")) {
              throw new UsageException("Option " + key + " needs a value.",
                  "Example: " + OPTION_HELP.getOrDefault(key, key + " <value>").split("\\s{2,}")[0]);
            }
            value = raw[++i];
          }
          String optionKey = key;
          // Repeated rename options accumulate.
          options.merge(key, value,
              (a, b) -> optionKey.startsWith("--rename") ? a + "," + b : b);
        } else if (FLAG_OPTIONS.contains(key) && value == null) {
          flags.add(key);
        } else {
          List<String> known = new ArrayList<>(VALUE_OPTIONS);
          known.addAll(FLAG_OPTIONS);
          String suggestion = closest(key, known);
          throw new UsageException("Unknown option '" + token + "'.",
              suggestion == null ? "Run 'migrax help' to see the options."
                  : "Did you mean '" + suggestion + "'?");
        }
      }
      return new Args(raw, List.copyOf(positionals), options, flags);
    }

    String option(String key) {
      return options.get(key);
    }

    String option(String key, String defaultValue) {
      return options.getOrDefault(key, defaultValue);
    }

    boolean flag(String... names) {
      for (String name : names) {
        if (flags.contains(name)) {
          return true;
        }
      }
      return false;
    }
  }
}
