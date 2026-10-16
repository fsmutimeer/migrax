package org.migrax.junit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the static field that holds the test database of a {@link MigraxTest} class: a
 * {@code javax.sql.DataSource}, or a Testcontainers JDBC container (anything with
 * {@code getJdbcUrl()}, {@code getUsername()} and {@code getPassword()}). A container that is
 * not running yet is started.
 *
 * <pre>{@code
 * @MigraxTest
 * class OrderRepositoryTest {
 *   @MigraxDatabase
 *   static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");
 * }
 * }</pre>
 *
 * @since 0.10.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface MigraxDatabase {
}
