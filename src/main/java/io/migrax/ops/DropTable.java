package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record DropTable(String table) implements Operation { public String kind(){return "drop_table";} }
