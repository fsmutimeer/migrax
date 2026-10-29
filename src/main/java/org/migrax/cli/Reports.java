package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.diff.Migrations;
import org.migrax.lint.SqlLinter;
import org.migrax.ops.AddColumn;
import org.migrax.ops.AddForeignKey;
import org.migrax.ops.AddIndex;
import org.migrax.ops.AddPrimaryKey;
import org.migrax.ops.AddUnique;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.CreateTable;
import org.migrax.ops.DropColumn;
import org.migrax.ops.DropForeignKey;
import org.migrax.ops.DropIndex;
import org.migrax.ops.DropPrimaryKey;
import org.migrax.ops.DropTable;
import org.migrax.ops.DropUnique;
import org.migrax.ops.Operation;
import org.migrax.ops.RenameColumn;
import org.migrax.ops.RenameTable;
import org.migrax.verify.TableStats;
import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Console and JSON output shared by several commands: planned operations and lint findings. */
final class Reports {
  private Reports() {}

  /** Numbered operations with their SQL, and table sizes when {@code stats} has them. */
  static void printOperations(PrintStream out, List<Operation> operations, Dialect dialect,
                              Map<String, TableStats> stats) {
    out.println(operations.size() + " change(s) for " + dialect.id() + ":");
    for (int i = 0; i < operations.size(); i++) {
      Operation operation = operations.get(i);
      out.printf("%3d. %s%s%n", i + 1, Migrations.summary(operation),
          operation.destructive() ? "  [DESTRUCTIVE]" : "");
      for (String line : dialect.render(operation).split("\n")) {
        out.println("     " + line + (line.endsWith(";") ? "" : ";"));
      }
      TableStats table = stats == null ? null : stats.get(table(operation));
      if (table != null) {
        out.println("     impact: " + table(operation) + " " + table.describe());
      }
    }
  }

  /** The table an operation touches, or null. */
  static String table(Operation o) {
    if (o instanceof CreateTable x) return x.table().name();
    if (o instanceof DropTable x) return x.table();
    if (o instanceof AddColumn x) return x.table();
    if (o instanceof DropColumn x) return x.table();
    if (o instanceof AlterColumn x) return x.table();
    if (o instanceof RenameColumn x) return x.table();
    if (o instanceof RenameTable x) return x.from();
    if (o instanceof AddIndex x) return x.table();
    if (o instanceof DropIndex x) return x.table();
    if (o instanceof AddForeignKey x) return x.table();
    if (o instanceof DropForeignKey x) return x.table();
    if (o instanceof org.migrax.ops.RenameForeignKey x) return x.table();
    if (o instanceof AddUnique x) return x.table();
    if (o instanceof org.migrax.ops.AddCheck x) return x.table();
    if (o instanceof org.migrax.ops.DropCheck x) return x.table();
    if (o instanceof org.migrax.ops.SetComment x) return x.table();
    if (o instanceof DropUnique x) return x.table();
    if (o instanceof AddPrimaryKey x) return x.table();
    if (o instanceof DropPrimaryKey x) return x.table();
    return null;
  }

  /** The column an operation adds, drops, changes or renames (its new name), or null. */
  static String column(Operation o) {
    if (o instanceof AddColumn x) return x.column().name();
    if (o instanceof DropColumn x) return x.column();
    if (o instanceof AlterColumn x) return x.after().name();
    if (o instanceof RenameColumn x) return x.to();
    return null;
  }

  /**
   * The entity class that owns the operation's table (the new name of a renamed table), or
   * null when no entity maps it, e.g. a dropped table.
   */
  static String entity(Operation o, Map<String, String> entities) {
    // A dropped table has no entity, even when an entity maps the same name in another case.
    String table = o instanceof RenameTable x ? x.to() : o instanceof DropTable ? null : table(o);
    if (table == null || entities.isEmpty()) {
      return null;
    }
    String entity = entities.get(table);
    if (entity != null) {
      return entity;
    }
    for (Map.Entry<String, String> e : entities.entrySet()) {
      if (e.getKey().equalsIgnoreCase(table)) {
        return e.getValue();
      }
    }
    return null;
  }

  /** One operation as JSON for {@code check}: what changes, where, and which entity owns it. */
  static Object operationJson(Operation o, Map<String, String> entities) {
    return JsonOut.object("kind", o.kind(), "summary", Migrations.summary(o),
        "table", table(o), "column", column(o), "entity", entity(o, entities));
  }

  static List<Object> findingsJson(List<SqlLinter.Finding> findings) {
    return findings.stream().map(f -> (Object) JsonOut.object("code", f.code(),
        "severity", f.severity(), "file", f.file(), "statement", f.statement(),
        "message", f.message(), "fix", f.fix(), "sql", f.sql())).toList();
  }

  static void printFindings(PrintStream out, List<SqlLinter.Finding> findings) {
    for (SqlLinter.Finding finding : findings) {
      out.println(finding.file() + ", statement " + finding.statement() + ": "
          + finding.severity().name().toLowerCase(Locale.ROOT) + " " + finding.code());
      out.println("  " + finding.sql());
      out.println("  " + finding.message());
      out.println("  Fix: " + finding.fix());
    }
  }
}
