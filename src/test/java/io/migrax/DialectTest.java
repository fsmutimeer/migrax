package io.migrax;

import io.migrax.dialect.*;
import io.migrax.diff.DiffEngine;
import io.migrax.model.SchemaModel;
import io.migrax.ops.AddColumn;
import io.migrax.ops.AddUnique;
import io.migrax.ops.AlterColumn;
import io.migrax.ops.CreateSequence;
import io.migrax.ops.CreateTable;
import io.migrax.ops.DropForeignKey;
import io.migrax.ops.DropPrimaryKey;
import io.migrax.ops.DropUnique;
import io.migrax.ops.Operation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DialectTest {
  private static final List<Dialect> ALL = List.of(
      new PostgresDialect(), new MySqlDialect(), new MariaDbDialect(), new SqlServerDialect(),
      new OracleDialect(), new H2Dialect());

  private static SchemaModel.Column column(String name, String logical, boolean nullable,
                                           Integer length) {
    return new SchemaModel.Column(
        name, logical, nullable, length, null, null, null, false, false, null, logical);
  }

  @Test void detectsAllJdbcDialects() {
    assertEquals("postgresql", Dialects.fromJdbcUrl("jdbc:postgresql://localhost/db").id());
    assertEquals("mysql", Dialects.fromJdbcUrl("jdbc:mysql://localhost/db").id());
    assertEquals("mariadb", Dialects.fromJdbcUrl("jdbc:mariadb://localhost/db").id());
    assertEquals("sqlserver", Dialects.fromJdbcUrl("jdbc:sqlserver://localhost;databaseName=db").id());
    assertEquals("oracle", Dialects.fromJdbcUrl("jdbc:oracle:thin:@localhost:1521/XEPDB1").id());
    assertEquals("h2", Dialects.fromJdbcUrl("jdbc:h2:mem:test").id());
    // CockroachDB uses the PostgreSQL driver: the server, not the URL, tells them apart.
    assertEquals("postgresql", Dialects.fromJdbcUrl("jdbc:postgresql://localhost:26257/db").id());
    assertEquals("mysql", Dialects.byName("mysql").id());
    assertEquals("sqlite", Dialects.fromJdbcUrl("jdbc:sqlite:data/shop.db").id());
    assertThrows(IllegalArgumentException.class, () -> Dialects.byName("db2"));
  }

  @Test void registeredDialectsAreFoundByNameAndAlias() {
    assertEquals(List.of("postgresql", "cockroachdb", "mysql", "mariadb", "sqlserver", "oracle",
        "h2", "sqlite"), Dialects.NAMES);
    assertEquals("postgresql", Dialects.byName("pg").id());
    assertEquals("cockroachdb", Dialects.byName("crdb").id());
    assertEquals("postgresql", Dialects.byName(" Postgres ").id());
    assertEquals("sqlserver", Dialects.byName("mssql").id());
    assertNotSame(Dialects.byName("h2"), Dialects.byName("h2"));
  }

  @Test void findsTheDialectForADatabaseProductName() {
    assertEquals("postgresql", Dialects.forProduct("PostgreSQL").orElseThrow().id());
    assertEquals("mysql", Dialects.forProduct("MySQL").orElseThrow().id());
    assertEquals("mariadb", Dialects.forProduct("MariaDB").orElseThrow().id());
    assertEquals("sqlserver", Dialects.forProduct("Microsoft SQL Server").orElseThrow().id());
    assertEquals("oracle", Dialects.forProduct("Oracle").orElseThrow().id());
    assertEquals("h2", Dialects.forProduct("H2").orElseThrow().id());
    assertEquals("cockroachdb", Dialects.forProduct("CockroachDB").orElseThrow().id());
    assertEquals("sqlite", Dialects.forProduct("SQLite").orElseThrow().id());
    assertTrue(Dialects.forProduct("DB2/LINUXX8664").isEmpty());
  }

  @Test void dialectsWithoutMigrationLockingRefuseToMigrate() throws Exception {
    Dialect withoutLocking = new Dialect() {
      @Override public String id() { return "test"; }
      @Override public String quote(String identifier) { return identifier; }
      @Override public String render(Operation operation) { return ""; }
    };
    try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:nolock")) {
      var error = assertThrows(java.sql.SQLException.class,
          () -> withoutLocking.acquireMigrationLock(connection, "io.migrax:test"));
      assertTrue(error.getMessage().contains("refusing to proceed"), error.getMessage());
    }
  }

  @Test
  void unsupportedUrlErrorsDoNotLeakCredentials() {
    var error = assertThrows(IllegalArgumentException.class,
        () -> Dialects.fromJdbcUrl("jdbc:db2:secret-password@/db"));
    assertFalse(error.getMessage().contains("secret-password"), error.getMessage());
  }

  /** Hibernate writes names unquoted, so Migrax does too; the database folds both alike. */
  @Test void writesPlainIdentifiersUnquotedAndQuotesReservedWords() {
    var plain = new AddColumn("users", column("display_name", "varchar", true, 100));
    assertEquals("ALTER TABLE users ADD COLUMN display_name varchar(100)",
        new PostgresDialect().render(plain));
    assertEquals("ALTER TABLE users ADD COLUMN display_name varchar(100)",
        new MySqlDialect().render(plain));
    assertEquals("ALTER TABLE users ADD display_name varchar(100)",
        new SqlServerDialect().render(plain));
    assertEquals("ALTER TABLE users ADD (display_name varchar2(100 char))",
        new OracleDialect().render(plain));

    var reserved = new AddColumn("user", column("order", "integer", true, null));
    assertEquals("ALTER TABLE \"user\" ADD COLUMN \"order\" integer",
        new PostgresDialect().render(reserved));
    assertEquals("ALTER TABLE `user` ADD COLUMN `order` int", new MySqlDialect().render(reserved));
    assertEquals("ALTER TABLE [user] ADD [order] int", new SqlServerDialect().render(reserved));
    assertTrue(new OracleDialect().render(new AddColumn("t", column("comment", "varchar", true, null)))
        .contains("\"comment\""));
    assertTrue(new H2Dialect().render(new AddColumn("t", column("value", "integer", true, null)))
        .contains("\"value\""));
  }

  @Test
  void rendersDialectSpecificAddColumnAndDropForeignKeySyntax() {
    assertEquals(
        "ALTER TABLE users DROP FOREIGN KEY fk_users_role",
        new MySqlDialect().render(new DropForeignKey("users", "fk_users_role")));
    assertEquals(
        "ALTER TABLE users DROP CONSTRAINT fk_users_role",
        new PostgresDialect().render(new DropForeignKey("users", "fk_users_role")));
  }

  @Test
  void altersOnlyWhatChanged() {
    var before = column("display_name", "varchar", true, 100);
    var wider = column("display_name", "varchar", true, 200);
    var required = column("display_name", "varchar", false, 100);

    assertEquals("ALTER TABLE users ALTER COLUMN display_name TYPE varchar(200)",
        new PostgresDialect().render(new AlterColumn("users", before, wider)));
    assertEquals("ALTER TABLE users ALTER COLUMN display_name SET NOT NULL",
        new PostgresDialect().render(new AlterColumn("users", before, required)));
    assertEquals("ALTER TABLE users MODIFY COLUMN display_name varchar(200)",
        new MySqlDialect().render(new AlterColumn("users", before, wider)));
    assertEquals("ALTER TABLE users ALTER COLUMN display_name varchar(200) NULL",
        new SqlServerDialect().render(new AlterColumn("users", before, wider)));
    assertEquals("ALTER TABLE users ALTER COLUMN display_name SET DATA TYPE varchar(200)",
        new H2Dialect().render(new AlterColumn("users", before, wider)));
  }

  @Test
  void changesColumnDefaults() {
    var before = column("status", "varchar", true, null);
    var after = new SchemaModel.Column(
        "status", "varchar", true, null, null, null, "'NEW'", false, false, null, "varchar");
    assertEquals(List.of(new AlterColumn("t", before, after)),
        new DiffEngine().diff(
            new SchemaModel(List.of(new SchemaModel.Table("t", List.of(before), null, List.of(), List.of()))),
            new SchemaModel(List.of(new SchemaModel.Table("t", List.of(after), null, List.of(), List.of())))));
    assertEquals("ALTER TABLE t ALTER COLUMN status SET DEFAULT 'NEW'",
        new PostgresDialect().render(new AlterColumn("t", before, after)));
    assertEquals("ALTER TABLE t ALTER COLUMN status DROP DEFAULT",
        new PostgresDialect().render(new AlterColumn("t", after, before)));
    assertEquals("ALTER TABLE t MODIFY (status DEFAULT 'NEW')",
        new OracleDialect().render(new AlterColumn("t", before, after)));
    assertTrue(new SqlServerDialect().render(new AlterColumn("t", before, after))
        .endsWith("ALTER TABLE t ADD DEFAULT 'NEW' FOR status"));
    assertEquals("ALTER TABLE t MODIFY COLUMN status varchar(255) DEFAULT 'NEW'",
        new MySqlDialect().render(new AlterColumn("t", before, after)));
  }

  /** ORA-01451 / ORA-01442: Oracle rejects NULL / NOT NULL that does not change anything. */
  @Test
  void oracleWritesNullabilityOnlyWhenItChanges() {
    var before = column("display_name", "varchar", true, 100);
    assertEquals("ALTER TABLE users MODIFY (display_name varchar2(200 char))",
        new OracleDialect().render(
            new AlterColumn("users", before, column("display_name", "varchar", true, 200))));
    assertEquals("ALTER TABLE users MODIFY (display_name NOT NULL)",
        new OracleDialect().render(
            new AlterColumn("users", before, column("display_name", "varchar", false, 100))));
  }

  @Test
  void identityColumnsUseTheDialectType() {
    var id = new SchemaModel.Column(
        "id", "bigint", false, null, null, null, null, false, true, null, "bigint");
    var table = new CreateTable(new SchemaModel.Table(
        "orders", List.of(id), new SchemaModel.PrimaryKey(List.of("id")), List.of(), List.of()));
    assertTrue(new OracleDialect().render(table)
        .contains("id number(19,0) GENERATED BY DEFAULT AS IDENTITY NOT NULL"),
        new OracleDialect().render(table));
    assertTrue(new PostgresDialect().render(table)
        .contains("id bigint GENERATED BY DEFAULT AS IDENTITY NOT NULL"));
    assertTrue(new MySqlDialect().render(table).contains("id bigint AUTO_INCREMENT NOT NULL"));
    assertTrue(new SqlServerDialect().render(table).contains("id bigint IDENTITY(1,1) NOT NULL"));
  }

  @Test
  void uniqueColumnsGetNamedConstraintsThatCanBeDropped() {
    var email = new SchemaModel.Column(
        "email", "varchar", true, null, null, null, null, true, false, null, "varchar");
    var table = new SchemaModel.Table("customer", List.of(email), null, List.of(), List.of());
    String create = new PostgresDialect().render(new CreateTable(table));
    assertTrue(create.contains("CONSTRAINT uk_customer_email UNIQUE (email)"), create);
    assertFalse(create.contains("varchar(255) UNIQUE"), create);

    var notUnique = column("email", "varchar", true, null);
    List<Operation> drop = new DiffEngine().diff(new SchemaModel(List.of(table)),
        new SchemaModel(List.of(new SchemaModel.Table(
            "customer", List.of(notUnique), null, List.of(), List.of()))));
    assertEquals(List.of(new DropUnique("customer", "email", "uk_customer_email")), drop);
    assertEquals("ALTER TABLE customer DROP CONSTRAINT uk_customer_email",
        new PostgresDialect().render(drop.get(0)));
    assertEquals("ALTER TABLE customer DROP INDEX uk_customer_email",
        new MySqlDialect().render(drop.get(0)));
    assertEquals("ALTER TABLE customer ADD CONSTRAINT uk_customer_email UNIQUE (email)",
        new PostgresDialect().render(new AddUnique("customer", "email", "uk_customer_email")));
  }

  /** MySQL MODIFY COLUMN used to repeat UNIQUE, adding a duplicate index on every change. */
  @Test
  void mysqlModifyDoesNotRepeatUnique() {
    var before = new SchemaModel.Column(
        "email", "varchar", true, 100, null, null, null, true, false, null, "varchar");
    var after = new SchemaModel.Column(
        "email", "varchar", true, 200, null, null, null, true, false, null, "varchar");
    assertFalse(new MySqlDialect().render(new AlterColumn("users", before, after))
        .contains("UNIQUE"));
  }

  @Test
  void typeChangesAndNarrowingAreDestructiveButWideningIsNot() {
    var before = column("name", "varchar", true, 100);
    assertFalse(new AlterColumn("t", before, column("name", "varchar", true, 200)).destructive());
    assertFalse(new AlterColumn("t", before, column("name", "varchar", false, 100)).destructive());
    assertTrue(new AlterColumn("t", before, column("name", "varchar", true, 50)).destructive());
    assertTrue(new AlterColumn("t", before, column("name", "integer", true, null)).destructive());
  }

  @Test
  void mysqlEmulatesSequencesLikeHibernate() {
    var sequence = new CreateSequence(new SchemaModel.Sequence("customer_seq", 1L, 50L));
    assertEquals("CREATE TABLE customer_seq (next_val bigint);\n"
        + "INSERT INTO customer_seq VALUES (1)", new MySqlDialect().render(sequence));
    assertEquals("CREATE SEQUENCE customer_seq START WITH 1 INCREMENT BY 50",
        new MariaDbDialect().render(sequence));
    assertEquals("CREATE SEQUENCE customer_seq START WITH 1 INCREMENT BY 50",
        new PostgresDialect().render(sequence));
  }

  @Test
  void everyLogicalTypeMapsInEveryDialect() {
    for (String logical : List.of("varchar", "nvarchar", "text", "clob", "varbinary", "integer", "bigint", "boolean",
        "decimal", "double", "float", "uuid", "date", "time", "timestamp", "timestamptz", "blob",
        "json")) {
      // No SQL type to fall back on: an unmapped logical type throws.
      var c = new SchemaModel.Column(
          "c", null, true, null, null, null, null, false, false, null, logical);
      for (Dialect dialect : ALL) {
        assertNotNull(dialect.render(new AddColumn("t", c)), dialect.id() + " " + logical);
      }
    }
    assertTrue(new SqlServerDialect().render(new AddColumn("t", column("c", "timestamp", true, null)))
        .contains("datetime2(6)"));
    assertTrue(new OracleDialect().render(new AddColumn("t", column("c", "text", true, null)))
        .contains("clob"));
    assertTrue(new PostgresDialect().render(new AddColumn("t", column("c", "nvarchar", true, 40)))
        .contains("varchar(40)"));
    assertTrue(new MySqlDialect().render(new AddColumn("t", column("c", "uuid", true, null)))
        .contains("binary(16)"));
    assertTrue(new PostgresDialect().render(new AddColumn("t", column("c", "timestamptz", true, null)))
        .contains("timestamp(6) with time zone"));
  }

  @Test
  void legacyTimestampLogicalTypeIsNotRenderedAsSqlServerRowversion() {
    var legacy = new SchemaModel.Column(
        "created", "TIMESTAMP", true, null, null, null, null, false, false, null, "TIMESTAMP");
    assertTrue(new SqlServerDialect().render(new AddColumn("t", legacy)).contains("datetime2(6)"));
  }

  @Test
  void dropsPrimaryKeysByTheirActualName() {
    assertEquals(
        "ALTER TABLE users DROP CONSTRAINT users_custom_pk",
        new PostgresDialect().render(new DropPrimaryKey("users", "users_custom_pk")));
    assertEquals(
        "ALTER TABLE users DROP CONSTRAINT users_custom_pk",
        new SqlServerDialect().render(new DropPrimaryKey("users", "users_custom_pk")));
    assertEquals("ALTER TABLE users DROP PRIMARY KEY",
        new OracleDialect().render(new DropPrimaryKey("users", null)));
  }

  @Test
  void boundsGeneratedConstraintNamesForOracleCompatibility() {
    assertTrue(SchemaModel.primaryKeyConstraintName("a_very_long_table_name_for_oracle")
        .length() <= 30);
    assertTrue(SchemaModel.uniqueConstraintName("a_very_long_table_name", "a_long_column_name")
        .length() <= 30);
  }

  @Test
  void refusesToGuessMissingPrimaryKeyConstraintNames() {
    assertThrows(IllegalStateException.class,
        () -> new PostgresDialect().render(new DropPrimaryKey("users")));
  }
}
