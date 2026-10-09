package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.util.Json;

import java.io.PrintStream;
import java.util.List;

/** {@code migrax inspect}: print the schema read from your entities as JSON. */
final class InspectCommand implements Command {

  @Override
  public String name() {
    return "inspect";
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "Print the schema read from your entities as JSON";
  }

  @Override
  public String usage() {
    return "migrax inspect";
  }

  @Override
  public String description() {
    return "Prints the schema model Migrax builds from the entities.";
  }

  @Override
  public List<String> options() {
    return List.of("--package", "--naming", "--extractor", "--dialect", "--classpath", "--no-build",
        "--refresh");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    PrintStream out = context.out();
    PrintStream err = context.err();
    Dialect dialect = project.dialect(true, err);
    out.println(Json.write(project.extract(dialect).model()));
    return OK;
  }
}
