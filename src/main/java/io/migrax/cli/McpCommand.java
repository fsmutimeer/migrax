package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** {@code migrax mcp}: let AI assistants use Migrax through the Model Context Protocol. */
final class McpCommand implements Command {

  @Override
  public String name() {
    return "mcp";
  }

  @Override
  public Group group() {
    return Group.GETTING_STARTED;
  }

  @Override
  public String summary() {
    return "Let AI assistants use Migrax (MCP server)";
  }

  @Override
  public String usage() {
    return "migrax mcp [--allow-generate]";
  }

  @Override
  public String description() {
    return """
        Runs a Model Context Protocol server on stdin/stdout, so AI assistants such as
        Claude Code can call status, check, plan, lint, drift, doctor, verify and
        show_migration as tools and read their JSON results. It never changes a database:
        migrate, rollback, repair and clean are not offered. --allow-generate also offers
        generate, which writes migration files for the person to review.
        Claude Code: claude mcp add migrax -- migrax mcp""";
  }

  @Override
  public List<String> options() {
    return List.of("--allow-generate");
  }

  @Override
  public boolean needsProject() {
    return false;
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Args args = context.args();
    Path project = Path.of(args.option("--dir", ".")).toAbsolutePath().normalize();
    // Stdout carries the protocol, in UTF-8 whatever the console's code page; anything else
    // that prints to System.out while the server runs goes to stderr instead.
    PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
        StandardCharsets.UTF_8);
    PrintStream original = System.out;
    System.setOut(System.err);
    try {
      context.err().println("migrax mcp: serving " + project + " on stdin/stdout"
          + (args.flag("--allow-generate") ? " (generate allowed)" : " (read-only)") + ".");
      new McpServer(project, args.flag("--allow-generate"), context.err()).serve(
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), protocol);
    } finally {
      System.setOut(original);
    }
    return OK;
  }
}
