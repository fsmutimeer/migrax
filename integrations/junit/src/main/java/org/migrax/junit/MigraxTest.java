package org.migrax.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Migrates a test database before the tests of the class run, then checks that the entities
 * match the migrated schema. When they don't (an entity changed without a migration, or a
 * migration was edited by hand), every test of the class fails and the error lists the
 * differences.
 *
 * <pre>{@code
 * @MigraxTest
 * class BookRepositoryTest {
 *   @Test
 *   void savesBooks(DataSource dataSource) { ... }
 * }
 * }</pre>
 *
 * <p>The database is a static field marked {@link MigraxDatabase} (a {@code DataSource}, or a
 * Testcontainers JDBC container), else {@link #url}, else a new H2 database in memory. Test
 * methods and constructors can ask for the {@code DataSource} and the {@code Migrax} instance
 * as parameters.
 *
 * @since 0.10.0
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(MigraxExtension.class)
public @interface MigraxTest {

  /** A JDBC URL for the test database; empty for a {@link MigraxDatabase} field or H2. */
  String url() default "";

  /** The user for {@link #url}. */
  String user() default "";

  /** The password for {@link #url}. */
  String password() default "";

  /**
   * Migration folders: classpath folders, or folders on disk with {@code filesystem:}.
   */
  String[] locations() default {"db/migration"};

  /** The package with Java migrations. */
  String javaPackage() default "db.migration";

  /**
   * The package that holds the entities; empty for the one the {@code migrax} command uses
   * ({@code migrax.package}, else the Maven groupId or Gradle group).
   */
  String entities() default "";

  /** Check that the entities match the migrated schema. */
  boolean validate() default true;

  /**
   * Also test every rollback script: roll each migration back on its own, apply it again and
   * check that the schema is the same.
   */
  boolean rollbacks() default false;

  /** Schemas to migrate one after another; empty for the default schema. */
  String[] schemas() default {};

  /** The environment for migrations marked {@code -- migrax:env <name>}. */
  String env() default "";
}
