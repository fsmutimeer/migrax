package org.migrax;

import org.migrax.dialect.*;
import org.migrax.diff.DiffEngine;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddColumn;
import org.migrax.ops.AddUnique;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.CreateSequence;
import org.migrax.ops.CreateTable;
import org.migrax.ops.DropForeignKey;
import org.migrax.ops.DropPrimaryKey;
import org.migrax.ops.DropUnique;
import org.migrax.ops.Operation;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DialectTest {

  @Test
  void cockroachRunsTypeChangesThatRewriteOutsideATransaction() {
    Dialect cockroach = Dialects.byName("cockroachdb");
    java.util.function.BiFunction<SchemaModel.Column, SchemaModel.Column, String> migration =
        (from, to) -> cockroach.prepare(List.of(new AlterColumn("t", from, to)),
                SchemaModel.empty(), SchemaModel.empty()).stream().map(cockroach::render)
            .map(sql -> sql + ";\n").collect(java.util.stream.Collectors.joining());
    SchemaModel.Column big = new SchemaModel.Column("n", "bigint", true, null, null, null,
        null, false, false, null, "bigint");
    SchemaModel.Column small = new SchemaModel.Column("n", "integer", true, null, null, null,
        null, false, false, null, "integer");
    String narrowing = migration.apply(big, small);
    assertTrue(org.migrax.runner.SqlScript.hasDirective(narrowing, "no-transaction"), narrowing);
    assertTrue(narrowing.contains("SET enable_experimental_alter_column_type_general = true;"));

    String widening = migration.apply(small, big);
    assertFalse(widening.contains("experimental"), widening);
    SchemaModel.Column shortText = new SchemaModel.Column("s", "varchar", true, 10, null, null,
        null, false, false, null, "varchar");
    SchemaModel.Column longText = new SchemaModel.Column("s", "varchar", true, 20, null, null,
        null, false, false, null, "varchar");
    assertFalse(migration.apply(shortText, longText).contains("experimental"));
    assertTrue(migration.apply(longText, shortText).contains("experimental"));
  }
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
    assertEquals("hsqldb", Dialects.fromJdbcUrl("jdbc:hsqldb:mem:test").id());
    assertEquals("derby", Dialects.fromJdbcUrl("jdbc:derby:memory:test;create=true").id());
    // TiDB uses the MySQL driver: the server, not the URL, tells them apart.
    assertEquals("mysql", Dialects.fromJdbcUrl("jdbc:mysql://localhost:4000/db").id());
    // YugabyteDB's own driver; with the PostgreSQL driver, the server tells.
    assertEquals("yugabytedb", Dialects.fromJdbcUrl("jdbc:yugabytedb://localhost:5433/db").id());
    assertThrows(IllegalArgumentException.class, () -> Dialects.byName("db2"));
  }

  @Test void registeredDialectsAreFoundByNameAndAlias() {
    assertEquals(List.of("postgresql", "cockroachdb", "yugabytedb", "mysql", "mariadb", "tidb",
        "sqlserver", "oracle", "h2", "hsqldb", "derby", "sqlite"), Dialects.NAMES);
    assertEquals("hsqldb", Dialects.byName("hsql").id());
    assertEquals("yugabytedb", Dialects.byName("yb").id());
    assertEquals("yugabytedb", Dialects.byName("yugabyte").id());
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
    assertEquals("yugabytedb", Dialects.forProduct("YugabyteDB").orElseThrow().id());
    assertEquals("tidb", Dialects.forProduct("TiDB").orElseThrow().id());
    assertEquals("hsqldb", Dialects.forProduct("HSQL Database Engine").orElseThrow().id());
    assertEquals("derby", Dialects.forProduct("Apache Derby").orElseThrow().id());
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
          () -> withoutLocking.acquireMigrationLock(connection, "org.migrax:test"));
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
  @Test void oracle23aiBooleansUseTheBooleanType() {
    SchemaModel.Column flag = column("active", "boolean", false, null);
    Dialect unknown = new OracleDialect();
    assertTrue(unknown.dependsOnServerVersion());
    assertEquals("number(1,0)", unknown.columnType(flag));
    assertEquals(java.util.Map.of(), unknown.serverSettings());
    assertEquals("number(1,0)", unknown.forServer(21, 3).columnType(flag));
    assertEquals("gvenzl/oracle-xe:21-slim-faststart", unknown.forServer(21, 3).scratchImage());

    Dialect oracle23 = unknown.forServer(23, 26);
    assertEquals("boolean", oracle23.columnType(flag));
    assertEquals("23", oracle23.serverSettings().get("jakarta.persistence.database-major-version"));
    assertEquals("gvenzl/oracle-free:slim-faststart", oracle23.scratchImage());
    assertEquals("oracle", oracle23.id());

    Dialect postgres = new PostgresDialect();
    assertFalse(postgres.dependsOnServerVersion());
    assertSame(postgres, postgres.forServer(16, 4));
  }

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

  @Test
  void sqlServerNamesTheSchemaOfEveryTableWhenBoundToOne() {
    SqlServerDialect dialect = new SqlServerDialect().inSchema("${migrax.schema}");
    SchemaModel.Table author = new SchemaModel.Table("author",
        List.of(column("id", "bigint", false, null)), new SchemaModel.PrimaryKey(List.of("id")),
        List.of(), List.of(), List.of(), "Writers");
    SchemaModel.Column authorId = column("author_id", "bigint", true, null);
    SchemaModel.Table book = new SchemaModel.Table("book",
        List.of(column("id", "bigint", false, null), authorId),
        new SchemaModel.PrimaryKey(List.of("id")),
        List.of(new SchemaModel.Index("ix_book_author", List.of("author_id"), false)),
        List.of(new SchemaModel.ForeignKey("fk_book_author", List.of("author_id"), "author",
            List.of("id"))));
    SchemaModel model = new SchemaModel(List.of(author, book),
        List.of(new SchemaModel.Sequence("book_seq", 1L, 50L)));
    String sql = new DiffEngine().diff(SchemaModel.empty(), model).stream()
        .map(dialect::render).collect(java.util.stream.Collectors.joining(";\n"));
    assertTrue(sql.contains("CREATE SEQUENCE ${migrax.schema}.book_seq"), sql);
    assertTrue(sql.contains("CREATE TABLE ${migrax.schema}.author ("), sql);
    assertTrue(sql.contains("CREATE TABLE ${migrax.schema}.book ("), sql);
    assertTrue(sql.contains("CREATE INDEX ix_book_author ON ${migrax.schema}.book"), sql);
    assertTrue(sql.contains("ALTER TABLE ${migrax.schema}.book ADD CONSTRAINT fk_book_author "
        + "FOREIGN KEY (author_id) REFERENCES ${migrax.schema}.author (id)"), sql);
    assertTrue(sql.contains("DECLARE @schema sysname = N'${migrax.schema}' IF EXISTS"), sql);
    assertTrue(sql.contains("OBJECT_ID(N'${migrax.schema}.author')"), sql);

    assertEquals("EXEC sp_rename '${migrax.schema}.book', 'books'",
        dialect.render(new org.migrax.ops.RenameTable("book", "books")));
    assertEquals("EXEC sp_rename '${migrax.schema}.book.author_id', 'writer_id', 'COLUMN'",
        dialect.render(new org.migrax.ops.RenameColumn("book", "author_id", "writer_id")));
    assertEquals("DROP INDEX ix_book_author ON ${migrax.schema}.book",
        dialect.render(new org.migrax.ops.DropIndex("book", "ix_book_author")));
    assertEquals("ALTER TABLE ${migrax.schema}.book DROP COLUMN author_id",
        dialect.render(new org.migrax.ops.DropColumn("book", "author_id")));
    SchemaModel.Column withDefault = new SchemaModel.Column("author_id", "bigint", true, null,
        null, null, "0", false, false, null, "bigint");
    String alter = dialect.render(new AlterColumn("book", authorId, withDefault));
    assertTrue(alter.contains("OBJECT_ID('${migrax.schema}.book')"), alter);
    assertTrue(alter.contains("ALTER TABLE ${migrax.schema}.book ADD DEFAULT 0 FOR author_id"),
        alter);
    assertEquals("SELECT id, author_id INTO ${migrax.schema}.migrax_backup_book FROM "
        + "${migrax.schema}.book", dialect.copyTable("migrax_backup_book", "book",
            List.of("id", "author_id")));

    // A schema name is quoted; the unbound dialect writes bare names as before.
    assertEquals("DROP TABLE [tenant_a].book",
        new SqlServerDialect().inSchema("tenant_a").render(new org.migrax.ops.DropTable("book")));
    assertEquals("DROP TABLE book",
        new SqlServerDialect().render(new org.migrax.ops.DropTable("book")));
  }

  @Test void yugabyteWritesThePostgresqlSql() {
    Dialect yugabyte = Dialects.byName("yugabytedb");
    Dialect postgres = Dialects.byName("postgresql");
    SchemaModel.Column email = new SchemaModel.Column("email", "varchar", false, 120, null, null,
        null, true, false, null, "varchar");
    for (Operation operation : List.<Operation>of(
        new org.migrax.ops.CreateTable(new SchemaModel.Table("customer", List.of(email),
            new SchemaModel.PrimaryKey(List.of("email"), "customer_pkey"), List.of(),
            List.of())),
        new org.migrax.ops.AddColumn("customer", email),
        new AlterColumn("customer", email, new SchemaModel.Column("email", "varchar", true, 200,
            null, null, null, true, false, null, "varchar")))) {
      assertEquals(postgres.render(operation), yugabyte.render(operation));
    }
    assertFalse(yugabyte.transactionalDdl());
    assertEquals(postgres.syncColumns("t", "a", "b"), yugabyte.syncColumns("t", "a", "b"));
  }

  @Test void tidbWritesTheMysqlSqlAndCopiesBackupsWithoutCreateTableAsSelect() {
    Dialect tidb = Dialects.byName("tidb");
    Dialect mysql = Dialects.byName("mysql");
    SchemaModel.Column name = new SchemaModel.Column("name", "varchar", false, 100, null, null,
        null, false, false, null, "varchar");
    for (Operation operation : List.<Operation>of(
        new org.migrax.ops.AddColumn("item", name),
        new AlterColumn("item", name, new SchemaModel.Column("name", "varchar", true, 200,
            null, null, null, false, false, null, "varchar")))) {
      assertEquals(mysql.render(operation), tidb.render(operation));
    }
    assertEquals("CREATE TABLE backup LIKE item;\nINSERT INTO backup SELECT * FROM item",
        tidb.copyTable("backup", "item", List.of("id", "name")));
    assertTrue(tidb.syncColumns("t", "a", "b").isEmpty());
    assertFalse(tidb.updatableViews());
    assertFalse(tidb.transactionalDdl());
    assertFalse(mysql.transactionalDdl());
  }

  @Test void hsqldbWritesItsOwnRenamesCommentsAndCopies() {
    Dialect hsqldb = Dialects.byName("hsqldb");
    assertEquals("ALTER TABLE item ALTER COLUMN name RENAME TO title",
        hsqldb.render(new org.migrax.ops.RenameColumn("item", "name", "title")));
    assertEquals("COMMENT ON TABLE item IS ''",
        hsqldb.render(new org.migrax.ops.SetComment("item", null, null)));
    assertEquals("CREATE TABLE backup AS (SELECT id, name FROM item) WITH DATA",
        hsqldb.copyTable("backup", "item", List.of("id", "name")));
    SchemaModel.Column id = new SchemaModel.Column("id", "bigint", false, null, null, null, null,
        false, false, "item_seq", "bigint");
    // No sequence default: HyperSQL has none, Hibernate draws ids itself.
    assertTrue(hsqldb.render(new org.migrax.ops.AddColumn("item", id)).endsWith("id bigint NOT NULL"),
        hsqldb.render(new org.migrax.ops.AddColumn("item", id)));
    assertTrue(hsqldb.updatableViews());
    assertTrue(hsqldb.syncColumns("t", "a", "b").isEmpty());
  }

  private static SchemaModel.Column varchar(String name, int length, boolean nullable) {
    return new SchemaModel.Column(name, "varchar", nullable, length, null, null, null, false,
        false, null, "varchar");
  }

  @Test void derbyRebuildsAColumnItCantChangeInPlace() {
    Dialect derby = Dialects.byName("derby");
    SchemaModel.Column wide = varchar("grade", 100, false);
    SchemaModel.Column narrow = varchar("grade", 10, false);
    SchemaModel after = new SchemaModel(List.of(new SchemaModel.Table("item", List.of(
        new SchemaModel.Column("id", "bigint", false, null, null, null, null, false, false,
            null, "bigint"), narrow), new SchemaModel.PrimaryKey(List.of("id"), "item_pkey"),
        List.of(new SchemaModel.Index("idx_item_grade", List.of("grade"), false)), List.of())));
    List<String> sql = derby.prepare(List.of(new AlterColumn("item", wide, narrow)),
        SchemaModel.empty(), after).stream().map(derby::render).toList();
    assertEquals(List.of(
        "ALTER TABLE item ADD COLUMN migrax_new_grade varchar(10)",
        "UPDATE item SET migrax_new_grade = CAST(grade AS varchar(10))",
        "ALTER TABLE item DROP COLUMN grade",
        "RENAME COLUMN item.migrax_new_grade TO grade",
        "ALTER TABLE item ALTER COLUMN grade NOT NULL",
        "CREATE INDEX idx_item_grade ON item (grade)"), sql);
    // Lengthening stays in place.
    assertEquals(List.of("ALTER TABLE item ALTER COLUMN grade SET DATA TYPE varchar(200)"),
        derby.prepare(List.of(new AlterColumn("item", wide, varchar("grade", 200, false))),
            SchemaModel.empty(), after).stream().map(derby::render).toList());
    assertEquals("DROP SEQUENCE item_seq RESTRICT",
        derby.render(new org.migrax.ops.DropSequence("item_seq", null)));
    assertEquals("RENAME TABLE item TO product",
        derby.render(new org.migrax.ops.RenameTable("item", "product")));
    assertEquals("-- Derby keeps no comments",
        derby.render(new org.migrax.ops.SetComment("item", null, "Things")));
  }


  // ---------------------------------------------------- Oracle: a smaller NUMBER with rows

  private static SchemaModel.Column amount(String name, Integer precision, Integer scale,
                                           String comment) {
    return new SchemaModel.Column(name, "decimal", false, null, precision, scale, "0", false,
        false, null, "decimal", comment);
  }

  private static SchemaModel payments(SchemaModel.Column amount, SchemaModel.PrimaryKey key,
                                      List<SchemaModel.Index> indexes) {
    return new SchemaModel(List.of(new SchemaModel.Table("payments", List.of(amount), key,
        indexes, List.of())));
  }

  private static List<Operation> oracle(List<Operation> operations, SchemaModel before,
                                        SchemaModel after) {
    return new OracleDialect().prepare(operations, before, after);
  }

  private static Operation oracleChange(SchemaModel.Column from, SchemaModel.Column to) {
    return oracle(List.of(new AlterColumn("payments", from, to)), payments(from, null, List.of()),
        payments(to, null, List.of())).get(0);
  }

  @Test
  void oracleMakesANumberSmallerThroughANewColumnAfterCheckingTheValues() {
    var from = amount("amount", 14, 3, "Paid, in euros");
    var to = amount("amount", 12, 2, "Paid, in euros");
    String sql = ((org.migrax.ops.RunSql) oracleChange(from, to)).sql();

    java.util.regex.Matcher name = java.util.regex.Pattern.compile("migrax_tmp_[0-9a-f]{8}")
        .matcher(sql);
    assertTrue(name.find(), sql);
    String copy = name.group();
    assertTrue(copy.length() <= 30, copy);
    assertTrue(sql.contains("EXECUTE IMMEDIATE 'ALTER TABLE payments DROP COLUMN " + copy + "'"),
        sql);
    assertTrue(sql.contains("EXECUTE IMMEDIATE 'DROP TRIGGER " + copy + "_t'"), sql);
    assertTrue(sql.contains("EXECUTE IMMEDIATE 'ALTER TABLE payments RENAME COLUMN " + copy
        + " TO amount'"), sql);
    assertTrue(sql.contains("table_name = 'PAYMENTS' AND column_name = 'AMOUNT'"), sql);
    assertTrue(sql.contains("all_ind_columns") && sql.contains("all_cons_columns")
        && sql.contains("all_col_privs") && sql.contains("FUNCTION-BASED%"), sql);
    assertTrue(sql.contains("AND (ROUND(amount, 2) <> amount OR ABS(amount) >= POWER(10, 10))"),
        sql);
    assertTrue(sql.contains("do not fit NUMBER(12,2); the column was not changed. Fix or "
        + "delete them, or keep the larger type."), sql);
    assertTrue(sql.contains("ALTER SESSION SET DDL_LOCK_TIMEOUT = 60;\n"
        + "ALTER TABLE payments ADD (" + copy + " number(12,2));\n"
        + "CREATE TRIGGER " + copy + "_t BEFORE INSERT OR UPDATE ON payments FOR EACH ROW\n"
        + "BEGIN\n  :NEW." + copy + " := :NEW.amount;\nEND;\n"
        + "BEGIN\n  EXECUTE IMMEDIATE 'LOCK TABLE payments IN SHARE MODE WAIT 60';\n"
        + "  COMMIT;\nEXCEPTION WHEN OTHERS THEN\n"
        + "    EXECUTE IMMEDIATE 'DROP TRIGGER " + copy + "_t';\n"
        + "    EXECUTE IMMEDIATE 'ALTER TABLE payments DROP COLUMN " + copy + "';\n"
        + "    RAISE;\nEND;\n"
        + "UPDATE payments SET " + copy + " = amount;\n"
        + "DECLARE\n  differing NUMBER;\nBEGIN\n"
        + "  EXECUTE IMMEDIATE 'SELECT COUNT(*) FROM payments WHERE amount IS NOT NULL AND ("
        + copy + " IS NULL OR " + copy + " <> amount)' INTO differing;\n"), sql);
    assertTrue(sql.contains("  IF differing > 0 THEN\n"
        + "    EXECUTE IMMEDIATE 'DROP TRIGGER " + copy + "_t';\n"
        + "    EXECUTE IMMEDIATE 'ALTER TABLE payments DROP COLUMN " + copy + "';\n"), sql);
    assertTrue(sql.contains("written during the move do not fit NUMBER(12,2); the column was "
        + "not changed."), sql);
    assertTrue(sql.contains("  END IF;\nEND;\n"
        + "ALTER TABLE payments DROP COLUMN amount;\n"
        + "DROP TRIGGER " + copy + "_t;\n"
        + "ALTER TABLE payments RENAME COLUMN " + copy + " TO amount;\n"
        + "ALTER TABLE payments MODIFY (amount DEFAULT 0 NOT NULL);\n"
        + "COMMENT ON COLUMN payments.amount IS 'Paid, in euros';\n"
        + "ALTER SESSION SET DDL_LOCK_TIMEOUT = 0"), sql);
    // One warning for the move, none for its drop, rename and copy.
    var findings = org.migrax.lint.SqlLinter.lint("0002_smaller.sql", sql + ";", "oracle");
    assertEquals(List.of("MX003"), findings.stream().map(f -> f.code()).toList(), sql);
  }

  @Test
  void oracleQuotesTheNamesItPutsInStrings() {
    // An exact name is compared as Oracle stores it; a quote in a name stays inside the string.
    var from = amount("\"Amount\"", 14, 2, null);
    var to = amount("\"Amount\"", 12, 2, null);
    var change = new AlterColumn("pay'ments", from, to);
    String sql = ((org.migrax.ops.RunSql) oracle(List.of(change),
        new SchemaModel(List.of(new SchemaModel.Table("pay'ments", List.of(from), null,
            List.of(), List.of()))),
        new SchemaModel(List.of(new SchemaModel.Table("pay'ments", List.of(to), null,
            List.of(), List.of())))).get(0)).sql();
    assertTrue(sql.contains("table_name = 'pay''ments' AND column_name = 'Amount'"), sql);
    assertTrue(sql.contains("'ALTER TABLE \"pay''ments\" DROP COLUMN "), sql);
    assertTrue(sql.contains("value(s) of pay''ments.Amount do not fit"), sql);
  }

  @Test
  void onlyAnOracleMoveThroughANewColumnHidesItsDropAndRename() {
    String move = "UPDATE orders SET migrax_tmp_1 = total;\n"
        + "ALTER TABLE orders DROP COLUMN total;\n"
        + "ALTER TABLE orders RENAME COLUMN migrax_tmp_1 TO total;";
    String bare = "ALTER TABLE orders DROP COLUMN total;\n"
        + "ALTER TABLE orders RENAME COLUMN migrax_tmp_1 TO total;";
    java.util.function.BiFunction<String, String, List<String>> codes = (sql, dialect) ->
        org.migrax.lint.SqlLinter.lint("0002.sql", sql, dialect).stream()
            .map(f -> f.code()).toList();
    assertEquals(List.of("MX003"), codes.apply(move, "oracle"));
    assertEquals(List.of("MX007", "MX008"), codes.apply(bare, "oracle"));
    assertTrue(codes.apply(move, "postgresql").containsAll(List.of("MX007", "MX008")));
  }

  @Test
  void oracleFindsSmallerNumbersByTheTypeOracleGets() {
    // An unsized decimal is number(38,2): a precision of 12 makes it smaller.
    assertInstanceOf(org.migrax.ops.RunSql.class,
        oracleChange(amount("amount", null, null, null), amount("amount", 12, 2, null)));
    // Fewer digits before the point, the same number of decimals... and the same precision.
    assertInstanceOf(org.migrax.ops.RunSql.class,
        oracleChange(amount("amount", 10, 2, null), amount("amount", 10, 4, null)));
    // bigint to integer: number(19,0) to number(10,0).
    var big = new SchemaModel.Column("amount", "bigint", false, null, null, null, null, false,
        false, null, "bigint");
    var small = new SchemaModel.Column("amount", "integer", false, null, null, null, null,
        false, false, null, "integer");
    assertInstanceOf(org.migrax.ops.RunSql.class, oracleChange(big, small));
    // Larger, and a text column with a precision: Oracle changes those in place.
    assertInstanceOf(AlterColumn.class,
        oracleChange(amount("amount", 12, 2, null), amount("amount", 14, 2, null)));
    var text = new SchemaModel.Column("amount", "varchar", false, 20, 12, null, null, false,
        false, null, "varchar");
    var shorter = new SchemaModel.Column("amount", "varchar", false, 10, 10, null, null, false,
        false, null, "varchar");
    assertInstanceOf(AlterColumn.class, oracleChange(text, shorter));
  }

  @Test
  void oracleKeepsThePlainModifyWhereANewColumnWouldLoseSomething() {
    var from = amount("amount", 14, 2, null);
    var to = amount("amount", 12, 2, null);
    var change = new AlterColumn("payments", from, to);
    var other = new SchemaModel.Column("payment_amount", "decimal", true, null, 14, 2, null,
        false, false, null, "decimal");
    // In the primary key, an index, a unique constraint, a check, its own key or another
    // table's key pointing at it: dropping the column would drop those.
    var key = new SchemaModel.PrimaryKey(List.of("amount"), "payments_pkey");
    assertInstanceOf(AlterColumn.class, oracle(List.of(change), payments(from, key, List.of()),
        payments(to, key, List.of())).get(0));
    var index = List.of(new SchemaModel.Index("ix_amount", List.of("amount"), false));
    assertInstanceOf(AlterColumn.class, oracle(List.of(change), payments(from, null, index),
        payments(to, null, index)).get(0));
    var unique = new SchemaModel.Column("amount", "decimal", false, null, 12, 2, "0", true,
        false, null, "decimal");
    assertInstanceOf(AlterColumn.class, oracleChange(from, unique));
    var identity = new SchemaModel.Column("amount", "decimal", false, null, 12, 2, null, false,
        true, null, "decimal");
    assertInstanceOf(AlterColumn.class, oracleChange(from, identity));
    var checked = new SchemaModel(List.of(new SchemaModel.Table("payments", List.of(to), null,
        List.of(), List.of(), List.of(new SchemaModel.Check("ck_amount", "amount >= 0")), null)));
    assertInstanceOf(AlterColumn.class, oracle(List.of(change), payments(from, null, List.of()),
        checked).get(0));
    var ownKey = new SchemaModel(List.of(new SchemaModel.Table("payments", List.of(to), null,
        List.of(), List.of(new SchemaModel.ForeignKey("fk_payments_amount", List.of("amount"),
            "ledger", List.of("total"))))));
    assertInstanceOf(AlterColumn.class, oracle(List.of(change), payments(from, null, List.of()),
        ownKey).get(0));
    var pointedAt = new SchemaModel(List.of(
        new SchemaModel.Table("payments", List.of(to), null, List.of(), List.of()),
        new SchemaModel.Table("refunds", List.of(other), null, List.of(), List.of(
            new SchemaModel.ForeignKey("fk_refunds_payment", List.of("payment_amount"),
                "payments", List.of("amount"))))));
    assertInstanceOf(AlterColumn.class, oracle(List.of(change), payments(from, null, List.of()),
        pointedAt).get(0));
    // A table that isn't in the schema: unknown counts as constrained.
    assertInstanceOf(AlterColumn.class, oracle(List.of(change), payments(from, null, List.of()),
        new SchemaModel(List.of())).get(0));
    // Renamed in the same migration: the index is found under the column's old name.
    var renamed = amount("total", 14, 2, null);
    var indexedTotal = List.of(new SchemaModel.Index("ix_total", List.of("total"), false));
    assertInstanceOf(AlterColumn.class, oracle(
        List.of(new org.migrax.ops.RenameColumn("payments", "total", "amount"), change),
        payments(renamed, null, indexedTotal), payments(to, null, List.of())).get(1));
  }
}
