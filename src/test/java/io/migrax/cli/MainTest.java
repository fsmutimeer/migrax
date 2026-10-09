package io.migrax.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
  @TempDir
  Path project;

  /** Captured result of one CLI invocation. */
  private record Result(int code, String out, String err) {}

  private static Result cli(String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = Main.run(args,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(code, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  /** Runs a command against the temp project; --no-build because the fixture is not a real build. */
  private Result inProject(String... args) {
    String[] full = new String[args.length + 3];
    System.arraycopy(args, 0, full, 0, args.length);
    full[args.length] = "--dir";
    full[args.length + 1] = project.toString();
    full[args.length + 2] = "--no-build";
    return cli(full);
  }

  private String setUpProject(String database) throws Exception {
    Files.writeString(project.resolve("pom.xml"), """
        <project>
          <groupId>io.migrax.cli.fixture</groupId>
          <artifactId>sample</artifactId>
        </project>
        """);
    String url = "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1";
    Path resources = project.resolve("src/main/resources");
    Files.createDirectories(resources);
    Files.writeString(resources.resolve("application.properties"), """
        spring.datasource.url=%s
        migrax.locations=filesystem:sql/migrations
        """.formatted(url));
    return url;
  }

  @Test
  void generatesAndAppliesMigrationFromProjectConfiguration() throws Exception {
    String url = setUpProject("migrax_cli_test");

    Result generate = inProject("generate");
    assertEquals(0, generate.code(), generate.err());
    assertTrue(generate.out().contains("Created sql/migrations/0001_initial.sql"), generate.out());

    Result migrate = inProject("migrate");
    assertEquals(0, migrate.code(), migrate.err());
    assertTrue(migrate.out().contains("Applied 1 migration(s)"), migrate.out());

    try (var connection = DriverManager.getConnection(url);
         var statement = connection.createStatement();
         var result = statement.executeQuery("select count(*) from sample_entity")) {
      assertTrue(result.next());
    }

    Result again = inProject("migrate");
    assertEquals(0, again.code(), again.err());
    assertTrue(again.out().contains("Nothing to migrate"), again.out());

    Result noChanges = inProject("makemigrations");
    assertEquals(0, noChanges.code(), noChanges.err());
    assertTrue(noChanges.out().contains("No changes detected"), noChanges.out());
  }

  @Test
  void statusAndDryRunReportPendingWithoutChangingTheDatabase() throws Exception {
    setUpProject("migrax_cli_status");
    assertEquals(0, inProject("generate").code());

    Result dryRun = inProject("migrate", "--dry-run");
    assertEquals(0, dryRun.code(), dryRun.err());
    assertTrue(dryRun.out().contains("1 migration(s) would be applied"), dryRun.out());

    Result pending = inProject("status");
    assertEquals(0, pending.code(), pending.err());
    assertTrue(pending.out().contains("[ ] 0001_initial.sql"), pending.out());

    assertEquals(0, inProject("migrate").code());
    Result applied = inProject("showmigrations");
    assertEquals(0, applied.code(), applied.err());
    assertTrue(applied.out().contains("[X] 0001_initial.sql"), applied.out());
    assertTrue(applied.out().contains("1 applied"), applied.out());
  }

  @Test
  void checkExitsWithTwoWhenEntitiesHaveNoMigration() throws Exception {
    setUpProject("migrax_cli_check");
    Result changed = inProject("check");
    assertEquals(ExitCode.CHANGES_DETECTED, changed.code(), changed.out() + changed.err());
    assertTrue(changed.err().contains("migrax generate"), changed.err());

    assertEquals(0, inProject("generate").code());
    Result clean = inProject("check");
    assertEquals(0, clean.code(), clean.err());
  }

  @Test
  void generateWithSnapshotNeedsOnlyADialect() throws Exception {
    setUpProject("migrax_cli_dialect");
    assertEquals(0, inProject("generate").code());
    Files.writeString(project.resolve("src/main/resources/application.properties"),
        "migrax.locations=filesystem:sql/migrations\n");

    Result noDialect = inProject("plan");
    assertEquals(0, noDialect.code(), noDialect.err());

    Result withDialect = inProject("generate", "--dialect", "postgresql");
    assertEquals(0, withDialect.code(), withDialect.err());
    assertTrue(withDialect.out().contains("No changes detected"), withDialect.out());
  }

  @Test
  void helpAndVersionWork() {
    Result help = cli("help");
    assertEquals(0, help.code());
    assertTrue(help.out().contains("generate"));
    assertTrue(help.out().contains("migrate"));

    Result noArgs = cli();
    assertEquals(0, noArgs.code());
    assertTrue(noArgs.out().contains("Usage: migrax"));

    Result commandHelp = cli("help", "generate");
    assertEquals(0, commandHelp.code());
    assertTrue(commandHelp.out().contains("--allow-destructive"), commandHelp.out());

    Result flagHelp = cli("migrate", "--help");
    assertEquals(0, flagHelp.code());
    assertTrue(flagHelp.out().contains("--dry-run"), flagHelp.out());

    Result version = cli("--version");
    assertEquals(0, version.code());
    assertTrue(version.out().startsWith("migrax "), version.out());
  }

  @Test
  void unknownCommandsAndOptionsGiveSuggestionsWithoutStackTraces() {
    Result command = cli("genrate");
    assertEquals(1, command.code());
    assertTrue(command.err().contains("Did you mean 'migrax generate'"), command.err());
    assertFalse(command.err().contains("\tat "), command.err());

    Result option = cli("migrate", "--dryrun");
    assertEquals(1, option.code());
    assertTrue(option.err().contains("Did you mean '--dry-run'"), option.err());

    Result missingValue = cli("generate", "--name");
    assertEquals(1, missingValue.code());
    assertTrue(missingValue.err().contains("needs a value"), missingValue.err());
  }

  @Test
  void missingDatabaseUrlIsExplained() throws Exception {
    Files.writeString(project.resolve("pom.xml"),
        "<project><groupId>io.migrax.cli.fixture</groupId></project>");
    Result migrate = inProject("migrate");
    assertEquals(1, migrate.code());
    assertTrue(migrate.err().contains("No database URL found"), migrate.err());
    // No framework in the build: the plain JPA setting is suggested.
    assertTrue(migrate.err().contains("jakarta.persistence.jdbc.url"), migrate.err());
    assertFalse(migrate.err().contains("\tat "), migrate.err());

    Files.writeString(project.resolve("pom.xml"), "<project><groupId>io.migrax.cli.fixture"
        + "</groupId><artifactId>org.springframework.boot</artifactId></project>");
    Result spring = inProject("migrate");
    assertTrue(spring.err().contains("spring.datasource.url"), spring.err());
  }

  @Test
  void repairRequiresActionAndConfirmation() throws Exception {
    setUpProject("migrax_cli_repair");
    Result noAction = inProject("repair", "0001_initial.sql");
    assertEquals(1, noAction.code());
    assertTrue(noAction.err().contains("--action"), noAction.err());

    Result noConfirm = inProject("repair", "0001_initial.sql", "--action", "retry");
    assertEquals(1, noConfirm.code());
    assertTrue(noConfirm.err().contains("--yes"), noConfirm.err());
  }

  @Test
  void initCreatesFoldersAndReportsDetectedSettings() throws Exception {
    setUpProject("migrax_cli_init");
    Result init = inProject("init");
    assertEquals(0, init.code(), init.err());
    assertTrue(Files.isDirectory(project.resolve("sql/migrations")));
    assertTrue(Files.isRegularFile(project.resolve(".migrax/.gitignore")));
    assertTrue(init.out().contains("io.migrax.cli.fixture"), init.out());
    assertTrue(init.out().contains("h2"), init.out());
  }

  @Test
  void relativeH2PathsResolveFromTheProjectRoot() {
    Path root = Path.of("/work/shop").toAbsolutePath();
    String expected = root.resolve("data/shopdb").normalize().toString().replace('\\', '/');
    assertEquals("jdbc:h2:file:" + expected,
        Project.resolveRelativeDatabasePath("jdbc:h2:file:./data/shopdb", root));
    assertEquals("jdbc:h2:" + expected + ";MODE=PostgreSQL",
        Project.resolveRelativeDatabasePath("jdbc:h2:./data/shopdb;MODE=PostgreSQL", root));
    assertEquals("jdbc:h2:mem:test", Project.resolveRelativeDatabasePath("jdbc:h2:mem:test", root));
    assertEquals("jdbc:postgresql://db/app",
        Project.resolveRelativeDatabasePath("jdbc:postgresql://db/app", root));
  }

  @Test
  void migrationNamesDescribeTheChange() {
    var column = new io.migrax.model.SchemaModel.Column(
        "phone", "varchar", true, null, null, null, null, false, false, null, "varchar");
    assertEquals("add_customer_phone", io.migrax.diff.Migrations.description(
        java.util.List.of(new io.migrax.ops.AddColumn("customer", column))));
    assertEquals("remove_customer_email_add_customer_phone", io.migrax.diff.Migrations.description(
        java.util.List.of(new io.migrax.ops.DropColumn("customer", "email"),
            new io.migrax.ops.AddColumn("customer", column))));
    assertEquals("drop_a_and_more", io.migrax.diff.Migrations.description(java.util.List.of(
        new io.migrax.ops.DropTable("a"), new io.migrax.ops.DropTable("b"),
        new io.migrax.ops.DropTable("c"))));
  }
}
