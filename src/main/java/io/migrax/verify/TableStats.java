package io.migrax.verify;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Estimated size of a table from the database catalog, without scanning it.
 *
 * @param rows estimated rows, or -1 when unknown
 * @param bytes table plus index size in bytes, or -1 when unknown
 * @since 0.1.0
 */
public record TableStats(long rows, long bytes) {

  public static final TableStats UNKNOWN = new TableStats(-1, -1);

  /** Reads the estimate; returns {@link #UNKNOWN} when the table or statistics are missing. */
  public static TableStats estimate(Connection connection, String table) {
    try {
      String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
      if (product.contains("postgres")) {
        return query(connection, "SELECT c.reltuples::bigint, pg_total_relation_size(c.oid) "
            + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
            + "WHERE c.relname = ? AND n.nspname = current_schema()", table);
      }
      if (product.contains("mysql") || product.contains("mariadb")) {
        return query(connection, "SELECT table_rows, data_length + index_length "
            + "FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
            table);
      }
      if (product.contains("sql server")) {
        return query(connection, "SELECT SUM(p.rows), SUM(a.total_pages) * 8192 "
            + "FROM sys.partitions p JOIN sys.allocation_units a ON a.container_id = p.partition_id "
            + "WHERE p.object_id = OBJECT_ID(?) AND p.index_id IN (0, 1)", table);
      }
      if (product.contains("oracle")) {
        return query(connection, "SELECT t.num_rows, (SELECT SUM(bytes) FROM user_segments s "
            + "WHERE s.segment_name = t.table_name) FROM user_tables t WHERE t.table_name = ?",
            table.toUpperCase(Locale.ROOT));
      }
      if (product.contains("h2")) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT COUNT(*) FROM " + table.replaceAll("[^A-Za-z0-9_]", ""))) {
          try (ResultSet result = statement.executeQuery()) {
            return result.next() ? new TableStats(result.getLong(1), -1) : UNKNOWN;
          }
        }
      }
    } catch (SQLException e) {
      return UNKNOWN;
    }
    return UNKNOWN;
  }

  private static TableStats query(Connection connection, String sql, String table)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, table);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next()) {
          return UNKNOWN;
        }
        long rows = result.getLong(1);
        if (result.wasNull()) {
          rows = -1;
        }
        long bytes = result.getLong(2);
        if (result.wasNull()) {
          bytes = -1;
        }
        return new TableStats(Math.max(rows, -1), bytes);
      }
    }
  }

  /** Short text such as "~1.2M rows, 340 MB". */
  public String describe() {
    if (rows < 0 && bytes < 0) {
      return "size unknown";
    }
    StringBuilder text = new StringBuilder();
    if (rows >= 0) {
      text.append("~").append(compact(rows)).append(" rows");
    }
    if (bytes >= 0) {
      text.append(text.isEmpty() ? "" : ", ").append(bytes(bytes));
    }
    return text.toString();
  }

  private static String compact(long value) {
    if (value >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1fB", value / 1e9);
    if (value >= 1_000_000L) return String.format(Locale.ROOT, "%.1fM", value / 1e6);
    if (value >= 1_000L) return String.format(Locale.ROOT, "%.1fk", value / 1e3);
    return Long.toString(value);
  }

  private static String bytes(long value) {
    if (value >= 1L << 30) return String.format(Locale.ROOT, "%.1f GB", value / (double) (1L << 30));
    if (value >= 1L << 20) return String.format(Locale.ROOT, "%.0f MB", value / (double) (1L << 20));
    if (value >= 1L << 10) return String.format(Locale.ROOT, "%.0f kB", value / (double) (1L << 10));
    return value + " B";
  }
}
