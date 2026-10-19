package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.dialect.MySqlDialect;
import org.migrax.dialect.PostgresDialect;
import org.migrax.dialect.SqlServerDialect;
import org.migrax.diff.SnapshotStore;
import org.migrax.model.ModelExtractor;
import org.migrax.model.NamingStrategy;
import org.migrax.plugin.ProjectDatabaseConfig;
import org.migrax.plugin.RuntimeJdbc;
import org.migrax.runner.Databases;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationLoader;
import org.migrax.runner.MigrationRunner;
import org.migrax.runner.TargetSchema;
import org.migrax.util.Log;

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
  /** The further database the command works on, or null for the default database. */
  private final String database;
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
    String name = args.option("--database");
    if (blank(name)) {
      name = System.getProperty("migrax.database");
    }
    if (blank(name)) {
      name = System.getenv("MIGRAX_DATABASE");
    }
    try {
      this.database = blank(name) || name.trim().equals(Databases.DEFAULT) ? null
          : Databases.checkName(name.trim());
    } catch (IllegalArgumentException e) {
      throw new UsageException(e.getMessage(),
          "Pass the name of a migrax.databases.<name> block, or leave --database out.");
    }
  }

  Path root() {
    return root;
  }

  Path resources() {
    return root.resolve("src/main/resources");
  }

  /** The further database the command works on ({@code --database}), or null. */
  String database() {
    return database;
  }

  /**
   * Migrax's own files for the database: {@code .migrax}, or {@code .migrax/<name>} for a
   * further database.
   */
  Path state() {
    Path state = root.resolve(".migrax");
    return database == null ? state : state.resolve(database);
  }

  Path snapshot() {
    return database == null ? SnapshotStore.projectSnapshot(root)
        : state().resolve("snapshot.json");
  }

  /** The model recorded after a generated migration, e.g. for squashing. */
  Path historySnapshot(String migrationName) {
    return SnapshotStore.historySnapshotIn(state(), migrationName);
  }

  /**
   * The application config. For a further database, its block takes the place of the default
   * database's own settings ({@link Databases#settings}).
   */
  Map<String, String> config() throws Exception {
    if (config == null) {
      Map<String, String> settings = ProjectDatabaseConfig.settings(resources());
      if (database != null) {
        List<String> names = databaseNames(settings);
        if (!names.contains(database)
            && ProjectDatabaseConfig.databaseSetting(settings, database, "url") == null) {
          throw new UsageException("No database named '" + database + "'.", names.isEmpty()
              ? "Configure it with migrax.databases." + database + ".* settings, or leave"
                  + " --database out."
              : "Configured: " + String.join(", ", names) + ".");
        }
        settings = Databases.settings(database, settings);
      }
      config = settings;
    }
    return config;
  }

  /** The further databases in the settings. */
  private static List<String> databaseNames(Map<String, String> settings) {
    try {
      return Databases.names(settings);
    } catch (IllegalArgumentException e) {
      throw new UsageException(e.getMessage(), null);
    }
  }

  /** Settings that belong to one database and never come from the default database's. */
  private static final List<String> OWN =
      List.of("locations", "schemas", "java-package", "package");

  /**
   * A Migrax setting from the command line option, -D, MIGRAX_* or application config. For a
   * further database, its own -D, MIGRAX_DATABASES_* or block first; settings that belong to
   * one database stop there.
   */
  String setting(String option, String key) throws Exception {
    String value = option == null ? null : args.option(option);
    if (value != null && !value.isBlank()) {
      return value;
    }
    if (database != null) {
      config();
      value = ProjectDatabaseConfig.databaseSetting(
          ProjectDatabaseConfig.settings(resources()), database, key);
      if (value != null || OWN.contains(key)) {
        return value;
      }
    }
    return ProjectDatabaseConfig.migraxSetting(config(), key);
  }

  ProjectDatabaseConfig.Credentials credentials() throws Exception {
    if (credentials == null) {
      Map<String, String> overrides = Map.of(
          "url", args.option("--url", ""),
          "user", args.option("--user", ""),
          "password", args.option("--password", ""),
          "password-file", args.option("--password-file", ""),
          "locations", args.option("--locations", ""));
      ProjectDatabaseConfig.Credentials loaded;
      if (database == null) {
        loaded = ProjectDatabaseConfig.load(resources(), overrides);
      } else {
        config();
        loaded = ProjectDatabaseConfig.loadDatabase(resources(), database, overrides);
      }
      credentials = new ProjectDatabaseConfig.Credentials(
          resolveRelativeDatabasePath(loaded.url(), root),
          loaded.user(), loaded.password(), loaded.locations());
    }
    return credentials;
  }

  ProjectDatabaseConfig.Credentials requireUrl() throws Exception {
    if (blank(credentials().url())) {
      if (database != null) {
        throw new UsageException(ErrorCode.NO_DATABASE_URL,
            "No URL found for database '" + database + "'.",
            "Set migrax.databases." + database + ".url, set MIGRAX_DATABASES_"
                + database.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_")
                + "_URL, or pass --url.");
      }
      throw new UsageException(ErrorCode.NO_DATABASE_URL, "No database URL found.",
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

  /** The first migration folder: new migrations are written there. */
  Path migrations() throws Exception {
    return migrationFolders().get(0);
  }

  /** Every migration folder ({@code --locations a,b}); migrations are read from all of them. */
  List<Path> migrationFolders() throws Exception {
    return ProjectDatabaseConfig.resolveMigrationDirectories(
        credentials().locations(), root, resources());
  }

  /** The migration folders for messages, separated by commas. */
  String displayMigrations() throws Exception {
    return String.join(", ", migrationFolders().stream().map(this::display).toList());
  }

  /**
   * SQL files of every migration folder in version order. Two folders holding a file with the
   * same name is an error: both would be recorded under that name.
   */
  List<Path> migrationFiles() throws Exception {
    List<Path> files = MigrationLoader.sqlFiles(migrationFolders());
    Map<String, Path> seen = new java.util.HashMap<>();
    for (Path file : files) {
      String name = String.valueOf(file.getFileName());
      Path other = seen.putIfAbsent(name, file);
      if (other != null) {
        throw new UsageException("Two migration folders contain " + name + ": "
            + display(other) + " and " + display(file) + ".",
            "Migration file names must be unique across all folders. Rename one of them.");
      }
    }
    return files;
  }

  /** The migration file with this name, from whichever folder holds it, or null. */
  Path migrationFile(String name) throws Exception {
    for (Path folder : migrationFolders()) {
      if (Files.isRegularFile(folder.resolve(name))) {
        return folder.resolve(name);
      }
    }
    return null;
  }

  /** The folder that holds a migration, or the first folder when none does. */
  Path folderOf(String version) throws Exception {
    Path file = migrationFile(version);
    return file == null ? migrations() : file.getParent();
  }

  /** The rollback script of a migration, next to the migration file; null when missing. */
  Path rollbackScript(String version) throws Exception {
    return MigrationLoader.rollbackScript(folderOf(version), version);
  }

  /** The next migration number after the highest one in any migration folder. */
  int nextNumber() throws Exception {
    int next = 1;
    for (Path folder : migrationFolders()) {
      next = Math.max(next, MigrationFiles.nextNumber(folder));
    }
    return next;
  }

  String packageName() throws Exception {
    String name = packageNameOrNull();
    if (blank(name)) {
      throw new UsageException("Entity package is unknown.", database == null
          ? "Pass --package <name> or set MIGRAX_PACKAGE (for Maven projects the groupId is used)."
          : "Set migrax.databases." + database + ".package or pass --package <name>.");
    }
    return name;
  }

  /**
   * The entity package: --package, then for a further database its own package setting, for
   * the default database MIGRAX_PACKAGE or the build's group.
   */
  String packageNameOrNull() throws Exception {
    return database == null ? ProjectContext.packageName(root, args.option("--package"))
        : setting("--package", "package");
  }

  /**
   * The packages to read entities from, as {@link org.migrax.util.ClassScanner} takes them: the
   * entity package, and for the default database without the packages of further databases
   * inside it ({@code com.shop,!com.shop.billing}).
   */
  String entityPackages() throws Exception {
    String name = packageName();
    if (database != null) {
      return name;
    }
    Map<String, String> settings = config();
    StringBuilder packages = new StringBuilder(name);
    for (String other : databaseNames(settings)) {
      String own = ProjectDatabaseConfig.databaseSetting(settings, other, "package");
      if (!blank(own) && own.trim().startsWith(name + ".")) {
        packages.append(",!").append(own.trim());
      }
    }
    return packages.toString();
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

  /** Hibernate settings plus Migrax's own reader settings (migrax.enum-checks). */
  private Map<String, String> readerSettings() throws Exception {
    Map<String, String> settings = new java.util.LinkedHashMap<>(hibernateSettings());
    String enumChecks = setting(null, "enum-checks");
    if (enumChecks != null) {
      settings.put("migrax.enum-checks", enumChecks.trim());
    }
    return settings;
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

  /**
   * The dialect migrations are generated with. SQL Server can't switch schemas, so when the
   * migrations run once per schema ({@link #schemas()}) every table names its schema with the
   * {@code ${migrax.schema}} placeholder.
   */
  Dialect generating(Dialect dialect) throws Exception {
    return dialect instanceof SqlServerDialect sqlServer && !schemas().isEmpty()
        ? sqlServer.inSchema("${" + TargetSchema.PLACEHOLDER + "}") : dialect;
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

  /** The environment for {@code -- migrax:env} migrations: --env, MIGRAX_ENV or migrax.env. */
  String environment() throws Exception {
    return setting("--env", "env");
  }

  /** How long to wait for the migration lock: --lock-timeout, then MIGRAX_LOCK_TIMEOUT. */
  java.time.Duration lockTimeout() {
    String value = args.option("--lock-timeout");
    try {
      return blank(value) ? org.migrax.runner.DatabaseMigrationLock.configuredTimeout()
          : org.migrax.runner.DatabaseMigrationLock.parseTimeout(value);
    } catch (IllegalArgumentException e) {
      throw new UsageException(e.getMessage(), "For example --lock-timeout 2m.");
    }
  }

  MigrationRunner.Options runOptions() throws Exception {
    return new MigrationRunner.Options(args.flag("--resume"), database == null
        ? ProjectDatabaseConfig.placeholders(config())
        : ProjectDatabaseConfig.databasePlaceholders(
            ProjectDatabaseConfig.settings(resources()), database));
  }

  /**
   * The dialect: --dialect, then the database URL (for PostgreSQL URLs, also whether the server
   * is CockroachDB), then the snapshot's recorded dialect.
   *
   * @param allowDefault fall back to PostgreSQL with a note instead of failing
   */
  Dialect dialect(boolean allowDefault, PrintStream out) throws Exception {
    return forServer(chooseDialect(allowDefault, out));
  }

  /**
   * The dialect for the server's version when its SQL depends on it (Oracle 23ai has a boolean
   * type): the configured server's, or else the one recorded in the snapshot; otherwise the
   * dialect as it is.
   */
  private Dialect forServer(Dialect dialect) throws Exception {
    if (!dialect.dependsOnServerVersion()) {
      return dialect;
    }
    if (serverVersion == null) {
      serverVersion = askServerVersion(dialect);
      if (serverVersion.length == 0 && dialect.id().equals(SnapshotStore.dialect(snapshot()))) {
        serverVersion = parseVersion(SnapshotStore.server(snapshot()));
      }
    }
    return serverVersion.length == 2 ? dialect.forServer(serverVersion[0], serverVersion[1])
        : dialect;
  }

  private int[] askServerVersion(Dialect dialect) throws Exception {
    if (!hasUrl() || !dialect.acceptsUrl(credentials().url())) {
      return new int[0];
    }
    try (Connection connection = connect()) {
      java.sql.DatabaseMetaData metadata = connection.getMetaData();
      return new int[] {metadata.getDatabaseMajorVersion(), metadata.getDatabaseMinorVersion()};
    } catch (Exception e) {
      Log.debug("Could not ask the database for its version: {}", e.getMessage());
      return new int[0];
    }
  }

  private static int[] parseVersion(String version) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\.(\\d+)")
        .matcher(version == null ? "" : version);
    return matcher.matches() ? new int[] {Integer.parseInt(matcher.group(1)),
        Integer.parseInt(matcher.group(2))} : new int[0];
  }

  /** The server's major and minor version, asked once per command; empty when unknown. */
  private int[] serverVersion;

  private Dialect chooseDialect(boolean allowDefault, PrintStream out) throws Exception {
    String name = args.option("--dialect");
    if (!blank(name)) {
      return Dialects.byName(name);
    }
    if (hasUrl()) {
      Dialect fromUrl = Dialects.fromJdbcUrl(credentials().url());
      // The PostgreSQL and MySQL drivers also reach CockroachDB, YugabyteDB and TiDB.
      return fromUrl instanceof PostgresDialect || fromUrl instanceof MySqlDialect
          ? confirmedByServer(fromUrl) : fromUrl;
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

  /**
   * The PostgreSQL driver also reaches CockroachDB and YugabyteDB, and the MySQL driver TiDB.
   * The snapshot's recorded dialect tells which, then a CockroachDB Hibernate dialect setting,
   * then the server itself.
   */
  private Dialect confirmedByServer(Dialect fromUrl) throws Exception {
    String recorded = SnapshotStore.dialect(snapshot());
    if (recorded != null) {
      Dialect same = Dialects.byName(recorded);
      return fromUrl.getClass().isInstance(same) ? same : fromUrl;
    }
    String hibernate = hibernateSettings().get("hibernate.dialect");
    if (hibernate != null && hibernate.toLowerCase(Locale.ROOT).contains("cockroach")) {
      return Dialects.byName("cockroachdb");
    }
    if (serverDialect == null) {
      try (Connection connection = connect()) {
        serverDialect = Dialects.forConnection(connection).orElse(fromUrl);
      } catch (Exception e) {
        Log.debug("Could not ask the database which one it is: {}", e.getMessage());
        return fromUrl;
      }
    }
    return serverDialect;
  }

  /** The dialect the server reported, asked once per command. */
  private Dialect serverDialect;

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
    return ModelExtractor.extract(loader(true), entityPackages(), naming(), dialect,
        readerSettings(), extractorMode());
  }

  /** SQL files, repeatable migrations, callbacks and Java migrations. */
  List<Migration> migrationsToRun(boolean withJava) throws Exception {
    migrationFiles();
    return MigrationLoader.load(migrationFolders(), withJava ? loader(false) : null,
        javaPackage());
  }

  Connection connect() throws Exception {
    ProjectDatabaseConfig.Credentials c = requireUrl();
    return RuntimeJdbc.connect(loader(false), c.url(), c.user(), c.password());
  }

  /**
   * Points the connection at a schema; MySQL and MariaDB call schemas databases. SQL Server
   * can't switch, so Migrax names the schema itself ({@link TargetSchema}).
   */
  static void useSchema(Connection connection, String schema) throws Exception {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    if (product.contains("sqlite")) {
      throw new UsageException("SQLite has no schemas.",
          "Remove --schema/--schemas: each SQLite database is its own file.");
    }
    TargetSchema.use(connection, schema);
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
