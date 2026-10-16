package org.migrax.cli;

import static org.migrax.cli.ExitCode.ERROR;
import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.model.ModelExtractor;
import org.migrax.model.SchemaModel;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.plugin.RuntimeJdbc;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;
import org.migrax.verify.RollbackVerifier;
import org.migrax.verify.SchemaComparator;
import org.migrax.verify.ScratchDatabase;
import org.migrax.util.Log;

import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code migrax verify}: apply all migrations to a throwaway database and validate. */
final class VerifyCommand implements Command {

  @Override
  public String name() {
    return "verify";
  }

  @Override
  public Group group() {
    return Group.SAFETY;
  }

  @Override
  public String summary() {
    return "Apply all migrations to a throwaway database and validate";
  }

  @Override
  public String usage() {
    return "migrax verify [--url <scratch-db>] [--skip-rollbacks]";
  }

  @Override
  public String description() {
    return """
        Starts a throwaway database (H2, HSQLDB and Derby in memory, Docker for other
        engines), applies every migration, then checks the result: Hibernate's schema
        validation when Hibernate is in the project, otherwise a structural comparison with the
        entities. It also tests every rollback script on its own: it rolls the migration back,
        applies it again and checks that the schema is the same, so a broken script is named.""";
  }

  @Override
  public List<String> options() {
    return List.of("--url", "--user", "--password", "--password-file", "--image",
        "--skip-rollbacks", "--json", "--env", "--dialect", "--package", "--naming", "--extractor",
        "--locations", "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
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
        : ScratchDatabase.start(dialect.id(), Project.blank(args.option("--image"))
            ? dialect.scratchImage() : args.option("--image"), loader)) {
      try (Connection connection = RuntimeJdbc.connect(loader, database.url(), database.user(),
          database.password())) {
        if (!Project.blank(url) && !DatabaseSchemaReader.read(connection).tables().isEmpty()
            && !args.flag("--yes", "-y")) {
          throw new UsageException("The database at --url is not empty.",
              "verify needs an empty scratch database; pass --yes if you are sure it may be "
                  + "changed.");
        }
        MigrationRunner runner = context.migrationRunner();
        int applied;
        try {
          applied = runner.migrateAll(connection, migrations, project.runOptions());
        } catch (java.sql.SQLException e) {
          throw failsOnEmptyDatabase(e);
        }
        report.put("applied", applied);
        if (!json) {
          out.println("Applied " + applied + " migration(s) to a fresh " + dialect.id()
              + " database.");
        }

        String hibernate = ModelExtractor.hibernateVersion(loader);
        List<String> problems = new ArrayList<>();
        // Hibernate's validator rejects the integer row-id columns that its own SQLite dialect
        // creates for Long ids, so SQLite gets the structural comparison.
        boolean validated = false;
        if (ModelExtractor.readsMapping(hibernate) && !"sqlite".equals(dialect.id())
            && project.extractorMode() != ModelExtractor.Mode.ANNOTATIONS
            && ModelExtractor.matchingEntities(loader, packageName, hibernate)) {
          try {
            String error = ModelExtractor.validateWithHibernate(loader, packageName,
                project.naming(), dialect, project.hibernateSettings(), database.url(),
                database.user(), database.password());
            if (error != null) {
              problems.add(error);
            }
            report.put("validation", "Hibernate " + hibernate + " schema validation");
            validated = true;
          } catch (Exception | LinkageError e) {
            // A class Hibernate needs is missing (JAXB for Hibernate 5.0 to 5.2 on Java 11+):
            // the structural comparison still checks the schema.
            if (!ModelExtractor.missingClass(e)) {
              throw e;
            }
            Log.warn("Hibernate " + hibernate + " could not start: "
                + ModelExtractor.explain(e, hibernate) + ". Comparing the schema structurally "
                + "instead.");
          }
        }
        if (!validated) {
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
          String rollbacks = RollbackVerifier.check(connection, runner, migrations,
              project.runOptions(), dialect.id(), version -> {
                Path script = project.rollbackScript(version);
                return script == null ? null : Files.readString(script);
              });
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
   * A migration that fails on the throwaway database: the runner's advice (inspect the database,
   * repair, resume) is meant for a real one, so say what the failure means here instead.
   */
  private static RuntimeException failsOnEmptyDatabase(java.sql.SQLException e) {
    java.util.regex.Matcher name = java.util.regex.Pattern.compile("^Migration '([^']+)' failed")
        .matcher(String.valueOf(e.getMessage()));
    if (!name.find()) {
      return new IllegalStateException(e.getMessage(), e);
    }
    Throwable cause = e.getCause() != null ? e.getCause() : e;
    return new UsageException(ErrorCode.MIGRATION_FAILED,
        "Migration '" + name.group(1) + "' fails on an empty database: "
            + Errors.describe(cause),
        "verify applies every migration, in order, to a new empty database. Fix the migration "
            + "so it works there: it may use something that no earlier migration creates.");
  }
}
