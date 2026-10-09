package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;

/** {@code migrax sql}: print a migration file. */
final class SqlCommand implements Command {

  @Override
  public String name() {
    return "sql";
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "Print a migration file";
  }

  @Override
  public String usage() {
    return "migrax sql <migration>";
  }

  @Override
  public String description() {
    return "Prints a migration file from the migration folder.";
  }

  @Override
  public List<String> options() {
    return List.of("--locations");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing the migration file.", "Usage: migrax sql <migration>");
    }
    out.print(Files.readString(MigrationFiles.find(project, args.positionals.get(1))));
    return OK;
  }
}
