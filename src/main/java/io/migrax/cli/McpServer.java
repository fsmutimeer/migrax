package io.migrax.cli;

import io.migrax.util.Json;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A Model Context Protocol server over stdin/stdout: AI assistants call Migrax's read-only
 * commands as tools and get their JSON results. Messages are JSON-RPC 2.0, one per line.
 *
 * <p>Read-only by default. {@code generate} is offered only when the server was started with
 * {@code --allow-generate}; it writes migration files, which stay reviewable in version
 * control. Commands that change a database ({@code migrate}, {@code rollback},
 * {@code repair}, {@code clean}) are never offered: the person runs them.
 */
final class McpServer {
  /** Protocol versions this server speaks, newest first. */
  static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

  static final String INSTRUCTIONS = """
      Migrax manages database migrations for this JPA/Hibernate project: it compares the \
      entities with the snapshot of what the migrations produce and writes SQL migrations. \
      Use status, check and plan to see what changed; plan shows the exact SQL with lint \
      findings and table sizes before anything is written. Explain risky findings (locking \
      statements, destructive changes, NOT NULL columns on big tables) to the person. Never \
      claim a migration ran: applying, rolling back, repairing or cleaning a database is done \
      by the person with the migrax command line, never by this server. Ask before \
      generating migrations, and ask about renames instead of guessing (a rename keeps data, \
      a drop and add loses it).""";

  private final Path project;
  private final boolean allowGenerate;
  private final PrintStream log;
  private final Map<String, Tool> tools = new LinkedHashMap<>();

  /** One tool: its description, input schema and how it turns arguments into a command line. */
  private record Tool(String description, Map<String, Object> inputSchema,
                      Function<Map<String, Object>, List<String>> command) {}

  /**
   * @param project the project folder the tools run in
   * @param allowGenerate offer the {@code generate} tool
   * @param log where the server reports its own problems (stderr)
   */
  McpServer(Path project, boolean allowGenerate, PrintStream log) {
    this.project = project;
    this.allowGenerate = allowGenerate;
    this.log = log;
    tool("status", "Shows which migrations are applied, pending, failed or changed in the "
        + "configured database. Read-only.", schema(), args -> List.of("status", "--json"));
    tool("check", "Tells whether the entities changed without a migration being written for "
        + "them (exit code 2 means yes). Read-only; no database needed once a snapshot exists.",
        schema(), args -> List.of("check", "--json"));
    tool("plan", "Previews the SQL that 'generate' would write for the current entity changes, "
        + "with lint findings and, when the database is reachable, row counts of the affected "
        + "tables. Writes nothing.", schema(), args -> List.of("plan", "--json", "--impact"));
    tool("lint", "Checks migration SQL for statements that lock tables, fail on existing data "
        + "or break running application instances. By default only pending migrations; "
        + "all=true checks every file.",
        schema("all", "boolean", "Check every migration file, not only pending ones"),
        args -> flagged(List.of("lint", "--json"), args, "all", "--all"));
    tool("drift", "Compares the live database schema with what the migrations produce "
        + "(entities=true: with the entities) and lists manual changes. Read-only.",
        schema("entities", "boolean", "Compare with the entities instead of the snapshot"),
        args -> flagged(List.of("drift", "--json"), args, "entities", "--entities"));
    tool("doctor", "Checks Java, the build, the entities, the database connection and "
        + "locking, and explains how to fix anything that fails. Read-only.", schema(),
        args -> List.of("doctor", "--json"));
    tool("verify", "Applies all migrations to a throwaway database (H2, a temporary SQLite "
        + "file, or a Docker container of the project's database), validates the result "
        + "against the entities and tests the rollback scripts. Slow (can take minutes); never "
        + "touches the configured database.",
        schema("skipRollbacks", "boolean", "Skip the rollback round trip"),
        args -> flagged(List.of("verify", "--json"), args, "skipRollbacks",
            "--skip-rollbacks"));
    tool("show_migration", "Prints one migration file's SQL. Pass the file name, for example "
        + "0002_add_customer_email.sql; for its rollback script pass rollback/<file name>.",
        schema("name", "string", "Migration file name"),
        args -> List.of("sql", required(args, "name")));
    if (allowGenerate) {
      tool("generate", "Writes a new migration (SQL plus rollback script) for the entity "
          + "changes. Run 'plan' first and agree the result with the person. Pass renames "
          + "explicitly, or columns are dropped and added. Writes files only; the person "
          + "applies them with 'migrax migrate'.",
          generateSchema(), McpServer::generateCommand);
    }
  }

  private void tool(String name, String description, Map<String, Object> schema,
                    Function<Map<String, Object>, List<String>> command) {
    tools.put(name, new Tool(description, schema, command));
  }

  private static Map<String, Object> schema() {
    return JsonOut.object("type", "object", "properties", new LinkedHashMap<>());
  }

  private static Map<String, Object> schema(String property, String type, String description) {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put(property, JsonOut.object("type", type, "description", description));
    Map<String, Object> schema = JsonOut.object("type", "object", "properties", properties);
    if (type.equals("string")) {
      schema.put("required", List.of(property));
    }
    return schema;
  }

  private static Map<String, Object> generateSchema() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("name", JsonOut.object("type", "string",
        "description", "Migration name, for example add_customer_email"));
    properties.put("renames", JsonOut.object("type", "array", "items",
        JsonOut.object("type", "string"),
        "description", "Column renames as table.old=new"));
    properties.put("renameTables", JsonOut.object("type", "array", "items",
        JsonOut.object("type", "string"), "description", "Table renames as old=new"));
    properties.put("allowDestructive", JsonOut.object("type", "boolean",
        "description", "Allow drops; only after the person confirmed them"));
    return JsonOut.object("type", "object", "properties", properties);
  }

  private static List<String> generateCommand(Map<String, Object> args) {
    List<String> command = new ArrayList<>(List.of("generate", "--no-input"));
    if (args.get("name") instanceof String name && !name.isBlank()) {
      command.add("--name");
      command.add(name);
    }
    for (String rename : strings(args.get("renames"))) {
      command.add("--rename");
      command.add(rename);
    }
    for (String rename : strings(args.get("renameTables"))) {
      command.add("--rename-table");
      command.add(rename);
    }
    if (Boolean.TRUE.equals(args.get("allowDestructive"))) {
      command.add("--allow-destructive");
    }
    return command;
  }

  private static List<String> strings(Object value) {
    List<String> result = new ArrayList<>();
    if (value instanceof List<?> list) {
      list.forEach(item -> result.add(String.valueOf(item)));
    }
    return result;
  }

  private static List<String> flagged(List<String> command, Map<String, Object> args,
                                      String argument, String flag) {
    List<String> result = new ArrayList<>(command);
    if (Boolean.TRUE.equals(args.get(argument))) {
      result.add(flag);
    }
    return result;
  }

  private static String required(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("'" + name + "' is required");
    }
    return text;
  }

  // ------------------------------------------------------------------ protocol

  /** Serves requests until the input ends. */
  void serve(BufferedReader in, PrintStream out) throws IOException {
    String line;
    while ((line = in.readLine()) != null) {
      if (line.isBlank()) {
        continue;
      }
      Object response = handle(line);
      if (response != null) {
        out.println(JsonOut.write(response));
        out.flush();
      }
    }
  }

  /** The response to one message, or null for notifications. */
  Object handle(String line) {
    Object message;
    try {
      message = new Json.Parser(line).parse();
    } catch (RuntimeException e) {
      return error(null, -32700, "Parse error: " + e.getMessage());
    }
    if (!(message instanceof Map<?, ?> request) || !(request.get("method") instanceof String)) {
      return error(null, -32600, "Invalid request");
    }
    Object id = request.get("id");
    String method = (String) request.get("method");
    Map<String, Object> params = map(request.get("params"));
    if (!request.containsKey("id")) {
      return null; // a notification, such as notifications/initialized
    }
    try {
      return switch (method) {
        case "initialize" -> result(id, initialize(params));
        case "ping" -> result(id, new LinkedHashMap<>());
        case "tools/list" -> result(id, JsonOut.object("tools", toolList()));
        case "tools/call" -> callTool(id, params);
        default -> error(id, -32601, "Method not found: " + method);
      };
    } catch (RuntimeException e) {
      log.println("migrax mcp: " + e);
      return error(id, -32603, "Internal error: " + e.getMessage());
    }
  }

  private Map<String, Object> initialize(Map<String, Object> params) {
    Object requested = params.get("protocolVersion");
    String version = requested instanceof String wanted && PROTOCOL_VERSIONS.contains(wanted)
        ? wanted : PROTOCOL_VERSIONS.get(0);
    return JsonOut.object("protocolVersion", version,
        "capabilities", JsonOut.object("tools", JsonOut.object("listChanged", false)),
        "serverInfo", JsonOut.object("name", "migrax", "version", Version.current()),
        "instructions", INSTRUCTIONS);
  }

  private List<Object> toolList() {
    List<Object> list = new ArrayList<>();
    tools.forEach((name, tool) -> list.add(JsonOut.object("name", name,
        "description", tool.description(), "inputSchema", tool.inputSchema())));
    return list;
  }

  private Object callTool(Object id, Map<String, Object> params) {
    Object name = params.get("name");
    Tool tool = name instanceof String toolName ? tools.get(toolName) : null;
    if (tool == null) {
      return error(id, -32602, "Unknown tool: " + name
          + (("generate".equals(name)) ? " (start the server with --allow-generate)" : ""));
    }
    List<String> command;
    try {
      command = new ArrayList<>(tool.command().apply(map(params.get("arguments"))));
    } catch (IllegalArgumentException e) {
      return error(id, -32602, "Invalid arguments: " + e.getMessage());
    }
    command.add("--dir");
    command.add(project.toString());
    ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    int code = Main.run(command.toArray(String[]::new),
        new PrintStream(stdout, true, StandardCharsets.UTF_8),
        new PrintStream(stderr, true, StandardCharsets.UTF_8), null);
    String text = "migrax " + command.get(0) + " exited with code " + code
        + (code == ExitCode.CHANGES_DETECTED ? " (changes found)" : "") + "\n"
        + stdout.toString(StandardCharsets.UTF_8).strip();
    String messages = stderr.toString(StandardCharsets.UTF_8).strip();
    if (!messages.isEmpty()) {
      text += "\n\nMessages:\n" + (messages.length() > 20_000
          ? messages.substring(0, 20_000) + "\n..." : messages);
    }
    return result(id, JsonOut.object(
        "content", List.of(JsonOut.object("type", "text", "text", text)),
        "isError", code == ExitCode.ERROR));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
  }

  private static Map<String, Object> result(Object id, Object result) {
    return JsonOut.object("jsonrpc", "2.0", "id", id, "result", result);
  }

  private static Map<String, Object> error(Object id, int code, String message) {
    return JsonOut.object("jsonrpc", "2.0", "id", id,
        "error", JsonOut.object("code", code, "message", message));
  }
}
