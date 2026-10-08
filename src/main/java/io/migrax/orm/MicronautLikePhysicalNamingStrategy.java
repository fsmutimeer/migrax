package io.migrax.orm;

import io.migrax.model.NamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;

/**
 * Same rule as Micronaut Data's {@code DefaultPhysicalNamingStrategy}
 * ({@code UnderScoreSeparatedLowerCase}). Used when Micronaut Data itself is not on the
 * classpath, for example with --naming micronaut.
 */
public final class MicronautLikePhysicalNamingStrategy extends PhysicalNamingStrategyStandardImpl {
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
        : new Identifier(NamingStrategy.micronautSnakeCase(name.getText()), name.isQuoted());
  }
}
