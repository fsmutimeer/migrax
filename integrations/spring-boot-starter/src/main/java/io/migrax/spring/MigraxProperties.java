package io.migrax.spring;

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

  /** Migration folder. */
  private String locations = "classpath:db/migration";

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
