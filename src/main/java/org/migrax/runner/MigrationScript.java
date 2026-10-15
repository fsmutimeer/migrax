package org.migrax.runner;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a migration plan as one SQL script for the database's own command line tool, so a DBA
 * can review and run it instead of 'migrax migrate'. Each migration is followed by the row
 * 'migrate' would record, so later runs see it as applied.
 */
final class MigrationScript {
  private enum Tool { POSTGRES, MYSQL, SQLSERVER, ORACLE, H2, SQLITE, OTHER }

  private final Tool tool;
  private final String timestamp;
  private final StringBuilder out = new StringBuilder();

  /**
   * @param product the database product name from the JDBC metadata
   * @param timestamp the SQL expression for the current time on this database
   */
  MigrationScript(String product, String timestamp) {
    String name = product.toLowerCase(Locale.ROOT);
    this.tool = name.contains("postgres") || name.contains("cockroach") ? Tool.POSTGRES
        : name.contains("mysql") || name.contains("mariadb") ? Tool.MYSQL
        : name.contains("sql server") ? Tool.SQLSERVER
        : name.contains("oracle") ? Tool.ORACLE
        : name.contains("h2") ? Tool.H2
        : name.contains("sqlite") ? Tool.SQLITE : Tool.OTHER;
    this.timestamp = timestamp;
  }

  /**
   * What the script does in one schema.
   *
   * @param schema the schema, or null for the connection's own
   * @param historyTable the history table's DDL when it doesn't exist there yet, else null
   * @param failureTable the failure table's DDL when it doesn't exist there yet, else null
   * @param stoppedAt the Java migration the plan stops before, or null
   * @param history the history table's name in SQL
   * @param qualified whether the statements name the schema themselves (SQL Server), so the
   *     script doesn't switch to it
   * @param statements each SQL migration's statements, placeholders filled in for this schema
   */
  record Part(String schema, List<MigrationRunner.Step> plan, String historyTable,
              String failureTable, String stoppedAt, String history, boolean qualified,
              Map<String, List<String>> statements) {}

  String write(List<Part> parts, Map<String, Migration> callbacks) {
    long applied = parts.stream().flatMap(part -> part.plan().stream())
        .filter(MigrationRunner.Apply.class::isInstance).count();
    if (parts.stream().allMatch(part -> part.plan().isEmpty() && part.stoppedAt() == null)) {
      return "-- Nothing to migrate: every migration is applied.\n";
    }
    out.append("-- Migration script written by Migrax: ").append(applied)
        .append(" migration(s) to apply")
        .append(parts.size() > 1 ? " in " + parts.size() + " schemas" : "").append(".\n");
    out.append("-- Review it, then run the whole file with the database's own tool; it must stop\n");
    out.append("-- at the first error:\n");
    out.append("--   ").append(runCommand()).append('\n');
    out.append("-- Each migration is recorded in migrax_history, so 'migrax status' and later\n");
    out.append("-- 'migrax migrate' runs see it as applied.\n");
    if (tool == Tool.MYSQL || tool == Tool.ORACLE) {
      out.append("-- This database commits DDL statements at once: if a migration fails halfway,\n");
      out.append("-- its earlier statements stay applied. Check the schema before running again.\n");
    }
    out.append('\n');
    if (tool == Tool.ORACLE) {
      out.append("WHENEVER SQLERROR EXIT FAILURE ROLLBACK\n\n");
    }
    for (Part part : parts) {
      part(part, callbacks);
    }
    if (tool == Tool.ORACLE) {
      out.append("EXIT\n");
    }
    return out.toString();
  }

  private void part(Part part, Map<String, Migration> callbacks) {
    Map<String, List<String>> statements = part.statements();
    if (part.schema() != null) {
      out.append("-- Schema ").append(part.schema()).append('\n');
      if (!part.qualified()) {
        out.append(terminated(useSchema(part.schema())));
      }
      out.append('\n');
      if (part.plan().isEmpty() && part.stoppedAt() == null) {
        out.append("-- Nothing to migrate in this schema.\n\n");
        return;
      }
    }
    String historyTable = part.historyTable();
    String failureTable = part.failureTable();
    List<MigrationRunner.Step> plan = part.plan();
    if (historyTable != null) {
      out.append("-- Migrax's history tables\n").append(terminated(historyTable));
    }
    if (failureTable != null) {
      out.append(terminated(failureTable));
    }
    if (historyTable != null || failureTable != null) {
      out.append('\n');
    }
    section("beforeMigrate.sql", callbacks.get("beforeMigrate.sql"), statements);
    for (MigrationRunner.Step step : plan) {
      if (step instanceof MigrationRunner.Apply apply) {
        migration(apply, part.history(), statements, callbacks);
      } else if (step instanceof MigrationRunner.Record record) {
        out.append("-- ").append(record.version())
            .append(": replaced by a squashed migration, recorded without running\n");
        out.append(terminated(insert(part.history(), record.version(),
            record.checksum())));
        commitForOracle();
        out.append('\n');
      }
    }
    section("afterMigrate.sql", callbacks.get("afterMigrate.sql"), statements);
    if (part.stoppedAt() != null) {
      out.append("-- Stopped before ").append(part.stoppedAt())
          .append(": a Java migration can't be written as SQL.\n")
          .append("-- After this script, run it with 'migrax migrate --to ")
          .append(part.stoppedAt()).append("',\n")
          .append("-- then write the script for the rest with 'migrax migrate --sql'.\n\n");
    }
  }

  /** The statement that makes {@code schema} the default for the statements after it. */
  private String useSchema(String schema) {
    return switch (tool) {
      case MYSQL -> "USE " + schema;
      case ORACLE -> "ALTER SESSION SET CURRENT_SCHEMA = " + schema;
      case H2 -> "SET SCHEMA " + schema;
      default -> "SET search_path TO " + schema;
    };
  }

  private void migration(MigrationRunner.Apply apply, String history,
                         Map<String, List<String>> statements,
                         Map<String, Migration> callbacks) {
    Migration migration = apply.migration();
    boolean transaction = !SqlScript.hasDirective(migration.sql(), "no-transaction")
        && tool != Tool.MYSQL && tool != Tool.ORACLE;
    out.append("-- ").append(migration.version())
        .append(transaction ? "" : " (runs without a transaction)").append('\n');
    if (transaction) {
      out.append(tool == Tool.SQLSERVER ? "BEGIN TRANSACTION\nGO\n" : "BEGIN;\n");
    }
    statements(callbacks.get("beforeEachMigrate.sql"), statements);
    statements(migration, statements);
    statements(callbacks.get("afterEachMigrate.sql"), statements);
    if (apply.replaceHistory()) {
      out.append(terminated("DELETE FROM " + history + " WHERE version=" + literal(
          migration.version())));
    }
    out.append(terminated(insert(history, migration.version(), migration.checksum())));
    if (transaction) {
      out.append(tool == Tool.SQLSERVER ? "COMMIT\nGO\n" : "COMMIT;\n");
    }
    commitForOracle();
    out.append('\n');
  }

  private void section(String name, Migration callback, Map<String, List<String>> statements) {
    if (callback != null) {
      out.append("-- ").append(name).append('\n');
      statements(callback, statements);
      out.append('\n');
    }
  }

  private void statements(Migration migration, Map<String, List<String>> statements) {
    if (migration != null) {
      statements.get(migration.version()).forEach(statement -> out.append(terminated(statement)));
    }
  }

  private void commitForOracle() {
    if (tool == Tool.ORACLE) {
      out.append("COMMIT;\n");
    }
  }

  /** A statement with the end marker its database's tool expects. */
  private String terminated(String statement) {
    return switch (tool) {
      case SQLSERVER -> statement + "\nGO\n";
      case ORACLE -> SqlScript.isPlsqlBlock(statement) ? statement + "\n/\n"
          : statement.replaceAll(";\\s*$", "") + ";\n";
      case MYSQL -> statement.contains(";") ? "DELIMITER $$\n" + statement + "$$\nDELIMITER ;\n"
          : statement + ";\n";
      default -> statement.endsWith(";") ? statement + "\n" : statement + ";\n";
    };
  }

  private String insert(String history, String version, String checksum) {
    return "INSERT INTO " + history + "(version,checksum,applied_at) VALUES(" + literal(version)
        + "," + literal(checksum) + "," + timestamp + ")";
  }

  private static String literal(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private String runCommand() {
    return switch (tool) {
      case POSTGRES -> "psql -v ON_ERROR_STOP=1 -f <this file>";
      case MYSQL -> "mysql <database> < <this file>";
      case SQLSERVER -> "sqlcmd -b -i <this file>";
      case ORACLE -> "sqlplus <user>@<database> @<this file>";
      case H2 -> "java -cp h2.jar org.h2.tools.RunScript -url <url> -script <this file>";
      case SQLITE -> "sqlite3 -bail <database file> < <this file>";
      default -> "your database's SQL tool, stopping at the first error";
    };
  }
}
