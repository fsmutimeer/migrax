package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.lint.SqlLinter;
import org.migrax.model.SchemaModel;
import org.migrax.verify.SchemaComparator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The page {@code migrax report} writes: one HTML file with its styles inside. */
final class HtmlReport {
  private HtmlReport() {}

  /** One migration with its state, or a null state when the database wasn't read. */
  record MigrationRow(String schema, String version, String state, String detail) {}

  /**
   * Everything on the page. Null lists mean "not checked".
   *
   * @param database the sanitized database URL, or null
   * @param schemaSource where the schema was read from, or null when it wasn't
   * @param unmigrated entity changes without a migration, or null when the entities weren't read
   * @param pending versions of pending migrations
   * @param drift differences between the database and the snapshot, or null
   * @param databaseNote why the database parts are missing, or null
   */
  record Data(String project, String version, String generated, Dialect dialect,
              String database, String schemaSource, SchemaModel schema, String entitiesNote,
              List<String> unmigrated, List<MigrationRow> migrations, Set<String> pending,
              List<SqlLinter.Finding> findings, String migrationFolder,
              List<SchemaComparator.Difference> drift, String databaseNote) {}

  /** The one-line summary {@code migrax report} prints. */
  static String summary(Data data) {
    List<String> parts = new ArrayList<>();
    parts.add((data.schema() == null ? 0 : data.schema().tables().size()) + " table(s)");
    parts.add(data.migrations().size() + " migration(s)"
        + (data.pending().isEmpty() ? "" : ", " + data.pending().size() + " pending"));
    parts.add(data.findings().size() + " lint finding(s)");
    if (data.unmigrated() != null && !data.unmigrated().isEmpty()) {
      parts.add(data.unmigrated().size() + " entity change(s) without a migration");
    }
    if (data.drift() != null) {
      parts.add(data.drift().isEmpty() ? "no drift" : data.drift().size() + " drift difference(s)");
    }
    return String.join(", ", parts);
  }

  static String render(Data data) {
    StringBuilder html = new StringBuilder();
    html.append("""
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        """);
    html.append("<title>Migrax report: ").append(esc(data.project())).append("</title>\n");
    html.append("<style>\n").append(STYLE).append("</style>\n</head>\n<body>\n<main>\n");

    html.append("<header>\n<h1>Migrax report <span>").append(esc(data.project()))
        .append("</span></h1>\n<p class=\"meta\">")
        .append(esc(data.dialect().id()));
    if (data.database() != null) {
      html.append(" &middot; <code>").append(esc(data.database())).append("</code>");
    }
    html.append(" &middot; ").append(esc(data.generated())).append(" &middot; Migrax ")
        .append(esc(data.version())).append("</p>\n</header>\n");

    cards(html, data);
    html.append("<nav><a href=\"#migrations\">Migrations</a><a href=\"#lint\">Lint</a>"
        + "<a href=\"#drift\">Drift</a><a href=\"#schema\">Schema</a></nav>\n");

    if (data.unmigrated() != null && !data.unmigrated().isEmpty()) {
      html.append("<section id=\"changes\" class=\"alert\">\n<h2>Entity changes without a "
          + "migration</h2>\n<p>Run <code>migrax generate</code> and commit the result.</p>\n"
          + "<ul>\n");
      data.unmigrated().forEach(c -> html.append("<li>").append(esc(c)).append("</li>\n"));
      html.append("</ul>\n</section>\n");
    }
    migrations(html, data);
    lint(html, data);
    drift(html, data);
    schema(html, data);

    html.append("</main>\n");
    if (data.schema() != null && !data.schema().tables().isEmpty()) {
      // The diagram needs the internet; offline, its text stays readable.
      html.append("<script type=\"module\">\nimport mermaid from "
          + "'https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.esm.min.mjs';\n"
          + "mermaid.initialize({ startOnLoad: true, theme: "
          + "matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'neutral' });\n"
          + "</script>\n");
    }
    html.append("</body>\n</html>\n");
    return html.toString();
  }

  private static void cards(StringBuilder html, Data data) {
    long applied = data.migrations().stream().filter(m -> "APPLIED".equals(m.state())).count();
    long problems = data.migrations().stream().filter(m -> m.state() != null
        && List.of("FAILED", "CHANGED", "MISSING").contains(m.state())).count();
    long errors = data.findings().stream()
        .filter(f -> f.severity() == SqlLinter.Severity.ERROR).count();
    long warnings = data.findings().stream()
        .filter(f -> f.severity() == SqlLinter.Severity.WARNING).count();
    html.append("<div class=\"cards\">\n");
    card(html, "Tables", String.valueOf(data.schema() == null ? 0
        : data.schema().tables().size()), data.schemaSource() == null ? "not read"
        : "from the " + data.schemaSource().replaceFirst(" \\(.*", ""), "");
    boolean checked = data.migrations().stream().anyMatch(m -> m.state() != null);
    card(html, "Migrations", String.valueOf(data.migrations().size()), checked
        ? applied + " applied, " + data.pending().size() + " pending"
            + (problems == 0 ? "" : ", " + problems + " need attention")
        : "status not checked", problems > 0 ? "bad" : data.pending().isEmpty() ? "" : "warn");
    card(html, "Lint findings", String.valueOf(data.findings().size()),
        errors + " error(s), " + warnings + " warning(s)",
        errors > 0 ? "bad" : warnings > 0 ? "warn" : "good");
    if (data.unmigrated() != null) {
      card(html, "Without a migration", String.valueOf(data.unmigrated().size()),
          "entity change(s)", data.unmigrated().isEmpty() ? "good" : "bad");
    }
    card(html, "Drift", data.drift() == null ? "&ndash;" : String.valueOf(data.drift().size()),
        data.drift() == null ? "not checked" : data.drift().isEmpty() ? "database matches"
            : data.pending().isEmpty() ? "difference(s)" : "difference(s), migrations pending",
        data.drift() == null ? "" : data.drift().isEmpty() ? "good"
            : data.pending().isEmpty() ? "bad" : "warn");
    html.append("</div>\n");
  }

  private static void card(StringBuilder html, String title, String value, String note,
                           String tone) {
    html.append("<div class=\"card ").append(tone).append("\"><div class=\"label\">")
        .append(esc(title)).append("</div><div class=\"value\">").append(value)
        .append("</div><div class=\"note\">").append(esc(note)).append("</div></div>\n");
  }

  private static void migrations(StringBuilder html, Data data) {
    html.append("<section id=\"migrations\">\n<h2>Migrations</h2>\n<p class=\"meta\">In <code>")
        .append(esc(data.migrationFolder())).append("</code></p>\n");
    if (data.databaseNote() != null) {
      html.append("<p class=\"note\">").append(esc(data.databaseNote())).append("</p>\n");
    }
    if (data.migrations().isEmpty()) {
      html.append("<p>No migrations yet. Run <code>migrax generate</code>.</p>\n</section>\n");
      return;
    }
    Map<String, Integer> findings = new LinkedHashMap<>();
    data.findings().forEach(f -> findings.merge(fileName(f.file()), 1, Integer::sum));
    boolean schemas = data.migrations().stream().anyMatch(m -> m.schema() != null);
    html.append("<table>\n<thead><tr>").append(schemas ? "<th>Schema</th>" : "")
        .append("<th>Migration</th><th>State</th><th>Detail</th><th>Lint</th></tr></thead>\n"
            + "<tbody>\n");
    for (MigrationRow row : data.migrations()) {
      String state = row.state() == null ? "unknown" : row.state().toLowerCase(Locale.ROOT);
      html.append("<tr>").append(schemas ? "<td>" + esc(String.valueOf(row.schema())) + "</td>"
          : "").append("<td><code>").append(esc(row.version())).append("</code></td><td>"
          + "<span class=\"badge ").append(state).append("\">").append(row.state() == null
          ? "not checked" : state).append("</span></td><td>").append(esc(row.detail()))
          .append("</td><td>");
      Integer count = findings.get(row.version());
      if (count != null) {
        html.append("<a href=\"#lint\">").append(count).append("</a>");
      }
      html.append("</td></tr>\n");
    }
    html.append("</tbody>\n</table>\n</section>\n");
  }

  private static void lint(StringBuilder html, Data data) {
    html.append("<section id=\"lint\">\n<h2>Lint findings</h2>\n");
    if (data.findings().isEmpty()) {
      html.append("<p class=\"good\">No findings in any migration.</p>\n</section>\n");
      return;
    }
    html.append("<p class=\"meta\">Every migration file is checked. Silence a finding with "
        + "<code>-- migrax:lint-ignore MX001</code> before the statement.</p>\n");
    for (SqlLinter.Finding finding : data.findings()) {
      String severity = finding.severity().name().toLowerCase(Locale.ROOT);
      boolean pending = data.pending().contains(fileName(finding.file()));
      html.append("<div class=\"finding ").append(severity).append("\">\n<div class=\"head\">"
          + "<span class=\"badge ").append(severity).append("\">").append(severity)
          .append(' ').append(esc(finding.code())).append("</span> <code>")
          .append(esc(finding.file())).append("</code>, statement ")
          .append(finding.statement()).append(pending ? " <span class=\"badge pending\">"
              + "pending</span>" : "").append("</div>\n<pre>").append(esc(finding.sql()))
          .append("</pre>\n<p>").append(esc(finding.message())).append("</p>\n<p><b>Fix:</b> ")
          .append(esc(finding.fix())).append("</p>\n</div>\n");
    }
    html.append("</section>\n");
  }

  private static void drift(StringBuilder html, Data data) {
    html.append("<section id=\"drift\">\n<h2>Drift</h2>\n");
    if (data.drift() == null) {
      html.append("<p class=\"note\">").append(data.databaseNote() != null
          ? "Not checked: the database wasn't read."
          : "Not checked: there is no snapshot yet (<code>migrax generate</code> writes it).")
          .append("</p>\n</section>\n");
      return;
    }
    if (!data.pending().isEmpty()) {
      html.append("<p class=\"meta\">").append(data.pending().size()).append(" migration(s) "
          + "are pending; differences they cover are expected.</p>\n");
    }
    if (data.drift().isEmpty()) {
      html.append("<p class=\"good\">No drift: the database matches the snapshot.</p>\n");
    } else {
      html.append(data.pending().isEmpty()
          ? "<p>Someone changed the database outside the migrations. Write a migration for "
              + "intended changes (<code>migrax new &lt;name&gt;</code>), or revert them.</p>\n"
          : "<p>Apply the pending migrations, then check again: what remains was changed "
              + "outside the migrations.</p>\n");
      html.append("<table>\n<thead><tr><th>Object</th><th>Difference</th></tr></thead>\n"
          + "<tbody>\n");
      for (SchemaComparator.Difference difference : data.drift()) {
        html.append("<tr><td><code>").append(esc(difference.object())).append("</code></td><td>")
            .append(esc(difference.detail())).append("</td></tr>\n");
      }
      html.append("</tbody>\n</table>\n");
    }
    html.append("</section>\n");
  }

  private static void schema(StringBuilder html, Data data) {
    html.append("<section id=\"schema\">\n<h2>Schema</h2>\n");
    if (data.entitiesNote() != null) {
      html.append("<p class=\"note\">").append(esc(data.entitiesNote())).append("</p>\n");
    }
    if (data.schema() == null || data.schema().tables().isEmpty()) {
      html.append("<p>No tables.</p>\n</section>\n");
      return;
    }
    html.append("<p class=\"meta\">From the ").append(esc(data.schemaSource()))
        .append(".</p>\n<pre class=\"mermaid\">\n").append(esc(diagram(data.schema())))
        .append("</pre>\n");
    for (SchemaModel.Table table : data.schema().tables()) {
      table(html, table, data.dialect());
    }
    if (!data.schema().sequences().isEmpty()) {
      html.append("<h3>Sequences</h3>\n<ul>\n");
      data.schema().sequences().forEach(s -> html.append("<li><code>").append(esc(s.name()))
          .append("</code></li>\n"));
      html.append("</ul>\n");
    }
    html.append("</section>\n");
  }

  private static void table(StringBuilder html, SchemaModel.Table table, Dialect dialect) {
    Set<String> key = new HashSet<>(table.primaryKey() == null ? List.of()
        : table.primaryKey().columns());
    Set<String> foreign = new HashSet<>();
    table.foreignKeys().forEach(k -> foreign.addAll(k.columns()));
    html.append("<details class=\"table\" id=\"table-").append(esc(table.name()))
        .append("\" open>\n<summary><code>").append(esc(table.name())).append("</code> <span "
            + "class=\"meta\">").append(table.columns().size()).append(" column(s)</span>");
    if (table.comment() != null) {
      html.append(" <span class=\"comment\">").append(esc(table.comment())).append("</span>");
    }
    html.append("</summary>\n<table>\n<thead><tr><th>Column</th><th>Type</th><th>Null</th>"
        + "<th>Default</th><th>Key</th><th>Comment</th></tr></thead>\n<tbody>\n");
    for (SchemaModel.Column column : table.columns()) {
      List<String> keys = new ArrayList<>();
      if (key.contains(column.name())) {
        keys.add("PK");
      }
      if (foreign.contains(column.name())) {
        keys.add("FK");
      }
      if (column.unique()) {
        keys.add("UK");
      }
      if (column.identity()) {
        keys.add("identity");
      }
      html.append("<tr><td><code>").append(esc(column.name())).append("</code></td><td><code>")
          .append(esc(type(dialect, column))).append("</code></td><td>")
          .append(column.nullable() ? "yes" : "<b>no</b>").append("</td><td>")
          .append(column.defaultValue() == null ? "" : "<code>" + esc(column.defaultValue())
              + "</code>").append("</td><td>").append(String.join(" ", keys))
          .append("</td><td>").append(column.comment() == null ? "" : esc(column.comment()))
          .append("</td></tr>\n");
    }
    html.append("</tbody>\n</table>\n");
    List<String> extras = new ArrayList<>();
    for (SchemaModel.Index index : table.indexes()) {
      extras.add((index.unique() ? "Unique index " : "Index ") + "<code>" + esc(index.name())
          + "</code> (" + esc(String.join(", ", index.columns())) + ")");
    }
    for (SchemaModel.ForeignKey foreignKey : table.foreignKeys()) {
      extras.add("Foreign key <code>" + esc(foreignKey.name()) + "</code> ("
          + esc(String.join(", ", foreignKey.columns())) + ") &rarr; <a href=\"#table-"
          + esc(foreignKey.referencedTable()) + "\"><code>" + esc(foreignKey.referencedTable())
          + "</code></a> (" + esc(String.join(", ", foreignKey.referencedColumns())) + ")");
    }
    for (SchemaModel.Check check : table.checks()) {
      extras.add("Check <code>" + esc(check.name()) + "</code>: <code>"
          + esc(check.expression()) + "</code>");
    }
    if (!extras.isEmpty()) {
      html.append("<ul class=\"extras\">\n");
      extras.forEach(e -> html.append("<li>").append(e).append("</li>\n"));
      html.append("</ul>\n");
    }
    html.append("</details>\n");
  }

  private static String type(Dialect dialect, SchemaModel.Column column) {
    try {
      return dialect.columnType(column);
    } catch (RuntimeException e) {
      return String.valueOf(column.sqlType());
    }
  }

  /** A Mermaid ER diagram: tables, their columns and their foreign keys. */
  static String diagram(SchemaModel schema) {
    StringBuilder text = new StringBuilder("erDiagram\n");
    for (SchemaModel.Table table : schema.tables()) {
      Set<String> key = new HashSet<>(table.primaryKey() == null ? List.of()
          : table.primaryKey().columns());
      Set<String> foreign = new HashSet<>();
      table.foreignKeys().forEach(k -> foreign.addAll(k.columns()));
      text.append("  ").append(id(table.name())).append(" {\n");
      for (SchemaModel.Column column : table.columns()) {
        List<String> keys = new ArrayList<>();
        if (key.contains(column.name())) {
          keys.add("PK");
        }
        if (foreign.contains(column.name())) {
          keys.add("FK");
        }
        if (column.unique()) {
          keys.add("UK");
        }
        text.append("    ").append(id(column.logicalType() == null ? "other"
            : column.logicalType())).append(' ').append(id(column.name()))
            .append(keys.isEmpty() ? "" : " " + String.join(", ", keys)).append('\n');
      }
      text.append("  }\n");
    }
    for (SchemaModel.Table table : schema.tables()) {
      for (SchemaModel.ForeignKey foreignKey : table.foreignKeys()) {
        if (schema.table(foreignKey.referencedTable()) == null) {
          continue;
        }
        boolean optional = foreignKey.columns().stream().map(table::column)
            .anyMatch(c -> c == null || c.nullable());
        text.append("  ").append(id(foreignKey.referencedTable()))
            .append(optional ? " |o--o{ " : " ||--o{ ").append(id(table.name()))
            .append(" : \"").append(String.join(", ", foreignKey.columns()).replace("\"", ""))
            .append("\"\n");
      }
    }
    return text.toString();
  }

  /** A name Mermaid accepts: letters, digits, underscores and hyphens. */
  private static String id(String name) {
    String id = name.replaceAll("[^A-Za-z0-9_-]", "_");
    return id.isEmpty() || !Character.isLetter(id.charAt(0)) ? "t_" + id : id;
  }

  private static String fileName(String path) {
    int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return path.substring(slash + 1);
  }

  static String esc(String text) {
    if (text == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length());
    for (char c : text.toCharArray()) {
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&#39;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private static final String STYLE = """
      :root { --bg: #ffffff; --fg: #1f2328; --muted: #59636e; --line: #d1d9e0;
        --card: #f6f8fa; --good: #1a7f37; --warn: #9a6700; --bad: #cf222e; --accent: #0969da;
        color-scheme: light dark; }
      @media (prefers-color-scheme: dark) {
        :root { --bg: #0d1117; --fg: #e6edf3; --muted: #9198a1; --line: #3d444d;
          --card: #151b23; --good: #3fb950; --warn: #d29922; --bad: #f85149; --accent: #4493f8; }
      }
      * { box-sizing: border-box; }
      body { margin: 0; background: var(--bg); color: var(--fg);
        font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
      main { max-width: 1100px; margin: 0 auto; padding: 24px 16px 64px; }
      h1 { font-size: 26px; margin: 0; } h1 span { color: var(--muted); font-weight: 400; }
      h2 { font-size: 20px; margin: 40px 0 8px; padding-bottom: 6px;
        border-bottom: 1px solid var(--line); }
      h3 { font-size: 16px; margin: 24px 0 8px; }
      code, pre { font: 13px/1.45 ui-monospace, SFMono-Regular, Consolas, monospace; }
      pre { background: var(--card); border: 1px solid var(--line); border-radius: 6px;
        padding: 10px 12px; overflow-x: auto; white-space: pre-wrap; }
      a { color: var(--accent); }
      .meta { color: var(--muted); margin: 4px 0; }
      .note { color: var(--warn); } .good { color: var(--good); }
      .cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
        gap: 12px; margin: 24px 0 8px; }
      .card { background: var(--card); border: 1px solid var(--line); border-radius: 8px;
        padding: 12px 14px; border-top: 3px solid var(--line); }
      .card.good { border-top-color: var(--good); } .card.warn { border-top-color: var(--warn); }
      .card.bad { border-top-color: var(--bad); }
      .card .label { color: var(--muted); font-size: 13px; }
      .card .value { font-size: 26px; font-weight: 600; }
      .card .note { color: var(--muted); font-size: 13px; }
      nav { display: flex; gap: 16px; flex-wrap: wrap; margin: 16px 0;
        border-bottom: 1px solid var(--line); padding-bottom: 8px; }
      nav a { text-decoration: none; font-weight: 500; }
      table { width: 100%; border-collapse: collapse; margin: 8px 0; display: block;
        overflow-x: auto; }
      th, td { text-align: left; padding: 6px 10px; border-bottom: 1px solid var(--line);
        vertical-align: top; }
      th { color: var(--muted); font-weight: 500; font-size: 13px; }
      .badge { display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 12px;
        font-weight: 600; border: 1px solid currentColor; white-space: nowrap; }
      .badge.applied, .badge.info { color: var(--good); }
      .badge.pending, .badge.outdated, .badge.warning { color: var(--warn); }
      .badge.failed, .badge.changed, .badge.missing, .badge.error { color: var(--bad); }
      .badge.skipped, .badge.unknown { color: var(--muted); }
      .finding { border: 1px solid var(--line); border-left: 4px solid var(--warn);
        border-radius: 6px; padding: 10px 14px; margin: 12px 0; }
      .finding.error { border-left-color: var(--bad); }
      .finding.info { border-left-color: var(--good); }
      .finding p { margin: 6px 0; }
      .alert { border: 1px solid var(--bad); border-radius: 8px; padding: 4px 16px;
        margin-top: 24px; } .alert h2 { border: 0; margin-top: 12px; }
      details.table { border: 1px solid var(--line); border-radius: 8px; padding: 8px 14px;
        margin: 12px 0; }
      details.table summary { cursor: pointer; font-size: 15px; }
      .comment { color: var(--muted); font-style: italic; }
      .extras { color: var(--muted); margin: 4px 0 0; padding-left: 20px; }
      pre.mermaid { background: transparent; border: 1px dashed var(--line); text-align: center; }
      """;
}
