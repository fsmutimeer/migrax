package org.migrax.cli;

import org.migrax.dialect.Dialects;
import org.migrax.lint.SqlLinter;
import org.migrax.model.SchemaModel;
import org.migrax.verify.SchemaComparator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report page, compared with {@code src/test/resources/report/expected.html}. After an
 * intended change, check {@code target/report-actual.html} in a browser and copy it there.
 */
class HtmlReportTest {

  private static SchemaModel.Column column(String name, String type, boolean nullable,
                                           Integer length) {
    return new SchemaModel.Column(name, type, nullable, length, null, null, null, false, false,
        null, type);
  }

  static HtmlReport.Data data() {
    SchemaModel.Column email = new SchemaModel.Column("email", "varchar", false, 120, null,
        null, null, true, false, null, "varchar", "Where receipts go");
    SchemaModel schema = new SchemaModel(List.of(
        new SchemaModel.Table("customer", List.of(
            new SchemaModel.Column("id", "bigint", false, null, null, null, null, false, true,
                null, "bigint"), email),
            new SchemaModel.PrimaryKey(List.of("id"), "customer_pkey"), List.of(), List.of(),
            List.of(), "People who <order>"),
        new SchemaModel.Table("purchase_order", List.of(column("id", "bigint", false, null),
            column("customer_id", "bigint", true, null), column("total", "decimal", false, null)),
            new SchemaModel.PrimaryKey(List.of("id"), "purchase_order_pkey"),
            List.of(new SchemaModel.Index("idx_order_customer", List.of("customer_id"), false)),
            List.of(new SchemaModel.ForeignKey("fk_order_customer", List.of("customer_id"),
                "customer", List.of("id"))),
            List.of(new SchemaModel.Check("chk_total", "total >= 0")), null)));
    List<HtmlReport.MigrationRow> rows = List.of(
        new HtmlReport.MigrationRow(null, "0001_initial.sql", "APPLIED",
            "applied 2026-10-16 08:00:00"),
        new HtmlReport.MigrationRow(null, "0002_drop_notes.sql", "PENDING", ""));
    List<SqlLinter.Finding> findings = List.of(new SqlLinter.Finding("MX007",
        SqlLinter.Severity.WARNING, "src/main/resources/db/migration/0002_drop_notes.sql", 1,
        "ALTER TABLE customer DROP COLUMN notes", "This deletes data.", "Drop it later."));
    List<SchemaComparator.Difference> drift = List.of(new SchemaComparator.Difference(
        "extra_column", "customer.nickname", "column exists in the database but not in the "
            + "expected schema"));
    return new HtmlReport.Data("shop", "0.11.0", "2026-10-16 08:00 UTC",
        Dialects.byName("postgresql"), "jdbc:postgresql://localhost:5432/shop",
        "entities (Hibernate 7.2.0.Final mapping)", schema, null,
        List.of("add column customer.phone"), rows, Set.of("0002_drop_notes.sql"), findings,
        "src/main/resources/db/migration", drift, null);
  }

  @Test
  void theReportMatchesTheReviewedPage() throws Exception {
    String actual = HtmlReport.render(data());
    Path written = Path.of("target", "report-actual.html");
    Files.createDirectories(written.getParent());
    Files.writeString(written, actual);
    String expected;
    try (InputStream in = getClass().getResourceAsStream("/report/expected.html")) {
      assertNotNull(in, "src/test/resources/report/expected.html is missing; review "
          + written.toAbsolutePath() + " and copy it there.");
      expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertEquals(expected, actual, "The report changed. Check " + written.toAbsolutePath()
        + " in a browser and copy it to src/test/resources/report/expected.html if it is right.");
  }

  @Test
  void escapesEverythingFromTheProjectAndTheDatabase() {
    String html = HtmlReport.render(data());
    assertTrue(html.contains("People who &lt;order&gt;"), html);
    assertTrue(html.contains("total &gt;= 0"), html);
    assertEquals("&lt;script&gt;&amp;&quot;&#39;", HtmlReport.esc("<script>&\"'"));
  }

  @Test
  void theDiagramUsesNamesMermaidAccepts() {
    SchemaModel schema = new SchemaModel(List.of(new SchemaModel.Table("order line",
        List.of(column("1st", "integer", true, null)), null, List.of(), List.of())));
    assertEquals("erDiagram\n  order_line {\n    integer t_1st\n  }\n",
        HtmlReport.diagram(schema));
    assertEquals("""
        erDiagram
          customer {
            bigint id PK
            varchar email UK
          }
          purchase_order {
            bigint id PK
            bigint customer_id FK
            decimal total
          }
          customer |o--o{ purchase_order : "customer_id"
        """, HtmlReport.diagram(data().schema()));
  }

  @Test
  void theSummaryLineCountsWhatMatters() {
    assertEquals("2 table(s), 2 migration(s), 1 pending, 1 lint finding(s), 1 entity change(s) "
        + "without a migration, 1 drift difference(s)", HtmlReport.summary(data()));
  }
}
