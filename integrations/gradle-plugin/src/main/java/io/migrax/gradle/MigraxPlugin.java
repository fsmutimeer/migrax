package io.migrax.gradle;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * Applies with {@code plugins { id("io.migrax") version "0.1.0" }} and adds one task per
 * Migrax command: migraxGenerate, migraxMigrate, migraxStatus, migraxPlan, migraxCheck,
 * migraxRollback, migraxLint, migraxVerify, migraxDrift, migraxSquash, migraxMerge,
 * migraxImport, migraxNew and migraxRepair.
 *
 * <p>Command options can be passed with {@code -Pmigrax.args="--allow-destructive"}.
 */
public class MigraxPlugin implements Plugin<Project> {
  /** Commands that read the entities and therefore need compiled classes. */
  private static final List<String> NEEDS_CLASSES = List.of(
      "generate", "plan", "check", "verify", "drift", "merge", "inspect");
  private static final List<String> COMMANDS = List.of(
      "generate", "migrate", "status", "plan", "check", "rollback", "lint", "verify", "drift",
      "squash", "merge", "import", "new", "repair", "inspect", "doctor");

  @Override
  public void apply(Project project) {
    project.getPluginManager().apply("java");
    MigraxExtension extension = project.getExtensions().create("migrax", MigraxExtension.class);
    extension.getVersion().convention(pluginVersion());

    Configuration cli = project.getConfigurations().create("migrax", configuration -> {
      configuration.setVisible(false);
      configuration.setCanBeConsumed(false);
      configuration.setDescription("The Migrax command line tool.");
      configuration.defaultDependencies(dependencies -> dependencies.add(
          project.getDependencies().create("io.migrax:migrax:" + extension.getVersion().get())));
    });

    for (String command : COMMANDS) {
      String name = "migrax" + Character.toUpperCase(command.charAt(0)) + command.substring(1);
      project.getTasks().register(name, JavaExec.class, task -> {
        task.setGroup("migrax");
        task.setDescription("Runs 'migrax " + command + "'.");
        task.getMainClass().set("io.migrax.cli.Main");
        task.setClasspath(cli);
        task.setStandardInput(System.in);
        if (NEEDS_CLASSES.contains(command)) {
          task.dependsOn("classes");
        }
        task.getArgumentProviders().add(() -> arguments(project, extension, command));
      });
    }
  }

  private static List<String> arguments(Project project, MigraxExtension extension,
                                        String command) {
    List<String> args = new ArrayList<>();
    args.add(command);
    Object extra = project.findProperty("migrax.args");
    if (extra != null && !extra.toString().isBlank()) {
      args.addAll(Arrays.asList(extra.toString().trim().split("\\s+")));
    }
    args.addAll(extension.getArgs().getOrElse(List.of()));
    args.add("--dir");
    args.add(project.getProjectDir().getAbsolutePath());
    SourceSet main = project.getExtensions().getByType(SourceSetContainer.class)
        .getByName(SourceSet.MAIN_SOURCE_SET_NAME);
    args.add("--classpath");
    args.add(main.getRuntimeClasspath().getAsPath());
    option(args, "--package", extension.getPackageName());
    option(args, "--url", extension.getUrl());
    option(args, "--user", extension.getUser());
    option(args, "--password", extension.getPassword());
    option(args, "--locations", extension.getLocations());
    option(args, "--naming", extension.getNaming());
    if (!command.equals("migrate") && !command.equals("status")) {
      option(args, "--dialect", extension.getDialect());
    }
    if (System.console() == null && !args.contains("--no-input")
        && command.toLowerCase(Locale.ROOT).equals("generate")) {
      args.add("--no-input");
    }
    return args;
  }

  private static void option(List<String> args, String name, Property<String> value) {
    if (value.isPresent() && !value.get().isBlank()) {
      args.add(name);
      args.add(value.get());
    }
  }

  private static String pluginVersion() {
    Properties properties = new Properties();
    try (InputStream in = MigraxPlugin.class.getResourceAsStream("/migrax-gradle.properties")) {
      if (in != null) {
        properties.load(in);
      }
    } catch (IOException ignored) {
      // Fall back below.
    }
    return properties.getProperty("version", "0.1.3");
  }
}
