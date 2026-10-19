package org.migrax.micronaut;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.inject.qualifiers.Qualifiers;
import jakarta.annotation.PostConstruct;
import org.migrax.runner.Databases;

import javax.sql.DataSource;

/**
 * Creates the data source of each further database (migrax.databases.<name>) while the
 * application starts, so {@link MigraxDataSourceListener} migrates it even when nothing else
 * uses it yet.
 */
@Context
@Requires(property = "migrax.enabled", notEquals = "false")
public class MigraxDatabases {
  private final Environment environment;
  private final BeanContext beanContext;

  public MigraxDatabases(Environment environment, BeanContext beanContext) {
    this.environment = environment;
    this.beanContext = beanContext;
  }

  @PostConstruct
  void createDataSources() {
    for (String database : Databases.names(MigraxDataSourceListener.settings(environment))) {
      String name = MigraxDataSourceListener.dataSourceName(database, environment);
      if (beanContext.findBean(DataSource.class, Qualifiers.byName(name)).isEmpty()) {
        throw new IllegalStateException("Migrax (" + database + "): no data source named '"
            + name + "' (datasources." + name + ".*). Set migrax.databases." + database
            + ".datasource to its name.");
      }
    }
  }
}
