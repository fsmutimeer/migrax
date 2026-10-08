package io.migrax.plugin;

import io.migrax.cli.Main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Base of every Maven goal: runs the matching {@code migrax} CLI command with the project's
 * runtime classpath, so the Maven plugin and the CLI behave the same.
 */
abstract class AbstractMigraxMojo extends AbstractMojo {
  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  protected MavenProject project;

  /** Project folder. */
  @Parameter(property = "migrax.dir", defaultValue = "${project.basedir}")
  protected File directory;

  /** Package that contains the entities; defaults to the groupId. */
  @Parameter(property = "migrax.package")
  protected String basePackage;

  @Parameter(property = "migrax.url")
  protected String url;

  @Parameter(property = "migrax.user")
  protected String user;

  @Parameter(property = "migrax.password")
  protected String password;

  /** Migration folder, e.g. classpath:db/migration or filesystem:db/sql. */
  @Parameter(property = "migrax.locations")
  protected String locations;

  /** Naming strategy: spring, jpa, jpa-snake or micronaut. Detected from application config if unset. */
  @Parameter(property = "migrax.naming")
  protected String naming;

  /** How entities are read: auto, hibernate or annotations. */
  @Parameter(property = "migrax.extractor")
  protected String extractor;

  /** SQL dialect; detected from the database URL if unset. */
  @Parameter(property = "migrax.dialect")
  protected String dialect;

  /** Comma-separated schemas to migrate one after another (multi-tenant). */
  @Parameter(property = "migrax.schemas")
  protected String schemas;

  /** Package of Java migrations. */
  @Parameter(property = "migrax.javaPackage")
  protected String javaPackage;

  /** Machine-readable output where the command supports it. */
  @Parameter(property = "migrax.json", defaultValue = "false")
  protected boolean json;

  /** The CLI command, e.g. migrate. */
  protected abstract String command();

  /** Command-specific arguments. */
  protected void addArguments(List<String> args) {}

  /** Whether exit code 2 (differences found) fails the build. */
  protected boolean failOnDifferences() {
    return true;
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    List<String> args = new ArrayList<>();
    args.add(command());
    addArguments(args);
    option(args, "--dir", directory == null ? null : directory.getAbsolutePath());
    option(args, "--package", basePackage);
    option(args, "--url", url);
    option(args, "--user", user);
    option(args, "--password", password);
    option(args, "--locations", locations);
    option(args, "--naming", naming);
    option(args, "--extractor", extractor);
    option(args, "--schemas", schemas);
    option(args, "--java-package", javaPackage);
    if (usesDialect()) {
      option(args, "--dialect", dialect);
    }
    flag(args, "--json", json);
    try {
      option(args, "--classpath", String.join(File.pathSeparator,
          project.getRuntimeClasspathElements()));
    } catch (Exception e) {
      throw new MojoExecutionException("Could not resolve the runtime classpath.", e);
    }
    flag(args, "--verbose", getLog().isDebugEnabled());

    Log log = getLog();
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int code;
    try (PrintStream out = new PrintStream(new LineLog(log, false), true, StandardCharsets.UTF_8);
         PrintStream err = new PrintStream(new TeeLog(log, errors), true, StandardCharsets.UTF_8)) {
      code = Main.run(args.toArray(String[]::new), json ? System.out : out, err);
    }
    if (code == 1 || code == 2 && failOnDifferences()) {
      String message = mavenHints(errors.toString(StandardCharsets.UTF_8).strip());
      throw new MojoFailureException(message.isEmpty()
          ? "migrax " + command() + " failed (exit code " + code + ")." : message);
    }
  }

  /** Commands that accept --dialect. */
  protected boolean usesDialect() {
    return true;
  }

  /** Rewrites the CLI's hints, such as 'migrax generate --allow-destructive', for Maven. */
  static String mavenHints(String text) {
    return text
        .replace("--allow-destructive", "-Dmigrax.allowDestructive=true")
        .replace("--rename-table ", "-Dmigrax.renameTables=")
        .replace("--rename ", "-Dmigrax.renames=")
        .replaceAll("'migrax (generate|migrate|check|plan|status|drift|lint|verify|merge|squash"
            + "|rollback|repair|new|import)\\b", "'mvn migrax:$1");
  }

  protected static void option(List<String> args, String name, String value) {
    if (value != null && !value.isBlank()) {
      args.add(name);
      args.add(value);
    }
  }

  protected static void flag(List<String> args, String name, boolean set) {
    if (set) {
      args.add(name);
    }
  }

  /** Forwards complete lines to the Maven log. */
  private static class LineLog extends OutputStream {
    private final Log log;
    private final boolean warn;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    LineLog(Log log, boolean warn) {
      this.log = log;
      this.warn = warn;
    }

    @Override
    public void write(int b) {
      if (b == '\n') {
        flushLine();
      } else if (b != '\r') {
        line.write(b);
      }
    }

    @Override
    public void flush() {
      // Lines are forwarded when complete.
    }

    @Override
    public void close() {
      flushLine();
    }

    private void flushLine() {
      if (line.size() > 0) {
        String text = mavenHints(line.toString(StandardCharsets.UTF_8));
        if (warn) {
          log.warn(text);
        } else {
          log.info(text);
        }
        line.reset();
      }
    }
  }

  /** Error output: logged as warnings and kept for the failure message. */
  private static final class TeeLog extends LineLog {
    private final ByteArrayOutputStream copy;

    TeeLog(Log log, ByteArrayOutputStream copy) {
      super(log, true);
      this.copy = copy;
    }

    @Override
    public void write(int b) {
      copy.write(b);
      super.write(b);
    }
  }
}
