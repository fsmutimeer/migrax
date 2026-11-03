package org.migrax.dialect;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Database differences belong in {@code org.migrax.dialect}. Code outside it asks the dialect
 * instead of checking which database it is. These tests fail on any class outside it that names
 * a concrete dialect or checks for a database by name.
 */
class DialectBoundaryTest {
  private static final String DIALECT_PACKAGE = "org.migrax.dialect";

  /** Matches comments and strings too: name the database's behavior there instead. */
  private static final Pattern DATABASE_NAME_CHECK = Pattern.compile(
      "contains\\(\"(postgres|mysql|mariadb|oracle|sql server|microsoft|h2|hsql|derby|sqlite"
          + "|cockroach|tidb|yugabyte)"
          + "|\"(postgresql|mysql|mariadb|oracle|sqlserver|h2|hsqldb|derby|sqlite|cockroachdb"
          + "|yugabytedb|tidb)\""
          + "|instanceof [A-Za-z]+Dialect",
      Pattern.CASE_INSENSITIVE);

  @Test
  void onlyTheDialectPackageNamesConcreteDialects() {
    Map<String, Set<String>> used = new TreeMap<>();
    for (JavaClass javaClass : new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("org.migrax")) {
      if (javaClass.getPackageName().equals(DIALECT_PACKAGE)) {
        continue;
      }
      for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
        if (isConcreteDialect(dependency.getTargetClass())) {
          used.computeIfAbsent(sourceFile(javaClass), file -> new TreeSet<>())
              .add(dependency.getTargetClass().getName());
        }
      }
    }
    assertNone("Classes outside " + DIALECT_PACKAGE + " that use concrete dialect classes",
        used);
  }

  @Test
  void codeOutsideTheDialectPackageDoesNotCheckForDatabaseNames() throws IOException {
    Path sources = Path.of("src/main/java");
    assertTrue(Files.isDirectory(sources),
        "Run this test from the folder that holds the core pom.xml.");
    Path dialectSources = sources.resolve(DIALECT_PACKAGE.replace('.', '/'));
    Map<String, Integer> found = new TreeMap<>();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(sources)) {
      files = walk.filter(file -> file.toString().endsWith(".java"))
          .filter(file -> !file.startsWith(dialectSources))
          .sorted()
          .toList();
    }
    for (Path file : files) {
      long lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
          .filter(line -> DATABASE_NAME_CHECK.matcher(line).find())
          .count();
      if (lines > 0) {
        found.put(sources.relativize(file).toString().replace('\\', '/'), (int) lines);
      }
    }
    assertNone("Lines outside " + DIALECT_PACKAGE + " that check for a database name", found);
  }

  private static boolean isConcreteDialect(JavaClass target) {
    return target.getPackageName().equals(DIALECT_PACKAGE)
        && target.isAssignableTo(Dialect.class)
        && !target.isInterface()
        && !target.isEquivalentTo(AbstractDialect.class);
  }

  /** The source file a class comes from, so nested and anonymous classes count for it. */
  private static String sourceFile(JavaClass javaClass) {
    JavaClass outer = javaClass;
    while (outer.getEnclosingClass().isPresent()) {
      outer = outer.getEnclosingClass().get();
    }
    return outer.getName().replace('.', '/') + ".java";
  }

  private static void assertNone(String what, Map<String, ?> found) {
    if (!found.isEmpty()) {
      fail(what + ": " + found + ". Add a method to Dialect (a default method, overridden where"
          + " it differs) and call it instead.");
    }
  }
}
