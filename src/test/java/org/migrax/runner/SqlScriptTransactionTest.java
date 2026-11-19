package org.migrax.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlScriptTransactionTest {
  private static boolean controls(String sql) {
    return SqlScript.hasTransactionControl(SqlScript.split(sql, false));
  }

  @Test
  void findsTransactionsWrittenInAScript() {
    assertTrue(controls(
        "INSERT INTO t VALUES (1);\nCOMMIT;\nALTER TABLE t ADD COLUMN x INT;"));
    assertTrue(controls("BEGIN;\nUPDATE t SET x = 1;\nEND;"));
    assertTrue(controls("rollback work;"));
    assertTrue(controls("START  TRANSACTION;"));
    assertTrue(controls("UPDATE t SET x = 1;\nABORT;"));
    assertTrue(controls("PREPARE TRANSACTION 'x';"));
    assertTrue(controls("COMMIT PREPARED 'x';"));
    assertTrue(controls("UPDATE t SET x = 1;\nCOMMIT AND NO CHAIN;"));
  }

  @Test
  void ignoresBlocksSavepointsAndPlainScripts() {
    assertFalse(controls("ALTER TABLE t ADD COLUMN x INT;"));
    assertFalse(controls("SAVEPOINT a;\nUPDATE t SET x = 1;\nROLLBACK TO SAVEPOINT a;"));
    assertFalse(controls("DO $$ BEGIN\n  UPDATE t SET x = 1;\nEND $$;"));
    assertFalse(controls("CREATE FUNCTION f() RETURNS trigger AS $fn$\nBEGIN\n  COMMIT;\n"
        + "  RETURN NEW;\nEND;\n$fn$ LANGUAGE plpgsql;"));
    assertFalse(controls("-- COMMIT;\nSELECT 'COMMIT';"));
    assertFalse(controls("UPDATE t SET x = CASE WHEN y > 1 THEN 1 ELSE 0\n  END\n  WHERE z;"));
    assertFalse(controls("UPDATE t SET x = CASE\n  WHEN y THEN 1\n  ELSE 0\nEND;"));
  }

  @Test
  void countsWhatItCannotTellApart() {
    // A lone END commits on PostgreSQL.
    assertTrue(controls("INSERT INTO t VALUES (1);\nEND;\nALTER TABLE t ADD COLUMN x INT;"));
    assertTrue(controls("UPDATE t SET x = 1;\nEND WORK;"));
    // E'...' strings are PostgreSQL's own: the splitter keeps all of this as one statement,
    // while the driver sends the COMMIT, ROLLBACK or END on its own.
    for (String command : new String[] {"COMMIT", "ROLLBACK", "END"}) {
      String escaped = "INSERT INTO t VALUES (1, E'it\\'s');\n" + command + ";\n"
          + "INSERT INTO t VALUES (2, E'it\\'s');";
      assertEquals(1, SqlScript.split(escaped, false).size(), "kept as one statement");
      assertTrue(controls(escaped), command);
    }
    // Text that hides a COMMIT from the splitter but not from the driver: an escaped quote in
    // an E'...' string followed by --, and $$ at the end of a name, which starts no body.
    assertTrue(controls("INSERT INTO t VALUES (E'\\'--'); COMMIT; ALTER TABLE t ADD x INT;"));
    assertTrue(controls("INSERT INTO t VALUES (e'a\\\\\\'--'); COMMIT;"));
    assertTrue(controls("SELECT 1 AS x$$; COMMIT; SELECT 2 AS y$$;"));
    assertTrue(controls("SELECT 1 AS \"x\"; /* a /* nested */ comment */ COMMIT;"));
    // A carriage return ends a -- comment for PostgreSQL too.
    assertTrue(SqlScript.hasTransactionControl(List.of("SELECT 1; -- x\rCOMMIT; SELECT 2")));
    // Code the driver also reads as strings, names and comments stays plain.
    assertFalse(controls("INSERT INTO t VALUES ('a\\', 'COMMIT;');"));
    assertFalse(SqlScript.hasTransactionControl(List.of("SELECT E'x\\' COMMIT;'")));
    assertFalse(controls("SELECT a FROM t; /* x /* ; COMMIT; */ y */ SELECT 1;"));
    assertFalse(controls("SELECT 1 AS x$y; DO $b$ BEGIN COMMIT; END $b$;"));
    // A statement sent whole that holds a COMMIT.
    assertTrue(SqlScript.hasTransactionControl(List.of(
        "INSERT INTO t VALUES (1);\nCOMMIT;\nALTER TABLE t ADD COLUMN x INT;")));
  }

  @Test
  void looksInsideWhatTheSplitterTookForARoutine() {
    String trigger = "CREATE TRIGGER t AFTER INSERT ON x REFERENCING NEW TABLE AS n "
        + "FOR EACH STATEMENT EXECUTE FUNCTION f();\nCOMMIT;\nALTER TABLE x ADD COLUMN y INT;";
    assertTrue(controls(trigger));
  }

  @Test
  void ranHoldsTheMigrationAndItsCallbacksAsSent() {
    Migration before = Migration.ofSql("beforeEachMigrate.sql", "x");
    Migration migration = Migration.ofSql("V1__a.sql", "x");
    Migration after = Migration.ofSql("afterEachMigrate.sql", "x");
    Map<String, List<String>> statements = Map.of(
        before.version(), List.of("SET a = 1"),
        migration.version(), List.of("INSERT INTO t VALUES (1)"),
        after.version(), List.of("COMMIT"));
    Map<String, Migration> callbacks = Map.of(
        "beforeEachMigrate.sql", before, "afterEachMigrate.sql", after);

    List<String> ran = MigrationRunner.ran(migration, statements, callbacks);
    assertEquals(List.of("SET a = 1", "INSERT INTO t VALUES (1)", "COMMIT"), ran);
    assertTrue(SqlScript.hasTransactionControl(ran), "a callback's COMMIT counts");
    assertEquals(List.of("INSERT INTO t VALUES (1)"),
        MigrationRunner.ran(migration, statements, Map.of()));

    Migration java = Migration.ofJava(connection -> { });
    assertNull(MigrationRunner.ran(java, statements, Map.of()), "Java code may do anything");
    assertNull(MigrationRunner.ran(Migration.ofSql("V2__b.sql", "x"), statements, Map.of()));
  }
}
