package org.migrax.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code --database}: the CLI works on one of the databases in migrax.databases.*. */
class DatabaseOptionTest {
  private static int projects;

  @TempDir
  Path project;

  private String main;
  private String billing;

  private record Result(int code, String out, String err) {
    String all() {
      return out + err;
    }
  }

  private Result cli(String... args) {
    String[] full = new String[args.length + 3];
    System.arraycopy(args, 0, full, 0, args.length);
    full[args.length] = "--dir";
    full[args.length + 1] = project.toString();
    full[args.length + 2] = "--no-build";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = Main.run(full, new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(code, out.toString(StandardCharsets.UTF_8),
        err.toString(StandardCharsets.UTF_8));
  }

  private void setUp(String extra) throws Exception {
    Files.writeString(project.resolve("pom.xml"),
        "<project><groupId>org.migrax.cli.multidb</groupId></project>");
    int n = ++projects;
    main = "jdbc:h2:mem:option_main_" + n + ";DB_CLOSE_DELAY=-1";
    billing = "jdbc:h2:mem:option_billing_" + n + ";DB_CLOSE_DELAY=-1";
    Path resources = project.resolve("src/main/resources");
    Files.createDirectories(resources);
    Files.writeString(resources.resolve("application.properties"), """
        spring.datasource.url=%s
        migrax.placeholders.owner=main_app
        migrax.databases.billing.url=%s
        migrax.databases.billing.package=org.migrax.cli.multidb.billing
        migrax.databases.billing.placeholders.owner=billing_app
        %s
        """.formatted(main, billing, extra));
  }

  private static String query(String url, String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(url);
         var statement = connection.createStatement();
         var result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : null;
    }
  }

  private static String tables(String url) throws Exception {
    return query(url, "SELECT LISTAGG(LOWER(TABLE_NAME), ',') WITHIN GROUP (ORDER BY TABLE_NAME)"
        + " FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'"
        + " AND LOWER(TABLE_NAME) NOT IN ('migrax_history', 'migrax_failures')");
  }

  private static String sqlIn(Path folder) throws Exception {
    try (Stream<Path> files = Files.list(folder)) {
      return files.filter(file -> file.toString().endsWith(".sql")).map(file -> {
        try {
          return Files.readString(file);
        } catch (Exception e) {
          throw new IllegalStateException(e);
        }
      }).reduce("", String::concat);
    }
  }

  @Test
  void eachDatabaseHasItsOwnEntitiesFoldersSnapshotAndHistory() throws Exception {
    setUp("");
    Path resources = project.resolve("src/main/resources");

    Result generate = cli("generate");
    assertEquals(0, generate.code(), generate.all());
    String mainSql = sqlIn(resources.resolve("db/migration"));
    assertTrue(mainSql.contains("customer"), mainSql);
    assertFalse(mainSql.contains("invoice"), "billing's entities stay out: " + mainSql);
    assertTrue(Files.exists(project.resolve(".migrax/snapshot.json")));

    Result billingGenerate = cli("generate", "--database", "billing");
    assertEquals(0, billingGenerate.code(), billingGenerate.all());
    String billingSql = sqlIn(resources.resolve("db/billing"));
    assertTrue(billingSql.contains("invoice"), billingSql);
    assertFalse(billingSql.contains("customer"), billingSql);
    assertTrue(Files.exists(project.resolve(".migrax/billing/snapshot.json")));
    assertTrue(Files.exists(project.resolve(".migrax/billing/history")));

    Files.writeString(resources.resolve("db/billing/0002_owner.sql"),
        "CREATE TABLE owner_note (who VARCHAR(20));\n"
            + "INSERT INTO owner_note VALUES ('${owner}');\n");

    assertEquals(0, cli("migrate").code());
    Result migrate = cli("migrate", "--database", "billing");
    assertEquals(0, migrate.code(), migrate.all());
    assertTrue(migrate.out().contains("in src/main/resources/db/billing"), migrate.all());
    assertEquals("customer", tables(main));
    assertEquals("invoice,owner_note", tables(billing));
    assertEquals("billing_app", query(billing, "SELECT who FROM owner_note"));

    Result status = cli("status", "--database", "billing");
    assertEquals(0, status.code(), status.all());
    assertTrue(status.out().startsWith("Migrations in src/main/resources/db/billing for "),
        status.all());
    assertEquals(0, cli("check", "--database", "billing").code());
    assertEquals(0, cli("check", "--database", "default").code());
    assertEquals(0, cli("check").code());

    Result doctor = cli("doctor", "--database", "billing");
    assertTrue(doctor.out().contains(", database billing"), doctor.all());
    assertTrue(doctor.out().contains("Entity package: org.migrax.cli.multidb.billing"),
        doctor.all());
  }

  @Test
  void javaMigrationsOfAFurtherDatabaseDefaultToItsOwnPackage() throws Exception {
    setUp("");
    // The same default as at startup (Databases): db.<name>, never the default database's.
    Result created = cli("new", "seed", "--java", "--database", "billing");
    assertEquals(0, created.code(), created.all());
    Path source = project.resolve("src/main/java/db/billing/V0001__Seed.java");
    assertTrue(Files.isRegularFile(source), created.all());
    assertTrue(Files.readString(source).startsWith("package db.billing;"), created.all());
    assertFalse(Files.exists(project.resolve("src/main/java/db/migration")), created.all());
  }

  @Test
  void theDatabaseCanBeChosenWithASystemProperty() throws Exception {
    setUp("");
    Files.createDirectories(project.resolve("src/main/resources/db/billing"));
    Files.writeString(project.resolve("src/main/resources/db/billing/0001_a.sql"),
        "CREATE TABLE a (id INT);\n");
    System.setProperty("migrax.database", "billing");
    try {
      Result migrate = cli("migrate");
      assertEquals(0, migrate.code(), migrate.all());
    } finally {
      System.clearProperty("migrax.database");
    }
    assertEquals("a", tables(billing));
    assertEquals(null, tables(main));
  }

  @Test
  void unknownDatabasesAndMissingSettingsAreExplained() throws Exception {
    setUp("migrax.databases.audit.locations=db/audit");

    Result unknown = cli("status", "--database", "orders");
    assertEquals(1, unknown.code(), unknown.all());
    assertTrue(unknown.err().contains("No database named 'orders'."), unknown.all());
    assertTrue(unknown.err().contains("Configured: audit, billing."), unknown.all());

    Result reserved = cli("status", "--database", "migration");
    assertEquals(1, reserved.code(), reserved.all());
    assertTrue(reserved.err().contains("migrax.databases.migration is reserved"),
        reserved.all());

    Result noUrl = cli("status", "--database", "audit");
    assertEquals(1, noUrl.code(), noUrl.all());
    assertTrue(noUrl.err().contains("No URL found for database 'audit'."), noUrl.all());
    assertTrue(noUrl.err().contains("MIGRAX_DATABASES_AUDIT_URL"), noUrl.all());

    Result noPackage = cli("check", "--database", "audit");
    assertEquals(1, noPackage.code(), noPackage.all());
    assertTrue(noPackage.err().contains("Set migrax.databases.audit.package"), noPackage.all());
  }
}
