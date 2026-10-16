package org.migrax.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import org.migrax.api.Migrax;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.EventType;
import org.junit.platform.testkit.engine.Events;

/** Runs small test classes with {@link MigraxTest} and checks what JUnit reports. */
class MigraxExtensionTest {

  @MigraxTest(entities = "org.migrax.junit.fixture")
  static class MigratedOnH2 {
    @Test
    void getsTheMigratedDatabase(DataSource dataSource, Migrax.MigrateResult result)
        throws Exception {
      assertEquals(1, result.applied());
      try (Connection connection = dataSource.getConnection()) {
        connection.createStatement().executeUpdate("INSERT INTO book (title) VALUES ('Dune')");
        ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM book");
        rows.next();
        assertEquals(1, rows.getInt(1));
      }
    }

    @Test
    void getsTheApi(Migrax migrax) throws Exception {
      assertTrue(migrax.status().upToDate());
    }

    @Nested
    class Inner {
      @Test
      void sharesTheDatabase(Migrax.MigrateResult result) {
        assertEquals(1, result.applied());
      }
    }
  }

  @MigraxTest(entities = "org.migrax.junit.fixture", locations = "db/broken")
  static class EntitiesAheadOfTheMigrations {
    @Test
    void neverRuns() {
    }
  }

  @MigraxTest(entities = "org.migrax.junit.fixture", locations = "db/badrollback",
      rollbacks = true)
  static class BrokenRollbackScript {
    @Test
    void neverRuns() {
    }
  }

  @MigraxTest(entities = "org.migrax.junit.fixture", rollbacks = true)
  static class WorkingRollbackScripts {
    @Test
    void runs() {
    }
  }

  @MigraxTest(entities = "org.migrax.junit.fixture")
  static class OwnDataSource {
    @MigraxDatabase
    static final JdbcDataSource DATABASE = new JdbcDataSource();

    static {
      DATABASE.setURL("jdbc:h2:mem:junit_own;DB_CLOSE_DELAY=-1");
    }

    @Test
    void usesTheMarkedField(DataSource dataSource) {
      assertEquals(DATABASE, dataSource);
    }
  }

  @MigraxTest(validate = false, url = "jdbc:h2:mem:junit_url;DB_CLOSE_DELAY=-1", user = "sa")
  static class WithUrl {
    @Test
    void migrates(Migrax.MigrateResult result) {
      assertEquals(1, result.applied());
    }
  }

  private static EngineExecutionResults run(Class<?> testClass) {
    return EngineTestKit.engine("junit-jupiter").selectors(selectClass(testClass)).execute();
  }

  private static String failure(Events events) {
    return events.failed().stream().map(Event::getPayload).map(Object::toString).toList()
        .toString();
  }

  @Test
  void migratesOnceAndInjectsTheDatabase() {
    EngineExecutionResults results = run(MigratedOnH2.class);
    assertEquals(0, results.allEvents().failed().count(), failure(results.allEvents()));
    assertEquals(3, results.testEvents().succeeded().count());
  }

  @Test
  void failsTheClassWhenTheEntitiesDontMatch() {
    EngineExecutionResults results = run(EntitiesAheadOfTheMigrations.class);
    List<Event> failed = results.allEvents().failed().list();
    assertEquals(1, failed.size(), failure(results.allEvents()));
    assertEquals(EventType.FINISHED, failed.get(0).getType());
    String message = failure(results.allEvents());
    assertTrue(message.contains("does not match the entities"), message);
    assertTrue(message.contains("title: column is missing from the database"), message);
    assertEquals(0, results.testEvents().started().count());
  }

  @Test
  void failsTheClassWhenARollbackScriptIsBroken() {
    EngineExecutionResults results = run(BrokenRollbackScript.class);
    String message = failure(results.allEvents());
    assertTrue(message.contains("Rollback scripts: failed"), message);
    assertTrue(message.contains("0002_index.sql"), message);
  }

  @Test
  void passesWithWorkingRollbackScripts() {
    EngineExecutionResults results = run(WorkingRollbackScripts.class);
    assertEquals(0, results.allEvents().failed().count(), failure(results.allEvents()));
    assertEquals(1, results.testEvents().succeeded().count());
  }

  @Test
  void usesADataSourceFieldOrAUrl() {
    EngineExecutionResults own = run(OwnDataSource.class);
    assertEquals(0, own.allEvents().failed().count(), failure(own.allEvents()));
    assertEquals(1, own.testEvents().succeeded().count());
    EngineExecutionResults url = run(WithUrl.class);
    assertEquals(0, url.allEvents().failed().count(), failure(url.allEvents()));
    assertEquals(1, url.testEvents().succeeded().count());
  }
}
