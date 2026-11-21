package org.migrax.cli;

import org.migrax.lint.SqlLinter;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lint findings as SARIF 2.1.0, the format GitHub code scanning, GitLab and Azure DevOps read to
 * show each finding on its SQL line in a pull request.
 */
final class SarifReport {
  private static final String RULES_PAGE = "https://migrax.org/reference/lint-rules/";
  /** One title per rule: code scanning shows it for every finding of that rule. */
  private static final Map<String, String> TITLES = Map.ofEntries(
      Map.entry("MX000", "The migration could not be parsed"),
      Map.entry("MX001", "Index build blocks writes"),
      Map.entry("MX002", "NOT NULL column without a default on an existing table"),
      Map.entry("MX003", "Column type change"),
      Map.entry("MX004", "SET NOT NULL scans the table under an exclusive lock"),
      Map.entry("MX005", "Foreign key checks every row while blocking writes"),
      Map.entry("MX006", "Unique constraint builds its index while blocking writes"),
      Map.entry("MX007", "Drop deletes data"),
      Map.entry("MX008", "Rename breaks running instances"),
      Map.entry("MX009", "UPDATE or DELETE without WHERE"),
      Map.entry("MX010", "TRUNCATE"),
      Map.entry("MX011", "Volatile default rewrites every row"),
      Map.entry("MX012", "Statement cannot run inside a transaction"),
      Map.entry("MX013", "Primary key builds an index while blocking writes"),
      Map.entry("MX014", "Check constraint scans the table under an exclusive lock"),
      Map.entry("MX015", "Index build locks the table unless online"),
      Map.entry("MX016", "Routine body takes in the statements after it"),
      Map.entry("MX017", "Table without its schema"),
      Map.entry("MX018", "Transaction control in a migration"),
      Map.entry("MX020", "Statement has a non-blocking CONCURRENTLY form"),
      Map.entry("MX021", "Statement rewrites the table under an exclusive lock"));

  private SarifReport() {}

  /**
   * The SARIF document.
   *
   * @param findings the findings, with {@code file} as Migrax shows it
   * @param paths each shown file name and where it is
   * @param base the folder the paths in the document are relative to (the current folder, which
   *     in CI is the repository's root)
   */
  static String render(List<SqlLinter.Finding> findings, Map<String, Path> paths, Path base) {
    Map<String, Object> rules = new LinkedHashMap<>();
    List<Object> results = new ArrayList<>();
    for (SqlLinter.Finding finding : findings) {
      rules.putIfAbsent(finding.code(), JsonOut.object(
          "id", finding.code(),
          "shortDescription", JsonOut.object("text",
              TITLES.getOrDefault(finding.code(), "Migrax lint rule " + finding.code())),
          "helpUri", RULES_PAGE));
      String text = finding.message() + " Fix: " + finding.fix()
          + (finding.safeSql() == null ? "" : "\nSafe SQL:\n" + finding.safeSql());
      // Code scanning needs a line: an unknown one points at the top of the file.
      Map<String, Object> location = JsonOut.object("artifactLocation",
          JsonOut.object("uri", uri(finding.file(), paths, base)),
          "region", JsonOut.object("startLine", Math.max(1, finding.line())));
      results.add(JsonOut.object(
          "ruleId", finding.code(),
          "level", switch (finding.severity()) {
            case ERROR -> "error";
            case WARNING -> "warning";
            case INFO -> "note";
          },
          "message", JsonOut.object("text", text),
          "locations", List.of(JsonOut.object("physicalLocation", location))));
    }
    Map<String, Object> driver = JsonOut.object("name", "Migrax", "version", Version.current(),
        "informationUri", "https://migrax.org/", "rules", new ArrayList<>(rules.values()));
    return JsonOut.write(JsonOut.object(
        "$schema", "https://json.schemastore.org/sarif-2.1.0.json",
        "version", "2.1.0",
        "runs", List.of(JsonOut.object("tool", JsonOut.object("driver", driver),
            "results", results))));
  }

  /** The file relative to the base folder with / between names, or as shown when outside it. */
  private static String uri(String shown, Map<String, Path> paths, Path base) {
    Path file = paths.get(shown);
    if (file != null) {
      Path absolute = file.toAbsolutePath().normalize();
      Path root = base.toAbsolutePath().normalize();
      if (absolute.startsWith(root)) {
        return encode(root.relativize(absolute).toString());
      }
    }
    return encode(shown);
  }

  /** A relative URI: / between names, and spaces, # and % escaped. */
  static String encode(String path) {
    try {
      return new URI(null, null, path.replace('\\', '/'), null).getRawPath();
    } catch (URISyntaxException e) {
      return path.replace('\\', '/');
    }
  }
}
