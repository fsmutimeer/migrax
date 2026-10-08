package io.migrax.cli;

import io.migrax.util.Json;
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
        "<project><groupId>io.migrax.cli.fixture</groupId></project>");
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
  }

  @Test
  void driftReportsManualChanges() throws Exception {
    setUp();
    generateAndMigrate();
    assertEquals(0, cli("drift").code());
    execute("ALTER TABLE sample_entity ADD COLUMN hotfix VARCHAR(10)");
    Result drift = cli("drift");
    assertEquals(Main.CHANGES_DETECTED, drift.code(), drift.all());
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
  void jsonOutputsParse() throws Exception {
    setUp();
    Result plan = cli("plan", "--json", "--dialect", "postgresql");
    assertEquals(0, plan.code(), plan.all());
    Object parsedPlan = new Json.Parser(plan.out()).parse();
    assertTrue(parsedPlan.toString().contains("create_table"), plan.out());

    generateAndMigrate();
    Result status = cli("status", "--json");
    assertEquals(0, status.code(), status.all());
    assertTrue(new Json.Parser(status.out()).parse().toString().contains("applied"),
        status.out());
    Result check = cli("check", "--json");
    assertTrue(new Json.Parser(check.out()).parse().toString().contains("ok=true"), check.out());
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
}
