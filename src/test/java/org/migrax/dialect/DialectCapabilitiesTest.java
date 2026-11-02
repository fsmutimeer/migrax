package org.migrax.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * The facts each dialect states about its database. The expected values are the lists the code
 * outside the dialect package used before it asked the dialect, so a dialect that changes one
 * changes behavior on purpose.
 */
class DialectCapabilitiesTest {

  @Test
  void factsPerDatabase() {
    assertDialects("backslashEscapes", Dialect::backslashEscapes, "mysql", "mariadb", "tidb");
    assertDialects("dumpsHavePsqlCommands", Dialect::dumpsHavePsqlCommands,
        "postgresql", "cockroachdb", "yugabytedb");
    assertDialects("keepsComments", d -> !d.keepsComments(), "sqlite", "derby");
    assertDialects("hasSchemas", d -> !d.hasSchemas(), "sqlite");
    assertDialects("switchesSchemas", d -> !d.switchesSchemas(), "sqlserver");
    assertDialects("urlReachesOtherDatabases", Dialect::urlReachesOtherDatabases,
        "postgresql", "cockroachdb", "yugabytedb", "mysql", "mariadb", "tidb");
    assertDialects("hibernateSequencesAreTables", Dialect::hibernateSequencesAreTables,
        "mysql", "tidb", "sqlite");
    assertDialects("hibernateValidationWorks", d -> !d.hibernateValidationWorks(), "sqlite");
    assertDialects("migrationLockLimit", d -> d.migrationLockLimit().isPresent(), "h2");
    assertDialects("caseSensitiveTableNames", Dialect::caseSensitiveTableNames,
        "mysql", "mariadb", "tidb");
    assertDialects("largeObjectTypesFromOrm", Dialect::largeObjectTypesFromOrm,
        "mysql", "mariadb", "tidb", "postgresql", "cockroachdb", "yugabytedb");
    assertDialects("plainBytesType", d -> d.plainBytesType().equals(Optional.of("bytea")),
        "postgresql", "cockroachdb", "yugabytedb");
    assertDialects("plainBytesType present", d -> d.plainBytesType().isPresent(),
        "postgresql", "cockroachdb", "yugabytedb");
    assertDialects("micronautDataSequenceIds", Dialect::micronautDataSequenceIds, "oracle");
    assertDialects("micronautDataTextUuids", Dialect::micronautDataTextUuids,
        "mysql", "mariadb", "tidb", "oracle");
    // The PostgreSQL and MySQL drivers report PostgreSQL and MySQL for the databases that
    // speak their protocol, so those got the same size query. (A driver that reports its own
    // name, such as TiDB, got no estimate before and now gets its family's.)
    assertDialects("tableSizeQuery", d -> d.tableSizeQuery().isPresent(),
        "postgresql", "cockroachdb", "yugabytedb", "mysql", "mariadb", "tidb", "sqlserver",
        "oracle");
    assertDialects("countsRowsForTableSize", Dialect::countsRowsForTableSize, "h2", "sqlite");
    assertDialects("dropsForeignKeys", d -> !d.dropsForeignKeys(), "sqlite");
    assertDialects("schemasAreDatabases", Dialect::schemasAreDatabases,
        "mysql", "mariadb", "tidb");
    assertDialects("rendersOnline", Dialect::rendersOnline,
        "postgresql", "cockroachdb", "yugabytedb");
    assertDialects("buildsIndexesWithoutBlocking", Dialect::buildsIndexesWithoutBlocking,
        "yugabytedb");
    assertDialects("keepDataRestoreNote", d -> d.keepDataRestoreNote().isPresent(),
        "cockroachdb", "yugabytedb");
  }

  @Test
  void tableSizeNames() {
    assertEquals("BOOK_ORDER", new OracleDialect().tableSizeName("book_order"));
    assertEquals("book_order", new PostgresDialect().tableSizeName("book_order"));
  }

  @Test
  void ormSettingsPerDatabase() {
    String platforms = "org.eclipse.persistence.platform.database.";
    Map<String, String> eclipseLink = Map.ofEntries(
        Map.entry("postgresql", platforms + "PostgreSQLPlatform"),
        Map.entry("cockroachdb", platforms + "PostgreSQLPlatform"),
        Map.entry("yugabytedb", platforms + "PostgreSQLPlatform"),
        Map.entry("mysql", platforms + "MySQLPlatform"),
        Map.entry("mariadb", platforms + "MySQLPlatform"),
        Map.entry("tidb", platforms + "MySQLPlatform"),
        Map.entry("sqlserver", platforms + "SQLServerPlatform"),
        Map.entry("oracle", platforms + "OraclePlatform"),
        Map.entry("h2", platforms + "H2Platform"),
        Map.entry("sqlite", platforms + "DatabasePlatform"),
        Map.entry("hsqldb", platforms + "HSQLPlatform"),
        Map.entry("derby", platforms + "DerbyPlatform"));
    Map<String, String> openJpa = Map.ofEntries(
        Map.entry("postgresql", "postgres"),
        Map.entry("cockroachdb", "postgres"),
        Map.entry("yugabytedb", "postgres"),
        Map.entry("mysql", "mysql"),
        Map.entry("tidb", "mysql"),
        Map.entry("mariadb", "mariadb"),
        Map.entry("sqlserver", "sqlserver"),
        Map.entry("oracle", "oracle"),
        Map.entry("h2", "h2"),
        Map.entry("sqlite", "org.apache.openjpa.jdbc.sql.DBDictionary"),
        Map.entry("hsqldb", "hsql"),
        Map.entry("derby", "derby"));
    Map<String, String> uuidDefaults = Map.of(
        "postgresql", "gen_random_uuid()", "cockroachdb", "gen_random_uuid()",
        "yugabytedb", "gen_random_uuid()", "h2", "random_uuid()", "sqlserver", "newid()",
        "oracle", "sys_guid()");
    for (String name : Dialects.NAMES) {
      Dialect dialect = Dialects.byName(name);
      assertEquals(eclipseLink.get(name), dialect.eclipseLinkPlatform(), name + ", EclipseLink");
      assertEquals(openJpa.get(name), dialect.openJpaDictionary(), name + ", OpenJPA");
      assertEquals(Optional.ofNullable(uuidDefaults.get(name)),
          dialect.micronautDataUuidDefault(), name + ", Micronaut Data UUID default");
    }
  }

  @Test
  void hibernateDialectsPerDatabase() {
    Map<String, List<String>> hibernate6 = Map.ofEntries(
        Map.entry("postgresql", List.of("org.hibernate.dialect.PostgreSQLDialect")),
        Map.entry("mysql", List.of("org.hibernate.dialect.MySQLDialect")),
        Map.entry("mariadb", List.of("org.hibernate.dialect.MariaDBDialect")),
        Map.entry("tidb", List.of("org.hibernate.dialect.MySQLDialect")),
        Map.entry("sqlserver", List.of("org.hibernate.dialect.SQLServerDialect")),
        Map.entry("oracle", List.of("org.hibernate.dialect.OracleDialect")),
        Map.entry("h2", List.of("org.hibernate.dialect.H2Dialect")),
        Map.entry("cockroachdb", List.of("org.hibernate.dialect.CockroachDialect")),
        Map.entry("yugabytedb", List.of("org.hibernate.dialect.PostgreSQLDialect")),
        Map.entry("sqlite", List.of("org.hibernate.community.dialect.SQLiteDialect")),
        Map.entry("hsqldb", List.of("org.hibernate.dialect.HSQLDialect")),
        Map.entry("derby", List.of("org.hibernate.dialect.DerbyDialect",
            "org.hibernate.community.dialect.DerbyDialect")));
    Map<String, List<String>> hibernate5 = Map.ofEntries(
        Map.entry("postgresql", List.of("org.hibernate.dialect.PostgreSQL10Dialect")),
        Map.entry("mysql", List.of("org.hibernate.dialect.MySQL8Dialect")),
        Map.entry("mariadb", List.of("org.hibernate.dialect.MariaDB103Dialect")),
        Map.entry("tidb", List.of("org.hibernate.dialect.MySQL8Dialect")),
        Map.entry("sqlserver", List.of("org.hibernate.dialect.SQLServer2012Dialect")),
        Map.entry("oracle", List.of("org.hibernate.dialect.Oracle12cDialect")),
        Map.entry("h2", List.of("org.hibernate.dialect.H2Dialect")),
        Map.entry("cockroachdb", List.of("org.hibernate.dialect.CockroachDB201Dialect")),
        Map.entry("yugabytedb", List.of("org.hibernate.dialect.PostgreSQL10Dialect")),
        Map.entry("sqlite", List.of()),
        Map.entry("hsqldb", List.of("org.hibernate.dialect.HSQLDialect")),
        Map.entry("derby", List.of("org.hibernate.dialect.DerbyTenSevenDialect")));
    List<String> postgres4 = List.of("org.hibernate.dialect.PostgreSQL9Dialect",
        "org.hibernate.dialect.PostgreSQL82Dialect", "org.hibernate.dialect.PostgreSQLDialect");
    List<String> mysql4 = List.of("org.hibernate.dialect.MySQL5InnoDBDialect");
    Map<String, List<String>> hibernate4 = Map.ofEntries(
        Map.entry("postgresql", postgres4),
        Map.entry("cockroachdb", postgres4),
        Map.entry("yugabytedb", postgres4),
        Map.entry("mysql", mysql4),
        Map.entry("mariadb", mysql4),
        Map.entry("tidb", mysql4),
        Map.entry("sqlserver", List.of("org.hibernate.dialect.SQLServer2012Dialect",
            "org.hibernate.dialect.SQLServer2008Dialect")),
        Map.entry("oracle", List.of("org.hibernate.dialect.Oracle10gDialect")),
        Map.entry("h2", List.of("org.hibernate.dialect.H2Dialect")),
        Map.entry("sqlite", List.of()),
        Map.entry("hsqldb", List.of("org.hibernate.dialect.HSQLDialect")),
        Map.entry("derby", List.of("org.hibernate.dialect.DerbyTenSevenDialect")));
    for (String name : Dialects.NAMES) {
      Dialect dialect = Dialects.byName(name);
      assertEquals(hibernate6.get(name), dialect.hibernateDialects(6), name + ", Hibernate 6");
      assertEquals(hibernate6.get(name), dialect.hibernateDialects(7), name + ", Hibernate 7");
      assertEquals(hibernate5.get(name), dialect.hibernateDialects(5), name + ", Hibernate 5");
      assertEquals(hibernate4.get(name), dialect.hibernateDialects(4), name + ", Hibernate 4");
    }
  }

  @Test
  void onlyCockroachIsNamedByTheHibernateDialectSetting() {
    for (String name : Dialects.NAMES) {
      assertEquals("cockroachdb".equals(name), Dialects.byName(name)
          .acceptsHibernateDialect("org.hibernate.dialect.CockroachDialect"), name);
      assertFalse(Dialects.byName(name)
          .acceptsHibernateDialect("org.hibernate.dialect.PostgreSQLDialect"), name);
    }
  }

  @Test
  void serverDescriptions() {
    assertEquals("PostgreSQL 16.4", new PostgresDialect().describeServer("PostgreSQL", "16.4"));
    assertEquals("CockroachDB (PostgreSQL protocol)",
        new CockroachDialect().describeServer("PostgreSQL", "13.0.0"));
    assertEquals("YugabyteDB, PostgreSQL 11.2-YB-2.20 protocol",
        new YugabyteDialect().describeServer("PostgreSQL", "11.2-YB-2.20"));
    assertEquals("TiDB v8.5.3 (MySQL protocol)",
        new TidbDialect().describeServer("MySQL", "8.0.11-TiDB-v8.5.3"));
  }

  @Test
  void schemaNamesOnlyWhereTheConnectionCantSwitch() {
    Dialect postgres = new PostgresDialect();
    assertSame(postgres, postgres.inSchema("${migrax.schema}"));
    assertEquals("${migrax.schema}.t",
        new SqlServerDialect().inSchema("${migrax.schema}").tableName("t"));
  }

  @Test
  void mysqlBefore8016IgnoresCheckConstraints() throws Exception {
    assertTrue(MySqlDialect.ignoresChecks("8.0.15"));
    assertTrue(MySqlDialect.ignoresChecks("5.7.44-log"));
    assertFalse(MySqlDialect.ignoresChecks("8.0.16"));
    // TiDB reports MySQL 8.0.11; its own setting decides (TidbDialect asks it).
    assertFalse(MySqlDialect.ignoresChecks("8.0.11-TiDB-v8.5.3"));
    assertFalse(MySqlDialect.ignoresChecks("8.4.3"));
    assertFalse(MySqlDialect.ignoresChecks("5.5.5-10.11.6-MariaDB"));
    assertFalse(new PostgresDialect().unenforcedChecks(null).isPresent());
    assertFalse(new MariaDbDialect().unenforcedChecks(null).isPresent());
    assertEquals(java.util.Optional.of("MySQL before 8.0.16 accepts them but doesn't enforce "
            + "them; the entities' @Check conditions are not checked on this server"),
        new MySqlDialect().unenforcedChecks(server("5.7.44-log", null)));
    assertFalse(new MySqlDialect().unenforcedChecks(server("8.4.3", null)).isPresent());
  }

  @Test
  void tidbWarnsWhileItsCheckSettingIsOff() {
    assertFalse(new TidbDialect().unenforcedChecks(server("8.0.11-TiDB-v8.5.3", 1)).isPresent());
    assertTrue(new TidbDialect().unenforcedChecks(server("8.0.11-TiDB-v8.5.3", 0)).get()
        .startsWith("TiDB ignores them while tidb_enable_check_constraint is OFF"));
    // An older TiDB without the setting fails the query: its checks aren't enforced either.
    assertTrue(new TidbDialect().unenforcedChecks(server("8.0.11-TiDB-v6.5.0", null))
        .isPresent());
  }

  @Test
  void defaultsForOtherDatabases() {
    assertEquals("H2 2.5 (2026)", new H2Dialect().describeServer("H2", "2.5 (2026)"));
    assertFalse(new PostgresDialect().migrationLockLimit().isPresent());
  }

  /**
   * A connection whose server reports {@code version} and answers a one-value query with
   * {@code queryResult}; a null result makes queries fail.
   */
  private static java.sql.Connection server(String version, Integer queryResult) {
    java.sql.DatabaseMetaData metadata = proxy(java.sql.DatabaseMetaData.class, (method, args) ->
        "getDatabaseProductVersion".equals(method) ? version : null);
    java.sql.ResultSet result = proxy(java.sql.ResultSet.class, (method, args) ->
        switch (method) {
          case "next" -> true;
          case "getInt" -> queryResult;
          default -> null;
        });
    java.sql.Statement statement = proxy(java.sql.Statement.class, (method, args) -> {
      if ("executeQuery".equals(method)) {
        if (queryResult == null) {
          throw new java.sql.SQLException("Unknown system variable");
        }
        return result;
      }
      return null;
    });
    return proxy(java.sql.Connection.class, (method, args) -> switch (method) {
      case "getMetaData" -> metadata;
      case "createStatement" -> statement;
      default -> null;
    });
  }

  private interface Answer {
    Object answer(String method, Object[] args) throws Exception;
  }

  private static <T> T proxy(Class<T> type, Answer answer) {
    return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),
        new Class<?>[] {type}, (self, method, args) -> answer.answer(method.getName(), args)));
  }

  private static void assertDialects(String fact, Predicate<Dialect> test, String... expected) {
    Set<String> found = new TreeSet<>();
    for (String name : Dialects.NAMES) {
      if (test.test(Dialects.byName(name))) {
        found.add(name);
      }
    }
    assertEquals(new TreeSet<>(Set.of(expected)), found, fact);
  }
}
