package io.migrax.ops;

import io.migrax.model.SchemaModel;
import java.util.Locale;
import java.util.Objects;

/**
 * Changes a column's type, size or nullability.
 *
 * @since 0.1.0
 */
public record AlterColumn(String table, SchemaModel.Column before, SchemaModel.Column after)
    implements Operation {

  @Override
  public String kind() {
    return "alter_column";
  }

  /**
   * A type change, a smaller size or an identity change can fail or lose data, so it needs
   * explicit approval. Widening a column or changing nullability does not.
   */
  @Override
  public boolean destructive() {
    return typeChanged() || shrinks(before.length(), after.length(), 255)
        || shrinks(before.precision(), after.precision(), Integer.MAX_VALUE)
        || shrinks(before.scale(), after.scale(), Integer.MAX_VALUE)
        || before.identity() != after.identity();
  }

  /** True when the logical type or an explicit SQL type changed. */
  public boolean typeChanged() {
    return !Objects.equals(before.logicalType(), after.logicalType())
        || !Objects.equals(explicitType(before), explicitType(after));
  }

  /** True when length, precision or scale changed. */
  public boolean sizeChanged() {
    return !Objects.equals(SchemaModel.effectiveLength(before), SchemaModel.effectiveLength(after))
        || !Objects.equals(before.precision(), after.precision())
        || !Objects.equals(before.scale(), after.scale());
  }

  /** True when the column default changed. */
  public boolean defaultChanged() {
    return !Objects.equals(normalizeDefault(before.defaultValue()),
        normalizeDefault(after.defaultValue()));
  }

  /** Default expression compared without surrounding whitespace; blank means none. */
  public static String normalizeDefault(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  /** True when nullability changed. */
  public boolean nullabilityChanged() {
    return before.nullable() != after.nullable();
  }

  private static boolean shrinks(Integer before, Integer after, int defaultValue) {
    int old = before == null ? defaultValue : before;
    int now = after == null ? defaultValue : after;
    return now < old;
  }

  private static String explicitType(SchemaModel.Column column) {
    String sql = column.sqlType();
    if (sql == null || sql.equalsIgnoreCase(column.logicalType())) {
      return null;
    }
    return sql.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }
}
