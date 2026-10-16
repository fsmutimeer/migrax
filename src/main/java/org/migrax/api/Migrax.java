package org.migrax.api;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.diff.SnapshotStore;
import org.migrax.model.ModelExtractor;
import org.migrax.model.NamingStrategy;
import org.migrax.model.SchemaModel;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.plugin.ProjectDatabaseConfig;
import org.migrax.plugin.ProjectPackage;
import org.migrax.plugin.RuntimeJdbc;
import org.migrax.runner.ClasspathMigrations;
import org.migrax.runner.DatabaseMigrationLock;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationLoader;
import org.migrax.runner.MigrationRunner;
import org.migrax.runner.TargetSchema;
import org.migrax.util.Log;
import org.migrax.verify.RollbackVerifier;
import org.migrax.verify.SchemaComparator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Runs Migrax from Java code: applies the migrations packaged with an application, reads their
 * status, rolls them back, and checks that the entities match the migrated database.
 *
 * <pre>{@code
 * Migrax migrax = Migrax.configure()
 *     .dataSource(dataSource)
 *     .locations("db/migration")
 *     .load();
 * int applied = migrax.migrate().applied();
 * }</pre>
 *
 * <p>Locations are classpath folders ({@code db/migration} or {@code classpath:db/migration})
 * or folders on disk ({@code filesystem:src/main/resources/db/migration}). Rollback scripts are
 * read from the {@code rollback} folder inside each location. Java migrations are the
 * {@link JavaMigration} classes in the Java migrations package ({@code db.migration} by
 * default) plus the ones passed to {@link Builder#javaMigrations}.
 *
 * <p>Every call opens its own connection and closes it again. An instance holds no other
 * resources and can be reused.
 *
 * @since 0.10.0
 */
public final class Migrax {
  private static final String DEFAULT_LOCATION = "db/migration";

  private final DataSource dataSource;
  private final String url;
  private final String user;
  private final String password;
  private final ClassLoader loader;
  private final List<String> locations;
  private final String locationsText;
  private final List<Migration> migrations;
  private final List<String> schemas;
  private final MigrationRunner.Options options;
  private final String environment;
  private final Duration lockTimeout;
  private final String entityPackage;
  private final String naming;
  private final Path projectDirectory;
  private final List<MigrationListener> listeners;

  private Migrax(Builder builder) throws Exception {
    if (builder.dataSource == null && (builder.url == null || builder.url.isBlank())) {
      throw new IllegalStateException("Migrax needs a database: call dataSource(...) or "
          + "url(...).");
    }
    this.dataSource = builder.dataSource;
    this.url = builder.url;
    this.user = builder.user;
    this.password = builder.password;
    this.loader = builder.loader != null ? builder.loader
        : Thread.currentThread().getContextClassLoader() != null
            ? Thread.currentThread().getContextClassLoader() : Migrax.class.getClassLoader();
    this.locations = builder.locations == null ? List.of(DEFAULT_LOCATION)
        : List.copyOf(builder.locations);
    this.locationsText = builder.locationsText != null ? builder.locationsText
        : String.join(",", locations);
    this.schemas = List.copyOf(builder.schemas);
    this.options = new MigrationRunner.Options(builder.resume, Map.copyOf(builder.placeholders));
    this.environment = builder.environment;
    this.lockTimeout = builder.lockTimeout;
    this.entityPackage = builder.entityPackage;
    this.naming = builder.naming;
    this.projectDirectory = builder.projectDirectory != null ? builder.projectDirectory
        : Path.of("").toAbsolutePath();
    this.listeners = List.copyOf(builder.listeners);
    this.migrations = load(builder);
  }

  /** Starts a configuration. */
  public static Builder configure() {
    return new Builder();
  }

  // ------------------------------------------------------------------ results

  /**
   * What {@link #migrate} did.
   *
   * @param applied migrations applied, in all schemas together
   * @param schemas per schema; one entry with a null schema when no schemas are configured
   */
  public record MigrateResult(int applied, List<SchemaResult> schemas) {
    public MigrateResult {
      schemas = List.copyOf(schemas);
    }
  }

  /**
   * Migrations applied in one schema.
   *
   * @param schema the schema, or null for the connection's default schema
   */
  public record SchemaResult(String schema, int applied) {}

  /** The state of one migration, as {@code migrax status} shows it. */
  public enum State {
    /** Applied, and the file is unchanged. */
    APPLIED,
    /** Not applied yet. */
    PENDING,
    /** An earlier attempt failed or was interrupted; it needs a repair or a resume. */
    FAILED,
    /** Applied, but the file was edited afterwards. */
    CHANGED,
    /** A repeatable migration that changed; it runs again on the next migrate. */
    OUTDATED,
    /** Recorded in the database, but missing from the application. */
    MISSING,
    /** Marked for other environments; it doesn't run here. */
    SKIPPED
  }

  /**
   * One migration in {@link Status}.
   *
   * @param schema the schema, or null for the connection's default schema
   */
  public record MigrationInfo(String schema, String version, State state, String detail) {}

  /** The state of every migration, in every schema. */
  public record Status(List<MigrationInfo> migrations) {
    public Status {
      migrations = List.copyOf(migrations);
    }

    /** Migrations applied. */
    public int applied() {
      return count(State.APPLIED);
    }

    /** Migrations still to run, including repeatable ones that changed. */
    public int pending() {
      return count(State.PENDING) + count(State.OUTDATED);
    }

    /** Failed, changed after being applied, or missing from the application. */
    public int problems() {
      return count(State.FAILED) + count(State.CHANGED) + count(State.MISSING);
    }

    /** Nothing pending and nothing wrong. */
    public boolean upToDate() {
      return pending() == 0 && problems() == 0;
    }

    private int count(State state) {
      return (int) migrations.stream().filter(m -> m.state() == state).count();
    }
  }

  /**
   * The result of {@link #validate}.
   *
   * @param problems differences between the entities and the database, empty when they match
   * @param entities how the entities were read, such as "Hibernate 7.2.0.Final mapping"
   */
  public record Validation(List<String> problems, String entities) {
    public Validation {
      problems = List.copyOf(problems);
    }

    /** The database matches the entities. */
    public boolean valid() {
      return problems.isEmpty();
    }
  }

  /**
   * The result of {@link #testRollbacks}.
   *
   * @param summary what happened, as {@code migrax verify} prints it
   */
  public record RollbackTest(boolean ok, String summary) {}

  // ------------------------------------------------------------------ operations

  /** Applies the pending migrations, in every configured schema. */
  public MigrateResult migrate() throws Exception {
    if (migrations.isEmpty()) {
      Log.info("No migrations found in {}.", locationsText);
      return new MigrateResult(0, List.of());
    }
    MigrationRunner runner = runner();
    List<SchemaResult> results = new ArrayList<>();
    int total = 0;
    try (Connection connection = connect()) {
      for (String schema : schemasOrDefault()) {
        use(connection, schema);
        int applied = runner.migrateAll(connection, migrations, options);
        results.add(new SchemaResult(schema, applied));
        total += applied;
      }
    }
    return new MigrateResult(total, results);
  }

  /** The state of every migration, without changing anything. */
  public Status status() throws Exception {
    List<MigrationInfo> rows = new ArrayList<>();
    try (Connection connection = connect()) {
      for (String schema : schemasOrDefault()) {
        use(connection, schema);
        for (MigrationRunner.MigrationStatus row : runner().statusOf(connection, migrations)) {
          rows.add(new MigrationInfo(schema, row.version(), State.valueOf(row.state().name()),
              row.detail()));
        }
      }
    }
    return new Status(rows);
  }

  /**
   * Rolls back the newest applied migrations with their rollback scripts, in every configured
   * schema. Restores structure, not deleted data.
   *
   * @param steps how many migrations to roll back
   * @return the versions rolled back, newest first (from all schemas)
   */
  public List<String> rollback(int steps) throws Exception {
    if (steps < 1) {
      throw new IllegalArgumentException("steps must be 1 or more.");
    }
    return rollback(applied -> {
      List<String> targets = new ArrayList<>();
      for (int i = applied.size() - 1; i >= 0 && targets.size() < steps; i--) {
        targets.add(applied.get(i));
      }
      return targets;
    });
  }

  /**
   * Rolls back every migration applied after {@code version}, which stays applied.
   *
   * @param version a migration name such as {@code 0003_add_email.sql}, or its start
   *     ({@code 0003})
   * @return the versions rolled back, newest first (from all schemas)
   */
  public List<String> rollbackTo(String version) throws Exception {
    Objects.requireNonNull(version, "version");
    return rollback(applied -> {
      String keep = resolve(version, applied);
      List<String> targets = new ArrayList<>();
      for (String migration : applied) {
        if (MigrationRunner.compareMigrationFilenames(migration, keep) > 0) {
          targets.add(0, migration);
        }
      }
      return targets;
    });
  }

  /**
   * Compares the entities with the database: the tables, columns, keys and checks the entities
   * need must be there, with matching types and nullability. Tables and nullable columns that
   * no entity maps are allowed (other code may use them); a NOT NULL column without a default
   * that no entity maps is a problem, because the application's inserts would fail. The entities are read
   * through the application's own JPA provider (Hibernate, EclipseLink or OpenJPA) when it is
   * on the class path, otherwise from their annotations.
   *
   * <p>The entity package is the one passed to {@link Builder#entities}; without it, the same
   * one the {@code migrax} command uses ({@code migrax.package}, else the Maven groupId).
   */
  public Validation validate() throws Exception {
    String basePackage = entityPackage != null ? entityPackage
        : ProjectPackage.entityPackage(projectDirectory);
    if (basePackage == null || basePackage.isBlank()) {
      throw new IllegalStateException("The entity package is unknown. Pass it with "
          + "entities(\"com.example.model\") or set migrax.package.");
    }
    Path resources = projectDirectory.resolve("src/main/resources");
    Path snapshot = SnapshotStore.projectSnapshot(projectDirectory);
    String recorded = SnapshotStore.naming(snapshot);
    NamingStrategy namingStrategy = ProjectDatabaseConfig.namingStrategy(resources,
        projectDirectory, naming, recorded, recorded == null && Files.exists(snapshot));
    Map<String, String> config = ProjectDatabaseConfig.settings(resources);
    Map<String, String> readerSettings =
        new LinkedHashMap<>(ProjectDatabaseConfig.hibernateSettings(config));
    String enumChecks = ProjectDatabaseConfig.migraxSetting(config, "enum-checks");
    if (enumChecks != null) {
      readerSettings.put("migrax.enum-checks", enumChecks.trim());
    }
    List<String> problems = new ArrayList<>();
    try (Connection connection = connect()) {
      String product = connection.getMetaData().getDatabaseProductName();
      Dialect dialect = Dialects.forConnection(connection).orElseThrow(() ->
          new IllegalStateException("Migrax does not support this database: " + product));
      ModelExtractor.Result expected = ModelExtractor.extract(loader, basePackage,
          namingStrategy, dialect, readerSettings, ModelExtractor.Mode.AUTO);
      for (String schema : schemasOrDefault()) {
        use(connection, schema);
        SchemaModel actual = DatabaseSchemaReader.read(connection);
        for (SchemaComparator.Difference difference
            : SchemaComparator.compare(expected.model(), actual, dialect.id())) {
          if (!harmless(difference, actual)) {
            problems.add((schema == null ? "" : schema + ": ") + difference);
          }
        }
      }
      return new Validation(problems, expected.source());
    }
  }

  /**
   * Tests the rollback scripts on an already migrated database: rolls each applied migration
   * back on its own, applies it again and checks that the schema is the same. Use it on a test
   * database only: rolling back drops data.
   */
  public RollbackTest testRollbacks() throws Exception {
    List<String> results = new ArrayList<>();
    boolean ok = true;
    try (Connection connection = connect()) {
      String dialect = Dialects.forConnection(connection).map(Dialect::id).orElse("");
      for (String schema : schemasOrDefault()) {
        use(connection, schema);
        String result = RollbackVerifier.check(connection, runner(), migrations, options,
            dialect, this::rollbackSql);
        ok &= !result.startsWith("failed");
        results.add(schema == null ? result : schema + ": " + result);
      }
    }
    return new RollbackTest(ok, String.join("; ", results));
  }

  // ------------------------------------------------------------------ helpers

  /**
   * Tables and columns the entities don't map, which other code may use. A column the
   * application's inserts can't leave out (NOT NULL without a default) still counts.
   */
  private static boolean harmless(SchemaComparator.Difference difference, SchemaModel actual) {
    if (difference.kind().equals("extra_table")) {
      return true;
    }
    if (!difference.kind().equals("extra_column")) {
      return false;
    }
    int dot = difference.object().lastIndexOf('.');
    String table = difference.object().substring(0, dot);
    String column = difference.object().substring(dot + 1);
    return actual.tables().stream().filter(t -> t.name().equalsIgnoreCase(table))
        .flatMap(t -> t.columns().stream()).filter(c -> c.name().equalsIgnoreCase(column))
        .allMatch(c -> c.nullable() || c.defaultValue() != null || c.identity());
  }

  private interface Targets {
    List<String> choose(List<String> applied);
  }

  private List<String> rollback(Targets targets) throws Exception {
    Map<String, Migration> java = new LinkedHashMap<>();
    migrations.stream().filter(Migration::isJava).forEach(m -> java.put(m.version(), m));
    MigrationRunner runner = runner();
    List<String> rolledBack = new ArrayList<>();
    try (Connection connection = connect()) {
      for (String schema : schemasOrDefault()) {
        use(connection, schema);
        List<String> applied = runner.applied(connection).stream()
            .map(MigrationRunner.AppliedMigration::version)
            .filter(version -> !version.startsWith("R__")).toList();
        List<String> chosen = targets.choose(applied);
        List<Migration> scripts = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String version : chosen) {
          String sql = java.containsKey(version) ? null : rollbackSql(version);
          if (java.containsKey(version)) {
            scripts.add(java.get(version));
          } else if (sql == null) {
            missing.add(version);
          } else {
            scripts.add(Migration.ofSql(version, sql));
          }
        }
        if (!missing.isEmpty()) {
          throw new IllegalStateException("No rollback script for " + String.join(", ", missing)
              + ". Write one in " + MigrationLoader.ROLLBACK_FOLDER + "/<same file name> next "
              + "to the migration.");
        }
        for (int i = 0; i < chosen.size(); i++) {
          runner.rollback(connection, chosen.get(i), scripts.get(i), options);
          rolledBack.add(chosen.get(i));
        }
      }
    }
    return rolledBack;
  }

  private static String resolve(String wanted, List<String> versions) {
    for (String version : versions) {
      if (version.equals(wanted) || version.equals(wanted + ".sql")) {
        return version;
      }
    }
    for (String version : versions) {
      if (version.startsWith(wanted)) {
        return version;
      }
    }
    throw new IllegalStateException("Migration '" + wanted + "' is not applied here.");
  }

  /** The rollback script of a SQL migration from the first location that has one, or null. */
  private String rollbackSql(String version) throws IOException {
    for (String location : locations) {
      Path folder = folder(location);
      if (folder != null) {
        Path script = MigrationLoader.rollbackScript(folder, version);
        if (script != null) {
          return Files.readString(script, StandardCharsets.UTF_8);
        }
      } else {
        String resource = classpathFolder(location) + "/" + MigrationLoader.ROLLBACK_FOLDER + "/"
            + version;
        try (InputStream in = loader.getResourceAsStream(resource)) {
          if (in != null) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
          }
        }
      }
    }
    return null;
  }

  private MigrationRunner runner() {
    return new MigrationRunner().withLockTimeout(lockTimeout != null ? lockTimeout
        : DatabaseMigrationLock.configuredTimeout()).withEnvironment(environment)
        .withListeners(listeners);
  }

  private Connection connect() throws SQLException {
    return dataSource != null ? dataSource.getConnection()
        : RuntimeJdbc.connect(loader, url, user, password);
  }

  private List<String> schemasOrDefault() {
    List<String> result = new ArrayList<>(schemas);
    if (result.isEmpty()) {
      result.add(null);
    }
    return result;
  }

  private static void use(Connection connection, String schema) throws SQLException {
    if (schema != null) {
      TargetSchema.use(connection, schema);
    }
  }

  /** The folder on disk of a {@code filesystem:} location, or null for a classpath one. */
  private static Path folder(String location) {
    return location.startsWith("filesystem:")
        ? Path.of(location.substring("filesystem:".length())) : null;
  }

  private static String classpathFolder(String location) {
    return location.replace("classpath:", "").replaceAll("^/+", "").replaceAll("/+$", "");
  }

  private List<Migration> load(Builder builder) throws Exception {
    List<String> classpath = new ArrayList<>();
    List<Path> folders = new ArrayList<>();
    for (String location : locations) {
      if (folder(location) != null) {
        folders.add(folder(location));
      } else {
        classpath.add(location);
      }
    }
    Map<String, Migration> byVersion = new LinkedHashMap<>();
    List<Migration> found = new ArrayList<>(ClasspathMigrations.load(loader, classpath));
    found.addAll(Migration.load(MigrationLoader.sqlFiles(folders)));
    for (Map.Entry<String, String> sql : builder.sql.entrySet()) {
      found.add(Migration.ofSql(sql.getKey(), sql.getValue()));
    }
    for (Migration migration : found) {
      if (byVersion.putIfAbsent(migration.version(), migration) != null) {
        throw new IllegalStateException("Two migration locations contain "
            + migration.version() + ". Migration file names must be unique across all "
            + "locations.");
      }
    }
    if (builder.javaPackage != null) {
      MigrationLoader.javaMigrations(loader, builder.javaPackage)
          .forEach(m -> byVersion.put(m.version(), m));
    }
    builder.javaMigrations.forEach(m -> byVersion.put(m.version(), Migration.ofJava(m)));
    return List.copyOf(byVersion.values());
  }

  // ------------------------------------------------------------------ builder

  /** The settings of a {@link Migrax} instance. Only the database is required. */
  public static final class Builder {
    private DataSource dataSource;
    private String url;
    private String user;
    private String password;
    private ClassLoader loader;
    private List<String> locations;
    private String locationsText;
    private String javaPackage = MigrationLoader.DEFAULT_JAVA_PACKAGE;
    private final List<JavaMigration> javaMigrations = new ArrayList<>();
    private final Map<String, String> sql = new LinkedHashMap<>();
    private List<String> schemas = List.of();
    private final Map<String, String> placeholders = new LinkedHashMap<>();
    private String environment;
    private Duration lockTimeout;
    private boolean resume;
    private String entityPackage;
    private String naming;
    private Path projectDirectory;
    private final List<MigrationListener> listeners = new ArrayList<>();

    private Builder() {}

    /**
     * Listeners for metrics and tracing of {@link Migrax#migrate}, such as
     * {@code new org.migrax.observe.MicrometerListener(meterRegistry)}.
     *
     * @since 0.13.0
     */
    public Builder listener(MigrationListener... listeners) {
      return listeners(List.of(listeners));
    }

    /**
     * Listeners for metrics and tracing of {@link Migrax#migrate}.
     *
     * @since 0.13.0
     */
    public Builder listeners(Collection<? extends MigrationListener> listeners) {
      this.listeners.addAll(listeners);
      return this;
    }

    /** The database, as a data source. */
    public Builder dataSource(DataSource dataSource) {
      this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
      return this;
    }

    /**
     * The database, as a JDBC URL. The driver is looked up on the class loader
     * ({@link #classLoader}).
     */
    public Builder url(String url, String user, String password) {
      this.url = Objects.requireNonNull(url, "url");
      this.user = user;
      this.password = password;
      return this;
    }

    /** Where Java migrations and SQL migrations on the class path are found. */
    public Builder classLoader(ClassLoader loader) {
      this.loader = loader;
      return this;
    }

    /**
     * Migration folders (default {@code db/migration}): classpath folders, or folders on disk
     * with {@code filesystem:}. A file name may appear in only one of them.
     */
    public Builder locations(String... locations) {
      return locations(List.of(locations));
    }

    /** Migration folders; an empty list reads only the migrations added in code. */
    public Builder locations(Collection<String> locations) {
      this.locations = locations.stream().map(String::trim).filter(l -> !l.isEmpty())
          .distinct().toList();
      this.locationsText = null;
      return this;
    }

    /**
     * The package with {@link JavaMigration} classes (default {@code db.migration}); null to
     * find none.
     */
    public Builder javaPackage(String javaPackage) {
      this.javaPackage = javaPackage == null || javaPackage.isBlank() ? null : javaPackage;
      return this;
    }

    /** Java migrations to run besides the ones in the Java migrations package. */
    public Builder javaMigrations(JavaMigration... migrations) {
      return javaMigrations(List.of(migrations));
    }

    /** Java migrations to run besides the ones in the Java migrations package. */
    public Builder javaMigrations(Collection<? extends JavaMigration> migrations) {
      javaMigrations.addAll(migrations);
      return this;
    }

    /**
     * A SQL migration that is not in a location, for frameworks that find the files
     * themselves.
     *
     * @param fileName the file name, such as {@code 0001_initial.sql}; it is the version
     */
    public Builder sqlMigration(String fileName, String sql) {
      this.sql.put(Objects.requireNonNull(fileName, "fileName"), Objects.requireNonNull(sql,
          "sql"));
      return this;
    }

    /** Schemas to migrate one after another (multi-tenant); none for the default schema. */
    public Builder schemas(String... schemas) {
      return schemas(List.of(schemas));
    }

    /** Schemas to migrate one after another (multi-tenant); empty for the default schema. */
    public Builder schemas(Collection<String> schemas) {
      this.schemas = schemas.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
      return this;
    }

    /** A value for {@code ${name}} in the migrations. */
    public Builder placeholder(String name, String value) {
      placeholders.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value,
          "value"));
      return this;
    }

    /** Values for {@code ${name}} in the migrations. */
    public Builder placeholders(Map<String, String> values) {
      values.forEach(this::placeholder);
      return this;
    }

    /**
     * The environment: migrations marked {@code -- migrax:env <name>} run only in the named
     * environments.
     */
    public Builder environment(String environment) {
      this.environment = environment == null || environment.isBlank() ? null : environment;
      return this;
    }

    /**
     * How long to wait while another instance holds the migration lock (default: fail at
     * once, or {@code MIGRAX_LOCK_TIMEOUT}).
     */
    public Builder lockTimeout(Duration timeout) {
      this.lockTimeout = timeout;
      return this;
    }

    /** Continue a migration that failed half way, when its steps are marked as safe to rerun. */
    public Builder resume(boolean resume) {
      this.resume = resume;
      return this;
    }

    /** The package that holds the entities, for {@link #validate}. */
    public Builder entities(String basePackage) {
      this.entityPackage = basePackage == null || basePackage.isBlank() ? null : basePackage;
      return this;
    }

    /**
     * How entity names become table and column names ({@code spring}, {@code jpa},
     * {@code snake}), for {@link #validate}. Default: as the {@code migrax} command decides.
     */
    public Builder naming(String naming) {
      this.naming = naming == null || naming.isBlank() ? null : naming;
      return this;
    }

    /**
     * The project folder, for {@link #validate}: its application config, snapshot and build
     * file tell the entity package and naming. Default: the working directory.
     */
    public Builder projectDirectory(Path directory) {
      this.projectDirectory = directory;
      return this;
    }

    /**
     * Applies {@code migrax.*} settings, with full keys, as the framework integrations read
     * them from application config: {@code migrax.locations} (comma-separated),
     * {@code migrax.java-package}, {@code migrax.resume}, {@code migrax.schemas},
     * {@code migrax.lock-timeout} (such as {@code 2m}), {@code migrax.env},
     * {@code migrax.placeholders.<name>}, {@code migrax.package} and {@code migrax.naming}.
     * Missing and blank settings keep their current value.
     */
    public Builder settings(Map<String, String> settings) {
      String locations = setting(settings, "migrax.locations");
      if (locations != null) {
        locations(List.of(locations.split(",")));
        this.locationsText = locations;
      }
      String java = setting(settings, "migrax.java-package");
      if (java != null) {
        javaPackage(java);
      }
      String resume = setting(settings, "migrax.resume");
      if (resume != null) {
        resume(Boolean.parseBoolean(resume));
      }
      String schemas = setting(settings, "migrax.schemas");
      if (schemas != null) {
        schemas(List.of(schemas.split(",")));
      }
      String timeout = setting(settings, "migrax.lock-timeout");
      if (timeout != null) {
        lockTimeout(DatabaseMigrationLock.parseTimeout(timeout));
      }
      String env = setting(settings, "migrax.env");
      if (env != null) {
        environment(env);
      }
      settings.forEach((key, value) -> {
        if (key.startsWith("migrax.placeholders.") && value != null) {
          placeholder(key.substring("migrax.placeholders.".length()), value);
        }
      });
      String entities = setting(settings, "migrax.package");
      if (entities != null) {
        entities(entities);
      }
      String names = setting(settings, "migrax.naming");
      if (names != null) {
        naming(names);
      }
      return this;
    }

    /** Reads the migrations and returns the configured instance. */
    public Migrax load() throws Exception {
      return new Migrax(this);
    }

    private static String setting(Map<String, String> settings, String key) {
      String value = settings.get(key);
      return value == null || value.isBlank() ? null : value.trim();
    }
  }
}
