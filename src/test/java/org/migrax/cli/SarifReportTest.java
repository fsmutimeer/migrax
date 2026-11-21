package org.migrax.cli;

import org.junit.jupiter.api.Test;
import org.migrax.lint.SqlLinter;
import org.migrax.util.Json;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SarifReportTest {

  @Test
  void writesLevelsLinesAndPathsCodeScanningAccepts() {
    Path base = Path.of("repo").toAbsolutePath();
    List<SqlLinter.Finding> findings = List.of(
        new SqlLinter.Finding("MX015", SqlLinter.Severity.INFO, "db/0001 a#b.sql", 1, "CREATE",
            "Index builds lock orders.", "Use ONLINE.", 0, null),
        new SqlLinter.Finding("MX015", SqlLinter.Severity.INFO, "/elsewhere/0002.sql", 2,
            "CREATE", "Index builds lock items.", "Use ONLINE.", 7, null));
    Map<String, Path> paths = Map.of("db/0001 a#b.sql", base.resolve("db/0001 a#b.sql"),
        "/elsewhere/0002.sql", Path.of("/elsewhere/0002.sql").toAbsolutePath());
    var document = (Map<?, ?>) new Json.Parser(SarifReport.render(findings, paths, base))
        .parse();
    var run = (Map<?, ?>) ((List<?>) document.get("runs")).get(0);
    var rules = (List<?>) ((Map<?, ?>) ((Map<?, ?>) run.get("tool")).get("driver")).get("rules");
    assertEquals(1, rules.size());
    assertEquals("Index build locks the table unless online",
        ((Map<?, ?>) ((Map<?, ?>) rules.get(0)).get("shortDescription")).get("text"));
    var results = (List<?>) run.get("results");
    assertEquals("note", ((Map<?, ?>) results.get(0)).get("level"));
    assertEquals("db/0001%20a%23b.sql", uri(results.get(0)));
    assertEquals(1, line(results.get(0)));
    assertEquals(7, line(results.get(1)));
    assertEquals(SarifReport.encode(Path.of("/elsewhere/0002.sql").toAbsolutePath().toString()),
        uri(results.get(1)));
  }

  private static Map<?, ?> physical(Object result) {
    return (Map<?, ?>) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) result).get("locations")).get(0))
        .get("physicalLocation");
  }

  private static Object uri(Object result) {
    return ((Map<?, ?>) physical(result).get("artifactLocation")).get("uri");
  }

  private static int line(Object result) {
    return ((Number) ((Map<?, ?>) physical(result).get("region")).get("startLine")).intValue();
  }
}
