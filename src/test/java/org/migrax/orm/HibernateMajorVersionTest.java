package org.migrax.orm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HibernateMajorVersionTest {

  @Test
  void majorVersionOfHibernateVersionStrings() {
    assertEquals(4, HibernateSchemaReader.majorVersion("4.3.11.Final"));
    assertEquals(5, HibernateSchemaReader.majorVersion("5.6.15.Final"));
    assertEquals(7, HibernateSchemaReader.majorVersion("7.1.0.Final"));
    assertEquals(10, HibernateSchemaReader.majorVersion("10.0.0-SNAPSHOT"));
    assertEquals(0, HibernateSchemaReader.majorVersion("x"));
    assertEquals(0, HibernateSchemaReader.majorVersion(null));
  }
}
