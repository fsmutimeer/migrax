package org.migrax.cli;

import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.diff.Migrations;
import org.migrax.diff.SnapshotStore;
import org.migrax.lint.SqlLinter;
import org.migrax.model.SchemaModel;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.plugin.RuntimeJdbc;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;
import org.migrax.verify.SchemaComparator;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code migrax report}: one HTML page with the schema, the migrations, lint findings and
 * drift.
 */
final class ReportCommand implements Command {

  @Override
  public String name() {
    return "report";
  }

  @Override
  public Group group() {
    return Group.SAFETY;
  }

  @Override
  public String summary() {
    return "Write an HTML report: schema, migrations, lint findings, drift";
  }

  @Override
  public String usage() {
    return "migrax report [--html <file>]";
  }

  @Override
  public String description() {
    return """
        Writes one HTML page (default migrax-report.html) for reviews and CI artifacts: the
        schema with a diagram, entity changes that have no migration yet, every migration with
        its status and lint findings, and drift between the database and the snapshot. Parts
        that need the database are skipped when it can't be reached.""";
  }

  @Override
  public List<String> options() {
    return List.of("--html", "--env", "--schemas", "--dialect", "--package", "--naming",
        "--extractor", "--url", "--user", "--password", "--password-file", "--locations",
        "--classpath", "--no-build", "--database");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    PrintStream out = context.out();
    PrintStream err = context.err();
    Dialect dialect = project.dialect(true, err);
    Path target = Path.of(context.args().option("--html", "migrax-report.html"));
    if (!target.isAbsolute()) {
      target = Path.of(System.getProperty("user.dir")).resolve(target);
    }

    // The schema: the entities, else the snapshot (what the migrations build), else the database.
    SchemaModel schema = null;
    String schemaSource;
    List<String> unmigrated = null;
    String entitiesNote = null;
    try {
      EntityChanges changes = EntityChanges.read(context, dialect, project.hasUrl(), false);
      schema = changes.current();
      schemaSource = "entities (" + changes.source() + ")";
      unmigrated = changes.operations().stream().map(Migrations::summary).toList();
    } catch (Exception e) {
      entitiesNote = "The entities could not be read: " + Errors.describe(e);
      schemaSource = null;
    }
    if (schema == null && Files.exists(project.snapshot())) {
      schema = SnapshotStore.load(project.snapshot());
      schemaSource = "snapshot (" + project.display(project.snapshot()) + ")";
    }

    List<Migration> migrations = project.migrationsToRun(true);
    List<HtmlReport.MigrationRow> rows = new ArrayList<>();
    Set<String> pending = new HashSet<>();
    List<SchemaComparator.Difference> drift = null;
    String databaseNote = null;
    String database = null;
    if (!project.hasUrl()) {
      databaseNote = "No database URL is configured, so the status of the migrations and drift "
          + "were not checked.";
    } else {
      database = RuntimeJdbc.sanitizeUrl(project.credentials().url());
      try (Connection connection = project.connect()) {
        for (String name : project.schemasOrDefault()) {
          if (name != null) {
            Project.useSchema(connection, name);
          }
          for (MigrationRunner.MigrationStatus row
              : context.migrationRunner().statusOf(connection, migrations)) {
            rows.add(new HtmlReport.MigrationRow(name, row.version(), row.state().name(),
                row.detail()));
            if (row.state() == MigrationRunner.State.PENDING
                || row.state() == MigrationRunner.State.OUTDATED) {
              pending.add(row.version());
            }
          }
        }
        SchemaModel live = DatabaseSchemaReader.read(connection);
        if (Files.exists(project.snapshot())) {
          drift = SchemaComparator.compare(SnapshotStore.load(project.snapshot()), live,
              dialect.id());
        }
        if (schema == null) {
          schema = live;
          schemaSource = "database";
        }
      } catch (Exception e) {
        databaseNote = "The database could not be reached, so the status of the migrations and "
            + "drift were not checked: " + Errors.describe(e);
        rows.clear();
      }
    }
    if (rows.isEmpty()) {
      migrations.stream().sorted(MigrationRunner.ORDER).forEach(m ->
          rows.add(new HtmlReport.MigrationRow(null, m.version(), null, "")));
    }

    List<SqlLinter.Finding> findings = new ArrayList<>();
    for (Path file : project.migrationFiles()) {
      findings.addAll(SqlLinter.lint(project.display(file), Files.readString(file), dialect.id(),
          !project.schemas().isEmpty()));
    }

    String generated = ZonedDateTime.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'"));
    String name = project.root().getFileName() == null ? project.root().toString()
        : project.root().getFileName().toString();
    HtmlReport.Data data = new HtmlReport.Data(name, Version.current(), generated,
        dialect, database, schemaSource, schema, entitiesNote, unmigrated, rows, pending,
        findings, project.displayMigrations(), drift, databaseNote);
    Files.createDirectories(target.toAbsolutePath().getParent());
    Files.writeString(target, HtmlReport.render(data));
    out.println("Wrote " + project.display(target) + ": " + HtmlReport.summary(data) + ".");
    return OK;
  }
}
