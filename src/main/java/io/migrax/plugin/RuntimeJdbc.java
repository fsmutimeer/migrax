package io.migrax.plugin;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.Properties;
import java.util.ServiceLoader;

public final class RuntimeJdbc {
  private RuntimeJdbc() {}

  public static Connection connect(
      ClassLoader runtimeLoader, String url, String user, String password) throws SQLException {
    Properties properties = new Properties();
    if (user != null && !user.isBlank()) {
      properties.setProperty("user", user);
    }
    if (password != null && !password.isBlank()) {
      properties.setProperty("password", password);
    }

    for (Driver driver : ServiceLoader.load(Driver.class, runtimeLoader)) {
      if (driver.acceptsURL(url)) {
        Connection connection = driver.connect(url, properties);
        if (connection != null) {
          return connection;
        }
      }
    }
    throw new SQLException(
        "No JDBC driver in the service runtime dependencies accepts URL: " + url
            + ". Ensure the database driver is a runtime dependency of the service.");
  }

}
