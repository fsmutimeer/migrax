package org.migrax.cli;

import org.migrax.plugin.ProjectDatabaseConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The environments {@code migrax environments} shows: {@code migrax.environments.<name>.*}, or
 * {@code migrax.databases.<db>.environments.<name>.*} for a further database.
 */
final class Environments {
  /** One environment's database, and its name for {@code -- migrax:env} migrations. */
  record Environment(String name, String url, String user, String password, String env) {}

  private Environments() {}

  /**
   * The environments in the order given by {@code chosen} (a comma-separated list, which also
   * selects them) or the {@code migrax.environments} setting; otherwise every configured one,
   * in alphabetical order.
   */
  static List<Environment> of(Project project, String chosen) throws Exception {
    Map<String, String> config = ProjectDatabaseConfig.settings(project.resources());
    String prefix = project.database() == null ? "migrax.environments."
        : "migrax.databases." + project.database() + ".environments.";
    Map<String, String> found = configured(config, prefix);
    String list = Project.blank(chosen)
        ? ProjectDatabaseConfig.keySetting(config, prefix.substring(0, prefix.length() - 1))
        : chosen;
    List<String> names = new ArrayList<>();
    if (Project.blank(list)) {
      names.addAll(found.values());
    } else {
      for (String name : list.split(",")) {
        String trimmed = name.trim();
        if (!trimmed.isEmpty() && !names.contains(trimmed)) {
          names.add(trimmed);
        }
      }
    }
    if (names.isEmpty()) {
      throw new UsageException(ErrorCode.NO_ENVIRONMENTS, "No environments are configured.",
          "Give each one a URL, for example " + prefix + "staging.url in the application "
              + "config or " + ProjectDatabaseConfig.environmentVariable(prefix + "staging.url")
              + " in the environment.");
    }
    List<Environment> environments = new ArrayList<>();
    for (String name : names) {
      environments.add(load(project, config, prefix, name, found));
    }
    return environments;
  }

  /**
   * The names in the settings and in variables such as MIGRAX_ENVIRONMENTS_STAGING_URL, keyed
   * by their variable spelling, so prod-eu in the config and PROD_EU in a variable are one.
   */
  private static Map<String, String> configured(Map<String, String> config, String prefix) {
    Map<String, String> names = new HashMap<>();
    List<String> keys = new ArrayList<>(config.keySet());
    keys.addAll(System.getProperties().stringPropertyNames());
    for (String key : keys) {
      if (key.startsWith(prefix)) {
        String rest = key.substring(prefix.length());
        int dot = rest.indexOf('.');
        String name = dot < 0 ? rest : rest.substring(0, dot);
        names.put(ProjectDatabaseConfig.environmentVariable(checked(name)), name);
      }
    }
    String variables = ProjectDatabaseConfig.environmentVariable(prefix);
    for (String variable : System.getenv().keySet()) {
      if (variable.startsWith(variables) && variable.endsWith("_URL")
          && variable.length() > variables.length() + 4) {
        String spelled = variable.substring(variables.length(), variable.length() - 4);
        names.putIfAbsent(spelled, spelled.toLowerCase(Locale.ROOT));
      }
    }
    // Sorted by name, not by the variable spelling.
    Map<String, String> sorted = new LinkedHashMap<>();
    names.values().stream().sorted().forEach(name ->
        sorted.put(ProjectDatabaseConfig.environmentVariable(name), name));
    return sorted;
  }

  private static Environment load(Project project, Map<String, String> config, String prefix,
                                  String name, Map<String, String> found) throws Exception {
    checked(name);
    String key = prefix + name + ".";
    String url = ProjectDatabaseConfig.keySetting(config, key + "url");
    if (Project.blank(url)) {
      throw new UsageException(ErrorCode.NO_ENVIRONMENTS,
          "No URL found for environment '" + name + "'.",
          found.isEmpty() ? "Set " + key + "url or "
              + ProjectDatabaseConfig.environmentVariable(key + "url") + "."
              : "Configured: " + String.join(", ", found.values()) + ".");
    }
    // As for the default database: a password given directly wins over a file.
    String password = ProjectDatabaseConfig.keySetting(config, key + "password");
    String passwordFile = ProjectDatabaseConfig.keySetting(config, key + "password-file");
    if (Project.blank(password) && !Project.blank(passwordFile)) {
      try {
        password = Files.readString(Path.of(passwordFile.trim())).replaceAll("\\R+$", "");
      } catch (IOException | InvalidPathException e) {
        throw new UsageException("Could not read the file that " + key + "password-file "
            + "names (" + passwordFile.trim() + ").",
            "Check the path; a mounted secret must be readable by this user.");
      }
    }
    String env = ProjectDatabaseConfig.keySetting(config, key + "env");
    return new Environment(name, Project.resolveRelativeDatabasePath(url.trim(), project.root()),
        ProjectDatabaseConfig.keySetting(config, key + "user"), password,
        Project.blank(env) ? name : env.trim());
  }

  private static String checked(String name) {
    if (!name.matches("[A-Za-z0-9_-]+")) {
      throw new UsageException("Environment name '" + name + "': use letters, digits, '-' "
          + "and '_', as in migrax.environments.staging.url.", null);
    }
    return name;
  }
}
