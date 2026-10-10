package io.migrax.cli;

import io.migrax.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Command line interface: {@code migrax <command> [options]}. Parses the arguments, runs the
 * {@link Command} from {@link CommandRegistry} and turns errors into messages and exit codes.
 *
 * <p>Exit codes: 0 success, 1 error, 2 differences found ({@code check}, {@code drift}).
 */
public final class Main {

  /** Strong reference so the level set in {@link #quietHibernateLogging} is kept. */
  private static final java.util.logging.Logger HIBERNATE_LOG =
      java.util.logging.Logger.getLogger("org.hibernate");

  private Main() {}

  public static void main(String[] args) {
    // Only in the standalone CLI process: run() also executes inside Maven and inside
    // applications (Spring Boot starter), whose logging must stay as configured.
    quietHibernateLogging(List.of(args).contains("--verbose") || List.of(args).contains("-v"));
    BufferedReader in = System.console() == null ? null
        : new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    System.exit(run(args, System.out, System.err, in));
  }

  /** Runs one command without asking questions and returns its exit code. */
  public static int run(String[] rawArgs, PrintStream out, PrintStream err) {
    return run(rawArgs, out, err, null);
  }

  /**
   * Runs one command and returns its exit code. Never calls {@link System#exit}.
   *
   * @param in answers to questions, or null when Migrax must not ask
   */
  public static int run(String[] rawArgs, PrintStream out, PrintStream err, BufferedReader in) {
    return run(rawArgs, out, err, in, Services.standard());
  }

  /** Runs one command with the given services; tests pass their own. */
  static int run(String[] rawArgs, PrintStream out, PrintStream err, BufferedReader in,
                 Services services) {
    List<String> raw = List.of(rawArgs);
    boolean verbose = raw.contains("--verbose") || raw.contains("-v");
    boolean json = raw.contains("--json");
    // In --json mode progress messages go to stderr so stdout stays parseable.
    Log.Sink previous = Log.setSink(Log.console(json ? err : out, err, verbose));
    try {
      Args args = Args.parse(rawArgs);
      BufferedReader input = args.flag("--no-input") ? null : in;
      return dispatch(CommandRegistry.standard(), args, out, err, input, services);
    } catch (UsageException e) {
      String hint = e.hint != null ? e.hint : "Run 'migrax help' to see the available commands.";
      err.println("error[" + e.code.code + "]: " + e.getMessage());
      err.println(hint);
      printJsonError(json, out, e.code, e.getMessage(), hint);
      return ExitCode.ERROR;
    } catch (Throwable e) {
      ErrorCode code = ErrorCode.of(e);
      String message = Errors.describe(e);
      err.println("error[" + code.code + "]: " + message);
      String hint = Errors.hint(e);
      if (hint != null) {
        err.println(hint);
      }
      printJsonError(json, out, code, message, hint);
      if (verbose) {
        e.printStackTrace(err);
      } else {
        err.println("Run again with --verbose for details.");
      }
      return ExitCode.ERROR;
    } finally {
      Log.setSink(previous);
    }
  }

  /** In --json mode a failure is also one JSON object on stdout. */
  private static void printJsonError(boolean json, PrintStream out, ErrorCode code,
                                     String message, String hint) {
    if (json) {
      out.println(JsonOut.write(JsonOut.object("ok", false, "exitCode", ExitCode.ERROR,
          "error", JsonOut.object("code", code.code, "message", message, "hint", hint))));
    }
  }

  private static int dispatch(CommandRegistry commands, Args args, PrintStream out,
                              PrintStream err, BufferedReader in, Services services)
      throws Exception {
    CommandContext withoutProject = new CommandContext(args, out, err, in, null, services);
    if (args.flag("--version", "-V")) {
      return commands.resolve("version").run(withoutProject);
    }
    if (args.command == null) {
      commands.help().printOverview(out);
      return ExitCode.OK;
    }
    Command command = commands.resolve(args.command);
    if (args.flag("--help", "-h")) {
      commands.help().printCommand(out, command);
      return ExitCode.OK;
    }
    if (!command.needsProject()) {
      return command.run(withoutProject);
    }
    boolean collect = args.flag("--json") && command.jsonResult();
    try (Project project = new Project(args, err)) {
      CommandContext context = new CommandContext(args, out, err, in, project, services)
          .forCommand(command);
      int code = command.run(context);
      if (collect) {
        java.util.Map<String, Object> result = JsonOut.object("ok", code == ExitCode.OK,
            "exitCode", code);
        result.putAll(context.results());
        out.println(JsonOut.write(result));
      }
      return code;
    }
  }

  /**
   * Hides Hibernate's boot banner and hints, which it logs while Migrax reads the mapping;
   * Migrax reports real mapping problems itself. JBoss Logging is pointed at java.util.logging,
   * because otherwise it picks the application's SLF4J/Logback (Micronaut, Spring), whose
   * default configuration prints every Hibernate DEBUG line.
   */
  static void quietHibernateLogging(boolean verbose) {
    HIBERNATE_LOG.setLevel(verbose ? null : java.util.logging.Level.SEVERE);
    if (System.getProperty("org.jboss.logging.provider") == null) {
      System.setProperty("org.jboss.logging.provider", "jdk");
    }
  }
}
