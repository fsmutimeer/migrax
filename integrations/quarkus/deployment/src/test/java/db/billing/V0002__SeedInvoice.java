package db.billing;

import java.sql.Connection;
import org.migrax.api.JavaMigration;

/** A Java migration of the billing database only (package db.billing). */
public class V0002__SeedInvoice implements JavaMigration {
  @Override
  public void migrate(Connection connection) throws Exception {
    try (var statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO invoice (id, owner) VALUES (2, 'from Java')");
    }
  }
}
