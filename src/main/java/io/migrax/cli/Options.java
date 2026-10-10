package io.migrax.cli;

import io.migrax.dialect.Dialects;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Every command line option: which ones take a value, and the help text of each. A new option
 * goes into {@link #VALUES} or {@link #FLAGS} and gets a help line here; commands list it in
 * {@link Command#options()}.
 */
final class Options {
  /** Options followed by a value. */
  static final Set<String> VALUES = Set.of(
      "--package", "--dir", "--locations", "--dialect", "--url", "--user", "--password",
      "--classpath", "--name", "--schema", "--action", "--naming", "--extractor", "--rename",
      "--rename-table", "--to", "--steps", "--image", "--java-package", "--schemas", "--table",
      "--lock-timeout", "--password-file");
  /** Options without a value. */
  static final Set<String> FLAGS = Set.of(
      "--allow-destructive", "--resume", "--dry-run", "--verbose", "-v", "--no-build",
      "--refresh", "--yes", "-y", "--help", "-h", "--version", "-V", "--json", "--safe",
      "--impact", "--strict", "--no-input", "--optimize", "--java", "--entities",
      "--skip-rollbacks", "--all", "--allow-generate");

  private static final Map<String, String> HELP = new LinkedHashMap<>();

  static {
    HELP.put("--dir", "--dir <path>            Project folder (default: current folder)");
    HELP.put("--package", "--package <name>        Entity package (default: MIGRAX_PACKAGE or pom groupId)");
    HELP.put("--name", "--name <name>           Migration file name (default: next number + description)");
    HELP.put("--allow-destructive", "--allow-destructive     Allow drops and narrowing type changes");
    HELP.put("--safe",
        "--safe                  Non-blocking indexes and constraints (PostgreSQL, CockroachDB)");
    HELP.put("--rename", "--rename t.old=new      Treat a column change as a rename (comma-separated)");
    HELP.put("--rename-table", "--rename-table old=new  Treat a table change as a rename");
    HELP.put("--no-input", "--no-input              Never ask questions");
    HELP.put("--dialect", "--dialect <name>        " + String.join(", ", Dialects.NAMES));
    HELP.put("--naming", "--naming <strategy>     spring, jpa, jpa-snake or micronaut (default: detected)");
    HELP.put("--extractor", "--extractor <mode>      auto, hibernate or annotations (default: auto)");
    HELP.put("--url", "--url <jdbc-url>        Database URL (default: application config)");
    HELP.put("--user", "--user <name>           Database user");
    HELP.put("--password", "--password <secret>     Database password (prefer env vars)");
    HELP.put("--password-file",
        "--password-file <path>  Read the database password from a file (mounted secret)");
    HELP.put("--schema", "--schema <name>         Database schema to read");
    HELP.put("--schemas", "--schemas <a,b>         Run for each schema (multi-tenant)");
    HELP.put("--locations", "--locations <path>      Migration folder, e.g. filesystem:db/sql");
    HELP.put("--java-package", "--java-package <pkg>    Package of Java migrations (default: db.migration)");
    HELP.put("--classpath", "--classpath <paths>     Extra classpath; skips Maven/Gradle resolution");
    HELP.put("--no-build", "--no-build              Do not run Maven/Gradle; use compiled classes");
    HELP.put("--refresh", "--refresh               Re-resolve dependencies even if cached");
    HELP.put("--dry-run", "--dry-run               Show what would happen without doing it");
    HELP.put("--allow-generate",
        "--allow-generate        Also offer the generate tool (writes migration files)");
    HELP.put("--lock-timeout",
        "--lock-timeout <time>   Wait this long while another process migrates (e.g. 2m)");
    HELP.put("--resume", "--resume                Re-run a failed resume-safe migration");
    HELP.put("--steps", "--steps <n>             Number of migrations to roll back");
    HELP.put("--to", "--to <migration>        Last migration to keep / squash up to");
    HELP.put("--action", "--action <action>       applied, retry or forget");
    HELP.put("--yes", "--yes, -y               Confirm without asking");
    HELP.put("--json", "--json                  Machine-readable output");
    HELP.put("--impact", "--impact                Show affected table sizes (needs the database)");
    HELP.put("--strict", "--strict                Fail on warnings too");
    HELP.put("--all", "--all                   Lint every migration, not only pending ones");
    HELP.put("--image", "--image <image>         Docker image for the throwaway database");
    HELP.put("--skip-rollbacks", "--skip-rollbacks        Do not test rollback scripts");
    HELP.put("--entities", "--entities              Compare with the entities instead of the snapshot");
    HELP.put("--table", "--table <name>          Flyway history table (default: flyway_schema_history)");
    HELP.put("--optimize", "--optimize              Squash to the resulting schema only");
    HELP.put("--java", "--java                  Create a Java migration");
  }

  private Options() {}

  /** The help line of an option, or null. */
  static String help(String option) {
    return HELP.get(option);
  }
}
