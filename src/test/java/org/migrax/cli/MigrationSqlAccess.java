package org.migrax.cli;

import org.migrax.dialect.Dialect;
import org.migrax.model.SchemaModel;
import org.migrax.ops.Operation;

import java.util.List;

/** Lets tests outside this package write migration SQL the way generate does. */
public final class MigrationSqlAccess {
  private MigrationSqlAccess() {}

  public static String render(Dialect dialect, List<Operation> operations, SchemaModel before,
                              SchemaModel after) {
    return MigrationSql.render(dialect, operations, null, before, after);
  }
}
