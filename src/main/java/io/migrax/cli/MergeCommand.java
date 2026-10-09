package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.diff.SnapshotStore;
import io.migrax.runner.MigrationLoader;
import io.migrax.util.Log;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code migrax merge}: fix migrations that two branches numbered the same. */
final class MergeCommand implements Command {

  @Override
  public String name() {
    return "merge";
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "Fix migrations that two branches numbered the same";
  }

  @Override
  public String usage() {
    return "migrax merge";
  }

  @Override
  public String description() {
    return """
        Renumbers migrations that share a number after a git merge (the one added later moves
        to the next free number) and rebuilds a snapshot that has merge conflicts.""";
  }

  @Override
  public List<String> options() {
    return List.of("--dialect", "--package", "--naming", "--extractor", "--url", "--locations",
        "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    PrintStream out = context.out();
    Path folder = project.migrations();
    Map<String, List<String>> duplicates = DuplicateMigrations.find(folder);
    Set<String> applied = new HashSet<>();
    if (!duplicates.isEmpty() && project.hasUrl()) {
      try (Connection connection = project.connect()) {
        context.migrationRunner().applied(connection).forEach(m -> applied.add(m.version()));
      } catch (Exception e) {
        Log.warn("Could not read the migration history ({}); renaming anyway.", Errors.describe(e));
      }
    }
    int settled = 0;
    for (Iterator<List<String>> groups = duplicates.values().iterator(); groups.hasNext(); ) {
      List<String> group = groups.next();
      if (applied.containsAll(group)) {
        out.println(String.join(" and ", group) + " are all applied already; they run in "
            + "filename order, so they are left as they are.");
        groups.remove();
        settled++;
      }
    }
    int highest = 0;
    int width = 4;
    Pattern leading = Pattern.compile("^(\\d+)_");
    for (Path file : MigrationLoader.sqlFiles(folder)) {
      Matcher matcher = leading.matcher(file.getFileName().toString());
      if (matcher.find()) {
        highest = Math.max(highest, Integer.parseInt(matcher.group(1)));
        width = Math.max(width, matcher.group(1).length());
      }
    }
    int renamed = 0;
    for (List<String> group : duplicates.values()) {
      // Applied files keep their names; of the rest, the one added first keeps the number.
      List<String> ordered = new ArrayList<>(group);
      ordered.sort(Comparator.comparing((String name) -> !applied.contains(name))
          .thenComparingLong(name -> addedAt(project, folder.resolve(name))));
      for (String name : ordered.subList(1, ordered.size())) {
        if (applied.contains(name)) {
          throw new UsageException(name + " is already applied in the configured database.",
              "Renaming it would break that history. Rename the other migration by hand.");
        }
        Matcher matcher = Pattern.compile("^(\\d+)(_.*)$").matcher(name);
        if (!matcher.matches()) {
          throw new UsageException("Cannot renumber " + name + " automatically.",
              "Rename it to the next free number by hand.");
        }
        String newName = String.format(Locale.ROOT, "%0" + width + "d", ++highest)
            + matcher.group(2);
        Files.move(folder.resolve(name), folder.resolve(newName));
        Path rollback = MigrationLoader.rollbackScript(folder, name);
        if (rollback != null) {
          Files.move(rollback, rollback.resolveSibling(newName));
        }
        Path history = SnapshotStore.historySnapshot(project.root(), name);
        if (Files.exists(history)) {
          Files.move(history, SnapshotStore.historySnapshot(project.root(), newName));
        }
        out.println("Renamed " + name + " to " + newName + ".");
        renamed++;
      }
    }
    boolean rebuilt = false;
    if (Files.exists(project.snapshot())) {
      try {
        SnapshotStore.load(project.snapshot());
      } catch (IllegalStateException e) {
        Dialect dialect = project.dialect(false, out);
        SnapshotStore.save(project.snapshot(), project.extract(dialect).model(), dialect.id(),
            project.recordedNaming());
        out.println("Rebuilt " + project.display(project.snapshot())
            + " from the merged entities (it had merge conflicts).");
        rebuilt = true;
      }
    }
    if (renamed == 0 && !rebuilt) {
      if (settled > 0) {
        return OK;
      }
      out.println("Nothing to merge: migration numbers are unique and the snapshot is valid.");
      return OK;
    }
    out.println("Next: run 'migrax check' (no changes expected) and 'migrax verify' to prove the "
        + "merged migrations produce the entity schema.");
    return OK;
  }

  /** When git first added the file, or its modification time. */
  private static long addedAt(Project project, Path file) {
    try {
      Process process = new ProcessBuilder("git", "log", "--diff-filter=A", "--format=%ct", "-1",
          "--", project.root().relativize(file.toAbsolutePath()).toString())
          .directory(project.root().toFile()).redirectErrorStream(true).start();
      process.getOutputStream().close();
      String output = new String(process.getInputStream().readAllBytes(),
          StandardCharsets.UTF_8).trim();
      if (process.waitFor() == 0 && output.matches("\\d+")) {
        return Long.parseLong(output) * 1000;
      }
    } catch (java.io.IOException ignored) {
      // Not a git checkout: fall back to the file time.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      return Files.getLastModifiedTime(file).toMillis();
    } catch (java.io.IOException e) {
      return Long.MAX_VALUE;
    }
  }
}
