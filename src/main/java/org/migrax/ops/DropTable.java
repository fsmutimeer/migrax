package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record DropTable(String table) implements Operation { public String kind(){return "drop_table";} }
