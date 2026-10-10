package io.migrax.cli;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The commands {@code migrax} knows, found by name or alias. */
final class CommandRegistry {
  private final Map<String, Command> commands = new LinkedHashMap<>();
  private final HelpCommand help = new HelpCommand(this);

  /**
   * Every built-in command. Within a {@link Command.Group}, {@code migrax help} lists commands in
   * this order.
   */
  static CommandRegistry standard() {
    CommandRegistry registry = new CommandRegistry();
    registry.add(new InitCommand());
    registry.add(new DoctorCommand());
    registry.add(new GenerateCommand());
    registry.add(new MigrateCommand());
    registry.add(new StatusCommand());
    registry.add(new PlanCommand());
    registry.add(new RollbackCommand());
    registry.add(new CheckCommand());
    registry.add(new LintCommand());
    registry.add(new VerifyCommand());
    registry.add(new DriftCommand());
    registry.add(new ImportCommand());
    registry.add(new SquashCommand());
    registry.add(new MergeCommand());
    registry.add(new NewCommand());
    registry.add(new RepairCommand());
    registry.add(new CleanCommand());
    registry.add(new InspectCommand());
    registry.add(new SqlCommand());
    registry.add(new VersionCommand());
    registry.add(registry.help);
    return registry;
  }

  void add(Command command) {
    if (commands.putIfAbsent(command.name(), command) != null) {
      throw new IllegalArgumentException("Duplicate command " + command.name());
    }
  }

  Collection<Command> all() {
    return Collections.unmodifiableCollection(commands.values());
  }

  /** The {@code help} command, which also prints the pages for {@code --help}. */
  HelpCommand help() {
    return help;
  }

  /**
   * The command with this name or alias.
   *
   * @throws UsageException with a "did you mean" hint when there is none
   */
  Command resolve(String name) {
    String normalized = name.toLowerCase(Locale.ROOT);
    for (Command command : commands.values()) {
      if (command.name().equals(normalized) || command.aliases().contains(normalized)) {
        return command;
      }
    }
    List<String> names = new ArrayList<>();
    for (Command command : commands.values()) {
      names.add(command.name());
      names.addAll(command.aliases());
    }
    String suggestion = Suggestions.closest(normalized, names);
    throw new UsageException("Unknown command '" + name + "'.",
        (suggestion == null ? "" : "Did you mean 'migrax " + suggestion + "'? ")
            + "Run 'migrax help' to see the available commands.");
  }
}
