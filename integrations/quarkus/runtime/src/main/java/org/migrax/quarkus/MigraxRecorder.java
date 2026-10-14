package org.migrax.quarkus;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InjectableInstance;
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

  /** Applies the pending migrations to the default data source, unless migrax.enabled=false. */
  public void migrate() {
    Config config = ConfigProvider.getConfig();
    if (!config.getOptionalValue("migrax.enabled", Boolean.class).orElse(true)) {
      return;
    }
    MigraxStartup.warnAboutSchemaManagement(config);
    InjectableInstance<DataSource> dataSource = Arc.container().select(DataSource.class);
    if (!dataSource.isResolvable()) {
      LOG.warn("Migrax: no default DataSource; migrations were not applied.");
      return;
    }
    try {
      MigraxStartup.migrate(dataSource.get(), config,
          Thread.currentThread().getContextClassLoader());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Migrax: " + e.getMessage(), e);
    }
  }
}
