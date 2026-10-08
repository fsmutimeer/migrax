package io.migrax.spring.app;

import io.migrax.api.JavaMigration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class TestApp {
  /** A Java migration provided as a Spring bean. */
  @Bean
  JavaMigration seedBooks() {
    return new JavaMigration() {
      @Override
      public void migrate(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement()) {
          statement.executeUpdate("INSERT INTO book (title) VALUES ('Seeded by Java')");
        }
      }

      @Override
      public String version() {
        return "V0002__SeedBooks";
      }
    };
  }
}
