package io.migrax.cli;

import io.migrax.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP server's protocol and tools, against an H2 project. */
class McpServerTest {
  private static int databases;

  @TempDir
  Path project;

  @BeforeEach
  void setUp() throws Exception {
    System.setProperty("migrax.noBuild", "true");
    Files.writeString(project.resolve("pom.xml"),
        "<project><groupId>io.migrax.cli.fixture</groupId></project>");
    Path resources = Files.createDirectories(project.resolve("src/main/resources"));
    Files.writeString(resources.resolve("application.properties"),
        "spring.datasource.url=jdbc:h2:mem:mcp_" + (++databases) + ";DB_CLOSE_DELAY=-1\n");
  }

  @AfterEach
  void tearDown() {
    System.clearProperty("migrax.noBuild");
  }

  private McpServer server(boolean allowGenerate) {
    return new McpServer(project, allowGenerate, new PrintStream(new ByteArrayOutputStream()));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> call(McpServer server, String message) {
    Object response = server.handle(message);
    return (Map<String, Object>) new Json.Parser(JsonOut.write(response)).parse();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> result(Map<String, Object> response) {
    assertNull(response.get("error"), String.valueOf(response));
    return (Map<String, Object>) response.get("result");
  }

  @SuppressWarnings("unchecked")
  private static String text(Map<String, Object> toolResult) {
    var content = (List<Map<String, Object>>) toolResult.get("content");
    return (String) content.get(0).get("text");
  }

  @Test
  void negotiatesTheProtocolVersion() {
    var known = result(call(server(false), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
        + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{}}}"));
    assertEquals("2025-03-26", known.get("protocolVersion"));
    assertTrue(String.valueOf(known.get("instructions")).contains("Never claim a migration ran"));
    var unknown = result(call(server(false), "{\"jsonrpc\":\"2.0\",\"id\":2,"
        + "\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}"));
    assertEquals(McpServer.PROTOCOL_VERSIONS.get(0), unknown.get("protocolVersion"));
    assertNull(server(false).handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
        "notifications get no response");
  }

  @Test
  void offersOnlyToolsThatDontChangeTheDatabase() {
    var tools = result(call(server(false),
        "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}")).get("tools").toString();
    for (String name : List.of("status", "check", "plan", "lint", "drift", "doctor", "verify",
        "show_migration")) {
      assertTrue(tools.contains("name=" + name + ","), name);
    }
    for (String name : List.of("migrate", "rollback", "repair", "clean", "generate")) {
      assertFalse(tools.contains("name=" + name + ","), name);
    }
    var withGenerate = result(call(server(true),
        "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}")).get("tools").toString();
    assertTrue(withGenerate.contains("name=generate,"));
    assertFalse(withGenerate.contains("name=migrate,"));
  }

  @Test
  void callsToolsAndReportsTheirResults() throws Exception {
    var refused = call(server(false), "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"generate\",\"arguments\":{}}}");
    assertTrue(String.valueOf(refused.get("error")).contains("--allow-generate"), refused.toString());

    McpServer writer = server(true);
    var generated = result(call(writer, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"generate\",\"arguments\":{\"name\":\"init\"}}}"));
    assertEquals(false, generated.get("isError"), text(generated));
    assertTrue(Files.exists(project.resolve("src/main/resources/db/migration/0001_init.sql")));

    var status = result(call(writer, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"status\",\"arguments\":{}}}"));
    assertEquals(false, status.get("isError"));
    assertTrue(text(status).startsWith("migrax status exited with code 0"), text(status));
    assertTrue(text(status).contains("\"state\":\"pending\""), text(status));

    var shown = result(call(writer, "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"show_migration\",\"arguments\":{\"name\":\"0001_init.sql\"}}}"));
    assertTrue(text(shown).contains("CREATE TABLE sample_entity"), text(shown));

    var missing = call(writer, "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\","
        + "\"params\":{\"name\":\"show_migration\",\"arguments\":{}}}");
    assertTrue(String.valueOf(missing.get("error")).contains("-32602"), missing.toString());
  }

  @Test
  void answersProtocolErrors() throws Exception {
    assertTrue(String.valueOf(call(server(false), "{not json").get("error")).contains("-32700"));
    assertTrue(String.valueOf(call(server(false),
        "{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"resources/list\"}").get("error"))
        .contains("-32601"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    server(false).serve(new BufferedReader(new StringReader(
        "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"ping\"}\n"
            + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n\n"
            + "{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"ping\"}\n")),
        new PrintStream(out, true, StandardCharsets.UTF_8));
    List<String> lines = out.toString(StandardCharsets.UTF_8).lines().toList();
    assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"id\":11,\"result\":{}}",
        "{\"jsonrpc\":\"2.0\",\"id\":12,\"result\":{}}"), lines);
  }
}
