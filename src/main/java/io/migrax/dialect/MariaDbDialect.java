package io.migrax.dialect;

public final class MariaDbDialect extends MySqlDialect {
    @Override public String id() { return "mariadb"; }
    @Override protected String sequenceDefault(String name){return "NEXT VALUE FOR "+q(name);}
}
