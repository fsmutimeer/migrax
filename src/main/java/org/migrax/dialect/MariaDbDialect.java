package org.migrax.dialect;

import org.migrax.model.SchemaModel;

/**
 * MariaDB database dialect. Unlike MySQL, MariaDB 10.3+ has real sequences and 10.7+ a
 * native uuid type, which Hibernate 6 uses.
 *
 * @since 0.1.0
 */
public final class MariaDbDialect extends MySqlDialect {
  @Override
  public String id() {
    return "mariadb";
  }

  @Override
  public java.util.List<String> hibernateDialects(int hibernateMajor) {
    return java.util.List.of(hibernateMajor == 5 ? "org.hibernate.dialect.MariaDB103Dialect"
        : "org.hibernate.dialect.MariaDBDialect");
  }

  /** MariaDB has sequences. */
  @Override
  public boolean hibernateSequencesAreTables() {
    return false;
  }

  /** MariaDB enforces check constraints. */
  @Override
  public java.util.Optional<String> unenforcedChecks(java.sql.Connection connection) {
    return java.util.Optional.empty();
  }

  @Override
  protected String uuidType() {
    return "uuid";
  }

  @Override
  protected String createSequence(SchemaModel.Sequence s) {
    return "CREATE SEQUENCE " + q(s.name()) + " START WITH " + s.initialValue()
        + " INCREMENT BY " + s.allocationSize();
  }

  @Override
  protected String dropSequence(String name) {
    return "DROP SEQUENCE " + q(name);
  }

  @Override
  protected String dropCheck(String table, String name) {
    return "ALTER TABLE " + q(table) + " DROP CONSTRAINT " + q(name);
  }

  @Override
  protected String sequenceDefault(String name) {
    return "NEXT VALUE FOR " + q(name);
  }
}
