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
  void routinesWithoutSeparatorsEndWhereTheirBodyEnds() {
    // MySQL and MariaDB, no DELIMITER: nested IF, CASE expression, WHILE, IS NULL.
    List<String> mysql = split("""
        CREATE TABLE note_log (id INT PRIMARY KEY, note VARCHAR(100));
        CREATE PROCEDURE add_note(IN p_note VARCHAR(100))
        BEGIN
          DECLARE next_id INT;
          SELECT COALESCE(MAX(id), 0) + 1 INTO next_id FROM note_log;
          IF p_note IS NULL THEN SET p_note = 'none'; END IF;
          WHILE next_id < 0 DO SET next_id = next_id + 1; END WHILE;
          INSERT INTO note_log (id, note) VALUES (next_id, CASE WHEN p_note = '' THEN '-' ELSE p_note END);
        END;
        CREATE TRIGGER fill_note BEFORE INSERT ON note_log FOR EACH ROW
          SET NEW.note = IF(NEW.note IS NULL, '-', NEW.note);
        INSERT INTO note_log VALUES (1, 'a');
        """);
    assertEquals(4, mysql.size(), mysql.toString());
    org.junit.jupiter.api.Assertions.assertTrue(mysql.get(1).startsWith("CREATE PROCEDURE")
        && mysql.get(1).endsWith("END;"), mysql.get(1));
    org.junit.jupiter.api.Assertions.assertTrue(mysql.get(2).startsWith("CREATE TRIGGER"));

    // Oracle, no / line: declarations before BEGIN, a loop, the name after END.
    List<String> oracle = split("""
        CREATE OR REPLACE PROCEDURE add_note(p_note IN VARCHAR2) AS
          next_id NUMBER;
        BEGIN
          SELECT NVL(MAX(id), 0) + 1 INTO next_id FROM note_log;
          FOR i IN 1..2 LOOP NULL; END LOOP;
          INSERT INTO note_log (id, note) VALUES (next_id, p_note);
        END add_note;
        CREATE TABLE other (id NUMBER);
        """);
    assertEquals(2, oracle.size(), oracle.toString());
    org.junit.jupiter.api.Assertions.assertTrue(oracle.get(0).endsWith("END add_note;"));

    // SQLite trigger, no / line.
    List<String> sqlite = split("""
        CREATE TRIGGER add_note AFTER INSERT ON note_source
        BEGIN
          INSERT INTO note_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, NEW.note FROM note_log;
        END;
        INSERT INTO note_source VALUES ('x');
        """);
    assertEquals(2, sqlite.size(), sqlite.toString());

    // SQL Server, no GO: TRY/CATCH and a transaction inside the body.
    List<String> sqlServer = split("""
        CREATE OR ALTER PROCEDURE add_note @note VARCHAR(100) AS
        BEGIN
          SET NOCOUNT ON;
          BEGIN TRANSACTION;
          BEGIN TRY
            INSERT INTO note_log (id, note) SELECT COALESCE(MAX(id), 0) + 1, @note FROM note_log;
            COMMIT;
          END TRY
          BEGIN CATCH
            ROLLBACK;
          END CATCH;
        END;
        EXEC add_note 'x';
        """);
    assertEquals(2, sqlServer.size(), sqlServer.toString());

    // A body without BEGIN: on SQL Server it runs to the end of the batch.
    assertEquals(1, split("CREATE PROCEDURE p AS SET NOCOUNT ON; SELECT 1;\n").size());

    // PostgreSQL's quoted bodies are unaffected, and so are plain statements.
    assertEquals(2, split("""
        CREATE FUNCTION add_note(p_note VARCHAR) RETURNS void AS $$
        BEGIN
          INSERT INTO note_log (id, note) VALUES (1, p_note);
        END;
        $$ LANGUAGE plpgsql;
        SELECT add_note('a');
        """).size());
    assertEquals(List.of("CREATE TABLE begin_log (id INT)", "INSERT INTO begin_log VALUES (1)"),
        split("CREATE TABLE begin_log (id INT);\nINSERT INTO begin_log VALUES (1);\n"));
  }

  @Test
  void oraclePackagesAndAnonymousBlocksWithoutSlashLines() {
    List<String> statements = split("""
        CREATE OR REPLACE PACKAGE notes AS
          TYPE note_list IS TABLE OF VARCHAR2(100);
          PROCEDURE add_note(p_note IN VARCHAR2);
          FUNCTION note_count RETURN NUMBER;
        END notes;
        CREATE OR REPLACE PACKAGE BODY notes AS
          added NUMBER := 0;
          CURSOR all_notes IS SELECT note FROM note_log;
          FUNCTION next_id RETURN NUMBER IS
            result NUMBER;
          BEGIN
            SELECT NVL(MAX(id), 0) + 1 INTO result FROM note_log;
            RETURN result;
          END next_id;
          PROCEDURE add_note(p_note IN VARCHAR2) IS
            PROCEDURE count_it IS BEGIN added := added + 1; END;
          BEGIN
            INSERT INTO note_log (id, note) VALUES (next_id, p_note);
            IF p_note IS NULL THEN NULL; END IF;
            count_it;
          END add_note;
          FUNCTION note_count RETURN NUMBER IS BEGIN RETURN added; END;
        BEGIN
          added := 0;
        END notes;
        CREATE OR REPLACE TYPE BODY point AS
          MEMBER FUNCTION length RETURN NUMBER IS
          BEGIN
            RETURN SQRT(x * x + y * y);
          END;
        END;
        DECLARE
          total NUMBER;
        BEGIN
          SELECT COUNT(*) INTO total FROM note_log;
          IF total = 0 THEN notes.add_note('first'); END IF;
        END;
        BEGIN
          notes.add_note('second');
        END;
        INSERT INTO note_log VALUES (9, 'last');
        """);
    assertEquals(6, statements.size(), statements.toString());
    org.junit.jupiter.api.Assertions.assertTrue(statements.get(0).endsWith("END notes;"));
    org.junit.jupiter.api.Assertions.assertTrue(statements.get(1).startsWith(
        "CREATE OR REPLACE PACKAGE BODY") && statements.get(1).endsWith("END notes;"));
    org.junit.jupiter.api.Assertions.assertTrue(statements.get(2).startsWith(
        "CREATE OR REPLACE TYPE BODY") && statements.get(2).endsWith("END;"));
    org.junit.jupiter.api.Assertions.assertTrue(statements.get(3).startsWith("DECLARE")
        && statements.get(3).endsWith("END;"));
    assertEquals("BEGIN\n  notes.add_note('second');\nEND;", statements.get(4));
    assertEquals("INSERT INTO note_log VALUES (9, 'last')", statements.get(5));
  }

  @Test
  void transactionsAndVariablesAreNotBlocks() {
    assertEquals(List.of("BEGIN", "INSERT INTO a VALUES (1)", "COMMIT"),
        split("BEGIN;\nINSERT INTO a VALUES (1);\nCOMMIT;\n"));
    assertEquals(2, split("BEGIN TRANSACTION;\nINSERT INTO a VALUES (1);\n").size());
    assertEquals(2, split("BEGIN ISOLATION LEVEL SERIALIZABLE;\nINSERT INTO a VALUES (1);\n")
        .size());
    assertEquals(2, split("BEGIN IMMEDIATE;\nINSERT INTO a VALUES (1);\n").size());
    assertEquals(2, split("DECLARE @n INT;\nSELECT 1;\n").size());
    org.junit.jupiter.api.Assertions.assertTrue(SqlScript.isRoutine("BEGIN\n  p;\nEND;"));
    org.junit.jupiter.api.Assertions.assertTrue(SqlScript.isRoutine(
        "CREATE PACKAGE BODY p AS BEGIN NULL; END;"));
    org.junit.jupiter.api.Assertions.assertFalse(SqlScript.isRoutine("BEGIN TRANSACTION"));
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
