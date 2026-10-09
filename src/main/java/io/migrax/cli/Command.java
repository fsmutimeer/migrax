package io.migrax.cli;

import java.util.List;

/**
 * One {@code migrax <command>}. To add a command, implement this interface in a new
 * {@code *Command} class and register it in {@link CommandRegistry#standard()}; its help page,
 * aliases and typo suggestions then work without further changes.
 */
interface Command {

  /** Where {@code migrax help} lists a command. */
  enum Group {
    GETTING_STARTED("Getting started"),
    EVERYDAY("Everyday"),
    SAFETY("Safety and CI"),
    MAINTENANCE("Maintenance");

    final String title;

    Group(String title) {
      this.title = title;
    }
  }

  /** The name typed after {@code migrax}, in lower case. */
  String name();

  /** Other names for the command. */
  default List<String> aliases() {
    return List.of();
  }

  Group group();

  /** One line for the command list in {@code migrax help}. */
  String summary();

  /** The usage line of {@code migrax help <command>}. */
  String usage();

  /** The paragraph of {@code migrax help <command>}. */
  String description();

  /** The options {@code migrax help <command>} lists; their texts are in {@link Options}. */
  default List<String> options() {
    return List.of();
  }

  /** False for commands that don't look at the project, such as {@code version}. */
  default boolean needsProject() {
    return true;
  }

  /**
   * Runs the command.
   *
   * @return the exit code, one of {@link ExitCode}
   * @throws UsageException for mistakes the user can fix; Migrax prints them without a trace
   */
  int run(CommandContext context) throws Exception;
}
