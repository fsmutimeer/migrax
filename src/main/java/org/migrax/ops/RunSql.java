package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record RunSql(String sql, boolean reversible) implements Operation { public String kind(){return "run_sql";} }
