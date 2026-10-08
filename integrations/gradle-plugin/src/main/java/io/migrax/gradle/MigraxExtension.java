package io.migrax.gradle;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

/**
 * The {@code migrax { ... }} block. Every setting is optional: by default Migrax reads the
 * database settings from application.properties/yml like the CLI does.
 */
public abstract class MigraxExtension {
  /** Entity package; defaults to MIGRAX_PACKAGE. */
  public abstract Property<String> getPackageName();

  public abstract Property<String> getUrl();

  public abstract Property<String> getUser();

  public abstract Property<String> getPassword();

  /** Migration folder, e.g. classpath:db/migration. */
  public abstract Property<String> getLocations();

  /** spring, jpa, jpa-snake or micronaut. */
  public abstract Property<String> getNaming();

  /** postgresql, mysql, mariadb, sqlserver, oracle or h2. */
  public abstract Property<String> getDialect();

  /** Extra arguments for every task, e.g. --verbose. */
  public abstract ListProperty<String> getArgs();

  /** Migrax version used to run the tasks; defaults to the plugin's version. */
  public abstract Property<String> getVersion();
}
