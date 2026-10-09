package io.migrax.cli;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.Dialects;
import io.migrax.diff.SnapshotStore;
import io.migrax.model.ModelExtractor;
import io.migrax.model.NamingStrategy;
import io.migrax.plugin.ProjectDatabaseConfig;
import io.migrax.plugin.RuntimeJdbc;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import io.migrax.util.Log;

import java.io.PrintStream;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything a command needs to know about the project: folders, configuration, the
 * application class loader, the dialect and database connections.
 */
final class Project implements AutoCloseable {
  private static final Pattern H2_RELATIVE_FILE =
      Pattern.compile("(?i)^(jdbc:h2:(?:file:)?)(\\.{1,2}[/\\\\].*)$");

  private final Args args;
  private final PrintStream err;
  private final Path root;
  private Map<String, String> config;
  private ProjectDatabaseConfig.Credentials credentials;
  private URLClassLoader loader;
  private boolean compiled;

  Project(Args args, PrintStream err) throws Exception {
    this.args = args;
    this.err = err;
    Path start = Path.of(args.option("--dir", "."));
    if (!Files.exists(start)) {
      throw new UsageException("Project folder does not exist: " + start, null);
    }
    this.root = ProjectContext.findRoot(start);
  }

  Path root() {
    return root;
  }

  Path resources() {
    return root.resolve("src/main/resources");
  }

  Path snapshot() {
    return SnapshotStore.projectSnapshot(root);
  }

  Map<String, String> config() throws Exception {
    if (config == null) {
      config = ProjectDatabaseConfig.settings(resources());
    }
    return config;
  }

  /** A Migrax setting from the command line option, -D, MIGRAX_* or application config. */
  String setting(String option, String key) throws Exception {
    String value = option == null ? null : args.option(option);
    return value != null && !value.isBlank() ? value
        : ProjectDatabaseConfig.migraxSetting(config(), key);
  }

  ProjectDatabaseConfig.Credentials credentials() throws Exception {
    if (credentials == null) {
      ProjectDatabaseConfig.Credentials loaded = ProjectDatabaseConfig.load(resources(), Map.of(
          "url", args.option("--url", ""),
          "user", args.option("--user", ""),
          "password", args.option("--password", ""),
          "locations", args.option("--locations", "")));
      credentials = new ProjectDatabaseConfig.Credentials(
          resolveRelativeDatabasePath(loaded.url(), root),
          loaded.user(), loaded.password(), loaded.locations());
    }
    return credentials;
  }

  ProjectDatabaseConfig.Credentials requireUrl() throws Exception {
    if (blank(credentials().url())) {
      throw new UsageException("No database URL found.",
          "Set " + urlSetting() + ", set MIGRAX_DATABASE_URL, or pass --url.");
    }
    return credentials();
  }

  ProjectDatabaseConfig.Framework framework() throws Exception {
    return ProjectDatabaseConfig.detectFramework(config(), root);
  }

  /** The detected framework's database URL setting, e.g. datasources.default.url. */
  String urlSetting() throws Exception {
    return ProjectDatabaseConfig.urlSetting(framework());
  }

  boolean hasUrl() throws Exception {
    return !blank(credentials().url());
  }

  Path migrations() throws Exception {
    return ProjectDatabaseConfig.resolveMigrationDirectory(
        credentials().locations(), root, resources());
  }

  String packageName() throws Exception {
    String name = ProjectContext.packageName(root, args.option("--package"));
    if (blank(name)) {
      throw new UsageException("Entity package is unknown.",
          "Pass --package <name> or set MIGRAX_PACKAGE (for Maven projects the groupId is used).");
    }
    return name;
  }

  String packageNameOrNull() throws Exception {
    return ProjectContext.packageName(root, args.option("--package"));
  }

  NamingStrategy naming() throws Exception {
    String recorded = SnapshotStore.naming(snapshot());
    return ProjectDatabaseConfig.namingStrategy(resources(), root, args.option("--naming"),
        recorded, recorded == null && Files.exists(snapshot()));
  }

  /** What the framework would use for a new project; differs from naming() after upgrades. */
  NamingStrategy frameworkNaming() throws Exception {
    return ProjectDatabaseConfig.namingStrategy(resources(), root, null, null, false);
  }

  ModelExtractor.Mode extractorMode() throws Exception {
    return ModelExtractor.Mode.parse(setting("--extractor", "extractor"));
  }

  Map<String, String> hibernateSettings() throws Exception {
    return ProjectDatabaseConfig.hibernateSettings(config());
  }

  String javaPackage() throws Exception {
    String value = setting("--java-package", "java-package");
    return blank(value) ? MigrationLoader.DEFAULT_JAVA_PACKAGE : value;
  }

  /** Schemas for multi-tenant runs, or an empty list for the connection's default schema. */
  List<String> schemas() throws Exception {
    String value = setting("--schemas", "schemas");
    List<String> schemas = new ArrayList<>();
    if (!blank(value)) {
      for (String schema : value.split(",")) {
        if (!schema.isBlank()) {
          schemas.add(schema.trim());
        }
      }
    }
    return schemas;
  }

  /** The schemas a command runs for: {@link #schemas()}, or one null for the default schema. */
  List<String> schemasOrDefault() throws Exception {
    List<String> schemas = schemas();
    List<String> result = new ArrayList<>();
    if (schemas.isEmpty()) {
      result.add(null);
    } else {
      result.addAll(schemas);
    }
    return result;
  }

  /** The naming id to record in the snapshot, or null for a custom combination. */
  String recordedNaming() throws Exception {
    String id = naming().id();
    try {
      NamingStrategy.parse(id);
      return id;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  MigrationRunner.Options runOptions() throws Exception {
    return new MigrationRunner.Options(args.flag("--resume"),
        ProjectDatabaseConfig.placeholders(config()));
  }

  /**
   * The dialect: --dialect, then the database URL, then the snapshot's recorded dialect.
   *
   * @param allowDefault fall back to PostgreSQL with a note instead of failing
   */
  Dialect dialect(boolean allowDefault, PrintStream out) throws Exception {
    String name = args.option("--dialect");
    if (!blank(name)) {
      return Dialects.byName(name);
    }
    if (hasUrl()) {
      return Dialects.fromJdbcUrl(credentials().url());
    }
    String recorded = SnapshotStore.dialect(snapshot());
    if (recorded != null) {
      return Dialects.byName(recorded);
    }
    if (allowDefault) {
      out.println("No database URL configured: showing PostgreSQL SQL. Use --dialect to change.");
      return Dialects.byName("postgresql");
    }
    throw new UsageException("Cannot tell which database to write SQL for.",
        "Configure the database URL, or pass --dialect (" + String.join(", ", Dialects.NAMES) + ").");
  }

  boolean noBuild() {
    return args.flag("--no-build")
        || "true".equalsIgnoreCase(System.getProperty("migrax.noBuild"))
        || "true".equalsIgnoreCase(System.getenv("MIGRAX_NO_BUILD"));
  }

  /**
   * Class loader with the project's classes and runtime dependencies, running Maven or Gradle
   * when needed. One loader is shared by all steps of a command.
   */
  URLClassLoader loader(boolean compile) throws Exception {
    if (loader != null && (compiled || !compile)) {
      return loader;
    }
    if (loader != null) {
      loader.close();
    }
    String explicit = ProjectContext.explicitClasspath(args.raw);
    List<Path> resolved = List.of();
    if (explicit == null && !noBuild()) {
      ProjectBuild.Result result = new ProjectBuild(root, err)
          .prepare(new ProjectBuild.Request(compile, args.flag("--refresh")));
      result.notes().forEach(Log::debug);
      resolved = result.dependencies();
    } else if (explicit == null) {
      // --no-build: reuse the dependencies resolved by the last build, if there was one.
      resolved = ProjectBuild.cachedClasspath(root);
    }
    loader = ProjectContext.runtimeLoader(root, explicit, resolved);
    compiled = compile;
    return loader;
  }

  /** Reads the entity model with Hibernate's mapping or annotation scanning. */
  ModelExtractor.Result extract(Dialect dialect) throws Exception {
    return ModelExtractor.extract(loader(true), packageName(), naming(), dialect,
        hibernateSettings(), extractorMode());
  }

  /** SQL files, repeatable migrations, callbacks and Java migrations. */
  List<Migration> migrationsToRun(boolean withJava) throws Exception {
    return MigrationLoader.load(migrations(), withJava ? loader(false) : null, javaPackage());
  }

  Connection connect() throws Exception {
    ProjectDatabaseConfig.Credentials c = requireUrl();
    return RuntimeJdbc.connect(loader(false), c.url(), c.user(), c.password());
  }

  /** Points the connection at a schema; MySQL and MariaDB call schemas databases. */
  static void useSchema(Connection connection, String schema) throws Exception {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    if (product.contains("mysql") || product.contains("mariadb")) {
      connection.setCatalog(schema);
    } else if (product.contains("sql server")) {
      throw new UsageException("SQL Server cannot switch the default schema per connection.",
          "Run Migrax once per schema with a login whose default schema is that schema.");
    } else {
      connection.setSchema(schema);
    }
  }

  /**
   * Makes relative H2 file URLs such as {@code jdbc:h2:file:./data/db} relative to the project
   * root, as the application sees them, instead of the folder Migrax was started from.
   */
  static String resolveRelativeDatabasePath(String url, Path root) {
    if (url == null) {
      return null;
    }
    Matcher matcher = H2_RELATIVE_FILE.matcher(url);
    if (!matcher.matches()) {
      return url;
    }
    String rest = matcher.group(2);
    int options = rest.indexOf(';');
    String path = options < 0 ? rest : rest.substring(0, options);
    String suffix = options < 0 ? "" : rest.substring(options);
    String absolute = root.toAbsolutePath().resolve(path).normalize().toString().replace('\\', '/');
    return matcher.group(1) + absolute + suffix;
  }

  /** Display path relative to the project root. */
  String display(Path path) {
    Path absolute = path.toAbsolutePath().normalize();
    Path base = root.toAbsolutePath().normalize();
    return absolute.startsWith(base) && !absolute.equals(base)
        ? base.relativize(absolute).toString().replace('\\', '/')
        : absolute.toString();
  }

  static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  @Override
  public void close() throws Exception {
    if (loader != null) {
      loader.close();
    }
  }
}
