package org.migrax.spring;

import org.migrax.api.JavaMigration;
import org.migrax.api.Migrax;
import org.migrax.cli.Main;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationLoader;
import org.migrax.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Applies migrations when the application context starts. JPA's EntityManagerFactory depends
 * on this bean, so Hibernate only starts (and validates) after the schema is up to date.
 */
public class MigraxMigrator implements InitializingBean {
  private static final org.apache.commons.logging.Log LOG = LogFactory.getLog("org.migrax");

  private final DataSource dataSource;
  private final MigraxProperties properties;
  private final ResourcePatternResolver resources;
  private final List<JavaMigration> javaMigrationBeans;
  private final Environment environment;
  private final List<String> basePackages;
  private org.springframework.beans.factory.ListableBeanFactory beans;

  public MigraxMigrator(DataSource dataSource, MigraxProperties properties,
                        ResourcePatternResolver resources, List<JavaMigration> javaMigrationBeans,
                        Environment environment, List<String> basePackages) {
    this.dataSource = dataSource;
    this.properties = properties;
    this.resources = resources;
    this.javaMigrationBeans = javaMigrationBeans;
    this.environment = environment;
    this.basePackages = basePackages;
  }

  /**
   * Where the migration listeners come from (MigrationListener beans, Micrometer,
   * OpenTelemetry); without it, migrations run without listeners.
   *
   * @since 0.13.0
   */
  public void setBeanFactory(org.springframework.beans.factory.ListableBeanFactory beans) {
    this.beans = beans;
  }

  /**
   * Whether the database is up to date with the packaged migrations and JavaMigration beans,
   * without changing anything; the health indicator reports it.
   */
  public org.migrax.runner.StartupMigrations.Status status() throws Exception {
    Migrax.Status status = migrax(loadPackaged(), List.of()).status();
    return new org.migrax.runner.StartupMigrations.Status(status.applied(), status.pending(),
        status.problems());
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    if (!properties.isMigrateAtStartup()) {
      LOG.info("Migrax: " + org.migrax.runner.StartupMigrations.notMigrating(this::status));
      return;
    }
    Log.Sink previous = Log.setSink(new CommonsSink());
    try {
      Path devFolder = properties.getDev().isGenerate() ? generateForDevelopment() : null;
      List<Migration> migrations = new ArrayList<>();
      if (devFolder == null || !Files.isDirectory(devFolder)) {
        migrations.addAll(loadPackaged());
      } else {
        // The source folder replaces its packaged copy; other locations come as packaged.
        if (properties.getLocations().contains(",")) {
          migrations.addAll(loadPackaged());
        }
        migrations.addAll(MigrationLoader.load(devFolder, classLoader(),
            properties.getJavaPackage()));
      }
      if (migrations.isEmpty() && javaMigrationBeans.isEmpty()) {
        LOG.info("Migrax: no migrations found in " + properties.getLocations());
        return;
      }
      List<org.migrax.api.MigrationListener> listeners = beans == null ? List.of()
          : MigraxListeners.find(beans, properties, classLoader());
      for (Migrax.SchemaResult result : migrax(migrations, listeners).migrate().schemas()) {
        report(result.applied(), result.schema());
      }
    } finally {
      Log.setSink(previous);
    }
  }

  private void report(int applied, String schema) {
    LOG.info("Migrax: " + (applied == 0 ? "schema is up to date"
        : "applied " + applied + " migration(s)") + (schema == null ? "" : " in " + schema));
  }

  /**
   * The API over the given migrations plus the JavaMigration beans; a bean replaces a
   * migration with the same version.
   */
  private Migrax migrax(List<Migration> migrations,
                        List<org.migrax.api.MigrationListener> listeners) throws Exception {
    Migrax.Builder builder = Migrax.configure().dataSource(dataSource)
        .classLoader(classLoader()).locations(List.of()).javaPackage(null)
        .schemas(properties.getSchemas()).environment(properties.getEnv())
        .lockTimeout(properties.getLockTimeout() == null ? java.time.Duration.ZERO
            : properties.getLockTimeout()).resume(properties.isResume())
        .placeholders(properties.getPlaceholders()).listeners(listeners);
    Map<String, Migration> byVersion = new LinkedHashMap<>();
    migrations.forEach(m -> byVersion.put(m.version(), m));
    javaMigrationBeans.forEach(bean -> byVersion.put(bean.version(), Migration.ofJava(bean)));
    for (Migration migration : byVersion.values()) {
      if (migration.isJava()) {
        builder.javaMigrations(migration.java());
      } else {
        builder.sqlMigration(migration.version(), migration.sql());
      }
    }
    return builder.load();
  }

  private ClassLoader classLoader() {
    ClassLoader loader = resources.getClassLoader();
    return loader != null ? loader : Thread.currentThread().getContextClassLoader();
  }

  private List<Migration> loadPackaged() throws Exception {
    List<Migration> migrations = new ArrayList<>();
    java.util.Map<String, String> locationOf = new java.util.HashMap<>();
    for (String configured : properties.getLocations().split(",")) {
      String location = configured.trim();
      if (location.isEmpty()) {
        continue;
      }
      if (location.startsWith("filesystem:")) {
        location = "file:" + location.substring("filesystem:".length());
      } else if (!location.startsWith("classpath:") && !location.startsWith("file:")) {
        location = "classpath:" + location;
      }
      for (Resource resource
          : resources.getResources(location.replaceAll("/+$", "") + "/*.sql")) {
        String name = resource.getFilename();
        if (name != null && resource.isReadable()) {
          String other = locationOf.putIfAbsent(name, configured.trim());
          if (other != null) {
            throw new IllegalStateException("Two migration locations contain " + name + ": "
                + other + " and " + configured.trim()
                + ". Migration file names must be unique across all locations.");
          }
          migrations.add(Migration.ofSql(name,
              resource.getContentAsString(StandardCharsets.UTF_8)));
        }
      }
    }
    migrations.addAll(MigrationLoader.javaMigrations(classLoader(), properties.getJavaPackage()));
    return migrations;
  }

  /**
   * Development mode: runs 'migrax generate' for the source tree the application was started
   * from, so a changed entity gets its migration on restart.
   *
   * @return the migration folder in the source tree, or null when there is none
   */
  private Path generateForDevelopment() {
    Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    if (!Files.isDirectory(root.resolve("src/main/resources")) || basePackages.isEmpty()) {
      LOG.info("Migrax: migrax.dev.generate is on, but " + root
          + " is not a source tree; skipping generation.");
      return null;
    }
    List<String> args = new ArrayList<>(List.of("generate", "--dir", root.toString(),
        "--no-build", "--no-input", "--package", basePackages.get(0)));
    if (properties.getDev().isAllowDestructive()) {
      args.add("--allow-destructive");
    }
    add(args, "--url", environment.getProperty("spring.datasource.url"));
    add(args, "--user", environment.getProperty("spring.datasource.username"));
    add(args, "--password", environment.getProperty("spring.datasource.password"));
    if (!properties.getLocations().startsWith("classpath:")
        || properties.getLocations().contains(",")) {
      add(args, "--locations", properties.getLocations());
    }
    int code = Main.run(args.toArray(String[]::new), lines(false), lines(true));
    if (code != 0) {
      LOG.warn("Migrax: development generation did not write a migration (see above).");
    }
    // New migrations are written to the first location.
    String location = properties.getLocations().split(",")[0].trim();
    return location.startsWith("classpath:")
        ? root.resolve("src/main/resources").resolve(location.substring("classpath:".length())
            .replaceAll("^/+", ""))
        : root.resolve(location.replace("filesystem:", ""));
  }

  private static void add(List<String> args, String option, String value) {
    if (value != null && !value.isBlank()) {
      args.add(option);
      args.add(value);
    }
  }

  private static PrintStream lines(boolean warn) {
    return new PrintStream(new OutputStream() {
      private final ByteArrayOutputStream line = new ByteArrayOutputStream();

      @Override
      public void write(int b) {
        if (b == '\n') {
          String text = line.toString(StandardCharsets.UTF_8).stripTrailing();
          if (!text.isEmpty()) {
            if (warn) {
              LOG.warn("Migrax: " + text);
            } else {
              LOG.info("Migrax: " + text);
            }
          }
          line.reset();
        } else {
          line.write(b);
        }
      }
    }, true, StandardCharsets.UTF_8);
  }

  /** Sends Migrax core messages to Spring's logging. */
  private static final class CommonsSink implements Log.Sink {
    @Override
    public void debug(String message) {
      LOG.debug("Migrax: " + message);
    }

    @Override
    public void info(String message) {
      LOG.info("Migrax: " + message);
    }

    @Override
    public void warn(String message) {
      LOG.warn("Migrax: " + message);
    }

    @Override
    public void error(String message) {
      LOG.error("Migrax: " + message);
    }
  }
}
