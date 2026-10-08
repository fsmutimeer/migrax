package io.migrax.orm;

import io.migrax.model.NamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/**
 * Same rule as Hibernate's {@code CamelCaseToUnderscoresNamingStrategy} and Spring Boot's
 * {@code SpringPhysicalNamingStrategy}. Used with Hibernate versions before 5.5, which do not
 * have that class.
 */
public final class SnakeCasePhysicalNamingStrategy extends PhysicalNamingStrategyStandardImpl {
  @Override
  public Identifier toPhysicalCatalogName(Identifier name, JdbcEnvironment environment) {
    return map(name);
  }

  @Override
  public Identifier toPhysicalSchemaName(Identifier name, JdbcEnvironment environment) {
    return map(name);
  }

  @Override
  public Identifier toPhysicalTableName(Identifier name, JdbcEnvironment environment) {
    return map(name);
  }

  @Override
  public Identifier toPhysicalSequenceName(Identifier name, JdbcEnvironment environment) {
    return map(name);
  }

  @Override
  public Identifier toPhysicalColumnName(Identifier name, JdbcEnvironment environment) {
    return map(name);
  }

  private static Identifier map(Identifier name) {
    return name == null ? null
        : new Identifier(NamingStrategy.snakeCase(name.getText()), name.isQuoted());
  }
}
