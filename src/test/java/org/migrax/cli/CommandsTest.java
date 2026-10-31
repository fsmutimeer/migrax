package org.migrax.cli;

import org.migrax.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests of the newer commands against an H2 project. */
class CommandsTest {
  private static int databases;

  @TempDir
  Path project;

  private String url;

  private record Result(int code, String out, String err) {
    String all() {
      return out + err;
    }
  }

  private Result ask(String input, String... args) {
    String[] full = new String[args.length + 3];
    System.arraycopy(args, 0, full, 0, args.length);
    full[args.length] = "--dir";
    full[args.length + 1] = project.toString();
    full[args.length + 2] = "--no-build";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = Main.run(full, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8),
        input == null ? null : new BufferedReader(new StringReader(input)));
    return new Result(code, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  private Result cli(String... args) {
    return ask(null, args);
  }

  private void setUp() throws Exception {
    Files.writeString(project.resolve("pom.xml"),
        "<project><groupId>org.migrax.cli.fixture</groupId></project>");
    url = "jdbc:h2:mem:commands_" + (++databases) + ";DB_CLOSE_DELAY=-1";
    Path resources = project.resolve("src/main/resources");
    Files.createDirectories(resources);
    Files.writeString(resources.resolve("application.properties"),
        "spring.datasource.url=" + url + "\n");
  }

  private Path migrations() {
    return project.resolve("src/main/resources/db/migration");
  }

  private void execute(String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(url);
         var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private String query(String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(url);
         var statement = connection.createStatement();
         var result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : null;
    }
  }

  private void generateAndMigrate() {
    Result generate = cli("generate");
    assertEquals(0, generate.code(), generate.all());
    Result migrate = cli("migrate");
    assertEquals(0, migrate.code(), migrate.all());
  }

  /** Simulates an entity field rename from old_name to name. */
  private void renameInSnapshotAndDatabase() throws Exception {
    Path snapshot = project.resolve(".migrax/snapshot.json");
    Files.writeString(snapshot,
        Files.readString(snapshot).replace("{\"name\":\"name\"", "{\"name\":\"old_name\""));
    execute("ALTER TABLE sample_entity RENAME COLUMN name TO old_name");
  }

  @Test
  void renamesKeepDataInsteadOfDropAndAdd() throws Exception {
    setUp();
    generateAndMigrate();
    renameInSnapshotAndDatabase();
    execute("INSERT INTO sample_entity (old_name) VALUES ('kept')");

    Result refused = cli("generate");
    assertEquals(1, refused.code());
    assertTrue(refused.out().contains("--rename sample_entity.old_name=name"), refused.all());

    Result renamed = cli("generate", "--rename", "sample_entity.old_name=name");
    assertEquals(0, renamed.code(), renamed.all());
    assertTrue(renamed.out().contains("rename column sample_entity.old_name to name"),
        renamed.out());
    assertTrue(renamed.out().contains("MX008"), "renames are linted: " + renamed.out());
    assertEquals(0, cli("migrate").code());
    assertEquals("kept", query("SELECT name FROM sample_entity"));
  }

  @Test
  void asksAboutRenamesInteractively() throws Exception {
    setUp();
    generateAndMigrate();
    renameInSnapshotAndDatabase();
    Result answered = ask("y\n", "generate");
    assertEquals(0, answered.code(), answered.all());
    assertTrue(answered.out().contains("Did you rename sample_entity.old_name to "
        + "sample_entity.name? [y/N]"), answered.out());
    assertTrue(answered.out().contains("rename column"), answered.out());
  }

  @Test
  void asksAboutTheNextLikelyNameAfterANo() throws Exception {
    setUp();
    String before = "org.migrax.cli.renames.before";
    String after = "org.migrax.cli.renames.after";
    Result first = cli("generate", "--package", before);
    assertEquals(0, first.code(), first.all());

    Result hint = cli("generate", "--package", after, "--dry-run");
    assertEquals(1, hint.out().split("Possible rename: ", -1).length - 1, hint.out());
    assertTrue(hint.out().contains("--rename customers.name=full_name"), hint.out());

    Result answered = ask("n\ny\n", "generate", "--package", after);
    assertEquals(1, answered.code(), "renaming name to country narrows it: " + answered.all());
    assertTrue(answered.out().contains(
        "Did you rename customers.name to customers.full_name? [y/N]"), answered.out());
    assertTrue(answered.out().contains(
        "Did you rename customers.name to customers.country? [y/N]"), answered.out());
    assertTrue(answered.out().contains("rename column customers.name to country"),
        answered.out());

    // Input that ends (Ctrl-D) stops the questions; the unanswered one becomes a hint.
    Result ended = ask("", "generate", "--package", after);
    assertEquals(1, ended.out().split("Did you rename ", -1).length - 1, ended.out());
    assertTrue(ended.out().contains("Possible rename: customers.name to customers.full_name. "
        + "To keep the data, rerun with --rename customers.name=full_name."), ended.out());
  }

  @Test
  void asksAtMostThreeQuestionsPerColumn() throws Exception {
    setUp();
    assertEquals(0, cli("generate", "--package", "org.migrax.cli.renames.before").code());
    Result answered = ask("n\nn\nn\nn\n", "generate", "--package",
        "org.migrax.cli.renames.many");
    assertEquals(3, answered.out().split("Did you rename ", -1).length - 1, answered.out());
  }

  @Test
  void aNoLeavesTheNewColumnFreeForTheNextDroppedOne() throws Exception {
    setUp();
    assertEquals(0, cli("generate", "--package", "org.migrax.cli.renames.twodropped").code());
    Result answered = ask("n\ny\n", "generate", "--package", "org.migrax.cli.renames.onename",
        "--allow-destructive");
    assertTrue(answered.out().contains(
        "Did you rename customers.nickname to customers.full_name? [y/N]"), answered.out());
    assertTrue(answered.out().contains("rename column customers.nickname to full_name"),
        answered.out());
  }

  @Test
  void forgetRemovesMigrationsDeletedOnPurpose() throws Exception {
    setUp();
    generateAndMigrate();

    Result kept = cli("repair", "0001_initial.sql", "--action", "forget", "--yes");
    assertEquals(1, kept.code(), "a file that still exists must not be forgotten");
    assertTrue(kept.err().contains("still exists"), kept.all());

    Files.delete(migrations().resolve("0001_initial.sql"));
    Result missing = cli("migrate");
    assertEquals(1, missing.code());
    assertTrue(missing.err().contains(
        "migrax repair 0001_initial.sql --action forget --yes"), missing.all());

    Result unconfirmed = cli("repair", "0001_initial", "--action", "forget");
    assertEquals(1, unconfirmed.code());

    Result forgotten = cli("repair", "0001_initial", "--action", "forget", "--yes");
    assertEquals(0, forgotten.code(), forgotten.all());
    assertTrue(forgotten.out().contains("Removed 0001_initial.sql from the migration history"),
        forgotten.out());
    assertEquals("0", query("SELECT COUNT(*) FROM migrax_history"));
    assertEquals("0", query("SELECT COUNT(*) FROM sample_entity"),
        "the database keeps the table the forgotten migration created");

    Result migrate = cli("migrate");
    assertEquals(0, migrate.code(), migrate.all());
    Result again = cli("repair", "0001_initial", "--action", "forget", "--yes");
    assertTrue(again.out().contains("nothing to forget"), again.out());
  }

  @Test
  void rollbackUndoesTheLatestMigration() throws Exception {
    setUp();
    generateAndMigrate();
    assertTrue(Files.isRegularFile(migrations().resolve("rollback/0001_initial.sql")));

    Result unconfirmed = cli("rollback");
    assertEquals(1, unconfirmed.code());
    assertTrue(unconfirmed.err().contains("--yes"), unconfirmed.all());

    Result dryRun = cli("rollback", "--dry-run");
    assertEquals(0, dryRun.code(), dryRun.all());
    assertTrue(dryRun.out().contains("Would roll back 1 migration(s)"), dryRun.out());

    Result rollback = cli("rollback", "--yes");
    assertEquals(0, rollback.code(), rollback.all());
    assertTrue(cli("status").out().contains("[ ] 0001_initial.sql"));
    assertEquals(0, cli("migrate").code());
    assertTrue(cli("status").out().contains("[X] 0001_initial.sql"));
  }

  @Test
  void lintFindsBlockingStatements() throws Exception {
    setUp();
    Files.createDirectories(migrations());
    Files.writeString(migrations().resolve("0001_index.sql"),
        "CREATE INDEX idx_name ON customer (name);\nDELETE FROM customer;\n");
    Result warnings = cli("lint", "--all", "--dialect", "postgresql");
    assertEquals(0, warnings.code(), warnings.all());
    assertTrue(warnings.out().contains("MX001"), warnings.out());
    assertTrue(warnings.out().contains("MX009"), warnings.out());
    assertEquals(1, cli("lint", "--all", "--dialect", "postgresql", "--strict").code());

    Result json = cli("lint", "--all", "--dialect", "postgresql", "--json");
    assertEquals(0, json.code(), json.all());
    Object parsed = new Json.Parser(json.out()).parse();
    assertTrue(parsed.toString().contains("MX001"), json.out());

    Files.writeString(migrations().resolve("0002_ignored.sql"),
        "-- migrax:lint-ignore MX001\nCREATE INDEX idx_x ON customer (x);\n");
    assertFalse(cli("lint", "0002_ignored.sql", "--dialect", "postgresql").out()
        .contains("MX001"));
  }

  @Test
  void verifyAppliesEverythingToAThrowawayDatabaseAndTestsRollbacks() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    Result verify = cli("verify");
    assertEquals(0, verify.code(), verify.all());
    assertTrue(verify.out().contains("Applied 1 migration(s) to a fresh h2 database"),
        verify.out());
    assertTrue(verify.out().contains("Rollback scripts: ok"), verify.out());
    assertTrue(verify.out().contains("Verified."), verify.out());
    // The configured database was not touched.
    assertTrue(cli("status").out().contains("[ ] 0001_initial.sql"));

    Files.writeString(migrations().resolve("0001_initial.sql"), "CREATE TABLE wrong (id INT);\n");
    Files.writeString(migrations().resolve("rollback/0001_initial.sql"), "DROP TABLE wrong;\n");
    Result broken = cli("verify");
    assertEquals(1, broken.code(), broken.all());
    assertTrue(broken.out().contains("sample_entity"), broken.out());

    // A migration that fails there: no advice about repairing a real database.
    Files.writeString(migrations().resolve("0002_fails.sql"),
        "INSERT INTO missing_table VALUES (1);\n");
    Result fails = cli("verify");
    assertEquals(1, fails.code(), fails.all());
    assertTrue(fails.all().contains("error[MXE102]: Migration '0002_fails.sql' fails on an empty "
        + "database"), fails.all());
    assertFalse(fails.all().contains("repair"), fails.all());
  }

  @Test
  void verifyNamesTheRollbackScriptThatLeavesSomethingBehind() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    // Rolling everything back hides this: dropping the table also drops the index.
    Files.writeString(migrations().resolve("0002_index.sql"),
        "CREATE INDEX idx_extra ON sample_entity (name);\n");
    Files.writeString(migrations().resolve("rollback/0002_index.sql"), "-- forgot the index\n");
    Result verify = cli("verify");
    assertEquals(1, verify.code(), verify.all());
    assertTrue(verify.out().contains("Rollback scripts: failed: 0002_index.sql fails when "
        + "applied again after its rollback script ran"), verify.out());
    assertTrue(verify.out().contains("IDX_EXTRA"), "the database's own error: " + verify.out());
    assertFalse(verify.out().contains("repair"), verify.out());

    Files.writeString(migrations().resolve("rollback/0002_index.sql"), "DROP INDEX idx_extra;\n");
    Result fixed = cli("verify");
    assertTrue(fixed.out().contains("Rollback scripts: ok (2 rolled back and re-applied)"),
        fixed.out());
  }

  /** A database with the entity's table that Migrax has never managed. */
  private void existingDatabaseWithoutMigrations() throws Exception {
    generateAndMigrate();
    try (Stream<Path> files = Files.walk(migrations())) {
      files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
          .forEach(java.io.File::delete);
    }
    try (Stream<Path> files = Files.walk(project.resolve(".migrax"))) {
      files.sorted(java.util.Comparator.reverseOrder()).map(Path::toFile)
          .forEach(java.io.File::delete);
    }
    execute("DROP TABLE migrax_history");
    execute("DROP TABLE migrax_failures");
  }

  @Test
  void firstGenerateWritesTheExistingTablesAsABaseline() throws Exception {
    setUp();
    existingDatabaseWithoutMigrations();

    Result generate = cli("generate");
    assertEquals(0, generate.code(), generate.all());
    assertTrue(generate.out().contains("No changes detected."), generate.out());
    assertTrue(generate.out().contains("0001_baseline.sql with the 1 table(s)"), generate.out());
    assertTrue(Files.readString(migrations().resolve("0001_baseline.sql"))
        .contains("CREATE TABLE sample_entity"));
    assertFalse(Files.exists(migrations().resolve("rollback/0001_baseline.sql")),
        "undoing the baseline would drop every table");
    assertTrue(cli("status").out().contains("[X] 0001_baseline.sql"),
        "the baseline is recorded as applied without running it");
    assertEquals(0, cli("migrate").code());

    Result verify = cli("verify");
    assertEquals(0, verify.code(), verify.all());
    assertTrue(verify.out().contains("Applied 1 migration(s) to a fresh h2 database"),
        verify.out());
  }

  @Test
  void aJavaMigrationWithAHugeNumberIsFollowedNotACrash() throws Exception {
    setUp();
    generateAndMigrate();
    Path java = project.resolve("src/main/java/db/migration/V99999999999__Big.java");
    Files.createDirectories(java.getParent());
    Files.writeString(java, "package db.migration;\n");

    Result check = cli("check");
    assertEquals(0, check.code(), check.all());
    Result created = cli("new", "next");
    assertEquals(0, created.code(), created.all());
    assertTrue(Files.exists(project.resolve(
        "src/main/resources/db/migration/100000000000_next.sql")), created.all());
  }

  @Test
  void numbersAboveAnIntAreFollowedNotWrappedAround() throws Exception {
    setUp();
    generateAndMigrate();
    // 4294967297 wrapped to 1 as an int, so the next file used to become 0002 again.
    Path folder = project.resolve("src/main/resources/db/migration");
    Files.writeString(folder.resolve("4294967297_far.sql"), "SELECT 1;\n");
    Result created = cli("new", "next");
    assertEquals(0, created.code(), created.all());
    assertTrue(Files.exists(folder.resolve("4294967298_next.sql")), created.all());

    // Date-style numbers, common after 'migrax import', keep counting up as well.
    Path java = project.resolve("src/main/java/db/migration/V20261027120000__Dated.java");
    Files.createDirectories(java.getParent());
    Files.writeString(java, "package db.migration;\n");
    created = cli("new", "later", "--java");
    assertEquals(0, created.code(), created.all());
    assertTrue(Files.exists(java.resolveSibling("V20261027120001__Later.java")), created.all());
  }

  @Test
  void theJavaPackageMustBeAPackageName() throws Exception {
    setUp();
    // "db.mig\\u0001ration" is a valid Java name, but the control character hides in the path.
    for (String bad : java.util.List.of("../outside", "/tmp/abs", "..secret", "a;b",
        "db.mig\u0001ration")) {
      Result created = cli("new", "x", "--java", "--java-package", bad);
      assertEquals(1, created.code(), bad + ": " + created.all());
      assertTrue(created.err().contains("Invalid Java package '" + bad + "'"), created.all());
    }
    // The migration loader takes a comma list and trims; new writes into the first package.
    for (String good : java.util.List.of("com.example.migrations", "db.café ",
        "db.more,!db.more.old")) {
      Result created = cli("new", "x", "--java", "--java-package", good);
      assertEquals(0, created.code(), good + ": " + created.all());
      String folder = good.split(",")[0].trim().replace('.', '/');
      assertTrue(Files.isDirectory(project.resolve("src/main/java/" + folder)), created.all());
    }
  }

  @Test
  void theBaselineNeedsNumberOneFreeOfJavaMigrations() throws Exception {
    setUp();
    existingDatabaseWithoutMigrations();
    assertEquals(0, cli("new", "seed", "--java").code());

    Result generate = cli("generate");
    assertEquals(1, generate.code(), generate.all());
    assertTrue(generate.err().contains("the Java migration V0001__Seed.java has number 0001"),
        generate.all());
    assertFalse(Files.exists(migrations().resolve("0001_baseline.sql")), generate.all());
  }

  @Test
  void firstGenerateWritesTheBaselineBeforeTheChanges() throws Exception {
    setUp();
    existingDatabaseWithoutMigrations();
    execute("ALTER TABLE sample_entity RENAME COLUMN name TO old_name");

    Result refused = cli("generate");
    assertEquals(1, refused.code(), refused.all());
    assertFalse(Files.exists(migrations().resolve("0001_baseline.sql")),
        "a refused generate writes nothing");
    Result clash = cli("generate", "--rename", "sample_entity.old_name=name", "--name", "0001_x");
    assertEquals(1, clash.code(), clash.all());
    assertFalse(Files.exists(migrations().resolve("0001_baseline.sql")), clash.all());

    Result generate = cli("generate", "--rename", "sample_entity.old_name=name");
    assertEquals(0, generate.code(), generate.all());
    assertTrue(generate.out().indexOf("0001_baseline.sql")
        < generate.out().indexOf("0002_rename_sample_entity_old_name.sql"), generate.out());
    String status = cli("status").out();
    assertTrue(status.contains("[X] 0001_baseline.sql"), status);
    assertTrue(status.contains("[ ] 0002_rename_sample_entity_old_name.sql"), status);
    assertEquals(0, cli("migrate").code());

    Result verify = cli("verify");
    assertEquals(0, verify.code(), verify.all());
    assertTrue(verify.out().contains("Applied 2 migration(s) to a fresh h2 database"),
        verify.out());
    assertTrue(verify.out().contains("Rollback scripts: ok (1 rolled back and re-applied; "
        + "older ones from 0001_baseline.sql down have no rollback script)"), verify.out());
  }

  @Test
  void backupsListsAndDropsTheKeepDataTables() throws Exception {
    setUp();
    generateAndMigrate();
    execute("CREATE TABLE migrax_backup_0003_orders (id bigint)");
    execute("CREATE TABLE migrax_backup_0004_orders_notes (id bigint, notes varchar(10))");

    Result list = cli("backups");
    assertEquals(0, list.code(), list.all());
    assertTrue(list.out().contains("MIGRAX_BACKUP_0003_ORDERS")
        && list.out().contains("MIGRAX_BACKUP_0004_ORDERS_NOTES"), list.out());

    Result unconfirmed = cli("backups", "--drop");
    assertEquals(1, unconfirmed.code(), unconfirmed.all());
    assertTrue(unconfirmed.err().contains("--yes"), unconfirmed.all());

    Result one = cli("backups", "--drop", "0004", "--yes");
    assertEquals(0, one.code(), one.all());
    assertTrue(one.out().contains("Dropped 1 backup table(s)."), one.out());
    Result rest = cli("backups");
    assertTrue(rest.out().contains("MIGRAX_BACKUP_0003_ORDERS")
        && !rest.out().contains("0004"), rest.out());
    // Backups are not entities: drift and check leave them alone.
    assertEquals(0, cli("check").code());
  }

  @Test
  void cleanEmptiesTheDatabaseOnlyWhenConfirmed() throws Exception {
    setUp();
    generateAndMigrate();
    execute("CREATE VIEW names AS SELECT name FROM sample_entity");
    execute("CREATE SEQUENCE leftover_seq");
    // Names created in quotes keep their lower case on H2: clean must quote them too.
    execute("CREATE TABLE \"customers\" (id INT)");
    execute("CREATE SEQUENCE \"orders_seq\"");

    Result unconfirmed = cli("clean");
    assertEquals(1, unconfirmed.code(), unconfirmed.all());
    assertTrue(unconfirmed.err().contains("--yes"), unconfirmed.all());
    assertEquals("1", query("SELECT COUNT(*) FROM migrax_history"), "nothing was dropped");

    Result dryRun = cli("clean", "--dry-run");
    assertEquals(0, dryRun.code(), dryRun.all());
    assertTrue(dryRun.out().contains("table    SAMPLE_ENTITY"), dryRun.out());
    assertTrue(dryRun.out().contains("view     NAMES"), dryRun.out());
    assertTrue(dryRun.out().contains("sequence leftover_seq"), dryRun.out());

    System.setProperty("migrax.cleanDisabled", "true");
    try {
      Result disabled = cli("clean", "--yes");
      assertEquals(1, disabled.code(), disabled.all());
      assertTrue(disabled.err().contains("MIGRAX_CLEAN_DISABLED"), disabled.all());
    } finally {
      System.clearProperty("migrax.cleanDisabled");
    }

    Result cleaned = cli("clean", "--yes");
    assertEquals(0, cleaned.code(), cleaned.all());
    assertTrue(cleaned.out().contains("Dropped 4 table(s), 1 view(s), 2 sequence(s)"),
        cleaned.out());
    assertEquals("0", query("SELECT COUNT(*) FROM information_schema.tables "
        + "WHERE table_schema = 'PUBLIC'"));
    assertEquals("0", query("SELECT COUNT(*) FROM information_schema.sequences "
        + "WHERE sequence_schema = 'PUBLIC'"));
    assertTrue(cli("clean", "--yes").out().contains("Nothing to clean"));

    Result migrate = cli("migrate");
    assertEquals(0, migrate.code(), migrate.all());
    assertEquals("0", query("SELECT COUNT(*) FROM sample_entity"), "migrate rebuilt the schema");
  }

  @Test
  void migrateWaitsForTheLockWithLockTimeout() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
    Thread holder = new Thread(() -> {
      try (Connection connection = DriverManager.getConnection(url);
           AutoCloseable lock = org.migrax.runner.DatabaseMigrationLock.acquire(connection)) {
        held.countDown();
        Thread.sleep(1500);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    holder.start();
    held.await();

    Result refused = cli("migrate");
    assertEquals(1, refused.code(), refused.all());
    assertTrue(refused.err().contains("--lock-timeout 2m"), refused.all());
    assertTrue(refused.err().startsWith("error[MXE101]:"), refused.err());

    Result waited = cli("migrate", "--lock-timeout", "10s");
    assertEquals(0, waited.code(), waited.all());
    assertTrue(waited.out().contains("Applied 1 migration(s)"), waited.out());
    holder.join();

    Result invalid = cli("migrate", "--lock-timeout", "soon");
    assertEquals(1, invalid.code());
    assertTrue(invalid.err().contains("Invalid lock timeout 'soon'"), invalid.all());
  }

  @Test
  void diffComparesTwoDatabasesAndItsSqlMakesThemEqual() throws Exception {
    setUp();
    String source = "jdbc:h2:mem:commands_source_" + (++databases) + ";DB_CLOSE_DELAY=-1";
    try (Connection connection = DriverManager.getConnection(source);
         var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE customer (id BIGINT PRIMARY KEY, name VARCHAR(100) "
          + "NOT NULL, email VARCHAR(200))");
      statement.execute("CREATE INDEX idx_customer_email ON customer (email)");
    }
    execute("CREATE TABLE customer (id BIGINT PRIMARY KEY, name VARCHAR(50) NOT NULL, "
        + "legacy VARCHAR(10))");

    Result diff = cli("diff", source);
    assertEquals(ExitCode.CHANGES_DETECTED, diff.code(), diff.all());
    assertTrue(diff.out().replace("\r\n", "\n").startsWith("To make " + url + " match "
        + source + ":\n4 change(s) for h2:\n"), diff.out());
    assertTrue(diff.out().contains("drop column customer.legacy  [DESTRUCTIVE]"), diff.out());
    assertTrue(diff.out().contains("add index idx_customer_email on customer"), diff.out());

    Result json = cli("diff", source, "--json");
    assertTrue(json.out().contains("\"kind\":\"add_column\""), json.out());

    Result sql = cli("diff", source, "--sql");
    assertEquals(ExitCode.CHANGES_DETECTED, sql.code(), sql.all());
    try (Connection connection = DriverManager.getConnection(url);
         var statement = connection.createStatement()) {
      String script = sql.out().replace("\r\n", "\n").replaceAll("(?m)^--.*\n", "");
      for (String sqlStatement : script.split(";\n")) {
        if (!sqlStatement.isBlank()) {
          statement.execute(sqlStatement);
        }
      }
    }
    Result same = cli("diff", source);
    assertEquals(0, same.code(), same.all());
    assertEquals("Same schema: " + url + " matches " + source + ".", same.out().strip());

    Result missing = cli("diff");
    assertEquals(1, missing.code());
    assertTrue(missing.all().contains("Name the source database"), missing.all());
  }

  @Test
  void diffHidesPasswordsInUrls() {
    assertEquals("jdbc:postgresql://db/shop?user=a&password=***&ssl=true",
        DiffCommand.masked("jdbc:postgresql://db/shop?user=a&password=secret&ssl=true"));
    assertEquals("jdbc:sqlserver://db;user=a;password=***",
        DiffCommand.masked("jdbc:sqlserver://db;user=a;password=secret"));
    assertEquals("jdbc:mysql://a:***@db/shop", DiffCommand.masked("jdbc:mysql://a:secret@db/shop"));
    // The forms its own weaker masking missed (diff, environments, the MCP environments tool).
    assertEquals("jdbc:oracle:thin:scott/***@db:1521/FREE",
        DiffCommand.masked("jdbc:oracle:thin:scott/secret@db:1521/FREE"));
    assertEquals("jdbc:sqlserver://db;password=***",
        DiffCommand.masked("jdbc:sqlserver://db;password={sec;ret}"));
    assertEquals("jdbc:mysql://db/shop?password1=***", DiffCommand.masked(
        "jdbc:mysql://db/shop?password1=secret"));
  }

  @Test
  void cleanNeverShowsThePassword() throws Exception {
    setUp();
    // A database of its own, created with this password, so clean connects and names it.
    Result dryRun = cli("clean", "--dry-run", "--url",
        "jdbc:h2:mem:clean_password_" + (++databases) + ";DB_CLOSE_DELAY=-1;PASSWORD=TopSecret");
    assertFalse(dryRun.all().contains("TopSecret"), dryRun.all());
    assertTrue(dryRun.out().contains("PASSWORD=***"), dryRun.all());
  }

  @Test
  void driftReportsManualChanges() throws Exception {
    setUp();
    generateAndMigrate();
    assertEquals(0, cli("drift").code());
    execute("ALTER TABLE sample_entity ADD COLUMN hotfix VARCHAR(10)");
    Result drift = cli("drift");
    assertEquals(ExitCode.CHANGES_DETECTED, drift.code(), drift.all());
    assertTrue(drift.out().contains("sample_entity.hotfix"), drift.out());
    Result json = cli("drift", "--json");
    assertTrue(json.out().contains("extra_column"), json.out());
  }

  @Test
  void importsFlywayHistory() throws Exception {
    setUp();
    Files.createDirectories(migrations());
    Files.writeString(migrations().resolve("V1__init.sql"), "CREATE TABLE legacy (id INT);\n");
    Files.writeString(migrations().resolve("V2__more.sql"), "CREATE TABLE more (id INT);\n");
    execute("CREATE TABLE legacy (id INT)");
    execute("CREATE TABLE \"flyway_schema_history\" (\"installed_rank\" INT, \"version\" "
        + "VARCHAR(50), \"type\" VARCHAR(20), \"script\" VARCHAR(1000), \"success\" BOOLEAN)");
    execute("INSERT INTO \"flyway_schema_history\" VALUES (1, '1', 'SQL', 'V1__init.sql', TRUE)");

    Result imported = cli("import", "flyway");
    assertEquals(0, imported.code(), imported.all());
    assertTrue(imported.out().contains("Recorded 1 migration(s)"), imported.out());
    Result migrate = cli("migrate");
    assertEquals(0, migrate.code(), migrate.all());
    assertTrue(migrate.out().contains("Applied 1 migration(s)"), migrate.out());
    assertEquals("0", query("SELECT COUNT(*) FROM more"));
  }

  @Test
  void importsALiquibaseDatabaseAsABaseline() throws Exception {
    setUp();
    existingDatabaseWithoutMigrations();
    execute("CREATE TABLE DATABASECHANGELOG (ID VARCHAR(255), AUTHOR VARCHAR(255))");
    execute("INSERT INTO DATABASECHANGELOG VALUES ('1', 'team'), ('2', 'team')");

    Result imported = cli("import", "liquibase");
    assertEquals(0, imported.code(), imported.all());
    assertTrue(imported.out().contains("Wrote 0001_liquibase_baseline.sql with 1 table(s)"),
        imported.out());
    assertTrue(imported.out().contains("Recorded 1 migration(s) as applied"), imported.out());
    assertTrue(Files.readString(migrations().resolve("0001_liquibase_baseline.sql"))
        .contains("CREATE TABLE sample_entity"));
    assertTrue(cli("status").out().contains("[X] 0001_liquibase_baseline.sql"));

    Result generate = cli("generate");
    assertEquals(0, generate.code(), generate.all());
    assertTrue(generate.out().contains("No changes detected."), generate.out());
    assertFalse(Files.exists(migrations().resolve("0001_baseline.sql")),
        "the imported baseline already builds an empty database");
    Result verify = cli("verify");
    assertEquals(0, verify.code(), verify.all());
  }

  @Test
  void squashReplacesOldMigrations() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    assertEquals(0, cli("new", "seed data").code());
    Path seed = migrations().resolve("0002_seed_data.sql");
    Files.writeString(seed, Files.readString(seed)
        + "INSERT INTO sample_entity (name) VALUES ('seed');\n");
    Files.writeString(migrations().resolve("rollback/0002_seed_data.sql"),
        "DELETE FROM sample_entity;\n");

    Result squash = cli("squash", "--to", "0002");
    assertEquals(0, squash.code(), squash.all());
    Path squashed = migrations().resolve("0002_squashed_0001_0002.sql");
    assertTrue(Files.isRegularFile(squashed), squash.out());
    Files.delete(migrations().resolve("0001_initial.sql"));
    Files.delete(seed);

    Result migrate = cli("migrate");
    assertEquals(0, migrate.code(), migrate.all());
    assertEquals("seed", query("SELECT name FROM sample_entity"));
    Result status = cli("status");
    assertEquals(0, status.code(), status.all());
  }

  @Test
  void mergeRenumbersDuplicateMigrations() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    Files.writeString(migrations().resolve("0002_branch_a.sql"), "SELECT 1;\n");
    Files.writeString(migrations().resolve("0002_branch_b.sql"), "SELECT 2;\n");
    Files.setLastModifiedTime(migrations().resolve("0002_branch_b.sql"),
        java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000));

    Result check = cli("check");
    assertEquals(1, check.code(), check.all());
    assertTrue(check.err().contains("migrax merge"), check.all());
    assertEquals(1, cli("generate").code());

    Result merge = cli("merge");
    assertEquals(0, merge.code(), merge.all());
    assertTrue(Files.exists(migrations().resolve("0003_branch_b.sql")), merge.out());
    assertEquals(0, cli("check").code());
  }

  @Test
  void mergeRebuildsAConflictedSnapshot() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    Path snapshot = project.resolve(".migrax/snapshot.json");
    Files.writeString(snapshot, "<<<<<<< HEAD\n" + Files.readString(snapshot)
        + "=======\n{}\n>>>>>>> branch\n");
    assertEquals(1, cli("check").code());
    Result merge = cli("merge");
    assertEquals(0, merge.code(), merge.all());
    assertTrue(merge.out().contains("Rebuilt"), merge.out());
    assertEquals(0, cli("check").code());
  }

  @Test
  void newCreatesSqlAndJavaMigrations() throws Exception {
    setUp();
    Result sql = cli("new", "Backfill names");
    assertEquals(0, sql.code(), sql.all());
    assertTrue(Files.exists(migrations().resolve("0001_backfill_names.sql")));
    assertTrue(Files.exists(migrations().resolve("rollback/0001_backfill_names.sql")));

    Result java = cli("new", "fix totals", "--java");
    assertEquals(0, java.code(), java.all());
    Path file = project.resolve("src/main/java/db/migration/V0002__FixTotals.java");
    assertTrue(Files.readString(file).contains("implements JavaMigration"));
  }

  @Test
  void generateNumbersAfterJavaMigrations() throws Exception {
    setUp();
    generateAndMigrate();
    assertEquals(0, cli("new", "split names", "--java").code());
    renameInSnapshotAndDatabase();

    Result generated = cli("generate", "--rename", "sample_entity.old_name=name");
    assertEquals(0, generated.code(), generated.all());
    try (var files = Files.list(migrations())) {
      assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("0003_rename_")),
          generated.out());
    }
  }

  @Test
  void aSqlAndAJavaMigrationWithTheSameNumberAreReported() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    Path java = project.resolve("src/main/java/db/migration/V0001__SplitNames.java");
    Files.createDirectories(java.getParent());
    Files.writeString(java, "package db.migration;\n");

    Result check = cli("check");
    assertEquals(1, check.code(), check.all());
    assertTrue(check.err().contains("[0001_initial.sql, V0001__SplitNames]"), check.all());
    assertTrue(check.err().contains("rename the one that is not applied yet"), check.all());
    Result generate = cli("generate");
    assertEquals(1, generate.code(), generate.all());
    assertTrue(generate.err().startsWith("error[MXE"), generate.all());
  }

  @Test
  void jsonOutputsParse() throws Exception {
    setUp();
    Result plan = cli("plan", "--json", "--dialect", "postgresql");
    assertEquals(0, plan.code(), plan.all());
    Object parsedPlan = new Json.Parser(plan.out()).parse();
    assertTrue(parsedPlan.toString().contains("create_table"), plan.out());
    assertTrue(plan.out().contains("\"entity\":\"org.migrax.cli.fixture.SampleEntity\""),
        plan.out());
    var changed = json(cli("check", "--json"));
    assertEquals(false, changed.get("ok"), changed.toString());
    var operation =
        (java.util.Map<?, ?>) ((java.util.List<?>) changed.get("operations")).get(0);
    assertEquals("create_table", operation.get("kind"), changed.toString());
    assertEquals("sample_entity", operation.get("table"), changed.toString());
    assertEquals("org.migrax.cli.fixture.SampleEntity", operation.get("entity"),
        changed.toString());
    assertTrue(changed.get("changes").toString().contains("create table sample_entity"),
        changed.toString());

    generateAndMigrate();
    Result status = cli("status", "--json");
    assertEquals(0, status.code(), status.all());
    assertTrue(new Json.Parser(status.out()).parse().toString().contains("applied"),
        status.out());
    Result check = cli("check", "--json");
    assertTrue(new Json.Parser(check.out()).parse().toString().contains("ok=true"), check.out());
  }

  /** Parses a command's stdout, which must be exactly one JSON object. */
  @SuppressWarnings("unchecked")
  private static java.util.Map<String, Object> json(Result result) {
    Object parsed = new Json.Parser(result.out().strip()).parse();
    assertTrue(parsed instanceof java.util.Map, result.all());
    return (java.util.Map<String, Object>) parsed;
  }

  @Test
  void everyCommandHasJsonResults() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    var migrate = json(cli("migrate", "--json"));
    assertEquals(true, migrate.get("ok"), migrate.toString());
    assertTrue(migrate.get("schemas").toString().contains("applied=1"), migrate.toString());

    var doctor = json(cli("doctor", "--json"));
    assertTrue(doctor.get("checks").toString().contains("status=ok"), doctor.toString());

    var created = json(cli("new", "seed", "--json"));
    assertTrue(created.get("created").toString().contains("0002_seed.sql"), created.toString());
    Files.writeString(migrations().resolve("rollback/0001_initial.sql"),
        "DROP TABLE sample_entity;\n");
    assertEquals(0, cli("migrate").code());

    var rollback = json(cli("rollback", "--steps", "2", "--yes", "--json"));
    assertTrue(rollback.get("schemas").toString().contains("0002_seed.sql"), rollback.toString());

    var dryClean = json(cli("clean", "--dry-run", "--json"));
    assertTrue(dryClean.get("schemas").toString().contains("wouldDrop"), dryClean.toString());
    var clean = json(cli("clean", "--yes", "--json"));
    assertTrue(clean.get("schemas").toString().contains("MIGRAX_HISTORY"), clean.toString());
  }

  /**
   * The JPA API is on this classpath too, but the package has Spring Data JDBC aggregates and no
   * JPA entities: doctor says what the reader uses.
   */
  @Test
  void doctorNamesTheReaderSpringDataJdbcWhenItReadsTheAggregates() throws Exception {
    setUp();
    Result doctor = cli("doctor", "--package", "org.migrax.springdata.fixtures");
    assertTrue(doctor.out().contains("[ok]    Spring Data JDBC "), doctor.all());
    assertTrue(doctor.out().contains("Spring Data JDBC's own mapping"), doctor.all());
    assertTrue(doctor.out().contains("table(s) under org.migrax.springdata.fixtures (Spring "
        + "Data JDBC"), doctor.all());
    assertFalse(doctor.out().contains("JPA API"), doctor.all());
    assertFalse(doctor.out().contains("Hibernate not found"), doctor.all());
  }

  @Test
  void doctorNamesTheReaderMicronautDataWhenItReadsTheEntities() throws Exception {
    setUp();
    Result doctor = cli("doctor", "--package", "org.migrax.micronautdata.fixtures");
    assertTrue(doctor.out().contains("[ok]    Micronaut Data "), doctor.all());
    assertTrue(doctor.out().contains("table(s) under org.migrax.micronautdata.fixtures (Micronaut "
        + "Data"), doctor.all());
    assertFalse(doctor.out().contains("JPA API"), doctor.all());
  }

  /** Hand-written migrations in a project without Java, as in the container image. */
  private Result doctorWithoutBuildFile(boolean withMigrations, String... extra) throws Exception {
    Path folder = project.resolve("db/migrations");
    Files.createDirectories(folder);
    if (withMigrations) {
      Files.writeString(folder.resolve("0001_create_customer.sql"),
          "CREATE TABLE customer (id INT PRIMARY KEY);\n");
    }
    String[] args = Stream.concat(Stream.of("doctor", "--locations", "filesystem:db/migrations",
            "--url", "jdbc:h2:mem:commands_" + (++databases) + ";DB_CLOSE_DELAY=-1"),
        Stream.of(extra)).toArray(String[]::new);
    return cli(args);
  }

  @Test
  void doctorChecksOnlyMigrationsAndDatabaseWithoutJava() throws Exception {
    Result doctor = doctorWithoutBuildFile(true);
    assertEquals(0, doctor.code(), doctor.all());
    assertTrue(doctor.out().contains(
        "[info]  No pom.xml, build.gradle or entity package: checking migrations and the "
            + "database only"), doctor.all());
    assertTrue(doctor.out().contains("[ok]    Migrations: 1 file(s) in db/migrations"),
        doctor.all());
    assertTrue(doctor.out().contains("[ok]    Connected: H2"), doctor.all());
    assertTrue(doctor.out().contains("All checks passed"), doctor.all());
    for (String skipped : new String[] {"Build tool", "Entity package", "Framework", "Naming",
        "Project compiled", "JPA", "Hibernate", "Snapshot"}) {
      assertFalse(doctor.out().contains(skipped), skipped + " in " + doctor.all());
    }
  }

  @Test
  void doctorInTheWrongFolderStillFailsTheJavaChecks() throws Exception {
    // A Java project opened one folder too high: no build file and no migrations here.
    Result doctor = doctorWithoutBuildFile(false);
    assertEquals(1, doctor.code(), doctor.all());
    assertTrue(doctor.out().contains("[FAIL]  Build tool: none - not found"), doctor.all());
    assertTrue(doctor.out().contains("Run inside the service folder"), doctor.all());
    assertFalse(doctor.out().contains("checking migrations and the database only"),
        doctor.all());
  }

  @Test
  void doctorKeepsTheJavaChecksWhenAnEntityPackageIsSet() throws Exception {
    Result doctor = doctorWithoutBuildFile(true, "--package", "com.example");
    assertTrue(doctor.out().contains("[ok]    Entity package: com.example"), doctor.all());
    assertTrue(doctor.out().contains("Build tool: none - not found"), doctor.all());
    assertFalse(doctor.out().contains("checking migrations and the database only"),
        doctor.all());
  }

  @Test
  void doctorKeepsTheJavaChecksInAJavaProject() throws Exception {
    setUp();
    Files.createDirectories(migrations());
    Files.writeString(migrations().resolve("0001_create_customer.sql"),
        "CREATE TABLE customer (id INT PRIMARY KEY);\n");
    Result doctor = cli("doctor");
    assertTrue(doctor.out().contains("[ok]    Build tool: maven"), doctor.all());
    assertTrue(doctor.out().contains("[ok]    Entity package: org.migrax.cli.fixture"),
        doctor.all());
    assertFalse(doctor.out().contains("checking migrations and the database only"),
        doctor.all());
  }

  @Test
  void migrateToStopsAtTheNamedMigration() throws Exception {
    setUp();
    Files.createDirectories(migrations());
    Files.writeString(migrations().resolve("0001_a.sql"), "CREATE TABLE a (id INT);\n");
    Files.writeString(migrations().resolve("0002_b.sql"), "CREATE TABLE b (id INT);\n");
    Files.writeString(migrations().resolve("0003_c.sql"), "CREATE TABLE c (id INT);\n");
    Files.writeString(migrations().resolve("R__v.sql"),
        "CREATE OR REPLACE VIEW v AS SELECT id FROM c;\n");

    Result dry = cli("migrate", "--to", "2", "--dry-run");
    assertEquals(0, dry.code(), dry.all());
    assertTrue(dry.out().replace("\r\n", "\n")
        .contains("2 migration(s) would be applied:\n  0001_a.sql\n  0002_b.sql\n"), dry.all());

    Result first = cli("migrate", "--to", "0002");
    assertEquals(0, first.code(), first.all());
    assertTrue(first.out().contains("Applied 2 migration(s) up to 0002_b.sql."), first.all());
    assertTrue(first.out().contains("2 migration(s) after it stay pending."), first.all());
    assertEquals("0", query("SELECT COUNT(*) FROM migrax_history WHERE version IN "
        + "('0003_c.sql', 'R__v.sql')"));

    Result again = cli("migrate", "--to", "0002_b.sql");
    assertTrue(again.out().contains("Nothing to migrate up to 0002_b.sql."), again.all());

    Result unknown = cli("migrate", "--to", "0009");
    assertEquals(1, unknown.code(), unknown.all());
    assertTrue(unknown.all().contains("No migration '0009' in src/main/resources/db/migration."),
        unknown.all());

    Result rest = cli("migrate");
    assertTrue(rest.out().contains("Applied 2 migration(s); 3 total"), rest.all());
  }

  @Test
  void baselineRecordsExistingMigrationsWithoutRunningThem() throws Exception {
    setUp();
    Files.createDirectories(migrations());
    Files.writeString(migrations().resolve("0001_a.sql"), "CREATE TABLE a (id INT);\n");
    Files.writeString(migrations().resolve("0002_b.sql"), "CREATE TABLE b (id INT);\n");
    Files.writeString(migrations().resolve("0003_c.sql"), "CREATE TABLE c (id INT);\n");
    // The database was built before Migrax: it already has a and b.
    execute("CREATE TABLE a (id INT)");
    execute("CREATE TABLE b (id INT)");

    Result unconfirmed = cli("baseline", "0002");
    assertEquals(1, unconfirmed.code(), unconfirmed.all());
    assertTrue(unconfirmed.all().contains("needs confirmation"), unconfirmed.all());

    Result dry = cli("baseline", "0002", "--dry-run");
    assertEquals(0, dry.code(), dry.all());
    assertTrue(dry.out().contains("Would record 2 migration(s)"), dry.all());

    Result baseline = cli("baseline", "0002", "--yes");
    assertEquals(0, baseline.code(), baseline.all());
    assertTrue(baseline.out().contains("  0001_a.sql\n") || baseline.out().contains(
        "  0001_a.sql\r\n"), baseline.all());
    assertTrue(baseline.out().contains("Run 'migrax migrate' to apply the migrations after "
        + "0002_b.sql."), baseline.all());

    Result migrate = cli("migrate");
    assertEquals(0, migrate.code(), migrate.all());
    assertTrue(migrate.out().contains("Applied 1 migration(s)"), migrate.all());
    assertEquals("3", query("SELECT COUNT(*) FROM migrax_history"));

    Result again = cli("baseline", "0002", "--yes");
    assertEquals(1, again.code(), again.all());
    assertTrue(again.all().contains("already has migration history"), again.all());
  }

  @Test
  void migrationsAreReadFromSeveralFolders() throws Exception {
    setUp();
    Path dev = project.resolve("db/dev-data");
    Files.createDirectories(migrations());
    Files.createDirectories(dev.resolve("rollback"));
    Files.writeString(migrations().resolve("0001_a.sql"), "CREATE TABLE a (id INT);\n");
    Files.writeString(dev.resolve("0002_seed.sql"), "INSERT INTO a VALUES (1);\n");
    Files.writeString(dev.resolve("rollback/0002_seed.sql"), "DELETE FROM a;\n");
    String locations = "classpath:db/migration,filesystem:db/dev-data";

    Result migrate = cli("migrate", "--locations", locations);
    assertEquals(0, migrate.code(), migrate.all());
    assertTrue(migrate.out().contains(
        "Applied 2 migration(s); 2 total in src/main/resources/db/migration, db/dev-data."),
        migrate.all());
    assertEquals("1", query("SELECT COUNT(*) FROM a"));

    Result status = cli("status", "--locations", locations);
    assertTrue(status.out().startsWith(
        "Migrations in src/main/resources/db/migration, db/dev-data for "), status.all());

    Result rollback = cli("rollback", "--yes", "--locations", locations);
    assertEquals(0, rollback.code(), rollback.all());
    assertEquals("0", query("SELECT COUNT(*) FROM a"));

    Result created = cli("new", "next", "--locations", locations);
    assertTrue(created.out().contains("src/main/resources/db/migration/0003_next.sql"),
        created.all());

    Files.writeString(dev.resolve("0001_a.sql"), "CREATE TABLE a2 (id INT);\n");
    Result duplicate = cli("status", "--locations", locations);
    assertEquals(1, duplicate.code(), duplicate.all());
    assertTrue(duplicate.all().contains("Two migration folders contain 0001_a.sql"),
        duplicate.all());
  }

  @Test
  void environmentMigrationsRunOnlyInTheirEnvironment() throws Exception {
    setUp();
    Files.createDirectories(migrations());
    Files.writeString(migrations().resolve("0001_a.sql"), "CREATE TABLE a (id INT);\n");
    Files.writeString(migrations().resolve("0002_seed.sql"),
        "-- migrax:env dev\nINSERT INTO a VALUES (1);\n");

    Result migrate = cli("migrate");
    assertEquals(0, migrate.code(), migrate.all());
    assertEquals("0", query("SELECT COUNT(*) FROM a"));
    Result status = cli("status");
    assertEquals(0, status.code(), status.all());
    assertTrue(status.out().contains("[-] 0002_seed.sql  only for dev"), status.all());
    assertTrue(status.out().contains("1 applied, 1 skipped."), status.all());

    Result dev = cli("migrate", "--env", "dev");
    assertEquals(0, dev.code(), dev.all());
    assertTrue(dev.out().contains("Applied 1 migration(s)"), dev.all());
    assertEquals("1", query("SELECT COUNT(*) FROM a"));
  }

  @Test
  void errorsHaveStableCodesInTextAndJson() throws Exception {
    setUp();
    Result unknown = cli("migrate", "--colour");
    assertTrue(unknown.err().startsWith("error[MXE001]: Unknown option '--colour'."),
        unknown.err());

    generateAndMigrate();
    Path initial = migrations().resolve("0001_initial.sql");
    Files.writeString(initial, Files.readString(initial) + "\n-- edited\n");
    Result changed = cli("migrate", "--json");
    assertEquals(1, changed.code());
    assertTrue(changed.err().startsWith("error[MXE104]:"), changed.err());
    var error = json(changed);
    assertEquals(false, error.get("ok"));
    assertTrue(error.get("error").toString().contains("code=MXE104"), error.toString());

    Files.delete(initial);
    assertTrue(cli("migrate").err().startsWith("error[MXE105]:"));
  }

  @Test
  void planShowsImpact() throws Exception {
    setUp();
    generateAndMigrate();
    execute("INSERT INTO sample_entity (name) VALUES ('a'), ('b')");
    Path snapshot = project.resolve(".migrax/snapshot.json");
    // Pretend the entity gained a unique constraint on name.
    Files.writeString(snapshot, Files.readString(snapshot).replace("\"unique\":false",
        "\"unique\":true"));
    Result plan = cli("plan", "--impact");
    assertEquals(0, plan.code(), plan.all());
    assertTrue(plan.out().contains("impact: sample_entity ~2 rows"), plan.out());
  }

  @Test
  void multipleSchemasAreMigratedSeparately() throws Exception {
    setUp();
    assertEquals(0, cli("generate").code());
    execute("CREATE SCHEMA TENANT_A");
    execute("CREATE SCHEMA TENANT_B");
    Result migrate = cli("migrate", "--schemas", "TENANT_A,TENANT_B");
    assertEquals(0, migrate.code(), migrate.all());
    assertEquals("0", query("SELECT COUNT(*) FROM TENANT_A.SAMPLE_ENTITY"));
    assertEquals("0", query("SELECT COUNT(*) FROM TENANT_B.SAMPLE_ENTITY"));
    try (Stream<String> lines = cli("status", "--schemas", "TENANT_A,TENANT_B").out().lines()) {
      assertEquals(2, lines.filter(l -> l.contains("[X] 0001_initial.sql")).count());
    }
  }

  /** Adds a NOT NULL column the entities don't have to the snapshot and the database. */
  private void addLegacyColumn() throws Exception {
    Path snapshot = project.resolve(".migrax/snapshot.json");
    org.migrax.model.SchemaModel model = org.migrax.diff.SnapshotStore.load(snapshot);
    org.migrax.model.SchemaModel.Table table = model.tables().get(0);
    java.util.List<org.migrax.model.SchemaModel.Column> columns =
        new java.util.ArrayList<>(table.columns());
    columns.add(new org.migrax.model.SchemaModel.Column("legacy", "varchar", false, 20, null,
        null, null, false, false, null, "varchar"));
    org.migrax.diff.SnapshotStore.save(snapshot, new org.migrax.model.SchemaModel(
        java.util.List.of(new org.migrax.model.SchemaModel.Table(table.name(), columns,
            table.primaryKey(), table.indexes(), table.foreignKeys())), model.sequences()),
        "h2", org.migrax.diff.SnapshotStore.naming(snapshot));
    execute("ALTER TABLE sample_entity ADD COLUMN legacy VARCHAR(20) DEFAULT 'x' NOT NULL");
    execute("ALTER TABLE sample_entity ALTER COLUMN legacy DROP DEFAULT");
  }

  @Test
  void zeroDowntimeKeepsTheContractStepForTheNextRelease() throws Exception {
    setUp();
    generateAndMigrate();
    addLegacyColumn();

    Result expand = cli("generate", "--zero-downtime", "--allow-destructive");
    assertEquals(0, expand.code(), expand.all());
    assertTrue(expand.out().replace("\r\n", "\n").contains("0002_remove_sample_entity_legacy_expand.sql (expand: "
        + "safe while the old version runs):\n  - alter column sample_entity.legacy\n"),
        expand.out().replace("\r\n", "\n"));
    assertTrue(expand.out().replace("\r\n", "\n").contains("Waiting in .migrax/pending/"
        + "0002_remove_sample_entity_legacy_contract.sql (contract: for the next release):\n"
        + "  - drop column sample_entity.legacy\n"), expand.out().replace("\r\n", "\n"));
    Path pending = project.resolve(".migrax/pending");
    assertTrue(Files.exists(pending.resolve("0002_remove_sample_entity_legacy_contract.sql")));
    assertTrue(Files.exists(pending.resolve("rollback/"
        + "0002_remove_sample_entity_legacy_contract.sql")));
    assertTrue(Files.readString(migrations().resolve(
        "0002_remove_sample_entity_legacy_expand.sql")).contains(
        "ALTER TABLE sample_entity ALTER COLUMN legacy SET NULL"), expand.out().replace("\r\n", "\n"));

    Result migrate = cli("migrate");
    assertEquals(0, migrate.code(), migrate.all());
    // The new version doesn't write legacy anymore.
    execute("INSERT INTO sample_entity (name) VALUES ('new version')");

    Result nothing = cli("generate");
    assertEquals(0, nothing.code(), nothing.all());
    assertTrue(nothing.out().replace("\r\n", "\n").contains("No changes detected.\n1 contract migration(s) wait in "
        + ".migrax/pending: add them to the release after their expand migration with "
        + "'migrax generate --contract'."), nothing.out().replace("\r\n", "\n"));

    Result contract = cli("generate", "--contract");
    assertEquals(0, contract.code(), contract.all());
    assertTrue(contract.out().replace("\r\n", "\n").startsWith("Created src/main/resources/db/migration/"
        + "0003_remove_sample_entity_legacy_contract.sql.\n"), contract.out().replace("\r\n", "\n"));
    assertFalse(Files.exists(pending));
    assertTrue(Files.readString(migrations().resolve(
        "rollback/0003_remove_sample_entity_legacy_contract.sql")).startsWith(
        "-- Generated by Migrax"));
    assertTrue(Files.readString(migrations().resolve(
        "rollback/0003_remove_sample_entity_legacy_contract.sql")).contains(
        "-- Rollback for 0003_remove_sample_entity_legacy_contract.sql."));
    assertTrue(Files.exists(project.resolve(
        ".migrax/history/0003_remove_sample_entity_legacy_contract.json")));
    Result applied = cli("migrate");
    assertEquals(0, applied.code(), applied.all());
    assertEquals("0", query("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE "
        + "COLUMN_NAME = 'LEGACY'"));
    assertEquals("No contract migrations are waiting.",
        cli("generate", "--contract").out().strip());
  }

  @Test
  void zeroDowntimeRefusesWhatTheDatabaseCantDo() throws Exception {
    setUp();
    generateAndMigrate();
    renameInSnapshotAndDatabase();
    Result rename = cli("generate", "--zero-downtime", "--rename", "sample_entity.old_name=name");
    assertEquals(1, rename.code(), rename.all());
    assertTrue(rename.err().contains("Renaming sample_entity.old_name needs a trigger that keeps "
        + "old_name and name equal while the old version runs, which Migrax can't write for "
        + "h2."), rename.all());
    Result combined = cli("generate", "--zero-downtime", "--keep-data");
    assertEquals(1, combined.code(), combined.all());
    assertTrue(combined.err().contains("--zero-downtime can't be combined with --keep-data."),
        combined.all());
  }

  @Test
  void reportWritesOnePageWithEverything() throws Exception {
    setUp();
    generateAndMigrate();
    Path page = project.resolve("out/report.html");
    Result report = cli("report", "--html", page.toString());
    assertEquals(0, report.code(), report.all());
    assertTrue(report.out().contains("1 table(s), 1 migration(s), 0 lint finding(s), "
        + "no drift."), report.out());
    String html = Files.readString(page);
    assertTrue(html.contains("<code>0001_initial.sql</code></td><td><span class=\"badge "
        + "applied\">applied</span>"), html);
    assertTrue(html.contains("No drift: the database matches the snapshot."), html);
    assertTrue(html.contains("erDiagram\n  sample_entity {"), html);

    // Without a database, the page still has the schema and the migrations.
    Files.writeString(project.resolve("src/main/resources/application.properties"), "");
    Result offline = cli("report", "--html", page.toString());
    assertEquals(0, offline.code(), offline.all());
    html = Files.readString(page);
    assertTrue(html.contains("No database URL is configured"), html);
    assertTrue(html.contains("<span class=\"badge unknown\">not checked</span>"), html);
  }
}
