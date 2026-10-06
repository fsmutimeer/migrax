package io.migrax.dialect;

import java.util.Locale;

public final class Dialects {
    private Dialects() {}
    public static Dialect fromJdbcUrl(String url) {
        if (url == null) throw new IllegalArgumentException("JDBC URL is required");
        String u = url.toLowerCase(Locale.ROOT);
        if (u.startsWith("jdbc:postgresql:")) return new PostgresDialect();
        if (u.startsWith("jdbc:mysql:")) return new MySqlDialect();
        if (u.startsWith("jdbc:mariadb:")) return new MariaDbDialect();
        if (u.startsWith("jdbc:sqlserver:")) return new SqlServerDialect();
        if (u.startsWith("jdbc:oracle:")) return new OracleDialect();
        if (u.startsWith("jdbc:h2:")) return new H2Dialect();
        throw new IllegalArgumentException("Unsupported JDBC URL: " + url);
    }
}
