package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

/** {@code migrax version}: print the Migrax version. */
final class VersionCommand implements Command {

  @Override
  public String name() {
    return "version";
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "Print the Migrax version";
  }

  @Override
  public String usage() {
    return "migrax version";
  }

  @Override
  public String description() {
    return "Prints the Migrax version.";
  }

  @Override
  public boolean needsProject() {
    return false;
  }

  @Override
  public int run(CommandContext context) {
    context.out().println("migrax " + Version.current());
    return OK;
  }
}
