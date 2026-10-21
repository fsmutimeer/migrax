package org.migrax.cli;

import static org.migrax.cli.ExitCode.CHANGES_DETECTED;
import static org.migrax.cli.ExitCode.ERROR;
import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.diff.SnapshotStore;
import org.migrax.model.SchemaModel;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.plugin.RuntimeJdbc;
import org.migrax.runner.DatabaseMigrationLock;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;
import org.migrax.runner.MigrationRunner.MigrationStatus;
import org.migrax.runner.MigrationRunner.State;
import org.migrax.util.Log;
import org.migrax.verify.SchemaComparator;

import java.io.PrintStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** {@code migrax environments}: every environment's migrations and drift, side by side. */
final class EnvironmentsCommand implements Command {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * What one environment's database said, or why it said nothing: {@code reached} tells
   * whether Migrax could connect before the {@code error}.
   */
  private record Report(Environments.Environment environment, boolean reached, String error,
                        List<MigrationStatus> rows, MigrationRunner.AppliedMigration last,
                        List<SchemaComparator.Difference> drift, String driftNote) {
    static Report failed(Environments.Environment environment, boolean reached, String error) {
      return new Report(environment, reached, error, List.of(), null, null, null);
    }

    /** Versioned migrations in this state; repeatable ones are counted nowhere here. */
    List<String> versions(State state) {
      return rows.stream().filter(row -> row.state() == state)
          .map(MigrationStatus::version).filter(version -> !version.startsWith("R__"))
          .toList();
    }

    long applied() {
      return versions(State.APPLIED).size();
    }

    List<MigrationStatus> problems() {
      return rows.stream().filter(row -> row.state() == State.FAILED
          || row.state() == State.CHANGED || row.state() == State.MISSING).toList();
    }

    String driftText() {
      return drift == null ? driftNote
          : drift.isEmpty() ? "none" : drift.size() + " difference(s)";
    }
  }

  @Override
  public String name() {
    return "environments";
  }

  @Override
  public Group group() {
    return Group.SAFETY;
  }

  @Override
  public String summary() {
    return "Show every environment's migrations and drift side by side";
  }

  @Override
  public String usage() {
    return "migrax environments [--environments <a,b>] [--no-drift] [--timeout <duration>] "
        + "[--json]";
  }

  @Override
  public String description() {
    return """
        Asks each environment's database (migrax.environments.<name>.url) which migrations
        it has applied, lists what is pending there and whether its schema drifted from the
        snapshot, without changing anything. Migrations marked '-- migrax:env' count only
        in the environments they name. Exits with code 1 when an environment can't be
        reached or a migration failed, changed or is missing there, and 2 when drift is
        found; pending migrations alone exit with 0.""";
  }

  @Override
  public List<String> options() {
    return List.of("--environments", "--no-drift", "--timeout", "--json", "--locations",
        "--java-package", "--classpath", "--no-build", "--database");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    List<Environments.Environment> environments =
        Environments.of(project, args.option("--environments"));
    List<Migration> migrations = project.migrationsToRun(true);
    SchemaModel snapshot = args.flag("--no-drift") || !Files.exists(project.snapshot()) ? null
        : SnapshotStore.load(project.snapshot());
    String noDrift = args.flag("--no-drift") ? "not checked" : "no snapshot";
    List<Report> reports = ask(context, project, environments, migrations, snapshot, noDrift,
        timeout(args), args.option("--timeout", "30s"));

    if (args.flag("--json")) {
      out.println(JsonOut.write(JsonOut.object("migrations", project.displayMigrations(),
          "environments", reports.stream().map(EnvironmentsCommand::json).toList())));
    } else {
      print(out, project, migrations, reports);
    }
    boolean failed = reports.stream().anyMatch(r -> r.error() != null);
    boolean problems = reports.stream().anyMatch(r -> !r.problems().isEmpty());
    boolean drifted = reports.stream().anyMatch(r -> r.drift() != null && !r.drift().isEmpty());
    if (problems && !args.flag("--json")) {
      context.err().println("Some migrations need attention. See 'migrax help repair'.");
    }
    return failed || problems ? ERROR : drifted ? CHANGES_DETECTED : OK;
  }

  private static Duration timeout(Args args) {
    String value = args.option("--timeout");
    if (Project.blank(value)) {
      return DEFAULT_TIMEOUT;
    }
    try {
      return DatabaseMigrationLock.parseTimeout(value);
    } catch (IllegalArgumentException e) {
      throw new UsageException("Invalid timeout '" + value + "'.",
          "Use a duration such as 30s, 2m or 500ms.");
    }
  }

  /**
   * Asks all environments at the same time, each on its own thread, so a database that doesn't
   * answer holds up only its own row.
   */
  private static List<Report> ask(CommandContext context, Project project,
                                  List<Environments.Environment> environments,
                                  List<Migration> migrations, SchemaModel snapshot,
                                  String noDrift, Duration timeout, String timeoutText)
      throws Exception {
    ClassLoader loader = project.loader(false);
    ExecutorService pool = Executors.newFixedThreadPool(environments.size(), task -> {
      Thread thread = new Thread(task, "migrax-environment");
      thread.setDaemon(true);
      return thread;
    });
    List<AtomicReference<Connection>> connections = new ArrayList<>();
    try {
      List<Future<Report>> futures = new ArrayList<>();
      for (Environments.Environment environment : environments) {
        MigrationRunner runner = context.migrationRunner().withEnvironment(environment.env());
        AtomicReference<Connection> connection = new AtomicReference<>();
        connections.add(connection);
        futures.add(pool.submit(() -> report(loader, runner, environment, migrations, snapshot,
            noDrift, connection)));
      }
      long deadline = System.nanoTime() + timeout.toNanos();
      List<Report> reports = new ArrayList<>();
      for (int i = 0; i < futures.size(); i++) {
        Environments.Environment environment = environments.get(i);
        try {
          reports.add(futures.get(i).get(Math.max(0, deadline - System.nanoTime()),
              TimeUnit.NANOSECONDS));
        } catch (TimeoutException e) {
          reports.add(Report.failed(environment, connections.get(i).get() != null,
              "no answer within " + timeoutText + " (--timeout)"));
        } catch (ExecutionException e) {
          reports.add(Report.failed(environment, false, firstLine(e.getCause())));
        }
      }
      return reports;
    } finally {
      // Drivers ignore interrupts: a connection still waiting is aborted, so neither a thread
      // nor a connection stays behind (migrax mcp runs commands in its own process).
      for (AtomicReference<Connection> connection : connections) {
        Connection open = connection.get();
        if (open != null) {
          try {
            open.abort(Runnable::run);
          } catch (Exception | LinkageError e) {
            Log.debug("Could not abort a connection: {}", e.getMessage());
          }
        }
      }
      pool.shutdownNow();
    }
  }

  /** The first line of an error: drivers add their own causes on further lines. */
  private static String firstLine(Throwable error) {
    return Errors.describe(error).lines().findFirst().orElse("");
  }

  private static Report report(ClassLoader loader, MigrationRunner runner,
                               Environments.Environment environment, List<Migration> migrations,
                               SchemaModel snapshot, String noDrift,
                               AtomicReference<Connection> open) throws Exception {
    try (Connection connection = RuntimeJdbc.connect(loader, environment.url(),
        environment.user(), environment.password())) {
      open.set(connection);
      try {
        return report(connection, runner, environment, migrations, snapshot, noDrift);
      } catch (Exception e) {
        return Report.failed(environment, true, firstLine(e));
      } finally {
        open.set(null);
      }
    }
  }

  private static Report report(Connection connection, MigrationRunner runner,
                               Environments.Environment environment, List<Migration> migrations,
                               SchemaModel snapshot, String noDrift) throws Exception {
    List<MigrationStatus> rows = runner.statusOf(connection, migrations);
    // The newest by time: migrations may have been applied out of order.
    MigrationRunner.AppliedMigration last = runner.applied(connection).stream()
        .filter(applied -> !applied.version().startsWith("R__"))
        .reduce((a, b) -> String.valueOf(b.appliedAt())
            .compareTo(String.valueOf(a.appliedAt())) >= 0 ? b : a)
        .orElse(null);
    if (snapshot == null) {
      return new Report(environment, true, null, rows, last, null, noDrift);
    }
    // Pending migrations make differences expected: they are what the migrations change.
    if (rows.stream().anyMatch(row -> row.state() == State.PENDING)) {
      return new Report(environment, true, null, rows, last, null, "not checked (pending)");
    }
    Dialect dialect = Dialects.forConnection(connection).orElse(null);
    if (dialect == null) {
      return new Report(environment, true, null, rows, last, null, "unknown database");
    }
    return new Report(environment, true, null, rows, last, SchemaComparator.compare(snapshot,
        DatabaseSchemaReader.read(connection, null), dialect.id()), null);
  }

  private static void print(PrintStream out, Project project, List<Migration> migrations,
                            List<Report> reports) throws Exception {
    long versioned = migrations.stream().filter(m -> m.kind() == Migration.Kind.VERSIONED)
        .count();
    out.println("Environments for " + project.displayMigrations() + " (" + versioned
        + " migration(s)):");
    out.println();
    List<String[]> table = new ArrayList<>();
    table.add(new String[] {"Environment", "Applied", "Pending", "Last applied", "Drift"});
    for (Report report : reports) {
      MigrationRunner.AppliedMigration last = report.last();
      table.add(report.error() != null ? new String[] {report.environment().name()}
          : new String[] {report.environment().name(), String.valueOf(report.applied()),
              String.valueOf(report.versions(State.PENDING).size()),
              last == null ? "none" : last.version() + ", " + minutes(last.appliedAt()),
              report.driftText()});
    }
    int[] widths = new int[5];
    table.forEach(row -> {
      for (int i = 0; i < row.length; i++) {
        widths[i] = Math.max(widths[i], row[i].length());
      }
    });
    for (int r = 0; r < table.size(); r++) {
      String[] row = table.get(r);
      StringBuilder line = new StringBuilder("  ");
      for (int i = 0; i < row.length; i++) {
        line.append(i == row.length - 1 ? row[i] : pad(row[i], widths[i]) + "  ");
      }
      if (row.length == 1) {
        // An environment that didn't answer: why, in place of its columns.
        line.append(" ".repeat(widths[0] - row[0].length() + 2))
            .append(reports.get(r - 1).reached() ? "error: " : "can't connect: ")
            .append(reports.get(r - 1).error());
      }
      out.println(line.toString().stripTrailing());
    }
    boolean details = false;
    for (Report report : reports) {
      String name = report.environment().name();
      List<String> pending = report.versions(State.PENDING);
      List<String> notes = new ArrayList<>();
      if (!pending.isEmpty()) {
        notes.add(name + ": pending " + String.join(", ", pending));
      }
      for (MigrationStatus problem : report.problems()) {
        notes.add(name + ": [!] " + problem.version() + " " + problem.state().name() + ": "
            + problem.detail());
      }
      if (report.drift() != null) {
        report.drift().forEach(d -> notes.add(name + ": drift: " + d));
      }
      if (!notes.isEmpty() && !details) {
        out.println();
        details = true;
      }
      notes.forEach(out::println);
    }
  }

  private static String pad(String text, int width) {
    return text + " ".repeat(Math.max(0, width - text.length()));
  }

  /** 2026-10-21 14:02 for 2026-10-21 14:02:11.123 (and the ISO form some drivers return). */
  private static String minutes(String appliedAt) {
    String text = String.valueOf(appliedAt).replace('T', ' ');
    return text.length() > 16 && text.charAt(13) == ':' ? text.substring(0, 16) : text;
  }

  private static Object json(Report report) {
    Environments.Environment environment = report.environment();
    MigrationRunner.AppliedMigration last = report.last();
    return JsonOut.object(
        "name", environment.name(),
        "url", DiffCommand.masked(environment.url()),
        "reachable", report.reached(),
        "error", report.error(),
        "applied", report.applied(),
        "pending", report.versions(State.PENDING),
        "skipped", report.versions(State.SKIPPED),
        "lastApplied", last == null ? null
            : JsonOut.object("version", last.version(), "appliedAt", last.appliedAt()),
        "problems", report.problems().stream().map(row -> (Object) JsonOut.object(
            "version", row.version(), "state", row.state(), "detail", row.detail())).toList(),
        "drift", report.drift() == null ? null : report.drift().stream()
            .map(d -> (Object) JsonOut.object("kind", d.kind(), "object", d.object(),
                "detail", d.detail())).toList(),
        "driftNote", report.driftNote());
  }
}
