package org.migrax.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Java a project needs, read from the JVM's message about a class it can't load. */
class ClassScannerTest {
  @Test
  void namesTheJavaFromTheClassFileVersion() {
    assertEquals(" (Java 25)", ClassScanner.newerJava("io/micronaut/X has been compiled by a "
        + "more recent version of the Java Runtime (class file version 69.0), this version of "
        + "the Java Runtime only recognizes class file versions up to 65.0"));
    assertEquals("", ClassScanner.newerJava(null));
    assertEquals("", ClassScanner.newerJava("something else"));
  }
}
