package io.migrax;

import io.migrax.dialect.*;
import io.migrax.model.SchemaModel;
import io.migrax.ops.AddColumn;
import io.migrax.ops.DropForeignKey;
import io.migrax.ops.DropPrimaryKey;
import io.migrax.ops.AlterColumn;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DialectTest {
  @Test void detectsAllJdbcDialects() {
    assertEquals("postgresql", Dialects.fromJdbcUrl("jdbc:postgresql://localhost/db").id());
    assertEquals("mysql", Dialects.fromJdbcUrl("jdbc:mysql://localhost/db").id());
    assertEquals("mariadb", Dialects.fromJdbcUrl("jdbc:mariadb://localhost/db").id());
    assertEquals("sqlserver", Dialects.fromJdbcUrl("jdbc:sqlserver://localhost;databaseName=db").id());
    assertEquals("oracle", Dialects.fromJdbcUrl("jdbc:oracle:thin:@localhost:1521/XEPDB1").id());
    assertEquals("h2", Dialects.fromJdbcUrl("jdbc:h2:mem:test").id());
  }

  @Test void rendersQuotedAddColumnAcrossDialects() {
    var c = new SchemaModel.Column("display_name", "VARCHAR(100)", true, 100, null, null, null, false);
    var op = new AddColumn("users", c);
    assertTrue(new PostgresDialect().render(op).contains("\"display_name\""));
    assertTrue(new MySqlDialect().render(op).contains("`display_name`"));
    assertTrue(new SqlServerDialect().render(op).contains("[display_name]"));
    assertTrue(new OracleDialect().render(op).contains("\"display_name\""));
    assertTrue(new H2Dialect().render(op).contains("\"display_name\""));
  }

  @Test
  void rendersDialectSpecificAddColumnAndDropForeignKeySyntax() {
    var column = new SchemaModel.Column(
        "display_name", "varchar", true, 100, null, null, null, false,
        false, null, "varchar");
    assertEquals(
        "ALTER TABLE [users] ADD [display_name] varchar(100)",
        new SqlServerDialect().render(new AddColumn("users", column)));
    assertEquals(
        "ALTER TABLE \"users\" ADD (\"display_name\" varchar2(100))",
        new OracleDialect().render(new AddColumn("users", column)));
    assertEquals(
        "ALTER TABLE `users` DROP FOREIGN KEY `fk_users_role`",
        new MySqlDialect().render(new DropForeignKey("users", "fk_users_role")));
  }

  @Test
  void rendersAlterColumnUsingDialectTypeAndActualPrimaryKeyName() {
    var before = new SchemaModel.Column(
        "display_name", "varchar", true, 100, null, null, null, false,
        false, null, "varchar");
    var after = new SchemaModel.Column(
        "display_name", "varchar", true, 200, null, null, null, false,
        false, null, "varchar");
    assertTrue(new PostgresDialect()
        .render(new AlterColumn("users", before, after)).contains("TYPE varchar(200)"));
    assertTrue(new MySqlDialect()
        .render(new AlterColumn("users", before, after)).contains("MODIFY COLUMN `display_name` varchar(200)"));
    assertTrue(new SqlServerDialect()
        .render(new AlterColumn("users", before, after)).contains("[display_name] varchar(200) NULL"));
    assertTrue(new OracleDialect()
        .render(new AlterColumn("users", before, after)).contains("\"display_name\" varchar2(200) NULL"));
    assertEquals(
        "ALTER TABLE \"users\" DROP CONSTRAINT \"users_custom_pk\"",
        new PostgresDialect().render(new DropPrimaryKey("users", "users_custom_pk")));
    assertEquals(
        "ALTER TABLE [users] DROP CONSTRAINT [users_custom_pk]",
        new SqlServerDialect().render(new DropPrimaryKey("users", "users_custom_pk")));
  }

  @Test
  void boundsGeneratedPrimaryKeyNamesForOracleCompatibility() {
    assertTrue(SchemaModel.primaryKeyConstraintName("a_very_long_table_name_for_oracle")
        .length() <= 30);
  }

  @Test
  void refusesToGuessMissingPrimaryKeyConstraintNames() {
    assertThrows(IllegalStateException.class,
        () -> new PostgresDialect().render(new DropPrimaryKey("users")));
  }
}
