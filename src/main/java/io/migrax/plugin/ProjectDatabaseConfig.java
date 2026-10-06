package io.migrax.plugin;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class ProjectDatabaseConfig {
  private static final List<String> URL_KEYS = List.of(
      "quarkus.datasource.jdbc.url",
      "quarkus.datasource.url",
      "spring.datasource.url",
      "spring.datasource.hikari.jdbc-url",
      "spring.datasource.hikari.jdbcUrl",
      "datasource.url",
      "datasource.jdbc-url",
      "jakarta.persistence.jdbc.url",
      "javax.persistence.jdbc.url",
      "hibernate.connection.url");

  private static final List<String> USER_KEYS = List.of(
      "quarkus.datasource.username",
      "spring.datasource.username",
      "spring.datasource.hikari.username",
      "datasource.username",
      "datasource.user",
      "jakarta.persistence.jdbc.user",
      "javax.persistence.jdbc.user",
      "hibernate.connection.username");

  private static final List<String> PASSWORD_KEYS = List.of(
      "quarkus.datasource.password",
      "spring.datasource.password",
      "spring.datasource.hikari.password",
      "datasource.password",
      "jakarta.persistence.jdbc.password",
      "javax.persistence.jdbc.password",
      "hibernate.connection.password");

  private ProjectDatabaseConfig() {}

  public static Credentials load(Path resourcesDirectory, Map<String, String> overrides)
      throws IOException {
    Map<String, String> config = new LinkedHashMap<>();
    loadProperties(resourcesDirectory.resolve("application.properties"), config);
    loadYaml(resourcesDirectory.resolve("application.yml"), config);
    loadYaml(resourcesDirectory.resolve("application.yaml"), config);

    String profile = firstNonBlank(
        System.getProperty("migrax.profile"), System.getenv("MIGRAX_PROFILE"),
        System.getenv("QUARKUS_PROFILE"),
        System.getenv("SPRING_PROFILES_ACTIVE"),
        resolve(config.get("migrax.profile")),
        resolve(config.get("quarkus.profile")),
        resolve(config.get("spring.profiles.active")));
    if (profile != null) {
      loadProperties(resourcesDirectory.resolve("application-" + profile + ".properties"), config);
      loadYaml(resourcesDirectory.resolve("application-" + profile + ".yml"), config);
      loadYaml(resourcesDirectory.resolve("application-" + profile + ".yaml"), config);
    }

    String url = setting(overrides, "url", "MIGRAX_DATABASE_URL",
        List.of("QUARKUS_DATASOURCE_JDBC_URL", "QUARKUS_DATASOURCE_URL",
            "SPRING_DATASOURCE_URL", "DATABASE_URL", "DB_URL"),
        prepend("migrax.url", URL_KEYS), config);
    String user = setting(overrides, "user", "MIGRAX_DATABASE_USER",
        List.of("QUARKUS_DATASOURCE_USERNAME", "SPRING_DATASOURCE_USERNAME",
            "DB_USERNAME", "DB_USER"),
        prepend("migrax.user", USER_KEYS), config);
    String password = setting(overrides, "password", "MIGRAX_DATABASE_PASSWORD",
        List.of("QUARKUS_DATASOURCE_PASSWORD", "SPRING_DATASOURCE_PASSWORD", "DB_PASSWORD"),
        prepend("migrax.password", PASSWORD_KEYS), config);
    String locations = firstNonBlank(
        overrides.get("locations"),
        System.getProperty("migrax.locations"), System.getenv("MIGRAX_LOCATIONS"),
        resolve(config.get("migrax.locations")),
        resolve(config.get("spring.flyway.locations")),
        "classpath:db/migration");
    return new Credentials(url, user, password, locations);
  }

  public static Path resolveMigrationDirectory(
      String location, Path projectDirectory, Path resourcesDirectory) {
    if (location == null || location.isBlank()) {
      throw new IllegalArgumentException("Migrax migration location must not be blank.");
    }
    String configured = location.trim();
    if (configured.indexOf(',') >= 0) {
      throw new IllegalArgumentException(
          "Configure one migration directory in migrax.locations; multiple locations are not supported.");
    }
    if (configured.startsWith("classpath:")) {
      String resourcePath = configured.substring("classpath:".length());
      while (resourcePath.startsWith("/")) {
        resourcePath = resourcePath.substring(1);
      }
      if (resourcePath.isBlank()) {
        throw new IllegalArgumentException("Classpath migration location must name a directory.");
      }
      Path normalizedResources = resourcesDirectory.toAbsolutePath().normalize();
      Path resolved = normalizedResources.resolve(resourcePath).normalize();
      if (!resolved.startsWith(normalizedResources)) {
        throw new IllegalArgumentException("Classpath migration location must stay under resources.");
      }
      return resolved;
    }
    if (configured.startsWith("filesystem:")) {
      configured = configured.substring("filesystem:".length());
    }
    if (configured.isBlank()) {
      throw new IllegalArgumentException("Filesystem migration location must name a directory.");
    }
    Path path = Path.of(configured);
    return (path.isAbsolute() ? path : projectDirectory.resolve(path)).normalize();
  }

  private static String setting(
      Map<String, String> overrides,
      String overrideKey,
      String migraxEnvironment,
      List<String> environmentKeys,
      List<String> configKeys,
      Map<String, String> config) {
    String value = firstNonBlank(overrides.get(overrideKey),
        System.getProperty("migrax." + overrideKey), System.getenv(migraxEnvironment));
    if (value != null) {
      return resolve(value);
    }
    for (String key : environmentKeys) {
      value = System.getenv(key);
      if (value != null && !value.isBlank()) {
        return resolve(value);
      }
    }
    for (String key : configKeys) {
      value = config.get(key);
      if (value != null && !value.isBlank()) {
        return resolve(value);
      }
    }
    return null;
  }

  private static void loadProperties(Path file, Map<String, String> values) throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(file)) {
      properties.load(reader);
    }
    for (String key : properties.stringPropertyNames()) {
      values.put(key.trim(), properties.getProperty(key).trim());
    }
  }

  private static void loadYaml(Path file, Map<String, String> values) throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    ArrayDeque<YamlLevel> parents = new ArrayDeque<>();
    for (String line : Files.readAllLines(file)) {
      String withoutComment = stripYamlComment(line);
      if (withoutComment.isBlank()) {
        continue;
      }
      int indent = leadingSpaces(withoutComment);
      String entry = withoutComment.trim();
      int separator = entry.indexOf(':');
      if (separator <= 0) {
        continue;
      }
      while (!parents.isEmpty() && parents.peekLast().indent() >= indent) {
        parents.removeLast();
      }
      String key = entry.substring(0, separator).trim();
      String value = unquote(entry.substring(separator + 1).trim());
      if (value.isEmpty()) {
        parents.addLast(new YamlLevel(indent, key));
      } else {
        StringBuilder fullKey = new StringBuilder();
        for (YamlLevel parent : parents) {
          if (!fullKey.isEmpty()) {
            fullKey.append('.');
          }
          fullKey.append(parent.key());
        }
        if (!fullKey.isEmpty()) {
          fullKey.append('.');
        }
        fullKey.append(key);
        values.put(fullKey.toString(), value);
      }
    }
  }

  private static String stripYamlComment(String line) {
    boolean singleQuoted = false;
    boolean doubleQuoted = false;
    for (int i = 0; i < line.length(); i++) {
      char current = line.charAt(i);
      if (current == '\'' && !doubleQuoted) {
        singleQuoted = !singleQuoted;
      } else if (current == '"' && !singleQuoted
          && (i == 0 || line.charAt(i - 1) != '\\')) {
        doubleQuoted = !doubleQuoted;
      } else if (current == '#' && !singleQuoted && !doubleQuoted
          && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
        return line.substring(0, i);
      }
    }
    return line;
  }

  private static int leadingSpaces(String line) {
    int spaces = 0;
    while (spaces < line.length() && line.charAt(spaces) == ' ') {
      spaces++;
    }
    return spaces;
  }

  private static String unquote(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1);
      }
    }
    return value;
  }

  private static String resolve(String value) {
    if (value == null) {
      return null;
    }
    String resolved = value;
    int start = resolved.indexOf("${");
    while (start >= 0) {
      int end = resolved.indexOf('}', start + 2);
      if (end < 0) {
        break;
      }
      String expression = resolved.substring(start + 2, end);
      int defaultSeparator = expression.indexOf(':');
      String key = defaultSeparator < 0 ? expression : expression.substring(0, defaultSeparator);
      String replacement = System.getProperty(key);
      if (replacement == null || replacement.isBlank()) {
        replacement = System.getenv(key);
      }
      if ((replacement == null || replacement.isBlank()) && defaultSeparator >= 0) {
        replacement = expression.substring(defaultSeparator + 1);
      }
      if (replacement == null) {
        return null;
      }
      resolved = resolved.substring(0, start) + replacement + resolved.substring(end + 1);
      start = resolved.indexOf("${", start + replacement.length());
    }
    return resolved;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static List<String> prepend(String first, List<String> rest) {
    return java.util.stream.Stream.concat(java.util.stream.Stream.of(first), rest.stream()).toList();
  }

  public record Credentials(String url, String user, String password, String locations) {}

  private record YamlLevel(int indent, String key) {}
}
