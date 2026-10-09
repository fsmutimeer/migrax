package io.migrax.cli;

import static io.migrax.cli.ExitCode.ERROR;
import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.model.ModelExtractor;
import io.migrax.model.SchemaModel;
import io.migrax.plugin.DatabaseSchemaReader;
import io.migrax.plugin.RuntimeJdbc;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import io.migrax.verify.SchemaComparator;
import io.migrax.verify.ScratchDatabase;

import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
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
        Starts a throwaway database (H2 in memory, or Docker for other engines), applies every
        migration, then checks the result: Hibernate's schema validation when Hibernate is in
        the project, otherwise a structural comparison with the entities. It also rolls every
        migration back and forward again to prove the rollback scripts work.""";
  }

  @Override
  public List<String> options() {
    return List.of("--url", "--user", "--password", "--image", "--skip-rollbacks", "--json",
        "--dialect", "--package", "--naming", "--extractor", "--locations", "--classpath",
        "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    PrintStream err = context.err();
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
        MigrationRunner runner = context.migrationRunner();
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
          String rollbacks = verifyRollbacks(connection, runner, project, migrations, err);
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
  private static String verifyRollbacks(Connection connection, MigrationRunner runner,
                                        Project project, List<Migration> migrations,
                                        PrintStream err) throws Exception {
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
      return "failed: " + Errors.describe(e);
    }
  }
}
