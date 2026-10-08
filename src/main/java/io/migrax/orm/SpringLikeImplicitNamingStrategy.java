package io.migrax.orm;

import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.ImplicitJoinTableNameSource;
import org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl;

/**
 * Same rule as Spring Boot's {@code SpringImplicitNamingStrategy}: join tables are named
 * {@code <owner table>_<attribute>}. Used when Spring Boot itself is not on the classpath.
 */
public final class SpringLikeImplicitNamingStrategy extends ImplicitNamingStrategyJpaCompliantImpl {
  @Override
  public Identifier determineJoinTableName(ImplicitJoinTableNameSource source) {
    String name = source.getOwningPhysicalTableName() + "_"
        + source.getAssociationOwningAttributePath().getProperty();
    return toIdentifier(name, source.getBuildingContext());
  }
}
