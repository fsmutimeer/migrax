package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.PostgresDialect;
import io.migrax.diff.DiffEngine;
import io.migrax.diff.Migrations;
import io.migrax.diff.Renames;
import io.migrax.diff.SnapshotStore;
import io.migrax.lint.SqlLinter;
import io.migrax.model.SchemaModel;
import io.migrax.ops.AddForeignKey;
import io.migrax.ops.AddIndex;
import io.migrax.ops.AddUnique;
import io.migrax.ops.CreateTable;
import io.migrax.ops.DropForeignKey;
import io.migrax.ops.DropIndex;
import io.migrax.ops.DropUnique;
import io.migrax.ops.Operation;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import io.migrax.util.Log;

import java.io.PrintStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** {@code migrax generate}: create a migration from entity changes. */
final class GenerateCommand implements Command {
  /** The migration the first generate writes with the tables the database already has. */
  static final String BASELINE = "0001_baseline";

  @Override
  public String name() {
    return "generate";
  }

  @Override
  public List<String> aliases() {
    return List.of("makemigrations");
  }

  @Override
  public Group group() {
    return Group.EVERYDAY;
  }

  @Override
  public String summary() {
    return "Create a migration from entity changes";
  }

  @Override
  public String usage() {
    return "migrax generate [--name <name>] [--allow-destructive] [--safe]";
  }

  @Override
  public String description() {
    return """
        Compiles the project if needed, compares the entities with .migrax/snapshot.json and
        writes a numbered SQL file plus a rollback script (rollback/<file>). On the first run,
        with no snapshot, the current database schema is the baseline: its tables are written
        to 0001_baseline.sql, which that database records as applied without running it.
        When a column or table seems renamed, Migrax asks (or pass --rename / --rename-table)
        so the data is kept. Drops need --allow-destructive. --safe (PostgreSQL, CockroachDB)
        builds indexes and constraints on existing tables without blocking writes, in a second
        migration.
        Always review the generated SQL; Migrax lints it for you.""";
  }

  @Override
  public List<String> options() {
    return List.of("--name", "--allow-destructive", "--safe", "--rename", "--rename-table",
        "--no-input", "--lock-timeout", "--dialect", "--package", "--naming", "--extractor",
        "--url", "--user", "--password", "--password-file", "--schema", "--locations",
        "--classpath", "--no-build", "--refresh");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    Path migrations = project.migrations();
    DuplicateMigrations.requireNone(project, context.migrationRunner());
    boolean hasSnapshot = Files.exists(project.snapshot());
    if (!hasSnapshot && !project.hasUrl()) {
      throw new UsageException("No database URL found, and there is no snapshot yet.",
          "The first generate reads the current database as its baseline. Set "
              + project.urlSetting() + ", MIGRAX_DATABASE_URL, or --url.");
    }
    Dialect dialect = project.dialect(false, out);
    EntityChanges changes = EntityChanges.read(context, dialect, true, true);
    if (changes.current().tables().isEmpty()) {
      throw new UsageException(
          "No JPA entities found under package '" + project.packageName() + "'.",
          "Pass --package <name> or set MIGRAX_PACKAGE. Run 'migrax doctor' to diagnose.");
    }
    Log.debug("Entities read from {}.", changes.source());
    List<Operation> operations = changes.operations();
    // The first generate against a database that already has tables (and no migrations yet)
    // also writes those tables as a baseline migration, so the migrations alone can build an
    // empty database.
    boolean baseline = !hasSnapshot && !changes.previous().tables().isEmpty()
        && MigrationFiles.nextNumber(migrations) == 1;
    if (operations.isEmpty()) {
      out.println("No changes detected.");
      if (!hasSnapshot) {
        if (baseline) {
          Files.createDirectories(migrations);
          Path file = writeBaseline(project, dialect, changes.previous());
          recordBaseline(context, file);
          printBaseline(out, project, file, changes.previous());
        }
        SnapshotStore.save(project.snapshot(), changes.current(), dialect.id(),
            project.recordedNaming());
        out.println("Saved the entity schema as the baseline snapshot: "
            + project.display(project.snapshot()));
      }
      return OK;
    }
    List<Operation> destructive = operations.stream().filter(Operation::destructive).toList();
    if (!destructive.isEmpty() && !args.flag("--allow-destructive")) {
      Reports.printOperations(out, operations, dialect, null);
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
        Log.warn("--safe applies to PostgreSQL and CockroachDB only; generating a normal "
            + "migration.");
      } else {
        Set<String> newTables = new HashSet<>();
        operations.stream().filter(o -> o instanceof CreateTable)
            .forEach(o -> newTables.add(((CreateTable) o).table().name()));
        for (Operation operation : operations) {
          String table = Reports.table(operation);
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
    Path baselineFile = baseline ? writeBaseline(project, dialect, changes.previous()) : null;
    String name;
    Path file;
    try {
      name = migrationName(args, migrations, changes.previous(), main.isEmpty() ? online : main);
      file = migrations.resolve(name + ".sql");
      if (Files.exists(file)) {
        throw new UsageException("Migration already exists: " + project.display(file),
            "Choose another --name.");
      }
      if (baselineFile != null && MigrationRunner.versionParts(name + ".sql")
          .equals(MigrationRunner.versionParts(baselineFile.getFileName().toString()))) {
        throw new UsageException("Migration " + name + " has the same number as the baseline "
            + "migration " + BASELINE + ".", "Choose another --name.");
      }
      if (baselineFile != null) {
        recordBaseline(context, baselineFile);
      }
    } catch (Exception e) {
      if (baselineFile != null) {
        Files.deleteIfExists(baselineFile);
        Files.deleteIfExists(SnapshotStore.historySnapshot(project.root(), BASELINE));
      }
      throw e;
    }
    if (baselineFile != null) {
      printBaseline(out, project, baselineFile, changes.previous());
    }
    List<Path> written = new ArrayList<>();
    List<Operation> reverse = Renames.reverse(changes.previous(), changes.current(),
        changes.tableRenames(), changes.columnRenames());
    if (!main.isEmpty()) {
      SchemaModel afterMain = withoutOnline(changes.current(), online);
      Files.writeString(file,
          MigrationSql.render(dialect, main, null, changes.previous(), afterMain));
      written.add(file);
      List<Operation> mainReverse = new ArrayList<>(reverse);
      mainReverse.removeIf(o -> undoesAny(o, online));
      MigrationSql.writeRollback(migrations, file.getFileName().toString(), dialect, mainReverse,
          main, afterMain, changes.previous());
      SnapshotStore.save(SnapshotStore.historySnapshot(project.root(), name), afterMain,
          dialect.id());
    }
    Path onlineFile = null;
    if (!online.isEmpty()) {
      String onlineName = main.isEmpty() ? name
          : Migrations.nextName(migrations, false, online) + "_online";
      onlineFile = migrations.resolve(onlineName + ".sql");
      PostgresDialect postgres = (PostgresDialect) dialect;
      StringBuilder sql = new StringBuilder("-- Generated by Migrax " + Version.current()
          + " for " + dialect.id() + " (--safe)\n-- migrax:no-transaction\n"
          + "-- Builds indexes and constraints without blocking writes; each statement commits "
          + "on its own.\n\n");
      for (Operation operation : online) {
        sql.append(postgres.renderOnline(operation)).append(";\n");
      }
      Files.writeString(onlineFile, sql);
      written.add(onlineFile);
      List<Operation> onlineReverse = reverse.stream().filter(o -> undoesAny(o, online)).toList();
      MigrationSql.writeRollback(migrations, onlineFile.getFileName().toString(), dialect,
          onlineReverse, online, changes.current(), withoutOnline(changes.current(), online));
      SnapshotStore.save(SnapshotStore.historySnapshot(project.root(), onlineName),
          changes.current(), dialect.id());
    }
    SnapshotStore.save(project.snapshot(), changes.current(), dialect.id(),
            project.recordedNaming());

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
      Reports.printFindings(out,
          SqlLinter.lint(project.display(path), Files.readString(path), dialect.id()));
    }
    out.println("Review the SQL, then run 'migrax migrate'.");
    return OK;
  }

  /**
   * Writes {@link #BASELINE} with the schema the database already has, so an empty database can
   * be built from the migrations. It has no rollback script: undoing it would drop every table.
   */
  private static Path writeBaseline(Project project, Dialect dialect, SchemaModel database)
      throws Exception {
    Path file = project.migrations().resolve(BASELINE + ".sql");
    String header = "-- Baseline: the tables the database already had before its first migration."
        + "\n-- That database records it as applied without running it; empty databases are "
        + "created from it.\n";
    Files.writeString(file, MigrationSql.render(dialect,
        new DiffEngine().diff(SchemaModel.empty(), database), header, SchemaModel.empty(),
        database));
    SnapshotStore.save(SnapshotStore.historySnapshot(project.root(), BASELINE), database,
        dialect.id());
    return file;
  }

  /** Records the baseline as applied in the database it was read from, without running it. */
  private static void recordBaseline(CommandContext context, Path file) throws Exception {
    Project project = context.project();
    try (Connection connection = project.connect()) {
      String schema = context.args().option("--schema");
      if (!Project.blank(schema)) {
        Project.useSchema(connection, schema);
      }
      context.migrationRunner().markApplied(connection, List.of(Migration.load(file)));
    }
  }

  private static void printBaseline(PrintStream out, Project project, Path file,
                                    SchemaModel database) {
    out.println("Created " + project.display(file) + " with the " + database.tables().size()
        + " table(s) the database already has.");
    out.println("  It is recorded as applied in this database without running it; empty "
        + "databases are created from it.");
  }

  private static String migrationName(Args args, Path migrations, SchemaModel previous,
                                      List<Operation> operations) throws Exception {
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
      name = String.format(Locale.ROOT, "%04d_%s", MigrationFiles.nextNumber(migrations), name);
    }
    return name;
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
}
