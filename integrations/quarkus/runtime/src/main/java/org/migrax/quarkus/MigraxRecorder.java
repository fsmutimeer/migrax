package org.migrax.quarkus;

import io.quarkus.runtime.annotations.Recorder;

import javax.sql.DataSource;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Runs the migrations while the application starts. The extension's build step records the
 * call so that it happens before Hibernate ORM starts its persistence units.
 */
@Recorder
public class MigraxRecorder {
  private static final Logger LOG = Logger.getLogger("org.migrax");

  /**
   * Applies the pending migrations to the default data source, then to each further database
   * (migrax.databases.<name>), unless migrax.enabled=false.
   */
  public void migrate() {
    Config config = ConfigProvider.getConfig();
    if (!config.getOptionalValue("migrax.enabled", Boolean.class).orElse(true)) {
      return;
    }
    MigraxStartup.warnAboutSchemaManagement(config);
    for (String database : MigraxStartup.databases(config)) {
      DataSource dataSource = MigraxStartup.dataSource(database, config);
      if (dataSource == null) {
        if (org.migrax.runner.Databases.DEFAULT.equals(database)) {
          LOG.warn("Migrax: no default DataSource; its migrations were not applied.");
          continue;
        }
        throw new IllegalStateException(MigraxStartup.prefix(database) + "no data source named '"
            + org.migrax.runner.Databases.dataSource(database, MigraxStartup.settings(config))
            + "' (quarkus.datasource.<name>.*). Set migrax.databases." + database
            + ".datasource to its name.");
      }
      try {
        MigraxStartup.migrate(database, dataSource, config,
            Thread.currentThread().getContextClassLoader());
      } catch (RuntimeException e) {
        throw e;
      } catch (Exception e) {
        throw new IllegalStateException(MigraxStartup.prefix(database) + e.getMessage(), e);
      }
    }
  }
}
