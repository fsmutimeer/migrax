package org.acme;

import jakarta.annotation.Resource;
import jakarta.annotation.sql.DataSourceDefinition;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * A second database the application defines itself; Migrax migrates it from db/billing. A global
 * JNDI name: Payara can't look up java:app names yet when Migrax runs at startup.
 */
@DataSourceDefinition(name = "java:global/jdbc/billing",
    className = "org.h2.jdbcx.JdbcDataSource", url = "jdbc:h2:mem:billing;DB_CLOSE_DELAY=-1",
    user = "sa", password = "")
@WebServlet("/billing")
public class Billing extends HttpServlet {
  @Resource(lookup = "java:global/jdbc/billing") DataSource billing;

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
    try (Connection connection = billing.getConnection();
         ResultSet rows = connection.createStatement().executeQuery("SELECT owner FROM invoice")) {
      response.getWriter().write(rows.next() ? rows.getString(1) : "no invoice");
    } catch (SQLException e) {
      response.getWriter().write("error: " + e.getMessage());
    }
  }
}
