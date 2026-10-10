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
      "datasources.default.url",
      "db.connection.url",
      "datasource.url",
      "datasource.jdbc-url",
      "jakarta.persistence.jdbc.url",
      "javax.persistence.jdbc.url",
      "hibernate.connection.url");

  private static final List<String> USER_KEYS = List.of(
      "quarkus.datasource.username",
      "spring.datasource.username",
      "spring.datasource.hikari.username",
      "datasources.default.username",
      "db.connection.username",
      "datasource.username",
      "datasource.user",
      "jakarta.persistence.jdbc.user",
      "javax.persistence.jdbc.user",
      "hibernate.connection.username");

  private static final List<String> PASSWORD_KEYS = List.of(
      "quarkus.datasource.password",
      "spring.datasource.password",
      "spring.datasource.hikari.password",
      "datasources.default.password",
      "db.connection.password",
      "datasource.password",
      "jakarta.persistence.jdbc.password",
      "javax.persistence.jdbc.password",
      "hibernate.connection.password");

  private ProjectDatabaseConfig() {}

  public static Credentials load(Path resourcesDirectory, Map<String, String> overrides)
      throws IOException {
    Map<String, String> config = loadConfig(resourcesDirectory);
    String helidon = helidonDataSource(config);
    String micronaut = micronautDataSource(config);

    String url = setting(overrides, "url", "MIGRAX_DATABASE_URL",
        environmentKeys(List.of("QUARKUS_DATASOURCE_JDBC_URL", "QUARKUS_DATASOURCE_URL",
            "SPRING_DATASOURCE_URL", "DATASOURCES_DEFAULT_URL"), helidon, "DATASOURCE_URL",
            List.of("DATABASE_URL", "DB_URL")),
        configKeys("migrax.url", URL_KEYS, helidon, HELIDON_URL_KEYS, micronaut, "url"), config);
    String user = setting(overrides, "user", "MIGRAX_DATABASE_USER",
        environmentKeys(List.of("QUARKUS_DATASOURCE_USERNAME", "SPRING_DATASOURCE_USERNAME",
            "DATASOURCES_DEFAULT_USERNAME"), helidon, "DATASOURCE_USER",
            List.of("DB_USERNAME", "DB_USER")),
        configKeys("migrax.user", USER_KEYS, helidon, HELIDON_USER_KEYS, micronaut, "username"),
        config);
    String password = setting(overrides, "password", "MIGRAX_DATABASE_PASSWORD",
        environmentKeys(List.of("QUARKUS_DATASOURCE_PASSWORD", "SPRING_DATASOURCE_PASSWORD",
            "DATASOURCES_DEFAULT_PASSWORD"), helidon, "DATASOURCE_PASSWORD",
            List.of("DB_PASSWORD")),
        configKeys("migrax.password", PASSWORD_KEYS, helidon, HELIDON_PASSWORD_KEYS, micronaut,
            "password"), config);
    String locations = firstNonBlank(
        overrides.get("locations"),
        System.getProperty("migrax.locations"), System.getenv("MIGRAX_LOCATIONS"),
        resolve(config.get("migrax.locations")),
        resolve(config.get("spring.flyway.locations")),
        resolve(config.get("flyway.datasources.default.locations")),
        "classpath:db/migration");
    return new Credentials(url, user, password, locations);
  }

  /** Reads application.properties/yml plus the active profile's files into one map. */
  static Map<String, String> loadConfig(Path resourcesDirectory) throws IOException {
    Map<String, String> config = new LinkedHashMap<>();
    // Lowest priority first: persistence.xml (Jakarta EE, Helidon), MicroProfile config
    // (Helidon MP), then application.properties/yml (Spring, Quarkus, Micronaut, Helidon).
    loadPersistenceXml(resourcesDirectory.resolve("META-INF/persistence.xml"), config);
    Path microprofile = resourcesDirectory.resolve("META-INF/microprofile-config.properties");
    loadProperties(microprofile, config);
    loadProperties(resourcesDirectory.resolve("application.properties"), config);
    loadYaml(resourcesDirectory.resolve("application.yml"), config);
    loadYaml(resourcesDirectory.resolve("application.yaml"), config);

    String profiles = firstNonBlank(
        System.getProperty("migrax.profile"), System.getenv("MIGRAX_PROFILE"),
        System.getenv("QUARKUS_PROFILE"),
        System.getenv("SPRING_PROFILES_ACTIVE"),
        System.getenv("MICRONAUT_ENVIRONMENTS"),
        System.getenv("MP_CONFIG_PROFILE"),
        resolve(config.get("migrax.profile")),
        resolve(config.get("quarkus.profile")),
        resolve(config.get("spring.profiles.active")),
        resolve(config.get("micronaut.environments")),
        resolve(config.get("mp.config.profile")));
    if (profiles != null) {
      // Spring and Micronaut accept several, comma-separated; later ones win.
      for (String profile : profiles.split(",")) {
        profile = profile.trim();
        if (profile.isEmpty()) {
          continue;
        }
        loadProperties(microprofile.resolveSibling(
            "microprofile-config-" + profile + ".properties"), config);
        loadProperties(resourcesDirectory.resolve("application-" + profile + ".properties"),
            config);
        loadYaml(resourcesDirectory.resolve("application-" + profile + ".yml"), config);
        loadYaml(resourcesDirectory.resolve("application-" + profile + ".yaml"), config);
      }
    }
    return config;
  }

  /** Internal key: the data source a persistence.xml unit names. */
  static final String PERSISTENCE_DATA_SOURCE = "migrax.persistence.data-source";

  /**
   * Reads the first persistence unit's {@code <property>} entries (hibernate.*,
   * jakarta.persistence.*) and its data source name from persistence.xml.
   */
  private static void loadPersistenceXml(Path file, Map<String, String> values)
      throws IOException {
    if (!Files.isRegularFile(file)) {
      return;
    }
    try {
      javax.xml.parsers.DocumentBuilderFactory factory =
          javax.xml.parsers.DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setExpandEntityReferences(false);
      org.w3c.dom.Document document = factory.newDocumentBuilder().parse(file.toFile());
      org.w3c.dom.NodeList units = document.getElementsByTagNameNS("*", "persistence-unit");
      if (units.getLength() == 0) {
        return;
      }
      org.w3c.dom.Element unit = (org.w3c.dom.Element) units.item(0);
      for (String tag : List.of("jta-data-source", "non-jta-data-source")) {
        org.w3c.dom.NodeList source = unit.getElementsByTagNameNS("*", tag);
        if (source.getLength() > 0 && !source.item(0).getTextContent().isBlank()) {
          values.putIfAbsent(PERSISTENCE_DATA_SOURCE, source.item(0).getTextContent().trim());
        }
      }
      org.w3c.dom.NodeList properties = unit.getElementsByTagNameNS("*", "property");
      for (int i = 0; i < properties.getLength(); i++) {
        org.w3c.dom.Element property = (org.w3c.dom.Element) properties.item(i);
        String name = property.getAttribute("name").trim();
        if (!name.isEmpty()) {
          values.put(name, property.getAttribute("value").trim());
        }
      }
    } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
      throw new IOException("Could not read " + file + ": " + e.getMessage(), e);
    }
  }

  /** Application configuration with ${...} placeholders resolved. */
  public static Map<String, String> settings(Path resourcesDirectory) throws IOException {
    Map<String, String> resolved = new LinkedHashMap<>();
    loadConfig(resourcesDirectory).forEach((key, value) -> resolved.put(key, resolve(value)));
    return resolved;
  }

  /** Settings that make Hibernate create or change tables itself when the application starts. */
  private static final List<String> SCHEMA_GENERATION_KEYS = List.of(
      "quarkus.hibernate-orm.schema-management.strategy",
      "quarkus.hibernate-orm.database.generation",
      "spring.jpa.hibernate.ddl-auto",
      "spring.jpa.properties.hibernate.hbm2ddl.auto",
      "spring.jpa.properties.jakarta.persistence.schema-generation.database.action",
      "jakarta.persistence.schema-generation.database.action",
      "javax.persistence.schema-generation.database.action",
      "jpa.default.properties.hibernate.hbm2ddl.auto",
      "jpa.default.properties.jakarta.persistence.schema-generation.database.action",
      "hibernate.hbm2ddl.auto");

  /**
   * Application settings, such as {@code quarkus.hibernate-orm.schema-management.strategy=update},
   * that let Hibernate change the schema at startup and so fight with migrations. Profile
   * variants like {@code %dev.} are included, {@code %test.} is not. Returns {@code key=value}
   * entries.
   */
  public static List<String> schemaGeneration(Map<String, String> config) {
    List<String> found = new java.util.ArrayList<>();
    config.forEach((key, value) -> {
      String plain = key.startsWith("%") && key.indexOf('.') > 0
          ? key.substring(key.indexOf('.') + 1) : key;
      String mode = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
      // %test. profiles often build a throwaway test database with Hibernate on purpose.
      if (SCHEMA_GENERATION_KEYS.contains(plain) && !key.startsWith("%test.") && !mode.isEmpty()
          && !List.of("none", "validate", "false", "no-file").contains(mode)) {
        found.add(key + "=" + value.trim());
      }
    });
    return found;
  }

  /**
   * A setting that lets Hibernate change the schema, and where it is written.
   *
   * @param file the file relative to the resources folder, or null when not found in one
   *     (for example a system property)
   * @param line 1-based line in that file, or 0
   */
  public record SchemaGeneration(String key, String value, String file, int line) {
    /** create, create-drop, drop-and-create and drop delete data, not just change tables. */
    public boolean deletesData() {
      String mode = value.toLowerCase(java.util.Locale.ROOT);
      return mode.contains("create") || mode.contains("drop");
    }

    /** The JPA-standard action has no validate; Hibernate's own settings do. */
    public boolean acceptsValidate() {
      return !key.contains("schema-generation");
    }
  }

  /** {@link #schemaGeneration} with the file and line each setting comes from. */
  public static List<SchemaGeneration> schemaGenerationSettings(Path resourcesDirectory,
                                                                Map<String, String> config) {
    List<SchemaGeneration> found = new java.util.ArrayList<>();
    for (String entry : schemaGeneration(config)) {
      int equals = entry.indexOf('=');
      String key = entry.substring(0, equals);
      String value = entry.substring(equals + 1);
      String file = null;
      int line = 0;
      for (Path candidate : configFiles(resourcesDirectory)) {
        int at = lineOf(candidate, key);
        if (at > 0) {
          file = resourcesDirectory.relativize(candidate).toString().replace('\\', '/');
          line = at;
        }
      }
      found.add(new SchemaGeneration(key, value, file, line));
    }
    return found;
  }

  /** Configuration files in the order they are loaded; later ones win. */
  private static List<Path> configFiles(Path resourcesDirectory) {
    List<Path> files = new java.util.ArrayList<>();
    files.add(resourcesDirectory.resolve("META-INF/persistence.xml"));
    files.add(resourcesDirectory.resolve("META-INF/microprofile-config.properties"));
    for (String name : List.of("application.properties", "application.yml",
        "application.yaml")) {
      files.add(resourcesDirectory.resolve(name));
    }
    try (java.util.stream.Stream<Path> profiles = Files.list(resourcesDirectory)) {
      profiles.filter(p -> p.getFileName().toString().matches("application-.+\\.(properties|ya?ml)"))
          .sorted().forEach(files::add);
    } catch (IOException ignored) {
      // No resources folder.
    }
    return files.stream().filter(Files::isRegularFile).toList();
  }

  /** 1-based line where the file sets {@code key}, or 0. */
  private static int lineOf(Path file, String key) {
    List<String> lines;
    try {
      lines = Files.readAllLines(file);
    } catch (IOException e) {
      return 0;
    }
    String name = String.valueOf(file.getFileName());
    if (name.endsWith(".xml")) {
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).contains("name=\"" + key + "\"")) {
          return i + 1;
        }
      }
      return 0;
    }
    if (name.endsWith(".properties")) {
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i).trim();
        int separator = line.indexOf('=') >= 0 ? line.indexOf('=') : line.indexOf(':');
        if (!line.startsWith("#") && separator > 0
            && line.substring(0, separator).trim().equals(key)) {
          return i + 1;
        }
      }
      return 0;
    }
    // YAML: rebuild each line's full dotted key from its parents, as loadYaml does.
    ArrayDeque<YamlLevel> parents = new ArrayDeque<>();
    for (int i = 0; i < lines.size(); i++) {
      String withoutComment = stripYamlComment(lines.get(i));
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
      String own = entry.substring(0, separator).trim();
      StringBuilder full = new StringBuilder();
      parents.forEach(p -> full.append(p.key()).append('.'));
      full.append(own);
      if (entry.substring(separator + 1).trim().isEmpty()) {
        parents.addLast(new YamlLevel(indent, own));
      } else if (full.toString().equals(key)) {
        return i + 1;
      }
    }
    return 0;
  }

  /**
   * A Migrax setting: {@code -Dmigrax.<key>}, then {@code MIGRAX_<KEY>} (dots and dashes become
   * underscores), then {@code migrax.<key>} in application config.
   */
  public static String migraxSetting(Map<String, String> config, String key) {
    String env = "MIGRAX_" + key.toUpperCase(java.util.Locale.ROOT).replace('.', '_')
        .replace('-', '_');
    return firstNonBlank(System.getProperty("migrax." + key), System.getenv(env),
        config.get("migrax." + key));
  }

  /**
   * Placeholder values for ${name} in migrations: {@code migrax.placeholders.<name>} from
   * config, {@code -Dmigrax.placeholders.<name>} and {@code MIGRAX_PLACEHOLDERS_<NAME>}.
   */
  public static Map<String, String> placeholders(Map<String, String> config) {
    Map<String, String> values = new LinkedHashMap<>();
    String prefix = "migrax.placeholders.";
    config.forEach((key, value) -> {
      if (key.startsWith(prefix) && value != null) {
        values.put(key.substring(prefix.length()), value);
      }
    });
    System.getProperties().forEach((key, value) -> {
      if (String.valueOf(key).startsWith(prefix)) {
        values.put(String.valueOf(key).substring(prefix.length()), String.valueOf(value));
      }
    });
    System.getenv().forEach((key, value) -> {
      if (key.startsWith("MIGRAX_PLACEHOLDERS_")) {
        values.put(key.substring("MIGRAX_PLACEHOLDERS_".length()).toLowerCase(java.util.Locale.ROOT),
            value);
      }
    });
    return values;
  }

  /**
   * Hibernate settings as the application would pass them: {@code spring.jpa.properties.*},
   * Spring and Quarkus naming-strategy keys, and plain {@code hibernate.*} keys. Connection and
   * schema-generation settings are left out because Migrax reads the mapping offline.
   */
  public static Map<String, String> hibernateSettings(Map<String, String> config) {
    Map<String, String> settings = new LinkedHashMap<>();
    config.forEach((key, value) -> {
      if (value == null) {
        return;
      }
      if (key.startsWith("hibernate.") || key.startsWith("jakarta.persistence.")
          || key.startsWith("javax.persistence.")) {
        settings.put(key, value);
      } else if (key.startsWith("spring.jpa.properties.")) {
        settings.put(key.substring("spring.jpa.properties.".length()), value);
      } else if (key.startsWith("jpa.default.properties.")) {
        // Micronaut: jpa.default.properties.hibernate.*
        settings.put(key.substring("jpa.default.properties.".length()), value);
      }
    });
    copy(config, settings, "spring.jpa.hibernate.naming.physical-strategy",
        "hibernate.physical_naming_strategy");
    copy(config, settings, "spring.jpa.hibernate.naming.implicit-strategy",
        "hibernate.implicit_naming_strategy");
    copy(config, settings, "quarkus.hibernate-orm.physical-naming-strategy",
        "hibernate.physical_naming_strategy");
    copy(config, settings, "quarkus.hibernate-orm.implicit-naming-strategy",
        "hibernate.implicit_naming_strategy");
    copy(config, settings, "quarkus.hibernate-orm.mapping.naming.physical-strategy",
        "hibernate.physical_naming_strategy");
    copy(config, settings, "quarkus.hibernate-orm.mapping.naming.implicit-strategy",
        "hibernate.implicit_naming_strategy");
    copy(config, settings, "spring.jpa.database-platform", "hibernate.dialect");
    settings.keySet().removeIf(key -> key.startsWith("hibernate.connection.")
        || key.startsWith("jakarta.persistence.jdbc.") || key.startsWith("javax.persistence.jdbc.")
        || key.startsWith("hibernate.hbm2ddl") || key.contains("schema-generation")
        || key.startsWith("hibernate.hikari") || key.equals("hibernate.show_sql"));
    return settings;
  }

  private static void copy(Map<String, String> from, Map<String, String> to, String source,
                           String target) {
    String value = from.get(source);
    if (value != null && !value.isBlank()) {
      to.put(target, value);
    }
  }

  /**
   * Detects how the application names tables and columns, so generated SQL matches Hibernate.
   *
   * <p>Order: the explicit override, {@code -Dmigrax.naming}, {@code MIGRAX_NAMING},
   * {@code migrax.naming} in application config, then the Spring or Quarkus Hibernate naming
   * properties, then the framework default: Quarkus uses Hibernate's names as written, and
   * everything else uses Spring Boot's snake_case names.
   */
  public static io.migrax.model.NamingStrategy namingStrategy(
      Path resourcesDirectory, Path projectDirectory, String override) throws IOException {
    return namingStrategy(resourcesDirectory, projectDirectory, override, null);
  }

  /**
   * The naming strategy: an explicit setting, else the one recorded in the snapshot (so names
   * stay stable once migrations exist), else what the framework and its config imply.
   */
  public static io.migrax.model.NamingStrategy namingStrategy(
      Path resourcesDirectory, Path projectDirectory, String override, String recorded)
      throws IOException {
    return namingStrategy(resourcesDirectory, projectDirectory, override, recorded, false);
  }

  /**
   * @param legacyDefaults use the defaults of Migrax versions that did not record the naming
   *     in the snapshot (Quarkus: jpa, everything else: spring), so a project generated with
   *     them keeps its names; set when a snapshot exists without a recorded naming
   */
  public static io.migrax.model.NamingStrategy namingStrategy(
      Path resourcesDirectory, Path projectDirectory, String override, String recorded,
      boolean legacyDefaults) throws IOException {
    Map<String, String> config = loadConfig(resourcesDirectory);
    String explicit = firstNonBlank(override, System.getProperty("migrax.naming"),
        System.getenv("MIGRAX_NAMING"), resolve(config.get("migrax.naming")), recorded);
    if (explicit != null) {
      return io.migrax.model.NamingStrategy.parse(explicit);
    }
    String physical = firstNonBlank(
        resolve(config.get("spring.jpa.hibernate.naming.physical-strategy")),
        resolve(config.get("spring.jpa.properties.hibernate.physical_naming_strategy")),
        resolve(config.get("quarkus.hibernate-orm.physical-naming-strategy")),
        resolve(config.get("quarkus.hibernate-orm.mapping.naming.physical-strategy")),
        resolve(config.get("jpa.default.properties.hibernate.physical_naming_strategy")),
        resolve(config.get("hibernate.physical_naming_strategy")));
    String implicit = firstNonBlank(
        resolve(config.get("spring.jpa.hibernate.naming.implicit-strategy")),
        resolve(config.get("spring.jpa.properties.hibernate.implicit_naming_strategy")),
        resolve(config.get("quarkus.hibernate-orm.implicit-naming-strategy")),
        resolve(config.get("quarkus.hibernate-orm.mapping.naming.implicit-strategy")),
        resolve(config.get("jpa.default.properties.hibernate.implicit_naming_strategy")),
        resolve(config.get("hibernate.implicit_naming_strategy")));

    Framework framework = detectFramework(config, projectDirectory);
    io.migrax.model.NamingStrategy defaults = !legacyDefaults ? frameworkNaming(framework)
        : framework == Framework.QUARKUS ? io.migrax.model.NamingStrategy.JPA
        : io.migrax.model.NamingStrategy.SPRING;
    if (!legacyDefaults && physical != null && physical.contains("io.micronaut.data")) {
      return io.migrax.model.NamingStrategy.MICRONAUT;
    }
    boolean snakeCase = defaults.snakeCase();
    boolean micronaut = defaults.micronaut();
    if (physical != null) {
      snakeCase = physical.contains("CamelCaseToUnderscores")
          || physical.contains("SpringPhysicalNamingStrategy");
      micronaut = false;
    }
    boolean springJoinTables = defaults.springJoinTables();
    if (implicit != null) {
      springJoinTables = implicit.contains("SpringImplicitNamingStrategy");
    }
    return new io.migrax.model.NamingStrategy(springJoinTables, snakeCase, micronaut);
  }

  /** Where this framework's database URL goes, for "not configured" hints. */
  public static String urlSetting(Framework framework) {
    return switch (framework) {
      case SPRING, UNKNOWN -> "spring.datasource.url in application.properties";
      case QUARKUS -> "quarkus.datasource.jdbc.url in application.properties";
      case MICRONAUT, MICRONAUT_DATA -> "datasources.default.url in application.yml";
      case HELIDON -> "javax.sql.DataSource.<name>.dataSource.url in "
          + "META-INF/microprofile-config.properties (Helidon SE: db.connection.url)";
      case JAKARTA -> "jakarta.persistence.jdbc.url in META-INF/persistence.xml";
    };
  }

  /** Frameworks whose defaults decide naming and setup hints. */
  public enum Framework {
    SPRING("Spring Boot"), QUARKUS("Quarkus"), MICRONAUT_DATA("Micronaut Data"),
    MICRONAUT("Micronaut"), HELIDON("Helidon"), JAKARTA("Jakarta EE / plain Hibernate"),
    UNKNOWN("unknown framework");

    private final String label;

    Framework(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** Recognises the framework from its configuration keys and the build file. */
  public static Framework detectFramework(Map<String, String> config, Path projectDirectory) {
    String build = buildFiles(projectDirectory);
    if (config.keySet().stream().anyMatch(k -> k.startsWith("quarkus."))
        || build.contains("io.quarkus")) {
      return Framework.QUARKUS;
    }
    if (build.contains("micronaut-data-hibernate")) {
      return Framework.MICRONAUT_DATA;
    }
    if (build.contains("io.micronaut") || config.containsKey("micronaut.application.name")) {
      return Framework.MICRONAUT;
    }
    if (build.contains("io.helidon")) {
      return Framework.HELIDON;
    }
    if (config.keySet().stream().anyMatch(k -> k.startsWith("spring."))
        || build.contains("org.springframework.boot")) {
      return Framework.SPRING;
    }
    if (!build.isEmpty()) {
      return Framework.JAKARTA;
    }
    return Framework.UNKNOWN;
  }

  /**
   * Naming each framework gives Hibernate by default. Spring Boot and Micronaut Data install
   * their own strategies; Quarkus, Helidon, plain Micronaut JPA and Jakarta EE keep Hibernate's.
   * Without a build file to look at, Spring's (the most common) is assumed.
   */
  static io.migrax.model.NamingStrategy frameworkNaming(Framework framework) {
    return switch (framework) {
      case SPRING, UNKNOWN -> io.migrax.model.NamingStrategy.SPRING;
      case MICRONAUT_DATA -> io.migrax.model.NamingStrategy.MICRONAUT;
      case QUARKUS, MICRONAUT, HELIDON, JAKARTA -> io.migrax.model.NamingStrategy.JPA;
    };
  }

  /**
   * The text of the build files: pom.xml or build.gradle(.kts), plus the Gradle version catalog
   * here or in the parent (root) project. Empty when there are none.
   */
  private static String buildFiles(Path projectDirectory) {
    if (projectDirectory == null) {
      return "";
    }
    List<Path> files = new java.util.ArrayList<>();
    for (String file : List.of("pom.xml", "build.gradle", "build.gradle.kts",
        "gradle/libs.versions.toml")) {
      files.add(projectDirectory.resolve(file));
    }
    Path parent = projectDirectory.toAbsolutePath().getParent();
    if (parent != null) {
      files.add(parent.resolve("gradle/libs.versions.toml"));
    }
    StringBuilder text = new StringBuilder();
    for (Path path : files) {
      try {
        if (Files.isRegularFile(path)) {
          text.append(Files.readString(path)).append('\n');
        }
      } catch (IOException ignored) {
        // An unreadable build file just means the framework cannot be detected from it.
      }
    }
    return text.toString();
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

  /**
   * The content of a secret file without its trailing line break, or null when no file is
   * named. The error names the setting and the path, never the content.
   */
  private static String fromFile(String path, String setting) {
    if (path == null || path.isBlank()) {
      return null;
    }
    try {
      String content = java.nio.file.Files.readString(Path.of(path.trim()));
      return content.replaceAll("\\R+$", "");
    } catch (IOException | java.nio.file.InvalidPathException e) {
      throw new IllegalStateException("Could not read the file that " + setting + " names ("
          + path.trim() + "): " + e.getMessage(), e);
    }
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
    // Secrets mounted as files (Kubernetes and Docker secrets): --password-file or
    // MIGRAX_DATABASE_PASSWORD_FILE, and the same for the user and URL.
    String fromFile = fromFile(overrides.get(overrideKey + "-file"), "--" + overrideKey + "-file");
    if (fromFile == null) {
      fromFile = fromFile(System.getenv(migraxEnvironment + "_FILE"), migraxEnvironment + "_FILE");
    }
    if (fromFile != null) {
      return fromFile;
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

  /** Helidon MP / MicroProfile data source properties, after {@code javax.sql.DataSource.<name>.}. */
  private static final List<String> HELIDON_URL_KEYS =
      List.of("dataSource.url", "dataSource.URL", "jdbcUrl", "URL", "url");
  private static final List<String> HELIDON_USER_KEYS =
      List.of("dataSource.user", "dataSource.username", "username", "user");
  private static final List<String> HELIDON_PASSWORD_KEYS =
      List.of("dataSource.password", "password");
  private static final String HELIDON_PREFIX = "javax.sql.DataSource.";

  /**
   * The Helidon MP data source to use: the one persistence.xml names as its (jta-)data-source,
   * otherwise the first {@code javax.sql.DataSource.<name>.*} in the configuration.
   */
  static String helidonDataSource(Map<String, String> config) {
    List<String> names = new java.util.ArrayList<>();
    for (String key : config.keySet()) {
      if (key.startsWith(HELIDON_PREFIX)) {
        int dot = key.indexOf('.', HELIDON_PREFIX.length());
        if (dot > HELIDON_PREFIX.length()) {
          String name = key.substring(HELIDON_PREFIX.length(), dot);
          if (!names.contains(name)) {
            names.add(name);
          }
        }
      }
    }
    String wanted = config.get(PERSISTENCE_DATA_SOURCE);
    if (wanted != null && names.contains(wanted)) {
      return wanted;
    }
    return names.isEmpty() ? null : names.get(0);
  }

  /** Micronaut's data source: {@code default}, otherwise the first {@code datasources.<name>}. */
  static String micronautDataSource(Map<String, String> config) {
    String first = null;
    for (String key : config.keySet()) {
      if (key.startsWith("datasources.") && key.endsWith(".url")) {
        String name = key.substring("datasources.".length(), key.length() - ".url".length());
        if (name.equals("default")) {
          return name;
        }
        if (first == null && !name.contains(".")) {
          first = name;
        }
      }
    }
    return first;
  }

  /** migrax.<key>, the fixed keys, then the chosen Helidon and Micronaut data source keys. */
  private static List<String> configKeys(String migraxKey, List<String> fixed, String helidon,
                                         List<String> helidonKeys, String micronaut,
                                         String micronautKey) {
    List<String> keys = new java.util.ArrayList<>();
    keys.add(migraxKey);
    keys.addAll(fixed);
    if (helidon != null) {
      helidonKeys.forEach(k -> keys.add(HELIDON_PREFIX + helidon + "." + k));
    }
    if (micronaut != null) {
      keys.add("datasources." + micronaut + "." + micronautKey);
    }
    return keys;
  }

  /**
   * Environment variables: framework ones, MicroProfile's mapping of the Helidon data source
   * (javax.sql.DataSource.db.dataSource.url is JAVAX_SQL_DATASOURCE_DB_DATASOURCE_URL), then
   * generic ones.
   */
  private static List<String> environmentKeys(List<String> framework, String helidon,
                                              String helidonSuffix, List<String> generic) {
    List<String> keys = new java.util.ArrayList<>(framework);
    if (helidon != null) {
      keys.add("JAVAX_SQL_DATASOURCE_" + helidon.toUpperCase(java.util.Locale.ROOT)
          .replaceAll("[^A-Z0-9]", "_") + "_" + helidonSuffix);
    }
    keys.addAll(generic);
    return keys;
  }

  public record Credentials(String url, String user, String password, String locations) {}

  private record YamlLevel(int indent, String key) {}
}
