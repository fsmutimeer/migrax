package org.migrax.cli;

import org.migrax.plugin.ProjectDatabaseConfig.SchemaGeneration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchemaGenerationAdviceTest {
  private static SchemaGeneration setting(String key) {
    return new SchemaGeneration(key, "update", "x", 1);
  }

  @Test
  void suggestedFixKeepsTheLineFormat() {
    assertEquals("quarkus.hibernate-orm.schema-management.strategy=none",
        DoctorCommand.replaceValue("quarkus.hibernate-orm.schema-management.strategy=update",
            setting("quarkus.hibernate-orm.schema-management.strategy"), "none"));
    assertEquals("spring.jpa.hibernate.ddl-auto = none",
        DoctorCommand.replaceValue("spring.jpa.hibernate.ddl-auto = update",
            setting("spring.jpa.hibernate.ddl-auto"), "none"));
    assertEquals("ddl-auto: none",
        DoctorCommand.replaceValue("ddl-auto: update", setting("spring.jpa.hibernate.ddl-auto"), "none"));
    assertEquals("<property name=\"hibernate.hbm2ddl.auto\" value=\"none\"/>",
        DoctorCommand.replaceValue("<property name=\"hibernate.hbm2ddl.auto\" value=\"update\"/>",
            setting("hibernate.hbm2ddl.auto"), "none"));
  }

  @Test
  void mysqlBefore8016IgnoresCheckConstraints() {
    org.junit.jupiter.api.Assertions.assertTrue(DoctorCommand.ignoresChecks("MySQL", "8.0.15"));
    org.junit.jupiter.api.Assertions.assertTrue(DoctorCommand.ignoresChecks("MySQL", "5.7.44-log"));
    org.junit.jupiter.api.Assertions.assertFalse(DoctorCommand.ignoresChecks("MySQL", "8.0.16"));
    org.junit.jupiter.api.Assertions.assertFalse(DoctorCommand.ignoresChecks("MySQL", "8.4.3"));
    org.junit.jupiter.api.Assertions.assertFalse(
        DoctorCommand.ignoresChecks("MySQL", "5.5.5-10.11.6-MariaDB"));
    org.junit.jupiter.api.Assertions.assertFalse(DoctorCommand.ignoresChecks("PostgreSQL", "9.6.1"));
  }
}
