package org.migrax.cli;

import static org.migrax.cli.ExitCode.OK;

import org.migrax.runner.MigrationLoader;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** {@code migrax new}: create an empty SQL or Java migration. */
final class NewCommand implements Command {

  @Override
  public String name() {
    return "new";
  }

  @Override
  public Group group() {
    return Group.EVERYDAY;
  }

  @Override
  public String summary() {
    return "Create an empty SQL or Java migration";
  }

  @Override
  public String usage() {
    return "migrax new <name> [--java]";
  }

  @Override
  public String description() {
    return """
        Creates the next numbered SQL file for hand-written changes such as data migrations,
        with an empty rollback script. --java creates a JavaMigration class instead.""";
  }

  @Override
  public boolean jsonResult() {
    return true;
  }

  @Override
  public List<String> options() {
    return List.of("--json", "--java", "--java-package", "--locations", "--database");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing the migration name.", "Usage: migrax new <name> [--java]");
    }
    String description = args.positionals.get(1).trim().toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    if (description.isEmpty()) {
      throw new UsageException("The migration name needs letters or digits.", null);
    }
    Path folder = project.migrations();
    Files.createDirectories(folder);
    int next = project.nextNumber();
    String number = String.format(Locale.ROOT, "%04d", next);
    if (args.flag("--java")) {
      String javaPackage = project.javaSourcePackage();
      if (javaPackage == null) {
        throw new UsageException("Invalid Java package '" + project.javaPackage() + "'.",
            "Set --java-package or migrax.java-package to a package name such as db.migration.");
      }
      Path javaFolder = project.javaMigrationFolder();
      StringBuilder camel = new StringBuilder();
      for (String part : description.split("_")) {
        if (!part.isEmpty()) {
          camel.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
      }
      String className = "V" + number + "__" + camel;
      Files.createDirectories(javaFolder);
      Path file = javaFolder.resolve(className + ".java");
      Files.writeString(file, """
          package PACKAGE_NAME;

          import org.migrax.api.JavaMigration;
          import java.sql.Connection;

          /** Migration MIGRATION_NUMBER, written by hand. */
          public class CLASS_NAME implements JavaMigration {
            @Override
            public void migrate(Connection connection) throws Exception {
              try (var statement = connection.createStatement()) {
                // statement.executeUpdate("UPDATE ...");
              }
            }

            @Override
            public void rollback(Connection connection) throws Exception {
              // Undo migrate(), or delete this method if it cannot be undone.
            }
          }
          """.replace("PACKAGE_NAME", javaPackage).replace("MIGRATION_NUMBER", number)
          .replace("CLASS_NAME", className));
      out.println("Created " + project.display(file) + ".");
      context.result("created", List.of(project.display(file)));
      out.println("Add org.migrax:migrax as a provided dependency so the class compiles; it runs "
          + "in version order with the SQL migrations.");
      return OK;
    }
    String name = number + "_" + description + ".sql";
    Path file = folder.resolve(name);
    Files.writeString(file, "-- " + description.replace('_', ' ') + "\n"
        + "-- Written by hand: Migrax does not add these changes to the entity snapshot.\n\n");
    Path rollback = folder.resolve(MigrationLoader.ROLLBACK_FOLDER).resolve(name);
    Files.createDirectories(rollback.getParent());
    Files.writeString(rollback, "-- Rollback for " + name + ". 'migrax rollback' runs it.\n\n");
    out.println("Created " + project.display(file) + " and " + project.display(rollback) + ".");
    context.result("created", List.of(project.display(file), project.display(rollback)));
    return OK;
  }
}
