package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record RunSql(String sql, boolean reversible) implements Operation { public String kind(){return "run_sql";} }
