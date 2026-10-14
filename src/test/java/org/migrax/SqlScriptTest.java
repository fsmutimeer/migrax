package org.migrax;

import org.migrax.runner.SqlScript;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlScriptTest {

  private static List<String> split(String sql) {
    return SqlScript.split(sql, false);
  }

  @Test
  void plainScriptsSplitOnSemicolons() {
    assertEquals(List.of("CREATE TABLE a (id INT)", "INSERT INTO a VALUES (1)"),
        split("CREATE TABLE a (id INT);\nINSERT INTO a VALUES (1);\n"));
  }

  @Test
  void goLinesEndSqlServerBatches() {
    String sql = """
        CREATE TABLE audit_log (id INT PRIMARY KEY, note VARCHAR(100));
        GO
        CREATE PROCEDURE add_note @note VARCHAR(100) AS
        BEGIN
          SET NOCOUNT ON;
          INSERT INTO audit_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, @note FROM audit_log;
        END;
        go
        """;
    List<String> statements = split(sql);
    assertEquals(2, statements.size(), statements.toString());
    assertEquals("CREATE TABLE audit_log (id INT PRIMARY KEY, note VARCHAR(100));",
        statements.get(0));
    assertEquals("""
        CREATE PROCEDURE add_note @note VARCHAR(100) AS
        BEGIN
          SET NOCOUNT ON;
          INSERT INTO audit_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, @note FROM audit_log;
        END;""", statements.get(1));
  }

  @Test
  void goWithACountRepeatsTheBatch() {
    String sql = """
        INSERT INTO counter DEFAULT VALUES;
        GO 3
        SELECT 1;
        GO
        """;
    assertEquals(List.of("INSERT INTO counter DEFAULT VALUES;", "INSERT INTO counter DEFAULT VALUES;",
        "INSERT INTO counter DEFAULT VALUES;", "SELECT 1;"), split(sql));
    // A column called go is not a separator, and GO 1 is a plain GO.
    assertEquals(List.of("SELECT go FROM t;", "SELECT 2;"),
        split("SELECT go FROM t;\nGO 1\nSELECT 2;\nGO\n"));
  }

  @Test
  void delimiterLinesChangeTheMysqlDelimiter() {
    String sql = """
        CREATE TABLE counter (id INT PRIMARY KEY, hits INT);
        DELIMITER $$
        CREATE TRIGGER count_hits BEFORE UPDATE ON counter FOR EACH ROW
        BEGIN
          SET NEW.hits = OLD.hits + 1;
        END$$
        CREATE PROCEDURE reset_hits() BEGIN UPDATE counter SET hits = 0; END$$
        DELIMITER ;
        INSERT INTO counter VALUES (1, 0);
        """;
    assertEquals(List.of(
        "CREATE TABLE counter (id INT PRIMARY KEY, hits INT)",
        """
            CREATE TRIGGER count_hits BEFORE UPDATE ON counter FOR EACH ROW
            BEGIN
              SET NEW.hits = OLD.hits + 1;
            END""",
        "CREATE PROCEDURE reset_hits() BEGIN UPDATE counter SET hits = 0; END",
        "INSERT INTO counter VALUES (1, 0)"), split(sql));
  }

  @Test
  void aColumnNamedDelimiterIsNotACommand() {
    assertEquals(List.of("CREATE TABLE csv_format (\n  delimiter CHAR,\n  quote CHAR\n)"),
        split("CREATE TABLE csv_format (\n  delimiter CHAR,\n  quote CHAR\n);\n"));
  }

  @Test
  void slashLinesEndOraclePlsqlBlocks() {
    String sql = """
        CREATE TABLE audit_log (id NUMBER PRIMARY KEY, note VARCHAR2(100));
        CREATE OR REPLACE PROCEDURE add_note(p_note IN VARCHAR2) AS
        BEGIN
          INSERT INTO audit_log (id, note)
            SELECT NVL(MAX(id), 0) + 1, p_note FROM audit_log;
        END;
        /
        -- an anonymous block
        BEGIN
          add_note('first; with a semicolon');
        END;
        /
        INSERT INTO audit_log (id, note) VALUES (100, 'plain');
        """;
    assertEquals(List.of(
        "CREATE TABLE audit_log (id NUMBER PRIMARY KEY, note VARCHAR2(100))",
        """
            CREATE OR REPLACE PROCEDURE add_note(p_note IN VARCHAR2) AS
            BEGIN
              INSERT INTO audit_log (id, note)
                SELECT NVL(MAX(id), 0) + 1, p_note FROM audit_log;
            END;""",
        """
            -- an anonymous block
            BEGIN
              add_note('first; with a semicolon');
            END;""",
        "INSERT INTO audit_log (id, note) VALUES (100, 'plain')"), split(sql));
  }

  @Test
  void postgresFunctionsWithoutSlashLinesStayAsTheyWere() {
    String sql = """
        CREATE FUNCTION touch() RETURNS trigger AS $$
        BEGIN
          NEW.updated := now();
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql;
        BEGIN;
        SELECT 1;
        """;
    List<String> statements = split(sql);
    assertEquals(3, statements.size(), statements.toString());
    assertEquals("BEGIN", statements.get(1));
  }

  @Test
  void noSplitSendsTheWholeScript() {
    String sql = "-- migrax:no-split\nCREATE PROCEDURE p() BEGIN SELECT 1; SELECT 2; END;\n";
    assertEquals(List.of(sql.strip()), split(sql));
  }
}
