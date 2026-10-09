package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import java.io.PrintStream;

/** {@code migrax help}: the command list, or the help page of one command. */
final class HelpCommand implements Command {
  private final CommandRegistry commands;

  HelpCommand(CommandRegistry commands) {
    this.commands = commands;
  }

  @Override
  public String name() {
    return "help";
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "Show help for a command";
  }

  @Override
  public String usage() {
    return "migrax help [command]";
  }

  @Override
  public String description() {
    return "Shows general help, or help for one command.";
  }

  @Override
  public boolean needsProject() {
    return false;
  }

  @Override
  public int run(CommandContext context) {
    Args args = context.args();
    if (args.positionals.size() > 1) {
      printCommand(context.out(), commands.resolve(args.positionals.get(1)));
    } else {
      printOverview(context.out());
    }
    return OK;
  }

  /** {@code migrax help}: every command by group, and the common options. */
  void printOverview(PrintStream out) {
    out.println("Migrax " + Version.current()
        + " - automatic database migrations for JPA/Hibernate projects");
    out.println();
    out.println("Usage: migrax <command> [options]");
    for (Group group : Group.values()) {
      out.println();
      out.println(group.title + ":");
      for (Command command : commands.all()) {
        if (command.group() == group) {
          String aliases = command.aliases().isEmpty() ? ""
              : " (alias: " + String.join(", ", command.aliases()) + ")";
          out.printf("  %-12s %s%s%n", command.name(), command.summary(), aliases);
        }
      }
    }
    out.println();
    out.println("Common options:");
    out.println("  --dir <path>          Project folder (default: current folder)");
    out.println("  --no-build            Do not run Maven/Gradle; use already-compiled classes");
    out.println("  --json                Machine-readable output (plan, status, check, lint, ...)");
    out.println("  --verbose, -v         Show debug output and stack traces");
    out.println();
    out.println("Run Migrax from your service folder. It compiles the project and finds its");
    out.println("dependencies and database settings (application.properties/yml) by itself.");
    out.println("Run 'migrax help <command>' for details.");
  }

  /** {@code migrax help <command>}: usage, description and options. */
  void printCommand(PrintStream out, Command command) {
    out.println("Usage: " + command.usage());
    if (!command.aliases().isEmpty()) {
      out.println("Alias: " + String.join(", ", command.aliases()));
    }
    out.println();
    out.println(command.description().strip());
    if (!command.options().isEmpty()) {
      out.println();
      out.println("Options:");
      for (String option : command.options()) {
        String text = Options.help(option);
        if (text != null) {
          out.println("  " + text);
        }
      }
      out.println("  " + Options.help("--dir"));
    }
  }
}
