package org.migrax.helidon;

import io.helidon.microprofile.testing.junit5.AddConfig;
import io.helidon.microprofile.testing.junit5.HelidonTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * migrax.drift.check=warn: the start compares the database with the packaged snapshot and logs
 * the differences. The test resources' META-INF/migrax/snapshot.json expects only a table
 * expected_only, so the check always finds differences here (other tests leave it off).
 */
@HelidonTest
@AddConfig(key = "migrax.drift.check", value = "warn")
// The span exporter is shared by the test applications; MigraxStartupTest counts its spans.
@AddConfig(key = "migrax.tracing.enabled", value = "false")
@AddConfig(key = "migrax.metrics.enabled", value = "false")
@AddConfig(key = "javax.sql.DataSource.notes.dataSource.url",
    value = "jdbc:h2:mem:notes_drift;DB_CLOSE_DELAY=-1")
class MigraxDriftTest {
  private static final List<String> WARNINGS = new CopyOnWriteArrayList<>();

  static {
    Logger.getLogger("org.migrax").addHandler(new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
          WARNINGS.add(String.valueOf(record.getMessage()) + " "
              + java.util.Arrays.toString(record.getParameters()));
        }
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    });
  }

  @Test
  void theStartLogsTheDifferences() {
    String warnings = String.join("\n", WARNINGS);
    assertTrue(warnings.contains("difference(s) between the database and the schema"),
        warnings);
    assertTrue(warnings.contains("expected_only: table is missing from the database"),
        warnings);
  }
}
