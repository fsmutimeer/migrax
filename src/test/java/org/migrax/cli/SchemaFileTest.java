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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A project without entities: the desired schema is a SQL file (migrax.schema-file). */
class SchemaFileTest {
  private static int databases;

  @TempDir
  Path project;

  private String url;

  private record Result(int code, String out, String err) {
    String all() {
      return out + err;
    }
  }

  @BeforeEach
  void setUp() {
    url = "jdbc:h2:mem:schema_file_" + (++databases) + ";DB_CLOSE_DELAY=-1";
  }

  /** Runs a command the way a project without Java does: everything as options. */
  private Result cli(String... args) {
    List<String> full = new ArrayList<>(List.of(args));
    full.addAll(List.of("--url", url, "--locations", "filesystem:db/migrations",
        "--schema-file", "db/schema.sql"));
    return run(full.toArray(String[]::new));
  }

  /** Runs a command with only the options given (settings from the application config). */
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

  private void schema(String sql) throws Exception {
    Files.createDirectories(project.resolve("db"));
    Files.writeString(project.resolve("db/schema.sql"), sql);
  }

  private List<String> migrations() throws Exception {
    try (Stream<Path> files = Files.list(project.resolve("db/migrations"))) {
      return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted()
          .toList();
    }
  }

  private String migration(String prefix) throws Exception {
    String name = migrations().stream().filter(n -> n.startsWith(prefix)).findFirst()
        .orElseThrow();
    return Files.readString(project.resolve("db/migrations").resolve(name));
  }

  private String query(String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(url);
         var statement = connection.createStatement();
         var result = statement.executeQuery(sql)) {
      return result.next() ? result.getString(1) : null;
    }
  }

  private static final String CUSTOMER = """
      -- The tables as they should be.
      CREATE TABLE customer (
        id BIGINT PRIMARY KEY,
        email VARCHAR(150) NOT NULL
      );
      """;

  @Test
  void generatesMigrationsFromTheSchemaFile() throws Exception {
    schema(CUSTOMER);
    Result first = cli("generate");
    assertEquals(0, first.code(), first.all());
    assertTrue(first.out().contains("create table customer"), first.out());
    assertTrue(migration("0001").toUpperCase().contains("CREATE TABLE CUSTOMER"),
        migration("0001"));
    assertTrue(Files.isRegularFile(project.resolve(".migrax/schema-model.json")));
    assertEquals(0, cli("migrate").code());

    Result clean = cli("check");
    assertEquals(0, clean.code(), clean.all());
    assertTrue(clean.out().contains("OK: db/schema.sql matches the snapshot."), clean.out());

    schema(CUSTOMER.replace("NOT NULL\n", "NOT NULL,\n  phone VARCHAR(30)\n"));
    Result changed = cli("check");
    assertEquals(2, changed.code(), changed.all());
    assertTrue(changed.err().contains("1 change(s) in db/schema.sql have no migration:"),
        changed.err());
    assertTrue(changed.err().contains("add column customer.phone"), changed.err());

    Result added = cli("generate");
    assertEquals(0, added.code(), added.all());
    assertTrue(migration("0002").toUpperCase().contains("ADD COLUMN PHONE"), migration("0002"));
    assertTrue(Files.isRegularFile(project.resolve("db/migrations/rollback")
        .resolve(migrations().get(1))), "rollback script written");
    assertEquals(0, cli("migrate").code());
    assertEquals(0, cli("check").code());
  }

  @Test
  void renamesKeepTheData() throws Exception {
    schema(CUSTOMER);
    assertEquals(0, cli("generate").code());
    assertEquals(0, cli("migrate").code());
    try (Connection connection = DriverManager.getConnection(url);
         var statement = connection.createStatement()) {
      statement.execute("INSERT INTO customer (id, email) VALUES (1, 'a@example.com')");
    }

    schema(CUSTOMER.replace("email", "email_address"));
    Result refused = cli("generate");
    assertEquals(1, refused.code(), refused.all());
    assertTrue(refused.out().contains("--rename customer.email=email_address"), refused.all());

    Result renamed = cli("generate", "--rename", "customer.email=email_address");
    assertEquals(0, renamed.code(), renamed.all());
    assertTrue(renamed.out().contains("rename column customer.email to email_address"),
        renamed.out());
    assertEquals(0, cli("migrate").code());
    assertEquals("a@example.com", query("SELECT email_address FROM customer"));
  }

  @Test
  void usesTheCachedModelWhileTheFileIsUnchanged() throws Exception {
    schema(CUSTOMER);
    assertEquals(0, cli("generate").code());
    Path cache = project.resolve(".migrax/schema-model.json");
    // A cached model that differs from the file: only the cache can produce this change.
    Files.writeString(cache, Files.readString(cache).replace("\"email\"", "\"mail\""));
    Result fromCache = cli("check");
    assertEquals(2, fromCache.code(), fromCache.all());

    // Editing the file makes the cache stale: Migrax reads the file again.
    schema(CUSTOMER + "\n");
    Result reread = cli("check");
    assertEquals(0, reread.code(), reread.all());
    assertFalse(Files.readString(cache).contains("\"mail\""), "cache rewritten");
  }

  private static final String ORDERS = CUSTOMER + """
      CREATE TABLE orders (
        id BIGINT PRIMARY KEY,
        customer_id BIGINT NOT NULL REFERENCES customer (id),
        total DECIMAL(12, 2) NOT NULL CHECK (total >= 0),
        status VARCHAR(10) DEFAULT 'new' NOT NULL
      );
      """;

  @Test
  void unnamedConstraintsKeepTheirNamesWhenTheFileChanges() throws Exception {
    schema(ORDERS);
    Result first = cli("generate");
    assertEquals(0, first.code(), first.all());
    String sql = migration("0001");
    // H2 names unnamed constraints CONSTRAINT_xx; Migrax writes its own readable names.
    assertFalse(sql.toUpperCase().contains("CONSTRAINT_"), sql);
    assertTrue(sql.contains("fk_orders_customer_id"), sql);
    assertTrue(sql.contains("DEFAULT 'new'"), sql);
    assertEquals(0, cli("migrate").code());

    schema(ORDERS + "CREATE INDEX orders_status ON orders (status);\n");
    Result changed = cli("check");
    assertEquals(2, changed.code(), changed.all());
    assertTrue(changed.err().contains("1 change(s)"), "only the new index: " + changed.err());
    assertTrue(changed.err().contains("orders_status"), changed.err());
  }

  @Test
  void defaultsAreKeptAndTheirChangesFound() throws Exception {
    schema(ORDERS);
    assertEquals(0, cli("generate").code());
    assertEquals(0, cli("migrate").code());
    schema(ORDERS.replace("DEFAULT 'new'", "DEFAULT 'open'"));
    Result changed = cli("check");
    assertEquals(2, changed.code(), changed.all());
    assertTrue(changed.err().contains("orders.status"), changed.err());
  }

  @Test
  void firstGenerateOnAnExistingDatabaseFindsNoChanges() throws Exception {
    try (Connection connection = DriverManager.getConnection(url);
         var statement = connection.createStatement()) {
      for (String sql : ORDERS.split(";")) {
        if (sql.contains("CREATE")) {
          statement.execute(sql);
        }
      }
    }
    schema(ORDERS);
    Result first = cli("generate");
    assertEquals(0, first.code(), first.all());
    assertTrue(first.out().contains("No changes detected."), first.out());
    assertTrue(migrations().get(0).contains("baseline"), migrations().toString());
    Result check = cli("check");
    assertEquals(0, check.code(), check.all());
  }

  @Test
  void eachDatabaseHasItsOwnSchemaFileAndCache() throws Exception {
    String billing = "jdbc:h2:mem:schema_file_billing_" + (++databases) + ";DB_CLOSE_DELAY=-1";
    Path resources = project.resolve("src/main/resources");
    Files.createDirectories(resources);
    Files.writeString(resources.resolve("application.properties"), String.join("\n",
        "migrax.url=" + url,
        "migrax.locations=filesystem:db/migrations",
        "migrax.schema-file=db/schema.sql",
        "migrax.databases.billing.url=" + billing,
        "migrax.databases.billing.locations=filesystem:db/billing",
        "migrax.databases.billing.schema-file=db/billing.sql", ""));
    schema(CUSTOMER);
    Files.writeString(project.resolve("db/billing.sql"),
        "CREATE TABLE invoice (id BIGINT PRIMARY KEY);\n");

    Result main = run("generate");
    assertEquals(0, main.code(), main.all());
    assertTrue(main.out().contains("create table customer"), main.out());
    Result other = run("generate", "--database", "billing");
    assertEquals(0, other.code(), other.all());
    assertTrue(other.out().contains("create table invoice"), other.out());
    assertTrue(Files.isRegularFile(project.resolve(".migrax/billing/schema-model.json")));
    assertTrue(Files.isRegularFile(project.resolve(".migrax/schema-model.json")));
  }

  @Test
  void aScratchDatabaseGivenWithScratchUrlIsEmptiedAgain() throws Exception {
    String scratch = "jdbc:h2:mem:schema_file_scratch_" + (++databases) + ";DB_CLOSE_DELAY=-1";
    schema(CUSTOMER);
    Result first = cli("generate", "--scratch-url", scratch);
    assertEquals(0, first.code(), first.all());
    schema(CUSTOMER + "\n");
    Result again = cli("check", "--scratch-url", scratch);
    assertEquals(0, again.code(), "the scratch database was emptied: " + again.all());
  }

  @Test
  void namesTheStatementThatFails() throws Exception {
    schema(CUSTOMER + "\nCREATE TABLE broken (id BIGINT PRIMARY KEY,);\n");
    Result result = cli("generate");
    assertEquals(1, result.code(), result.all());
    assertTrue(result.err().contains("MXE115"), result.err());
    assertTrue(result.err().contains("db/schema.sql, statement 2 (line 7) fails on h2"),
        result.err());
  }

  @Test
  void reportsAMissingFileAndAFileWithoutTables() throws Exception {
    Result missing = cli("generate");
    assertEquals(1, missing.code(), missing.all());
    assertTrue(missing.err().contains("Schema file not found: db/schema.sql"), missing.err());

    schema("-- nothing yet\n");
    Result empty = cli("generate");
    assertEquals(1, empty.code(), empty.all());
    assertTrue(empty.err().contains("db/schema.sql has no tables."), empty.err());
  }
}
