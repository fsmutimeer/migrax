package org.migrax.micronaut;

import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * With migrax.migrate-at-startup=false a pipeline migrates: the application starts without
 * migrating, and the gauge reports what waits.
 */
@MicronautTest(transactional = false)
@Property(name = "migrax.migrate-at-startup", value = "false")
@Property(name = "datasources.default.url",
    value = "jdbc:h2:mem:migrax_report_only;DB_CLOSE_DELAY=-1")
@Property(name = "jpa.default.properties.hibernate.hbm2ddl.auto", value = "none")
class MigraxReportOnlyTest {
  @Inject
  DataSource dataSource;

  @Inject
  io.micrometer.core.instrument.MeterRegistry registry;

  @Test
  void reportsWithoutMigrating() throws Exception {
    try (Connection connection = MigraxDataSourceListener.unwrap(dataSource).getConnection();
         ResultSet tables = connection.getMetaData().getTables(null, null, "MIGRAX_HISTORY",
             null)) {
      assertFalse(tables.next());
    }
    assertEquals(1.0, registry.get("migrax.migrations.pending").gauge().value());
  }
}
