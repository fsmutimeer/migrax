package org.migrax.runner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SqlScriptTransactionTest {
  @Test
  void findsTransactionsWrittenInAScript() {
    assertTrue(SqlScript.hasTransactionControl(
        "INSERT INTO t VALUES (1);\nCOMMIT;\nALTER TABLE t ADD COLUMN x INT;", false));
    assertTrue(SqlScript.hasTransactionControl("BEGIN;\nUPDATE t SET x = 1;\nEND;", false));
    assertTrue(SqlScript.hasTransactionControl("rollback work;", false));
    assertTrue(SqlScript.hasTransactionControl("START  TRANSACTION;", false));
  }

  @Test
  void countsWhatItCannotTellApart() {
    // A lone END commits on PostgreSQL.
    assertTrue(SqlScript.hasTransactionControl(
        "INSERT INTO t VALUES (1);\nEND;\nALTER TABLE t ADD COLUMN x INT;", false));
    assertTrue(SqlScript.hasTransactionControl("UPDATE t SET x = 1;\nEND WORK;", false));
    // Sent whole, so its COMMIT is not a statement of its own.
    assertTrue(SqlScript.hasTransactionControl("-- migrax:no-split\n"
        + "INSERT INTO t VALUES (1);\nCOMMIT;\nALTER TABLE t ADD COLUMN x INT;", false));
    // Can't be split.
    assertTrue(SqlScript.hasTransactionControl("INSERT INTO t VALUES ('open);", false));
  }

  @Test
  void ignoresBlocksSavepointsAndPlainScripts() {
    assertFalse(SqlScript.hasTransactionControl("ALTER TABLE t ADD COLUMN x INT;", false));
    assertFalse(SqlScript.hasTransactionControl(
        "SAVEPOINT a;\nUPDATE t SET x = 1;\nROLLBACK TO SAVEPOINT a;", false));
    assertFalse(SqlScript.hasTransactionControl("DO $$ BEGIN\n  UPDATE t SET x = 1;\nEND $$;",
        false));
    assertFalse(SqlScript.hasTransactionControl("-- COMMIT;\nSELECT 'COMMIT';", false));
  }
}
