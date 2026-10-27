package org.migrax.diff;

import org.migrax.model.SchemaModel;
import org.migrax.ops.AddColumn;
import org.migrax.ops.AddForeignKey;
import org.migrax.ops.AddIndex;
import org.migrax.ops.AddPrimaryKey;
import org.migrax.ops.AddUnique;
import org.migrax.ops.AlterColumn;
import org.migrax.ops.CreateSequence;
import org.migrax.ops.CreateTable;
import org.migrax.ops.DropColumn;
import org.migrax.ops.DropForeignKey;
import org.migrax.ops.DropIndex;
import org.migrax.ops.DropPrimaryKey;
import org.migrax.ops.DropSequence;
import org.migrax.ops.DropTable;
import org.migrax.ops.DropUnique;
import org.migrax.ops.Operation;
import org.migrax.ops.RenameColumn;
import org.migrax.ops.RenameForeignKey;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Helpers shared by the CLI and the Maven plugin: migration file names, one-line operation
 * summaries and warnings about risky changes.
 *
 * @since 0.1.0
 */
public final class Migrations {
  private static final Pattern NUMBERED_MIGRATION = Pattern.compile("^(\\d+)_.*");

  private Migrations() {}

  /**
   * Next file name (without .sql) in the migration folder: {@code 0001_initial}
   * for the first migration, then {@code 0002_add_customer_phone} and so on.
   */
  public static String nextName(Path migrations, boolean baselineIsEmpty,
                                List<Operation> operations) throws IOException {
    return nextName(List.of(migrations), baselineIsEmpty, operations);
  }

  /**
   * The next migration name when migrations are read from several folders: its number follows
   * the highest number in any of them.
   *
   * @since 0.5.0
   */
  public static String nextName(List<Path> folders, boolean baselineIsEmpty,
                                List<Operation> operations) throws IOException {
    BigInteger latest = BigInteger.ZERO;
    for (Path migrations : folders) {
      latest = latest.max(latestNumber(migrations));
    }
    if (latest.signum() == 0 && baselineIsEmpty) {
      return "0001_initial";
    }
    String description = description(operations);
    if (description.isEmpty()) {
      description = "auto_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
    }
    return String.format(Locale.ROOT, "%04d_%s", latest.add(BigInteger.ONE), description);
  }

  // BigInteger: a file such as 99999999999_x.sql must neither crash nor wrap to a small number.
  private static BigInteger latestNumber(Path migrations) throws IOException {
    BigInteger latest = BigInteger.ZERO;
    if (Files.isDirectory(migrations)) {
      try (Stream<Path> paths = Files.list(migrations)) {
        for (Path path : paths.filter(file -> file.getFileName().toString().endsWith(".sql")).toList()) {
          String filename = path.getFileName().toString();
          Matcher matcher = NUMBERED_MIGRATION.matcher(filename.substring(0, filename.length() - 4));
          if (matcher.matches()) {
            latest = latest.max(new BigInteger(matcher.group(1)));
          } else {
            // V12__name.sql files, e.g. after 'migrax import'.
            List<BigInteger> version =
                org.migrax.runner.MigrationRunner.versionParts(filename);
            if (!version.isEmpty()) {
              latest = latest.max(version.get(0));
            }
          }
        }
      }
    }
    return latest;
  }

  /** Name from the first operations, e.g. {@code add_customer_phone_and_more}. */
  public static String description(List<Operation> operations) {
    List<String> parts = new ArrayList<>();
    for (Operation operation : operations) {
      String part = nameFragment(operation);
      if (part != null && !parts.contains(part)) {
        parts.add(part);
      }
    }
    if (parts.isEmpty()) {
      return "";
    }
    String name = parts.size() <= 2 ? String.join("_", parts) : parts.get(0) + "_and_more";
    name = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_");
    if (name.length() > 60) {
      name = name.substring(0, 60).replaceAll("_+$", "");
    }
    return name;
  }

  private static String nameFragment(Operation o) {
    if (o instanceof CreateTable x) return "create_" + x.table().name();
    if (o instanceof DropTable x) return "drop_" + x.table();
    if (o instanceof AddColumn x) return "add_" + x.table() + "_" + x.column().name();
    if (o instanceof DropColumn x) return "remove_" + x.table() + "_" + x.column();
    if (o instanceof AlterColumn x) return "alter_" + x.table() + "_" + x.after().name();
    if (o instanceof RenameColumn x) return "rename_" + x.table() + "_" + x.from();
    if (o instanceof org.migrax.ops.RenameTable x) return "rename_" + x.from();
    if (o instanceof AddIndex x) return "index_" + x.table();
    if (o instanceof DropIndex x) return "drop_index_" + x.table();
    if (o instanceof AddUnique x) return "unique_" + x.table() + "_" + x.column();
    if (o instanceof org.migrax.ops.AddCheck x) return "check_" + x.table();
    if (o instanceof org.migrax.ops.DropCheck x) return "drop_check_" + x.table();
    if (o instanceof org.migrax.ops.SetComment x) return "comment_" + x.table();
    if (o instanceof DropUnique x) return "remove_unique_" + x.table() + "_" + x.column();
    if (o instanceof CreateSequence x) return "create_" + x.sequence().name();
    if (o instanceof DropSequence x) return "drop_" + x.name();
    // Keys and constraints usually accompany one of the changes above.
    return null;
  }

  /** One-line summary of an operation, e.g. {@code add column customer.phone}. */
  public static String summary(Operation o) {
    if (o instanceof CreateTable x) return "create table " + x.table().name();
    if (o instanceof DropTable x) return "drop table " + x.table();
    if (o instanceof AddColumn x) return "add column " + x.table() + "." + x.column().name();
    if (o instanceof DropColumn x) return "drop column " + x.table() + "." + x.column();
    if (o instanceof AlterColumn x) return "alter column " + x.table() + "." + x.after().name();
    if (o instanceof RenameColumn x) {
      return "rename column " + x.table() + "." + x.from() + " to " + x.to();
    }
    if (o instanceof org.migrax.ops.RenameTable x) {
      return "rename table " + x.from() + " to " + x.to();
    }
    if (o instanceof AddPrimaryKey x) return "add primary key on " + x.table();
    if (o instanceof DropPrimaryKey x) return "drop primary key on " + x.table();
    if (o instanceof AddIndex x) return "add index " + x.index().name() + " on " + x.table();
    if (o instanceof DropIndex x) return "drop index " + x.name() + " on " + x.table();
    if (o instanceof AddForeignKey x) return "add foreign key " + x.fk().name() + " on " + x.table();
    if (o instanceof DropForeignKey x) return "drop foreign key " + x.name() + " on " + x.table();
    if (o instanceof RenameForeignKey x) {
      return "rename foreign key " + x.from() + " to " + x.key().name() + " on " + x.table();
    }
    if (o instanceof AddUnique x) return "add unique constraint on " + x.table() + "." + x.column();
    if (o instanceof org.migrax.ops.AddCheck x) {
      return "add check " + x.check().name() + " on " + x.table();
    }
    if (o instanceof org.migrax.ops.DropCheck x) {
      return "drop check " + x.name() + " on " + x.table();
    }
    if (o instanceof org.migrax.ops.SetComment x) {
      return (x.comment() == null ? "remove comment on " : "comment on ") + x.table()
          + (x.column() == null ? "" : "." + x.column().name());
    }
    if (o instanceof DropUnique x) {
      return "drop unique constraint on " + x.table() + "." + x.column();
    }
    if (o instanceof CreateSequence x) return "create sequence " + x.sequence().name();
    if (o instanceof DropSequence x) return "drop sequence " + x.name();
    return o.kind().replace('_', ' ');
  }

  /** Warnings about changes that may fail or block on a database with existing data. */
  public static List<String> warnings(List<Operation> operations, SchemaModel previous) {
    List<String> warnings = new ArrayList<>();
    for (Operation operation : operations) {
      if (operation instanceof AddColumn add
          && previous.table(add.table()) != null
          && !add.column().nullable()
          && add.column().defaultValue() == null
          && !add.column().identity()
          && add.column().sequenceName() == null) {
        warnings.add("Adding NOT NULL column '" + add.table() + "." + add.column().name()
            + "' without a default fails if the table has rows. Add a default in the SQL, or "
            + "add it as nullable, backfill, then make it NOT NULL.");
      }
      if (operation instanceof AlterColumn alter && alter.destructive()) {
        warnings.add("Changing the type or size of '" + alter.table() + "."
            + alter.after().name() + "' can fail or truncate data and may rewrite the table. "
            + "Test it on a copy of production data.");
      }
    }
    return warnings;
  }
}
