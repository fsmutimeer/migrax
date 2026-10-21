package org.migrax.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code migrax environments}: dev, staging and production side by side. */
class EnvironmentsCommandTest {
  private static int projects;

  @TempDir
  Path project;

  private String prefix;

  private record Result(int code, String out, String err) {
    String all() {
      return out + err;
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    prefix = "jdbc:h2:mem:environments_" + (++projects) + "_";
    Files.createDirectories(project.resolve("db/migrations"));
    migration("0001_initial.sql", "CREATE TABLE customer (id BIGINT PRIMARY KEY);\n");
    migration("0002_add_email.sql", "ALTER TABLE customer ADD COLUMN email VARCHAR(150);\n");
    config("");
  }

  private String url(String environment) {
    return prefix + environment + ";DB_CLOSE_DELAY=-1";
  }

  private void migration(String name, String sql) throws Exception {
    Files.writeString(project.resolve("db/migrations").resolve(name), sql);
  }

  /** The application config with dev, staging and production, plus {@code more}. */
  private void config(String more) throws Exception {
    Path resources = project.resolve("src/main/resources");
    Files.createDirectories(resources);
    Files.writeString(resources.resolve("application.properties"), String.join("\n",
        "migrax.locations=filesystem:db/migrations",
        "migrax.environments.dev.url=" + url("dev"),
        "migrax.environments.staging.url=" + url("staging"),
        "migrax.environments.production.url=" + url("production"),
        more, ""));
  }

  private Result run(String... args) {
    List<String> full = new ArrayList<>(List.of(args));
    full.addAll(List.of("--dir", project.toString(), "--no-build"));
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = Main.run(full.toArray(String[]::new),
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(code, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  private void migrate(String environment, String... more) {
    List<String> args = new ArrayList<>(List.of("migrate", "--url", url(environment)));
    args.addAll(List.of(more));
    Result result = run(args.toArray(String[]::new));
    assertEquals(0, result.code(), result.all());
  }

  private void execute(String environment, String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(url(environment));
         var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  /** The table line of one environment. */
  private static String row(Result result, String environment) {
    return result.out().lines().filter(line -> line.startsWith("  " + environment + " "))
        .findFirst().orElseThrow(() -> new AssertionError(environment + ": " + result.all()));
  }

  @Test
  void showsWhatEachEnvironmentHasApplied() {
    migrate("dev");
    migrate("staging");
    migrate("production", "--to", "0001_initial");

    Result result = run("environments", "--environments", "dev,staging,production");
    assertEquals(0, result.code(), "pending migrations alone are no error: " + result.all());
    assertTrue(result.out().startsWith("Environments for db/migrations (2 migration(s)):"),
        result.out());
    assertTrue(row(result, "dev").matches(" +dev +2 +0 +0002_add_email\\.sql, \\d{4}-.*"
        + "no snapshot"), row(result, "dev"));
    assertTrue(row(result, "production").matches(" +production +1 +1 +0001_initial\\.sql.*"),
        row(result, "production"));
    assertTrue(result.out().contains("production: pending 0002_add_email.sql"), result.out());
    List<String> order = result.out().lines().filter(l -> l.matches("  [a-z]+ .*"))
        .map(l -> l.trim().split(" ")[0]).toList();
    assertEquals(List.of("dev", "staging", "production"), order, "the order given");

    Result alphabetical = run("environments");
    assertEquals(List.of("dev", "production", "staging"), alphabetical.out().lines()
        .filter(l -> l.matches("  [a-z]+ .*")).map(l -> l.trim().split(" ")[0]).toList());
  }

  @Test
  void migrationsForOtherEnvironmentsAreNotPending() throws Exception {
    migration("0003_test_data.sql",
        "-- migrax:env dev\nINSERT INTO customer (id) VALUES (1);\n");
    migrate("dev", "--env", "dev");
    migrate("staging", "--env", "staging");
    migrate("production");

    Result result = run("environments");
    assertEquals(0, result.code(), result.all());
    assertTrue(row(result, "dev").matches(" +dev +3 +0 .*"), row(result, "dev"));
    assertTrue(row(result, "staging").matches(" +staging +2 +0 .*"), row(result, "staging"));
    assertFalse(result.out().contains("pending 0003"), result.out());

    // An environment whose -- migrax:env name differs from its own.
    config("migrax.environments.staging.env=dev");
    Result renamed = run("environments");
    assertTrue(row(renamed, "staging").matches(" +staging +2 +1 .*"), row(renamed, "staging"));
  }

  @Test
  void findsDriftWhereNothingIsPending() throws Exception {
    Files.writeString(project.resolve("db/schema.sql"),
        "CREATE TABLE customer (id BIGINT PRIMARY KEY, email VARCHAR(150));\n");
    Files.delete(project.resolve("db/migrations/0001_initial.sql"));
    Files.delete(project.resolve("db/migrations/0002_add_email.sql"));
    Result generated = run("generate", "--url", url("dev"), "--schema-file", "db/schema.sql");
    assertEquals(0, generated.code(), generated.all());
    migrate("dev");
    migrate("staging");
    execute("staging", "ALTER TABLE customer ADD COLUMN note VARCHAR(20)");

    Result result = run("environments", "--environments", "dev,staging,production");
    assertEquals(2, result.code(), result.all());
    assertTrue(row(result, "dev").endsWith("none"), row(result, "dev"));
    assertTrue(row(result, "staging").endsWith("1 difference(s)"), row(result, "staging"));
    assertTrue(result.out().contains("staging: drift: "), result.out());
    assertTrue(row(result, "production").endsWith("not checked (pending)"),
        row(result, "production"));

    Result skipped = run("environments", "--no-drift");
    assertEquals(0, skipped.code(), skipped.all());
    assertTrue(row(skipped, "staging").endsWith("not checked"), row(skipped, "staging"));
  }

  @Test
  void reportsMigrationsAnEnvironmentHasButTheFolderNot() throws Exception {
    migrate("dev");
    migrate("staging");
    migrate("production");
    Files.delete(project.resolve("db/migrations/0002_add_email.sql"));

    Result result = run("environments");
    assertEquals(1, result.code(), result.all());
    assertTrue(result.out().contains("staging: [!] 0002_add_email.sql MISSING"), result.out());
    assertTrue(result.err().contains("migrax help repair"), result.err());
  }

  @Test
  void anEnvironmentThatCantBeReachedHasItsOwnRow() throws Exception {
    migrate("dev");
    config("migrax.environments.qa.url=jdbc:h2:tcp://localhost:1/qa");

    Result result = run("environments");
    assertEquals(1, result.code(), result.all());
    assertTrue(row(result, "qa").matches(" +qa +can't connect: .+"), row(result, "qa"));
    assertTrue(row(result, "dev").matches(" +dev +2 +0 .*"), "the others are still shown: "
        + result.out());
  }

  @Test
  void jsonForDashboards() {
    migrate("dev");
    Result result = run("environments", "--environments", "dev,production", "--json");
    assertEquals(0, result.code(), result.all());
    String json = result.out();
    assertTrue(json.contains("{\"name\":\"dev\","), json);
    assertTrue(json.contains("\"applied\":2,\"pending\":[],"), json);
    assertTrue(json.contains("\"lastApplied\":{\"version\":\"0002_add_email.sql\","), json);
    assertTrue(json.contains("\"pending\":[\"0001_initial.sql\",\"0002_add_email.sql\"]"),
        json);
    assertTrue(json.contains("\"reachable\":true"), json);
    assertFalse(json.contains("staging"), "only the chosen ones: " + json);
  }

  @Test
  void environmentsFromSystemPropertiesAndUnknownNames() throws Exception {
    config("");
    System.setProperty("migrax.environments.preview.url", url("preview"));
    try {
      Result result = run("environments");
      assertTrue(row(result, "preview").matches(" +preview +0 +2 +none .*"),
          row(result, "preview"));
    } finally {
      System.clearProperty("migrax.environments.preview.url");
    }
    Result unknown = run("environments", "--environments", "prod");
    assertEquals(1, unknown.code(), unknown.all());
    assertTrue(unknown.err().contains("No URL found for environment 'prod'"), unknown.err());
    assertTrue(unknown.err().contains("Configured: dev, production, staging."), unknown.err());
  }

  @Test
  void aFurtherDatabaseHasItsOwnEnvironments() throws Exception {
    Files.createDirectories(project.resolve("db/billing"));
    Files.writeString(project.resolve("db/billing/0001_invoice.sql"),
        "CREATE TABLE invoice (id BIGINT PRIMARY KEY);\n");
    config(String.join("\n",
        "migrax.databases.billing.url=" + url("billing_dev"),
        "migrax.databases.billing.locations=filesystem:db/billing",
        "migrax.databases.billing.environments.staging.url=" + url("billing_staging")));

    Result result = run("environments", "--database", "billing");
    assertEquals(0, result.code(), result.all());
    assertTrue(result.out().startsWith("Environments for db/billing (1 migration(s)):"),
        result.out());
    assertTrue(row(result, "staging").matches(" +staging +0 +1 +none .*"),
        row(result, "staging"));
    assertFalse(result.out().contains("  dev "), "not the default database's: " + result.out());
  }

  @Test
  void usesEachEnvironmentsUserAndPasswordFile() throws Exception {
    String secured = prefix + "secured;DB_CLOSE_DELAY=-1";
    Result created = run("migrate", "--url", secured, "--user", "deployer", "--password", "s3cret");
    assertEquals(0, created.code(), created.all());
    Path secret = project.resolve("secret.txt");
    Files.writeString(secret, "s3cret\n");
    config(String.join("\n",
        "migrax.environments.secured.url=" + secured + ";PASSWORD_HASH=FALSE",
        "migrax.environments.secured.user=deployer",
        "migrax.environments.secured.password-file=" + secret.toString().replace('\\', '/')));

    Result result = run("environments", "--environments", "secured");
    assertEquals(0, result.code(), result.all());
    assertTrue(row(result, "secured").matches(" +secured +2 +0 .*"), row(result, "secured"));

    Files.writeString(secret, "wrong\n");
    Result refused = run("environments", "--environments", "secured");
    assertEquals(1, refused.code(), refused.all());
    assertTrue(row(refused, "secured").contains("can't connect: "), refused.out());

    Files.delete(secret);
    Result missing = run("environments", "--environments", "secured");
    assertEquals(1, missing.code(), missing.all());
    assertTrue(missing.err().contains("Could not read the file that "
        + "migrax.environments.secured.password-file names"), missing.err());
  }

  @Test
  void jsonHidesPasswordsInUrls() throws Exception {
    config("migrax.environments.qa.url=jdbc:h2:tcp://localhost:1/qa;PASSWORD=topsecret");
    Result result = run("environments", "--environments", "qa", "--json");
    assertEquals(1, result.code(), result.all());
    assertFalse(result.all().contains("topsecret"), result.all());
    assertTrue(result.out().contains("\"reachable\":false"), result.out());
  }

  @Test
  void repeatableMigrationsAreNotCounted() throws Exception {
    migration("R__customer_view.sql", "CREATE OR REPLACE VIEW customer_view AS "
        + "SELECT id FROM customer;\n");
    migrate("dev");
    Result result = run("environments", "--environments", "dev,staging");
    assertEquals(0, result.code(), result.all());
    assertTrue(row(result, "dev").matches(" +dev +2 +0 +0002_add_email\\.sql, .*"),
        row(result, "dev"));
    assertTrue(row(result, "staging").matches(" +staging +0 +2 .*"), row(result, "staging"));
    assertFalse(result.out().contains("R__"), result.out());
  }

  /** A database function that takes its time. */
  public static final class Slow {
    private Slow() {}

    public static void pause(int millis) throws InterruptedException {
      Thread.sleep(millis);
    }
  }

  @Test
  void aDatabaseThatDoesntAnswerInTimeGetsItsOwnRow() throws Exception {
    migrate("dev");
    // Connecting runs the INIT script, which waits two seconds. H2 needs "\;" between its
    // statements, which a properties file writes as "\\;".
    config("migrax.environments.slow.url=" + prefix + "slow;INIT=CREATE ALIAS IF NOT EXISTS "
        + "PAUSE FOR 'org.migrax.cli.EnvironmentsCommandTest$Slow.pause'\\\\;CALL PAUSE(2000)");
    long start = System.nanoTime();
    Result result = run("environments", "--environments", "dev,slow", "--timeout", "300ms");
    assertEquals(1, result.code(), result.all());
    assertTrue(row(result, "slow").matches(" +slow +can't connect: no answer within 300ms "
        + "\\(--timeout\\)"), row(result, "slow"));
    assertTrue(row(result, "dev").matches(" +dev +2 +0 .*"), result.out());
    assertTrue(System.nanoTime() - start < 1_900_000_000L, "didn't wait for the slow one");

    Result invalid = run("environments", "--timeout", "soon");
    assertEquals(1, invalid.code(), invalid.all());
    assertTrue(invalid.err().contains("Invalid timeout 'soon'."), invalid.err());
  }

  @Test
  void noEnvironmentsConfigured() throws Exception {
    Files.writeString(project.resolve("src/main/resources/application.properties"),
        "migrax.locations=filesystem:db/migrations\n");
    Result result = run("environments");
    assertEquals(1, result.code(), result.all());
    assertTrue(result.err().contains("MXE116"), result.err());
    assertTrue(result.err().contains("migrax.environments.staging.url"), result.err());
  }
}
