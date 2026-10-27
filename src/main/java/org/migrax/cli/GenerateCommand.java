package org.migrax.cli;

import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.PostgresDialect;
import org.migrax.diff.DiffEngine;
import org.migrax.diff.Migrations;
import org.migrax.diff.Renames;
import org.migrax.diff.SnapshotStore;
import org.migrax.diff.ZeroDowntime;
import org.migrax.lint.SqlLinter;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddCheck;
import org.migrax.ops.AddForeignKey;
import org.migrax.ops.AddIndex;
import org.migrax.ops.AddUnique;
import org.migrax.ops.CreateTable;
import org.migrax.ops.DropCheck;
import org.migrax.ops.DropForeignKey;
import org.migrax.ops.DropIndex;
import org.migrax.ops.DropUnique;
import org.migrax.ops.Operation;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationLoader;
import org.migrax.runner.MigrationRunner;
import org.migrax.util.Log;

import java.io.PrintStream;
import java.math.BigInteger;
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
    return "migrax generate [--name <name>] [--allow-destructive [--keep-data]] [--safe]\n"
        + "       migrax generate --zero-downtime [--name <name>] [--allow-destructive]\n"
        + "       migrax generate --contract";
  }

  @Override
  public String description() {
    return """
        Compiles the project if needed, compares the entities with .migrax/snapshot.json and
        writes a numbered SQL file plus a rollback script (rollback/<file>). On the first run,
        with no snapshot, the current database schema is the baseline: its tables are written
        to 0001_baseline.sql, which that database records as applied without running it.
        When a column or table seems renamed, Migrax asks (or pass --rename / --rename-table)
        so the data is kept. Drops need --allow-destructive; with --keep-data, dropped tables
        and columns go to migrax_backup_* tables and the rollback script restores them
        ('migrax backups' lists and drops those tables). --safe (PostgreSQL, CockroachDB)
        builds indexes and constraints on existing tables without blocking writes, in a second
        migration.
        --zero-downtime splits the change so the old and new application versions can run at
        the same time: an expand migration now (new columns and tables, a renamed column kept
        equal to its old name by a trigger, a renamed table's old name as a view) and a
        contract migration for the next release (drops, NOT NULL, narrower types, new
        constraints). The contract migration waits in .migrax/pending/; generate --contract
        adds it to the migration folder.
        Always review the generated SQL; Migrax lints it for you.""";
  }

  @Override
  public List<String> options() {
    return List.of("--name", "--allow-destructive", "--keep-data", "--safe", "--zero-downtime",
        "--contract", "--rename",
        "--rename-table",
        "--no-input", "--lock-timeout", "--dialect", "--package", "--naming", "--extractor",
        "--schema-file", "--scratch-url",
        "--url", "--user", "--password", "--password-file", "--schema", "--locations",
        "--classpath", "--no-build", "--refresh", "--database");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    Path migrations = project.migrations();
    DuplicateMigrations.requireNone(project, context.migrationRunner());
    if (args.flag("--contract")) {
      return releaseContracts(project, out);
    }
    if (args.flag("--zero-downtime") && (args.flag("--keep-data") || args.flag("--safe"))) {
      throw new UsageException("--zero-downtime can't be combined with "
          + (args.flag("--keep-data") ? "--keep-data" : "--safe") + ".",
          "Generate the zero-downtime change on its own.");
    }
    boolean hasSnapshot = Files.exists(project.snapshot());
    if (!hasSnapshot && !project.hasUrl()) {
      throw new UsageException(ErrorCode.NO_DATABASE_URL,
          "No database URL found, and there is no snapshot yet.",
          "The first generate reads the current database as its baseline. Set "
              + project.urlSetting() + ", MIGRAX_DATABASE_URL, or --url.");
    }
    Dialect dialect = project.generating(project.dialect(false, out));
    EntityChanges changes = EntityChanges.read(context, dialect, true, true);
    if (changes.current().tables().isEmpty() && project.schemaFile() != null) {
      throw new UsageException(ErrorCode.NO_ENTITIES, changes.source() + " has no tables.",
          "Write the tables as they should be, as CREATE TABLE statements.");
    }
    if (changes.current().tables().isEmpty()) {
      throw new UsageException(ErrorCode.NO_ENTITIES,
          "No JPA entities found under package '" + project.packageName() + "'.",
          "Pass --package <name> or set MIGRAX_PACKAGE. Run 'migrax doctor' to diagnose.");
    }
    Log.debug("Entities read from {}.", changes.source());
    List<Operation> operations = changes.operations();
    // The first generate against a database that already has tables (and no migrations yet)
    // also writes those tables as a baseline migration, so the migrations alone can build an
    // empty database.
    boolean baseline = !hasSnapshot && !changes.previous().tables().isEmpty()
        && project.noSqlMigrations();
    if (baseline) {
      for (Path source : project.javaMigrationSources()) {
        if (BigInteger.ONE.equals(MigrationFiles.javaNumber(source))) {
          throw new UsageException("The database already has tables, so the first migration is "
              + "the baseline " + BASELINE + ".sql, but the Java migration "
              + source.getFileName() + " has number 0001.",
              "Give the Java migration a higher number (rename its class and file), then run "
                  + "'migrax generate' again.");
        }
      }
    }
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
            project.recordedNaming(), dialect.serverVersion());
        out.println("Saved the " + (project.schemaFile() != null ? "schema in "
            + changes.source() : "entity schema") + " as the baseline snapshot: "
            + project.display(project.snapshot()));
      }
      remindContracts(project, out);
      return OK;
    }
    List<Operation> destructive = operations.stream().filter(Operation::destructive).toList();
    if (!destructive.isEmpty() && !args.flag("--allow-destructive")) {
      Reports.printOperations(out, operations, dialect, null);
      throw new UsageException(ErrorCode.DESTRUCTIVE_CHANGES, destructive.size()
          + " destructive change(s) detected (marked [DESTRUCTIVE] above).",
          "Review them, then run 'migrax generate --allow-destructive'. If a column or table "
              + "was renamed, pass --rename table.old=new or --rename-table old=new instead.");
    }
    Migrations.warnings(operations, changes.previous()).forEach(Log::warn);
    if (args.flag("--zero-downtime")) {
      ZeroDowntime.Plan plan = ZeroDowntime.plan(changes.previous(), changes.current(),
          changes.tableRenames(), changes.columnRenames(), dialect);
      if (!plan.problems().isEmpty()) {
        throw new UsageException(String.join(System.lineSeparator(), plan.problems()),
            "Generate this change without --zero-downtime, or split it into smaller steps.");
      }
      if (!plan.contract().isEmpty()) {
        return zeroDowntime(context, dialect, changes, plan, baseline);
      }
      out.println("Nothing has to wait for the old version: writing a normal migration.");
    }

    // --safe on PostgreSQL: indexes and constraints on existing tables go to a second,
    // non-transactional migration that builds them without blocking writes.
    List<Operation> main = new ArrayList<>(operations);
    List<Operation> online = new ArrayList<>();
    if (args.flag("--safe")) {
      if (dialect instanceof org.migrax.dialect.YugabyteDialect) {
        Log.warn("--safe is not needed on YugabyteDB: it builds indexes without blocking "
            + "writes anyway. Generating a normal migration.");
      } else if (!(dialect instanceof PostgresDialect)) {
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
              || operation instanceof AddForeignKey || operation instanceof AddCheck)) {
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
      name = migrationName(args, project, changes.previous(), main.isEmpty() ? online : main);
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
        Files.deleteIfExists(project.historySnapshot(BASELINE));
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
      List<Operation> mainReverse = new ArrayList<>(reverse);
      mainReverse.removeIf(o -> undoesAny(o, online));
      if (args.flag("--keep-data")) {
        // The migration's number as in its file name: 0005 for 0005_drop_notes.
        KeepData.Plan keep = KeepData.apply(main, mainReverse, changes.previous(), dialect,
            name.split("_", 2)[0]);
        keep.warnings().forEach(Log::warn);
        Files.writeString(file,
            MigrationSql.render(dialect, keep.forward(), null, changes.previous(), afterMain));
        MigrationSql.writeRollback(migrations, file.getFileName().toString(), dialect, keep,
            main, afterMain, changes.previous());
      } else {
        Files.writeString(file,
            MigrationSql.render(dialect, main, null, changes.previous(), afterMain));
        MigrationSql.writeRollback(migrations, file.getFileName().toString(), dialect,
            mainReverse, main, afterMain, changes.previous());
      }
      written.add(file);
      SnapshotStore.save(project.historySnapshot(name), afterMain,
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
      SnapshotStore.save(project.historySnapshot(onlineName),
          changes.current(), dialect.id());
    }
    SnapshotStore.save(project.snapshot(), changes.current(), dialect.id(),
            project.recordedNaming(), dialect.serverVersion());

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
          SqlLinter.lint(project.display(path), Files.readString(path), dialect.id(),
              !project.schemas().isEmpty()));
    }
    out.println("Review the SQL, then run 'migrax migrate'.");
    remindContracts(project, out);
    return OK;
  }

  /**
   * Writes the expand migration and keeps the contract migration in .migrax/pending/ until
   * {@code generate --contract}.
   */
  private static int zeroDowntime(CommandContext context, Dialect dialect, EntityChanges changes,
                                  ZeroDowntime.Plan plan, boolean baseline) throws Exception {
    Project project = context.project();
    PrintStream out = context.out();
    Path migrations = project.migrations();
    Files.createDirectories(migrations);
    Path baselineFile = baseline ? writeBaseline(project, dialect, changes.previous()) : null;
    String name = migrationName(context.args(), project, changes.previous(),
        changes.operations());
    String expandName = name + "_expand.sql";
    String contractName = name + "_contract.sql";
    Path file = migrations.resolve(expandName);
    if (Files.exists(file)) {
      if (baselineFile != null) {
        Files.deleteIfExists(baselineFile);
        Files.deleteIfExists(project.historySnapshot(BASELINE));
      }
      throw new UsageException("Migration already exists: " + project.display(file),
          "Choose another --name.");
    }
    if (baselineFile != null) {
      recordBaseline(context, baselineFile);
      printBaseline(out, project, baselineFile, changes.previous());
    }
    StringBuilder notes = new StringBuilder();
    plan.notes().forEach(note -> notes.append("-- Note: ").append(note).append('\n'));
    String expandHeader = "-- Expand step of a zero-downtime change: safe while instances of "
        + "the old version still run.\n-- The contract step (" + contractName + ") waits in "
        + project.root().relativize(PendingContracts.folder(project)).toString()
            .replace('\\', '/') + "/ for the next release.\n" + notes;
    Files.writeString(file, MigrationSql.render(dialect, plan.expand(), expandHeader,
        changes.previous(), plan.middle()));
    MigrationSql.writeRollback(migrations, expandName, dialect, plan.expandReverse(),
        unsynced(plan.expand(), changes), plan.middle(), plan.rolledBack());
    SnapshotStore.save(project.historySnapshot(name + "_expand"),
        plan.middle(), dialect.id());

    Path pending = PendingContracts.folder(project);
    Files.createDirectories(pending);
    Path contract = pending.resolve(contractName);
    String contractHeader = "-- Contract step of a zero-downtime change: runs once no instance "
        + "of the version\n-- before " + expandName + " is left.\n" + notes;
    Files.writeString(contract, MigrationSql.render(dialect, plan.contract(), contractHeader,
        plan.middle(), changes.current()));
    MigrationSql.writeRollback(pending, contractName, dialect, plan.contractReverse(),
        unsynced(plan.contract(), changes), changes.current(), plan.middle());
    SnapshotStore.save(PendingContracts.schema(contract), changes.current(), dialect.id());
    SnapshotStore.save(project.snapshot(), changes.current(), dialect.id(),
        project.recordedNaming(), dialect.serverVersion());

    out.println("Created " + project.display(file) + " (expand: safe while the old version "
        + "runs):");
    describe(out, plan.expand(), changes, true);
    out.println("Waiting in " + project.display(contract) + " (contract: for the next "
        + "release):");
    describe(out, plan.contract(), changes, false);
    plan.notes().forEach(note -> out.println("Note: " + note));
    out.println("Rollback scripts written to "
        + project.display(migrations.resolve(MigrationLoader.ROLLBACK_FOLDER)) + " and "
        + project.display(pending.resolve(MigrationLoader.ROLLBACK_FOLDER)) + ".");
    Reports.printFindings(out, SqlLinter.lint(project.display(file), Files.readString(file),
        dialect.id(), !project.schemas().isEmpty()));
    out.println("Review the SQL, then run 'migrax migrate'. When every instance runs this "
        + "release, run 'migrax generate --contract' for the next one.");
    return OK;
  }

  /**
   * The operations whose data a rollback script can lose: not the two columns of a rename,
   * which the trigger keeps equal.
   */
  private static List<Operation> unsynced(List<Operation> operations, EntityChanges changes) {
    return operations.stream().filter(o -> changes.columnRenames().stream().noneMatch(r ->
        o instanceof org.migrax.ops.AddColumn add && add.table().equals(r.table())
            && add.column().name().equals(r.to())
        || o instanceof org.migrax.ops.DropColumn drop && drop.table().equals(r.table())
            && drop.column().equals(r.from()))).toList();
  }

  /** The operations of one step, with the triggers and views in words. */
  private static void describe(PrintStream out, List<Operation> operations,
                               EntityChanges changes, boolean expand) {
    for (Operation operation : operations) {
      if (!(operation instanceof org.migrax.ops.RunSql)) {
        out.println("  - " + Migrations.summary(operation));
      }
    }
    for (org.migrax.diff.Renames.ColumnRename rename : changes.columnRenames()) {
      out.println("  - " + (expand ? "copy " + rename.table() + "." + rename.from() + " to "
          + rename.to() + " and keep both equal (trigger)"
          : "remove the trigger that keeps " + rename.table() + "." + rename.from() + " and "
              + rename.to() + " equal"));
    }
    for (org.migrax.diff.Renames.TableRename rename : changes.tableRenames()) {
      out.println("  - " + (expand ? "keep " + rename.from() + " as a view on " + rename.to()
          : "drop the view " + rename.from()));
    }
  }

  /** {@code generate --contract}: the waiting contract migrations join the migrations. */
  private static int releaseContracts(Project project, PrintStream out) throws Exception {
    List<Path> written = PendingContracts.release(project);
    if (written.isEmpty()) {
      out.println("No contract migrations are waiting.");
      return OK;
    }
    for (Path path : written) {
      out.println("Created " + project.display(path) + ".");
    }
    out.println("Release them only when no instance of the version before their expand "
        + "migration is left.");
    return OK;
  }

  private static void remindContracts(Project project, PrintStream out) throws Exception {
    List<Path> pending = PendingContracts.list(project);
    if (!pending.isEmpty()) {
      out.println(pending.size() + " contract migration(s) wait in "
          + project.display(PendingContracts.folder(project)) + ": add them to the release "
          + "after their expand migration with 'migrax generate --contract'.");
    }
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
    SnapshotStore.save(project.historySnapshot(BASELINE), database,
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

  private static String migrationName(Args args, Project project, SchemaModel previous,
                                      List<Operation> operations) throws Exception {
    String name = args.option("--name");
    if (Project.blank(name)) {
      String generated = Migrations.nextName(project.migrationFolders(),
          previous.tables().isEmpty(), operations);
      // nextName counts the SQL files only; Java migrations take numbers too.
      int separator = generated.indexOf('_');
      BigInteger next = project.nextNumber()
          .max(new BigInteger(generated.substring(0, separator)));
      return String.format(Locale.ROOT, "%04d_%s", next, generated.substring(separator + 1));
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
      name = String.format(Locale.ROOT, "%04d_%s", project.nextNumber(), name);
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
      if (reverse instanceof DropCheck drop && operation instanceof AddCheck add
          && drop.name().equals(add.check().name())) {
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
      List<SchemaModel.Check> checks = table.checks().stream().filter(c -> online.stream()
          .noneMatch(o -> o instanceof AddCheck a && a.table().equals(table.name())
              && a.check().name().equals(c.name()))).toList();
      List<SchemaModel.Column> columns = table.columns().stream().map(c -> online.stream()
          .anyMatch(o -> o instanceof AddUnique u && u.table().equals(table.name())
              && u.column().equals(c.name()))
          ? new SchemaModel.Column(c.name(), c.sqlType(), c.nullable(), c.length(), c.precision(),
              c.scale(), c.defaultValue(), false, c.identity(), c.sequenceName(), c.logicalType(),
              c.comment())
          : c).toList();
      tables.add(new SchemaModel.Table(table.name(), columns, table.primaryKey(), indexes, keys,
          checks, table.comment()));
    }
    return new SchemaModel(tables, model.sequences());
  }
}
