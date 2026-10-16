package org.migrax.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.migrax.api.Migrax;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A Testcontainers database marked with {@link MigraxDatabase}; skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
@MigraxTest(entities = "org.migrax.junit.fixture", locations = "db/postgres", rollbacks = true)
class PostgresContainerTest {
  @MigraxDatabase
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine");

  @Test
  void migratesTheContainer(Migrax migrax) throws Exception {
    assertEquals(1, migrax.status().applied());
    assertEquals("Hibernate", migrax.validate().entities().split(" ")[0]);
  }
}
