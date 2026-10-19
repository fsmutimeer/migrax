package org.migrax.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code migrax.*} in application.properties or application.yml. */
@ConfigurationProperties("migrax")
public class MigraxProperties {
  /** Apply migrations when the application starts. */
  private boolean enabled = true;

  /** Migration folders, separated by commas. */
  private String locations = "classpath:db/migration";

  /** Environment name: migrations marked '-- migrax:env <name>' run only there. */
  private String env;

  /** Package scanned for JavaMigration classes, in addition to JavaMigration beans. */
  private String javaPackage = "db.migration";

  /** Re-run a failed migration marked '-- migrax:resume-safe'. */
  private boolean resume;

  /**
   * How long to wait while another instance holds the migration lock, for example {@code 2m}
   * when several instances start together; zero fails at once.
   */
  private java.time.Duration lockTimeout = java.time.Duration.ZERO;

  /** Values for ${name} placeholders in migrations. */
  private Map<String, String> placeholders = new LinkedHashMap<>();

  /** Schemas to migrate one after another (multi-tenant); empty means the default schema. */
  private List<String> schemas = new ArrayList<>();

  /** Development settings. */
  private final Dev dev = new Dev();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Apply migrations at startup; false leaves them to a pipeline or a DBA while health and
   * metrics still report them.
   */
  private boolean migrateAtStartup = true;

  public boolean isMigrateAtStartup() {
    return migrateAtStartup;
  }

  public void setMigrateAtStartup(boolean migrateAtStartup) {
    this.migrateAtStartup = migrateAtStartup;
  }

  public String getLocations() {
    return locations;
  }

  public void setLocations(String locations) {
    this.locations = locations;
  }

  public String getEnv() {
    return env;
  }

  public void setEnv(String env) {
    this.env = env;
  }

  public String getJavaPackage() {
    return javaPackage;
  }

  public void setJavaPackage(String javaPackage) {
    this.javaPackage = javaPackage;
  }

  public java.time.Duration getLockTimeout() {
    return lockTimeout;
  }

  public void setLockTimeout(java.time.Duration lockTimeout) {
    this.lockTimeout = lockTimeout;
  }

  public boolean isResume() {
    return resume;
  }

  public void setResume(boolean resume) {
    this.resume = resume;
  }

  public Map<String, String> getPlaceholders() {
    return placeholders;
  }

  public void setPlaceholders(Map<String, String> placeholders) {
    this.placeholders = placeholders;
  }

  public List<String> getSchemas() {
    return schemas;
  }

  public void setSchemas(List<String> schemas) {
    this.schemas = schemas;
  }

  public Dev getDev() {
    return dev;
  }

  /** Micrometer metrics of migration runs, when the application has a MeterRegistry. */
  private final Toggle metrics = new Toggle();

  /** OpenTelemetry spans of migration runs, when the application has an OpenTelemetry. */
  private final Toggle tracing = new Toggle();

  public Toggle getMetrics() {
    return metrics;
  }

  public Toggle getTracing() {
    return tracing;
  }

  /**
   * Further databases, by name: {@code migrax.databases.billing.datasource=billingDataSource}
   * (the DataSource bean; default: the name itself), {@code locations} (default
   * {@code classpath:db/<name>}), {@code java-package} (default {@code db.<name>}), {@code schemas} and {@code placeholders}. {@code env},
   * {@code lock-timeout}, {@code resume} and {@code migrate-at-startup} default to the settings
   * above.
   */
  private final Map<String, Database> databases = new LinkedHashMap<>();

  public Map<String, Database> getDatabases() {
    return databases;
  }

  /**
   * The settings of one further database, as the settings of a single-database application:
   * its own folders, schemas and placeholders, and the shared settings it doesn't set itself.
   * Development generation stays with the default database.
   */
  MigraxProperties forDatabase(String name) {
    org.migrax.runner.Databases.checkName(name);
    Database database = databases.get(name);
    MigraxProperties result = new MigraxProperties();
    result.setLocations(database.getLocations() != null ? database.getLocations()
        : "classpath:db/" + name);
    result.setJavaPackage(database.getJavaPackage() != null ? database.getJavaPackage()
        : "db." + name);
    result.setSchemas(database.getSchemas());
    result.setPlaceholders(database.getPlaceholders());
    result.setEnv(database.getEnv() != null ? database.getEnv() : env);
    result.setLockTimeout(database.getLockTimeout() != null ? database.getLockTimeout()
        : lockTimeout);
    result.setResume(database.getResume() != null ? database.getResume() : resume);
    result.setMigrateAtStartup(database.getMigrateAtStartup() != null
        ? database.getMigrateAtStartup() : migrateAtStartup);
    result.getMetrics().setEnabled(metrics.isEnabled());
    result.getTracing().setEnabled(tracing.isEnabled());
    return result;
  }

  /** One further database ({@code migrax.databases.<name>.*}). */
  public static class Database {
    /** The DataSource bean to migrate; default: the database's name. */
    private String datasource;

    /** Migration folders, separated by commas; default classpath:db/<name>. */
    private String locations;

    /** Package scanned for JavaMigration classes; default db.<name>. */
    private String javaPackage;

    /** Schemas to migrate one after another; empty means the default schema. */
    private List<String> schemas = new ArrayList<>();

    /** Values for ${name} placeholders in this database's migrations. */
    private Map<String, String> placeholders = new LinkedHashMap<>();

    /** Environment name; default: migrax.env. */
    private String env;

    /** How long to wait for the migration lock; default: migrax.lock-timeout. */
    private java.time.Duration lockTimeout;

    /** Re-run a failed resume-safe migration; default: migrax.resume. */
    private Boolean resume;

    /** Apply this database's migrations at startup; default: migrax.migrate-at-startup. */
    private Boolean migrateAtStartup;

    public String getDatasource() {
      return datasource;
    }

    public void setDatasource(String datasource) {
      this.datasource = datasource;
    }

    public String getLocations() {
      return locations;
    }

    public void setLocations(String locations) {
      this.locations = locations;
    }

    public String getJavaPackage() {
      return javaPackage;
    }

    public void setJavaPackage(String javaPackage) {
      this.javaPackage = javaPackage;
    }

    public List<String> getSchemas() {
      return schemas;
    }

    public void setSchemas(List<String> schemas) {
      this.schemas = schemas;
    }

    public Map<String, String> getPlaceholders() {
      return placeholders;
    }

    public void setPlaceholders(Map<String, String> placeholders) {
      this.placeholders = placeholders;
    }

    public String getEnv() {
      return env;
    }

    public void setEnv(String env) {
      this.env = env;
    }

    public java.time.Duration getLockTimeout() {
      return lockTimeout;
    }

    public void setLockTimeout(java.time.Duration lockTimeout) {
      this.lockTimeout = lockTimeout;
    }

    public Boolean getResume() {
      return resume;
    }

    public void setResume(Boolean resume) {
      this.resume = resume;
    }

    public Boolean getMigrateAtStartup() {
      return migrateAtStartup;
    }

    public void setMigrateAtStartup(Boolean migrateAtStartup) {
      this.migrateAtStartup = migrateAtStartup;
    }
  }

  /** A feature that is on unless turned off. */
  public static class Toggle {
    private boolean enabled = true;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }
  }

  /** Settings for local development. */
  public static class Dev {
    /**
     * Generate a migration for entity changes at startup, like running 'migrax generate'
     * before every restart. Only for development: it writes files into src/main/resources.
     */
    private boolean generate;

    /** Allow drops in generated development migrations. */
    private boolean allowDestructive;

    public boolean isGenerate() {
      return generate;
    }

    public void setGenerate(boolean generate) {
      this.generate = generate;
    }

    public boolean isAllowDestructive() {
      return allowDestructive;
    }

    public void setAllowDestructive(boolean allowDestructive) {
      this.allowDestructive = allowDestructive;
    }
  }
}
