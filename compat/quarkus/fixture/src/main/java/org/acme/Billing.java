package org.acme;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/** The second database's rows: migrated at startup from db/billing and db.billing. */
@Path("/billing")
public class Billing {
  @Inject @DataSource("billing") AgroalDataSource billing;

  @GET
  public String list() throws Exception {
    List<String> owners = new ArrayList<>();
    try (Connection connection = billing.getConnection();
         ResultSet rows = connection.createStatement()
             .executeQuery("SELECT owner FROM invoice ORDER BY id")) {
      while (rows.next()) {
        owners.add(rows.getString(1));
      }
    }
    return String.join(",", owners);
  }
}
