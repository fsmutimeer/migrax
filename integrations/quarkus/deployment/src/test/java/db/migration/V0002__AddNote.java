package db.migration;

import java.sql.Connection;
import org.migrax.api.JavaMigration;

/** A Java migration in the test application, found through the build-time list. */
public class V0002__AddNote implements JavaMigration {
  @Override
  public void migrate(Connection connection) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO note (body) VALUES ('from Java')");
    }
  }
}
