package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record CreateSequence(SchemaModel.Sequence sequence) implements Operation { public String kind(){return "create_sequence";} }
