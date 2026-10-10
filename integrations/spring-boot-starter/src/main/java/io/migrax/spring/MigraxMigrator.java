package io.migrax.spring;

import io.migrax.api.JavaMigration;
import io.migrax.cli.Main;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import io.migrax.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
  private static final org.apache.commons.logging.Log LOG = LogFactory.getLog("io.migrax");

  private final DataSource dataSource;
  private final MigraxProperties properties;
  private final ResourcePatternResolver resources;
  private final List<JavaMigration> javaMigrationBeans;
  private final Environment environment;
  private final List<String> basePackages;

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

  @Override
  public void afterPropertiesSet() throws Exception {
    Log.Sink previous = Log.setSink(new CommonsSink());
    try {
      Path devFolder = properties.getDev().isGenerate() ? generateForDevelopment() : null;
      List<Migration> migrations = devFolder != null && Files.isDirectory(devFolder)
          ? MigrationLoader.load(devFolder, classLoader(), properties.getJavaPackage())
          : loadPackaged();
      Map<String, Migration> byVersion = new LinkedHashMap<>();
      migrations.forEach(m -> byVersion.put(m.version(), m));
      javaMigrationBeans.forEach(bean -> byVersion.put(bean.version(), Migration.ofJava(bean)));
      List<Migration> all = new ArrayList<>(byVersion.values());
      if (all.isEmpty()) {
        LOG.info("Migrax: no migrations found in " + properties.getLocations());
        return;
      }
      MigrationRunner.Options options =
          new MigrationRunner.Options(properties.isResume(), properties.getPlaceholders());
      try (Connection connection = dataSource.getConnection()) {
        List<String> schemas = properties.getSchemas();
        if (schemas.isEmpty()) {
          report(new MigrationRunner().withLockTimeout(properties.getLockTimeout())
              .migrateAll(connection, all, options), null);
        } else {
          for (String schema : schemas) {
            useSchema(connection, schema);
            report(new MigrationRunner().withLockTimeout(properties.getLockTimeout())
                .migrateAll(connection, all, options), schema);
          }
        }
      }
    } finally {
      Log.setSink(previous);
    }
  }

  private void report(int applied, String schema) {
    LOG.info("Migrax: " + (applied == 0 ? "schema is up to date"
        : "applied " + applied + " migration(s)") + (schema == null ? "" : " in " + schema));
  }

  private ClassLoader classLoader() {
    ClassLoader loader = resources.getClassLoader();
    return loader != null ? loader : Thread.currentThread().getContextClassLoader();
  }

  private List<Migration> loadPackaged() throws Exception {
    String location = properties.getLocations().trim();
    if (location.startsWith("filesystem:")) {
      location = "file:" + location.substring("filesystem:".length());
    } else if (!location.startsWith("classpath:") && !location.startsWith("file:")) {
      location = "classpath:" + location;
    }
    List<Migration> migrations = new ArrayList<>();
    for (Resource resource : resources.getResources(location.replaceAll("/+$", "") + "/*.sql")) {
      String name = resource.getFilename();
      if (name != null && resource.isReadable()) {
        migrations.add(Migration.ofSql(name,
            resource.getContentAsString(StandardCharsets.UTF_8)));
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
    if (!properties.getLocations().startsWith("classpath:")) {
      add(args, "--locations", properties.getLocations());
    }
    int code = Main.run(args.toArray(String[]::new), lines(false), lines(true));
    if (code != 0) {
      LOG.warn("Migrax: development generation did not write a migration (see above).");
    }
    String location = properties.getLocations();
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

  private static void useSchema(Connection connection, String schema) throws Exception {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    if (product.contains("mysql") || product.contains("mariadb")) {
      connection.setCatalog(schema);
    } else {
      connection.setSchema(schema);
    }
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
